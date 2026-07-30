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
 *   MAP     0x04  count:int32be  (key:STRING value)...   keys sorted, byte-order of UTF-8
 *   BOOL    0x05  0x00 | 0x01
 * </pre>
 *
 * <p>{@code null} and the empty string are therefore different encodings, as are an absent list
 * and an empty one. That distinction is deliberate: {@code operationId=null} and
 * {@code operationId=""} are not the same fact.
 *
 * <p>This encoding is frozen by {@code LineageCanonicalHashTest}'s golden vectors. Changing it
 * changes every processKey and deliveryId ever computed, so it may only change together with an
 * {@code idempotencyKeyVersion} bump.
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
        Map<String, Object> sorted = new TreeMap<>();
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
        List<String> names = new ArrayList<>();
        for (LineageEndpoint endpoint : endpoints) {
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint must not be null");
            }
            names.add(endpoint.catalogQualifiedName());
        }
        names.sort(null);
        for (int i = 1; i < names.size(); i++) {
            if (names.get(i).equals(names.get(i - 1))) {
                throw new IllegalArgumentException(
                        "duplicate endpoint in one event: " + names.get(i));
            }
        }
        return List.copyOf(names);
    }

    /** Target names in the canonical order identity uses: sorted, non-blank, deduplicated. */
    public static List<String> canonicalTargetSet(Iterable<String> targets) {
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
        canonical.sort(null);
        return List.copyOf(canonical);
    }
}
