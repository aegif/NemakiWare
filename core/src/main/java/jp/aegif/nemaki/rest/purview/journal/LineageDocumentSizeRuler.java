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

import java.util.List;
import java.util.Map;

/**
 * A conservative, mechanically provable upper bound on the serialized size of a stored
 * document (chunking, v2.3.22).
 *
 * <p>This is a RULER, not a serializer: it never produces bytes, only a length that no
 * compact JSON writer can exceed. The bound is proved per JSON type and is therefore
 * independent of any Jackson configuration — a library upgrade cannot move it:
 *
 * <ul>
 *   <li><b>string</b>: {@code 2 + 6 × length()}. Six is the widest escape any writer emits
 *       for one UTF-16 code unit ({@code \\uXXXX}); it is also ≥ that unit's UTF-8 length
 *       (an astral character is two units → 12 ≥ 4 bytes).</li>
 *   <li><b>integral number</b>: 20 — the widest {@code long} rendering including its sign.</li>
 *   <li><b>boolean</b>: 5 ({@code false}); <b>null</b>: 4.</li>
 *   <li><b>array</b>: {@code 2 + Σ(elements) + max(0, n-1)} (brackets + commas).</li>
 *   <li><b>object</b>: {@code 2 + Σ(keyBound + 1 + valueBound) + max(0, n-1)} (braces, colons,
 *       commas).</li>
 * </ul>
 *
 * <p>The v2 document's strict codec admits exactly these value types, so the enumeration is
 * exhaustive by construction; an unknown type is a loud refusal rather than a guess.
 *
 * <p><b>It is a guard rail, not a guarantee.</b> CouchDB measures {@code max_document_size}
 * on its own internal representation, so no JSON-side bound can prove acceptance — the
 * backend's verdict is final, and a {@code document_too_large} rejection is handled as the
 * deterministic "unstorable" outcome (v2.3.22 D1).
 */
public final class LineageDocumentSizeRuler {

    /** The widest escape a compact writer emits per UTF-16 code unit: {@code \\uXXXX}. */
    private static final long MAX_BYTES_PER_CODE_UNIT = 6L;

    /** The widest {@code long} rendering, sign included. */
    private static final long MAX_NUMBER_BYTES = 20L;

    /**
     * Chunk coordinates are measured with this fixed allowance instead of their digits, so a
     * partition's size decisions never depend on the chunk count it is still computing.
     */
    static final long CHUNK_COORDINATE_ALLOWANCE = MAX_NUMBER_BYTES;

    private LineageDocumentSizeRuler() {
    }

    /** The upper bound on the serialized length of one document map. */
    public static long upperBound(Map<String, Object> document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        return objectBound(document);
    }

    private static long objectBound(Map<?, ?> map) {
        long total = 2L; // { }
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("document keys must be strings, got "
                        + (entry.getKey() == null ? "null"
                                : entry.getKey().getClass().getSimpleName()));
            }
            if (!first) {
                total = Math.addExact(total, 1L); // ,
            }
            first = false;
            total = Math.addExact(total, stringBound(key));
            total = Math.addExact(total, 1L); // :
            total = Math.addExact(total, valueBound(entry.getValue()));
        }
        return total;
    }

    private static long arrayBound(List<?> list) {
        long total = 2L; // [ ]
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                total = Math.addExact(total, 1L); // ,
            }
            first = false;
            total = Math.addExact(total, valueBound(element));
        }
        return total;
    }

    private static long valueBound(Object value) {
        if (value == null) {
            return 4L; // null
        }
        if (value instanceof String s) {
            return stringBound(s);
        }
        if (value instanceof Boolean) {
            return 5L; // false
        }
        if (value instanceof Number) {
            return MAX_NUMBER_BYTES;
        }
        if (value instanceof Map<?, ?> map) {
            return objectBound(map);
        }
        if (value instanceof List<?> list) {
            return arrayBound(list);
        }
        throw new IllegalArgumentException("the size ruler does not admit "
                + value.getClass().getName() + " — the v2 codec never stores it, and guessing"
                + " a bound for an unknown type is how an under-measurement happens");
    }

    private static long stringBound(String s) {
        return Math.addExact(2L, Math.multiplyExact((long) s.length(), MAX_BYTES_PER_CODE_UNIT));
    }
}
