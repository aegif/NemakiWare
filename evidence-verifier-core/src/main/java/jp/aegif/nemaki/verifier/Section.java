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

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The one place a check finds an evidence document — §4.2.
 *
 * <h2>Why it is one place</h2>
 *
 * <p>Five copies of the same three lines used to do this, and a correction reached one of them.
 * {@code PackageIntegrity} stopped counting a PAYLOAD copy of an evidence folder as a second
 * section — which was right, because content is not metadata — but the LOOKUPS kept matching it.
 * A package could then carry a substituted {@code anchor-target-checkpoint.json} under
 * {@code representations/rep1/data/…/nemaki-evidence/}, place it earlier in the zip, and have
 * {@code one evidence section} answer PASSED while every check above P0 read the substitute.
 * Measured by the subagent's seventh review (P1) on a real package; before the counting change
 * the duplicate check had caught it.
 *
 * <p>So the exclusion lives with the lookup, not beside it.
 */
final class Section {

    /** Where §4.2 puts the evidence section. */
    static final String DIR = "/metadata/other/nemaki-evidence/";

    /** Where a payload lives inside a CSIP package. */
    private static final String PAYLOAD_PREFIX_MARKER = "/representations/";
    private static final String PAYLOAD_DATA_MARKER = "/data/";

    private Section() {
    }

    /** Content, not metadata: a file under a representation's own data directory. */
    static boolean isPayload(String path) {
        String slashed = "/" + path;
        return slashed.contains(PAYLOAD_PREFIX_MARKER) && slashed.contains(PAYLOAD_DATA_MARKER);
    }

    /**
     * The bytes of {@code name} inside the evidence section, or null.
     *
     * <p>The FIRST match in the map's order, which is the zip's — a package holding two is a
     * finding {@code one evidence section} reports, and choosing differently here would only
     * move which of them is read.
     */
    static byte[] fileIn(Map<String, byte[]> entries, String name) {
        String wanted = DIR + name;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!isPayload(entry.getKey()) && ("/" + entry.getKey()).endsWith(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * {@code name} parsed as a JSON object, or null when it is absent or is not one.
     *
     * <p>Every {@code RuntimeException}, not only {@code NotCanonicalisable}: {@code Json.parse}
     * raises {@code NumberFormatException} for a broken unicode escape, so a package could turn
     * a check into exit 5 — the verifier failing rather than answering. That was corrected in
     * one place and left in eight others, each measured to crash (subagent, seventh review, P2).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> documentIn(Map<String, byte[]> entries, String name) {
        byte[] json = fileIn(entries, name);
        return json == null ? null : parse(json);
    }

    /** {@code json} as a JSON object, or null when it is not one or does not parse. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(byte[] json) {
        try {
            Object value = Json.parse(new String(json, StandardCharsets.UTF_8));
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
