package jp.aegif.nemaki.epoch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.service.exception.ConflictException;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-commit epoch finalization + crash-recovery scanner (design
 * {@code docs/design/acl-epoch-fencing.md} §2.2 / §3 — increment 2 + review 2a).
 *
 * <p>Phase 2 of the two-phase mutation: a content document already committed in
 * {@link AclEpochState#PENDING_EPOCH} (with an {@code aclEpochMutationId}) is finalized by
 * allocating an epoch and CAS-patching it to {@link AclEpochState#FINALIZED_NEEDS_RECONCILE}
 * — ONLY while the mutation id still matches. The scanner recovers a crash between Phase 1
 * and Phase 2, always draining {@code PENDING_EPOCH} FIRST (so it can never be starved by
 * an accumulation of parked {@code FINALIZED_NEEDS_RECONCILE} documents), and STOPS at
 * {@code FINALIZED_NEEDS_RECONCILE} (the enqueue/ACK is a later increment).
 *
 * <p><b>Fail-closed staging (increment-2 acceptance conditions):</b> NOT auto-started
 * (plain bean, no scheduler / init / cron; zero production callers); NO side effects (never
 * touches Solr, reconcile tasks, or the ACL cache); never selects or initializes a
 * state-less document; every anomaly (unknown / non-String state, a missing mutation id, an
 * invalid epoch) is RECORDED and the document LEFT UNPROCESSED, never silently skipped —
 * including for states OUTSIDE the two live states (a bounded audit pass surfaces them).
 */
public class AclEpochFinalizationService {

    private static final Logger logger = LoggerFactory.getLogger(AclEpochFinalizationService.class);

    /** Default PER-PASS processing budget (each of the four passes is bounded independently). */
    public static final int DEFAULT_SCAN_MAX_DOCS = 500;
    private static final int PAGE_SIZE = 100;
    private static final int FINALIZE_CAS_RETRIES = 8;
    private static final int QUARANTINE_CAS_RETRIES = 4;

    private CloudantClientPool connectorPool;
    private AclEpochCounterService counterService;

    public void setConnectorPool(CloudantClientPool connectorPool) { this.connectorPool = connectorPool; }
    public void setCounterService(AclEpochCounterService counterService) { this.counterService = counterService; }

    // ── outcome types ──────────────────────────────────────────────

    public enum FinalizeResult {
        /** The epoch was allocated and CAS-committed to FINALIZED_NEEDS_RECONCILE. */
        FINALIZED,
        /** Not an epoch document, or not in PENDING_EPOCH (already finalized/advanced) — nothing to do. */
        SKIPPED_NOT_PENDING,
        /** Was PENDING but a newer mutation / another finalizer superseded it before our CAS —
         *  this attempt's allocated epoch is abandoned (a safe gap). */
        ABANDONED_SUPERSEDED
    }

    public static final class FinalizeOutcome {
        public final FinalizeResult result;
        public final Long epoch; // non-null only for FINALIZED
        FinalizeOutcome(FinalizeResult result, Long epoch) { this.result = result; this.epoch = epoch; }
    }

    public static final class ScanSummary {
        public int scanned;
        public int finalized;
        public int awaitingReconcile;
        public int enqueued;             // valid RECONCILE_ENQUEUED docs seen by the terminal audit
        public int quarantined;          // anomalous docs moved to durable quarantine this scan
        public int quarantineFailures;   // anomalies that could NOT be durably quarantined this scan
        public boolean more;             // hit a pass's budget OR a quarantine write failed → re-scan
        public final List<Map<String, String>> errors = new ArrayList<>();
    }

    /** Anomaly in the epoch outbox data — fail-closed (record + retain), never silently skipped. */
    public static final class AclEpochAnomalyException extends RuntimeException {
        public AclEpochAnomalyException(String message) { super(message); }
    }

    /** Validated epoch fields of one document. */
    private static final class EpochFields {
        final String state;
        final String mutationId;
        final Long epoch; // non-null for FINALIZED / ENQUEUED
        EpochFields(String state, String mutationId, Long epoch) {
            this.state = state; this.mutationId = mutationId; this.epoch = epoch;
        }
    }

    /**
     * Validate a document as a live epoch doc: it must NOT be quarantined (or carry a
     * malformed quarantine marker) AND its epoch fields must be valid. Shared by finalize,
     * the 409 re-read, and the scan passes so "valid" has one definition, and so a
     * quarantined document is fail-closed-rejected everywhere (a direct finalizer must never
     * finalize a quarantined document — review 2d [P1]).
     *
     * @throws AclEpochAnomalyException if quarantined / the marker is malformed / the epoch
     *         fields are anomalous
     */
    private static EpochFields validate(Document doc) {
        Object q = doc.getProperties() != null ? doc.getProperties().get(AclEpochState.FIELD_QUARANTINED) : null;
        if (q != null) {
            if (Boolean.TRUE.equals(q)) {
                throw new AclEpochAnomalyException("document is quarantined on " + doc.getId());
            }
            throw new AclEpochAnomalyException("malformed aclEpochQuarantined marker (not Boolean true) on "
                    + doc.getId());
        }
        return validateEpochFields(doc);
    }

    /**
     * Strictly validate ONLY the epoch fields (state / mutationId / epoch), ignoring the
     * quarantine marker. Used by {@link #quarantine} to decide whether a document is STILL
     * anomalous on re-read (a repaired document must not be quarantined — review 2d [P1]).
     *
     * @throws AclEpochAnomalyException on a non-String / unknown state, a missing / non-UUID
     *         mutation id, or (for FINALIZED / ENQUEUED) an invalid epoch
     */
    private static EpochFields validateEpochFields(Document doc) {
        Map<String, Object> props = doc.getProperties();
        Object rawState = props != null ? props.get(AclEpochState.FIELD_STATE) : null;
        if (!(rawState instanceof String)) {
            throw new AclEpochAnomalyException("missing / non-String aclEpochState on " + doc.getId());
        }
        String state = (String) rawState;
        if (!AclEpochState.isKnown(state)) {
            throw new AclEpochAnomalyException("unknown aclEpochState '" + state + "' on " + doc.getId());
        }
        Object rawMut = props.get(AclEpochState.FIELD_MUTATION_ID);
        String mutationId = (rawMut instanceof String && !((String) rawMut).isBlank())
                ? (String) rawMut : null;
        // All three states carry a mutation id (Phase 1 always writes it) and it MUST be a
        // canonical UUID — reusing / malforming it would let an old finalizer believe it
        // still owns a newer mutation (review 2b [P2]).
        if (mutationId == null) {
            throw new AclEpochAnomalyException(state + " without aclEpochMutationId on " + doc.getId());
        }
        if (!AclEpochState.isValidMutationId(mutationId)) {
            throw new AclEpochAnomalyException(state + " with non-UUID aclEpochMutationId on " + doc.getId());
        }
        Long epoch = null;
        if (AclEpochState.FINALIZED_NEEDS_RECONCILE.equals(state)
                || AclEpochState.RECONCILE_ENQUEUED.equals(state)) {
            try {
                epoch = AclEpochCounterService.parseExactLong(props.get(AclEpochState.FIELD_SOURCE_EPOCH));
            } catch (RuntimeException e) {
                throw new AclEpochAnomalyException(state + " with invalid epoch on " + doc.getId()
                        + ": " + e.getMessage());
            }
            if (epoch < 1) {
                throw new AclEpochAnomalyException(state + " with non-positive epoch " + epoch
                        + " on " + doc.getId());
            }
        }
        return new EpochFields(state, mutationId, epoch);
    }

    // ── finalize (Phase 2) ─────────────────────────────────────────

    /** Finalize the content document addressed by {@code docId} (fetches it first). */
    public FinalizeOutcome finalizePending(String repositoryId, String docId) {
        Document doc = getDoc(repositoryId, docId);
        if (doc == null) {
            return new FinalizeOutcome(FinalizeResult.SKIPPED_NOT_PENDING, null); // vanished
        }
        return finalizePending(repositoryId, doc);
    }

    /**
     * Finalize the mutation identified by {@code hint} (a document snapshot — e.g. from the
     * scanner's {@code _find}, which does NOT carry {@code _attachments}). The hint supplies
     * only (a) the Phase-2 precondition that this is a committed Phase-1 document — {@code id}
     * and {@code _rev} are required BEFORE any epoch is allocated, so a rev-less hand-built
     * snapshot can never mint an epoch and PUT itself as a NEW document — and (b) the
     * mutation id this finalizer owns. Every actual read/write goes through a fresh
     * {@link #getDoc} (which carries the {@code _attachments} stubs) so finalize PRESERVES
     * attachments and all other content fields. The CAS commits to FINALIZED only while the
     * live document is still PENDING with the OWNED mutation id; it ABANDONS only on a
     * genuine newer mutation or a valid finalized state, and a corrupt live state is an
     * anomaly (thrown), never a silent supersede.
     */
    public FinalizeOutcome finalizePending(String repositoryId, Document hint) {
        Map<String, Object> hp = hint.getProperties();
        Object rawState = hp != null ? hp.get(AclEpochState.FIELD_STATE) : null;
        if (rawState == null) {
            return new FinalizeOutcome(FinalizeResult.SKIPPED_NOT_PENDING, null); // not an epoch doc
        }
        // Phase-2 precondition: a committed Phase-1 document (id + rev), checked BEFORE
        // allocate so a rev-less snapshot cannot mint an epoch and create a new document.
        if (hint.getId() == null || hint.getId().isBlank()) {
            throw new AclEpochAnomalyException("finalize target has no id");
        }
        if (hint.getRev() == null || hint.getRev().isBlank()) {
            throw new AclEpochAnomalyException("finalize target has no _rev (Phase 2 requires a "
                    + "committed Phase-1 document): " + hint.getId());
        }
        EpochFields hintEf = validate(hint); // corrupt hint → anomaly (never a silent skip)
        if (!AclEpochState.PENDING_EPOCH.equals(hintEf.state)) {
            return new FinalizeOutcome(FinalizeResult.SKIPPED_NOT_PENDING, null); // already finalized/advanced
        }
        String ownedMutation = hintEf.mutationId; // non-blank (validate guarantees)
        String docId = hint.getId();

        // Fetch the LIVE document (with _attachments stubs). Confirm it is still ours BEFORE
        // allocating, so an obviously-stale hint does not waste an epoch.
        Document current = getDoc(repositoryId, docId);
        FinalizeOutcome pre = stillOursOrOutcome(current, ownedMutation);
        if (pre != null) {
            return pre;
        }

        // Allocate ONCE for this mutation (a later supersede abandons it — a safe gap).
        long epoch = counterService.allocate(repositoryId);

        for (int attempt = 0; attempt < FINALIZE_CAS_RETRIES; attempt++) {
            Map<String, Object> p = current.getProperties();
            p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
            p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
            current.setProperties(p); // getProperties() may be a copy — re-set so it persists
            if (putBack(repositoryId, current) != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Finalized epoch {} for {}/{}", epoch, repositoryId, docId);
                }
                return new FinalizeOutcome(FinalizeResult.FINALIZED, epoch);
            }
            // 409 — re-read (attachment stubs) and re-evaluate.
            current = getDoc(repositoryId, docId);
            FinalizeOutcome post = stillOursOrOutcome(current, ownedMutation);
            if (post != null) {
                return post; // ABANDONED (or an anomaly thrown by validate inside)
            }
            // still PENDING with the owned mutation id → retry the CAS with the fresh rev.
        }
        throw new AclEpochAnomalyException("finalize did not converge for " + docId
                + " after " + FINALIZE_CAS_RETRIES + " CAS attempts");
    }

    /**
     * Decide whether the live {@code current} is still the PENDING document we own. Returns
     * {@code null} to PROCEED (still PENDING with {@code ownedMutation}), or a terminal
     * {@link FinalizeOutcome} otherwise. Only a GENUINE supersede — a real delete race, a
     * valid newer mutation, or a valid finalized/enqueued state — abandons. Corruption
     * THROWS an anomaly (never a silent supersede, review 2a #4 / 2b): a document that STILL
     * EXISTS but has LOST its {@code aclEpochState} is marker loss (a valid content doc does
     * not lose an epoch marker on its own), NOT a delete race, so it is reported and
     * retained rather than quietly abandoned.
     */
    private FinalizeOutcome stillOursOrOutcome(Document current, String ownedMutation) {
        if (current == null) {
            return new FinalizeOutcome(FinalizeResult.ABANDONED_SUPERSEDED, null); // genuine delete race
        }
        Map<String, Object> props = current.getProperties();
        if (props == null || props.get(AclEpochState.FIELD_STATE) == null) {
            throw new AclEpochAnomalyException("epoch marker disappeared from an existing document "
                    + current.getId() + " (marker loss — not a delete race)");
        }
        EpochFields cur = validate(current); // corrupt → AclEpochAnomalyException
        if (!AclEpochState.PENDING_EPOCH.equals(cur.state)) {
            return new FinalizeOutcome(FinalizeResult.ABANDONED_SUPERSEDED, null); // validly finalized/enqueued
        }
        if (!ownedMutation.equals(cur.mutationId)) {
            return new FinalizeOutcome(FinalizeResult.ABANDONED_SUPERSEDED, null); // genuine newer mutation
        }
        return null; // proceed
    }

    // ── scan (crash recovery) ──────────────────────────────────────

    /**
     * Advance epoch-outbox documents by one step. Four passes, each with its OWN
     * {@code maxDocsPerPass} budget (never a shared cap, so no pass can starve another —
     * review 2b [P1]). Every selector excludes already-quarantined documents
     * ({@code aclEpochQuarantined:{$exists:false}}).
     * <ol>
     *   <li><b>Finalize PENDING</b> — {@code aclEpochState=PENDING_EPOCH} with a mutation id.</li>
     *   <li><b>Count FINALIZED</b> — validate + count (the RECONCILE_ENQUEUED ACK is a later
     *       increment).</li>
     *   <li><b>Audit — live state WITHOUT a mutation id.</b></li>
     *   <li><b>Audit — a state that is set but is NEITHER live nor RECONCILE_ENQUEUED</b>
     *       (unknown / non-String).</li>
     * </ol>
     * <b>Guaranteed progression (review 2c [P1]):</b> ANY document {@link #validate} rejects —
     * a null / non-String / blank / non-UUID mutation id, a missing mutation id, an invalid
     * epoch, an unknown state — is moved to DURABLE QUARANTINE the moment the scanner sees it
     * (an {@code aclEpochQuarantined=true} flag added by CAS, ALL original fields preserved).
     * Because every selector excludes quarantined documents, an anomalous document is removed
     * from the live selectors after at most one scan and can NEVER block a valid document
     * again — even a {@code >budget} pile of anomalies clears in a FINITE number of scans and
     * the trailing valid documents are then finalized. A state-less (normal) document is never
     * matched by any pass.
     */
    public ScanSummary scan(String repositoryId, int maxDocsPerPass) {
        int budget = maxDocsPerPass > 0 ? maxDocsPerPass : DEFAULT_SCAN_MAX_DOCS;
        ScanSummary summary = new ScanSummary();

        runPass(repositoryId, notQuarantined(Map.of(
                        AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH,
                        AclEpochState.FIELD_MUTATION_ID, Map.of("$exists", true))),
                budget, summary, d -> {
                    FinalizeOutcome o = finalizePending(repositoryId, d);
                    if (o.result == FinalizeResult.FINALIZED) summary.finalized++;
                });

        runPass(repositoryId, notQuarantined(Map.of(
                        AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE,
                        AclEpochState.FIELD_MUTATION_ID, Map.of("$exists", true))),
                budget, summary, d -> {
                    validate(d); // rejects a present marker + an invalid UUID / epoch → quarantined
                    summary.awaitingReconcile++;
                });

        runPass(repositoryId, notQuarantined(Map.of(
                        AclEpochState.FIELD_STATE, Map.of("$in", List.of(
                                AclEpochState.PENDING_EPOCH, AclEpochState.FINALIZED_NEEDS_RECONCILE)),
                        AclEpochState.FIELD_MUTATION_ID, Map.of("$exists", false))),
                budget, summary, d -> {
                    throw new AclEpochAnomalyException("live state without aclEpochMutationId on " + d.getId());
                });

        // Terminal audit (review 2d [P2]): a RECONCILE_ENQUEUED with a malformed marker,
        // non-UUID mutation id, or invalid epoch is quarantined too, so "whatever validate()
        // rejects is quarantined" holds for the terminal state as well. A valid one is
        // counted (the ACK that consumes it is a later increment).
        runPass(repositoryId, notQuarantined(Map.of(
                        AclEpochState.FIELD_STATE, AclEpochState.RECONCILE_ENQUEUED)),
                budget, summary, d -> {
                    validate(d);
                    summary.enqueued++;
                });

        runPass(repositoryId, notQuarantined(Map.of(
                        AclEpochState.FIELD_STATE, Map.of("$exists", true, "$nin", List.of(
                                AclEpochState.PENDING_EPOCH, AclEpochState.FINALIZED_NEEDS_RECONCILE,
                                AclEpochState.RECONCILE_ENQUEUED)))),
                budget, summary, d -> {
                    throw new AclEpochAnomalyException("unknown / non-String aclEpochState on " + d.getId());
                });

        return summary;
    }

    /**
     * Add the "not quarantined" condition to a state selector. CouchDB Mango {@code $ne} /
     * {@code $not} do NOT match an ABSENT field, so excluding a TRUE marker while still
     * matching an absent OR malformed (false / null / non-Boolean) marker requires an
     * explicit {@code $or}: {@code {$exists:false} OR {$ne:true}}. This matches an absent
     * marker and any non-true value but NOT a proper {@code true} — so a malformed marker can
     * NOT hide a document from the scanner (review 2d [P1]); it is surfaced and normalized to
     * true. Still {@code (aclEpochState)}-index-served (verified by {@code _explain}).
     */
    private static Map<String, Object> notQuarantined(Map<String, Object> stateAndFields) {
        Map<String, Object> s = new LinkedHashMap<>(stateAndFields);
        s.put("$or", List.of(
                Map.of(AclEpochState.FIELD_QUARANTINED, Map.of("$exists", false)),
                Map.of(AclEpochState.FIELD_QUARANTINED, Map.of("$ne", true))));
        return s;
    }

    /** Functional per-document handler for a scan pass (may throw an anomaly). */
    @FunctionalInterface
    private interface PassHandler {
        void handle(Document doc) throws AclEpochAnomalyException;
    }

    /**
     * Bookmark-paged pass over {@code selector} with its OWN {@code budget} (a per-pass cap,
     * NOT shared with other passes), invoking {@code handler} per document. On an
     * {@link AclEpochAnomalyException} the document is moved to durable quarantine (so it
     * leaves the live selectors and can never block a valid document again) and recorded; a
     * non-anomaly (infrastructure) error propagates (fails the scan). {@code summary.more} is
     * set if this pass hit its budget with more possibly due.
     */
    private void runPass(String repositoryId, Map<String, Object> selector, int budget,
                         ScanSummary summary, PassHandler handler) {
        CloudantClientWrapper client = contentClient(repositoryId);
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        String bookmark = null;
        int processed = 0;
        while (processed < budget) {
            int limit = Math.min(PAGE_SIZE, budget - processed);
            PostFindOptions.Builder b = new PostFindOptions.Builder()
                    .db(db).selector(selector).limit(limit);
            if (bookmark != null) b.bookmark(bookmark);
            FindResult r = cloudant.postFind(b.build()).execute().getResult();
            List<Document> docs = r.getDocs();
            if (docs == null || docs.isEmpty()) {
                break;
            }
            for (Document d : docs) {
                if (processed >= budget) break;
                processed++;
                summary.scanned++;
                try {
                    handler.handle(d);
                } catch (AclEpochAnomalyException ae) {
                    quarantine(repositoryId, d.getId(), ae.getMessage(), summary);
                }
            }
            bookmark = r.getBookmark();
            if (docs.size() < limit) {
                break; // last page
            }
        }
        if (processed >= budget) {
            summary.more = true; // hit this pass's budget — more may be due
        }
    }

    /**
     * Move an anomalous document to DURABLE QUARANTINE: CAS-add {@code aclEpochQuarantined=true}
     * (preserving every original field, including {@code _attachments}) so it is excluded from
     * the live selectors and can never block a valid document again.
     *
     * <p><b>Re-validates on every CAS attempt (review 2d [P1]):</b> the flag is set ONLY if the
     * freshly-read document is STILL anomalous — if a concurrent normal Phase 1 REPAIRED it
     * (valid epoch fields AND no stray marker) or it became state-less normal content, the
     * quarantine is ABORTED (a repaired ACL mutation must never be permanently isolated). A
     * malformed non-true marker is itself an anomaly and is normalized to {@code true}.
     *
     * <p>A failure to durably quarantine is NOT swallowed silently (review 2d [P2]): it
     * increments {@code summary.quarantineFailures}, records an error, and sets
     * {@code summary.more} so a monitor/driver re-scans.
     *
     * <p>Package-private so the IT can drive the re-validation / failure paths deterministically.
     */
    void quarantine(String repositoryId, String docId, String problem, ScanSummary summary) {
        try {
            for (int attempt = 0; attempt < QUARANTINE_CAS_RETRIES; attempt++) {
                Document d = getDoc(repositoryId, docId);
                if (d == null || d.getProperties() == null) {
                    return; // gone — nothing to quarantine (no error: transient anomaly resolved)
                }
                Map<String, Object> p = d.getProperties();
                Object q = p.get(AclEpochState.FIELD_QUARANTINED);
                if (Boolean.TRUE.equals(q)) {
                    return; // already properly quarantined by a concurrent scan
                }
                boolean markerAnomalous = (q != null); // present but not Boolean true
                boolean epochAnomalous;
                if (p.get(AclEpochState.FIELD_STATE) == null) {
                    epochAnomalous = false; // repaired to state-less normal content
                } else {
                    try {
                        validateEpochFields(d);
                        epochAnomalous = false; // epoch fields are valid now (repaired)
                    } catch (AclEpochAnomalyException stillBad) {
                        epochAnomalous = true;
                    }
                }
                if (!epochAnomalous && !markerAnomalous) {
                    return; // fully repaired between detection and now → do NOT quarantine
                }
                p.put(AclEpochState.FIELD_QUARANTINED, true);
                d.setProperties(p);
                if (putBack(repositoryId, d) != null) {
                    summary.quarantined++;
                    record(summary, docId, problem);
                    return;
                }
                // 409 — retry with a fresh read (re-validated above each iteration).
            }
            recordQuarantineFailure(summary, docId, "quarantine CAS did not converge: " + problem);
        } catch (RuntimeException e) {
            recordQuarantineFailure(summary, docId, "quarantine write failed: " + e.getMessage());
        }
    }

    private void record(ScanSummary summary, String docId, String problem) {
        Map<String, String> err = new LinkedHashMap<>();
        err.put("docId", docId);
        err.put("problem", problem);
        summary.errors.add(err);
        logger.warn("ACL epoch scan anomaly {} — quarantined: {}", docId, problem);
    }

    private void recordQuarantineFailure(ScanSummary summary, String docId, String problem) {
        summary.quarantineFailures++;
        summary.more = true; // signal the driver to re-scan (the anomaly is not yet contained)
        record(summary, docId, problem);
    }

    // ── CouchDB primitives (content DB = repositoryId) ─────────────

    private Document getDoc(String repositoryId, String docId) {
        CloudantClientWrapper client = contentClient(repositoryId);
        try {
            return client.getClient().getDocument(new GetDocumentOptions.Builder()
                    .db(client.getDatabaseName()).docId(docId).build()).execute().getResult();
        } catch (NotFoundException e) {
            return null;
        }
    }

    /** PUT the (in-place mutated) document with its captured rev (CAS). Returns new rev, or null on 409. */
    private String putBack(String repositoryId, Document doc) {
        CloudantClientWrapper client = contentClient(repositoryId);
        try {
            var result = client.getClient().putDocument(new PutDocumentOptions.Builder()
                    .db(client.getDatabaseName()).docId(doc.getId()).document(doc).build())
                    .execute().getResult();
            if (result != null && result.isOk()) {
                doc.setRev(result.getRev());
                return result.getRev();
            }
            return null;
        } catch (ConflictException e) {
            return null;
        }
    }

    private CloudantClientWrapper contentClient(String repositoryId) {
        if (connectorPool == null) {
            throw new IllegalStateException("connectorPool not wired on AclEpochFinalizationService");
        }
        CloudantClientWrapper client = connectorPool.getClient(repositoryId);
        if (client == null) {
            throw new IllegalStateException("content DB client not available for repository '" + repositoryId + "'");
        }
        return client;
    }
}
