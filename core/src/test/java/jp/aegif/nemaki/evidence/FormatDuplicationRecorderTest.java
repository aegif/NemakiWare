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
package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.evidence.FormatDuplicationRecorder.Converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A derived copy is recorded as a derived copy, or it reads as an equivalent of the original.
 *
 * <h2>The failure this is built against</h2>
 *
 * <p>Not "the duplication was not recorded" — that leaves a gap somebody can see. The quiet one
 * is a duplication recorded WITHOUT its disclosure: a PDF beside a Word file, both with digests,
 * both in the chain, and nothing anywhere saying the PDF is a viewing copy produced by a
 * converter with no PDF/A profile. A reader takes two recorded artefacts as two records.
 */
class FormatDuplicationRecorderTest {

    private static final String REPO = "bedroom";

    private static FormatDuplicationRecorder recorderOver(EvidenceLedgerService service) {
        FormatDuplicationRecorder recorder = new FormatDuplicationRecorder();
        recorder.setLedgerService(service);
        return recorder;
    }

    private static EvidenceLedgerService appending() {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.APPENDED, 1, "hash", null));
        return service;
    }

    // ---- the disclosure ----

    @Test
    @DisplayName("EVERY converter says its copy is not a preservation format")
    void everyConverterDisclosesWhatItIsNot() {
        // The compiler asks for a disclosure when a converter is added; this asks that the
        // answer is not a placeholder. A blank or vague one would leave the enum looking
        // complete while the protection is gone.
        // Every converter against every target, because the disclosure is composed from both
        // and a gap in either half is a gap a reader meets.
        List<String> missing = new ArrayList<>();
        for (Converter converter : Converter.values()) {
            for (FormatDuplicationRecorder.TargetFormat target :
                    FormatDuplicationRecorder.TargetFormat.values()) {
                String disclosure = converter.disclosureFor(target);
                String where = converter.name() + "/" + target.name();
                if (disclosure == null || disclosure.isBlank()
                        || !disclosure.contains("CONVENIENCE COPY")
                        || !disclosure.contains("ORIGINAL is unchanged")) {
                    missing.add(where + " -> " + disclosure);
                    continue;
                }
                // A KNOWN converter says what it drops. UNKNOWN must not pretend to: it does
                // not know, and asserting the same sentence there would be a claim about a
                // tool this build cannot name. It has to say the stronger and less comfortable
                // thing — that fidelity cannot be stated at all.
                boolean known = converter != Converter.UNKNOWN;
                if (!known && !disclosure.contains("NOT possible to state")) {
                    missing.add(where + " (claims to know what it lost) -> " + disclosure);
                }
                // And the target's own caveat has to be there. PDF must name the missing
                // PDF/A profile; SVG must NOT, because PDF/A is not a thing an SVG could have
                // had and naming it would read as the only thing wrong with the copy.
                if (target == FormatDuplicationRecorder.TargetFormat.PDF
                        && !disclosure.contains("no PDF/A profile was requested")) {
                    missing.add(where + " (no PDF/A statement) -> " + disclosure);
                }
                if (target == FormatDuplicationRecorder.TargetFormat.SVG
                        && (disclosure.contains("PDF/A")
                                || !disclosure.contains("no archival profile at all"))) {
                    missing.add(where + " (describes an SVG as a PDF) -> " + disclosure);
                }
                if (target == FormatDuplicationRecorder.TargetFormat.UNKNOWN
                        && !disclosure.contains("format of this copy was not recorded")) {
                    missing.add(where + " (assumes a format) -> " + disclosure);
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "a converter records duplications without saying the copy is not a preservation "
                        + "format, so a reader takes the derived file for a second record: "
                        + missing);
    }

    @Test
    @DisplayName("the disclosure comes FIRST in the description, before the identifiers")
    void theDisclosureIsFirst() {
        // A reader skimming a block about a derived copy has to meet "this is not a
        // preservation format" before the digests, not after them.
        Map<String, Object> body = FormatDuplicationRecorder.describe("obj-1", "src", "out",
                Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin", "2026-08-26T00:00:00Z");

        assertEquals("disclosure", body.keySet().iterator().next(),
                "the identifiers are shown before the caveat: " + body.keySet());
        assertTrue(String.valueOf(body.get("disclosure")).contains("CONVENIENCE COPY"));
    }

    @Test
    @DisplayName("each converter discloses ITS own losses, not a shared sentence")
    void eachConverterDisclosesItsOwnLosses() {
        // One sentence for every converter is how a specific caveat becomes a generic one. A
        // CAD drawing flattened to a page and a Word file reflowed are different losses.
        assertTrue(Converter.CAD_RENDITION.disclosure().contains("layers"),
                Converter.CAD_RENDITION.disclosure());
        assertTrue(Converter.JODCONVERTER_LIBREOFFICE.disclosure().contains("Fonts"),
                Converter.JODCONVERTER_LIBREOFFICE.disclosure());
        assertNotEquals(Converter.CAD_RENDITION.disclosure(),
                Converter.DIAGRAM_RENDITION.disclosure(),
                "two converters share one disclosure, so neither says what it actually loses");
    }

    // ---- the entry ----

    @Test
    @DisplayName("a duplication is appended under its own kind, naming the ORIGINAL")
    void aDuplicationIsChained() {
        EvidenceLedgerService service = appending();

        FormatDuplicationRecorder.Recorded recorded = recorderOver(service).recordDuplication(
                REPO, "obj-1", "srcdigest", "outdigest", Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF,
                "admin", "2026-08-26T00:00:00Z");

        assertTrue(recorded.inChain(), recorded.warning());
        org.mockito.ArgumentCaptor<EvidenceLedgerEntry.SubjectKind> kind =
                org.mockito.ArgumentCaptor.forClass(EvidenceLedgerEntry.SubjectKind.class);
        org.mockito.ArgumentCaptor<String> subject =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(service).append(org.mockito.ArgumentMatchers.eq(REPO),
                kind.capture(), subject.capture(), anyString(),
                org.mockito.ArgumentMatchers.eq("2026-08-26T00:00:00Z"));

        assertEquals(EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION, kind.getValue(),
                "a derived copy was chained under a kind it shares with everything else, so a "
                        + "reader cannot tell it from a record");
        assertEquals("obj-1", subject.getValue(),
                "the entry does not name the original, so a reader holding the record cannot "
                        + "find what was derived from it");
    }

    @Test
    @DisplayName("a chain that refuses does NOT fail the rendition")
    void aRefusedAppendDoesNotFailTheCopy() {
        // Fail-open, and for a reason specific to what a rendition is: it can be produced
        // again from a source that was never touched. Refusing would take preview down to
        // protect a record of a regenerable file.
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.UNAVAILABLE, -1, null, "down"));

        FormatDuplicationRecorder.Recorded recorded = recorderOver(service).recordDuplication(
                REPO, "obj-1", "src", "out", Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin",
                "2026-08-26T00:00:00Z");

        assertFalse(recorded.inChain());
        assertTrue(recorded.warning().contains("original is unchanged"),
                "the warning does not say the original survived, so an operator cannot tell a "
                        + "missing record from a lost document: " + recorded.warning());
        assertTrue(recorded.warning().contains("will not be back-filled"), recorded.warning());
    }

    @Test
    @DisplayName("a ledger that throws does not escape into the rendition path")
    void aThrowingLedgerDoesNotPropagate() {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("couchdb is down"));

        FormatDuplicationRecorder.Recorded recorded = recorderOver(service).recordDuplication(
                REPO, "obj-1", "src", "out", Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin",
                "2026-08-26T00:00:00Z");

        assertFalse(recorded.inChain());
        assertTrue(recorded.warning().contains("couchdb is down"), recorded.warning());
    }

    // ---- the digest ----

    @Test
    @DisplayName("the digest commits to what came out, not just what went in")
    void theDigestCommitsToBothSides() {
        String base = FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "src", "out",
                Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin");

        assertNotEquals(base, FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "src",
                        "other", Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin"),
                "what was produced does not affect the digest, so the entry cannot show WHICH "
                        + "copy was made");
        assertNotEquals(base, FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "other",
                        "out", Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin"),
                "what it was made FROM does not affect the digest");
        assertNotEquals(base, FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "src",
                        "out", Converter.CAD_RENDITION, FormatDuplicationRecorder.TargetFormat.PDF, "admin"),
                "the converter does not affect the digest, so two very different losses record "
                        + "identically");
        assertNotEquals(base, FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "src",
                        "out", Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "someone-else"),
                "who did it does not affect the digest");
    }

    @Test
    @DisplayName("a missing source digest is carried through, not filled in")
    void aMissingSourceDigestStaysMissing() {
        // A duplication of a document with no recorded digest is a WEAKER fact. Substituting
        // the produced digest, or an empty string, would write it as a stronger one.
        assertNotEquals(
                FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", null, "out",
                        Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin"),
                FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "", "out",
                        Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin"),
                "'no digest was recorded' and 'the digest is the empty string' produce the same "
                        + "entry");
    }

    @Test
    @DisplayName("the digest is domain-separated from every other in the product")
    void theDigestIsDomainSeparated() {
        // The literal is written here; production reads the constant.
        String expected = jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash.hash(
                "LEDGER_FORMAT_DUPLICATION_V1", REPO, "obj-1", "src", "out",
                "jodconverter/LibreOffice", "application/pdf", "admin");

        assertEquals(expected, FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "src",
                        "out", Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin"),
                "the duplication digest is no longer H(LEDGER_FORMAT_DUPLICATION_V1, "
                        + "repositoryId, sourceObjectId, sourceDigest, producedDigest, "
                        + "converterId, targetMediaType, actor)");
    }

    @Test
    @DisplayName("the digest carries the converter's ID, not its disclosure text")
    void theDigestUsesTheStableConverterId() {
        // Otherwise rewording a sentence changes every entry, which looks like the facts
        // changed. The id is what stays the same when the wording improves.
        //
        // The first version of this compared the two STRING LENGTHS, which is true of any
        // build and measured nothing — swapping id() for disclosure() in the digest left it
        // green. This computes the value the id produces and the value the disclosure would,
        // and asserts which one production actually emits.
        String withId = jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash.hash(
                "LEDGER_FORMAT_DUPLICATION_V1", REPO, "obj-1", "src", "out",
                Converter.JODCONVERTER_LIBREOFFICE.id(), "application/pdf", "admin");
        String withDisclosure = jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash.hash(
                "LEDGER_FORMAT_DUPLICATION_V1", REPO, "obj-1", "src", "out",
                Converter.JODCONVERTER_LIBREOFFICE.disclosure(), "application/pdf", "admin");
        assertNotEquals(withId, withDisclosure, "the fixture cannot tell the two apart");

        assertEquals(withId, FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "src",
                        "out", Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin"),
                "the digest is taken over the disclosure TEXT, so rewording a caveat changes "
                        + "every entry and looks like the facts changed");
    }

    @Test
    @DisplayName("the digest commits to the TARGET FORMAT, not just the converter")
    void theDigestCommitsToWhatWasProduced() {
        // The same converter on the same source produces a PDF and an SVG that lose different
        // things. An entry that does not commit to which one it was leaves the disclosure a
        // reader is shown detached from the copy it describes.
        String pdf = FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "src", "out",
                Converter.DIAGRAM_RENDITION, FormatDuplicationRecorder.TargetFormat.PDF, "admin");
        String svg = FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "src", "out",
                Converter.DIAGRAM_RENDITION, FormatDuplicationRecorder.TargetFormat.SVG, "admin");
        String unknown = FormatDuplicationRecorder.duplicationDigest(REPO, "obj-1", "src", "out",
                Converter.DIAGRAM_RENDITION, FormatDuplicationRecorder.TargetFormat.UNKNOWN,
                "admin");

        assertNotEquals(pdf, svg, "a PDF copy and an SVG copy hash to the same entry");
        assertNotEquals(pdf, unknown, "an unrecorded format hashes as though it were PDF");
        assertNotEquals(svg, unknown);
    }

    @Test
    @DisplayName("a checked copy's disclosure states what was FOUND, not what was asked for")
    void aValidatedCopyReportsTheFinding() {
        // Requesting PDF/A from LibreOffice and receiving it are different events. A product
        // that recorded the request would tell every reader its convenience copies are archival
        // renditions on the strength of a flag it set itself.
        var conforms = new jp.aegif.nemaki.businesslogic.rendition.pdfa.PdfAValidation(
                jp.aegif.nemaki.businesslogic.rendition.pdfa.PdfAValidation.Outcome.CONFORMS,
                "1b", 0, "veraPDF reported compliance");

        String text = Converter.JODCONVERTER_LIBREOFFICE.disclosureFor(
                FormatDuplicationRecorder.TargetFormat.PDF, conforms);

        assertTrue(text.contains("conforms to 1b"), text);
        // And the unchecked caveat must be GONE, not sitting beside it. A reader meeting both
        // takes the pessimistic one — making the check pointless — or the optimistic one,
        // making the caveat a lie. Only one can be true of a given copy.
        assertFalse(text.contains("no PDF/A profile was requested"), text);
        assertTrue(text.contains("CONVENIENCE COPY"), text);
        assertTrue(text.contains("ORIGINAL is unchanged"), text);
    }

    @Test
    @DisplayName("a copy that failed validation says so, and is not called archival")
    void aFailedValidationIsSaid() {
        var failed = new jp.aegif.nemaki.businesslogic.rendition.pdfa.PdfAValidation(
                jp.aegif.nemaki.businesslogic.rendition.pdfa.PdfAValidation.Outcome
                        .DOES_NOT_CONFORM, "1b", 3, "veraPDF reported 3 failed check(s)");

        String text = Converter.JODCONVERTER_LIBREOFFICE.disclosureFor(
                FormatDuplicationRecorder.TargetFormat.PDF, failed);

        assertTrue(text.contains("does NOT conform"), text);
        assertTrue(text.contains("even though the profile was requested"), text);
    }

    @Test
    @DisplayName("a validation result does not leak into an SVG's disclosure")
    void aPdfFindingIsNotAboutAnSvg() {
        // Only PDF has a PDF/A profile to report on. Letting the finding through for another
        // target would attach a PDF verdict to a file that is not one.
        var conforms = new jp.aegif.nemaki.businesslogic.rendition.pdfa.PdfAValidation(
                jp.aegif.nemaki.businesslogic.rendition.pdfa.PdfAValidation.Outcome.CONFORMS,
                "1b", 0, "veraPDF reported compliance");

        String text = Converter.DIAGRAM_RENDITION.disclosureFor(
                FormatDuplicationRecorder.TargetFormat.SVG, conforms);

        assertFalse(text.contains("conforms to 1b"), text);
        assertTrue(text.contains("no archival profile at all"), text);
    }

    @Test
    @DisplayName("an SVG disclosure does not describe a PDF")
    void anSvgIsNotDescribedAsAPdf() {
        // The whole reason the disclosure is composed. Every converter's text used to end in
        // "rendered to PDF" and name a PDF/A profile; the moment the SVG path recorded
        // anything, a reader would have been told what an SVG copy lost "rendered to PDF".
        String svg = Converter.DIAGRAM_RENDITION.disclosureFor(
                FormatDuplicationRecorder.TargetFormat.SVG);

        assertFalse(svg.contains("PDF"),
                "the SVG disclosure talks about PDF: " + svg);
        assertTrue(svg.contains("no archival profile at all"), svg);
        assertTrue(svg.contains("shapes, connectors"),
                "the converter's own losses were dropped: " + svg);
    }

    @Test
    @DisplayName("an unrecognised converter id becomes UNKNOWN, never the nearest match")
    void anUnknownIdIsNotGuessed() {
        assertEquals(Converter.UNKNOWN, Converter.forId("something/new"));
        assertEquals(Converter.UNKNOWN, Converter.forId(null));
        assertEquals(Converter.CAD_RENDITION, Converter.forId("nemaki/cad"),
                "a known id was not recognised, so every CAD copy would record as UNKNOWN");
    }

    @Test
    @DisplayName("no ledger wired is silent, not a warning per rendition")
    void anUnwiredLedgerIsSilent() {
        FormatDuplicationRecorder.Recorded recorded = recorderOver(null).recordDuplication(
                REPO, "obj-1", "src", "out", Converter.JODCONVERTER_LIBREOFFICE, FormatDuplicationRecorder.TargetFormat.PDF, "admin",
                "2026-08-26T00:00:00Z");

        assertFalse(recorded.inChain());
        assertNull(recorded.warning(),
                "an unconfigured ledger produced a warning on every preview: "
                        + recorded.warning());
    }
}
