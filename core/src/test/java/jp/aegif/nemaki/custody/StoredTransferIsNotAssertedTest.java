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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stored row is read back through the state machine, not around it (P3-4).
 *
 * <h2>The back door persistence would have opened</h2>
 *
 * <p>Persisting a state machine means being able to set its state from outside. Do that without
 * checking and anything that can write to the database can hand itself {@code RECEIPT_VERIFIED}
 * — the state whose whole meaning is "we checked a receipt" — by editing one field. That is the
 * false diagnosis the machine exists to prevent, reached by a shorter route than the one that
 * was closed.
 *
 * <p>So {@link CustodyTransfer#restore} verifies the stored history walks, that every step is a
 * move the machine allows, and that it ends at the stored state. A forged row is refused at the
 * point it is read, which is the first moment anyone could act on it.
 */
class StoredTransferIsNotAssertedTest {

    private static final String REPO = "bedroom";
    private static final String DIGEST = "d".repeat(64);

    private static CustodyTransfer walked() {
        CustodyTransfer transfer = new CustodyTransfer("t-1", REPO, "doc-1", DIGEST, "roda",
                "2026-08-26T00:00:00Z");
        for (CustodyState next : List.of(CustodyState.SENT, CustodyState.RECEIVED,
                CustodyState.VALIDATED, CustodyState.INGEST_ACCEPTED, CustodyState.AIP_CREATED)) {
            transfer.advance(next, "2026-08-26T01:00:00Z", "step");
        }
        transfer.verifyReceipt(new CustodyReceipt("sub-1", "aip-1", "c".repeat(64), DIGEST,
                "PASSED", "roda-agent", "2026-08-26T02:00:00Z", null, false),
                "2026-08-26T02:00:00Z");
        return transfer;
    }

    @Test
    @DisplayName("an honest row round-trips")
    void anHonestRowIsRead() {
        CustodyTransfer restored = CouchCustodyTransferStore.decode(
                CouchCustodyTransferStore.document(walked()));

        assertNotNull(restored);
        assertEquals(CustodyState.RECEIPT_VERIFIED, restored.state());
        assertEquals("roda", restored.receivingSystem());
        assertEquals(DIGEST, restored.sipDigest());
        assertEquals("aip-1", restored.receipt().aipId());
    }

    @Test
    @DisplayName("a row edited to claim a state its history does not reach is refused")
    void aForgedStateIsRefused() {
        Map<String, Object> doc = CouchCustodyTransferStore.document(walked());
        doc.put("state", CustodyState.CUSTODY_TRANSFERRED.name());

        assertNull(CouchCustodyTransferStore.decode(doc),
                "editing one field handed out a transfer whose history does not support it");
    }

    @Test
    @DisplayName("a row whose history skips a step is refused")
    void aSkippedStepIsRefused() {
        // PACKAGE_CREATED -> AIP_CREATED. The machine refuses a skip as firmly as a reversal,
        // and a stored history has to be held to the same rule or the rule is only about the
        // live path.
        List<CustodyTransfer.Step> history = new ArrayList<>();
        history.add(new CustodyTransfer.Step(null, CustodyState.PACKAGE_CREATED, "t", "made"));
        history.add(new CustodyTransfer.Step(CustodyState.PACKAGE_CREATED,
                CustodyState.AIP_CREATED, "t", "they said so"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CustodyTransfer.restore("t-1", REPO, "doc-1", DIGEST, "roda",
                        CustodyState.AIP_CREATED, null, history));

        assertTrue(refused.getMessage().contains("does not allow"), refused.getMessage());
    }

    @Test
    @DisplayName("a row at RECEIPT_VERIFIED with no receipt is refused")
    void verifiedWithNothingCheckedIsRefused() {
        Map<String, Object> doc = CouchCustodyTransferStore.document(walked());
        doc.put("receipt", null);

        assertNull(CouchCustodyTransferStore.decode(doc),
                "a stored transfer said we had checked a receipt it does not have");
    }

    @Test
    @DisplayName("a row with no history at all is refused")
    void anAssertedStateIsRefused() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "custody_transfer");
        doc.put("repositoryId", REPO);
        doc.put("transferId", "t-1");
        doc.put("objectId", "doc-1");
        doc.put("sipDigest", DIGEST);
        doc.put("state", CustodyState.CUSTODY_TRANSFERRED.name());

        assertNull(CouchCustodyTransferStore.decode(doc),
                "a state with no history behind it was accepted, which is an assertion");
    }

    @Test
    @DisplayName("a row whose history is discontinuous is refused")
    void aHistoryThatDoesNotJoinUpIsRefused() {
        // Each step is individually legal and they do not connect: SENT -> RECEIVED followed by
        // VALIDATED -> INGEST_ACCEPTED. Checking only "is each move allowed" would pass this.
        List<CustodyTransfer.Step> history = new ArrayList<>();
        history.add(new CustodyTransfer.Step(null, CustodyState.PACKAGE_CREATED, "t", "made"));
        history.add(new CustodyTransfer.Step(CustodyState.PACKAGE_CREATED, CustodyState.SENT,
                "t", "sent"));
        history.add(new CustodyTransfer.Step(CustodyState.VALIDATED,
                CustodyState.INGEST_ACCEPTED, "t", "accepted"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CustodyTransfer.restore("t-1", REPO, "doc-1", DIGEST, "roda",
                        CustodyState.INGEST_ACCEPTED, null, history));

        assertTrue(refused.getMessage().contains("does not walk"), refused.getMessage());
    }
}
