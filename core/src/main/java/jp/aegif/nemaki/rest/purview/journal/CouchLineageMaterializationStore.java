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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The convergent materializer's storage (v2.3.18 ⑦), moved out of
 * {@link CouchLineageJournalStore} one method at a time, unchanged.
 *
 * <p>Unlike the barrier seam, this responsibility genuinely shares the journal's database,
 * provisioning and strict-IO rules — so it takes {@link LineageStoreSupport}, which names
 * exactly that basis and nothing else. It does not see the facade: a delegate holding the
 * concrete store could reach every other responsibility, which is the cycle the split exists
 * to remove.
 *
 * <p>Nothing here changed in the move: same documents, same ids, same create-if-absent and
 * digest-equality rules, same exception classification.
 */
final class CouchLineageMaterializationStore {

    static final String DECISION_TYPE = "lineage_materialization";

    private final LineageStoreSupport support;

    /**
     * Creating a v1 row needs a sequence number, which is the SEQUENCING responsibility's to
     * give. Held as its interface, not as the facade: this delegate may ask for a sequence and
     * for nothing else, and when sequencing is extracted in turn the wiring changes here and
     * nowhere else.
     */
    private final LineageSequencingStore sequencing;

    CouchLineageMaterializationStore(LineageStoreSupport support,
                                     LineageSequencingStore sequencing) {
        this.support = support;
        this.sequencing = sequencing;
    }

    LineageMaterializationDecision readDecision(String spoolRecordId) {
        if (spoolRecordId == null || spoolRecordId.isBlank()) {
            throw new IllegalArgumentException("spoolRecordId must not be blank");
        }
        support.ensureDatabase();
        Map<String, Object> raw =
                support.readRawStrict("lineage_materialization:" + spoolRecordId);
        if (raw == null) {
            return null;
        }
        try {
            return decisionFromRaw(raw);
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException(
                    "undecodable materialization decision '" + spoolRecordId + "': "
                            + e.getMessage(), e);
        }
    }

    private static LineageMaterializationDecision decisionFromRaw(Map<String, Object> raw) {
        if (!DECISION_TYPE.equals(raw.get("type"))) {
            throw new IllegalArgumentException("not a materialization decision: type="
                    + raw.get("type"));
        }
        String spoolRecordId = requireString(raw, "spoolRecordId");
        String expectedId = "lineage_materialization:" + spoolRecordId;
        if (!expectedId.equals(raw.get("_id"))) {
            throw new IllegalArgumentException("decision _id '" + raw.get("_id")
                    + "' does not match its spoolRecordId");
        }
        long schemaLong = longOf(raw.get("materializeSchemaVersion"),
                "materializeSchemaVersion");
        if (schemaLong != 1L && schemaLong != 2L) {
            // Validated BEFORE narrowing: 4294967297 must never masquerade as schema 1.
            throw new IllegalArgumentException("materializeSchemaVersion must be 1 or 2, got "
                    + schemaLong);
        }
        int schemaVersion = (int) schemaLong;
        Object entriesValue = raw.get("planEntries");
        if (!(entriesValue instanceof List<?> entryList) || entryList.isEmpty()) {
            throw new IllegalArgumentException("planEntries must be a non-empty list");
        }
        List<LineageMaterializationDecision.PlanEntry> entries = new ArrayList<>();
        for (Object entryValue : entryList) {
            if (!(entryValue instanceof Map)) {
                throw new IllegalArgumentException("plan entry must be a map");
            }
            Map<String, Object> e = (Map<String, Object>) entryValue;
            if (schemaVersion == 1) {
                entries.add(new LineageMaterializationDecision.V1Entry(
                        requireString(e, "eventId"), requireString(e, "v1EventDigest")));
            } else {
                entries.add(new LineageMaterializationDecision.V2Entry(
                        longOf(e.get("chunkIndex"), "chunkIndex"),
                        requireString(e, "deliveryId"), requireString(e, "eventDigest")));
            }
        }
        // Version dispatch (v2.3.22 B5/C1): absent means V2 — historical decisions decode
        // exactly as they always did, multi-entry shapes included.
        long planDigestVersion = raw.containsKey("planDigestVersion")
                ? longOf(raw.get("planDigestVersion"), "planDigestVersion") : 2L;
        if (planDigestVersion != 2L && planDigestVersion != 3L) {
            throw new IllegalArgumentException("unknown planDigestVersion "
                    + planDigestVersion + " — this build cannot act on it");
        }
        Long partitionVersion = null;
        LineageChunkPlanner.ChunkLimits chunkLimits = null;
        Map<String, LineageMaterializationDecision.CreationClassification> classification =
                new LinkedHashMap<>();
        if (planDigestVersion == 2L) {
            // V3-only fields under V2 are a malformed document, not fields to ignore (F4).
            for (String forbidden : List.of("partitionVersion", "chunkLimits",
                    "creationClassification")) {
                if (raw.containsKey(forbidden)) {
                    throw new IllegalArgumentException("a V2 decision must not carry '"
                            + forbidden + "'");
                }
            }
        }
        if (planDigestVersion == 3L) {
            partitionVersion = longOf(raw.get("partitionVersion"), "partitionVersion");
            Object limitsValue = raw.get("chunkLimits");
            if (!(limitsValue instanceof Map)) {
                throw new IllegalArgumentException("a V3 decision requires chunkLimits");
            }
            Map<String, Object> limits = (Map<String, Object>) limitsValue;
            chunkLimits = new LineageChunkPlanner.ChunkLimits(
                    longOf(limits.get("maxEndpointsPerEvent"), "maxEndpointsPerEvent"),
                    longOf(limits.get("maxPayloadBytes"), "maxPayloadBytes"));
            Object classificationValue = raw.get("creationClassification");
            if (classificationValue != null) {
                if (!(classificationValue instanceof Map)) {
                    throw new IllegalArgumentException("creationClassification must be a map");
                }
                for (var e : ((Map<String, Object>) classificationValue).entrySet()) {
                    if (!(e.getValue() instanceof Map)) {
                        throw new IllegalArgumentException("classification entry must be a map");
                    }
                    Map<String, Object> entry = (Map<String, Object>) e.getValue();
                    Object reasonValue = entry.get("reason");
                    if (!(reasonValue instanceof Map)) {
                        throw new IllegalArgumentException("classification requires its reason");
                    }
                    Map<String, Object> reason = (Map<String, Object>) reasonValue;
                    classification.put(e.getKey(),
                            new LineageMaterializationDecision.CreationClassification(
                                    LineagePublishStatus.valueOf(requireString(entry, "status")),
                                    new LineageTargetLifecycle.TerminalReason(
                                            requireString(reason, "reason"),
                                            reason.get("detail") instanceof String d ? d : null,
                                            longOf(reason.get("atMs"), "atMs"))));
                }
            }
        }
        // The typed constructor recomputes the plan digest — tampered content throws here.
        return new LineageMaterializationDecision(spoolRecordId,
                requireString(raw, "factPayloadDigest"), schemaVersion,
                longOf(raw.get("barrierGeneration"), "barrierGeneration"),
                requireString(raw, "allocatedEventId"), entries,
                requireString(raw, "materializationPlanDigest"),
                longOf(raw.get("createdAtMs"), "createdAtMs"),
                (int) planDigestVersion, partitionVersion, chunkLimits, classification);
    }

    /** The owner's strict integral parse, under a short local name. Same implementation. */
    private static long longOf(Object value, String field) {
        return CouchLineageJournalStore.exactLong(value, field);
    }

    private static String requireString(Map<String, Object> map, String field) {
        if (!(map.get(field) instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return s;
    }
    LineageMaterializationStore.MaterializedV1Row readMaterializedV1RowStrict(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readRawStrict("lineage:" + eventId);
        if (raw == null) {
            return null;
        }
        try {
            return new LineageMaterializationStore.MaterializedV1Row(strictV1Event(raw, eventId),
                    raw.get("_rev") instanceof String r ? r : null);
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException("undecodable materialized v1 row 'lineage:"
                    + eventId + "': " + e.getMessage(), e);
        }
    }

    /**
     * The strict v1 materialization decoder (v2.3.21 B4): the exact writer shape, no
     * defaulting. Absence of the two writer-omitted-when-empty maps decodes as canonical
     * empty; present-but-wrong-type is refused.
     */
    @SuppressWarnings("unchecked")
    private static LineageEvent strictV1Event(Map<String, Object> raw, String eventId) {
        if (!"lineage_event".equals(raw.get("type"))) {
            throw new IllegalArgumentException("type must be lineage_event, got "
                    + raw.get("type"));
        }
        long schemaVersion = longOf(raw.get("schemaVersion"), "schemaVersion");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1, got " + schemaVersion);
        }
        String storedEventId = requireString(raw, "eventId");
        if (!storedEventId.equals(eventId) || !("lineage:" + eventId).equals(raw.get("_id"))) {
            throw new IllegalArgumentException("row identity disagrees with its _id");
        }
        LineageProcessType processType;
        try {
            processType = LineageProcessType.valueOf(requireString(raw, "processType"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown processType " + raw.get("processType"));
        }
        List<String> inputs = strictStringList(raw.get("inputs"), "inputs");
        List<String> outputs = strictStringList(raw.get("outputs"), "outputs");
        Map<String, String> snapshot;
        Object sa = raw.get("snapshotAttributes");
        if (sa == null && !raw.containsKey("snapshotAttributes")) {
            snapshot = Map.of(); // writer omits when empty — absence IS canonical empty
        } else if (sa instanceof Map) {
            snapshot = new LinkedHashMap<>();
            for (var e : ((Map<Object, Object>) sa).entrySet()) {
                if (!(e.getKey() instanceof String k) || !(e.getValue() instanceof String v)) {
                    throw new IllegalArgumentException("snapshotAttributes must map strings"
                            + " to strings");
                }
                ((LinkedHashMap<String, String>) snapshot).put(k, v);
            }
        } else {
            throw new IllegalArgumentException("snapshotAttributes present but not a map");
        }
        Map<String, LineagePublishStatus> statuses;
        Object ps = raw.get("publishStatusByTarget");
        if (ps == null && !raw.containsKey("publishStatusByTarget")) {
            statuses = Map.of();
        } else if (ps instanceof Map) {
            statuses = new LinkedHashMap<>();
            for (var e : ((Map<Object, Object>) ps).entrySet()) {
                if (!(e.getKey() instanceof String k) || !(e.getValue() instanceof String v)) {
                    throw new IllegalArgumentException("publishStatusByTarget must map"
                            + " strings to status names");
                }
                ((LinkedHashMap<String, LineagePublishStatus>) statuses)
                        .put(k, LineagePublishStatus.valueOf(v));
            }
        } else {
            throw new IllegalArgumentException("publishStatusByTarget present but not a map");
        }
        long sequence = longOf(raw.get("sequenceNumber"), "sequenceNumber");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 0");
        }
        return new LineageEvent((int) schemaVersion, storedEventId,
                requireString(raw, "eventKey"), sequence, requireString(raw, "occurredAt"),
                requireString(raw, "repositoryId"), processType, inputs, outputs,
                requireStringAllowEmpty(raw, "runId"),
                requireStringAllowEmpty(raw, "correlationId"),
                (int) longOf(raw.get("version"), "version"), snapshot, statuses);
    }

    private static String requireStringAllowEmpty(Map<String, Object> map, String field) {
        if (!(map.get(field) instanceof String s)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return s;
    }

    private static List<String> strictStringList(Object value, String what) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(what + " must be a list");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new IllegalArgumentException(what + " must contain only strings");
            }
            out.add(s);
        }
        return out;
    }

    void createMaterializedV1RowIfAbsent(LineageEvent event,
            String expectedV1EventDigest) {
        if (event == null || expectedV1EventDigest == null
                || expectedV1EventDigest.isBlank()) {
            throw new IllegalArgumentException("event and expected digest are required");
        }
        support.ensureDatabase();
        LineageMaterializationStore.MaterializedV1Row existing = readMaterializedV1RowStrict(event.eventId());
        if (existing != null) {
            verifyMaterializedV1Digest(existing.event(), expectedV1EventDigest);
            return;
        }
        // The fenced allocator, never the eager v1 helper: counter-required,
        // watermark-checked, fail-closed. A burned number on a lost race is an accepted gap.
        long sequence = sequencing.allocateSequenceFenced(event.repositoryId());
        LineageEvent sequenced = new LineageEvent(event.schemaVersion(), event.eventId(),
                event.eventKey(), sequence, event.occurredAt(), event.repositoryId(),
                event.processType(), event.inputs(), event.outputs(), event.runId(),
                event.correlationId(), event.version(), event.snapshotAttributes(),
                event.publishStatusByTarget());
        Map<String, Object> doc = new CouchLineageEvent(sequenced).toMap();
        try {
            com.ibm.cloud.cloudant.v1.model.Document sdkDoc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new HashMap<>(doc);
            Object id = withoutMeta.remove("_id");
            withoutMeta.remove("_rev");
            sdkDoc.setProperties(withoutMeta);
            sdkDoc.setId((String) id);
            support.client().getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .docId((String) id)
                            .document(sdkDoc)
                            .build())
                    .execute();
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException conflict) {
            LineageMaterializationStore.MaterializedV1Row occupant = readMaterializedV1RowStrict(event.eventId());
            if (occupant == null) {
                throw new LineageSequencingStore.SequencingStorageException("v1 row occupant for '"
                        + event.eventId() + "' vanished after 409", null);
            }
            verifyMaterializedV1Digest(occupant.event(), expectedV1EventDigest);
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException("materialized v1 create failed for '"
                    + event.eventId() + "'", e);
        }
    }

    private static void verifyMaterializedV1Digest(LineageEvent stored, String expected) {
        String recomputed = LineageSpoolIdentity.v1EventDigest(stored.eventId(),
                stored.eventKey(), stored.repositoryId(), stored.processType(),
                stored.inputs(), stored.outputs(), stored.snapshotAttributes(),
                stored.occurredAt(), stored.correlationId());
        if (!recomputed.equals(expected)) {
            throw new LineageIntegrityException("lineage:" + stored.eventId(), recomputed,
                    "materialized v1 occupant disagrees with the frozen plan entry");
        }
    }


    // ---------------------------------------------------------------
    // §6-a barrier seam — delegated (see CouchLineageBarrierStore)
    // ---------------------------------------------------------------
    LineageMaterializationDecision createDecisionIfAbsent(
            LineageMaterializationDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        support.ensureDatabase();
        Map<String, Object> doc = decisionToRaw(decision);
        try {
            com.ibm.cloud.cloudant.v1.model.Document sdkDoc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new HashMap<>(doc);
            withoutMeta.remove("_id");
            sdkDoc.setProperties(withoutMeta);
            sdkDoc.setId(decision.documentId());
            support.client().getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .docId(decision.documentId())
                            .document(sdkDoc)
                            .build())
                    .execute();
            return decision;
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException conflict) {
            LineageMaterializationDecision stored =
                    readDecision(decision.spoolRecordId());
            if (stored == null) {
                throw new LineageSequencingStore.SequencingStorageException("decision occupant for '"
                        + decision.spoolRecordId() + "' vanished after 409", null);
            }
            if (!stored.factPayloadDigest().equals(decision.factPayloadDigest())
                    || !stored.materializationPlanDigest()
                            .equals(decision.materializationPlanDigest())) {
                throw new LineageIntegrityException(decision.documentId(),
                        stored.materializationPlanDigest(),
                        "decision occupant disagrees — a different fact or plan already"
                                + " committed under this spoolRecordId");
            }
            // Benign collision: the STORED decision's allocations are the frozen truth.
            if (support.metrics() != null) {
                support.metrics().recordDecisionCollision();
            }
            return stored;
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException("decision create failed for '"
                    + decision.spoolRecordId() + "'", e);
        }
    }


    private static Map<String, Object> decisionToRaw(LineageMaterializationDecision d) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", d.documentId());
        doc.put("type", DECISION_TYPE);
        doc.put("spoolRecordId", d.spoolRecordId());
        doc.put("factPayloadDigest", d.factPayloadDigest());
        doc.put("materializeSchemaVersion", (long) d.materializeSchemaVersion());
        doc.put("barrierGeneration", d.barrierGeneration());
        doc.put("allocatedEventId", d.allocatedEventId());
        doc.put("planEntries", d.planEntries().stream()
                .map(LineageMaterializationDecision.PlanEntry::asRecord).toList());
        doc.put("materializationPlanDigest", d.materializationPlanDigest());
        doc.put("createdAtMs", d.createdAtMs());
        // v2.3.22: absent planDigestVersion means V2 (historical decisions), so only V3
        // decisions write the version and its chunk-aware fields.
        if (d.planDigestVersion() == 3) {
            doc.put("planDigestVersion", 3L);
            doc.put("partitionVersion", d.partitionVersion());
            doc.put("chunkLimits", java.util.Map.of(
                    "maxEndpointsPerEvent", d.chunkLimits().maxEndpointsPerEvent(),
                    "maxPayloadBytes", d.chunkLimits().maxPayloadBytes()));
            Map<String, Object> classification = new LinkedHashMap<>();
            d.creationClassification().forEach((target, c) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("status", c.status().name());
                Map<String, Object> reason = new LinkedHashMap<>();
                reason.put("reason", c.reason().reason());
                reason.put("detail", c.reason().detail());
                reason.put("atMs", c.reason().atMs());
                entry.put("reason", reason);
                classification.put(target, entry);
            });
            doc.put("creationClassification", classification);
        }
        return doc;
    }

    @SuppressWarnings("unchecked")

    void appendV2Classified(LineageEventV2 event,
            Map<String, LineageMaterializationDecision.CreationClassification> classification) {
        if (event == null || classification == null || classification.isEmpty()) {
            throw new IllegalArgumentException("event and a non-empty classification are"
                    + " required");
        }
        if (!event.publishStatusByTarget().keySet().equals(classification.keySet())) {
            throw new IllegalArgumentException("the classified targets and the event's status"
                    + " targets must be exactly equal");
        }
        for (var e : classification.entrySet()) {
            if (event.publishStatusByTarget().get(e.getKey()) != e.getValue().status()) {
                throw new IllegalArgumentException("target '" + e.getKey() + "' status"
                        + " disagrees with its classification");
            }
        }
        if (event.sequenceNumber() != 0) {
            throw new IllegalArgumentException("a classified append is still an UNSEQUENCED"
                    + " row: sequences are the fenced sequencer's");
        }
        support.ensureDatabase();
        Map<String, Object> doc = new LinkedHashMap<>(CouchLineageEventV2.toMap(event));
        doc.put("state", LineageJournalRowV2.SequencingState.UNSEQUENCED.name());
        Map<String, Object> reasons = new LinkedHashMap<>();
        classification.forEach((target, c) -> {
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("reason", c.reason().reason());
            reason.put("detail", c.reason().detail());
            reason.put("atMs", c.reason().atMs());
            reasons.put(target, reason);
        });
        doc.put("v2TerminalReasonByTarget", reasons);
        try {
            com.ibm.cloud.cloudant.v1.model.Document sdkDoc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new LinkedHashMap<>(doc);
            Object id = withoutMeta.remove("_id");
            withoutMeta.remove("_rev");
            sdkDoc.setProperties(withoutMeta);
            sdkDoc.setId((String) id);
            support.client().getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .docId((String) id)
                            .document(sdkDoc)
                            .build())
                    .execute();
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException conflict) {
            Map<String, Object> occupantRaw =
                    support.readV2RawStrict(event.deliveryId());
            if (occupantRaw == null) {
                throw new LineageSequencingStore.SequencingStorageException("classified occupant for '"
                        + event.deliveryId() + "' vanished after 409", null);
            }
            LineageJournalRowV2 occupant = support.decodeV2Strict(occupantRaw);
            if (!occupant.event().creationPayloadDigest()
                    .equals(event.creationPayloadDigest())) {
                throw new LineageIntegrityException(
                        CouchLineageEventV2.documentId(event.deliveryId()),
                        occupant.event().creationPayloadDigest(),
                        "classified occupant carries a different payload");
            }
            // The classification is part of what must match: a PENDING occupant at this key
            // is a DIFFERENT decision about the same payload.
            for (var e : classification.entrySet()) {
                LineageTargetLifecycle lifecycle =
                        occupant.targetLifecycles().get(e.getKey());
                boolean same = lifecycle != null
                        && lifecycle.status() == e.getValue().status()
                        && lifecycle.terminalReason() != null
                        && lifecycle.terminalReason().reason()
                                .equals(e.getValue().reason().reason())
                        && lifecycle.terminalReason().detail()
                                .equals(e.getValue().reason().detail())
                        && lifecycle.terminalReason().atMs() == e.getValue().reason().atMs();
                if (!same) {
                    throw new LineageIntegrityException(
                            CouchLineageEventV2.documentId(event.deliveryId()),
                            occupant.event().creationPayloadDigest(),
                            "classified occupant disagrees for target '" + e.getKey() + "'");
                }
            }
        } catch (RuntimeException e) {
            if (CouchLineageJournalStore.isDocumentTooLarge(e)) {
                throw new LineageMaterializationStore.DocumentTooLargeException("CouchDB refused the document for its"
                        + " size: " + e.getMessage(), e);
            }
            throw new LineageSequencingStore.SequencingStorageException("classified append failed for '"
                    + event.deliveryId() + "'", e);
        }
    }
}
