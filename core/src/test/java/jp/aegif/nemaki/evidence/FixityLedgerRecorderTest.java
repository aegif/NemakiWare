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

import jp.aegif.nemaki.fixity.FixityOutcome;
import jp.aegif.nemaki.fixity.FixityScanReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
 * A fixity pass goes into the chain as ONE fact, and the fact includes what it did not cover.
 *
 * <h2>The failure this is built against</h2>
 *
 * <p>Not "the pass was not recorded" — that one is visible. The quiet one is a {@code PARTIAL}
 * sweep whose entry is indistinguishable from a {@code COMPLETE} one, so a chain that says "we
 * checked" when what happened was "we checked two hundred of them".
 */
class FixityLedgerRecorderTest {

    private static final String REPO = "bedroom";

    private static FixityScanReport report(FixityScanReport.Verdict verdict, long scanned,
            long mismatch, List<FixityScanReport.Finding> findings) {
        return new FixityScanReport(verdict, REPO, scanned, scanned - mismatch, mismatch, 0, 0,
                findings, null);
    }

    private static FixityScanReport.Finding finding(String objectId) {
        return new FixityScanReport.Finding(objectId, FixityOutcome.MISMATCH, "a", "b", null);
    }

    private static FixityLedgerRecorder recorderOver(EvidenceLedgerService service) {
        FixityLedgerRecorder recorder = new FixityLedgerRecorder();
        recorder.setLedgerService(service);
        return recorder;
    }

    @Test
    @DisplayName("a completed pass is appended, naming the scope and committing to the counts")
    void aPassIsChained() {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.APPENDED, 1, "hash", null));
        FixityScanReport report = report(FixityScanReport.Verdict.COMPLETE, 10, 1,
                List.of(finding("obj-3")));

        FixityLedgerRecorder.Recorded recorded = recorderOver(service)
                .recordPass(REPO, "folder:f-1", report, "2026-08-26T00:00:00Z");

        assertTrue(recorded.inChain(), recorded.warning());
        assertNull(recorded.warning());

        org.mockito.ArgumentCaptor<String> subject =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> digest =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<EvidenceLedgerEntry.SubjectKind> kind =
                org.mockito.ArgumentCaptor.forClass(EvidenceLedgerEntry.SubjectKind.class);
        org.mockito.Mockito.verify(service).append(org.mockito.ArgumentMatchers.eq(REPO),
                kind.capture(), subject.capture(), digest.capture(),
                org.mockito.ArgumentMatchers.eq("2026-08-26T00:00:00Z"));

        assertEquals(EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, kind.getValue());
        assertTrue(subject.getValue().contains("folder:f-1"),
                "the entry does not name the scope, so 'the fixity result for bedroom' is the "
                        + "only thing a reader can ask for and a repository has many passes: "
                        + subject.getValue());
        assertEquals(FixityLedgerRecorder.passDigest(REPO, "folder:f-1", report),
                digest.getValue());
        assertFalse(digest.getValue().isBlank());
    }

    @Test
    @DisplayName("a PARTIAL sweep does not digest the same as a COMPLETE one")
    void aPartialSweepIsNotACompleteOne() {
        // The quiet failure. Identical counts, one of them a sample: a chain entry that let the
        // sample read as a full sweep would be the strongest possible version of the weakest
        // fact, which is the thing this whole layer exists to stop.
        assertNotEquals(
                FixityLedgerRecorder.passDigest(REPO, "repository",
                        report(FixityScanReport.Verdict.COMPLETE, 200, 0, List.of())),
                FixityLedgerRecorder.passDigest(REPO, "repository",
                        report(FixityScanReport.Verdict.PARTIAL, 200, 0, List.of())),
                "a pass that stopped at its limit commits to the same value as one that "
                        + "finished, so the chain cannot tell 'we checked' from 'we checked some'");
    }

    @Test
    @DisplayName("the same counts over different objects are different facts")
    void theFindingsAreInTheDigest() {
        assertNotEquals(
                FixityLedgerRecorder.passDigest(REPO, "repository",
                        report(FixityScanReport.Verdict.COMPLETE, 10, 1, List.of(finding("a")))),
                FixityLedgerRecorder.passDigest(REPO, "repository",
                        report(FixityScanReport.Verdict.COMPLETE, 10, 1, List.of(finding("b")))),
                "which objects failed does not affect the digest, so the entry says only how "
                        + "many, and a reader cannot check that they were told the same ones");
    }

    @Test
    @DisplayName("the scope is part of the fact")
    void theScopeIsInTheDigest() {
        FixityScanReport report = report(FixityScanReport.Verdict.COMPLETE, 10, 0, List.of());
        assertNotEquals(
                FixityLedgerRecorder.passDigest(REPO, "repository", report),
                FixityLedgerRecorder.passDigest(REPO, "folder:f-1", report),
                "the same counts over a different scope digest the same, so a folder scan and "
                        + "a whole-repository scan are indistinguishable in the chain");
    }

    @Test
    @DisplayName("a truncated finding list is not the same as a shorter one")
    void theFindingCountIsInTheDigest() {
        // findings are capped at MAX_FINDINGS. Without the count, a pass with 600 mismatches
        // and one with 500 commit to the same value once the list is truncated.
        List<FixityScanReport.Finding> five = List.of(finding("a"), finding("b"), finding("c"),
                finding("d"), finding("e"));
        assertNotEquals(
                FixityLedgerRecorder.passDigest(REPO, "repository",
                        new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO, 10, 5, 5,
                                0, 0, five, null)),
                FixityLedgerRecorder.passDigest(REPO, "repository",
                        new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO, 10, 5, 5,
                                0, 0, five.subList(0, 4), null)),
                "two different finding lists digest the same");
    }

    @Test
    @DisplayName("a pass that has not produced a result is not chained")
    void anUnfinishedPassIsNotChained() {
        // An entry here would put "a pass" in the chain for something that has not produced one.
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);

        FixityLedgerRecorder.Recorded recorded = recorderOver(service).recordPass(REPO,
                "repository", FixityScanReport.notRun(REPO), "2026-08-26T00:00:00Z");

        assertFalse(recorded.inChain());
        assertNull(recorded.warning(), "a pass that never ran produced a warning");
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .append(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a FAILED pass IS chained — the control")
    void aFailedPassIsStillChained() {
        // Without this, refusing to chain anything unusual would pass the test above while
        // leaving the chain silent about exactly the passes worth knowing about.
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.APPENDED, 1, "hash", null));

        FixityLedgerRecorder.Recorded recorded = recorderOver(service).recordPass(REPO,
                "repository", FixityScanReport.failed(REPO, "the folder could not be read"),
                "2026-08-26T00:00:00Z");

        assertTrue(recorded.inChain(),
                "a pass that failed was not recorded, so the chain shows a gap in time with no "
                        + "explanation in it");
    }

    @Test
    @DisplayName("a chain that refuses the entry does NOT throw away the pass")
    void aRefusedAppendDoesNotLoseThePass() {
        // The opposite of DispositionRecorder, and for a reason: the pass has already run and
        // its results are already in the caller's response.
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.UNAVAILABLE, -1, null,
                        "the evidence ledger is not available"));

        FixityLedgerRecorder.Recorded recorded = recorderOver(service).recordPass(REPO,
                "repository", report(FixityScanReport.Verdict.COMPLETE, 10, 0, List.of()),
                "2026-08-26T00:00:00Z");

        assertFalse(recorded.inChain());
        assertTrue(recorded.warning().contains("will not be back-filled"),
                "the warning does not say the gap is permanent: " + recorded.warning());
    }

    @Test
    @DisplayName("the digest is domain-separated from every other in the product")
    void theDigestIsDomainSeparated() {
        // The literal is written here; production reads the constant.
        FixityScanReport report = report(FixityScanReport.Verdict.COMPLETE, 3, 0, List.of());
        String expected = jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash.hash(
                "LEDGER_FIXITY_RESULT_V1", REPO, "repository", "COMPLETE",
                "3", "3", "0", "0", "0", "0", "");

        assertEquals(expected, FixityLedgerRecorder.passDigest(REPO, "repository", report),
                "the pass digest is no longer H(LEDGER_FIXITY_RESULT_V1, repositoryId, scope, "
                        + "verdict, counts, finding count, findings). Emptying or removing the "
                        + "domain lands here, and so does dropping a count");
    }
}
