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

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.evidence.CouchEvidenceLedgerStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two requests holding the same transfer cannot both apply a move (P3-4 §7).
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code save()} looked the current revision up at write time and updated against it. That
 * read always succeeds and always returns the NEWEST revision — including one another request
 * has just written — so the write it authorised was precisely the lost update the conflict
 * handling below it claimed to catch. Both callers were told the move had been applied, and only
 * the later one survived. The {@code catch (isConflict)} branch could only ever fire on a race
 * inside {@code save}'s own microsecond-wide window, never on the request-level race that
 * actually happens.
 *
 * <p>A lost move here is not a lost field. The states are the diagnosis — "it reached
 * INGEST_ACCEPTED and has not moved since" is the whole point of the machine — so a move that
 * vanishes leaves the record saying a thing happened that did not, or did not happen when it did.
 *
 * <h2>Why this is a fake database rather than a live one</h2>
 *
 * <p>The failure needs two writers interleaved over one row. Against a real CouchDB that is a
 * timing test, and a timing test that passes is not evidence. The row below models exactly the
 * one behaviour under test — a revision that changes when someone writes — and nothing else.
 */
final class StaleWritesAreRefusedTest {

    private static final String REPO = "bedroom";
    private static final String TRANSFER = "t-1";
    private static final String DIGEST =
            "26b9bd3e3be50260cc7580be38113bbc0000000000000000000000000000abcd";

    /** One CouchDB row: a body and a revision that moves when it is written. */
    private static final class Row {
        private String rev;
        private Map<String, Object> body;
        private int next = 1;

        private String bump() {
            rev = (next++) + "-x";
            return rev;
        }
    }

    @Test
    @DisplayName("a move made against a revision another request has already superseded is refused")
    void aMoveMadeAgainstAStaleReadIsRefused() throws Exception {
        Row row = new Row();
        CouchCustodyTransferStore store = storeOver(row);
        assertTrue(store.save(opened()), "the row should have been created");

        // Two requests, each loading the transfer for itself. This is the ordinary shape: the
        // service has no cache, so every request decodes its own object out of the row.
        CustodyTransfer first = store.find(REPO, TRANSFER);
        CustodyTransfer second = store.find(REPO, TRANSFER);

        assertTrue(first.advance(CustodyState.SENT, "2026-08-27T00:00:01Z", "handed over")
                .accepted());
        assertTrue(store.save(first), "the first mover's write should have been accepted");

        assertTrue(second.advance(CustodyState.SENT, "2026-08-27T00:00:02Z", "handed over")
                .accepted());
        assertFalse(store.save(second),
                "a move built on a read that is now stale was written anyway. The other "
                        + "request's move is gone and both callers were told they succeeded — "
                        + "which is the state machine reporting a history nobody walked");
    }

    @Test
    @DisplayName("a transfer built afresh does not overwrite one that is already stored")
    void aFreshObjectDoesNotOverwriteAStoredTransfer() throws Exception {
        Row row = new Row();
        CouchCustodyTransferStore store = storeOver(row);

        CustodyTransfer stored = opened();
        assertTrue(store.save(stored));
        assertTrue(stored.advance(CustodyState.SENT, "2026-08-27T00:00:01Z", "handed over")
                .accepted());
        assertTrue(store.save(stored));

        // Same ids, never read from the store, still at PACKAGE_CREATED. Writing it would erase
        // the move above and reset the transfer to "nothing has been sent".
        assertFalse(store.save(opened()),
                "an object that never read the row overwrote it, so a transfer that had been "
                        + "sent reads afterwards as one that never left");
        assertEquals(CustodyState.SENT, store.find(REPO, TRANSFER).state(),
                "the stored row no longer says what the last accepted move said");
    }

    @Test
    @DisplayName("two moves in one request are both written — the control")
    void twoMovesInOneRequestAreBothWritten() throws Exception {
        // Without this, refusing every write after the first would satisfy the two tests above
        // and leave a store that can record one move per transfer and no more.
        Row row = new Row();
        CouchCustodyTransferStore store = storeOver(row);

        CustodyTransfer transfer = opened();
        assertTrue(store.save(transfer));
        assertTrue(transfer.advance(CustodyState.SENT, "2026-08-27T00:00:01Z", "handed over")
                .accepted());
        assertTrue(store.save(transfer));
        assertTrue(transfer.advance(CustodyState.RECEIVED, "2026-08-27T00:00:02Z", "they have it")
                .accepted());
        assertTrue(store.save(transfer),
                "the revision a successful write produced was not carried forward, so a "
                        + "transfer can only ever be moved once per request");

        assertEquals(CustodyState.RECEIVED, store.find(REPO, TRANSFER).state());
    }

    private static CustodyTransfer opened() {
        return new CustodyTransfer(TRANSFER, REPO, "obj-1", DIGEST, "RODA 6.3.0",
                "2026-08-27T00:00:00Z");
    }

    @SuppressWarnings("unchecked")
    private static CouchCustodyTransferStore storeOver(Row row) throws Exception {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);

        when(client.get(anyString())).thenAnswer(invocation -> {
            if (row.rev == null) {
                return null;
            }
            Document document = mock(Document.class);
            when(document.getRev()).thenReturn(row.rev);
            when(document.getProperties()).thenReturn(new LinkedHashMap<>(row.body));
            return document;
        });

        when(client.create(anyString(), any())).thenAnswer(invocation -> {
            if (row.rev != null) {
                throw new RuntimeException("Document update conflict (409)");
            }
            row.body = new LinkedHashMap<>((Map<String, Object>) invocation.getArgument(1));
            return okResult(row.bump());
        });

        when(client.update(any())).thenAnswer(invocation -> {
            Map<String, Object> document = (Map<String, Object>) invocation.getArgument(0);
            if (!String.valueOf(document.get("_rev")).equals(row.rev)) {
                throw new RuntimeException("Document update conflict (409)");
            }
            row.body = new LinkedHashMap<>(document);
            return okResult(row.bump());
        });

        CouchEvidenceLedgerStore ledger = new CouchEvidenceLedgerStore();
        Method useClientForTests = CouchEvidenceLedgerStore.class
                .getDeclaredMethod("useClientForTests", CloudantClientWrapper.class);
        useClientForTests.setAccessible(true);
        useClientForTests.invoke(ledger, client);

        CouchCustodyTransferStore store = new CouchCustodyTransferStore();
        store.setLedgerStore(ledger);
        return store;
    }

    private static DocumentResult okResult(String rev) {
        DocumentResult result = mock(DocumentResult.class);
        when(result.isOk()).thenReturn(true);
        when(result.getRev()).thenReturn(rev);
        return result;
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a view that did not answer is not 'never sent anywhere'")
    void anUnansweredViewIsNotACompleteEmptyHistory() throws Exception {
        // findByObject's completeness travels with the list -- the service turns lastUnreadable
        // into complete=false and the endpoint prints it -- and the one arm that returned before
        // any row was examined left the counter at zero. So a view that did not answer produced
        // `complete: true, transfers: []`: "this record has no transfers", from a question
        // nobody managed to ask. The class javadoc of the service says exactly this must not
        // happen; the guard was on the rows, not on the answer.
        for (com.ibm.cloud.cloudant.v1.model.ViewResult answer : new
                com.ibm.cloud.cloudant.v1.model.ViewResult[] { null, viewWithNullRows() }) {
            CloudantClientWrapper client = org.mockito.Mockito.mock(CloudantClientWrapper.class);
            org.mockito.Mockito.when(client.queryView(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyMap())).thenReturn(answer);
            CouchCustodyTransferStore store = storeOverClient(client);

            org.junit.jupiter.api.Assertions.assertTrue(
                    store.findByObject(REPO, "obj-1", 10).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(store.unreadableCount() > 0,
                    "an unanswered view produced an empty list that reports itself complete, so "
                            + "the endpoint says this record was never sent anywhere");

            // ...and what the operator READS must not turn "the question could not be asked"
            // into "one transfer exists that could not be read". The store counts an unanswered
            // view as one, so the flat rendering asserted an existence nobody established and
            // sent someone looking for a transfer that may never have happened. The direction
            // was already safe (complete=false either way); the sentence was not.
            CustodyTransferService service = new CustodyTransferService();
            service.setStore(store);
            CustodyTransferService.Listed listed = service.findByObject(REPO, "obj-1", 10);
            org.junit.jupiter.api.Assertions.assertFalse(listed.complete());
            org.junit.jupiter.api.Assertions.assertTrue(
                    listed.incomplete().contains("could NOT BE QUERIED"),
                    "the answer still counts the unanswered view as a transfer: "
                            + listed.incomplete());
            // "at least 1" was the first attempt and it STILL asserted an existence — one or
            // more is a claim that a transfer is there, and here there may be none. The two
            // facts are separated in the store now rather than folded into one integer.
            org.junit.jupiter.api.Assertions.assertFalse(
                    listed.incomplete().contains("stored transfer(s) for this record could not "
                            + "be read"),
                    "the answer says a transfer exists that could not be read, from a question "
                            + "nobody managed to ask: " + listed.incomplete());
            org.junit.jupiter.api.Assertions.assertTrue(
                    listed.incomplete().contains("not a finding that there are none"),
                    "the answer closes off the possibility it cannot rule out: "
                            + listed.incomplete());
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a record with genuinely no transfers is complete — control")
    void aRecordWithNoTransfersIsStillComplete() throws Exception {
        com.ibm.cloud.cloudant.v1.model.ViewResult empty =
                org.mockito.Mockito.mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        org.mockito.Mockito.when(empty.getRows()).thenReturn(java.util.List.of());
        CloudantClientWrapper client = org.mockito.Mockito.mock(CloudantClientWrapper.class);
        org.mockito.Mockito.when(client.queryView(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(empty);
        CouchCustodyTransferStore store = storeOverClient(client);

        org.junit.jupiter.api.Assertions.assertTrue(
                store.findByObject(REPO, "obj-1", 10).isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(0, store.unreadableCount(),
                "a record that genuinely has no transfers was reported as unreadable");
    }

    private static com.ibm.cloud.cloudant.v1.model.ViewResult viewWithNullRows() {
        com.ibm.cloud.cloudant.v1.model.ViewResult answered =
                org.mockito.Mockito.mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        org.mockito.Mockito.when(answered.getRows()).thenReturn(null);
        return answered;
    }

    private static CouchCustodyTransferStore storeOverClient(CloudantClientWrapper client)
            throws Exception {
        CouchEvidenceLedgerStore ledger = new CouchEvidenceLedgerStore();
        Method useClientForTests = CouchEvidenceLedgerStore.class
                .getDeclaredMethod("useClientForTests", CloudantClientWrapper.class);
        useClientForTests.setAccessible(true);
        useClientForTests.invoke(ledger, client);
        CouchCustodyTransferStore store = new CouchCustodyTransferStore();
        store.setLedgerStore(ledger);
        return store;
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a verified signature does not survive the reload, so the digest cannot commit it")
    void theVerifiedFindingIsGoneByTheTimeTheDigestIsTaken() throws Exception {
        // MEASURED, not reasoned. receiptDigest commits signatureVerified, and its comment says
        // "a receipt taken on trust and one whose signature was verified are different facts,
        // and an entry that digested the same for both would lose the distinction". This test
        // exists to find out whether the digest actually keeps that distinction.
        //
        // It cannot: passCustody LOADS the transfer, and decode() deliberately forces
        // signatureVerified to false ("a finding read back out of a row anyone with database
        // access can edit is an assertion wearing a finding's name"). So by the time the digest
        // is taken the input is false for every handover that went through the REST flow — the
        // two receipts the comment distinguishes digest identically, always.
        //
        // The rule the store states is right. What was wrong is the claim next to the digest.
        Row row = new Row();
        CouchCustodyTransferStore store = storeOver(row);
        CustodyTransfer opened = new CustodyTransfer(TRANSFER, REPO, "obj-1", DIGEST,
                "RODA", "2026-08-28T00:00:00Z");
        for (CustodyState next : java.util.List.of(CustodyState.SENT, CustodyState.RECEIVED,
                CustodyState.VALIDATED, CustodyState.INGEST_ACCEPTED, CustodyState.AIP_CREATED)) {
            opened.advance(next, "2026-08-28T00:01:00Z", "step");
        }
        CustodyReceipt verified = new CustodyReceipt("sub-1", "aip-1", null, DIGEST,
                "SUCCESS", "roda-agent", "2026-08-28T00:02:00Z", "sig", true);
        org.junit.jupiter.api.Assertions.assertTrue(
                opened.verifyReceipt(verified, "2026-08-28T00:03:00Z").accepted());
        org.junit.jupiter.api.Assertions.assertTrue(opened.receipt().signatureVerified(),
                "fixture check: the receipt was not verified in memory, so this test would "
                        + "prove nothing");
        store.save(opened);

        CustodyTransfer reloaded = store.find(REPO, TRANSFER);

        org.junit.jupiter.api.Assertions.assertFalse(reloaded.receipt().signatureVerified(),
                "the finding survived the reload, which would mean a row anyone can edit is "
                        + "read back as a verification this product performed");
        // ... and therefore the digest input is the same for both.
        org.junit.jupiter.api.Assertions.assertEquals(
                CustodyLedgerRecorder.receiptDigest(reloaded),
                CustodyLedgerRecorder.receiptDigest(withUnverifiedReceipt(reloaded)),
                "the digest still distinguishes a verified receipt from one taken on trust at "
                        + "the point it is taken — if this passes, the claim beside receiptDigest "
                        + "can be restored and this test rewritten");
    }

    private static CustodyTransfer withUnverifiedReceipt(CustodyTransfer transfer) {
        CustodyReceipt r = transfer.receipt();
        CustodyTransfer copy = CustodyTransfer.restore(transfer.transferId(),
                transfer.repositoryId(), transfer.objectId(), transfer.sipDigest(),
                transfer.receivingSystem(), transfer.state(),
                new CustodyReceipt(r.submissionId(), r.aipId(), r.aipChecksum(), r.sipDigest(),
                        r.verificationOutcome(), r.reportedOutcome(), r.receivingAgent(),
                        r.receivedAt(), r.signature(), false),
                transfer.history());
        return copy;
    }
}
