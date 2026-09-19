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
package jp.aegif.nemaki.evidence.anchor;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.evidence.CouchEvidenceLedgerStore;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The receipt store does not report a write it did not make.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>{@code AnchorReceiptPersistenceTest} exercises an in-memory stand-in, so the class that
 * actually talks to CouchDB had no tests at all — while the design document said "persistence is
 * the load-bearing part of rung 2". Two reviewers pointed at the same gap, and inside it was a
 * real defect: {@link CouchAnchorReceiptStore#save} checked the result of {@code create} and
 * discarded the result of {@code update}. The update is the PENDING to CONFIRMED transition —
 * the single write the class exists for.
 */
class CouchAnchorReceiptStoreTest {

    private static final String DOMAIN = "bedroom";
    private static final String ROOT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static CouchAnchorReceiptStore storeWith(CloudantClientWrapper client)
            throws Exception {
        CouchEvidenceLedgerStore ledger = new CouchEvidenceLedgerStore();
        java.lang.reflect.Method useClientForTests = CouchEvidenceLedgerStore.class
                .getDeclaredMethod("useClientForTests", CloudantClientWrapper.class);
        useClientForTests.setAccessible(true);
        useClientForTests.invoke(ledger, client);
        CouchAnchorReceiptStore store = new CouchAnchorReceiptStore();
        store.setLedgerStore(ledger);
        return store;
    }

    private static AnchorReceipt confirmed() {
        return AnchorReceipts.confirmed(AnchorKind.RFC3161_TSA, ROOT,
                Instant.parse("2026-08-24T00:00:00Z"), new byte[] { 1, 2, 3 },
                Map.of("digestAlgorithm", "SHA-256"));
    }

    @Test
    @DisplayName("an UPDATE the database did not accept is not reported as stored")
    void aRejectedUpdateIsNotSilent() throws Exception {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        // A row already exists, so save() takes the update branch — the PENDING to CONFIRMED
        // path. This is where the result used to be thrown away.
        Document existing = mock(Document.class);
        when(existing.getRev()).thenReturn("1-abc");
        when(client.get(anyString())).thenReturn(existing);
        when(client.update(any())).thenReturn(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> storeWith(client).save(DOMAIN, 5, confirmed()),
                "an update the database refused returned normally; upgradePending would then "
                        + "list the receipt as upgraded and the settled proof would be lost");
        assertTrue(e.getMessage().contains("not updated"), e.getMessage());
    }

    @Test
    @DisplayName("an accepted update returns normally — the control")
    void anAcceptedUpdateIsFine() throws Exception {
        // Without this, throwing unconditionally would pass the test above and nothing could
        // ever be upgraded.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        Document existing = mock(Document.class);
        when(existing.getRev()).thenReturn("1-abc");
        when(client.get(anyString())).thenReturn(existing);
        DocumentResult ok = mock(DocumentResult.class);
        when(ok.isOk()).thenReturn(true);
        when(client.update(any())).thenReturn(ok);

        storeWith(client).save(DOMAIN, 5, confirmed());
    }

    @Test
    @DisplayName("a CREATE the database did not accept is not reported as stored")
    void aRejectedCreateIsNotSilent() throws Exception {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(anyString())).thenReturn(null);
        when(client.create(anyString(), any())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> storeWith(client).save(DOMAIN, 5, confirmed()),
                "a create the database refused returned normally; a PENDING commitment that "
                        + "was never written can never be upgraded");
    }

    // ---- the compare-and-set window ----

    @Test
    @DisplayName("a CONFIRMED receipt written DURING our write is not overwritten")
    void aRaceLostToAConfirmedWriterIsRespected() throws Exception {
        // The race a service-level read-then-write cannot close: this writer reads PENDING,
        // another writer stores CONFIRMED, and this writer's update then lands on top. Here
        // the rule is inside the same compare-and-set as the write, so the stale revision is
        // rejected, the row is read AGAIN, and the CONFIRMED receipt that arrived meanwhile is
        // seen and kept.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        Document pendingRow = rowHolding(pending());
        Document confirmedRow = rowHolding(confirmed());
        // First read: PENDING. Second read (after the lost race): CONFIRMED.
        when(client.get(anyString())).thenReturn(pendingRow, confirmedRow);
        // WRAPPED, as production delivers it: CloudantClientWrapper.update always wraps in a
        // plain RuntimeException, so a raw conflict here would let isConflict degrade to a
        // bare instanceof while the real retry died silently (review).
        when(client.update(any())).thenThrow(new RuntimeException(
                "Failed to update document in database 'nemaki_evidence_ledger': conflict",
                jp.aegif.nemaki.dao.impl.couch.connector.CouchConflicts.conflict()));

        AnchorReceiptStore.SaveOutcome outcome = storeWith(client).save(DOMAIN, 5, pending());

        assertEquals(AnchorReceiptStore.SaveOutcome.KEPT_STRONGER, outcome,
                "a PENDING receipt overwrote a CONFIRMED one that arrived between our read and "
                        + "our write; hours of waiting on a Bitcoin block are discarded by a "
                        + "concurrent job");
    }

    @Test
    @DisplayName("a lost race with no stronger receipt is simply retried — the control")
    void aLostRaceWithoutAStrongerReceiptRetries() throws Exception {
        // Without this, answering KEPT_STRONGER to every conflict would pass the test above
        // while making ordinary contention silently lose writes.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        Document pendingRow = rowHolding(pending());
        when(client.get(anyString())).thenReturn(pendingRow);
        DocumentResult ok = mock(DocumentResult.class);
        when(ok.isOk()).thenReturn(true);
        when(client.update(any()))
                .thenThrow(jp.aegif.nemaki.dao.impl.couch.connector.CouchConflicts.conflict())
                .thenReturn(ok);

        AnchorReceiptStore.SaveOutcome outcome = storeWith(client).save(DOMAIN, 5, pending());

        assertEquals(AnchorReceiptStore.SaveOutcome.STORED, outcome,
                "an ordinary lost race was not retried, so the receipt was dropped");
    }

    @Test
    @DisplayName("a weaker receipt is refused WITHOUT any contention, and nothing is written")
    void aWeakerReceiptIsRefusedOnTheFirstRead() throws Exception {
        // The ordinary path: a FAILED attempt arrives while a CONFIRMED token is already
        // stored, with no race at all. Every other monotonicity test here relies on a lost
        // race, so making the check fire only from the second attempt onwards left them all
        // green (review).
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        Document confirmedRow = rowHolding(confirmed());
        when(client.get(anyString())).thenReturn(confirmedRow);

        AnchorReceiptStore.SaveOutcome outcome = storeWith(client).save(DOMAIN, 5,
                AnchorReceipt.failed(AnchorKind.RFC3161_TSA, ROOT, Instant.now(),
                        "TSA unreachable"));

        assertEquals(AnchorReceiptStore.SaveOutcome.KEPT_STRONGER, outcome);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).update(any());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
                .create(anyString(), any());
    }

    @Test
    @DisplayName("a FAILED re-check does not replace a PENDING commitment")
    void aFailedRecheckDoesNotKillAPendingCommitment() throws Exception {
        // The rule used to protect CONFIRMED and nothing else, and that is the rung that loses
        // proofs. AnchorService.upgradePending stores whatever the rung hands back, and
        // pending() only ever returns PENDING rows — so one FAILED re-check (a timeout, a
        // calendar that answered 500) permanently removes the commitment from the list of
        // things this deployment will ever ask about again. The calendar still holds it and a
        // block still confirms it; we have simply stopped asking, with no error anywhere.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        Document pendingRow = rowHolding(pending());
        when(client.get(anyString())).thenReturn(pendingRow);

        AnchorReceiptStore.SaveOutcome outcome = storeWith(client).save(DOMAIN, 5,
                AnchorReceipt.failed(AnchorKind.RFC3161_TSA, ROOT, Instant.now(),
                        "the calendar did not answer"));

        assertEquals(AnchorReceiptStore.SaveOutcome.KEPT_STRONGER, outcome,
                "a transient re-check failure overwrote a live commitment; it can never be "
                        + "re-checked, so the proof is lost silently");
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).update(any());
    }

    @Test
    @DisplayName("a rung that went unconfigured does not erase what an attempt found out")
    void anUnconfiguredReceiptDoesNotReplaceAFailedOne() throws Exception {
        // The ordering has four steps and only PENDING > FAILED was measured; the doc claimed
        // the whole ordering was. NOT_CONFIGURED is reachable in production — every rung is
        // constructed and isConfigured() answers on configuration, so a URL removed or a
        // publisher bean that has not come up yet turns a rung's receipt into NOT_CONFIGURED
        // mid-life. "We did not try" must not overwrite "we tried and it did not work".
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        Document failedRow = rowHolding(AnchorReceipt.failed(AnchorKind.RFC3161_TSA, ROOT,
                Instant.parse("2026-08-24T00:00:00Z"), "the TSA refused the request"));
        when(client.get(anyString())).thenReturn(failedRow);

        AnchorReceiptStore.SaveOutcome outcome = storeWith(client).save(DOMAIN, 5,
                AnchorReceipt.notConfigured(AnchorKind.RFC3161_TSA, ROOT));

        assertEquals(AnchorReceiptStore.SaveOutcome.KEPT_STRONGER, outcome,
                "a NOT_CONFIGURED receipt erased a FAILED one, so the reason the rung did not "
                        + "work is gone and the row reads as though nobody ever tried");
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).update(any());
    }

    @Test
    @DisplayName("a FAILED attempt DOES replace a NOT_CONFIGURED row — the control")
    void aFailedAttemptReplacesAnUnconfiguredRow() throws Exception {
        // Without this, ranking the two equally (or refusing both directions) would pass the
        // test above while freezing a rung at NOT_CONFIGURED once it had ever been unconfigured.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        Document unconfiguredRow = rowHolding(
                AnchorReceipt.notConfigured(AnchorKind.RFC3161_TSA, ROOT));
        when(client.get(anyString())).thenReturn(unconfiguredRow);
        DocumentResult ok = mock(DocumentResult.class);
        when(ok.isOk()).thenReturn(true);
        when(client.update(any())).thenReturn(ok);

        AnchorReceiptStore.SaveOutcome outcome = storeWith(client).save(DOMAIN, 5,
                AnchorReceipt.failed(AnchorKind.RFC3161_TSA, ROOT, Instant.now(),
                        "the TSA refused the request"));

        assertEquals(AnchorReceiptStore.SaveOutcome.STORED, outcome,
                "an actual attempt could not replace 'nobody tried', so the rung is stuck");
    }

    @Test
    @DisplayName("a PENDING commitment still replaces a FAILED attempt — the control")
    void aPendingCommitmentReplacesAFailedAttempt() throws Exception {
        // Without this, refusing every write over an existing row would pass the test above and
        // freeze the first attempt for ever: a rung that failed once could never come back.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        Document failedRow = rowHolding(AnchorReceipt.failed(AnchorKind.RFC3161_TSA, ROOT,
                Instant.parse("2026-08-24T00:00:00Z"), "the calendar did not answer"));
        when(client.get(anyString())).thenReturn(failedRow);
        DocumentResult ok = mock(DocumentResult.class);
        when(ok.isOk()).thenReturn(true);
        when(client.update(any())).thenReturn(ok);

        AnchorReceiptStore.SaveOutcome outcome = storeWith(client).save(DOMAIN, 5, pending());

        assertEquals(AnchorReceiptStore.SaveOutcome.STORED, outcome,
                "a new commitment could not replace an earlier failure, so the rung is stuck");
    }

    @Test
    @DisplayName("endless contention REFUSES rather than forcing the write")
    void endlessContentionRefuses() throws Exception {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        // Built BEFORE the stubbing: rowHolding() creates mocks of its own, and doing that
        // inside when(...) leaves Mockito with an unfinished stub.
        Document row = rowHolding(pending());
        when(client.get(anyString())).thenReturn(row);
        when(client.update(any()))
                .thenThrow(jp.aegif.nemaki.dao.impl.couch.connector.CouchConflicts.conflict());

        // Giving up and forcing the write is the one outcome this method exists to prevent.
        assertThrows(RuntimeException.class,
                () -> storeWith(client).save(DOMAIN, 5, pending()),
                "the store gave up and forced the write after exhausting its retries");
        // And it really RETRIED. Without this the test passes for an implementation that
        // throws on the first conflict and never loops at all.
        org.mockito.Mockito.verify(client,
                        org.mockito.Mockito.times(CouchAnchorReceiptStore.MAX_SAVE_ATTEMPTS))
                .update(any());
    }

    private static AnchorReceipt pending() {
        byte[] proof = { 9, 9, 9 };
        return AnchorReceipt.pending(AnchorKind.RFC3161_TSA, ROOT,
                Instant.parse("2026-08-24T00:00:00Z"), proof,
                jp.aegif.nemaki.rest.purview.anchor.AnchorReceiptCodec.sha256Hex(proof),
                Map.of());
    }

    /** A stored row carrying this receipt, as CouchDB would hand it back. */
    private static Document rowHolding(AnchorReceipt receipt) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("type", "anchor_receipt");
        row.put("domain", DOMAIN);
        row.put("toSequence", 5L);
        row.put("rung", receipt.kind().name());
        row.put("receipt", jp.aegif.nemaki.rest.purview.anchor.AnchorReceiptCodec
                .toDocument(receipt));
        Document doc = mock(Document.class);
        when(doc.getRev()).thenReturn("1-abc");
        when(doc.getProperties()).thenReturn(row);
        return doc;
    }

    @Test
    @DisplayName("one row per rung — the three rungs do not overwrite each other")
    void eachRungGetsItsOwnRow() {
        String tsa = CouchAnchorReceiptStore.documentId(DOMAIN, 5, AnchorKind.RFC3161_TSA.name());
        String ots = CouchAnchorReceiptStore.documentId(DOMAIN, 5,
                AnchorKind.OPENTIMESTAMPS.name());

        // Dropping the rung from the id would make each rung's receipt replace the previous
        // one, so a checkpoint could only ever show one anchor however many it had.
        org.junit.jupiter.api.Assertions.assertNotEquals(tsa, ots,
                "two rungs share a document id at the same checkpoint");
        assertTrue(tsa.contains(AnchorKind.RFC3161_TSA.name()), tsa);
    }

    @Test
    @DisplayName("a view that did not answer is not 'there are no receipts'")
    void aViewThatDidNotAnswerIsNotAnEmptyStore() throws Exception {
        // AnchorService.upgradePending was just given a failure channel so that "the store is
        // not wired" stops reading as "nothing had settled yet ... do not re-anchor". It asks
        // isActive() and unreadableCount(). Neither notices THIS: isActive() only checks that a
        // client object exists, and a view answering nothing dropped no row, so both said the
        // store was fine and every read came back empty. The correction had been applied one
        // layer up from where the substitution actually happens.
        for (com.ibm.cloud.cloudant.v1.model.ViewResult answer : viewsThatDidNotAnswer()) {
            CloudantClientWrapper client = mock(CloudantClientWrapper.class);
            when(client.queryView(anyString(), anyString(), anyMap())).thenReturn(answer);
            CouchAnchorReceiptStore store = storeWith(client);

            org.junit.jupiter.api.Assertions.assertTrue(store.pending(DOMAIN, 10).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(store.unreadableCount() > 0,
                    "pending() came back empty and reported nothing unaccounted for, so a "
                            + "caller cannot tell it from 'asked, none are pending'");
            org.junit.jupiter.api.Assertions.assertTrue(store.confirmed(DOMAIN, 10).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(store.unreadableCount() > 0,
                    "confirmed() lost the same distinction");
            org.junit.jupiter.api.Assertions.assertTrue(
                    store.forCheckpoint(DOMAIN, 9).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(store.unreadableCount() > 0,
                    "forCheckpoint() lost the same distinction");
        }
    }

    @Test
    @DisplayName("a view that answered with no rows counts nothing unreadable — the control")
    void anAnsweredEmptyViewIsNotUnreadable() throws Exception {
        // Without this, counting every read as unreadable would satisfy the test above and make
        // a repository with no receipts yet refuse to anchor for ever.
        com.ibm.cloud.cloudant.v1.model.ViewResult empty =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(empty.getRows()).thenReturn(java.util.List.of());
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(anyString(), anyString(), anyMap())).thenReturn(empty);
        CouchAnchorReceiptStore store = storeWith(client);

        org.junit.jupiter.api.Assertions.assertTrue(store.pending(DOMAIN, 10).isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(0, store.unreadableCount(),
                "an answered, empty view was reported as unreadable");
    }

    private static com.ibm.cloud.cloudant.v1.model.ViewResult[] viewsThatDidNotAnswer() {
        com.ibm.cloud.cloudant.v1.model.ViewResult nullRows =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(nullRows.getRows()).thenReturn(null);
        return new com.ibm.cloud.cloudant.v1.model.ViewResult[] { null, nullRows };
    }

    @Test
    @DisplayName("a row the view returned without a document is counted, not dropped")
    void aRowWithNoDocumentIsCounted() throws Exception {
        // The sibling ledger store counts exactly this shape; this one skipped it one call
        // earlier, so a row that came back without a document was invisible.
        //
        // The consumer is a GUARD, not a display: AnchorService refuses to re-anchor a rung
        // when anything is unaccounted for, because an unread receipt may be the settled one
        // and re-anchoring buys a second RFC 3161 token for a rung that already has one. A
        // guard that cannot see the gap does not fire.
        ViewResult rows = mock(ViewResult.class);
        ViewResultRow noDoc = mock(ViewResultRow.class);
        when(noDoc.getDoc()).thenReturn(null);
        when(rows.getRows()).thenReturn(List.of(noDoc));
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(anyString(), anyString(), anyMap())).thenReturn(rows);
        CouchAnchorReceiptStore store = storeWith(client);

        assertTrue(store.forCheckpoint(DOMAIN, 1L).isEmpty(),
                "fixture check: the row decoded after all, so nothing was dropped to count");
        assertEquals(1, store.unreadableCount(),
                "a row the view returned with no document vanished without a trace, so the "
                        + "re-anchor guard sees a complete answer and lets a second timestamp "
                        + "be bought for a rung that already has one");
    }

    @Test
    @DisplayName("'the view did not answer' is a separate fact from 'N rows would not decode'")
    void anUnansweredViewIsNotARowCount() throws Exception {
        // Folded into the count alone, a view that did not answer became "1 receipt row could
        // not be read" in every consumer's sentence — which asserts a row exists, and there may
        // be none. The custody store was split for this a round earlier; this sibling was not.
        // The count itself stays at 1 so every guard that only asks "is anything unaccounted
        // for" keeps refusing.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(anyString(), anyString(), anyMap())).thenReturn(null);
        CouchAnchorReceiptStore store = storeWith(client);

        assertTrue(store.forCheckpoint(DOMAIN, 1L).isEmpty());
        assertTrue(store.unreadableCount() > 0,
                "an unanswered view no longer counts as unaccounted-for, so the re-anchor "
                        + "guard lets a second RFC 3161 token be bought");
        assertTrue(store.lastQueryFailed(),
                "an unanswered view reads as 'a row would not decode', so the operator is told "
                        + "a receipt exists that nobody established");

        // The control: a row-level decode failure is NOT a query failure.
        ViewResult rows = mock(ViewResult.class);
        ViewResultRow noDoc = mock(ViewResultRow.class);
        when(noDoc.getDoc()).thenReturn(null);
        when(rows.getRows()).thenReturn(List.of(noDoc));
        when(client.queryView(anyString(), anyString(), anyMap())).thenReturn(rows);
        assertTrue(store.forCheckpoint(DOMAIN, 1L).isEmpty());
        assertFalse(store.lastQueryFailed(),
                "a decode failure was reported as 'could not be queried', so an operator is "
                        + "sent to the network for a data problem");
    }
}
