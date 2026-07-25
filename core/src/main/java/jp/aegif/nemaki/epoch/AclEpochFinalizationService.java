package jp.aegif.nemaki.epoch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.sdk.core.service.exception.BadRequestException;
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

    /** Default PER-PASS processing budget (each pass is bounded independently). */
    public static final int DEFAULT_SCAN_MAX_DOCS = 500;
    private static final int PAGE_SIZE = 100;
    private static final int FINALIZE_CAS_RETRIES = 8;
    /** Deterministic id of the per-content-DB terminal-audit resume cursor (no aclEpochState → matched by no pass). */
    private static final String AUDIT_CURSOR_ID = "acl-epoch-audit-cursor";
    private static final String AUDIT_CURSOR_TYPE = "aclEpochAuditCursor";
    private static final String AUDIT_CURSOR_FIELD = "terminalBookmark";
    /** Cursor-document field naming the persisted format this build understands. */
    private static final String FIELD_SCHEMA_VERSION = "schemaVersion";
    /**
     * The ONLY cursor {@code schemaVersion} this build may read or write. A cursor whose version is
     * absent / non-integer / different (older OR newer) is unusable and is left untouched — see
     * {@link #cursorUnusableReason}.
     */
    private static final int AUDIT_CURSOR_SCHEMA_VERSION = 1;
    private static final int CURSOR_CAS_RETRIES = 5;
    /** Index (ddoc, then index name) the terminal query is pinned to via {@code use_index}. */
    private static final List<String> TERMINAL_USE_INDEX = List.of("acl-epoch-indexes", "idx_aclEpochState");
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
        public int contended;            // valid docs a finalize CAS could not converge on (not anomalies)
        public int cursorFailures;       // terminal-audit resume-cursor read/write failures this scan
        public boolean more;             // a pass hit budget / quarantine or cursor write failed / contention → re-scan
        public final List<Map<String, String>> errors = new ArrayList<>();
    }

    /**
     * CONTENTION (a CAS livelock), NOT a data anomaly: a finalize could not converge because
     * another writer kept bumping the document's {@code _rev} while it stayed a valid PENDING.
     * It must NOT be routed to {@link #quarantine} (the document is valid, not corrupt) — the
     * scan records it and sets {@code more} so the driver re-scans (review 2f [P3]).
     */
    public static final class AclEpochContentionException extends RuntimeException {
        public AclEpochContentionException(String message) { super(message); }
    }

    /**
     * Validate a document as a live epoch doc: it must NOT be quarantined (or carry a
     * malformed quarantine marker) AND its epoch fields must be valid. Delegates to the SHARED
     * {@link AclEpochFields} validator (review 3a [P1]) so the state machine's owner and the
     * effective-epoch consumer can never drift apart.
     *
     * @throws AclEpochAnomalyException if quarantined / the marker is malformed / the epoch
     *         fields are anomalous
     */
    private static AclEpochFields.Values validate(Document doc) {
        AclEpochFields.requireNotQuarantined(doc.getId(), doc.getProperties());
        return validateEpochFields(doc);
    }

    /**
     * Strictly validate ONLY the epoch fields (state / mutationId / epoch), ignoring the
     * quarantine marker. Used by {@link #quarantine} to decide whether a document is STILL
     * anomalous on re-read (a repaired document must not be quarantined — review 2d [P1]).
     * The finalizer REQUIRES a state: a state-less document is not its business.
     */
    private static AclEpochFields.Values validateEpochFields(Document doc) {
        return AclEpochFields.validate(doc.getId(), doc.getProperties(), true);
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
        // ABSENT state = not an epoch doc (skip). PRESENT-but-null state is corruption, NOT
        // "not an epoch doc": use containsKey so a present-null state falls through to
        // validate() and is raised as an anomaly rather than silently skipped (review 2f;
        // the SDK stores an explicit JSON null as a present entry).
        if (hp == null || !hp.containsKey(AclEpochState.FIELD_STATE)) {
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
        AclEpochFields.Values hintEf = validate(hint); // corrupt hint → anomaly (never a silent skip)
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
        // CONTENTION, not a data anomaly: the document is a valid PENDING that a competing
        // writer kept bumping. Throw the contention type so the scan records it (never
        // quarantines a valid document — review 2f).
        throw new AclEpochContentionException("finalize did not converge for " + docId
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
        AclEpochFields.Values cur = validate(current); // corrupt → AclEpochAnomalyException
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
     * Advance epoch-outbox documents by one step. Each pass has its OWN {@code maxDocsPerPass}
     * budget (never a shared cap, so no pass can starve another — review 2b [P1]). Every
     * selector excludes an already-quarantined document via {@link #notQuarantined}
     * ({@code $or({$exists:false}, {$ne:true})} — Mango {@code $ne}/{@code $not} do NOT match an
     * absent field, so this is the only form that excludes a TRUE marker while still matching an
     * absent OR malformed marker).
     * <ol>
     *   <li><b>Finalize PENDING</b> — {@code aclEpochState=PENDING_EPOCH} with a mutation id
     *       (advances → leaves the selector).</li>
     *   <li><b>Audit — live state WITHOUT a mutation id</b> (anomaly → quarantined → leaves).</li>
     *   <li><b>Audit — a state that is set but is NEITHER live nor terminal</b> (unknown /
     *       non-String; anomaly → quarantined → leaves).</li>
     *   <li><b>Terminal audit (CURSORED)</b> — FINALIZED + RECONCILE_ENQUEUED. A VALID terminal
     *       doc never leaves the selector (the ACK is a later increment), so this pass uses a
     *       PERSISTENT cursor ({@link #runCursoredPass}) to cycle through the whole terminal set
     *       across scans, guaranteeing every anomalous terminal doc is reached in FINITE scans.
     *       A valid one is counted (awaitingReconcile / enqueued).</li>
     * </ol>
     * <b>Guaranteed progression (review 2c/2f [P1]):</b> ANY document {@link #validate} rejects —
     * a null / non-String / blank / non-UUID mutation id, a missing mutation id, an invalid
     * epoch, an unknown or present-null state, OR a malformed quarantine marker — is moved to
     * DURABLE QUARANTINE (an {@code aclEpochQuarantined=true} flag added by CAS; all OTHER fields
     * preserved — a malformed marker is the one field normalized to {@code true}). Because every
     * selector excludes a true marker, an anomalous document is removed from the live selectors
     * (advance passes: at most one scan; cursored terminal pass: within a finite number of
     * scans) and can NEVER block a valid document again. A CAS contention (a valid doc a
     * competing writer keeps bumping) is recorded as {@code contended} + {@code more}, never
     * quarantined.
     *
     * <p><b>Scope:</b> the marker/epoch contract applies ONLY to EPOCH-STATE-BEARING documents
     * (those carrying an {@code aclEpochState}); a state-less document is normal content, is
     * matched by no pass, and a stray marker on it is irrelevant to the epoch machine. The
     * terminal-audit resume cursor is a per-content-DB document (id {@code acl-epoch-audit-cursor},
     * no {@code aclEpochState}) — a new persistent-format artefact, matched by no scan pass.
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
                        AclEpochState.FIELD_STATE, Map.of("$in", List.of(
                                AclEpochState.PENDING_EPOCH, AclEpochState.FINALIZED_NEEDS_RECONCILE)),
                        AclEpochState.FIELD_MUTATION_ID, Map.of("$exists", false))),
                budget, summary, d -> {
                    throw new AclEpochAnomalyException("live state without aclEpochMutationId on " + d.getId());
                });

        runPass(repositoryId, notQuarantined(Map.of(
                        AclEpochState.FIELD_STATE, Map.of("$exists", true, "$nin", List.of(
                                AclEpochState.PENDING_EPOCH, AclEpochState.FINALIZED_NEEDS_RECONCILE,
                                AclEpochState.RECONCILE_ENQUEUED)))),
                budget, summary, d -> {
                    throw new AclEpochAnomalyException("unknown / non-String aclEpochState on " + d.getId());
                });

        // TERMINAL audit (review 2f [P1]): FINALIZED_NEEDS_RECONCILE + RECONCILE_ENQUEUED. These
        // are TERMINAL-parked (the ACK that clears them is a later increment), so a VALID one
        // never leaves the selector; a stateless bookmark=null pass would count the same >budget
        // valid pile every scan and STARVE an anomalous terminal doc behind it. A PERSISTENT
        // cursor makes this pass resume where it left off and wrap on exhaustion, so it cycles
        // through the whole terminal set across scans — every corrupt terminal doc is reached and
        // quarantined within FINITE scans (bounded by |terminal| / budget). A valid one is counted.
        runCursoredPass(repositoryId, notQuarantined(Map.of(
                        AclEpochState.FIELD_STATE, Map.of("$in", List.of(
                                AclEpochState.FINALIZED_NEEDS_RECONCILE, AclEpochState.RECONCILE_ENQUEUED)))),
                budget, summary, d -> {
                    AclEpochFields.Values ef = validate(d); // marker / UUID / epoch anomalies → quarantined
                    if (AclEpochState.FINALIZED_NEEDS_RECONCILE.equals(ef.state)) summary.awaitingReconcile++;
                    else summary.enqueued++;
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
                } catch (AclEpochContentionException ce) {
                    // Contention on a VALID document — record + re-scan, but do NOT quarantine
                    // (the document is not corrupt). review 2f [P3].
                    summary.contended++;
                    summary.more = true;
                    recordContention(summary, d.getId(), ce.getMessage());
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
     * Like {@link #runPass} but RESUMES from a persistent per-content-DB cursor and WRAPS on
     * exhaustion (review 2f [P1]). Used only for the TERMINAL states, whose VALID documents
     * never leave the selector (the ACK is a later increment): a stateless bookmark=null pass
     * would re-count the same {@code >budget} valid pile every scan and starve an anomalous
     * terminal doc behind it. By persisting the bookmark and wrapping when the selector is
     * exhausted, the pass cycles through the whole terminal set across scans, so every
     * anomalous terminal doc is reached and quarantined within a FINITE number of scans.
     *
     * <p><b>Cursor failure contract (review 2g/2h):</b> an UNUSABLE cursor document — a foreign
     * document occupying the cursor id, or one whose {@code schemaVersion} this build does not
     * understand (absent / non-integer / a future version) — is reported as a {@code cursorFailure}
     * and the terminal pass is SKIPPED fail-closed: the document is NEVER modified (no implicit
     * upgrade, no clobber). A TRANSIENT cursor READ error (an infrastructure failure, not a
     * document problem) is reported and degrades to a bookmark=null pass from the top of the
     * terminal set (no worse than {@link #runPass}). A cursor SAVE failure is reported and sets
     * {@code more}; the sweep simply restarts from the top next scan (the cursor is pure resume
     * state, so losing it costs progress, never correctness).
     */
    private void runCursoredPass(String repositoryId, Map<String, Object> selector, int budget,
                                 ScanSummary summary, PassHandler handler) {
        CloudantClientWrapper client = contentClient(repositoryId);
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();

        CursorRead cr = readCursor(repositoryId, summary);
        if (cr.unusable) {
            return; // unusable cursor doc (foreign / unsupported version) — reported, left untouched
        }
        String bookmark = cr.bookmark;
        boolean usingStored = (bookmark != null); // a STORED (not this-scan) bookmark may be invalid
        boolean triedReset = false;
        int processed = 0;
        boolean exhausted = false;
        while (processed < budget) {
            int limit = Math.min(PAGE_SIZE, budget - processed);
            FindResult r;
            try {
                r = findTerminal(cloudant, db, selector, limit, bookmark);
            } catch (BadRequestException e) {
                // ONLY a stored bookmark being invalid/expired is self-healed: CAS-clear the
                // cursor and retry from the top ONCE. A general 400 (e.g. missing pinned index)
                // must NOT be mistaken for an invalid bookmark — it propagates.
                if (usingStored && !triedReset && isInvalidBookmark(e)) {
                    triedReset = true;
                    usingStored = false;
                    bookmark = null;
                    clearCursor(repositoryId, summary); // CAS-clear the bad stored bookmark
                    continue; // retry from the top with a null bookmark
                }
                throw e;
            }
            List<Document> docs = r.getDocs();
            if (docs == null || docs.isEmpty()) {
                exhausted = true;
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
                } catch (AclEpochContentionException ce) {
                    summary.contended++;
                    summary.more = true;
                    recordContention(summary, d.getId(), ce.getMessage());
                }
            }
            bookmark = r.getBookmark();
            usingStored = false; // subsequent bookmarks come from THIS scan's _find (valid)
            if (docs.size() < limit) {
                exhausted = true;
                break;
            }
        }
        // Exhausted → reset (wrap to the start next scan). Otherwise save the position and flag
        // that the terminal set is not fully swept yet, so a driver keeps scanning.
        saveCursor(repositoryId, exhausted ? null : bookmark, summary);
        if (!exhausted) {
            summary.more = true;
        }
    }

    /**
     * The terminal query, PINNED to the {@code (aclEpochState)} index via {@code use_index}
     * (review 2g [P1]). The terminal selector is provably served by that index (verified by
     * {@code _explain} in the IT), so there is no {@code _all_docs} full-scan fallback in
     * practice. NOTE: the belt-and-suspenders {@code allow_fallback=false} query parameter is a
     * CouchDB 3.4+/Cloudant feature — CouchDB 3.3.x (this project's deployed version) rejects it
     * with {@code invalid_key}, so the no-fallback guarantee here rests on the {@code use_index}
     * pin plus the {@code _explain}-verified index-served selector, not on {@code allow_fallback}.
     */
    private FindResult findTerminal(Cloudant cloudant, String db, Map<String, Object> selector,
                                    int limit, String bookmark) {
        PostFindOptions.Builder b = new PostFindOptions.Builder()
                .db(db).selector(selector).limit(limit)
                .useIndex(TERMINAL_USE_INDEX);
        if (bookmark != null) {
            b.bookmark(bookmark);
        }
        return cloudant.postFind(b.build()).execute().getResult();
    }

    private static boolean isInvalidBookmark(BadRequestException e) {
        String body = e.getResponseBody();
        if (body != null && body.contains("invalid_bookmark")) return true;
        return e.getMessage() != null && e.getMessage().contains("invalid_bookmark");
    }

    private static final class CursorRead {
        String bookmark;
        /** The cursor DOCUMENT is unusable (foreign / unsupported version) — skip, never modify. */
        boolean unusable;
    }

    /**
     * The SINGLE definition of "this build may use this cursor document", shared by the read and
     * the save path so they can never disagree (review 2h [P2]). Returns {@code null} when the
     * document is usable (including {@code null} = absent, i.e. a fresh cursor may be created), or
     * a human-readable reason when it is NOT.
     *
     * <p>Strict, fail-closed checks:
     * <ul>
     *   <li>{@code type} must be exactly {@code aclEpochAuditCursor} (else a foreign document).</li>
     *   <li>{@code schemaVersion} must be PRESENT and a STRICT integer equal to
     *       {@link #AUDIT_CURSOR_SCHEMA_VERSION}. Absent (an increment-2f-era cursor), an explicit
     *       null, a non-Number ({@code "1"}), a non-integral number ({@code 1.5}), or a FUTURE
     *       version ({@code 2}, written by a newer build) are all unusable.</li>
     * </ul>
     *
     * <p><b>Migration contract:</b> an unusable cursor is NEVER implicitly upgraded or overwritten
     * — a newer build's cursor must not be silently downgraded by an older one, and a 2f-era
     * cursor is not silently adopted. The cursor holds only a resume bookmark, so the documented
     * operator remedy is simply to DELETE the document: the terminal sweep then restarts from the
     * top (exactly the normal wrap behaviour) with no correctness loss.
     */
    private static String cursorUnusableReason(Document c) {
        if (c == null || c.getProperties() == null) {
            return null; // absent → a fresh cursor may be created
        }
        Map<String, Object> p = c.getProperties();
        Object type = p.get("type");
        if (!AUDIT_CURSOR_TYPE.equals(type)) {
            return "audit cursor id '" + AUDIT_CURSOR_ID + "' is occupied by a foreign document (type="
                    + type + ")";
        }
        if (!p.containsKey(FIELD_SCHEMA_VERSION)) {
            return "audit cursor has no " + FIELD_SCHEMA_VERSION + " (a pre-" + AUDIT_CURSOR_SCHEMA_VERSION
                    + " cursor is not adopted implicitly)";
        }
        Object rawVersion = p.get(FIELD_SCHEMA_VERSION);
        long version;
        try {
            // Strict: rejects an explicit null, a non-Number ("1"), a non-integral number (1.5),
            // an out-of-long-range or non-finite value. (The counter's own wording is not reused —
            // it would misattribute the failure.)
            version = AclEpochCounterService.parseExactLong(rawVersion);
        } catch (RuntimeException e) {
            return "audit cursor " + FIELD_SCHEMA_VERSION + " is not a strict integer (value=" + rawVersion + ")";
        }
        if (version != AUDIT_CURSOR_SCHEMA_VERSION) {
            return "audit cursor " + FIELD_SCHEMA_VERSION + "=" + version + " is not understood by this "
                    + "build (expects " + AUDIT_CURSOR_SCHEMA_VERSION + ")";
        }
        return null; // usable
    }

    /** Suffix appended to every unusable-cursor report: what happens and what the operator does. */
    private static final String CURSOR_UNUSABLE_REMEDY =
            " — terminal audit skipped, the document is left untouched; delete the document to restart "
            + "the terminal sweep from the top";

    /**
     * Read the terminal-audit cursor. Fail-closed via {@link #cursorUnusableReason}: an unusable
     * cursor DOCUMENT (foreign type / unsupported {@code schemaVersion}) reports a
     * {@code cursorFailure} and signals the caller to skip the pass WITHOUT touching the document
     * (review 2g/2h [P1/P2]). A missing cursor → start (null bookmark); a TRANSIENT read error →
     * reported and treated as start (an infrastructure failure is not a document problem).
     */
    private CursorRead readCursor(String repositoryId, ScanSummary summary) {
        CursorRead cr = new CursorRead();
        Document c;
        try {
            c = getDoc(repositoryId, AUDIT_CURSOR_ID);
        } catch (RuntimeException e) {
            recordCursorFailure(summary, "audit cursor read failed: " + e.getMessage());
            return cr; // transient infrastructure failure → start from the top (document is fine)
        }
        String unusable = cursorUnusableReason(c);
        if (unusable != null) {
            cr.unusable = true;
            recordCursorFailure(summary, unusable + CURSOR_UNUSABLE_REMEDY);
            return cr;
        }
        if (c == null || c.getProperties() == null) {
            return cr; // absent → start from the top
        }
        Object v = c.getProperties().get(AUDIT_CURSOR_FIELD);
        cr.bookmark = (v instanceof String && !((String) v).isBlank()) ? (String) v : null;
        return cr;
    }

    private void clearCursor(String repositoryId, ScanSummary summary) {
        saveCursor(repositoryId, null, summary);
    }

    /**
     * Persist (or clear, when {@code bookmark==null}) the terminal-audit resume position with a
     * BOUNDED {@code _rev} CAS retry. A {@code putBack()==null} (409) is NOT success — it retries
     * with a fresh read. An UNUSABLE document at the id (foreign type / unsupported
     * {@code schemaVersion}) is never modified — the SAME {@link #cursorUnusableReason} the read
     * path uses, so read and save can never disagree (review 2h [P2]). On persistent failure it
     * reports a {@code cursorFailure} + {@code more} (never a silent swallow — review 2g [P1]).
     */
    private void saveCursor(String repositoryId, String bookmark, ScanSummary summary) {
        for (int attempt = 0; attempt < CURSOR_CAS_RETRIES; attempt++) {
            Document c;
            try {
                c = getDoc(repositoryId, AUDIT_CURSOR_ID);
            } catch (RuntimeException e) {
                recordCursorFailure(summary, "audit cursor re-read failed: " + e.getMessage());
                return;
            }
            // Re-checked on EVERY attempt: a concurrent writer may have replaced the cursor with a
            // foreign / newer-version document between attempts.
            String unusable = cursorUnusableReason(c);
            if (unusable != null) {
                recordCursorFailure(summary, unusable + " — not saving, the document is left untouched");
                return;
            }
            // Reuse the fetched Document so its _attachments stubs (if any) and _rev are preserved.
            Document out = (c != null) ? c : new Document();
            out.setId(AUDIT_CURSOR_ID);
            Map<String, Object> props = (out.getProperties() != null) ? out.getProperties() : new LinkedHashMap<>();
            props.put("type", AUDIT_CURSOR_TYPE);
            props.put(FIELD_SCHEMA_VERSION, AUDIT_CURSOR_SCHEMA_VERSION);
            if (bookmark == null) {
                props.remove(AUDIT_CURSOR_FIELD);
            } else {
                props.put(AUDIT_CURSOR_FIELD, bookmark);
            }
            out.setProperties(props);
            try {
                if (putBack(repositoryId, out) != null) {
                    return; // durably saved
                }
            } catch (RuntimeException e) {
                recordCursorFailure(summary, "audit cursor save failed: " + e.getMessage());
                return;
            }
            // 409 → another writer changed the cursor; re-read + retry.
        }
        recordCursorFailure(summary, "audit cursor save did not converge after "
                + CURSOR_CAS_RETRIES + " CAS attempts");
    }

    private void recordCursorFailure(ScanSummary summary, String problem) {
        summary.cursorFailures++;
        summary.more = true;
        Map<String, String> err = new LinkedHashMap<>();
        err.put("docId", AUDIT_CURSOR_ID);
        err.put("problem", problem);
        summary.errors.add(err);
        logger.warn("ACL epoch audit cursor failure: {}", problem);
    }

    private void recordContention(ScanSummary summary, String docId, String problem) {
        Map<String, String> err = new LinkedHashMap<>();
        err.put("docId", docId);
        err.put("problem", problem);
        summary.errors.add(err);
        // NOT "quarantined": a contended document is a VALID PENDING left as-is for the next scan.
        logger.info("ACL epoch finalize contention on {} (valid document, not quarantined): {}", docId, problem);
    }

    /**
     * Move an anomalous document to DURABLE QUARANTINE: CAS-set {@code aclEpochQuarantined=true}
     * (preserving every other field, including {@code _attachments}; a malformed marker is the
     * one field this NORMALIZES to {@code true}) so it is excluded from the live selectors and
     * can never block a valid document again.
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
                if (Boolean.TRUE.equals(p.get(AclEpochState.FIELD_QUARANTINED))) {
                    return; // already properly quarantined by a concurrent scan
                }
                // PRESENCE contract (review 2e): an explicit-null marker is present (the SDK
                // stores it), so use containsKey — a non-true PRESENT marker (false / null /
                // string / …) is a marker anomaly to normalize to true.
                boolean markerAnomalous = p.containsKey(AclEpochState.FIELD_QUARANTINED);
                boolean epochAnomalous;
                if (!p.containsKey(AclEpochState.FIELD_STATE)) {
                    // ABSENT state = repaired to state-less normal content. Use containsKey,
                    // NOT get()==null: the SDK stores an explicit-null state as PRESENT, and a
                    // present-null state is corruption (validateEpochFields rejects it), not
                    // "repaired" — the earlier review 2e containsKey fix was on the marker only,
                    // this closes the same class on the STATE field (review 2f).
                    epochAnomalous = false;
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

    /**
     * PUT the (in-place mutated) document with its captured rev (CAS). Returns new rev, or null
     * on 409. Package-private so an IT can override it to force a deterministic CAS livelock
     * (review 2g contention test).
     */
    String putBack(String repositoryId, Document doc) {
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
