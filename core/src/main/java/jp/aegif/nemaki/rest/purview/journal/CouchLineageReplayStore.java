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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

/**
 * §8-d's replay machine (D-rest-3), moved out of {@link CouchLineageJournalStore} unchanged.
 *
 * <p>It writes to the same v2 rows the transition machine does, so it takes the shared
 * {@link LineageStoreSupport} basis rather than its own client. The generation CAS, the
 * REQUESTED → CREATED → ACKED states, the permanently durable FAILED and the reread-driven
 * convergence are exactly the ones that were here before.
 */
final class CouchLineageReplayStore {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(CouchLineageReplayStore.class);

    private final LineageStoreSupport support;

    /** The configured targets: a compensation for an unconfigured one could never be claimed. */
    private final LineageConfig lineageConfig;

    CouchLineageReplayStore(LineageStoreSupport support, LineageConfig lineageConfig) {
        this.support = support;
        this.lineageConfig = lineageConfig;
    }

    LineageV2ReplayStore.ReplayGrant requestReplay(String recordId, String target) {
        if (recordId == null || recordId.isBlank() || target == null) {
            throw new IllegalArgumentException("recordId and target are required");
        }
        String canonicalTarget = target.trim();
        if (canonicalTarget.isBlank()) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target must not be blank");
        }
        // Null-safe for the direct-client test construction; production always injects.
        if (lineageConfig != null && !lineageConfig.getTargets().contains(canonicalTarget)) {
            // An unconfigured target would create a compensation nothing can ever claim, and
            // readiness never verified its sink.
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' is not currently configured (lineage.targets)");
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readV2RawStrict(recordId);
        if (raw == null) {
            throw new LineageV2ReplayStore.ReplayRefusedException("row '" + recordId
                    + "' does not exist");
        }
        if (!"lineage_event_v2".equals(raw.get("type"))) {
            throw new LineageV2ReplayStore.ReplayRefusedException("row '" + recordId
                    + "' is not a v2 row");
        }
        LineageJournalRowV2 row = support.decodeV2Strict(raw);
        if (row.state() != LineageJournalRowV2.SequencingState.SEQUENCED) {
            throw new LineageV2ReplayStore.ReplayRefusedException(
                    "only a SEQUENCED row can be replayed — this row is " + row.state());
        }
        LineageTargetLifecycle lifecycle = row.targetLifecycles().get(canonicalTarget);
        if (lifecycle == null) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' is not owed by this row — replay cannot invent a delivery");
        }
        if (lifecycle.hasLiveClaim()) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' holds a live " + lifecycle.status()
                    + " claim — replay must not steal a token-fenced claim");
        }
        if (!lifecycle.status().isTerminal()) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' is " + lifecycle.status() + " — only a terminal delivery is"
                    + " replayable (the live machine owns everything else)");
        }
        LineageReplayRequest existing = row.replayRequests().get(canonicalTarget);
        long generation;
        if (existing == null) {
            generation = 1L;
        } else if (existing.state() == LineageReplayRequest.State.ACKED) {
            generation = Math.addExact(existing.generation(), 1L);
        } else if (existing.state() == LineageReplayRequest.State.FAILED) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' has a durable FAILED replay request (generation "
                    + existing.generation() + ": " + existing.reason().reason()
                    + ") — it blocks new requests pending an audited repair");
        } else {
            throw new LineageV2ReplayStore.ReplayRefusedException("a replay request is"
                    + " already in progress for target '" + canonicalTarget + "' (state "
                    + existing.state() + ", generation " + existing.generation() + ")");
        }
        String requestId = java.util.UUID.randomUUID().toString();
        long nowMs = Instant.now().toEpochMilli();
        CouchLineageJournalRowV2.applyReplayRequested(raw, canonicalTarget, generation,
                requestId, nowMs);
        if (!support.updateStrictCas(raw)) {
            return null; // lost the race — the winner's request is in progress
        }
        if (support.metrics() != null) {
            support.metrics().recordReplayRequested(canonicalTarget);
        }
        return new LineageV2ReplayStore.ReplayGrant(recordId, canonicalTarget, generation, requestId);
    }

    boolean advanceReplay(String recordId, String target, String requestId,
            LineageReplayRequest.State expected, LineageReplayRequest.State next) {
        boolean allowed = (expected == LineageReplayRequest.State.REQUESTED
                && next == LineageReplayRequest.State.CREATED)
                || (expected == LineageReplayRequest.State.CREATED
                        && next == LineageReplayRequest.State.ACKED);
        if (!allowed) {
            throw new IllegalArgumentException("replay transition " + expected + "->" + next
                    + " is not in the §8-d table — caller bug, not a race");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId fence is required");
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = support.decodeV2Strict(raw);
        LineageReplayRequest current = row.replayRequests().get(target);
        if (current == null || current.state() != expected
                || !requestId.equals(current.requestId())) {
            return false;
        }
        CouchLineageJournalRowV2.applyReplayState(raw, target, next,
                Instant.now().toEpochMilli(), null);
        return support.updateStrictCas(raw);
    }

    boolean failReplay(String recordId, String target, String requestId,
            LineageTargetLifecycle.TerminalReason reason) {
        if (requestId == null || requestId.isBlank() || reason == null) {
            throw new IllegalArgumentException("requestId fence and reason are required");
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = support.decodeV2Strict(raw);
        LineageReplayRequest current = row.replayRequests().get(target);
        if (current == null || !current.isUnacked()
                || !requestId.equals(current.requestId())) {
            return false;
        }
        CouchLineageJournalRowV2.applyReplayState(raw, target,
                LineageReplayRequest.State.FAILED, Instant.now().toEpochMilli(), reason);
        boolean persisted = support.updateStrictCas(raw);
        if (persisted && support.metrics() != null) {
            support.metrics().recordReplayFailed(target);
        }
        return persisted;
    }

    List<LineageV2ReplayStore.ReplayRecovery> findUnackedReplayRequests(int limit) {
        support.ensureDatabase();
        List<LineageV2ReplayStore.ReplayRecovery> out = new ArrayList<>();
        // (documentId, target, requestId, generation) — one row with two active targets is
        // two recovery items, and a superseded request is a different item.
        java.util.Set<String> examined = new java.util.HashSet<>();
        Object pageStartKey = null;
        String pageStartDocId = null;
        int pageSize = Math.min(Math.max(limit, 1), 100);
        while (out.size() < limit) {
            ViewResult result;
            try {
                var builder = new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                        .db(support.client().getDatabaseName())
                        .ddoc(support.designDoc())
                        .view("v2_replay_requests_unacked")
                        .reduce(false)
                        .limit((long) pageSize);
                if (pageStartKey != null) {
                    // EXCLUSIVE continuation (F3): this scan is read-only, so the anchor row
                    // is still present on the next page — skip(1) steps past it and a corrupt
                    // first row can never pin a page even at limit 1.
                    builder.startKey(pageStartKey).skip(1L);
                }
                if (pageStartDocId != null) {
                    builder.startKeyDocId(pageStartDocId);
                }
                result = support.client().getClient().postView(builder.build())
                        .execute().getResult();
            } catch (RuntimeException e) {
                throw new LineageSequencingStore.SequencingStorageException(
                        "v2_replay_requests_unacked query failed", e);
            }
            if (result == null || result.getRows() == null) {
                throw new LineageSequencingStore.SequencingStorageException(
                        "v2_replay_requests_unacked returned no result", null);
            }
            List<ViewResultRow> rows = result.getRows();
            if (rows.isEmpty()) {
                return out;
            }
            for (ViewResultRow viewRow : rows) {
                if (out.size() >= limit) {
                    return out;
                }
                String docId = viewRow.getId();
                Object key = viewRow.getKey();
                String targetHint = key instanceof List<?> parts && parts.size() == 2
                        && parts.get(1) instanceof String t ? t : null;
                if (targetHint == null) {
                    logger.error("Replay scan skipping malformed view key {} on {}", key,
                            docId);
                    continue;
                }
                try {
                    Map<String, Object> raw = support.readRawStrict(docId);
                    if (raw == null) {
                        continue; // purged/changed under us — the view is a hint
                    }
                    LineageJournalRowV2 row = support.decodeV2Strict(raw);
                    LineageReplayRequest request = row.replayRequests().get(targetHint);
                    if (request == null || !request.isUnacked()) {
                        continue; // stale hint
                    }
                    String identity = docId + "|" + targetHint + "|" + request.requestId()
                            + "|" + request.generation();
                    if (!examined.add(identity)) {
                        continue;
                    }
                    String recordId = row.event().deliveryId();
                    out.add(new LineageV2ReplayStore.ReplayRecovery(recordId, targetHint, request));
                } catch (LineageSequencingStore.SequencingStorageException e) {
                    if (e.getMessage() != null
                            && e.getMessage().startsWith("undecodable v2 row")) {
                        logger.error("Replay scan skipping corrupt v2 row {}: {}", docId,
                                e.getMessage());
                        continue;
                    }
                    throw e;
                }
            }
            if (rows.size() < pageSize) {
                return out;
            }
            ViewResultRow last = rows.get(rows.size() - 1);
            pageStartKey = last.getKey();
            pageStartDocId = last.getId();
        }
        return out;
    }
}
