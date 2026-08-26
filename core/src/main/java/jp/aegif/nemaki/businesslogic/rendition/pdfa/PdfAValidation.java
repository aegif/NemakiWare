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
package jp.aegif.nemaki.businesslogic.rendition.pdfa;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What veraPDF found in a produced PDF (P3-2).
 *
 * <h2>Three answers, and the third is the one that matters</h2>
 *
 * <p>{@code CONFORMS} / {@code DOES_NOT_CONFORM} / {@code NOT_CHECKED}. The third exists
 * because "we could not check" is not "it is not PDF/A", and the disclosure a reader sees
 * depends on which. Collapsing them would let a deployment where the validator never runs
 * describe every copy as failing a standard nobody tested it against — or, worse the other way,
 * as passing one.
 *
 * <p>Requesting PDF/A from a converter is not the same as getting it. That is the whole reason
 * this exists: LibreOffice accepts a {@code SelectPdfVersion} store property and produces what
 * it produces, and the only way to state a profile honestly is to check the bytes.
 */
public record PdfAValidation(Outcome outcome, String flavour, int failedChecks, String detail) {

    public enum Outcome {
        /** The validator ran and the file meets the profile it was checked against. */
        CONFORMS,
        /** The validator ran and it does not. */
        DOES_NOT_CONFORM,
        /**
         * The validator did not run, or could not read the file.
         *
         * <p>NOT a finding about the file. A deployment with validation switched off, a file
         * the parser rejects, and a missing dependency all land here, and none of them is
         * evidence that the copy fails a standard.
         */
        NOT_CHECKED
    }

    public static PdfAValidation notChecked(String why) {
        return new PdfAValidation(Outcome.NOT_CHECKED, null, 0, why);
    }

    /** Whether this validation supports calling the copy an archival rendition. */
    public boolean conforms() {
        return outcome == Outcome.CONFORMS;
    }

    /**
     * The sentence that goes into a duplication's disclosure.
     *
     * <p>Written here so the three outcomes cannot drift apart in the places that report them.
     * The {@code NOT_CHECKED} wording is the careful one: it has to avoid both "this is PDF/A"
     * and "this failed PDF/A", because neither was established.
     */
    public String disclosureSentence() {
        return switch (outcome) {
            case CONFORMS -> "This copy was checked with veraPDF and conforms to " + flavour
                    + ". That is a statement about the FILE FORMAT — that it carries what the "
                    + "profile requires for later rendering — and not about whether the "
                    + "conversion preserved the original's content.";
            case DOES_NOT_CONFORM -> "This copy was checked with veraPDF against " + flavour
                    + " and does NOT conform (" + failedChecks + " failed check(s)), so it must "
                    + "not be treated as an archival rendition even though the profile was "
                    + "requested.";
            case NOT_CHECKED -> "No archival profile was verified for this copy (" + detail
                    + "). That is NOT a finding that it fails one, and NOT a finding that it "
                    + "meets one — it means nothing was checked.";
        };
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", outcome.name());
        m.put("flavour", flavour);
        m.put("failedChecks", failedChecks);
        m.put("detail", detail);
        m.put("disclosure", disclosureSentence());
        return m;
    }
}
