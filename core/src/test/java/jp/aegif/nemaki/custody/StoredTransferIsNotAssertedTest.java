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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("a row whose mapped word nobody could derive is refused on read-back")
    void aForgedMappingInARowIsRefused() {
        // The database is the OTHER place a forged pair arrives. reportsSuccess() reads the
        // mapped word, so a stored SUCCESS/FAILED pair passes every other check here: it names
        // this transfer's package, it carries every identifying field, and the history really
        // does reach RECEIPT_VERIFIED. Only re-deriving the mapping catches it -- which is why
        // the rule lives on the receipt rather than in the service that happens to be today's
        // only caller.
        Map<String, Object> doc = CouchCustodyTransferStore.document(walked());
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) doc.get("receipt");
        receipt.put("verificationOutcome", "SUCCESS");
        receipt.put("reportedOutcome", "FAILED");

        assertNull(CouchCustodyTransferStore.decode(doc),
                "a row claiming the receiver said FAILED and that this means SUCCESS was read "
                        + "back as a verified receipt. A signature would only ever cover FAILED");
    }

    @Test
    @DisplayName("a row with a genuine mapping still round-trips")
    void aGenuineMappingInARowIsRead() {
        // The check above must not refuse every mapped receipt -- Archivematica's
        // COMPLETE -> SUCCESS is the whole reason the second slot exists.
        Map<String, Object> doc = CouchCustodyTransferStore.document(walked());
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) doc.get("receipt");
        receipt.put("verificationOutcome", "SUCCESS");
        receipt.put("reportedOutcome", "COMPLETE");

        CustodyTransfer restored = CouchCustodyTransferStore.decode(doc);
        assertNotNull(restored, "a genuine Archivematica mapping was refused on read-back");
        assertEquals("COMPLETE", restored.receipt().reportedOutcome());
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

    /** Walked all the way to CUSTODY_TRANSFERRED, which is where the hole was. */
    private static CustodyTransfer handedOver() {
        CustodyTransfer transfer = walked();
        // The real door: advance() refuses this state, so a fixture that used it was
        // building a transfer the product cannot build.
        transfer.passCustody("2026-08-26T03:00:00Z", "recorded");
        return transfer;
    }

    @Test
    @DisplayName("custody passed with NO receipt stored is refused")
    void handedOverWithNothingCheckedIsRefused() {
        // The hole self-review found. The check was `state == RECEIPT_VERIFIED`, so a row
        // saying CUSTODY_TRANSFERRED with a null receipt sailed through: custody passed, with
        // nothing recording what was checked. The state it stopped at is the last place to
        // look for a missing receipt, not the only one.
        Map<String, Object> doc = CouchCustodyTransferStore.document(handedOver());
        doc.put("receipt", null);

        assertNull(CouchCustodyTransferStore.decode(doc),
                "a transfer that had passed custody was read back with no receipt behind it");
    }

    @Test
    @DisplayName("a stored receipt about a DIFFERENT package is refused")
    void aReceiptAboutAnotherPackageIsRefused() {
        // verifyReceipt refuses this on the live path. A stored row has to meet the same rule,
        // or the rule only ever applied where nobody is attacking.
        CustodyTransfer transfer = handedOver();
        Map<String, Object> doc = CouchCustodyTransferStore.document(transfer);
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) doc.get("receipt");
        receipt.put("sipDigest", "f".repeat(64));

        assertNull(CouchCustodyTransferStore.decode(doc),
                "a receipt about another package was accepted as this transfer's");
    }

    @Test
    @DisplayName("a stored receipt reporting REJECTED is refused")
    void aNegativeStoredReceiptIsRefused() {
        Map<String, Object> doc = CouchCustodyTransferStore.document(handedOver());
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) doc.get("receipt");
        receipt.put("verificationOutcome", "REJECTED");

        assertNull(CouchCustodyTransferStore.decode(doc),
                "a transfer said custody passed on a receipt saying the far end refused it");
    }

    @Test
    @DisplayName("a stored receipt missing an identifying field is refused")
    void anAnonymousStoredReceiptIsRefused() {
        Map<String, Object> doc = CouchCustodyTransferStore.document(handedOver());
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) doc.get("receipt");
        receipt.put("receivingAgent", null);

        assertNull(CouchCustodyTransferStore.decode(doc),
                "custody passed to nobody in particular, and the row was read back as valid");
    }

    @Test
    @DisplayName("an honest handover still round-trips — the control for all four above")
    void anHonestHandoverIsStillRead() {
        // Without this, tightening restore until it refuses everything would look like success.
        CustodyTransfer restored = CouchCustodyTransferStore.decode(
                CouchCustodyTransferStore.document(handedOver()));

        assertNotNull(restored, "a genuine completed handover can no longer be read back");
        assertEquals(CustodyState.CUSTODY_TRANSFERRED, restored.state());
        assertTrue(restored.state().custodyHasPassed());
    }

    @Test
    @DisplayName("a stored signatureVerified=true is never believed")
    void aStoredVerifiedFlagIsNotAFinding() {
        // A verified signature is a FINDING. Read back out of a row anyone with database access
        // can edit, it is an assertion wearing a finding's name — and re-verifying needs the
        // receiving agent's key, which this product is given rather than holding.
        Map<String, Object> doc = CouchCustodyTransferStore.document(walked());
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) doc.get("receipt");
        receipt.put("signature", "c2ln");
        receipt.put("signatureVerified", true);

        CustodyTransfer restored = CouchCustodyTransferStore.decode(doc);

        assertNotNull(restored);
        assertFalse(restored.receipt().signatureVerified(),
                "a row asserted that its own signature had been verified, and was believed");
        assertEquals("c2ln", restored.receipt().signature(),
                "the signature was dropped, so it cannot be checked later either");
    }

    @Test
    @DisplayName("a step with no time is refused")
    void anUntimedStepIsRefused() {
        List<CustodyTransfer.Step> history = new ArrayList<>();
        history.add(new CustodyTransfer.Step(null, CustodyState.PACKAGE_CREATED, "t", "made"));
        history.add(new CustodyTransfer.Step(CustodyState.PACKAGE_CREATED, CustodyState.SENT,
                null, "sent"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CustodyTransfer.restore("t-1", REPO, "doc-1", DIGEST, "roda",
                        CustodyState.SENT, null, history));

        assertTrue(refused.getMessage().contains("no time"), refused.getMessage());
    }

    @Test
    @DisplayName("advance() cannot pass custody — the DOMAIN door, not just the service's")
    void theDomainObjectRefusesCustody() {
        // The design document said "advance explicitly rejects CUSTODY_TRANSFERRED" while only
        // the service wrapper did, so anything holding the domain object could pass custody
        // without the ledger being asked.
        CustodyTransfer transfer = walked();

        CustodyTransfer.Moved moved = transfer.advance(CustodyState.CUSTODY_TRANSFERRED, "t",
                "we are done");

        assertFalse(moved.accepted(), "custody passed straight through the ordinary door");
        assertEquals(CustodyState.RECEIPT_VERIFIED, transfer.state());
        assertTrue(moved.refusedReason().contains("recorded first"), moved.refusedReason());
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

    @Test
    @DisplayName("a forged pair is refused even when the history never reached RECEIPT_VERIFIED")
    void aForgedMappingIsRefusedEvenWhereItUnlocksNothing() {
        // The mapping check used to sit inside `if (everVerified)`. A row whose history stops at
        // AIP_CREATED but which carries a receipt therefore came back unchecked -- and the
        // describe endpoint renders it. It unlocks nothing, so it is not a route to custody; it
        // is a forged pair displayed to an operator as a record, which is the false diagnosis
        // this class exists to refuse. A direct database edit is exactly restore()'s threat
        // model, so "only reachable by editing the row" is not a reason to let it through.
        CustodyReceipt forged = new CustodyReceipt("sub-1", "aip-1", "b".repeat(64),
                "a".repeat(64), "SUCCESS", "FAILED", "am-agent", "2026-08-27T00:00:00Z",
                null, false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CustodyTransfer.restore("t-1", "bedroom", "doc-1", "a".repeat(64), "RODA",
                        CustodyState.AIP_CREATED, forged,
                        List.of(new CustodyTransfer.Step(null, CustodyState.PACKAGE_CREATED,
                                        "2026-08-26T00:00:00Z", "built"),
                                new CustodyTransfer.Step(CustodyState.PACKAGE_CREATED,
                                        CustodyState.SENT, "2026-08-26T00:01:00Z", "sent"),
                                new CustodyTransfer.Step(CustodyState.SENT,
                                        CustodyState.RECEIVED, "2026-08-26T00:02:00Z", "there"),
                                new CustodyTransfer.Step(CustodyState.RECEIVED,
                                        CustodyState.VALIDATED, "2026-08-26T00:03:00Z", "ok"),
                                new CustodyTransfer.Step(CustodyState.VALIDATED,
                                        CustodyState.INGEST_ACCEPTED, "2026-08-26T00:04:00Z", "in"),
                                new CustodyTransfer.Step(CustodyState.INGEST_ACCEPTED,
                                        CustodyState.AIP_CREATED, "2026-08-26T00:05:00Z", "aip"))),
                "a stored receipt whose outcome pair no receiver produces was read back without "
                        + "being questioned, because its history stopped one state short");
        assertTrue(e.getMessage().contains("cannot be re-derived"), e.getMessage());
    }
}
