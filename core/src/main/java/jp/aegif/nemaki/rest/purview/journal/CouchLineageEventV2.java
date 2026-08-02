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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CouchDB document form of a {@link LineageEventV2}.
 *
 * <h2>{@code type} is {@code lineage_event_v2}, and that is a safety property</h2>
 *
 * <p>Every already-deployed binary selects its journal views with
 * {@code doc.type === 'lineage_event'} and nothing else — that is §6-a's 撤回 2, the fact that
 * killed every "the old binary will just ignore v2" argument. Storing v2 under a <em>different</em>
 * type turns the remaining hazard around: an old binary's views structurally cannot return a v2
 * row, so it cannot claim one, publish one, or advance a cursor over one — even if a v2 document
 * reaches a mixed cluster that the scale-to-one procedure said could not exist. The fence stays
 * normative; this makes one of its failure modes impossible instead of merely forbidden.
 *
 * <p>The price is permanent: every <em>new</em> view and query added from Slice 2d-2 on must
 * cover both types, and missing one makes v2 invisible to the new binary — a silent gap. That
 * trade is accepted deliberately: the old binary cannot be fixed, the new one can be tested.
 *
 * <h2>Decode reconstructs through the canonical constructor</h2>
 *
 * <p>{@link LineageEventV2}'s constructor recomputes {@code processKey}, {@code deliveryId} and
 * {@code creationPayloadDigest} from the decoded fields and rejects mismatch — so every read
 * re-verifies the stored identity, and a tampered or torn document fails here rather than
 * publishing. There is deliberately no trusted path around it: a second construction contract
 * would admit rows production construction rejects. The consequence is documented in §3: once v2
 * writes begin, shape rules may only widen; narrowing is a new schema version.
 */
public final class CouchLineageEventV2 {

    /** Never {@code lineage_event}: that is the type the old binaries' views select. */
    public static final String TYPE = "lineage_event_v2";

    /** Same prefix as v1; version isolation comes from {@link #TYPE}, not the key. */
    static final String ID_PREFIX = CouchLineageEvent.ID_PREFIX;

    private CouchLineageEventV2() {
    }

    /** The document {@code _id}: prefix + the delivery identity, per §3. */
    public static String documentId(String deliveryId) {
        return CouchLineageEvent.journalDocumentId(deliveryId);
    }

    // ------------------------------------------------------------------ encode

    /** The creation form, without {@code _rev}. */
    public static Map<String, Object> toMap(LineageEventV2 event) {
        return toMap(event, null);
    }

    /** The update form. {@code rev} is required by CouchDB for anything but creation. */
    public static Map<String, Object> toMap(LineageEventV2 event, String rev) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", documentId(event.deliveryId()));
        if (rev != null && !rev.isBlank()) {
            doc.put("_rev", rev);
        }
        doc.put("type", TYPE);
        doc.put("schemaVersion", event.schemaVersion());
        doc.put("idempotencyKeyVersion", event.idempotencyKeyVersion());
        doc.put("eventId", event.eventId());
        doc.put("processKey", event.processKey());
        doc.put("deliveryId", event.deliveryId());
        doc.put("deliveryKind", event.delivery().kind().name());
        doc.put("delivery", deliveryToMap(event.delivery()));
        doc.put("repositoryId", event.repositoryId());
        doc.put("processType", event.processType().name());
        doc.put("operationId", event.operationId());
        doc.put("occurredAt", event.occurredAt());
        doc.put("chunkIndex", event.chunkIndex());
        doc.put("chunkCount", event.chunkCount());
        doc.put("sequenceNumber", event.sequenceNumber());
        if (event.correlationId() != null) {
            doc.put("correlationId", event.correlationId());
        }
        if (event.spoolRecordId() != null) {
            doc.put("spoolRecordId", event.spoolRecordId());
        }
        if (event.legacyEventKey() != null) {
            doc.put("legacyEventKey", event.legacyEventKey());
        }
        doc.put("inputs", endpointsToList(event.inputs()));
        doc.put("outputs", endpointsToList(event.outputs()));
        Map<String, String> status = new LinkedHashMap<>();
        event.publishStatusByTarget().forEach((target, s) -> status.put(target, s.name()));
        doc.put("publishStatusByTarget", status);
        doc.put("creationPayloadDigest", event.creationPayloadDigest());
        return doc;
    }

    private static Map<String, Object> deliveryToMap(LineageDelivery delivery) {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (delivery) {
            case LineageDelivery.Original original -> map.put("targets", original.targets());
            case LineageDelivery.Replay replay -> {
                map.put("originalDeliveryId", replay.originalDeliveryId());
                map.put("target", replay.target());
                map.put("generation", replay.generation());
            }
            case LineageDelivery.Repair repair -> {
                map.put("deadLetterId", repair.deadLetterId());
                map.put("generation", repair.generation());
            }
        }
        return map;
    }

    private static List<Map<String, Object>> endpointsToList(List<LineageEndpoint> endpoints) {
        List<Map<String, Object>> list = new ArrayList<>(endpoints.size());
        for (LineageEndpoint endpoint : endpoints) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", endpoint.kind().name());
            map.put("repositoryId", endpoint.repositoryId());
            map.put("catalogQualifiedName", endpoint.catalogQualifiedName());
            if (endpoint.objectId() != null) {
                map.put("objectId", endpoint.objectId());
            }
            if (endpoint.operationId() != null) {
                map.put("operationId", endpoint.operationId());
            }
            map.put("attributes", endpoint.attributes());
            list.add(map);
        }
        return list;
    }

    // ------------------------------------------------------------------ decode

    /**
     * @throws IllegalArgumentException if the document is not a well-formed v2 row, or if its
     *                                  stored identity does not match its contents
     */
    @SuppressWarnings("unchecked")
    public static LineageEventV2 fromMap(Map<String, Object> doc) {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        // A schemaVersion-2 document typed lineage_event would be returned by the old binaries'
        // views — the exact exposure the distinct type exists to prevent. Loud, immediately.
        String type = asString(doc.get("type"));
        if (!TYPE.equals(type)) {
            throw new IllegalArgumentException("a v2 journal document must carry type=" + TYPE
                    + ", got '" + type + "' — under the v1 type it would be visible to old"
                    + " binaries' views");
        }

        LineageDelivery delivery = deliveryFromMap(
                requireString(doc, "deliveryKind"),
                (Map<String, Object>) doc.get("delivery"));

        LineageProcessType processType = processType(requireString(doc, "processType"));

        LineageEventV2 event = new LineageEventV2(
                requireInt(doc, "schemaVersion"),
                requireInt(doc, "idempotencyKeyVersion"),
                requireString(doc, "eventId"),
                requireString(doc, "processKey"),
                delivery,
                requireString(doc, "deliveryId"),
                requireString(doc, "repositoryId"),
                processType,
                requireString(doc, "operationId"),
                requireString(doc, "occurredAt"),
                endpointsFromList(doc.get("inputs"), "inputs"),
                endpointsFromList(doc.get("outputs"), "outputs"),
                requireInt(doc, "chunkIndex"),
                requireInt(doc, "chunkCount"),
                requireLong(doc, "sequenceNumber"),
                asString(doc.get("correlationId")),
                asString(doc.get("spoolRecordId")),
                asString(doc.get("legacyEventKey")),
                statusFromMap(doc.get("publishStatusByTarget")),
                requireString(doc, "creationPayloadDigest"));

        // The _id is derived from the deliveryId; a row stored under any other key is a row that
        // create-if-absent idempotency cannot see, which is how duplicates are born.
        String id = asString(doc.get("_id"));
        if (id != null && !id.equals(documentId(event.deliveryId()))) {
            throw new IllegalArgumentException("document _id '" + id + "' does not match the"
                    + " deliveryId-derived key '" + documentId(event.deliveryId()) + "'");
        }
        return event;
    }

    private static LineageDelivery deliveryFromMap(String kindName, Map<String, Object> map) {
        if (map == null) {
            throw new IllegalArgumentException("delivery sub-object is required");
        }
        DeliveryKind kind;
        try {
            kind = DeliveryKind.valueOf(kindName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown deliveryKind '" + kindName + "'");
        }
        // Dispatching on the tag and then requiring the tag's own fields is what makes a
        // disagreement (say, deliveryKind=REPLAY over a targets list) fail here: the fields the
        // REPLAY constructor requires are simply absent.
        return switch (kind) {
            case ORIGINAL -> {
                Object targets = map.get("targets");
                if (!(targets instanceof List<?> list)) {
                    throw new IllegalArgumentException("an ORIGINAL delivery requires targets");
                }
                yield new LineageDelivery.Original(
                        list.stream().map(String.class::cast).toList());
            }
            case REPLAY -> new LineageDelivery.Replay(
                    requireString(map, "originalDeliveryId"),
                    requireString(map, "target"),
                    requireLong(map, "generation"));
            case REPAIR -> new LineageDelivery.Repair(
                    requireString(map, "deadLetterId"),
                    requireLong(map, "generation"));
        };
    }

    @SuppressWarnings("unchecked")
    private static List<LineageEndpoint> endpointsFromList(Object value, String side) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(side + " must be a list");
        }
        List<LineageEndpoint> endpoints = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map)) {
                throw new IllegalArgumentException(side + " contains a non-object element");
            }
            Map<String, Object> map = (Map<String, Object>) element;
            EndpointKind kind;
            String kindName = requireString(map, "kind");
            try {
                kind = EndpointKind.valueOf(kindName);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown endpoint kind '" + kindName + "'");
            }
            Map<String, Object> attributes = map.get("attributes") instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) map.get("attributes"))
                    : new LinkedHashMap<>();
            normaliseCounts(kind, attributes);
            endpoints.add(new LineageEndpoint(
                    kind,
                    requireString(map, "catalogQualifiedName"),
                    requireString(map, "repositoryId"),
                    asString(map.get("objectId")),
                    asString(map.get("operationId")),
                    attributes));
        }
        return endpoints;
    }

    /**
     * JSON has one number type and Jackson picks the narrowest Java one, so a COUNT written as
     * {@code Long} comes back {@code Integer} when it is small. The canonical hash encodes both
     * identically — the digest does not move — but record equality would, and a round-trip that
     * is not {@code equals} to what it stored is a codec that cannot be tested. Normalised here,
     * where the drift happens, not in the endpoint, whose validation is A-1's and frozen.
     */
    private static void normaliseCounts(EndpointKind kind, Map<String, Object> attributes) {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            EndpointAttribute declared = kind.attribute(entry.getKey());
            if (declared == null || declared.type() != EndpointAttribute.Type.COUNT) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Long) {
                continue;
            }
            // Jackson narrows small longs to Integer; the Cloudant SDK's Gson path hands back
            // LazilyParsedNumber. Both are integral values wearing the wrong class, and the
            // validator's strictness (Integer|Long only) exists for producers, not for storage
            // round-trips — so normalise exactly: any Number whose value is a whole number in
            // long range becomes Long, and anything fractional stays as-is for the validator
            // to reject loudly.
            if (value instanceof Number n) {
                try {
                    entry.setValue(new java.math.BigDecimal(n.toString()).longValueExact());
                } catch (ArithmeticException | NumberFormatException notIntegral) {
                    // leave it; EndpointAttribute.validate reports it with the field name
                }
            }
        }
    }

    /**
     * Unknown status values throw, unlike the v1 codec's silent fall-back to {@code PENDING} —
     * which can re-publish an already-published event off the back of one mangled string. The
     * message says which half of the row is broken: the status map is <b>mutable state, outside
     * {@code creationPayloadDigest}</b>, so the immutable payload may be perfectly intact and the
     * row is repairable, not disposable.
     */
    private static Map<String, LineagePublishStatus> statusFromMap(Object value) {
        if (value == null) {
            // Absent is a fact (no targets recorded yet), not an error. Map.of() rather than a
            // mutable empty map: the two are equivalent to every caller, and the immutable one is
            // not a mutation target.
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("publishStatusByTarget must be an object");
        }
        Map<String, LineagePublishStatus> status = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String target = String.valueOf(entry.getKey());
            String name = String.valueOf(entry.getValue());
            try {
                status.put(target, LineagePublishStatus.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown publish status '" + name
                        + "' for target '" + target + "' — mutable-state corruption; the immutable"
                        + " payload may be intact, so repair the status rather than discarding"
                        + " the row");
            }
        }
        return status;
    }

    private static LineageProcessType processType(String name) {
        try {
            return LineageProcessType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown processType '" + name + "'");
        }
    }

    // ------------------------------------------------------------------ field helpers

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("field '" + key + "' is required and must be a"
                    + " non-blank string");
        }
        return s;
    }

    private static int requireInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("field '" + key + "' is required and must be a"
                    + " number");
        }
        try {
            return new java.math.BigDecimal(n.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("field '" + key + "' must be an exact integral"
                    + " int, got " + n);
        }
    }

    private static long requireLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("field '" + key + "' is required and must be a"
                    + " number");
        }
        try {
            return new java.math.BigDecimal(n.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("field '" + key + "' must be an exact integral"
                    + " long, got " + n);
        }
    }
}
