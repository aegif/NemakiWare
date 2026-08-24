/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.rest.ingest.capture.CaptureIntent;
import jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore;
import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.rest.ingest.capture.CaptureState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The capture boundary's two writes, over the lineage database, with strict IO throughout.
 *
 * <p>Lives in this package because the strict primitives it needs — a create that reports a
 * conflict as a conflict, a read that reports a 404 as {@code null} and anything else as a
 * throw, a CAS that distinguishes a lost race from an outage — are the journal store's, and
 * giving this class its own route to CouchDB would be a behaviour change wearing a
 * refactoring's clothes.
 *
 * <h2>Why not the ordinary write path</h2>
 *
 * <p>{@code CloudantClientWrapper.create} and {@code .update} catch everything and return
 * {@code null}, and {@code append} does not check even that. A 500, an authentication failure
 * and an oversized document all arrive as silence. A boundary built on that reports success it
 * never had, so every method here reads a verdict (design §6.8-1b).
 *
 * <h2>The row is never turned into a journal event</h2>
 *
 * <p>Completion sets {@code captureState} and leaves {@code type} alone. Writing a v1 event
 * directly would bypass the write-schema barrier, whose javadoc states that a missing spool is
 * not a licence to write v1 — in a deployment migrated to schema 2 that would erase the v2
 * representation of ingest events. The lineage event itself is still written by the emitter,
 * through the barrier, exactly as before (design §6.10-B4).
 */
public class CouchCaptureIntentStore implements CaptureIntentStore, CaptureMaintenanceStore {

    private static final Logger logger = LoggerFactory.getLogger(CouchCaptureIntentStore.class);

    /**
     * The design document. Deliberately not {@code _design/lineage}.
     *
     * <p>Adding a view to an existing design document rebuilds the whole view group, and while
     * that runs the other views answer from an incomplete index rather than failing — which is
     * indistinguishable from "there is nothing to report" at exactly the moment an operator is
     * most likely to be looking (design §6.8-3).
     */
    static final String DESIGN_DOC = "lineage_capture";

    /** Unresolved rows for the operator listing, keyed so the newest sort first. */
    static final String VIEW_UNRESOLVED = "unresolved_by_opened_at";

    /**
     * Unresolved rows keyed by {@code [repositoryId, intentOpenedAtMs]}.
     *
     * <p>Filtering in Java after the page was cut is wrong, not merely slow: {@code limit} and
     * {@code skip} are applied by CouchDB before the filter can run, so a busy repository's rows
     * push another repository's out of the page and the caller is told there is nothing there
     * (external review). A composite key does the selection where the paging happens.
     */
    static final String VIEW_UNRESOLVED_BY_REPO = "unresolved_by_repo_and_opened_at";

    /** Open intents past their deadline, for the sweeper. */
    static final String VIEW_OPEN_BY_OPENED_AT = "open_by_opened_at";

    /** Unresolved rows keyed by when they became unresolved, for retention. */
    static final String VIEW_UNRESOLVED_BY_UNRESOLVED_AT = "unresolved_by_unresolved_at";

    /** Completed rows keyed by when they completed, for retention. */
    static final String VIEW_CAPTURED_BY_CAPTURED_AT = "captured_by_captured_at";

    /**
     * Completed rows by the OBJECT they captured, newest first. The objectId reaches the row as
     * completion evidence, so only CAPTURED rows can appear here — an open or unresolved row has
     * no objectId yet. Exists for metadata-hash verification (p1-1d-metadata-hash.md §4): the
     * verifier needs "the latest hash-bearing completed row for object X", and without this view
     * that question is a full scan.
     */
    static final String VIEW_CAPTURED_BY_OBJECT = "captured_by_object";

    /**
     * Every row by its SOURCE item, newest first. What lets the verifier see passes that touched
     * the same source item but never completed (partial failures) or completed without a hash —
     * the rows that downgrade a would-be MISMATCH to UNVERIFIABLE instead of letting it read as
     * "an unrecorded change" when a record does exist.
     */
    static final String VIEW_BY_SOURCE_OBJECT = "rows_by_source_object";

    private final LineageStoreSupport support;
    private final LineageConfig lineageConfig;

    public CouchCaptureIntentStore(LineageStoreSupport support) {
        this(support, null);
    }

    public CouchCaptureIntentStore(LineageStoreSupport support, LineageConfig lineageConfig) {
        this.support = support;
        this.lineageConfig = lineageConfig;
    }

    @Override
    public Applicability appliesTo(String repositoryId) {
        if (lineageConfig == null) {
            // No configuration bean at all. Every repository behaves exactly as it does today
            // rather than failing closed on a boundary nobody configured — and unlike an
            // unreadable configuration, this IS a determinate answer about this deployment.
            return Applicability.NOT_APPLICABLE;
        }
        try {
            LineageMode mode = lineageConfig.getModeForRepository(repositoryId);
            if (mode != LineageMode.DISABLED && mode != LineageMode.DIRECT) {
                return Applicability.APPLIES;
            }
            if (mode == LineageMode.DIRECT) {
                // DIRECT is never what an unreadable configuration falls back to — the startup
                // default is disabled — so reaching it means someone set it. A determinate answer.
                return Applicability.NOT_APPLICABLE;
            }
            // DISABLED is only an answer when the configuration could be read. The read collapses
            // every failure into an empty configuration, which falls through to the disabled
            // startup default — so an unreachable nemaki_conf and a deliberate switch-off look
            // identical here (external review).
            return lineageConfig.configurationReadFailed()
                    ? Applicability.UNDETERMINED : Applicability.NOT_APPLICABLE;
        } catch (Exception e) {
            logger.warn("Could not resolve the lineage mode for {}: {}", repositoryId, e.toString());
            return Applicability.UNDETERMINED;
        }
    }

    @Override
    public boolean isActive() {
        // Mode-independent on purpose. Tying this to whether any repository is in journaled mode
        // would mean the boundary stops the moment the last one is switched off — and every
        // intent still open at that instant would freeze forever (design §6.8-4).
        try {
            return support != null && support.client() != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean openIntent(CaptureIntent intent) {
        if (intent == null) {
            return false;
        }
        try {
            Map<String, Object> doc = intent.toDocument();
            boolean created = support.createIfAbsentStrict(intent.documentId(), doc);
            if (!created) {
                // The id is unique per attempt, so a conflict means two attempts generated the
                // same id — not an ordinary race. Reporting it as success would let a second
                // ingest ride on the first one's intent row.
                logger.error("Capture intent {} already exists; refusing to treat that as a "
                        + "successful open", intent.documentId());
            }
            return created;
        } catch (Exception e) {
            // The intent write is the fail-closed point; a failure here must be visible.
            logger.error("Capture intent {} could not be written: {}", intent.documentId(),
                    e.toString());
            return false;
        }
    }

    /**
     * Pushes the lease forward on an OPEN row. Never throws into the caller.
     *
     * <p>Only while the row is still {@code CAPTURE_INTENT}: once it has been completed or
     * swept, extending would either rewrite a finished record or resurrect a row the sweeper
     * has already reported, and neither is this method's business.
     */
    @Override
    public void extendLease(CaptureIntent intent) {
        if (intent == null) {
            return;
        }
        try {
            Map<String, Object> raw = support.readRawStrict(intent.documentId());
            if (raw == null
                    || !CaptureState.CAPTURE_INTENT.name().equals(raw.get("captureState"))) {
                return;
            }
            raw.put(CaptureIntent.LEASE_FIELD,
                    System.currentTimeMillis() + CaptureIntent.LEASE_MS);
            support.updateStrictCas(raw);
            // A lost CAS needs no retry: either the pass completed (nothing to lease) or
            // another writer moved the row, and the next progress step will try again.
        } catch (Exception e) {
            // Best effort. A failed extension costs at most an early sweep — the behaviour
            // that was unconditional before the lease existed.
            logger.debug("Could not extend capture lease for {}: {}", intent.documentId(),
                    e.toString());
        }
    }

    @Override
    public CaptureCompletion completeIntent(CaptureIntent intent, Map<String, Object> evidence) {
        if (intent == null) {
            return CaptureCompletion.ROW_UNREADABLE;
        }
        try {
            Map<String, Object> raw = support.readRawStrict(intent.documentId());
            if (raw == null) {
                // A 404 here. The cause is NOT determinable from this position and must not be
                // guessed at by the caller (design §6.8-6).
                return CaptureCompletion.ROW_UNREADABLE;
            }
            Object state = raw.get("captureState");
            if (CaptureState.CAPTURED.name().equals(state)) {
                // Terminal, and already where we want it. Completing twice is not an error.
                return CaptureCompletion.COMPLETED;
            }
            if (!CaptureState.CAPTURE_INTENT.name().equals(state)
                    && !CaptureState.UNRESOLVED.name().equals(state)) {
                return CaptureCompletion.NOT_COMPLETABLE;
            }

            applyCompletion(raw, evidence);
            if (support.updateStrictCas(raw)) {
                return CaptureCompletion.COMPLETED;
            }
            // A lost CAS. Re-read to find out what it lost to, rather than assuming — and if the
            // row is still completable, try once more. The sweeper is the likely winner: a long
            // ingest passes the staleness threshold and is marked UNRESOLVED between this read
            // and this write, which §5.1 says the ingest may still complete. Giving up there
            // reported a finished ingest as "not in a state that could be completed" and left it
            // in the operator's listing for ever (external review).
            for (int attempt = 0; attempt < CAS_RETRIES; attempt++) {
                Map<String, Object> now = support.readRawStrict(intent.documentId());
                if (now == null) {
                    return CaptureCompletion.ROW_UNREADABLE;
                }
                Object nowState = now.get("captureState");
                if (CaptureState.CAPTURED.name().equals(nowState)) {
                    return CaptureCompletion.COMPLETED;
                }
                if (!CaptureState.CAPTURE_INTENT.name().equals(nowState)
                        && !CaptureState.UNRESOLVED.name().equals(nowState)) {
                    return CaptureCompletion.NOT_COMPLETABLE;
                }
                applyCompletion(now, evidence);
                if (support.updateStrictCas(now)) {
                    return CaptureCompletion.COMPLETED;
                }
            }
            // Distinct from NOT_COMPLETABLE. What was observed is "the row was still completable
            // every time we looked, and we lost the write every time" — reporting that as a state
            // problem would send an operator looking for the wrong thing (design §6.8-6).
            return CaptureCompletion.CONTENDED;
        } catch (Exception e) {
            logger.warn("Capture intent {} could not be completed: {}", intent.documentId(),
                    e.toString());
            return CaptureCompletion.ROW_UNREADABLE;
        }
    }

    /** Bounded: a row that keeps losing is reported, not retried for ever. */
    private static final int CAS_RETRIES = 3;

    /**
     * Stamps a row as completed and folds the evidence in.
     *
     * <p>Deliberately absent: {@code sequenceNumber}, {@code publishStatusByTarget},
     * {@code schemaVersion}. Those belong to a journal event, and this row never becomes one.
     */
    private static void applyCompletion(Map<String, Object> raw, Map<String, Object> evidence) {
        raw.put("captureState", CaptureState.CAPTURED.name());
        raw.put("capturedAtMs", System.currentTimeMillis());
        if (evidence != null) {
            for (Map.Entry<String, Object> e : evidence.entrySet()) {
                // Never let evidence overwrite the row's identity or its state.
                if (isReservedField(e.getKey())) {
                    continue;
                }
                raw.put(e.getKey(), e.getValue());
            }
        }
    }

    private static boolean isReservedField(String key) {
        return "_id".equals(key) || "_rev".equals(key) || "type".equals(key)
                || "captureState".equals(key) || "intentId".equals(key)
                || "intentOpenedAtMs".equals(key) || "capturedAtMs".equals(key);
    }


    // ── Maintenance: sweeping, retention, listing ────────────────────────────────────────

    @Override
    public int sweepExpiredIntents(long olderThanMs, int batchLimit) {
        int moved = 0;
        for (Map<String, Object> raw : page(VIEW_OPEN_BY_OPENED_AT, null, olderThanMs, batchLimit)) {
            // Re-check the state we read. Between the view answering and this write, the ingest
            // may have completed — and a view group is allowed to answer from a stale index.
            if (!CaptureState.CAPTURE_INTENT.name().equals(raw.get("captureState"))) {
                continue;
            }
            // A live lease means the pass is still making progress, however long it is taking.
            // The age rule that got this row into the page is the FLOOR (P1-1(e) Step 4); the
            // lease is what tells a slow pass from a dead one (capture-outbox M5). Rows written
            // before the lease existed have no such field and are governed by age alone.
            if (raw.get(CaptureIntent.LEASE_FIELD) instanceof Number lease
                    && lease.longValue() > System.currentTimeMillis()) {
                continue;
            }
            raw.put("captureState", CaptureState.UNRESOLVED.name());
            raw.put("unresolvedAtMs", System.currentTimeMillis());
            try {
                if (support.updateStrictCas(raw)) {
                    moved++;
                }
                // A lost CAS is ordinary here: another replica swept it, or the ingest completed
                // it. Both are correct outcomes, so there is nothing to retry and nothing to warn
                // about.
            } catch (Exception e) {
                logger.warn("Could not sweep capture intent {}: {}", raw.get("_id"), e.toString());
            }
        }
        return moved;
    }

    @Override
    public int purgeUnresolvedOlderThan(long cutoffMs, int batchLimit) {
        return purge(VIEW_UNRESOLVED_BY_UNRESOLVED_AT, CaptureState.UNRESOLVED, cutoffMs,
                batchLimit);
    }

    @Override
    public int purgeCapturedOlderThan(long cutoffMs, int batchLimit) {
        return purge(VIEW_CAPTURED_BY_CAPTURED_AT, CaptureState.CAPTURED, cutoffMs, batchLimit);
    }

    @Override
    public List<Map<String, Object>> listCapturedForObject(String repositoryId, String objectId,
            int limit) {
        int safeLimit = limit <= 0 ? 10 : Math.min(limit, 100);
        Map<String, Object> params = new HashMap<>();
        params.put("descending", true);
        params.put("limit", safeLimit);
        params.put("startkey", List.of(repositoryId, objectId, MAX_KEY));
        params.put("endkey", List.of(repositoryId, objectId, 0));
        return new ArrayList<>(support.queryRawView(DESIGN_DOC, VIEW_CAPTURED_BY_OBJECT, params));
    }

    @Override
    public List<Map<String, Object>> listRowsForSourceBefore(String repositoryId,
            String sourceObjectId, long beforeOpenedAtMsExclusive, int limit) {
        int safeLimit = limit <= 0 ? 10 : Math.min(limit, 100);
        Map<String, Object> params = new HashMap<>();
        params.put("descending", true);
        params.put("limit", safeLimit);
        // Descending, so startkey is the HIGH end. The exclusive upper bound is the caller's
        // keyset cursor (the previous page's last intentOpenedAtMs); MAX_VALUE means "from the
        // top" and uses the sentinel that sorts above every number.
        params.put("startkey", beforeOpenedAtMsExclusive == Long.MAX_VALUE
                ? List.of(repositoryId, sourceObjectId, MAX_KEY)
                : List.of(repositoryId, sourceObjectId, beforeOpenedAtMsExclusive - 1));
        params.put("endkey", List.of(repositoryId, sourceObjectId, 0));
        return new ArrayList<>(support.queryRawView(DESIGN_DOC, VIEW_BY_SOURCE_OBJECT, params));
    }

    private int purge(String view, CaptureState expected, long cutoffMs, int batchLimit) {
        int deleted = 0;
        for (Map<String, Object> raw : page(view, null, cutoffMs, batchLimit)) {
            // The state is re-checked against the document, not trusted from the view, so a
            // stale index cannot make this delete a row in a state it may not delete.
            if (!expected.name().equals(raw.get("captureState"))) {
                continue;
            }
            if (support.deleteRaw(raw)) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public UnresolvedPage listUnresolved(String repositoryId, int limit, int offset) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        int safeOffset = Math.max(offset, 0);
        boolean scoped = repositoryId != null && !repositoryId.isBlank();
        Map<String, Object> params = new HashMap<>();
        params.put("descending", true);      // newest first
        params.put("limit", safeLimit + 1);  // one extra, purely to answer hasMore
        params.put("skip", safeOffset);
        if (scoped) {
            // Descending, so the range runs from the repository's highest key down to its
            // lowest. The selection has to happen in CouchDB: limit and skip are applied before
            // any Java-side filter could run.
            params.put("startkey", List.of(repositoryId, MAX_KEY));
            params.put("endkey", List.of(repositoryId, 0));
        }

        List<Map<String, Object>> rows = new ArrayList<>(support.queryRawView(DESIGN_DOC,
                scoped ? VIEW_UNRESOLVED_BY_REPO : VIEW_UNRESOLVED, params));
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = rows.subList(0, safeLimit);
        }
        return new UnresolvedPage(List.copyOf(rows), safeLimit, safeOffset, hasMore);
    }

    @Override
    public CaptureCounts countByState(int scanLimit) {
        int limit = scanLimit <= 0 ? DEFAULT_COUNT_LIMIT : Math.min(scanLimit, MAX_COUNT_LIMIT);
        long open = countView(VIEW_OPEN_BY_OPENED_AT, limit);
        long unresolved = countView(VIEW_UNRESOLVED, limit);
        long captured = countView(VIEW_CAPTURED_BY_CAPTURED_AT, limit);
        boolean truncated = open >= limit || unresolved >= limit || captured >= limit;
        return new CaptureCounts(open, unresolved, captured, truncated);
    }

    private long countView(String view, int limit) {
        // countRawView, not queryRawView: the latter always asks for include_docs, so counting
        // would pull the documents into heap to produce a number (external review).
        return support.countRawView(DESIGN_DOC, view, limit);
    }

    static final int DEFAULT_COUNT_LIMIT = 10_000;
    static final int MAX_COUNT_LIMIT = 100_000;

    /** One bounded page of a view, from the beginning of the key range up to {@code endKey}. */
    private List<Map<String, Object>> page(String view, Long startKey, long endKey, int batchLimit) {
        Map<String, Object> params = new HashMap<>();
        if (startKey != null) {
            params.put("startkey", startKey);
        }
        params.put("endkey", endKey);
        // Clamped, like the listing's page size. Unbounded, a configured batch materialises the
        // whole view into a List<Map> against a 1 GB heap (external review).
        params.put("limit", batchLimit <= 0 ? DEFAULT_BATCH_LIMIT
                : Math.min(batchLimit, MAX_BATCH_LIMIT));
        return support.queryRawView(DESIGN_DOC, view, params);
    }

    /** Bounded because with leader election off, every replica walks the same batch. */
    static final int DEFAULT_BATCH_LIMIT = 200;

    /** The most rows one pass may materialise, whatever the configuration asks for. */
    static final int MAX_BATCH_LIMIT = 2000;

    /** Above any millisecond timestamp this product will produce; the top of a descending range. */
    private static final long MAX_KEY = Long.MAX_VALUE;

    /**
     * The views, keyed by name. Every one is guarded by the intent type, so no journal row can
     * ever appear in them and no intent row can appear in a journal view.
     */
    static Map<String, String> views() {
        Map<String, String> views = new LinkedHashMap<>();
        views.put(VIEW_UNRESOLVED,
                "function(doc) { if (doc.type === 'lineage_capture_intent'"
                        + " && doc.captureState === 'UNRESOLVED') {"
                        + " emit(doc.intentOpenedAtMs, null); } }");
        views.put(VIEW_UNRESOLVED_BY_REPO,
                "function(doc) { if (doc.type === 'lineage_capture_intent'"
                        + " && doc.captureState === 'UNRESOLVED' && doc.repositoryId) {"
                        + " emit([doc.repositoryId, doc.intentOpenedAtMs], null); } }");
        views.put(VIEW_OPEN_BY_OPENED_AT,
                "function(doc) { if (doc.type === 'lineage_capture_intent'"
                        + " && doc.captureState === 'CAPTURE_INTENT') {"
                        + " emit(doc.intentOpenedAtMs, null); } }");
        views.put(VIEW_UNRESOLVED_BY_UNRESOLVED_AT,
                // Only UNRESOLVED rows appear here, which is why retention cannot reach a row
                // that is still open: an intent must be swept before it can ever be deleted.
                "function(doc) { if (doc.type === 'lineage_capture_intent'"
                        + " && doc.captureState === 'UNRESOLVED' && doc.unresolvedAtMs) {"
                        + " emit(doc.unresolvedAtMs, null); } }");
        views.put(VIEW_CAPTURED_BY_CAPTURED_AT,
                "function(doc) { if (doc.type === 'lineage_capture_intent'"
                        + " && doc.captureState === 'CAPTURED' && doc.capturedAtMs) {"
                        + " emit(doc.capturedAtMs, null); } }");
        views.put(VIEW_CAPTURED_BY_OBJECT,
                "function(doc) { if (doc.type === 'lineage_capture_intent'"
                        + " && doc.captureState === 'CAPTURED' && doc.repositoryId"
                        + " && doc.objectId) {"
                        + " emit([doc.repositoryId, doc.objectId, doc.capturedAtMs || 0], null); } }");
        views.put(VIEW_BY_SOURCE_OBJECT,
                "function(doc) { if (doc.type === 'lineage_capture_intent'"
                        + " && doc.repositoryId && doc.sourceObjectId) {"
                        + " emit([doc.repositoryId, doc.sourceObjectId,"
                        + " doc.intentOpenedAtMs || 0], null); } }");
        return views;
    }
}
