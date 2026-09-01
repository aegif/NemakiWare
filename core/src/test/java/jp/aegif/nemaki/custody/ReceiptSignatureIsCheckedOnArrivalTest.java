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

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The signature is checked when the receipt ARRIVES, not read back out of a row (P3-4).
 *
 * <h2>Why the timing is the whole point</h2>
 *
 * <p>A stored {@code signatureVerified} is not believed on reload — anything with database
 * access could set it — so the finding has to be made at the moment the receipt is examined and
 * chained from there. A verifier with no production caller would leave the field permanently
 * false and the class permanently decorative, which is the defect this product keeps finding in
 * its own work.
 */
class ReceiptSignatureIsCheckedOnArrivalTest {

    private static final String REPO = "bedroom";
    private static final String DIGEST = "a".repeat(64);
    private static final String AGENT = "roda-agent";

    private static KeyPair theirs;
    private static KeyPair somebodyElses;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        theirs = kpg.generateKeyPair();
        somebodyElses = kpg.generateKeyPair();
    }

    /** The in-memory store, through the real encoding, as the service test uses. */
    private static final class Store implements CustodyTransferStore {
        final Map<String, Map<String, Object>> saved = new LinkedHashMap<>();

        @Override public boolean isActive() {
            return true;
        }

        @Override public boolean save(CustodyTransfer transfer) {
            saved.put(transfer.transferId(), CouchCustodyTransferStore.document(transfer));
            return true;
        }

        @Override public CustodyTransfer find(String repositoryId, String transferId) {
            return CouchCustodyTransferStore.decode(saved.get(transferId));
        }

        @Override public List<CustodyTransfer> findByObject(String repositoryId, String objectId,
                int limit) {
            return new ArrayList<>();
        }
    }

    private Store store;
    private CustodyTransferService service;
    private PropertyManager propertyManager;

    @BeforeEach
    void setUp() {
        store = new Store();
        service = new CustodyTransferService();
        service.setStore(store);
        service.setLedgerRecorder(mock(CustodyLedgerRecorder.class));
        propertyManager = mock(PropertyManager.class);
        when(propertyManager.readValue(anyString())).thenReturn(null);
        service.setPropertyManager(propertyManager);
    }

    private void configureKey(java.security.PublicKey key) {
        when(propertyManager.readValue(PropertyKey.CUSTODY_RECEIPT_KEY_PREFIX + AGENT))
                .thenReturn(Base64.getEncoder().encodeToString(key.getEncoded()));
        when(propertyManager.readValue(PropertyKey.CUSTODY_RECEIPT_SIGNATURE_ALGORITHM))
                .thenReturn("SHA256withRSA");
    }

    private static CustodyReceipt signedBy(KeyPair key) throws Exception {
        CustodyReceipt receipt = new CustodyReceipt("sub-1", "aip-1", "c".repeat(64), DIGEST,
                "PASSED", AGENT, "2026-08-26T02:00:00Z", null, false);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate());
        signer.update(ReceiptSignatureVerifier.canonicalForm(receipt));
        return new CustodyReceipt(receipt.submissionId(), receipt.aipId(), receipt.aipChecksum(),
                receipt.sipDigest(), receipt.verificationOutcome(), receipt.receivingAgent(),
                receipt.receivedAt(), Base64.getEncoder().encodeToString(signer.sign()), false);
    }

    private void atAipCreated() {
        assertTrue(service.open(REPO, "t-1", "doc-1", DIGEST, "roda").done());
        for (CustodyState next : List.of(CustodyState.SENT, CustodyState.RECEIVED,
                CustodyState.VALIDATED, CustodyState.INGEST_ACCEPTED, CustodyState.AIP_CREATED)) {
            assertTrue(service.advance(REPO, "t-1", next, "step").done());
        }
    }

    @Test
    @DisplayName("a genuine signature is VERIFIED at the moment the receipt is checked")
    void aGenuineSignatureIsVerifiedOnArrival() throws Exception {
        configureKey(theirs.getPublic());
        atAipCreated();

        CustodyTransferService.Outcome outcome =
                service.verifyReceipt(REPO, "t-1", signedBy(theirs));

        assertTrue(outcome.done(), String.valueOf(outcome.refusedReason()));
        assertTrue(outcome.transfer().receipt().signatureVerified(),
                "the signature was checked and the receipt still says it was not verified");
    }

    @Test
    @DisplayName("somebody else's signature is not verified — and does not block the receipt")
    void anotherPartysSignatureIsNotVerified() throws Exception {
        // The receipt is still about our package and still reports success, so the transfer
        // moves. What must NOT happen is the flag coming out true. Refusing the whole receipt
        // over a signature this product cannot attribute to anyone would be a different
        // decision, and one the submission agreement makes, not this code.
        configureKey(theirs.getPublic());
        atAipCreated();

        CustodyTransferService.Outcome outcome =
                service.verifyReceipt(REPO, "t-1", signedBy(somebodyElses));

        assertTrue(outcome.done(), String.valueOf(outcome.refusedReason()));
        assertFalse(outcome.transfer().receipt().signatureVerified(),
                "a signature made with another key was recorded as verified");
        // And the finding LEAVES this JVM. signatureVerified=false has three producers -- no
        // key, a check that ran and did not match, an unreadable signature -- and the receipt
        // holds one boolean. Its limits used to name the harmless one ("this product holds no
        // key material"), so a receipt demonstrably not from the holder of the configured key
        // was reported to the operator as an unconfigured deployment: the strongest of the
        // three read as the weakest. Checked.asMap() had no caller anywhere in main.
        assertNotNull(outcome.signatureCheck(),
                "the signature check's result was dropped, so nothing outside this JVM can tell "
                        + "a FAILED check from a missing key");
        assertEquals(Boolean.TRUE, outcome.signatureCheck().get("signatureCheckRan"));
        assertEquals(Boolean.FALSE, outcome.signatureCheck().get("signatureValid"));
        assertFalse(outcome.transfer().receipt().limits().contains("holds no key material"),
                "the receipt names 'no key material' as the reason after a check that RAN and "
                        + "FAILED: " + outcome.transfer().receipt().limits());
    }

    @Test
    @DisplayName("with no key configured, nothing is verified — and nothing is faulted")
    void noKeyMeansNotChecked() throws Exception {
        atAipCreated();

        CustodyTransferService.Outcome outcome =
                service.verifyReceipt(REPO, "t-1", signedBy(theirs));

        assertTrue(outcome.done(), String.valueOf(outcome.refusedReason()));
        assertFalse(outcome.transfer().receipt().signatureVerified(),
                "a signature nobody checked was recorded as verified");
    }

    @Test
    @DisplayName("an unreadable configured key is 'not checked', not a rejection")
    void anUnreadableKeyDoesNotFaultTheReceipt() throws Exception {
        when(propertyManager.readValue(PropertyKey.CUSTODY_RECEIPT_KEY_PREFIX + AGENT))
                .thenReturn("this is not a key");
        when(propertyManager.readValue(PropertyKey.CUSTODY_RECEIPT_SIGNATURE_ALGORITHM))
                .thenReturn("SHA256withRSA");
        atAipCreated();

        CustodyTransferService.Outcome outcome =
                service.verifyReceipt(REPO, "t-1", signedBy(theirs));

        assertTrue(outcome.done(),
                "a key this deployment could not read was treated as a bad receipt: "
                        + outcome.refusedReason());
        assertFalse(outcome.transfer().receipt().signatureVerified());
    }
}
