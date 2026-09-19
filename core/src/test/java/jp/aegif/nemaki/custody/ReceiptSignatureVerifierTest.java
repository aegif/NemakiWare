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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A verified signature has to be a result, not a field somebody set (P3-4).
 */
class ReceiptSignatureVerifierTest {

    private static final String DIGEST = "e".repeat(64);
    private static final String ALGORITHM = "SHA256withRSA";

    private static KeyPair theirs;
    private static KeyPair somebodyElses;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        theirs = kpg.generateKeyPair();
        somebodyElses = kpg.generateKeyPair();
    }

    private static CustodyReceipt unsigned() {
        return new CustodyReceipt("sub-1", "aip-1", "c".repeat(64), DIGEST, "PASSED",
                "roda-agent", "2026-08-26T02:00:00Z", null, false);
    }

    private static CustodyReceipt signedBy(KeyPair key) throws Exception {
        CustodyReceipt receipt = unsigned();
        Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(key.getPrivate());
        signer.update(ReceiptSignatureVerifier.canonicalForm(receipt));
        return new CustodyReceipt(receipt.submissionId(), receipt.aipId(), receipt.aipChecksum(),
                receipt.sipDigest(), receipt.verificationOutcome(), receipt.receivingAgent(),
                receipt.receivedAt(), Base64.getEncoder().encodeToString(signer.sign()), false);
    }

    @Test
    @DisplayName("a genuine signature verifies, and the flag becomes a result")
    void aGenuineSignatureVerifies() throws Exception {
        ReceiptSignatureVerifier.Checked checked = ReceiptSignatureVerifier.verify(
                signedBy(theirs), theirs.getPublic(), ALGORITHM);

        assertTrue(checked.ran());
        assertTrue(checked.valid(), checked.detail());
        assertTrue(checked.receipt().signatureVerified(),
                "the check passed and the receipt still says it was not verified");
    }

    @Test
    @DisplayName("somebody else's key does not verify — the control")
    void anotherPartysSignatureIsCaught() throws Exception {
        ReceiptSignatureVerifier.Checked checked = ReceiptSignatureVerifier.verify(
                signedBy(somebodyElses), theirs.getPublic(), ALGORITHM);

        assertTrue(checked.ran());
        assertFalse(checked.valid());
        assertFalse(checked.receipt().signatureVerified());
        assertTrue(checked.detail().contains("does NOT match"), checked.detail());
    }

    @Test
    @DisplayName("altering the receipt after signing breaks the signature")
    void anAlteredReceiptIsCaught() throws Exception {
        // The point of signing it at all. Without covering the fields, a receipt could be
        // re-pointed at a different package and keep its signature.
        CustodyReceipt signed = signedBy(theirs);
        CustodyReceipt altered = new CustodyReceipt(signed.submissionId(), signed.aipId(),
                signed.aipChecksum(), "f".repeat(64), signed.verificationOutcome(),
                signed.receivingAgent(), signed.receivedAt(), signed.signature(), false);

        ReceiptSignatureVerifier.Checked checked = ReceiptSignatureVerifier.verify(
                altered, theirs.getPublic(), ALGORITHM);

        assertFalse(checked.valid(),
                "the SIP digest was changed after signing and the signature still passed");
    }

    @Test
    @DisplayName("altering the AIP checksum after signing breaks the signature too")
    void anAlteredAipChecksumIsCaught() throws Exception {
        // `aipChecksum` stopped being a REQUIRED field (design §16): no receiver's checksum is
        // ever compared here, and RODA reports none at all. That made it easy to read the field
        // as inert and drop it from canonicalForm() as tidy-up — at which point the one check on
        // it that CAN fail disappears, and every existing test stays green because the tampering
        // test above alters sipDigest instead. When a receiver DOES report a checksum, altering
        // it afterwards has to break the signature.
        CustodyReceipt signed = signedBy(theirs);
        CustodyReceipt altered = new CustodyReceipt(signed.submissionId(), signed.aipId(),
                "d".repeat(64), signed.sipDigest(), signed.verificationOutcome(),
                signed.receivingAgent(), signed.receivedAt(), signed.signature(), false);

        ReceiptSignatureVerifier.Checked checked = ReceiptSignatureVerifier.verify(
                altered, theirs.getPublic(), ALGORITHM);

        assertFalse(checked.valid(),
                "the receiver's reported AIP checksum was changed after signing and the "
                        + "signature still passed, so nothing covers that field any more");
    }

    @Test
    @DisplayName("no key is 'not checked', not 'invalid'")
    void noKeyIsNotAFinding() throws Exception {
        ReceiptSignatureVerifier.Checked checked = ReceiptSignatureVerifier.verify(
                signedBy(theirs), null, ALGORITHM);

        assertFalse(checked.ran());
        assertFalse(checked.receipt().signatureVerified());
        assertTrue(checked.detail().contains("statement about this deployment"), checked.detail());
        assertTrue(checked.detail().contains("NOT a finding that the signature is bad"),
                checked.detail());
    }

    @Test
    @DisplayName("an unsigned receipt is usable and says it is unauthenticated")
    void anUnsignedReceiptIsNotAFailure() {
        ReceiptSignatureVerifier.Checked checked = ReceiptSignatureVerifier.verify(
                unsigned(), theirs.getPublic(), ALGORITHM);

        assertFalse(checked.ran());
        assertTrue(checked.detail().contains("unauthenticated statement"), checked.detail());
    }

    @Test
    @DisplayName("an unusable signature is a failure to check, not an accusation")
    void garbageIsNotAnAccusation() throws Exception {
        CustodyReceipt receipt = new CustodyReceipt("sub-1", "aip-1", "c".repeat(64), DIGEST,
                "PASSED", "roda-agent", "t", "not base64 at all !!!", false);

        ReceiptSignatureVerifier.Checked checked = ReceiptSignatureVerifier.verify(
                receipt, theirs.getPublic(), ALGORITHM);

        assertFalse(checked.ran());
        assertFalse(checked.valid());
        assertTrue(checked.detail().contains("not a finding that it is bad"), checked.detail());
    }

    @Test
    @DisplayName("a valid signature does not vouch for who holds the key")
    void aValidSignatureDoesNotAuthenticateTheParty() {
        assertTrue(ReceiptSignatureVerifier.LIMITS.contains("does NOT establish that that key "
                + "belongs to the receiving organisation"), ReceiptSignatureVerifier.LIMITS);
        assertTrue(ReceiptSignatureVerifier.LIMITS.contains("nor that the statements in the "
                + "receipt are true"), ReceiptSignatureVerifier.LIMITS);
    }

    @Test
    @DisplayName("the signed form excludes the signature and our own finding")
    void theCanonicalFormExcludesWhatItCannotCover() throws Exception {
        // A signature cannot cover itself, and whether WE checked it is our finding, not theirs
        // — putting either in would make the same receipt hash differently on the two sides.
        CustodyReceipt signed = signedBy(theirs);
        CustodyReceipt verifiedFlagSet = new CustodyReceipt(signed.submissionId(),
                signed.aipId(), signed.aipChecksum(), signed.sipDigest(),
                signed.verificationOutcome(), signed.receivingAgent(), signed.receivedAt(),
                signed.signature(), true);

        assertEquals(
                new String(ReceiptSignatureVerifier.canonicalForm(unsigned())),
                new String(ReceiptSignatureVerifier.canonicalForm(verifiedFlagSet)),
                "the signature or the verified flag is inside the signed bytes");
    }

    @Test
    @DisplayName("a mapped receipt is signed over the RECEIVER's word, not our translation")
    void theSignatureCoversWhatTheReceiverSaid() throws Exception {
        // Archivematica reports COMPLETE, which reportsSuccess() does not accept, so a connector
        // maps it to SUCCESS and puts the mapped word where the state machine reads it
        // (design §13.1). The far end signed COMPLETE -- it has never heard of our vocabulary.
        // If canonicalForm signed the mapped word, EVERY mapped receipt would fail verification.
        CustodyReceipt mapped = new CustodyReceipt("sub-1", "aip-1", "b".repeat(64),
                "a".repeat(64), "SUCCESS", "COMPLETE", "am-agent", "2026-08-27T00:00:00Z",
                null, false);

        String signed = new String(ReceiptSignatureVerifier.canonicalForm(mapped),
                java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(signed.contains("COMPLETE"),
                "the canonical form does not carry the receiver's own word, so a receipt the "
                        + "receiver really signed would fail verification here: " + signed);
        assertFalse(signed.contains("SUCCESS"),
                "the canonical form carries OUR mapped word. The far end never signed that: "
                        + signed);

        // And with no mapping, nothing changes: the receiver's word IS the judged word.
        CustodyReceipt plain = new CustodyReceipt("sub-1", "aip-1", "b".repeat(64),
                "a".repeat(64), "PASSED", "roda-agent", "2026-08-27T00:00:00Z", null, false);
        assertTrue(new String(ReceiptSignatureVerifier.canonicalForm(plain),
                        java.nio.charset.StandardCharsets.UTF_8).contains("PASSED"),
                "an unmapped receipt lost its outcome from the canonical form");
    }
}
