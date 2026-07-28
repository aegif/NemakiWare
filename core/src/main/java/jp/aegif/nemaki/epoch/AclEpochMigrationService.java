package jp.aegif.nemaki.epoch;

import jp.aegif.nemaki.acl.AclSemantics;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.rag.acl.ACLExpander;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.CursorMarkParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The repository-wide initial {@code effective_acl_epoch} stamp — wiring gate 2's operational half
 * (design §8, increment 10).
 *
 * <p><b>Why this has to exist at all.</b> {@link AclEpochIndexWriter#write} refuses an UNFENCED
 * document: with no stored epoch there is nothing to compare against, so writing would be a guess
 * rather than a fence. Migration is the one caller allowed to see that state
 * ({@link AclEpochIndexWriter#stampInitialEpoch}) — and until every document has been through it,
 * every ACL update would fail closed. So the stamp is not a nicety; without it, wiring the writer
 * breaks all ACL updates on day one.
 *
 * <p><b>Run it AFTER the mandatory v3.3.0 full reindex.</b> The reindex rebuilds documents through
 * the content writer, whose fence PRESERVES whatever ACL group Solr already holds — and on a
 * freshly-rebuilt index there is nothing to preserve, so {@code readers} is stamped by that writer
 * while {@code effective_acl_epoch}, which only ever comes from {@link AclEpochIndexWriter}, is
 * left absent. Running the stamp first and
 * reindexing after would therefore discard the whole migration.
 *
 * <h3>Scope: CMIS objects only</h3>
 * The {@code nemaki} core holds three populations under one schema: CMIS objects, RAG parent-document
 * markers ({@code doc_type=document}, ids prefixed {@code rag:}) and RAG chunks
 * ({@code doc_type=chunk}). All three carry {@code readers}, but only the first are CMIS objects with
 * an ACL chain to walk; the RAG entries' tokens are maintained by the RAG path, and stamping them
 * would have the epoch writer claim a field another writer rewrites. The selector is therefore
 * {@code repository_id:{repo} AND -doc_type:[* TO *]} — a NEGATIVE test on the RAG discriminator,
 * deliberately: if a new CMIS field appeared, a positive test could silently EXCLUDE documents,
 * whereas this errs towards including one extra (which the walk then reports as
 * {@code SKIPPED_DELETED}). Missing a document is the dangerous direction; visiting a spare one is not.
 *
 * <h3>Restartability without a persistent cursor</h3>
 * Iteration is a Solr {@code cursorMark} over a set whose membership the run does not change (the
 * filter does not mention {@code effective_acl_epoch}), so pages cannot shift underfoot. Already-stamped
 * documents are recognised from the query's own {@code fl} and skipped WITHOUT a walk, which makes a
 * re-run cheap and makes "run it again" the whole recovery procedure — no cursor document to
 * corrupt, resume from, or validate.
 *
 * <h3>What it is NOT</h3>
 * Not leader-gated: it is an explicit operator action, not a poller. Two replicas racing is wasteful
 * but not unsafe — every write is a {@code _version_} CAS and the loser recomputes. It never deletes,
 * never touches CouchDB, and never writes any field outside the ACL group.
 */
public class AclEpochMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(AclEpochMigrationService.class);

    /** Solr page size. Each document costs an authoritative ancestor walk, so pages stay modest. */
    private static final int PAGE_SIZE = 200;
    /** Per-document failures recorded verbatim; beyond this only the counter grows. */
    private static final int MAX_RECORDED_ERRORS = 50;

    private AclEpochIndexWriter indexWriter;
    private SolrUtil solrUtil;
    private PrincipalService principalService;
    private jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repositoryInfoMap;
    private jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool connectorPool;

    public void setIndexWriter(AclEpochIndexWriter indexWriter) { this.indexWriter = indexWriter; }
    public void setSolrUtil(SolrUtil solrUtil) { this.solrUtil = solrUtil; }
    public void setPrincipalService(PrincipalService principalService) { this.principalService = principalService; }
    public void setRepositoryInfoMap(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap m) {
        this.repositoryInfoMap = m;
    }

    public void setConnectorPool(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool p) {
        this.connectorPool = p;
    }

    private final Map<String, Progress> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(
            Thread.ofVirtual().name("acl-epoch-migration-", 0).factory());

    /** Live counters for one repository's run. Every field is read by the status endpoint. */
    public static final class Progress {
        public volatile String repositoryId;
        public volatile String status = "RUNNING";  // RUNNING | COMPLETED | FAILED
        public volatile long startedAt;
        public volatile long finishedAt;
        public volatile long scanned;
        /** Newly fenced by this run. */
        public volatile long stamped;
        /** Already carried an epoch — skipped WITHOUT a walk. */
        public volatile long alreadyStamped;
        /** The walk found no such object in CouchDB (a Solr entry outliving its content). */
        public volatile long skippedDeleted;
        /** Vanished from Solr between the page read and the write. */
        public volatile long notIndexed;
        /** Blocked by a quarantined dependency — repair it, then re-run (§5.1). */
        public volatile long quarantineBlocked;
        public volatile long failed;
        public final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        /** Distinct quarantined ids blocking this run — the ids to repair. */
        public final List<String> quarantineBlockingIds = Collections.synchronizedList(new ArrayList<>());
        public volatile String errorMessage;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repositoryId", repositoryId);
            m.put("status", status);
            m.put("startedAt", startedAt);
            m.put("finishedAt", finishedAt);
            m.put("scanned", scanned);
            m.put("stamped", stamped);
            m.put("alreadyStamped", alreadyStamped);
            m.put("skippedDeleted", skippedDeleted);
            m.put("notIndexed", notIndexed);
            m.put("quarantineBlocked", quarantineBlocked);
            m.put("quarantineBlockingIds", List.copyOf(quarantineBlockingIds));
            m.put("failed", failed);
            m.put("errors", List.copyOf(errors));
            m.put("errorMessage", errorMessage);
            return m;
        }
    }

    /**
     * Start the stamp for one repository. Returns {@code false} if a run is already in flight for it
     * — concurrent runs would be safe (every write is a CAS) but the progress counters would be
     * nonsense, and an operator reading them is the entire point of the endpoint.
     */
    public boolean start(String repositoryId) {
        requireKnownRepository(repositoryId);
        requireWiring();
        Progress fresh = new Progress();
        fresh.repositoryId = repositoryId;
        fresh.startedAt = System.currentTimeMillis();
        Progress prior = runs.compute(repositoryId, (k, existing) ->
                (existing != null && "RUNNING".equals(existing.status)) ? existing : fresh);
        if (prior != fresh) {
            return false; // already running
        }
        try {
            executor.submit(() -> {
            try {
                run(repositoryId, fresh);
                fresh.status = "COMPLETED";
            } catch (RuntimeException e) {
                // A run-level fault (Solr unreachable, a bean missing) — NOT a per-document failure,
                // which is counted and stepped over inside run().
                fresh.status = "FAILED";
                fresh.errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
                logger.error("ACL-epoch migration FAILED for '{}': {}", repositoryId, e.getMessage(), e);
            } finally {
                fresh.finishedAt = System.currentTimeMillis();
                logger.info("ACL-epoch migration {} for '{}': scanned={}, stamped={}, alreadyStamped={}, "
                                + "skippedDeleted={}, notIndexed={}, quarantineBlocked={}, failed={}",
                        fresh.status, repositoryId, fresh.scanned, fresh.stamped, fresh.alreadyStamped,
                        fresh.skippedDeleted, fresh.notIndexed, fresh.quarantineBlocked, fresh.failed);
            }
            });
        } catch (RuntimeException e) {
            // A rejected submission (the executor was shut down) must NOT leave the entry RUNNING:
            // `start` would then answer "already running" for ever and the endpoint would be dead
            // until a JVM restart, with no run to observe.
            fresh.status = "FAILED";
            fresh.errorMessage = "could not start: " + e;
            fresh.finishedAt = System.currentTimeMillis();
            throw e;
        }
        return true;
    }

    /** Release the executor on undeploy; an in-flight run must not outlive the context. */
    public void stop() {
        executor.shutdownNow();
    }

    /**
     * Reject a repository id that is not configured.
     *
     * <p>Without this the runner is a TRAP rather than a verification: a typo'd id matches no Solr
     * document, so the run completes with {@code scanned: 0} and {@code remainingUnfenced: 0}, and
     * the endpoint answers {@code verdict: COMPLETE, fenced: true} for a repository it never
     * touched. Confirmed against the dev stack before this guard existed — an operator would have
     * concluded gate 2 was closed for a repository still entirely unfenced.
     */
    private void requireKnownRepository(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        if (repositoryInfoMap == null) {
            throw new AclEpochWiringException("repositoryInfoMap not wired on AclEpochMigrationService "
                    + "— it is REQUIRED: without it an unknown repository id reports a COMPLETE "
                    + "migration it never performed");
        }
        if (!repositoryInfoMap.contains(repositoryId)) {
            throw new IllegalArgumentException("unknown repository '" + repositoryId + "' — configured: "
                    + repositoryInfoMap.keys());
        }
    }

    /** The last run's progress for this repository, or {@code null} if it has never been started. */
    public Progress status(String repositoryId) {
        return runs.get(repositoryId);
    }

    /** What the numbers MEAN for the wiring decision — see {@link #verdict}. */
    public enum Verdict {
        /** No run recorded for this repository in this JVM. */
        NOT_RUN,
        RUNNING,
        /** The run hit a run-level fault (Solr down, a bean missing). Nothing can be concluded. */
        FAILED,
        /** Every CMIS object in the index carries an epoch. */
        COMPLETE,
        /**
         * Everything that CAN be fenced IS fenced; what remains is ORPHANED index entries — Solr
         * documents whose CouchDB content is authoritatively gone (a 404, not a read failure).
         *
         * <p>This is a complete migration, not a partial one. An orphan can never be stamped, and it
         * can never be the target of {@link AclEpochIndexWriter#write} either: that is called on an
         * ACL mutation of an EXISTING object. So orphans do not block wiring — but they are stale
         * index entries worth cleaning up separately, and a permanently non-zero
         * {@code remainingUnfenced} would otherwise read as "the migration never finished".
         */
        COMPLETE_EXCEPT_ORPHANS,
        /** Documents remain that could have been fenced — re-run (after repairing any blockers). */
        INCOMPLETE,
        /**
         * The repository has NO CMIS objects in the index at all — so "nothing left to fence" says
         * nothing about the migration.
         *
         * <p>This is the documented order mistake wearing a different hat. Running the stamp BEFORE
         * the mandatory full reindex visits an empty index: {@code scanned: 0}, nothing fails, and
         * {@code remainingUnfenced} is 0 — which the earlier verdict logic read as COMPLETE. The
         * operator would record the gate as closed, then run the reindex that makes every document
         * unfenced again. Reporting the emptiness is the only honest answer.
         */
        EMPTY_INDEX
    }

    /**
     * The wiring-relevant reading of the last run plus the live Solr count.
     *
     * <p>{@code remainingUnfenced == 0} is NOT the criterion, because it is unreachable whenever the
     * index holds an entry whose content has been deleted. The criterion is that every document with
     * content is fenced, nothing failed, and nothing is blocked by a quarantine.
     */
    public Verdict verdict(String repositoryId, long remainingUnfenced, long indexedCmisObjects) {
        Progress p = runs.get(repositoryId);
        if (p == null) return Verdict.NOT_RUN;
        if ("RUNNING".equals(p.status)) return Verdict.RUNNING;
        if ("FAILED".equals(p.status)) return Verdict.FAILED;
        // BEFORE the zero-remaining check: an empty index also has zero remaining, and calling that
        // COMPLETE is how "stamp first, reindex second" gets recorded as a closed gate.
        if (indexedCmisObjects == 0) return Verdict.EMPTY_INDEX;
        if (remainingUnfenced == 0) return Verdict.COMPLETE;
        if (p.failed == 0 && p.quarantineBlocked == 0 && remainingUnfenced == p.skippedDeleted) {
            return Verdict.COMPLETE_EXCEPT_ORPHANS;
        }
        return Verdict.INCOMPLETE;
    }

    /**
     * How many CMIS objects in this repository carry no epoch. Read straight from Solr, so it is an
     * independent check on the run's own counters rather than a restatement of them — but read it
     * through {@link #verdict}, not as "work remaining": an orphaned index entry keeps it above zero
     * for ever.
     */
    public long remainingUnfenced(String repositoryId) {
        return count(repositoryId, "-" + AclEpochIndexWriter.FIELD_EFFECTIVE_EPOCH + ":[* TO *]");
    }

    /**
     * How many CMIS objects this repository has in the index AT ALL.
     *
     * <p>Read alongside {@link #remainingUnfenced} because zero-remaining is ambiguous on its own:
     * a repository that has not been reindexed yet also has nothing left to fence.
     */
    public long indexedCmisObjects(String repositoryId) {
        return count(repositoryId, null);
    }

    private long count(String repositoryId, String extraFilter) {
        requireKnownRepository(repositoryId);
        requireWiring();
        SolrClient client = solrClient();
        SolrQuery q = new SolrQuery("*:*");
        q.addFilterQuery(cmisObjectFilter(repositoryId));
        if (extraFilter != null) {
            q.addFilterQuery(extraFilter);
        }
        q.setRows(0);
        try {
            return client.query(q).getResults().getNumFound();
        } catch (Exception e) {
            throw new IllegalStateException("could not count documents in '" + repositoryId
                    + "': " + e.getMessage(), e);
        }
    }

    // ── Internal ───────────────────────────────────────────────────

    private void run(String repositoryId, Progress p) {
        SolrClient client = solrClient();
        AclSemantics.PrincipalResolver resolver = ACLExpander.principalResolver(principalService);
        String cursor = CursorMarkParams.CURSOR_MARK_START;
        while (true) {
            SolrQuery q = new SolrQuery("*:*");
            q.addFilterQuery(cmisObjectFilter(repositoryId));
            // `id` ASC is a total order on a unique key — cursorMark requires one, and it is also
            // what makes a restart deterministic.
            q.setSort("id", SolrQuery.ORDER.asc);
            q.setFields("id", AclEpochIndexWriter.FIELD_EFFECTIVE_EPOCH);
            q.setRows(PAGE_SIZE);
            q.set(CursorMarkParams.CURSOR_MARK_PARAM, cursor);
            QueryResponse resp;
            try {
                resp = client.query(q);
            } catch (Exception e) {
                throw new IllegalStateException("Solr page query failed for '" + repositoryId
                        + "': " + e.getMessage(), e);
            }
            for (SolrDocument doc : resp.getResults()) {
                p.scanned++;
                Object id = doc.getFieldValue("id");
                if (id == null) {
                    continue;
                }
                if (doc.getFieldValue(AclEpochIndexWriter.FIELD_EFFECTIVE_EPOCH) != null) {
                    // Already fenced. Skipped WITHOUT a walk — this is what makes a re-run cheap
                    // enough to be the recovery procedure.
                    p.alreadyStamped++;
                    continue;
                }
                stampOne(repositoryId, id.toString(), client, resolver, p);
            }
            String next = resp.getNextCursorMark();
            if (next == null || next.equals(cursor)) {
                makeTheWritesVISIBLE(client);
                return; // Solr's own end-of-iteration signal
            }
            cursor = next;
        }
    }

    /**
     * Soft-commit once at the END of a run, so the operator's very next status poll tells the truth.
     *
     * <p>{@code remainingUnfenced} is a SEARCHER query while the stamps are atomic updates, and the
     * core's {@code autoSoftCommit} is 3s — so a poll issued right after the run (the natural thing
     * to do, and what a script does) counts documents that have already been fenced and reports
     * INCOMPLETE. Observed on the dev stack: {@code canopy} read {@code remainingUnfenced: 1,
     * verdict: INCOMPLETE} 100ms after stamping its only document, and {@code 0, COMPLETE} twenty
     * seconds later. An operator would have re-run a completed migration, or worse, concluded the
     * stamp had not worked.
     *
     * <p>ONE soft commit per run, not per document, and a failure here is not a failure of the run:
     * the writes are already durable in the update log: {@code autoSoftCommit} will expose them
     * regardless. So it is logged and stepped over.
     */
    private void makeTheWritesVISIBLE(SolrClient client) {
        try {
            client.commit(false, false, true); // waitFlush=false, waitSearcher=false, softCommit=true
        } catch (Exception e) {
            logger.warn("ACL-epoch migration finished but the closing soft commit failed ({}) — the "
                    + "stamps ARE durable; the status count just lags until autoSoftCommit", e.getMessage());
        }
    }

    private void stampOne(String repositoryId, String objectId, SolrClient client,
                          AclSemantics.PrincipalResolver resolver, Progress p) {
        try {
            AclEpochIndexWriter.WriteOutcome outcome =
                    indexWriter.stampInitialEpoch(repositoryId, objectId, client, resolver);
            switch (outcome.result) {
                case UPDATED -> p.stamped++;
                case SKIPPED_DELETED -> p.skippedDeleted++;
                case NOT_INDEXED -> p.notIndexed++;
                // SKIPPED_IDEMPOTENT cannot happen in bootstrap mode for an ABSENT epoch (the writer
                // forces the write so the document does not stay unfenced while reporting success),
                // so reaching it means the document was stamped concurrently. Count it as already
                // done rather than inventing a category.
                case SKIPPED_IDEMPOTENT, SKIPPED_FRESHER -> p.alreadyStamped++;
            }
        } catch (AclEpochQuarantineBlockedException e) {
            // Not this document's fault and not fixed by retrying — a human repairs the blocker and
            // re-runs. Recording the distinct blocking ids is the actionable part; one quarantined
            // folder can account for every failure in the run.
            p.quarantineBlocked++;
            String blocker = e.getQuarantinedId();
            if (blocker != null) {
                synchronized (p.quarantineBlockingIds) {
                    if (!p.quarantineBlockingIds.contains(blocker)) {
                        p.quarantineBlockingIds.add(blocker);
                    }
                }
            }
        } catch (AclEpochWiringException e) {
            // A missing bean is not a per-document problem: every remaining document would fail the
            // same way, and burying that in a `failed` count would look like data corruption.
            throw e;
        } catch (Exception e) {
            p.failed++;
            if (p.errors.size() < MAX_RECORDED_ERRORS) {
                p.errors.add(objectId + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            logger.warn("ACL-epoch stamp failed for {}/{}: {}", repositoryId, objectId, e.getMessage());
        }
    }

    /** One orphan candidate: an unfenced index entry whose CouchDB content is definitively gone. */
    public static final class OrphanRef {
        public final String id;
        public final Long solrVersion;
        OrphanRef(String id, Long solrVersion) { this.id = id; this.solrVersion = solrVersion; }
        public String getId() { return id; }
    }

    /** Dry-run result of an orphan scan. */
    public static final class OrphanScan {
        public final java.util.List<OrphanRef> verifiedOrphans;
        /** Entries whose CouchDB read ERRORED (not 404) — NEVER deletable on this evidence. */
        public final int unverifiable;
        /** Unfenced entries whose content EXISTS (not orphans — the migration simply has not covered them). */
        public final int liveUnfenced;
        OrphanScan(java.util.List<OrphanRef> v, int u, int l) {
            this.verifiedOrphans = java.util.Collections.unmodifiableList(v);
            this.unverifiable = u;
            this.liveUnfenced = l;
        }
    }

    /**
     * Find the ORPHANED index entries: UNFENCED CMIS-population Solr documents whose CouchDB
     * content is a definitive 404 (increment 13; the residual behind
     * {@code COMPLETE_EXCEPT_ORPHANS}).
     *
     * <p>Scoped to the UNFENCED set on purpose: that is the population that keeps
     * {@code remainingUnfenced} above zero for ever, and probing every document in a large
     * repository would be one CouchDB GET per indexed object. FAIL-CLOSED per entry: only a
     * {@code NotFoundException} counts as gone — any other read outcome is {@code unverifiable}
     * and is never deleted on that evidence.
     */
    public OrphanScan scanOrphans(String repositoryId, int limit) {
        requireKnownRepository(repositoryId);
        requireWiring();
        if (connectorPool == null) {
            throw new AclEpochWiringException("connectorPool not wired on AclEpochMigrationService "
                    + "— orphan verification needs an authoritative CouchDB read");
        }
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper couch =
                connectorPool.getClient(repositoryId);
        if (couch == null) {
            throw new AclEpochWiringException("content DB client not available for '" + repositoryId + "'");
        }
        SolrClient client = solrClient();
        int capped = Math.min(Math.max(1, limit), 5000);
        java.util.List<OrphanRef> verified = new ArrayList<>();
        int unverifiable = 0, live = 0;
        String cursor = org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_START;
        int examined = 0;
        while (examined < capped) {
            SolrQuery q = new SolrQuery("*:*");
            q.addFilterQuery(cmisObjectFilter(repositoryId));
            q.addFilterQuery("-" + AclEpochIndexWriter.FIELD_EFFECTIVE_EPOCH + ":[* TO *]");
            q.setSort("id", SolrQuery.ORDER.asc);
            q.setFields("id", "_version_");
            q.setRows(Math.min(PAGE_SIZE, capped - examined));
            q.set(org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_PARAM, cursor);
            QueryResponse resp;
            try {
                resp = client.query(q);
            } catch (Exception e) {
                throw new IllegalStateException("orphan scan query failed for '" + repositoryId
                        + "': " + e.getMessage(), e);
            }
            for (SolrDocument d : resp.getResults()) {
                examined++;
                String id = String.valueOf(d.getFieldValue("id"));
                Object v = d.getFieldValue("_version_");
                Long version = (v instanceof Number) ? ((Number) v).longValue() : null;
                try {
                    couch.getClient().getDocument(
                            new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
                                    .db(couch.getDatabaseName()).docId(id).build()).execute();
                    live++; // content exists — NOT an orphan
                } catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException nf) {
                    verified.add(new OrphanRef(id, version)); // definitively gone
                } catch (RuntimeException e) {
                    unverifiable++; // read ERROR ≠ absence — fail closed
                }
                if (examined >= capped) {
                    break;
                }
            }
            String next = resp.getNextCursorMark();
            if (next == null || next.equals(cursor) || resp.getResults().isEmpty()) {
                break;
            }
            cursor = next;
        }
        return new OrphanScan(verified, unverifiable, live);
    }

    /** Result of an orphan deletion pass. */
    public static final class OrphanDeleteResult {
        public final int deleted;
        public final int skippedConcurrentlyChanged;
        public final int unverifiable;
        public final int liveUnfenced;
        OrphanDeleteResult(int d, int s, int u, int l) {
            this.deleted = d; this.skippedConcurrentlyChanged = s;
            this.unverifiable = u; this.liveUnfenced = l;
        }
    }

    /**
     * DELETE the verified orphans (increment 13). DESTRUCTIVE — the controller demands
     * {@code ?confirm=true}; this method assumes that gate has been passed.
     *
     * <p>Each delete is conditional on the {@code _version_} read during the scan: an entry that
     * changed since (e.g. an archive RESTORE reusing the {@code _id} re-indexed it) fails its CAS
     * and is skipped, never deleted over fresh content. Deleting an index entry has no
     * authorization effect in either direction — absence from the index is fail-closed — so the
     * worst outcome of any residual race is a missing search hit until the object's next write.
     */
    public OrphanDeleteResult deleteOrphans(String repositoryId, int limit) {
        OrphanScan scan = scanOrphans(repositoryId, limit);
        SolrClient client = solrClient();
        int deleted = 0, skipped = 0;
        for (OrphanRef ref : scan.verifiedOrphans) {
            try {
                org.apache.solr.client.solrj.request.UpdateRequest req =
                        new org.apache.solr.client.solrj.request.UpdateRequest();
                if (ref.solrVersion != null) {
                    req.deleteById(ref.id, ref.solrVersion);
                } else {
                    req.deleteById(ref.id);
                }
                req.process(client);
                deleted++;
            } catch (Exception e) {
                // A version conflict (concurrent re-index) or a transient failure — skipped, not
                // retried here: re-running the endpoint is the retry.
                skipped++;
                logger.info("orphan delete skipped for {} ({})", ref.id, e.getMessage());
            }
        }
        if (deleted > 0) {
            try {
                client.commit(false, false, true); // soft commit: the status counts tell the truth
            } catch (Exception e) {
                logger.warn("orphan delete soft commit failed ({}); autoSoftCommit will catch up",
                        e.getMessage());
            }
        }
        return new OrphanDeleteResult(deleted, skipped, scan.unverifiable, scan.liveUnfenced);
    }

    /**
     * CMIS objects only — see the class Javadoc on why this excludes the RAG populations by a
     * NEGATIVE test on their own discriminator.
     */
    static String cmisObjectFilter(String repositoryId) {
        return "repository_id:\"" + repositoryId.replace("\"", "\\\"") + "\" AND -doc_type:[* TO *]";
    }

    private SolrClient solrClient() {
        SolrClient client = solrUtil.getSolrClient();
        if (client == null) {
            throw new AclEpochWiringException("Solr client unavailable — cannot run the ACL-epoch migration");
        }
        return client;
    }

    private void requireWiring() {
        if (indexWriter == null) {
            throw new AclEpochWiringException("indexWriter not wired on AclEpochMigrationService");
        }
        if (solrUtil == null) {
            throw new AclEpochWiringException("solrUtil not wired on AclEpochMigrationService");
        }
        if (principalService == null) {
            throw new AclEpochWiringException("principalService not wired on AclEpochMigrationService");
        }
    }
}
