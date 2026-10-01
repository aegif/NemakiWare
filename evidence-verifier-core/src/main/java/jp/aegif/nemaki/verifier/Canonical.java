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
package jp.aegif.nemaki.verifier;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The typed canonical encoding of {@code docs/design/evidence-profile-v1.md} §3.
 *
 * <p><b>Written from the specification, not copied from the product.</b> That is the whole
 * point of this module: a verifier that shared the writer's encoder would verify that the
 * writer agrees with itself. The two implementations are held to the same vectors instead, and
 * a third party writing a third one from the same document gets the same bytes.
 *
 * <p>If this ever needs to change to match the product, the product is wrong or the
 * specification is — and in either case the fix is not here.
 */
public final class Canonical {

    private static final byte TAG_NULL = 0x00;
    private static final byte TAG_STRING = 0x01;
    private static final byte TAG_LONG = 0x02;
    private static final byte TAG_LIST = 0x03;
    private static final byte TAG_MAP = 0x04;
    private static final byte TAG_BOOL = 0x05;

    private Canonical() {
    }

    /**
     * Unsigned lexicographic order over UTF-8 bytes — §3.1.
     *
     * <p>Not {@code String.compareTo}: that orders by UTF-16 code unit, which puts a
     * supplementary character before U+E000..U+FFFF because its surrogates start at 0xD800. A
     * verifier using the wrong order produces a different digest for any document with a key
     * outside the BMP, and only for those documents — which is the kind of disagreement that
     * shows up years later on one record.
     */
    static final Comparator<String> UTF8_ORDER = (left, right) -> {
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

    /** A value the encoding has no tag for. Refused, never stringified. */
    public static class NotEncodable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotEncodable(String message) {
            super(message);
        }
    }

    /** {@code enc(v)} — §3.1. */
    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    /** {@code hash(parts...)} — §3.3. The arguments are encoded as ONE list. */
    public static String hash(Object... parts) {
        return hex(sha256(encode(parts == null ? List.of() : List.of(nullSafe(parts)))));
    }

    /** {@code documentDigest} — §3.2. */
    public static String documentDigest(Object document) {
        return hex(sha256(encode(document)));
    }

    private static final Object NULL = new Object();

    private static Object[] nullSafe(Object[] parts) {
        Object[] copy = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            copy[i] = parts[i] == null ? NULL : parts[i];
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void write(ByteArrayOutputStream out, Object value) {
        if (value == null || value == NULL) {
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
            out.write(TAG_LIST);
            writeInt32(out, list.size());
            for (Object element : list) {
                write(out, element);
            }
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>(UTF8_ORDER);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new NotEncodable("a map key that is not a string has no encoding");
                }
                sorted.put(key, e.getValue());
            }
            out.write(TAG_MAP);
            writeInt32(out, sorted.size());
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                write(out, e.getKey());
                write(out, e.getValue());
            }
        } else {
            // Including Double and BigDecimal. §3.2 makes a non-integral number malformed,
            // because rounding it would digest a value the document does not contain.
            throw new NotEncodable("the encoding has no tag for " + value.getClass().getName()
                    + "; a document holding one has no canonical form");
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

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** Lowercase hex, 64 characters — §2. Case matters: a verifier compares the bytes. */
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
