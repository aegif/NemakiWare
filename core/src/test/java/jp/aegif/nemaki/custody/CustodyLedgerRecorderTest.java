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
package jp.aegif.nemaki.custody;

import jp.aegif.nemaki.evidence.EvidenceLedgerEntry;
import jp.aegif.nemaki.evidence.EvidenceLedgerService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Custody does not pass on a handover this repository could not record.
 *
 * <h2>Why fail-closed here and fail-open two packages away</h2>
 *
 * <p>Custody passing is the step before a local copy may legitimately be destroyed. A handover
 * with no record of it, followed by a deletion, leaves nothing that says who became answerable
 * — and no local object left for anyone to notice. Refusing costs a delay.
 */
class CustodyLedgerRecorderTest {

    private static final String SIP_DIGEST = "a".repeat(64);

    private static CustodyTransfer verifiedTransfer(String signature, boolean signatureVerified) {
        CustodyTransfer transfer = new CustodyTransfer("t-1", "bedroom", "doc-1", SIP_DIGEST,
                "RODA", "2026-08-26T00:00:00Z");
        for (CustodyState next : List.of(CustodyState.SENT, CustodyState.RECEIVED,
                CustodyState.VALIDATED, CustodyState.INGEST_ACCEPTED,
                CustodyState.AIP_CREATED)) {
            transfer.advance(next, "t", "step");
        }
        transfer.verifyReceipt(new CustodyReceipt("sub-1", "aip-1", "b".repeat(64), SIP_DIGEST,
                "PASSED", "roda-agent", "2026-08-26T01:00:00Z", signature, signatureVerified),
                "2026-08-26T02:00:00Z");
        return transfer;
    }

    private static CustodyLedgerRecorder recorderOver(EvidenceLedgerService service) {
        CustodyLedgerRecorder recorder = new CustodyLedgerRecorder();
        recorder.setLedgerService(service);
        return recorder;
    }

    @Test
    @DisplayName("recording the same handover twice appends ONCE")
    void aRetryDoesNotChainTheHandoverTwice() {
        // passCustody records, then moves, then writes. When the write fails it says so
        // honestly — the chain has an entry the transfer does not — and the operator retries.
        // Without this the retry appends a SECOND custody receipt for one handover, and the
        // chain then says the record was handed over twice.
        CustodyTransfer transfer = verifiedTransfer(null, false);
        String digest = CustodyLedgerRecorder.receiptDigest(transfer);
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.APPENDED, 1, "hash", null));
        jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                        EvidenceLedgerEntry.of("bedroom", 1,
                                EvidenceLedgerEntry.SubjectKind.CUSTODY_RECEIPT, "doc-1",
                                digest, "2026-08-26T02:00:00Z", null)));
        CustodyLedgerRecorder recorder = recorderOver(service);
        recorder.setStore(store);

        CustodyLedgerRecorder.Authorisation second =
                recorder.recordVerifiedReceipt(transfer, "2026-08-26T03:00:00Z");

        assertTrue(second.mayProceed(),
                "an already-recorded handover was refused, so the operator cannot finish it: "
                        + second.refusedReason());
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .append(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a DIFFERENT handover for the same record is still appended")
    void adifferentHandoverIsNotSuppressed() {
        // The control: matching on the subject alone would make the second, genuine transfer of
        // the same record silently unrecorded.
        CustodyTransfer transfer = verifiedTransfer(null, false);
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.APPENDED, 2, "hash", null));
        jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                        EvidenceLedgerEntry.of("bedroom", 1,
                                EvidenceLedgerEntry.SubjectKind.CUSTODY_RECEIPT, "doc-1",
                                "mh1:some-other-handover", "2026-08-26T02:00:00Z", null)));
        CustodyLedgerRecorder recorder = recorderOver(service);
        recorder.setStore(store);

        assertTrue(recorder.recordVerifiedReceipt(transfer, "2026-08-26T03:00:00Z")
                .mayProceed());
        org.mockito.Mockito.verify(service).append(anyString(), any(), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("a recorded handover authorises custody to pass, under its own kind")
    void aRecordedHandoverIsAuthorised() {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.APPENDED, 1, "hash", null));

        CustodyLedgerRecorder.Authorisation authorisation = recorderOver(service)
                .recordVerifiedReceipt(verifiedTransfer(null, false), "2026-08-26T02:00:00Z");

        assertTrue(authorisation.mayProceed(), authorisation.refusedReason());
        org.mockito.ArgumentCaptor<EvidenceLedgerEntry.SubjectKind> kind =
                org.mockito.ArgumentCaptor.forClass(EvidenceLedgerEntry.SubjectKind.class);
        org.mockito.Mockito.verify(service).append(org.mockito.ArgumentMatchers.eq("bedroom"),
                kind.capture(), org.mockito.ArgumentMatchers.eq("doc-1"), anyString(),
                anyString());
        assertEquals(EvidenceLedgerEntry.SubjectKind.CUSTODY_RECEIPT, kind.getValue(),
                "another organisation's statement was chained under a kind it shares with this "
                        + "repository's own observations, so a reader cannot tell them apart");
    }

    @Test
    @DisplayName("an unrecordable handover REFUSES custody")
    void anUnrecordableHandoverIsRefused() {
        CustodyLedgerRecorder.Authorisation authorisation = recorderOver(
                answering(EvidenceLedgerService.AppendOutcome.UNAVAILABLE, "the ledger is down"))
                .recordVerifiedReceipt(verifiedTransfer(null, false), "2026-08-26T02:00:00Z");

        assertFalse(authorisation.mayProceed(),
                "custody passed with no record of the handover; the next legitimate step is to "
                        + "destroy the local copy, and nothing would say who became answerable");
        assertTrue(authorisation.refusedReason().contains("still here"),
                "the refusal does not say the record survived: "
                        + authorisation.refusedReason());
    }

    @Test
    @DisplayName("a ledger that throws refuses too, and does not escape")
    void aThrowingLedgerRefuses() {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("couchdb is down"));

        CustodyLedgerRecorder.Authorisation authorisation = recorderOver(service)
                .recordVerifiedReceipt(verifiedTransfer(null, false), "2026-08-26T02:00:00Z");

        assertFalse(authorisation.mayProceed());
        assertTrue(authorisation.refusedReason().contains("couchdb is down"),
                authorisation.refusedReason());
    }

    @Test
    @DisplayName("no ledger wired refuses — it does not fall through to silence")
    void anUnwiredLedgerRefuses() {
        assertFalse(recorderOver(null)
                .recordVerifiedReceipt(verifiedTransfer(null, false), "t").mayProceed(),
                "an unwired ledger let custody pass with no record at all");
    }

    @Test
    @DisplayName("a transfer whose receipt is not yet verified is refused")
    void anUnverifiedTransferIsRefused() {
        CustodyTransfer transfer = new CustodyTransfer("t-1", "bedroom", "doc-1", SIP_DIGEST,
                "RODA", "t");
        transfer.advance(CustodyState.SENT, "t", "sent");

        assertFalse(recorderOver(mock(EvidenceLedgerService.class))
                .recordVerifiedReceipt(transfer, "t").mayProceed(),
                "a handover was recorded before anything about it had been checked");
    }

    // ---- the digest ----

    @Test
    @DisplayName("the digest commits to BOTH ends of the reference")
    void theDigestCommitsToBothEnds() {
        // Their side alone would be a commitment to a value we have never seen; ours alone
        // would not record the handover at all.
        String base = CustodyLedgerRecorder.receiptDigest(verifiedTransfer(null, false));

        CustodyTransfer otherAip = new CustodyTransfer("t-1", "bedroom", "doc-1", SIP_DIGEST,
                "RODA", "t");
        for (CustodyState next : List.of(CustodyState.SENT, CustodyState.RECEIVED,
                CustodyState.VALIDATED, CustodyState.INGEST_ACCEPTED,
                CustodyState.AIP_CREATED)) {
            otherAip.advance(next, "t", "step");
        }
        otherAip.verifyReceipt(new CustodyReceipt("sub-1", "aip-2", "b".repeat(64), SIP_DIGEST,
                "PASSED", "roda-agent", "2026-08-26T01:00:00Z", null, false), "t");

        assertNotEquals(base, CustodyLedgerRecorder.receiptDigest(otherAip),
                "which AIP the record became does not affect the digest");
    }

    @Test
    @DisplayName("a receipt taken on trust does not digest the same as a verified one")
    void trustAndVerificationAreDifferentFacts() {
        // The distinction disappears exactly when it matters: somebody asking, later, whether
        // anybody actually checked that the far end sent this.
        assertNotEquals(
                CustodyLedgerRecorder.receiptDigest(verifiedTransfer("MEUCIQ...", false)),
                CustodyLedgerRecorder.receiptDigest(verifiedTransfer("MEUCIQ...", true)),
                "a signature nobody checked digests the same as one that was verified");
        assertNotEquals(
                CustodyLedgerRecorder.receiptDigest(verifiedTransfer(null, false)),
                CustodyLedgerRecorder.receiptDigest(verifiedTransfer("MEUCIQ...", false)),
                "an unsigned receipt digests the same as a signed one");
    }

    @Test
    @DisplayName("the digest is domain-separated from every other in the product")
    void theDigestIsDomainSeparated() {
        String expected = jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash.hash(
                "LEDGER_CUSTODY_RECEIPT_V1", "bedroom", "doc-1", SIP_DIGEST,
                "sub-1", "aip-1", "b".repeat(64), "PASSED", "roda-agent", "false", "false");

        assertEquals(expected,
                CustodyLedgerRecorder.receiptDigest(verifiedTransfer(null, false)),
                "the custody digest is no longer H(LEDGER_CUSTODY_RECEIPT_V1, repositoryId, "
                        + "objectId, sipDigest, submissionId, aipId, aipChecksum, outcome, "
                        + "agent, hasSignature, signatureVerified)");
    }

    private static EvidenceLedgerService answering(
            EvidenceLedgerService.AppendOutcome outcome, String reason) {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(outcome, 1, "hash", reason));
        return service;
    }
}
