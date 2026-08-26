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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The caller that executes the fail-closed rule, and the persistence that makes it usable.
 *
 * <h2>What was missing</h2>
 *
 * <p>{@code recordVerifiedReceipt} returned an {@code Authorisation} whose javadoc said the
 * caller must not pass custody when it refuses. <b>There was no caller.</b> A rule enforced in
 * a comment holds until the first person who does not read the comment, and what it guards is a
 * repository deciding it is no longer answerable for a record.
 */
class CustodyTransferServiceTest {

    private static final String REPO = "bedroom";
    private static final String DIGEST = "b".repeat(64);

    /**
     * A store that behaves, through the REAL encoding.
     *
     * <p>It holds documents, not objects, and decodes on read — because the first version held
     * the objects and handed the same instance back. That made every test of "the move was not
     * written" pass for the wrong reason: the service had mutated the very object the store was
     * holding, so a refused write still changed what the store returned. CouchDB decodes a
     * fresh object per read, and so does this now. It also means every test here exercises
     * {@code document()} and {@code decode()}, which is where a persistence bug would live.
     */
    private static final class InMemoryStore implements CustodyTransferStore {
        final Map<String, Map<String, Object>> saved = new LinkedHashMap<>();
        boolean active = true;
        int refuseNextSaves;

        @Override public boolean isActive() {
            return active;
        }

        @Override public boolean save(CustodyTransfer transfer) {
            if (refuseNextSaves > 0) {
                refuseNextSaves--;
                return false;
            }
            saved.put(transfer.transferId(), CouchCustodyTransferStore.document(transfer));
            return true;
        }

        @Override public CustodyTransfer find(String repositoryId, String transferId) {
            return CouchCustodyTransferStore.decode(saved.get(transferId));
        }

        @Override public List<CustodyTransfer> findByObject(String repositoryId, String objectId,
                int limit) {
            List<CustodyTransfer> found = new ArrayList<>();
            for (Map<String, Object> doc : saved.values()) {
                CustodyTransfer decoded = CouchCustodyTransferStore.decode(doc);
                if (decoded != null && objectId.equals(decoded.objectId())) {
                    found.add(decoded);
                }
            }
            return found;
        }
    }

    private InMemoryStore store;
    private CustodyLedgerRecorder recorder;
    private CustodyTransferService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        recorder = mock(CustodyLedgerRecorder.class);
        service = new CustodyTransferService();
        service.setStore(store);
        service.setLedgerRecorder(recorder);
    }

    private static CustodyReceipt receipt() {
        return new CustodyReceipt("sub-1", "aip-1", "c".repeat(64), DIGEST, "PASSED",
                "roda-agent", "2026-08-26T02:00:00Z", null, false);
    }

    /** Opens and walks to RECEIPT_VERIFIED, the state passCustody starts from. */
    private void atReceiptVerified() {
        assertTrue(service.open(REPO, "t-1", "doc-1", DIGEST, "roda").done());
        for (CustodyState next : List.of(CustodyState.SENT, CustodyState.RECEIVED,
                CustodyState.VALIDATED, CustodyState.INGEST_ACCEPTED, CustodyState.AIP_CREATED)) {
            assertTrue(service.advance(REPO, "t-1", next, "step").done(), next.name());
        }
        assertTrue(service.verifyReceipt(REPO, "t-1", receipt()).done());
    }

    @Test
    @DisplayName("custody does NOT pass when the handover could not be recorded")
    void anUnrecordableHandoverDoesNotPassCustody() {
        // The rule, executed. Custody has not passed when we try to chain it, so refusing costs
        // a retry — and proceeding would mean this repository stopped being answerable for a
        // record with nothing anywhere saying when or to whom.
        atReceiptVerified();
        when(recorder.recordVerifiedReceipt(any(), anyString())).thenReturn(
                new CustodyLedgerRecorder.Authorisation(false, "the ledger is not reachable"));

        CustodyTransferService.Outcome outcome = service.passCustody(REPO, "t-1");

        assertFalse(outcome.done(), "custody passed with nothing recording that it did");
        assertEquals(CustodyState.RECEIPT_VERIFIED, store.find(REPO, "t-1").state());
        assertTrue(outcome.refusedReason().contains("not reachable"), outcome.refusedReason());
    }

    @Test
    @DisplayName("custody passes when the handover WAS recorded")
    void arecordedHandoverPassesCustody() {
        atReceiptVerified();
        when(recorder.recordVerifiedReceipt(any(), anyString())).thenReturn(
                new CustodyLedgerRecorder.Authorisation(true, null));

        CustodyTransferService.Outcome outcome = service.passCustody(REPO, "t-1");

        assertTrue(outcome.done(), String.valueOf(outcome.refusedReason()));
        assertEquals(CustodyState.CUSTODY_TRANSFERRED, store.find(REPO, "t-1").state());
    }

    @Test
    @DisplayName("the recording happens BEFORE the move, not after")
    void theRecordingComesFirst() {
        // Recording after moving would leave the window this whole design exists to close: the
        // transfer says custody passed, the chain says nothing, and the process crashed in
        // between.
        atReceiptVerified();
        List<String> order = new ArrayList<>();
        when(recorder.recordVerifiedReceipt(any(), anyString())).thenAnswer(call -> {
            CustodyTransfer seen = call.getArgument(0);
            order.add("recorded-at-" + seen.state());
            return new CustodyLedgerRecorder.Authorisation(true, null);
        });

        service.passCustody(REPO, "t-1");

        assertEquals(List.of("recorded-at-RECEIPT_VERIFIED"), order,
                "the handover was recorded after the move, so a crash in between leaves a "
                        + "transfer that says custody passed and a chain that does not");
    }

    @Test
    @DisplayName("advance() refuses CUSTODY_TRANSFERRED — the rule has one door")
    void custodyDoesNotPassThroughTheOrdinaryDoor() {
        // Letting it through here would put the ledger rule back where it was: in a comment.
        atReceiptVerified();

        CustodyTransferService.Outcome outcome = service.advance(REPO, "t-1",
                CustodyState.CUSTODY_TRANSFERRED, "we are done");

        assertFalse(outcome.done());
        assertTrue(outcome.refusedReason().contains("passCustody"), outcome.refusedReason());
        verify(recorder, never()).recordVerifiedReceipt(any(), anyString());
        assertEquals(CustodyState.RECEIPT_VERIFIED, store.find(REPO, "t-1").state());
    }

    @Test
    @DisplayName("a move that was not written did not happen")
    void aMoveThatDidNotReachTheStoreIsRefused() {
        // A move in memory that the store rejected leaves a transfer whose state is one thing
        // to this process and another to the next one — and the receipt arriving tomorrow is
        // checked against the wrong one.
        assertTrue(service.open(REPO, "t-1", "doc-1", DIGEST, "roda").done());
        store.refuseNextSaves = 1;

        CustodyTransferService.Outcome outcome = service.advance(REPO, "t-1", CustodyState.SENT,
                "posted");

        assertFalse(outcome.done());
        assertEquals(CustodyState.PACKAGE_CREATED, store.find(REPO, "t-1").state(),
                "the stored transfer moved even though the write was refused");
    }

    @Test
    @DisplayName("a chained handover whose transfer was not written says so")
    void aRecordedHandoverThatDidNotSaveIsNotSilent() {
        // The entry is in the chain and the row is not. An operator reconciling the two has to
        // be told which way round it is; reporting success would hide it entirely.
        atReceiptVerified();
        when(recorder.recordVerifiedReceipt(any(), anyString())).thenReturn(
                new CustodyLedgerRecorder.Authorisation(true, null));
        store.refuseNextSaves = 1;

        CustodyTransferService.Outcome outcome = service.passCustody(REPO, "t-1");

        assertFalse(outcome.done());
        assertTrue(outcome.refusedReason().contains("chain holds an entry this transfer does not"),
                outcome.refusedReason());
    }

    @Test
    @DisplayName("with no store, nothing is opened — and it says why")
    void noStoreMeansNoTransfer() {
        store.active = false;

        CustodyTransferService.Outcome outcome = service.open(REPO, "t-1", "doc-1", DIGEST, "roda");

        assertFalse(outcome.done());
        assertTrue(outcome.refusedReason().contains("lost at the next restart"),
                outcome.refusedReason());
    }

    @Test
    @DisplayName("with no ledger recorder, custody does not pass")
    void noRecorderMeansNoHandover() {
        atReceiptVerified();
        service.setLedgerRecorder(null);

        CustodyTransferService.Outcome outcome = service.passCustody(REPO, "t-1");

        assertFalse(outcome.done());
        assertEquals(CustodyState.RECEIPT_VERIFIED, store.find(REPO, "t-1").state());
    }

    @Test
    @DisplayName("a transfer nobody stored is not a statement that nothing happened")
    void anUnknownTransferSaysWhatItMeans() {
        CustodyTransferService.Outcome outcome = service.passCustody(REPO, "never-opened");

        assertFalse(outcome.done());
        assertTrue(outcome.refusedReason().contains("NOT a statement that the handover did not"),
                outcome.refusedReason());
    }

    @Test
    @DisplayName("the transfer survives being written and read back")
    void itRoundTripsThroughTheStore() {
        atReceiptVerified();
        CustodyTransfer stored = store.find(REPO, "t-1");

        assertNotNull(stored);
        assertEquals(CustodyState.RECEIPT_VERIFIED, stored.state());
        assertEquals("aip-1", stored.receipt().aipId());
        assertEquals(7, stored.history().size(), "a step was lost on the way through");
    }
}
