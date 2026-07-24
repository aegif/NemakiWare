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
 * {@code docs/design/acl-epoch-fencing.md} §2.2 / §3 — increment 2).
 *
 * <p>Phase 2 of the two-phase mutation: a content document already committed in
 * {@link AclEpochState#PENDING_EPOCH} (with an {@code aclEpochMutationId}) is finalized by
 * allocating an epoch and CAS-patching it to {@link AclEpochState#FINALIZED_NEEDS_RECONCILE}
 * — but ONLY if the mutation id still matches (a newer Phase-1 supersedes it; an
 * already-finalized document is never re-allocated or regressed). The scanner recovers a
 * crash between Phase 1 and Phase 2 by finalizing any {@code PENDING_EPOCH} document it
 * finds, and STOPS at {@code FINALIZED_NEEDS_RECONCILE} (the enqueue/ACK is a later
 * increment).
 *
 * <p><b>Fail-closed staging (increment-2 acceptance conditions):</b>
 * <ul>
 *   <li>NOT auto-started — this is a plain bean with NO scheduler wiring, NO
 *       {@code init-method}, NO cron. {@link #scan} / {@link #finalizePending} have ZERO
 *       production callers; only tests / an explicit (default-disabled) admin driver run
 *       them.</li>
 *   <li>NO side effects — it never touches Solr, never creates a reconcile task, never
 *       evicts the ACL cache, and never stops the existing async ACL refresh. It only
 *       CAS-patches the epoch fields, preserving every other content field.</li>
 *   <li>Never initializes a state-less document — the scanner selects ONLY documents whose
 *       {@code aclEpochState} is already set; a normal (state-less) document is invisible
 *       to it.</li>
 *   <li>Anomalies (unknown state, {@code PENDING_EPOCH} without a mutation id, a
 *       {@code FINALIZED} document with an invalid epoch) are recorded as ERRORS and the
 *       document is LEFT UNPROCESSED — never silently skipped.</li>
 * </ul>
 */
public class AclEpochFinalizationService {

    private static final Logger logger = LoggerFactory.getLogger(AclEpochFinalizationService.class);

    /** Default per-scan processing cap (bounds one scan invocation). */
    public static final int DEFAULT_SCAN_MAX_DOCS = 500;
    /** Mango page size. */
    private static final int PAGE_SIZE = 100;
    /** CAS retries for a finalize whose conflict is a same-mutation transient. */
    private static final int FINALIZE_CAS_RETRIES = 8;

    private CloudantClientPool connectorPool;
    private AclEpochCounterService counterService;

    public void setConnectorPool(CloudantClientPool connectorPool) { this.connectorPool = connectorPool; }
    public void setCounterService(AclEpochCounterService counterService) { this.counterService = counterService; }

    // ── outcome types ──────────────────────────────────────────────

    public enum FinalizeResult {
        /** The epoch was allocated and CAS-committed to FINALIZED_NEEDS_RECONCILE. */
        FINALIZED,
        /** Not in PENDING_EPOCH (already finalized/advanced, or no epoch state) — nothing to do. */
        SKIPPED_NOT_PENDING,
        /** Was PENDING but superseded before our CAS (newer mutation, or finalized by another
         *  worker) — this attempt's allocated epoch is abandoned (a safe gap). */
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
        public boolean more; // hit the processing cap with (possibly) more due
        public final List<Map<String, String>> errors = new ArrayList<>();
    }

    /** Anomaly in the epoch outbox data — fail-closed (record + retain), never silently skipped. */
    public static final class AclEpochAnomalyException extends RuntimeException {
        public AclEpochAnomalyException(String message) { super(message); }
    }

    // ── finalize (Phase 2) ─────────────────────────────────────────

    /** Finalize the content document addressed by {@code docId} (fetches it first). */
    public FinalizeOutcome finalizePending(String repositoryId, String docId) {
        Document doc = getDoc(repositoryId, docId);
        if (doc == null) {
            return new FinalizeOutcome(FinalizeResult.SKIPPED_NOT_PENDING, null); // vanished — nothing to finalize
        }
        return finalizePending(repositoryId, doc);
    }

    /**
     * Finalize a content document already fetched (its {@code _rev} seeds the first CAS).
     * Strict transition: allocate once, CAS to FINALIZED only while the state is still
     * PENDING_EPOCH with the SAME mutation id; a 409 re-reads and abandons (never
     * overwrites a finalized epoch, never regresses, never depends on a JVM lock).
     */
    public FinalizeOutcome finalizePending(String repositoryId, Document doc) {
        Map<String, Object> props = doc.getProperties();
        String state = str(props, AclEpochState.FIELD_STATE);
        if (state == null) {
            return new FinalizeOutcome(FinalizeResult.SKIPPED_NOT_PENDING, null); // not an epoch doc
        }
        if (!AclEpochState.isKnown(state)) {
            throw new AclEpochAnomalyException("unknown aclEpochState '" + state + "' on " + doc.getId());
        }
        if (!AclEpochState.PENDING_EPOCH.equals(state)) {
            // Already FINALIZED / ENQUEUED — idempotent no-op, never re-allocate or regress.
            return new FinalizeOutcome(FinalizeResult.SKIPPED_NOT_PENDING, null);
        }
        String mutationId = str(props, AclEpochState.FIELD_MUTATION_ID);
        if (mutationId == null || mutationId.isBlank()) {
            throw new AclEpochAnomalyException("PENDING_EPOCH without aclEpochMutationId on " + doc.getId());
        }

        // Allocate ONCE for this mutation (a superseded CAS abandons it — a safe gap).
        long epoch = counterService.allocate(repositoryId);

        Document current = doc;
        for (int attempt = 0; attempt < FINALIZE_CAS_RETRIES; attempt++) {
            // Mutate the fetched document's own map in place so ALL other content fields
            // (and _attachments stubs) are preserved; set only the epoch fields.
            Map<String, Object> p = current.getProperties();
            p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
            p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
            // mutationId is left unchanged.
            current.setProperties(p);
            if (putBack(repositoryId, current) != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Finalized epoch {} for {}/{}", epoch, repositoryId, doc.getId());
                }
                return new FinalizeOutcome(FinalizeResult.FINALIZED, epoch);
            }
            // 409 — re-read and re-evaluate.
            current = getDoc(repositoryId, doc.getId());
            if (current == null) {
                return new FinalizeOutcome(FinalizeResult.ABANDONED_SUPERSEDED, null); // deleted mid-flight
            }
            String curState = str(current.getProperties(), AclEpochState.FIELD_STATE);
            String curMut = str(current.getProperties(), AclEpochState.FIELD_MUTATION_ID);
            if (!AclEpochState.PENDING_EPOCH.equals(curState)) {
                return new FinalizeOutcome(FinalizeResult.ABANDONED_SUPERSEDED, null); // finalized/advanced
            }
            if (!mutationId.equals(curMut)) {
                return new FinalizeOutcome(FinalizeResult.ABANDONED_SUPERSEDED, null); // newer mutation
            }
            // still PENDING with the SAME mutation id → retry the CAS with the fresh rev.
        }
        // Could not converge (persistent same-mutation contention) — leave PENDING for a
        // later pass; the allocated epoch is abandoned (safe gap).
        throw new AclEpochAnomalyException("finalize did not converge for " + doc.getId()
                + " after " + FINALIZE_CAS_RETRIES + " CAS attempts");
    }

    // ── scan (crash recovery) ──────────────────────────────────────

    /**
     * Scan the content DB for epoch-outbox documents and advance them by ONE step, up to
     * {@code maxDocs}. Selector matches ONLY documents whose {@code aclEpochState} is one
     * of the known non-terminal states — a state-less (normal) document is never touched.
     * A {@code PENDING_EPOCH} document is finalized; a {@code FINALIZED_NEEDS_RECONCILE}
     * document is validated and COUNTED but left as-is (the enqueue/ACK is a later
     * increment). Anomalous documents are recorded in {@link ScanSummary#errors} and left
     * unprocessed.
     */
    public ScanSummary scan(String repositoryId, int maxDocs) {
        int cap = maxDocs > 0 ? maxDocs : DEFAULT_SCAN_MAX_DOCS;
        ScanSummary summary = new ScanSummary();
        Map<String, Object> selector = Map.of(
                AclEpochState.FIELD_STATE,
                Map.of("$in", List.of(AclEpochState.PENDING_EPOCH, AclEpochState.FINALIZED_NEEDS_RECONCILE)));

        CloudantClientWrapper client = contentClient(repositoryId);
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        String bookmark = null;

        while (summary.scanned < cap) {
            PostFindOptions.Builder b = new PostFindOptions.Builder()
                    .db(db).selector(selector).limit(Math.min(PAGE_SIZE, cap - summary.scanned));
            if (bookmark != null) b.bookmark(bookmark);
            FindResult r = cloudant.postFind(b.build()).execute().getResult();
            List<Document> docs = r.getDocs();
            if (docs == null || docs.isEmpty()) {
                break;
            }
            for (Document d : docs) {
                summary.scanned++;
                try {
                    advanceOne(repositoryId, d, summary);
                } catch (AclEpochAnomalyException ae) {
                    // Fail-closed: record + LEAVE the document unprocessed (do not advance).
                    Map<String, String> err = new LinkedHashMap<>();
                    err.put("docId", d.getId());
                    err.put("problem", ae.getMessage());
                    summary.errors.add(err);
                    logger.warn("ACL epoch scan anomaly for {}/{}: {}", repositoryId, d.getId(), ae.getMessage());
                }
            }
            bookmark = r.getBookmark();
            if (docs.size() < PAGE_SIZE) {
                break; // last page
            }
        }
        // If we stopped because of the cap and a full page was still coming, flag it.
        summary.more = summary.scanned >= cap;
        return summary;
    }

    private void advanceOne(String repositoryId, Document d, ScanSummary summary) {
        String state = str(d.getProperties(), AclEpochState.FIELD_STATE);
        if (AclEpochState.PENDING_EPOCH.equals(state)) {
            FinalizeOutcome o = finalizePending(repositoryId, d);
            if (o.result == FinalizeResult.FINALIZED) {
                summary.finalized++;
            }
            // ABANDONED / SKIPPED are counted only in scanned (no error, no advance).
        } else if (AclEpochState.FINALIZED_NEEDS_RECONCILE.equals(state)) {
            // Validate the finalized epoch, then STOP (increment 2 does not enqueue/ACK).
            // A missing / non-integral / out-of-range epoch is a DATA anomaly (recorded +
            // retained by scan), so convert parseExactLong's IllegalStateException.
            Object epochField = d.getProperties().get(AclEpochState.FIELD_SOURCE_EPOCH);
            long epoch;
            try {
                epoch = AclEpochCounterService.parseExactLong(epochField);
            } catch (RuntimeException e) {
                throw new AclEpochAnomalyException("FINALIZED_NEEDS_RECONCILE with invalid epoch on "
                        + d.getId() + ": " + e.getMessage());
            }
            if (epoch < 1) {
                throw new AclEpochAnomalyException("FINALIZED_NEEDS_RECONCILE with non-positive epoch "
                        + epoch + " on " + d.getId());
            }
            summary.awaitingReconcile++;
        } else {
            // The selector only returns the two states above; anything else is corruption.
            throw new AclEpochAnomalyException("unexpected state '" + state + "' returned by scan selector");
        }
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

    private static String str(Map<String, Object> props, String key) {
        Object v = props != null ? props.get(key) : null;
        return v == null ? null : v.toString();
    }
}
