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

    /** Open intents past their deadline, for the sweeper. */
    static final String VIEW_OPEN_BY_OPENED_AT = "open_by_opened_at";

    /** Unresolved rows keyed by when they became unresolved, for retention. */
    static final String VIEW_UNRESOLVED_BY_UNRESOLVED_AT = "unresolved_by_unresolved_at";

    /** Completed rows keyed by when they completed, for retention. */
    static final String VIEW_CAPTURED_BY_CAPTURED_AT = "captured_by_captured_at";

    private final LineageStoreSupport support;

    public CouchCaptureIntentStore(LineageStoreSupport support) {
        this.support = support;
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
            // Deliberately absent: sequenceNumber, publishStatusByTarget, schemaVersion. Those
            // belong to a journal event, and this row never becomes one.
            if (!support.updateStrictCas(raw)) {
                // A lost CAS. Re-read to find out what it lost to, rather than assuming.
                Map<String, Object> now = support.readRawStrict(intent.documentId());
                if (now == null) {
                    return CaptureCompletion.ROW_UNREADABLE;
                }
                if (CaptureState.CAPTURED.name().equals(now.get("captureState"))) {
                    return CaptureCompletion.COMPLETED;
                }
                return CaptureCompletion.NOT_COMPLETABLE;
            }
            return CaptureCompletion.COMPLETED;
        } catch (Exception e) {
            logger.warn("Capture intent {} could not be completed: {}", intent.documentId(),
                    e.toString());
            return CaptureCompletion.ROW_UNREADABLE;
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
        Map<String, Object> params = new HashMap<>();
        params.put("descending", true);      // newest first
        params.put("limit", safeLimit + 1);  // one extra, purely to answer hasMore
        params.put("skip", safeOffset);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : support.queryRawView(DESIGN_DOC, VIEW_UNRESOLVED, params)) {
            if (repositoryId != null && !repositoryId.isBlank()
                    && !repositoryId.equals(raw.get("repositoryId"))) {
                continue;
            }
            rows.add(raw);
        }
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = rows.subList(0, safeLimit);
        }
        return new UnresolvedPage(List.copyOf(rows), safeLimit, safeOffset, hasMore);
    }

    /** One bounded page of a view, from the beginning of the key range up to {@code endKey}. */
    private List<Map<String, Object>> page(String view, Long startKey, long endKey, int batchLimit) {
        Map<String, Object> params = new HashMap<>();
        if (startKey != null) {
            params.put("startkey", startKey);
        }
        params.put("endkey", endKey);
        params.put("limit", batchLimit <= 0 ? DEFAULT_BATCH_LIMIT : batchLimit);
        return support.queryRawView(DESIGN_DOC, view, params);
    }

    /** Bounded because with leader election off, every replica walks the same batch. */
    static final int DEFAULT_BATCH_LIMIT = 200;

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
        return views;
    }
}
