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
package jp.aegif.nemaki.verifier;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TSPValidationException;
import org.bouncycastle.tsp.TimeStampToken;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;

/**
 * Whether a timestamp token's CMS signature verifies against the certificate it carries.
 *
 * <h2>One classification, because one token must not get two answers</h2>
 *
 * <p>{@code ANCHORED_CHECKPOINT_V1} and {@code TRUSTED_RFC3161_V1} both verify this signature,
 * and they used to decide separately what a failure meant. P2 split "the signature is wrong"
 * from "this build cannot check it"; P3 kept a blanket {@code catch (Exception)} that called
 * everything a bad signature. One token whose algorithm this JVM has no provider for therefore
 * exited 3 at P2 and 2 at P3 — the same bytes reported as unchecked by one profile and as
 * tampered by the other (both reviews, fifth round).
 *
 * <p>So the decision lives here, once, and both call it. A profile may still choose the check's
 * NAME; it may not choose what an outcome means.
 *
 * <h2>Why the cause chain and not the exception type</h2>
 *
 * <p>BouncyCastle raises {@link TSPValidationException} when the token really does not verify,
 * and a plain {@code TSPException} wrapping a {@code CMSException} when the machinery could not
 * run — including when no provider implements the signature algorithm. Catching
 * {@code TSPException} as a finding therefore reports "the signature does not verify" for a
 * signature nobody computed. The exception TYPE does not separate them; the cause does.
 */
final class TokenSignature {

    private TokenSignature() {
    }

    /**
     * @param name the check's name in the report, which differs between profiles
     * @param what a phrase naming the token, used in the detail
     */
    static Outcome.Check verify(String name, String what, TimeStampToken token,
            X509CertificateHolder signer) {
        if (signer == null) {
            return Outcome.Check.absent(name,
                    what + " carries no signer certificate, so its signature cannot be verified "
                            + "from the package alone");
        }
        try {
            token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(signer));
            return Outcome.Check.passed(name);
        } catch (TSPValidationException invalid) {
            // Checked, and does not verify.
            return Outcome.Check.failed(name,
                    what + "'s signature does not verify against its own signer certificate: "
                            + invalid.getMessage());
        } catch (Exception cannotAsk) {
            return classify(name, what, token, signer, cannotAsk);
        }
    }

    /**
     * What a failure that is NOT a validation failure means — asked of the JCA, not guessed.
     *
     * <p>Four rounds of review found a defect in this classification, and every one of them was
     * in a rule that read an exception TYPE or a MESSAGE: {@code OperatorCreationException} is
     * not only "no provider"; {@code SignatureException} is both a mangled signature and an
     * unimplemented curve; a pre-flight run before the attempt pre-empted findings BouncyCastle
     * would have made, and excused its own failures as the build's. So nothing is inferred from
     * the exception at all. Three questions are put to the JCA directly, in order, and each
     * separates one thing:
     *
     * <ol>
     *   <li><b>Is the algorithm implemented here?</b> {@code Signature.getInstance}. No →
     *       {@code UNKNOWN_ALGORITHM}, which is what §12 says for this case.</li>
     *   <li><b>Can this key be used with it?</b> {@code initVerify}. No → the token names an
     *       algorithm its own certificate's key cannot be used with, which no build could
     *       compute — a contradiction the package states about itself, so a FINDING.</li>
     *   <li><b>Can this build compute a signature with this key?</b> {@code verify} over a
     *       well-formed dummy. Throws → the key's parameters are beyond this build (a brainpool
     *       curve, which initVerify accepts and verify refuses) → {@code SIGNATURE_NOT_COMPUTED}.
     *       Returns → the build can, so the original failure was about the token's own
     *       signature bytes → a FINDING.</li>
     * </ol>
     */
    private static Outcome.Check classify(String name, String what, TimeStampToken token,
            X509CertificateHolder signer, Exception cannotAsk) {
        String sigAlg;
        java.security.PublicKey key;
        try {
            org.bouncycastle.cms.SignerInformation info =
                    token.toCMSSignedData().getSignerInfos().getSigners().iterator().next();
            key = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                    .getCertificate(signer).getPublicKey();
            sigAlg = new org.bouncycastle.cms.DefaultCMSSignatureAlgorithmNameGenerator()
                    .getSignatureName(
                            new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                    new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                                            info.getDigestAlgOID())),
                            new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                    new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                                            info.getEncryptionAlgOID())));
        } catch (Exception couldNotInspect) {
            // This method's OWN work failed. That says nothing about the signature, so the
            // original failure stands as what it was: a verification that did not succeed.
            return failure(name, what, cannotAsk);
        }

        java.security.Signature probe;
        try {
            probe = java.security.Signature.getInstance(sigAlg);
        } catch (NoSuchAlgorithmException unimplemented) {
            return Outcome.Check.unavailable(name, "UNKNOWN_ALGORITHM",
                    "no provider in this build implements " + sigAlg + ", so the signature on "
                            + what + " has NOT been checked. That is not a finding that it is "
                            + "wrong");
        }
        try {
            probe.initVerify(key);
        } catch (Exception keyDoesNotGoWithIt) {
            // No build could compute this: the token names an algorithm that cannot be used
            // with the key its own certificate carries.
            return Outcome.Check.failed(name,
                    what + " names " + sigAlg + " and carries a "
                            + key.getAlgorithm() + " key, which cannot be used with it ("
                            + said(keyDoesNotGoWithIt) + ")");
        }
        byte[] dummy = wellFormedDummySignature(key);
        if (dummy != null) {
            try {
                probe.update(new byte[] { 0 });
                probe.verify(dummy);
            } catch (Exception beyondThisBuild) {
                return Outcome.Check.unavailable(name, "SIGNATURE_NOT_COMPUTED",
                        "this build cannot compute a " + sigAlg + " signature with the key "
                                + what + "'s certificate carries (" + said(beyondThisBuild)
                                + "), so the signature has NOT been compared");
            }
        }
        return failure(name, what, cannotAsk);
    }

    private static Outcome.Check failure(String name, String what, Exception cannotAsk) {
        return Outcome.Check.failed(name,
                what + "'s signature does not verify against its own signer certificate: "
                        + said(cannotAsk));
    }

    /**
     * What an exception said, or what it IS when it said nothing.
     *
     * <p>{@code String.valueOf(e.getMessage())} prints the four letters {@code null} for an
     * exception with no message, so a refusal read "cannot be used with it (null)" — the branch
     * STATING a thing it has not stated (subagent, ninth review, P3).
     */
    private static String said(Exception thrown) {
        return thrown.getMessage() == null
                ? thrown.getClass().getSimpleName() + " with no message"
                : thrown.getMessage();
    }

    /** A signature of the right SHAPE for {@code key}'s family, or null when unknown. */
    private static byte[] wellFormedDummySignature(java.security.PublicKey key) {
        String algorithm = key.getAlgorithm() == null ? ""
                : key.getAlgorithm().toUpperCase(java.util.Locale.ROOT);
        if (algorithm.contains("RSA") && key instanceof java.security.interfaces.RSAPublicKey rsa) {
            return new byte[(rsa.getModulus().bitLength() + 7) / 8];
        }
        if (algorithm.contains("EC") || algorithm.contains("DSA")) {
            // SEQUENCE { INTEGER 1, INTEGER 1 } — a syntactically valid (r, s).
            return new byte[] { 0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01 };
        }
        return null;
    }
}
