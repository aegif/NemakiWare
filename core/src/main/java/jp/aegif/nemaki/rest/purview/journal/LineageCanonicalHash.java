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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Typed, length-prefixed serialization for the identity hashes in
 * {@link LineageIdentity}.
 *
 * <h2>Why not concatenation</h2>
 *
 * <p>Joining the parts with a separator cannot distinguish {@code ("ab","c")} from
 * {@code ("a","bc")} — an attacker, or an unlucky object name, can manufacture two different
 * business facts that hash to one identity. Any separator can appear inside a CMIS name, a
 * folder path or an external URI, so escaping it is a second problem rather than a solution.
 *
 * <h2>Encoding</h2>
 *
 * <p>Every value carries a one-byte type tag, and every variable-length value carries its length
 * before its bytes. Lengths and integers are fixed width, big-endian, so the encoding does not
 * vary with platform:
 *
 * <pre>
 *   NULL    0x00
 *   STRING  0x01  len:int32be  utf8bytes
 *   LONG    0x02  value:int64be
 *   LIST    0x03  count:int32be  element...
 *   MAP     0x04  count:int32be  (key:STRING value)...   keys sorted, see below
 *   BOOL    0x05  0x00 | 0x01
 * </pre>
 *
 * <h2>What "sorted" means</h2>
 *
 * <p>Unsigned lexicographic order over the UTF-8 bytes — the same rule in every language, and
 * deliberately not Java's natural {@code String} order, which compares UTF-16 code units and
 * disagrees above U+E000. Java and JavaScript sort strings one way, Python and Go another; a
 * repair or DLQ tool written outside this codebase would have produced different ids for the
 * same event.
 *
 * <p>Today's inputs are ASCII, where all of these coincide, so this costs nothing and is worth
 * fixing while no v2 event has been persisted. Nothing in the code constrains a repository id or
 * a target name to ASCII, so "it is ASCII in practice" was an observation and not a contract.</p>
 *
 * <p>{@code null} and the empty string are therefore different encodings, as are an absent list
 * and an empty one. That distinction is deliberate: {@code operationId=null} and
 * {@code operationId=""} are not the same fact.
 *
 * <p>This encoding is frozen by {@code LineageCanonicalHashTest}'s golden vectors. Changing it
 * changes every processKey and deliveryId ever computed, so it may only change together with an
 * {@code idempotencyKeyVersion} bump.
 *
 * <p>{@code core/src/test/resources/lineage/reference_hash.py} implements the same spec
 * independently and produces the same vectors. It is what a repair or DLQ tool outside the JVM
 * would be written from, and it is checked in so that "another language gets the same ids" is
 * something anyone can re-run rather than something this javadoc asserts.
 */
public final class LineageCanonicalHash {

    private static final byte TAG_NULL = 0x00;
    private static final byte TAG_STRING = 0x01;
    private static final byte TAG_LONG = 0x02;
    private static final byte TAG_LIST = 0x03;
    private static final byte TAG_MAP = 0x04;
    private static final byte TAG_BOOL = 0x05;

    private LineageCanonicalHash() {
    }

    /**
     * SHA-256 over the typed encoding of {@code parts}, as lowercase hex.
     *
     * @param parts String, Long/Integer, Boolean, List, Map or null. Anything else is rejected
     *              rather than silently stringified — a hash whose input depends on
     *              {@code toString()} is not a contract.
     */
    public static String hash(Object... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeList(out, parts == null ? List.of() : List.of(nullSafe(parts)));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /** {@link List#of} rejects nulls, and null is a value we must be able to encode. */
    private static Object[] nullSafe(Object[] parts) {
        Object[] copy = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            copy[i] = parts[i] == null ? NULL_SENTINEL : parts[i];
        }
        return copy;
    }

    private static final Object NULL_SENTINEL = new Object();

    private static void write(ByteArrayOutputStream out, Object value) {
        if (value == null || value == NULL_SENTINEL) {
            out.write(TAG_NULL);
        } else if (value instanceof String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            out.write(TAG_STRING);
            writeInt32(out, bytes.length);
            out.writeBytes(bytes);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short) {
            out.write(TAG_LONG);
            writeInt64(out, ((Number) value).longValue());
        } else if (value instanceof Boolean b) {
            out.write(TAG_BOOL);
            out.write(b ? 1 : 0);
        } else if (value instanceof List<?> list) {
            writeList(out, list);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(out, map);
        } else {
            throw new IllegalArgumentException(
                    "unhashable type " + value.getClass().getName()
                            + " — identity must not depend on toString()");
        }
    }

    private static void writeList(ByteArrayOutputStream out, List<?> list) {
        out.write(TAG_LIST);
        writeInt32(out, list.size());
        for (Object element : list) {
            write(out, element);
        }
    }

    /** Keys are sorted so that map iteration order cannot change the identity. */
    private static void writeMap(ByteArrayOutputStream out, Map<?, ?> map) {
        Map<String, Object> sorted = new TreeMap<>(UTF8_ORDER);
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("map keys must be String, got "
                        + (e.getKey() == null ? "null" : e.getKey().getClass().getName()));
            }
            sorted.put(key, e.getValue());
        }
        out.write(TAG_MAP);
        writeInt32(out, sorted.size());
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            write(out, e.getKey());
            write(out, e.getValue());
        }
    }

    /**
     * Unsigned lexicographic order over UTF-8 bytes.
     *
     * <p>{@code String.compareTo} would order by UTF-16 code unit instead, which puts a
     * supplementary character before U+E000..U+FFFF because its surrogates start at 0xD800. The
     * bytes are compared unsigned because a Java {@code byte} is signed and every UTF-8
     * continuation byte has the high bit set.
     */
    static final java.util.Comparator<String> UTF8_ORDER = (left, right) -> {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int shared = Math.min(a.length, b.length);
        for (int i = 0; i < shared; i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return a.length - b.length;
    };

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt64(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xFF));
        }
    }

    /**
     * Endpoint qualified names in the canonical order identity uses: sorted, duplicates rejected.
     *
     * <p>Producer order must not change identity — the same export selected in a different order
     * is the same export — but a repeated endpoint is a caller mistake rather than something to
     * silently collapse, because collapsing it would change the arity the catalog sees.
     */
    public static List<String> canonicalQualifiedNames(List<LineageEndpoint> endpoints) {
        if (endpoints == null) {
            throw new IllegalArgumentException("endpoint list must not be null");
        }
        List<LineageEndpoint> sorted = new ArrayList<>();
        for (LineageEndpoint endpoint : endpoints) {
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint must not be null");
            }
            sorted.add(endpoint);
        }
        // sorted as endpoints rather than as names, so the duplicate can be reported through the
        // kind-aware descriptor — an external name is reversible base64 of its stable key
        sorted.sort((a, b) -> UTF8_ORDER.compare(a.catalogQualifiedName(),
                b.catalogQualifiedName()));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            LineageEndpoint endpoint = sorted.get(i);
            if (i > 0 && endpoint.catalogQualifiedName()
                    .equals(sorted.get(i - 1).catalogQualifiedName())) {
                throw new IllegalArgumentException("duplicate endpoint in one event: "
                        + endpoint.describeQualifiedName());
            }
            names.add(endpoint.catalogQualifiedName());
        }
        return List.copyOf(names);
    }

    /**
     * Target names in the canonical order identity uses: trimmed, non-blank, deduplicated, sorted.
     *
     * <p>Trimming matters because the set comes from configuration: {@code "atlas, purview"} split
     * on the comma yields a leading space that must not fork the identity of the same delivery.
     *
     * <p>An empty set is accepted here and produces a well-formed id. Whether a journal record
     * with no delivery obligation should exist at all is the emitter's call, and is decided in
     * increment A-2 — this function has no way to tell "no targets configured" from "the caller
     * meant to pass none".
     */
    public static List<String> canonicalTargetSet(Iterable<String> targets) {
        if (targets == null) {
            throw new IllegalArgumentException("target set must not be null");
        }
        List<String> canonical = new ArrayList<>();
        for (String target : targets) {
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("target name must not be null or blank");
            }
            String trimmed = target.trim();
            if (!canonical.contains(trimmed)) {
                canonical.add(trimmed);
            }
        }
        canonical.sort(UTF8_ORDER);
        return List.copyOf(canonical);
    }
}
