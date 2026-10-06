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
package jp.aegif.nemaki.rest.purview.anchor;

import jp.aegif.nemaki.evidence.ContentWriteJournal;
import jp.aegif.nemaki.evidence.EvidenceBundle;
import jp.aegif.nemaki.evidence.EvidenceBundleAssembler;
import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.EvidenceLedgerEntry;
import jp.aegif.nemaki.evidence.EvidenceLedgerService;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.evidence.RecordContentStatementV1;
import jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the manifest says about each rung is read off the receipts, not guessed (9-6 review,
 * P3). In this package because {@link AnchorReceipt#confirmed} is package-private — a CONFIRMED
 * receipt with material on it is the one shape the assembler's material arm can be driven with.
 */
class TheAssemblerReadsReceiptsAsTheyAreTest {

    private static final String DOMAIN = "record-content";

    /** A ledger with one statement, its entry at sequence 5, and a checkpoint over it. */
    private static EvidenceBundleAssembler assembler(List<AnchorReceipt> receipts) {
        RecordContentStatementV1 statement = new RecordContentStatementV1("bedroom", "doc-1", "v-1",
                "att-1", "c".repeat(64), 12L, RecordContentStatementV1.CommitmentKind.CAPTURED,
                null, "2026-09-20T00:00:00Z");
        EvidenceLedgerEntry entry = EvidenceLedgerEntry.of(DOMAIN, 5L,
                EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE, "v-1", statement.digest(),
                "t5", null);
        EvidenceCheckpoint covering = EvidenceCheckpoint.of(DOMAIN, 5L, 5L, "aa", null,
                "2026-09-20T00:00:00Z");

        ContentWriteJournal journal = mock(ContentWriteJournal.class);
        when(journal.isActive()).thenReturn(true);
        when(journal.statementFor(anyString(), anyString())).thenReturn(statement.toDocument());
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(), anyInt())).thenReturn(List.of(entry));
        when(store.latestCheckpoint(anyString())).thenReturn(covering);
        when(store.checkpointEndingBefore(anyString(), anyLong())).thenReturn(null);
        EvidenceLedgerService ledger = mock(EvidenceLedgerService.class);
        when(ledger.inclusionProof(anyString(), anyLong())).thenReturn(Map.of("auditPath", List.of()));
        AnchorReceiptStore receiptStore = mock(AnchorReceiptStore.class);
        when(receiptStore.forCheckpoint(anyString(), anyLong())).thenReturn(receipts);

        EvidenceBundleAssembler assembler = new EvidenceBundleAssembler();
        assembler.setJournal(journal);
        assembler.setLedgerStore(store);
        assembler.setLedgerService(ledger);
        assembler.setReceiptStore(receiptStore);
        return assembler;
    }

    private static AnchorReceipt confirmedRfc3161(Map<String, String> attributes) {
        return AnchorReceipt.confirmed(AnchorKind.RFC3161_TSA, "aa", Instant.EPOCH, Instant.EPOCH,
                new byte[] { 0x30, 0x03 }, "proof-digest", attributes,
                AnchorKind.TimeSemantics.BIDIRECTIONAL_WITHIN_ACCURACY);
    }

    @Test
    @DisplayName("a rung with no receipt is NOT_PRESENT; a NOT_CONFIGURED receipt is NOT_CONFIGURED")
    void rungsAreClassifiedByTheirReceipts() {
        EvidenceBundle bundle = assembler(List.of(
                AnchorReceipt.notConfigured(AnchorKind.OPENTIMESTAMPS, "aa")))
                .assemble("bedroom", "doc-1", "v-1");

        assertEquals(EvidenceBundle.AnchorPart.State.NOT_PRESENT,
                bundle.anchors().get(AnchorKind.RFC3161_TSA).state(),
                "no receipt means nothing attempted this rung for this checkpoint — the enum's "
                        + "NOT_PRESENT. The manifest said \"unconfigured\" about a rung nobody "
                        + "asked, and a reader took that as a fact about the deployment (9-6 "
                        + "review, P3)");
        assertEquals(EvidenceBundle.AnchorPart.State.NOT_CONFIGURED,
                bundle.anchors().get(AnchorKind.OPENTIMESTAMPS).state(),
                "the anchor service wrote a NOT_CONFIGURED receipt, which IS the fact about the "
                        + "deployment");
    }

    @Test
    @DisplayName("revocation material that cannot be decoded refuses the bundle; material that is absent ships none")
    void undecodableMaterialRefusesTheBundle() {
        EvidenceBundleAssembler.EvidenceNotReadable refusal = assertThrows(
                EvidenceBundleAssembler.EvidenceNotReadable.class,
                () -> assembler(List.of(confirmedRfc3161(
                        Map.of(RevocationMaterial.MATERIAL_KEY, "not base64 !!!"))))
                        .assemble("bedroom", "doc-1", "v-1"),
                "material that is there and cannot be read shipped as none, and the package "
                        + "answered INDETERMINATE for P3's revocation check about a receipt that "
                        + "holds the answer (9-6 review, P3)");
        assertTrue(refusal.getMessage().contains("revocation material"), refusal.getMessage());

        EvidenceBundle bundle = assembler(List.of(confirmedRfc3161(Map.of())))
                .assemble("bedroom", "doc-1", "v-1");
        EvidenceBundle.AnchorPart rfc = bundle.anchors().get(AnchorKind.RFC3161_TSA);
        assertEquals(EvidenceBundle.AnchorPart.State.PRESENT, rfc.state());
        assertNull(rfc.revocationDer(), "no material on the receipt ships none — the ordinary case");

        // And material that IS there is carried — the arm the structural lock in the evidence
        // package used to be the only measurement of.
        byte[] crl = { 0x30, 0x05, 0x02, 0x03, 0x01, 0x02, 0x03 };
        EvidenceBundle withMaterial = assembler(List.of(confirmedRfc3161(Map.of(
                RevocationMaterial.MATERIAL_KEY, java.util.Base64.getEncoder().encodeToString(crl)))))
                .assemble("bedroom", "doc-1", "v-1");
        org.junit.jupiter.api.Assertions.assertArrayEquals(crl,
                withMaterial.anchors().get(AnchorKind.RFC3161_TSA).revocationDer(),
                "the captured material did not reach the bundle");
    }
}
