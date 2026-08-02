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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON ⇄ {@link LineageSpoolPayloadV1}, strict in both directions.
 *
 * <p>Decode rejects rather than repairs: an unknown {@code spoolSchemaVersion}, a missing
 * field, an attribute value that is neither TEXT nor COUNT — each is an
 * {@link IllegalArgumentException} naming the field, and the scanner's answer to any of them
 * is quarantine, never a guess. COUNT attributes are normalised to {@link Long} on decode
 * (JSON narrows them to {@code Integer}), the same rule the v2 event codec applies.
 *
 * <p>Decode does <b>not</b> verify the hashes — that is
 * {@link LineageSpoolPayloadV1#selfVerifies()}, which recomputes them from content. Keeping
 * the steps separate keeps "the JSON is malformed" and "the content does not match its
 * identity" as the two distinct quarantine reasons they are.
 */
public final class LineageSpoolCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final java.util.Set<String> ROOT_FIELDS = java.util.Set.of(
            "spoolSchemaVersion", "spoolRecordId", "repositoryId", "processType",
            "operationId", "occurredAt", "inputs", "outputs", "canonicalTargetSet",
            "chunkIndex", "chunkCount", "correlationId", "legacyV1Projection",
            "payloadDigest");
    private static final java.util.Set<String> ENDPOINT_FIELDS = java.util.Set.of(
            "kind", "catalogQualifiedName", "repositoryId", "objectId", "operationId",
            "attributes");
    private static final java.util.Set<String> LEGACY_FIELDS = java.util.Set.of(
            "processType", "inputs", "outputs", "snapshotAttributes", "presetEventId");

    private LineageSpoolCodec() {
    }

    public static String encode(LineageSpoolPayloadV1 payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("spoolSchemaVersion", payload.spoolSchemaVersion());
        root.put("spoolRecordId", payload.spoolRecordId());
        root.put("repositoryId", payload.repositoryId());
        root.put("processType", payload.processType().name());
        root.put("operationId", payload.operationId());
        root.put("occurredAt", payload.occurredAt());
        root.set("inputs", endpointsNode(payload.inputs()));
        root.set("outputs", endpointsNode(payload.outputs()));
        ArrayNode targets = root.putArray("canonicalTargetSet");
        payload.canonicalTargetSet().forEach(targets::add);
        root.put("chunkIndex", payload.chunkIndex());
        root.put("chunkCount", payload.chunkCount());
        if (payload.correlationId() == null) {
            root.putNull("correlationId");
        } else {
            root.put("correlationId", payload.correlationId());
        }
        root.set("legacyV1Projection", legacyNode(payload.legacyV1Projection()));
        root.put("payloadDigest", payload.payloadDigest());
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("spool payload could not be encoded", e);
        }
    }

    public static LineageSpoolPayloadV1 decode(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("spool record is not JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("spool record is not a JSON object");
        }
        requireExactFields(root, ROOT_FIELDS, "spool record");
        long schemaVersion = requiredLong(root, "spoolSchemaVersion");
        if (schemaVersion != LineageSpoolPayloadV1.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unknown spoolSchemaVersion " + schemaVersion
                    + " — this build decodes only " + LineageSpoolPayloadV1.SCHEMA_VERSION);
        }
        return new LineageSpoolPayloadV1(
                schemaVersion,
                requiredText(root, "spoolRecordId"),
                requiredText(root, "repositoryId"),
                processType(requiredText(root, "processType")),
                requiredText(root, "operationId"),
                requiredText(root, "occurredAt"),
                endpoints(root, "inputs"),
                endpoints(root, "outputs"),
                textList(root, "canonicalTargetSet"),
                requiredLong(root, "chunkIndex"),
                requiredLong(root, "chunkCount"),
                nullableNonBlankText(root, "correlationId"),
                legacyProjection(root.get("legacyV1Projection")),
                requiredText(root, "payloadDigest"));
    }

    /**
     * Strictness is symmetry: every representation this accepts must be one {@link #encode}
     * produces, or the digest would cover something other than the bytes on disk. Unknown
     * fields, duplicate keys (rejected by the parser feature), blank nullable strings and
     * out-of-range integers are all representations encode never writes.
     */
    private static void requireExactFields(JsonNode node, java.util.Set<String> expected,
                                           String what) {
        java.util.Set<String> actual = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            java.util.Set<String> unknown = new java.util.LinkedHashSet<>(actual);
            unknown.removeAll(expected);
            java.util.Set<String> missing = new java.util.LinkedHashSet<>(expected);
            missing.removeAll(actual);
            throw new IllegalArgumentException(what + " has the wrong field set"
                    + (unknown.isEmpty() ? "" : " — unknown: " + unknown)
                    + (missing.isEmpty() ? "" : " — missing: " + missing));
        }
    }

    // ------------------------------------------------------------------ encode helpers

    private static ArrayNode endpointsNode(List<LineageEndpoint> endpoints) {
        ArrayNode array = MAPPER.createArrayNode();
        for (LineageEndpoint endpoint : endpoints) {
            ObjectNode node = array.addObject();
            node.put("kind", endpoint.kind().name());
            node.put("catalogQualifiedName", endpoint.catalogQualifiedName());
            node.put("repositoryId", endpoint.repositoryId());
            putNullable(node, "objectId", endpoint.objectId());
            putNullable(node, "operationId", endpoint.operationId());
            ObjectNode attributes = node.putObject("attributes");
            for (Map.Entry<String, Object> attribute : endpoint.attributes().entrySet()) {
                Object value = attribute.getValue();
                if (value instanceof String s) {
                    attributes.put(attribute.getKey(), s);
                } else if (value instanceof Long l) {
                    attributes.put(attribute.getKey(), l);
                } else if (value instanceof Integer i) {
                    attributes.put(attribute.getKey(), i.longValue());
                } else {
                    throw new IllegalArgumentException("attribute '" + attribute.getKey()
                            + "' is neither TEXT nor COUNT: "
                            + (value == null ? "null" : value.getClass().getSimpleName()));
                }
            }
        }
        return array;
    }

    private static JsonNode legacyNode(LineageFact.LegacyV1Projection projection) {
        if (projection == null) {
            return MAPPER.nullNode();
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("processType", projection.processType().name());
        ArrayNode inputs = node.putArray("inputs");
        projection.inputs().forEach(inputs::add);
        ArrayNode outputs = node.putArray("outputs");
        projection.outputs().forEach(outputs::add);
        ObjectNode snapshot = node.putObject("snapshotAttributes");
        projection.snapshotAttributes().forEach(snapshot::put);
        putNullable(node, "presetEventId", projection.presetEventId());
        return node;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    // ------------------------------------------------------------------ decode helpers

    private static List<LineageEndpoint> endpoints(JsonNode root, String field) {
        JsonNode array = root.get(field);
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("'" + field + "' must be an array");
        }
        List<LineageEndpoint> endpoints = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            if (!node.isObject()) {
                throw new IllegalArgumentException("'" + field + "' entries must be objects");
            }
            requireExactFields(node, ENDPOINT_FIELDS, "endpoint");
            endpoints.add(new LineageEndpoint(
                    endpointKind(requiredText(node, "kind")),
                    requiredText(node, "catalogQualifiedName"),
                    requiredText(node, "repositoryId"),
                    nullableText(node, "objectId"),
                    nullableText(node, "operationId"),
                    attributes(node.get("attributes"))));
        }
        return endpoints;
    }

    private static Map<String, Object> attributes(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("'attributes' must be an object");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isTextual()) {
                attributes.put(entry.getKey(), value.textValue());
            } else if (value.isIntegralNumber() && value.canConvertToLong()) {
                attributes.put(entry.getKey(), value.longValue());
            } else {
                throw new IllegalArgumentException("attribute '" + entry.getKey()
                        + "' is neither TEXT nor COUNT");
            }
        });
        return attributes;
    }

    private static LineageFact.LegacyV1Projection legacyProjection(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("'legacyV1Projection' must be an object or null");
        }
        requireExactFields(node, LEGACY_FIELDS, "legacyV1Projection");
        Map<String, String> snapshot = new LinkedHashMap<>();
        JsonNode snapshotNode = node.get("snapshotAttributes");
        if (snapshotNode == null || !snapshotNode.isObject()) {
            throw new IllegalArgumentException("'legacyV1Projection.snapshotAttributes' must be"
                    + " an object");
        }
        snapshotNode.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("legacy snapshot attribute '"
                        + entry.getKey() + "' must be a string");
            }
            snapshot.put(entry.getKey(), entry.getValue().textValue());
        });
        return new LineageFact.LegacyV1Projection(
                processType(requiredText(node, "processType")),
                textList(node, "inputs"),
                textList(node, "outputs"),
                snapshot,
                nullableNonBlankText(node, "presetEventId"));
    }

    private static LineageProcessType processType(String name) {
        try {
            return LineageProcessType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown processType '" + name + "'");
        }
    }

    private static EndpointKind endpointKind(String name) {
        try {
            return EndpointKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown endpoint kind '" + name + "'");
        }
    }

    private static List<String> textList(JsonNode root, String field) {
        JsonNode array = root.get(field);
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("'" + field + "' must be an array");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            if (!node.isTextual()) {
                throw new IllegalArgumentException("'" + field + "' entries must be strings");
            }
            values.add(node.textValue());
        }
        return values;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException("'" + field + "' must be a non-blank string");
        }
        return node.textValue();
    }

    private static String nullableText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("'" + field + "' must be a string or null");
        }
        return node.textValue();
    }

    /**
     * Nullable, but a blank string is rejected: the producer types normalise blank to null
     * ({@code LegacyV1Projection.presetEventId}, {@code LineageFact.correlationId}), so a
     * blank on disk is a representation encode never writes — and one whose recomputed digest
     * would not describe the file's bytes.
     */
    private static String nullableNonBlankText(JsonNode root, String field) {
        String value = nullableText(root, field);
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' must be null or non-blank");
        }
        return value;
    }

    private static long requiredLong(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw new IllegalArgumentException("'" + field + "' must be an integer");
        }
        if (!node.canConvertToLong()) {
            throw new IllegalArgumentException("'" + field + "' does not fit in a signed long");
        }
        return node.longValue();
    }
}
