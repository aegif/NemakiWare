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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs veraPDF over produced bytes (P3-2).
 *
 * <h2>Why it never throws</h2>
 *
 * <p>A rendition is a convenience copy. Failing the whole preview because a validator could not
 * parse its output would trade a working feature for a stricter claim about a file nobody is
 * relying on — and this is the fail-OPEN side of the boundary: the copy exists whatever the
 * validator says. Every failure becomes {@link PdfAValidation.Outcome#NOT_CHECKED} with the
 * reason attached, which the disclosure then states.
 *
 * <p>{@code LinkageError} is caught too, not only {@code Exception}: veraPDF is an optional
 * part of the stack, and a deployment that shed it should report "not checked" rather than
 * killing rendition with a {@code NoClassDefFoundError}.
 */
public final class VeraPdfAValidator {

    private static final Logger logger = LoggerFactory.getLogger(VeraPdfAValidator.class);

    /**
     * veraPDF's greenfield foundry registers globally and must be initialised once.
     *
     * <p>Once, and lazily. Doing it in a static initialiser would make loading this class fail
     * on a deployment without veraPDF, which is the situation it is supposed to survive.
     */
    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);

    private VeraPdfAValidator() {
    }

    /**
     * Checks {@code pdf} against {@code flavourId}.
     *
     * @param flavourId e.g. {@code 1b}, {@code 2b}, {@code 3b}; the profile the conversion was
     *        asked for. Passing the requested one matters: validating against "whatever the
     *        file claims" would let a file that declares nothing pass by declaring nothing.
     */
    public static PdfAValidation validate(byte[] pdf, String flavourId) {
        if (pdf == null || pdf.length == 0) {
            return PdfAValidation.notChecked("there were no bytes to check");
        }
        try {
            initialise();
            org.verapdf.pdfa.flavours.PDFAFlavour flavour =
                    org.verapdf.pdfa.flavours.PDFAFlavour.byFlavourId(flavourId);
            if (flavour == org.verapdf.pdfa.flavours.PDFAFlavour.NO_FLAVOUR) {
                // Not a fallback to "whatever it claims". A profile this build was not told to
                // check against is one whose result would not answer the question that was
                // asked.
                return PdfAValidation.notChecked("'" + flavourId + "' is not a PDF/A flavour "
                        + "this validator recognises, so nothing was checked against it");
            }
            try (org.verapdf.pdfa.PDFAParser parser = org.verapdf.pdfa.Foundries.defaultInstance()
                    .createParser(new ByteArrayInputStream(pdf), flavour)) {
                org.verapdf.pdfa.PDFAValidator validator =
                        org.verapdf.pdfa.Foundries.defaultInstance().createValidator(flavour,
                                false);
                org.verapdf.pdfa.results.ValidationResult result = validator.validate(parser);
                int failed = result.getTestAssertions() == null ? 0
                        : (int) result.getTestAssertions().stream()
                                .filter(a -> a.getStatus()
                                        == org.verapdf.pdfa.results.TestAssertion.Status.FAILED)
                                .count();
                return new PdfAValidation(
                        result.isCompliant() ? PdfAValidation.Outcome.CONFORMS
                                : PdfAValidation.Outcome.DOES_NOT_CONFORM,
                        flavour.getId(), failed,
                        result.isCompliant() ? "veraPDF reported compliance"
                                : "veraPDF reported " + failed + " failed check(s)");
            }
        } catch (Exception | LinkageError e) {
            String why = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            logger.debug("PDF/A validation did not run: {}", why);
            return PdfAValidation.notChecked("the validator could not run (" + why + ")");
        }
    }

    private static void initialise() {
        if (INITIALISED.compareAndSet(false, true)) {
            org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider.initialise();
        }
    }
}
