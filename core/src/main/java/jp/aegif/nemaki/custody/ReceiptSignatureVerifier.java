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

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks a receipt's signature against a key the operator supplied (P3-4).
 *
 * <h2>What was missing, and what still is</h2>
 *
 * <p>{@link CustodyReceipt#signatureVerified} was a caller-supplied boolean: anything that
 * could construct a receipt could set it. This turns it into a result. Given the receiving
 * agent's public key, it verifies the signature over the receipt's canonical form and returns
 * a receipt whose flag is what the check found.
 *
 * <p><b>Where the key comes from is still the operator's problem</b>, and that is not a gap this
 * class can close: obtaining and trusting the far end's key material is what a submission
 * agreement is for. What has changed is that a verified flag can no longer be asserted — it can
 * only be produced by a check that ran.
 *
 * <h2>The canonical form</h2>
 *
 * <p>The signed bytes are the receipt's identifying fields joined with {@code \n}, in a fixed
 * order, excluding the signature itself. Fixed rather than "whatever the far end sent", because
 * a signature over a serialisation this product does not control cannot be reproduced: the far
 * end must sign the same string this builds, and a submission agreement is where that is
 * agreed. Until it is, this verifies nothing anyone has agreed to — which is why an unsigned
 * receipt stays perfectly usable and simply says it is unsigned.
 */
public final class ReceiptSignatureVerifier {

    private ReceiptSignatureVerifier() {
    }

    /** What the check found, and what it does not cover. */
    public record Checked(CustodyReceipt receipt, boolean ran, boolean valid, String detail) {

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("signatureCheckRan", ran);
            m.put("signatureValid", valid);
            m.put("detail", detail);
            m.put("limits", LIMITS);
            return m;
        }
    }

    /** What a valid signature does and does not establish. */
    public static final String LIMITS =
            "A valid signature establishes that whoever holds the key this was checked against "
                    + "produced this receipt. It does NOT establish that that key belongs to the "
                    + "receiving organisation — this product is given a key, it does not "
                    + "authenticate one — nor that the statements in the receipt are true. Who "
                    + "the key belongs to is settled by a submission agreement, outside this "
                    + "software.";

    /**
     * The bytes a signature is over.
     *
     * <p>Excludes {@code signature} and {@code signatureVerified}: a signature cannot cover
     * itself, and whether we checked it is our finding, not theirs.
     */
    public static byte[] canonicalForm(CustodyReceipt receipt) {
        String joined = String.join("\n",
                nullToEmpty(receipt.submissionId()),
                nullToEmpty(receipt.aipId()),
                nullToEmpty(receipt.aipChecksum()),
                nullToEmpty(receipt.sipDigest()),
                nullToEmpty(receipt.verificationOutcome()),
                nullToEmpty(receipt.receivingAgent()),
                nullToEmpty(receipt.receivedAt()));
        return joined.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Verifies, and returns a receipt whose {@code signatureVerified} is what was found.
     *
     * @param key the receiving agent's public key, from wherever the submission agreement says.
     *        Null means no check was possible, which is reported as such and NOT as a failure:
     *        "we have no key" is a statement about us.
     * @param algorithm e.g. {@code SHA256withRSA}; agreed, not guessed
     */
    public static Checked verify(CustodyReceipt receipt, PublicKey key, String algorithm) {
        if (receipt == null) {
            throw new IllegalArgumentException("there is no receipt to check");
        }
        if (receipt.signature() == null || receipt.signature().isBlank()) {
            return new Checked(unverified(receipt), false, false,
                    "this receipt carries no signature, so there was nothing to check. It is "
                            + "an unauthenticated statement and is stored as one.");
        }
        if (key == null) {
            return new Checked(unverified(receipt), false, false,
                    "no public key was supplied for " + receipt.receivingAgent() + ", so the "
                            + "signature was not checked. That is a statement about this "
                            + "deployment, NOT a finding that the signature is bad.");
        }
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(canonicalForm(receipt));
            boolean valid = verifier.verify(Base64.getDecoder().decode(receipt.signature()));
            return new Checked(withVerified(receipt, valid), true, valid,
                    valid ? "the signature is the one the supplied key produces over this "
                                    + "receipt's canonical form"
                          : "the signature does NOT match the supplied key over this receipt's "
                                    + "canonical form, so this receipt is not from the holder of "
                                    + "that key — or it has been altered since it was signed");
        } catch (Exception e) {
            // NOT reported as invalid. An algorithm this JVM does not have, or a signature that
            // is not base64, is a failure to check — and calling it "invalid" would accuse the
            // far end of something this deployment could not establish.
            return new Checked(unverified(receipt), false, false,
                    "the signature could not be checked (" + e.getMessage() + "), which is not "
                            + "a finding that it is bad");
        }
    }

    private static CustodyReceipt unverified(CustodyReceipt receipt) {
        return withVerified(receipt, false);
    }

    private static CustodyReceipt withVerified(CustodyReceipt receipt, boolean verified) {
        return new CustodyReceipt(receipt.submissionId(), receipt.aipId(), receipt.aipChecksum(),
                receipt.sipDigest(), receipt.verificationOutcome(), receipt.receivingAgent(),
                receipt.receivedAt(), receipt.signature(), verified);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
