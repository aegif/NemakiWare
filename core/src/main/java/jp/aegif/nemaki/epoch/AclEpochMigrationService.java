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
 * freshly-rebuilt index there is nothing to preserve, so {@code readers} and
 * {@code acl_index_generation} are stamped by that writer while {@code effective_acl_epoch}, which
 * only ever comes from {@link AclEpochIndexWriter}, is left absent. Running the stamp first and
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

    public void setIndexWriter(AclEpochIndexWriter indexWriter) { this.indexWriter = indexWriter; }
    public void setSolrUtil(SolrUtil solrUtil) { this.solrUtil = solrUtil; }
    public void setPrincipalService(PrincipalService principalService) { this.principalService = principalService; }

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
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        requireWiring();
        Progress fresh = new Progress();
        fresh.repositoryId = repositoryId;
        fresh.startedAt = System.currentTimeMillis();
        Progress prior = runs.compute(repositoryId, (k, existing) ->
                (existing != null && "RUNNING".equals(existing.status)) ? existing : fresh);
        if (prior != fresh) {
            return false; // already running
        }
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
        return true;
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
        INCOMPLETE
    }

    /**
     * The wiring-relevant reading of the last run plus the live Solr count.
     *
     * <p>{@code remainingUnfenced == 0} is NOT the criterion, because it is unreachable whenever the
     * index holds an entry whose content has been deleted. The criterion is that every document with
     * content is fenced, nothing failed, and nothing is blocked by a quarantine.
     */
    public Verdict verdict(String repositoryId, long remainingUnfenced) {
        Progress p = runs.get(repositoryId);
        if (p == null) return Verdict.NOT_RUN;
        if ("RUNNING".equals(p.status)) return Verdict.RUNNING;
        if ("FAILED".equals(p.status)) return Verdict.FAILED;
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
        requireWiring();
        SolrClient client = solrClient();
        SolrQuery q = new SolrQuery("*:*");
        q.addFilterQuery(cmisObjectFilter(repositoryId));
        q.addFilterQuery("-" + AclEpochIndexWriter.FIELD_EFFECTIVE_EPOCH + ":[* TO *]");
        q.setRows(0);
        try {
            return client.query(q).getResults().getNumFound();
        } catch (Exception e) {
            throw new IllegalStateException("could not count unfenced documents in '" + repositoryId
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
