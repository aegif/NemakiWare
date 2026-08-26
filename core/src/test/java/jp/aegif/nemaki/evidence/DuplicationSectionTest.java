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

import jp.aegif.nemaki.model.Document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The disclosure has to reach a reader, or it is not a disclosure.
 *
 * <h2>What was wrong</h2>
 *
 * <p>Every duplication carried a per-converter sentence saying the copy is a convenience copy
 * and not a preservation format — in a Java enum, reachable from one method with no production
 * caller. The ledger entry holds a digest, so nothing a reader ever opened said it. A PDF
 * sitting beside a Word file, both with digests, both in the chain, is taken for two records:
 * exactly the reading the sentence exists to prevent.
 */
class DuplicationSectionTest {

    private static final String REPO = "bedroom";

    private static EvidenceLedgerEntry entry(long sequence,
            EvidenceLedgerEntry.SubjectKind kind) {
        return EvidenceLedgerEntry.of(REPO, sequence, kind, "doc-1", "digest-" + sequence,
                "2026-08-26T00:00:00Z", null);
    }

    private static AuthenticityReport.Section duplications(List<EvidenceLedgerEntry> entries,
            boolean storeWired) {
        return duplications(entries, storeWired, null);
    }

    private static AuthenticityReport.Section duplications(List<EvidenceLedgerEntry> entries,
            boolean storeWired, List<jp.aegif.nemaki.model.Rendition> renditions) {
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        if (renditions != null) {
            jp.aegif.nemaki.businesslogic.ContentService contentService =
                    mock(jp.aegif.nemaki.businesslogic.ContentService.class);
            when(contentService.getRenditions(anyString(), anyString()))
                    .thenReturn(renditions);
            assembler.setContentService(contentService);
        }
        if (storeWired) {
            EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
            when(store.findBySubject(anyString(), anyString(), anyInt())).thenReturn(entries);
            assembler.setLedgerStore(store);
        }
        Document document = new Document();
        document.setId("doc-1");
        document.setName("minutes.docx");
        AuthenticityReport report = assembler.assemble(REPO, "doc-1", "2026-08-26T00:00:00Z",
                false);
        for (AuthenticityReport.Section section : report.sections()) {
            if ("duplications".equals(section.name())) {
                return section;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a recorded duplication reaches the report, disclosure FIRST")
    void aDuplicationReachesTheReport() {
        AuthenticityReport.Section section = duplications(
                List.of(entry(7, EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION)), true);

        assertNotNull(section, "the report has no section about derived copies at all");
        assertEquals(AuthenticityReport.Verdict.REPORTED, section.verdict());
        assertEquals("disclosure", section.content().keySet().iterator().next(),
                "the identifiers are shown before the caveat: " + section.content().keySet());
        String disclosure = String.valueOf(section.content().get("disclosure"));
        assertTrue(disclosure.contains("CONVENIENCE COPIES"), disclosure);
        assertTrue(disclosure.contains("ORIGINAL is unchanged"), disclosure);
    }

    @Test
    @DisplayName("the disclosure names NO output format — it cannot know which one applied")
    void theDisclosureDoesNotGuessTheFormat() {
        // It used to say "this product converts to PDF without requesting or validating a
        // PDF/A profile". True while PDF was the only recorded path; false as soon as the REST
        // stacks were wired, because two of them also produce SVG. A reader looking at a
        // drawing's SVG copy would have been told about PDF/A — which is not a thing an SVG
        // could have had. The per-converter fix in the recorder does not reach here: this
        // section is built from the ledger, and the entry carries a digest.
        AuthenticityReport.Section section = duplications(
                List.of(entry(7, EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION)), true);
        String disclosure = String.valueOf(section.content().get("disclosure"));

        // The rule is about ASSERTING a format property, not about the letters "PDF/A". The
        // disclosure may — and now does — name PDF/A in order to say it cannot tell you
        // anything about it; forbidding the string outright would push the text back towards
        // silence, and silence reads as "no format caveat applies".
        assertFalse(disclosure.contains("no PDF/A profile was requested"),
                "the report asserts that no profile was requested, which it cannot know: "
                        + disclosure);
        assertFalse(disclosure.contains("converts to PDF"),
                "the report asserts an output format the chain does not reveal: " + disclosure);
        // And it says WHY it cannot say, rather than staying silent and reading as "no format
        // caveat applies".
        assertTrue(disclosure.contains("COMMITS to which converter"), disclosure);
        assertTrue(disclosure.contains("does not carry"), disclosure);
    }

    private static jp.aegif.nemaki.model.Rendition rendition(String id, String mediaType,
            String outcome, String flavour) {
        jp.aegif.nemaki.model.Rendition r = new jp.aegif.nemaki.model.Rendition();
        r.setId(id);
        r.setMimetype(mediaType);
        r.setPdfaOutcome(outcome);
        r.setPdfaFlavour(flavour);
        return r;
    }

    @Test
    @DisplayName("the PDF/A verdict reaches the report, with where it came from FIRST")
    void theVerdictIsReadable() {
        // The chain commits to the verdict and carries only a digest, so for a while the
        // product checked PDF/A and no reader could see the answer. It is readable because it
        // is ALSO on the rendition row — and that row is mutable, which is exactly why the
        // provenance sentence has to arrive before the values.
        AuthenticityReport.Section section = duplications(
                List.of(entry(7, EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION)), true,
                List.of(rendition("rend-1", "application/pdf", "CONFORMS", "1b")));

        List<String> keys = List.copyOf(section.content().keySet());
        assertEquals("disclosure", keys.get(0), keys.toString());
        assertEquals("renditionsNowSource", keys.get(1),
                "the values arrive before the sentence saying where they came from: " + keys);
        String source = String.valueOf(section.content().get("renditionsNowSource"));
        assertTrue(source.contains("not from the evidence chain"), source);
        assertTrue(source.contains("COMMITS"), source);
        // And it must NOT offer a check the reader cannot perform. The digest's other inputs —
        // the converter, both digests, the actor — are in neither this report nor the entry,
        // so "checkable by anyone holding both" invites a reader to treat the row as chained
        // evidence on the strength of an arithmetic they cannot do.
        assertTrue(source.contains("NOTHING HERE CAN BE CHECKED AGAINST THE CHAIN FROM THIS "
                + "REPORT ALONE"), source);
        assertFalse(source.contains("checked against the chain by anyone holding both"), source);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> now =
                (List<Map<String, Object>>) section.content().get("renditionsNow");
        assertEquals("CONFORMS", now.get(0).get("archivalProfileOutcome"));
        assertEquals("1b", now.get(0).get("archivalProfileFlavour"));
        assertEquals(Boolean.TRUE, now.get(0).get("archivalProfileChecked"));
    }

    @Test
    @DisplayName("an unchecked copy says nothing was checked, not that it failed")
    void anUncheckedCopyIsNotAFailedOne() {
        AuthenticityReport.Section section = duplications(
                List.of(entry(7, EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION)), true,
                List.of(rendition("rend-1", "application/pdf", null, null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> now =
                (List<Map<String, Object>>) section.content().get("renditionsNow");
        assertEquals(Boolean.FALSE, now.get(0).get("archivalProfileChecked"),
                "a copy nobody checked was reported as having a verdict");
        assertNull(now.get(0).get("archivalProfileOutcome"));
    }

    @Test
    @DisplayName("the disclosure does not claim, or deny, an archival-profile check")
    void theDisclosureIsHonestAboutPdfA() {
        // The recorder now folds a veraPDF finding into the entry's digest, and the roadmap
        // briefly claimed that finding was "wired into the disclosure". It is not: the entry
        // carries a digest, so this section cannot reveal the outcome any more than it can
        // reveal the format. What it CAN do is say so — rather than leaving a reader with the
        // old flat sentence, which read as "no copy here was ever checked".
        AuthenticityReport.Section section = duplications(
                List.of(entry(7, EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION)), true);
        String disclosure = String.valueOf(section.content().get("disclosure"));

        assertTrue(disclosure.contains("archival profile such as PDF/A"), disclosure);
        assertTrue(disclosure.contains("does NOT say a copy failed"), disclosure);
        assertTrue(disclosure.contains("does NOT say one passed"), disclosure);
    }

    @Test
    @DisplayName("only FORMAT_DUPLICATION entries are listed — the control")
    void otherKindsAreNotDerivedCopies() {
        // Without this, listing every entry for the object would report fixity passes and
        // custody receipts as copies of the record.
        AuthenticityReport.Section section = duplications(List.of(
                entry(1, EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT),
                entry(2, EvidenceLedgerEntry.SubjectKind.CUSTODY_RECEIPT)), true);

        assertEquals(AuthenticityReport.Verdict.ABSENT, section.verdict(),
                "entries that are not duplications were reported as derived copies: "
                        + section.content());
    }

    @Test
    @DisplayName("no ledger is UNAVAILABLE, not ABSENT")
    void anUnreadableLedgerIsNotAnAbsenceOfCopies() {
        // "We could not look" and "there are none" are different answers, and only one of them
        // is about the record.
        AuthenticityReport.Section section = duplications(List.of(), false);

        assertEquals(AuthenticityReport.Verdict.UNAVAILABLE, section.verdict());
        assertTrue(section.limits().contains("NOT a statement that"), section.limits());
    }

    @Test
    @DisplayName("a truncated read is UNAVAILABLE, not 'no copies'")
    void aTruncatedReadIsNotAnAnswer() {
        // findBySubject answers in ascending sequence and lets the view apply the limit, so a
        // full result means the entries it dropped are the LATEST. Fixity results accumulate on
        // every scan, so a long-lived record hits the limit with nothing wrong — and a
        // duplication recorded last week is then simply not in what we read.
        List<EvidenceLedgerEntry> fixityOnly = new java.util.ArrayList<>();
        for (int i = 0; i < AuthenticityReportAssembler.LEDGER_ENTRY_LIMIT; i++) {
            fixityOnly.add(entry(i, EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT));
        }

        AuthenticityReport.Section section = duplications(fixityOnly, true);

        assertEquals(AuthenticityReport.Verdict.UNAVAILABLE, section.verdict(),
                "the read stopped before the newest entries and the report still answered "
                        + "'no copy of this record in another format is recorded'");
        assertTrue(section.limits().contains("most recent"), section.limits());
    }

    @Test
    @DisplayName("a truncated read that DID find copies says the list is short")
    void aTruncatedReadWithCopiesSaysSo() {
        List<EvidenceLedgerEntry> entries = new java.util.ArrayList<>();
        entries.add(entry(0, EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION));
        for (int i = 1; i < AuthenticityReportAssembler.LEDGER_ENTRY_LIMIT; i++) {
            entries.add(entry(i, EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT));
        }

        AuthenticityReport.Section section = duplications(entries, true);

        assertEquals(AuthenticityReport.Verdict.REPORTED, section.verdict());
        assertEquals(Boolean.TRUE, section.content().get("truncated"));
        assertTrue(section.limits().contains("missing from this list"),
                "a short list was presented as the copies there are: " + section.limits());
    }

    @Test
    @DisplayName("the section says it is not a complete inventory")
    void theSectionSaysWhatItMisses() {
        AuthenticityReport.Section section = duplications(
                List.of(entry(7, EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION)), true);

        assertFalse(section.limits().isBlank());
        assertTrue(section.limits().contains("NOT a complete inventory"),
                "a reader could take this list for every copy that exists: " + section.limits());
    }
}
