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
        // ASKED FIRST, not inferred from an exception four wrappers deep: can this build set
        // up the verification at all? Everything after this is then a fact about the TOKEN.
        //
        // Reading it the other way round was wrong in both directions. An InvalidKeyException
        // was excused as "no provider" even when the token named a signature algorithm its own
        // certificate's key cannot use — a self-contradiction the package states. And a
        // SignatureException from a mangled ECDSA (r,s) encoding was excused too, so an edited
        // signature answered UNAVAILABLE instead of FAILED (Codex, eighth review, P1 and P2).
        String setup = cannotSetUp(token, signer);
        if (setup != null) {
            return Outcome.Check.unavailable(name, "SIGNATURE_NOT_COMPUTED",
                    "the signature on " + what + " could not be set up by this build (" + setup
                            + "). It has NOT been compared — that is neither a finding that it "
                            + "is wrong nor a statement that it is right");
        }
        try {
            token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(signer));
            return Outcome.Check.passed(name);
        } catch (TSPValidationException invalid) {
            // The token was checked and does not verify. This is the only finding this method
            // is allowed to report.
            return Outcome.Check.failed(name,
                    what + "'s signature does not verify against its own signer certificate: "
                            + invalid.getMessage());
        } catch (Exception cannotAsk) {
            if (keyNotUsable(cannotAsk)) {
                // The signature was NOT computed: no provider would initialise with the key the
                // token's own certificate carries. Two reviews pushed this in opposite
                // directions — one called UNAVAILABLE "a mismatch excused", the other called
                // FAILED "a comparison nobody made" — and both were right about the OTHER
                // reading. It gets its own reason code: this says what happened without
                // claiming an algorithm is unknown and without claiming a signature was
                // compared. The verdict is INDETERMINATE either way, so a substituted
                // certificate still cannot reach VERIFIED (Codex, sixth and seventh reviews).
                return Outcome.Check.unavailable(name, "SIGNATURE_NOT_COMPUTED",
                        "the signature on " + what + " could not be computed against the key its "
                                + "own certificate carries (" + cannotAsk.getMessage() + "). It "
                                + "has NOT been compared — that is neither a finding that it is "
                                + "wrong nor a statement that it is right");
            }
            if (uncheckable(cannotAsk)) {
                return Outcome.Check.unavailable(name, "UNKNOWN_ALGORITHM",
                        "the signature on " + what + " could not be computed by this build ("
                                + cannotAsk.getMessage() + "), so it has NOT been checked. That "
                                + "is not a finding that it is wrong");
            }
            return Outcome.Check.failed(name,
                    what + "'s signature does not verify against its own signer certificate: "
                            + cannotAsk.getMessage());
        }
    }

    /**
     * Whether a failure is about this build rather than about the token.
     *
     * <p>Walks the cause chain: the algorithm failure arrives several wrappers down
     * ({@code TSPException} → {@code CMSException} → {@code OperatorCreationException} →
     * {@code NoSuchAlgorithmException}), and looking only at the exception thrown reports every
     * one of them as a bad signature.
     */
    /**
     * Whether this build can even begin the verification — asked directly.
     *
     * <p>Returns why not, or null when it can. Two things are checked, and both are about THIS
     * BUILD rather than about the token:
     *
     * <ul>
     *   <li>the JCA knows the signature algorithm the SignerInfo names, and</li>
     *   <li>it will initialise with the key the token's own certificate carries.</li>
     * </ul>
     *
     * <p>A token that names an algorithm incompatible with its own key fails the second — and
     * that is a fact about the token, not a limit of this build. So the key's ALGORITHM FAMILY
     * is compared first: a mismatch there is left to the verification below, which reports it
     * as a finding.
     */
    private static String cannotSetUp(TimeStampToken token, X509CertificateHolder signer) {
        try {
            org.bouncycastle.cms.SignerInformation info =
                    token.toCMSSignedData().getSignerInfos().getSigners().iterator().next();
            java.security.PublicKey key = new org.bouncycastle.cert.jcajce
                    .JcaX509CertificateConverter().getCertificate(signer).getPublicKey();
            String encryption = info.getEncryptionAlgOID();
            if (!familyMatches(encryption, key.getAlgorithm())) {
                // The token names a signature algorithm its own certificate's key cannot be
                // used with. Read, and contradictory — the verification below says so.
                return null;
            }
            java.security.Signature probe;
            // The name CMS ITSELF derives from the SignerInfo. Reconstructing it by hand
            // produced "SHA256with<the full signature OID>" and refused every ordinary token
            // (measured).
            String sigAlg = new org.bouncycastle.cms.DefaultCMSSignatureAlgorithmNameGenerator()
                    .getSignatureName(
                            new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                    new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                                            info.getDigestAlgOID())),
                            new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                    new org.bouncycastle.asn1.ASN1ObjectIdentifier(encryption)));
            try {
                probe = java.security.Signature.getInstance(sigAlg);
            } catch (java.security.NoSuchAlgorithmException unknown) {
                return "no provider implements " + sigAlg;
            }
            probe.initVerify(key);
            return null;
        } catch (java.security.InvalidKeyException | RuntimeException cannot) {
            // initVerify refused a key whose FAMILY matched, so no installed provider accepts
            // this key implementation or its parameters — a brainpool curve, for instance.
            return String.valueOf(cannot.getMessage());
        } catch (Exception other) {
            return String.valueOf(other.getMessage());
        }
    }

    /** Whether a signature algorithm OID belongs to the same key family as {@code keyAlgorithm}. */
    private static boolean familyMatches(String encryptionOid, String keyAlgorithm) {
        String family = new org.bouncycastle.operator.DefaultAlgorithmNameFinder()
                .getAlgorithmName(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                        new org.bouncycastle.asn1.ASN1ObjectIdentifier(encryptionOid), null))
                .toUpperCase(java.util.Locale.ROOT);
        String key = keyAlgorithm == null ? "" : keyAlgorithm.toUpperCase(java.util.Locale.ROOT);
        if (family.contains("RSA")) {
            return key.contains("RSA");
        }
        if (family.contains("ECDSA") || family.equals("EC")) {
            return key.contains("EC");
        }
        if (family.contains("DSA")) {
            return key.contains("DSA");
        }
        // Unknown family: let the verification decide rather than guessing here.
        return true;
    }

    /**
     * Whether no signature was computed because the KEY could not be used.
     *
     * <p>Distinct from {@link #uncheckable}: there the algorithm is unknown to this build; here
     * the algorithm is known and the key its own certificate carries would not initialise. Both
     * mean nothing was compared, and neither is a finding — but they are different facts and a
     * machine branching on {@code reasonCode} has to be able to tell them apart.
     */
    static boolean keyNotUsable(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.security.InvalidKeyException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    static boolean uncheckable(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoSuchAlgorithmException
                    || cause instanceof NoSuchProviderException
                    || cause instanceof org.bouncycastle.operator.OperatorCreationException) {
                return true;
            }
            // A SignatureException is NO LONGER excused here. Whether this build can compute
            // the signature at all is asked UP FRONT by cannotSetUp, so anything that fails
            // after that is a fact about the token — including a mangled ECDSA (r,s) encoding,
            // which reaches verify() and would otherwise have answered "not checked" for a
            // signature that was read and is malformed (Codex, eighth review, P2).
            if (cause instanceof java.security.cert.CertificateException) {
                // The signer certificate could not be converted, so no signature was computed.
                // This landed in "does not verify against its own signer certificate", which
                // states a comparison nobody made (subagent, sixth review, P3).
                return true;
            }
            if (cause instanceof GeneralSecurityException
                    && cause.getMessage() != null
                    && cause.getMessage().contains("no such algorithm")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
