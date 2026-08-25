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

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.evidence.CouchEvidenceLedgerStore;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
                Instant.parse("2026-08-24T00:00:00Z"), new byte[] { 1, 2, 3 }, "tokendigest",
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
        when(client.update(any()))
                .thenThrow(jp.aegif.nemaki.dao.impl.couch.connector.CouchConflicts.conflict());

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
}
