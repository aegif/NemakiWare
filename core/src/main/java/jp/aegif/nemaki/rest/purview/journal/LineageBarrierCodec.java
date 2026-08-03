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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The barrier document's strict codec (A-2 Slice 4a).
 *
 * <p>Strict in both directions, with one deliberate asymmetry: it refuses shapes that cannot
 * mean anything (an unknown state, a version outside {1, 2}, an ACK missing a required field)
 * but it PRESERVES a stale-generation or expired ACK. Those are not corrupt — they are the
 * evidence CAS conditions 3 and 5 exist to reject, and a decoder that dropped them would make
 * activation answer "no ACK from this node" where the truth is "an ACK from the wrong
 * generation".
 */
final class LineageBarrierCodec {

    private LineageBarrierCodec() {
    }

    static LineageWriteVersionBarrier decode(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        String rev = string(raw, "_rev", true);
        LineageWriteVersionBarrier.State state;
        try {
            state = LineageWriteVersionBarrier.State.valueOf(string(raw, "state", true));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown barrier state '"
                    + raw.get("state") + "'");
        }
        long generation = longValue(raw, "generation");
        int write = intValue(raw, "writeSchemaVersion");
        int minReader = intValue(raw, "minReaderSchemaVersion");

        List<LineageWriteVersionBarrier.NodeRef> nodes = new ArrayList<>();
        for (Object element : list(raw, "expectedNodes")) {
            Map<String, Object> node = asMap(element, "expectedNodes element");
            nodes.add(new LineageWriteVersionBarrier.NodeRef(string(node, "nodeId", true),
                    string(node, "bootId", true)));
        }
        String membershipDigest = string(raw, "expectedMembershipDigest", true);

        Map<String, LineageWriteVersionBarrier.Ack> acks = new LinkedHashMap<>();
        Object rawAcks = raw.get("acks");
        if (rawAcks != null) {
            for (Map.Entry<String, Object> entry : asMap(rawAcks, "acks").entrySet()) {
                acks.put(entry.getKey(), decodeAck(asMap(entry.getValue(),
                        "ack for '" + entry.getKey() + "'")));
            }
        }

        LineageWriteVersionBarrier barrier = new LineageWriteVersionBarrier(rev, state,
                generation, write, minReader, nodes, membershipDigest,
                stringSet(raw, "requiredCapabilities"), stringSet(raw, "approvedBinaryDigests"),
                acks);
        String recomputed = barrier.computeMembershipDigest();
        if (!recomputed.equals(membershipDigest)) {
            throw new IllegalArgumentException("the stored membership digest does not"
                    + " recompute from expectedNodes — the document was edited by hand or"
                    + " corrupted");
        }
        return barrier;
    }

    private static LineageWriteVersionBarrier.Ack decodeAck(Map<String, Object> raw) {
        return new LineageWriteVersionBarrier.Ack(
                longValue(raw, "generation"),
                string(raw, "bootId", true),
                string(raw, "binaryDigest", true),
                stringSet(raw, "capabilities"),
                intSet(raw, "readSchemaVersions"),
                intSet(raw, "spoolRecordSchemaVersions"),
                intSet(raw, "materializeEventSchemaVersions"),
                bool(raw, "spoolReady"),
                bool(raw, "drestReady"),
                stringList(raw, "drestViolations"),
                longValue(raw, "ackedAtMs"),
                longValue(raw, "expiresAtMs"));
    }

    /** The document body, with {@code _rev} included only when the barrier carries one. */
    static Map<String, Object> encode(LineageWriteVersionBarrier barrier) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("_id", LineageWriteVersionBarrier.DOCUMENT_ID);
        if (barrier.rev() != null && !barrier.rev().isBlank()) {
            raw.put("_rev", barrier.rev());
        }
        raw.put("type", "lineage_write_version");
        raw.put("state", barrier.state().name());
        raw.put("generation", barrier.generation());
        raw.put("writeSchemaVersion", (long) barrier.writeSchemaVersion());
        raw.put("minReaderSchemaVersion", (long) barrier.minReaderSchemaVersion());
        List<Object> nodes = new ArrayList<>();
        for (LineageWriteVersionBarrier.NodeRef node : barrier.expectedNodes()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("nodeId", node.nodeId());
            encoded.put("bootId", node.bootId());
            nodes.add(encoded);
        }
        raw.put("expectedNodes", nodes);
        raw.put("expectedMembershipDigest", barrier.expectedMembershipDigest());
        raw.put("requiredCapabilities", sortedList(barrier.requiredCapabilities()));
        raw.put("approvedBinaryDigests", sortedList(barrier.approvedBinaryDigests()));
        Map<String, Object> acks = new TreeMap<>();
        barrier.acks().forEach((nodeId, ack) -> acks.put(nodeId, encodeAck(ack)));
        raw.put("acks", acks);
        return raw;
    }

    private static Map<String, Object> encodeAck(LineageWriteVersionBarrier.Ack ack) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("generation", ack.generation());
        raw.put("bootId", ack.bootId());
        raw.put("binaryDigest", ack.binaryDigest());
        raw.put("capabilities", sortedList(ack.capabilities()));
        raw.put("readSchemaVersions", sortedInts(ack.readSchemaVersions()));
        raw.put("spoolRecordSchemaVersions", sortedInts(ack.spoolRecordSchemaVersions()));
        raw.put("materializeEventSchemaVersions",
                sortedInts(ack.materializeEventSchemaVersions()));
        raw.put("spoolReady", ack.spoolReady());
        raw.put("drestReady", ack.drestReady());
        raw.put("drestViolations", List.copyOf(ack.drestViolations()));
        raw.put("ackedAtMs", ack.ackedAtMs());
        raw.put("expiresAtMs", ack.expiresAtMs());
        return raw;
    }

    // ---------------------------------------------------------------- strict field access

    private static String string(Map<String, Object> raw, String field, boolean required) {
        Object value = raw.get(field);
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException("barrier field '" + field + "' is missing");
            }
            return null;
        }
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("barrier field '" + field + "' must be a"
                    + " non-blank string");
        }
        return s;
    }

    /**
     * A number that is EXACTLY integral. {@code longValue()} alone would truncate {@code 2.9}
     * to 2 and narrow {@code 4294967298} to 2 — either of which could pass off malformed
     * evidence as a valid schema version or generation.
     */
    private static long longValue(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("barrier field '" + field + "' must be a"
                    + " number, got " + (value == null ? "nothing" : value.getClass()
                            .getSimpleName()));
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            // Long.MAX_VALUE promotes to 2^63 as a double, so "d > Long.MAX_VALUE" lets
            // 0x1p63 through and casts it back to Long.MAX_VALUE — malformed evidence
            // becoming valid evidence. The exclusive bound is the representable one.
            if (d != Math.rint(d) || Double.isNaN(d) || Double.isInfinite(d)
                    || d >= 0x1p63 || d < -0x1p63) {
                throw new IllegalArgumentException("barrier field '" + field + "' must be an"
                        + " integral number, got " + d);
            }
            return (long) d;
        }
        if (n instanceof java.math.BigDecimal bd) {
            try {
                return bd.longValueExact();
            } catch (ArithmeticException notIntegral) {
                throw new IllegalArgumentException("barrier field '" + field + "' must be an"
                        + " integral number that fits a long, got " + bd);
            }
        }
        if (n instanceof java.math.BigInteger bi) {
            try {
                return bi.longValueExact();
            } catch (ArithmeticException tooBig) {
                throw new IllegalArgumentException("barrier field '" + field + "' does not fit"
                        + " a long: " + bi);
            }
        }
        return n.longValue();
    }

    /** An {@code int} field, refused rather than narrowed when it does not fit. */
    private static int intValue(Map<String, Object> raw, String field) {
        long value = longValue(raw, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("barrier field '" + field + "' does not fit an"
                    + " int: " + value);
        }
        return (int) value;
    }

    private static boolean bool(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException("barrier field '" + field + "' must be a"
                    + " boolean");
        }
        return b;
    }

    private static List<?> list(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> l)) {
            throw new IllegalArgumentException("barrier field '" + field + "' must be a list");
        }
        return l;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(what + " must be an object");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(what + " has a non-string key");
            }
        }
        return (Map<String, Object>) map;
    }

    private static Set<String> stringSet(Map<String, Object> raw, String field) {
        return new LinkedHashSet<>(stringList(raw, field));
    }

    private static List<String> stringList(Map<String, Object> raw, String field) {
        List<String> values = new ArrayList<>();
        for (Object element : list(raw, field)) {
            if (!(element instanceof String s)) {
                throw new IllegalArgumentException("barrier field '" + field + "' must hold"
                        + " strings");
            }
            values.add(s);
        }
        return values;
    }

    private static Set<Integer> intSet(Map<String, Object> raw, String field) {
        Set<Integer> values = new LinkedHashSet<>();
        List<?> elements = list(raw, field);
        for (int i = 0; i < elements.size(); i++) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put(field + "[" + i + "]", elements.get(i));
            // Same exact-integral rule as every other number: a schema version narrowed from
            // 4294967298 to 2 would satisfy condition 6 while meaning nothing.
            values.add(intValue(one, field + "[" + i + "]"));
        }
        return values;
    }

    private static List<String> sortedList(Set<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        return sorted;
    }

    private static List<Long> sortedInts(Set<Integer> values) {
        List<Long> sorted = new ArrayList<>();
        values.stream().sorted().forEach(v -> sorted.add(v.longValue()));
        return sorted;
    }
}
