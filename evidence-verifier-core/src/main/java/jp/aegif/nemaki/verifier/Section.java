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

    /**
     * A section document as read: absent, or there and well formed, or there and NOT a
     * well-formed JSON object.
     *
     * <p>The third is its own state. {@link #documentIn} folds it into null, and every check
     * that read the null said NOT_PRESENT — so a ledger entry with a duplicate key, which §3.2
     * makes a malformed document and FAILED, was reported as "the package carries no ledger
     * entry" and exit 3 (9-6 review, P1). A check that reads a malformed document says FAILED.
     */
    record Doc(Map<String, Object> value, boolean malformed) {
        static final Doc ABSENT = new Doc(null, false);
    }

    static Doc read(Map<String, byte[]> entries, String name) {
        byte[] json = fileIn(entries, name);
        if (json == null) {
            return Doc.ABSENT;
        }
        Map<String, Object> value = parse(json);
        return value == null ? new Doc(null, true) : new Doc(value, false);
    }

    /**
     * {@code check}, unless it passed and {@code stem}.c14n sits beside {@code stem}.json and is
     * not its canonical form — then §3.2's FAILED, under the check's own name.
     *
     * <p>§3.2 makes every shipped {@code .c14n} a thing to check, and only the statement's was
     * (9-6 review, P3). A {@code .c14n} that is NOT shipped is nothing to check here: §4.2
     * names the four files of {@code prior/} as a set, and that rule has its own arm.
     */
    static Outcome.Check alsoCanonicalForm(Outcome.Check check, Map<String, byte[]> entries,
            String stem) {
        if (check.outcome() != Outcome.PASSED) {
            return check;
        }
        byte[] shipped = fileIn(entries, stem + ".c14n");
        byte[] json = fileIn(entries, stem + ".json");
        if (shipped == null || json == null) {
            return check;
        }
        byte[] recomputed;
        try {
            recomputed = Canonical.encode(Json.parse(new String(json, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            return Outcome.Check.failed(check.name(), stem + ".json has no canonical form: "
                    + e.getMessage());
        }
        if (!java.util.Arrays.equals(recomputed, shipped)) {
            return Outcome.Check.failed(check.name(), "the shipped " + stem + ".c14n is not the "
                    + "canonical form of the .json beside it (§3.2)");
        }
        return check;
    }

    /** The §3.2 answer for a check that read a document that is there and is not well formed. */
    static Outcome.Check malformed(String check, String... names) {
        return Outcome.Check.failed(check, String.join(", ", names) + " is not a well-formed "
                + "JSON object; §3.2 makes a malformed document FAILED, not absent");
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
