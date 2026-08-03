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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parent materialization decision (v2.3.18 ⑦, digest revised v2.3.21): one durable,
 * immutable commitment per spool fact — which schema version to write, under which barrier
 * generation, with which once-allocated audit id, producing exactly which journal rows.
 *
 * <p>There are no child decision documents: the plan entries ARE the per-row decisions, and
 * the journal rows converge by create-if-absent on the entry's identity plus digest-exact
 * collision. The {@code materializationPlanDigest} (domain {@code MATERIALIZATION_PLAN_V2})
 * binds spoolRecordId, factPayloadDigest, materializeSchemaVersion, allocatedEventId and the
 * complete entry list; it is <b>recomputed at every decode</b>, so a stored decision whose
 * allocations or entries were tampered never becomes a value.
 */
public record LineageMaterializationDecision(
        String spoolRecordId,
        String factPayloadDigest,
        int materializeSchemaVersion,
        long barrierGeneration,
        String allocatedEventId,
        List<PlanEntry> planEntries,
        String materializationPlanDigest,
        long createdAtMs
) {

    /** The frozen per-row commitment; exactly one shape per schema version. */
    public sealed interface PlanEntry permits V1Entry, V2Entry {
        /** The canonical-hash record encoding frozen by the plan digest formula. */
        Map<String, Object> asRecord();
    }

    /** v1: single unchunked row at {@code lineage:{eventId}}, identity = v1EventDigest. */
    public record V1Entry(String eventId, String v1EventDigest) implements PlanEntry {
        public V1Entry {
            if (eventId == null || eventId.isBlank()) {
                throw new IllegalArgumentException("v1 entry requires its eventId");
            }
            if (v1EventDigest == null || v1EventDigest.isBlank()) {
                throw new IllegalArgumentException("v1 entry requires its v1EventDigest");
            }
        }

        @Override
        public Map<String, Object> asRecord() {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("schemaVersion", 1L);
            record.put("eventId", eventId);
            record.put("v1EventDigest", v1EventDigest);
            return record;
        }
    }

    /** v2: one row per chunk at the deliveryId-derived key, identity = creationPayloadDigest. */
    public record V2Entry(long chunkIndex, String deliveryId, String eventDigest)
            implements PlanEntry {
        public V2Entry {
            if (chunkIndex < 0) {
                throw new IllegalArgumentException("chunkIndex must be >= 0");
            }
            if (deliveryId == null || deliveryId.isBlank()) {
                throw new IllegalArgumentException("v2 entry requires its deliveryId");
            }
            if (eventDigest == null || eventDigest.isBlank()) {
                throw new IllegalArgumentException("v2 entry requires its eventDigest");
            }
        }

        @Override
        public Map<String, Object> asRecord() {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("chunkIndex", chunkIndex);
            record.put("deliveryId", deliveryId);
            record.put("eventDigest", eventDigest);
            return record;
        }
    }

    public LineageMaterializationDecision {
        if (spoolRecordId == null || spoolRecordId.isBlank()) {
            throw new IllegalArgumentException("spoolRecordId must not be blank");
        }
        if (factPayloadDigest == null || factPayloadDigest.isBlank()) {
            throw new IllegalArgumentException("factPayloadDigest must not be blank");
        }
        if (materializeSchemaVersion != 1 && materializeSchemaVersion != 2) {
            throw new IllegalArgumentException("materializeSchemaVersion must be 1 or 2, got "
                    + materializeSchemaVersion);
        }
        if (barrierGeneration < 0) {
            throw new IllegalArgumentException("barrierGeneration must be >= 0");
        }
        if (allocatedEventId == null || allocatedEventId.isBlank()) {
            throw new IllegalArgumentException("allocatedEventId must not be blank — it is"
                    + " the once-allocated audit id the plan digest binds");
        }
        if (planEntries == null || planEntries.isEmpty()) {
            throw new IllegalArgumentException("a decision without plan entries decides"
                    + " nothing");
        }
        planEntries = List.copyOf(planEntries);
        for (PlanEntry entry : planEntries) {
            boolean shapeMatches = materializeSchemaVersion == 1
                    ? entry instanceof V1Entry
                    : entry instanceof V2Entry;
            if (!shapeMatches) {
                throw new IllegalArgumentException("plan entry shape " + entry.getClass()
                        .getSimpleName() + " contradicts materializeSchemaVersion "
                        + materializeSchemaVersion);
            }
        }
        if (materializeSchemaVersion == 1) {
            if (planEntries.size() != 1) {
                throw new IllegalArgumentException("v1 materialization is a single unchunked"
                        + " entry, got " + planEntries.size());
            }
            V1Entry sole = (V1Entry) planEntries.get(0);
            if (!sole.eventId().equals(allocatedEventId)) {
                throw new IllegalArgumentException("the v1 entry's eventId must be the"
                        + " decision's allocatedEventId — one allocation, one truth");
            }
        }
        if (createdAtMs <= 0) {
            throw new IllegalArgumentException("createdAtMs must be positive");
        }
        String recomputed = LineageSpoolIdentity.materializationPlanDigest(spoolRecordId,
                factPayloadDigest, materializeSchemaVersion, allocatedEventId,
                planEntries.stream().map(PlanEntry::asRecord).toList());
        if (!recomputed.equals(materializationPlanDigest)) {
            throw new IllegalArgumentException("materializationPlanDigest does not match its"
                    + " own content — a tampered decision never becomes a value");
        }
    }

    /** The CouchDB document id this decision lives under. */
    public String documentId() {
        return "lineage_materialization:" + spoolRecordId;
    }

    /** Convenience constructor computing the digest from the components. */
    public static LineageMaterializationDecision of(String spoolRecordId,
            String factPayloadDigest, int materializeSchemaVersion, long barrierGeneration,
            String allocatedEventId, List<PlanEntry> planEntries, long createdAtMs) {
        String digest = LineageSpoolIdentity.materializationPlanDigest(spoolRecordId,
                factPayloadDigest, materializeSchemaVersion, allocatedEventId,
                planEntries.stream().map(PlanEntry::asRecord).toList());
        return new LineageMaterializationDecision(spoolRecordId, factPayloadDigest,
                materializeSchemaVersion, barrierGeneration, allocatedEventId, planEntries,
                digest, createdAtMs);
    }
}
