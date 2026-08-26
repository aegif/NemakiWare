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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "We could not check" must never read as either verdict (P3-2).
 *
 * <h2>What is measured here and what is not</h2>
 *
 * <p>These tests drive the validator over real bytes and pin the three-way outcome and the
 * sentences that go into a disclosure. They do <b>not</b> establish that this product produces
 * conforming PDF/A: that needs LibreOffice with the profile requested, which is not available
 * in this build. The gap is recorded in the design document rather than papered over with a
 * fixture that would prove nothing about the conversion.
 */
class VeraPdfAValidatorTest {

    /**
     * A real, parseable PDF that is certainly not PDF/A.
     *
     * <p>Written with PDFBox rather than hand-rolled. The hand-rolled one had no cross
     * reference table, so veraPDF could not parse it and answered NOT_CHECKED — which is the
     * right answer to an unreadable file and the wrong fixture for "does a plain PDF conform".
     * A test that cannot tell a parse failure from a non-conformance measures neither.
     */
    private static byte[] plainPdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                        new org.apache.pdfbox.pdmodel.PDDocument();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("a plain PDF does not conform, and the validator ran to say so")
    void aPlainPdfIsNotPdfA() throws Exception {
        PdfAValidation validation = VeraPdfAValidator.validate(plainPdf(), "1b");

        // What must not happen is NOT_CHECKED: that would mean this whole path is inert and
        // every other assertion here is about code nobody reaches.
        assertEquals(PdfAValidation.Outcome.DOES_NOT_CONFORM, validation.outcome(),
                "the validator did not reach a verdict: " + validation.detail());
        assertFalse(validation.conforms());
        assertTrue(validation.disclosureSentence().contains("must not be treated as an archival"),
                validation.disclosureSentence());
    }

    @Test
    @DisplayName("bytes that are not a PDF are NOT_CHECKED, not a failure")
    void garbageIsNotAFailedFile() {
        PdfAValidation validation = VeraPdfAValidator.validate(
                "this is not a PDF".getBytes(StandardCharsets.UTF_8), "1b");

        assertEquals(PdfAValidation.Outcome.NOT_CHECKED, validation.outcome(),
                "an unreadable file was reported as failing a standard nobody tested it against");
        assertTrue(validation.disclosureSentence().contains("NOT a finding that it fails one"),
                validation.disclosureSentence());
        assertTrue(validation.disclosureSentence().contains("NOT a finding that it meets one"),
                validation.disclosureSentence());
    }

    @Test
    @DisplayName("a flavour this build does not know is NOT_CHECKED, not 'whatever it claims'")
    void anUnknownFlavourIsNotGuessed() throws Exception {
        // Falling back to the file's own declaration would let a file that declares nothing
        // pass by declaring nothing.
        PdfAValidation validation = VeraPdfAValidator.validate(plainPdf(), "9z");

        assertEquals(PdfAValidation.Outcome.NOT_CHECKED, validation.outcome());
        assertTrue(validation.detail().contains("not a PDF/A flavour"), validation.detail());
    }

    @Test
    @DisplayName("no bytes is NOT_CHECKED")
    void nothingToCheck() {
        assertEquals(PdfAValidation.Outcome.NOT_CHECKED,
                VeraPdfAValidator.validate(new byte[0], "1b").outcome());
        assertEquals(PdfAValidation.Outcome.NOT_CHECKED,
                VeraPdfAValidator.validate(null, "1b").outcome());
    }

    @Test
    @DisplayName("conformance is about the format, not about what the conversion kept")
    void conformanceDoesNotVouchForContent() {
        // The misreading this feature invites: "it is PDF/A" taken as "the conversion was
        // faithful". PDF/A says the file carries what it needs to be rendered later. It says
        // nothing about what the converter dropped on the way in.
        PdfAValidation conforms = new PdfAValidation(PdfAValidation.Outcome.CONFORMS,
                "1b", 0, "veraPDF reported compliance");

        assertTrue(conforms.disclosureSentence().contains("statement about the FILE FORMAT"),
                conforms.disclosureSentence());
        assertTrue(conforms.disclosureSentence().contains(
                        "not about whether the conversion preserved"),
                conforms.disclosureSentence());
    }
}
