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

import java.security.NoSuchAlgorithmException;

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
 * <h2>Why nothing is read from the exception</h2>
 *
 * <p>BouncyCastle raises {@link TSPValidationException} when the token really does not verify.
 * Everything else arrives as a {@code TSPException} wrapping something, and four rounds of
 * review found a defect in every rule that tried to tell those apart by TYPE or by MESSAGE —
 * and then in the rule that read the CAUSE CHAIN instead. So neither is read now: the JCA is
 * asked directly, and {@link #classify} states the three questions.
 *
 * <p>This heading used to say "Why the cause chain and not the exception type", which outlived
 * the cause chain by one batch (subagent, tenth review, P3).
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
     *   <li><b>Can this key be used with it?</b> {@code initVerify}. No → ask once more whether
     *       an ORDINARY key of the same kind initialises with the same algorithm. It does not →
     *       the algorithm and the key kind do not go together at all, which no build could
     *       compute: a contradiction the package states about itself, so a FINDING. It does →
     *       the refusal is about THIS key (explicit domain parameters, a provider-backed key),
     *       so {@code SIGNATURE_NOT_COMPUTED}.
     *       <p>No fixture in this build reaches that second arm: the one real key this JVM
     *       cannot handle — a brainpool curve — is ACCEPTED by {@code initVerify} and answered
     *       by the third question below (measured, ninth review). The arm is here because a
     *       PKCS#11 or HSM-backed key reaches it in deployment, and calling that a finding
     *       would report tampering nobody found.</p></li>
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
            // This method's OWN work failed: the SignerInfo could not be read, or the signer
            // certificate could not be converted, so the algorithm was never established and
            // NOTHING WAS COMPARED.
            //
            // Not FAILED. Letting the original exception "stand as what it was" made this
            // answer "the token's signature does not verify against its own signer
            // certificate" — a comparison nobody made, for a package whose certificate this
            // build simply could not parse (subagent, tenth review, P1, measured against the
            // previous commit). The deleted cause-chain walk had an arm for exactly this and
            // the rewrite dropped it.
            //
            // Not SIGNATURE_NOT_COMPUTED either: that reason names a specific finding — this
            // build cannot compute a signature with this key — and nothing here established
            // it (QG3). UNKNOWN_ALGORITHM is §12's code for "this check was not performed",
            // and the detail says which of its situations this is.
            return Outcome.Check.unavailable(name, "UNKNOWN_ALGORITHM",
                    "this verifier could not read " + what + "'s signer certificate or the "
                            + "algorithm its SignerInfo names (" + said(couldNotInspect)
                            + "), so the signature has NOT been compared. That is not a finding "
                            + "that it is wrong");
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
            // WHICH kind of refusal? Reporting every one of them as a contradiction the package
            // states about itself made a finding out of a key this build's providers merely
            // could not handle — an explicit-parameters EC key, an HSM-backed one — where
            // nothing was compared (Codex, tenth review, P1).
            //
            // Asked of the JCA, not of the algorithm's NAME: does an ORDINARY key of the same
            // kind initialise with this same signature algorithm? If it does, the refusal is
            // about THIS key. If even a fresh one is refused, the algorithm and the key kind do
            // not go together at all, and no build could compute it.
            Boolean ordinaryWorks = anOrdinaryKeyOfTheSameKindInitialises(sigAlg,
                    key.getAlgorithm());
            if (Boolean.FALSE.equals(ordinaryWorks)) {
                return Outcome.Check.failed(name,
                        what + " names " + sigAlg + " and carries a "
                                + key.getAlgorithm() + " key, which cannot be used with it ("
                                + said(keyDoesNotGoWithIt) + ")");
            }
            // The detail says WHICH of the two it is. Writing "an ordinary key of that kind does
            // initialise" unconditionally stated the answer to a question that had not been put
            // when the probe could not make one (Codex, eleventh review, P1).
            return Outcome.Check.unavailable(name, "SIGNATURE_NOT_COMPUTED",
                    "no provider in this build would initialise " + what + "'s "
                            + key.getAlgorithm() + " key for " + sigAlg + " ("
                            + said(keyDoesNotGoWithIt) + "), and "
                            + (Boolean.TRUE.equals(ordinaryWorks)
                                    ? "an ordinary key of that kind DOES initialise — so this is "
                                            + "about THIS key"
                                    : "this build could not put the same question to an ordinary "
                                            + "key of that kind, so which of the two it is has "
                                            + "NOT been established")
                            + ". The signature has NOT been compared");
        }
        byte[] dummy = wellFormedDummySignature(key);
        // A key family this method has no dummy shape for (Ed25519, SM2, ...) SKIPS the third
        // question, and the answer below is then a finding. That is deliberate — the first two
        // questions passed, so the algorithm is implemented here and the key initialises with
        // it — but it was not stated anywhere (subagent, tenth review, P3).
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

    /**
     * Does a fresh, ordinary key of {@code keyAlgorithm} initialise with {@code sigAlg}?
     *
     * @return TRUE when it does, FALSE when even an ordinary key is refused (so the algorithm
     *         and the key kind do not go together), and null when this build cannot make such a
     *         key at all — in which case NOTHING has been established, and the caller must not
     *         call the package contradictory on the strength of it
     */
    private static Boolean anOrdinaryKeyOfTheSameKindInitialises(String sigAlg,
            String keyAlgorithm) {
        java.security.PublicKey ordinary;
        try {
            // Cached: generating an RSA pair costs ~800 ms on this machine, and a package with
            // several rungs asks the same question about the same kind of key each time.
            ordinary = ORDINARY_KEYS.computeIfAbsent(keyAlgorithm, kind -> {
                try {
                    return java.security.KeyPairGenerator.getInstance(kind)
                            .generateKeyPair().getPublic();
                } catch (Exception cannotMakeOne) {
                    return null;
                }
            });
        } catch (Exception cannotAskAtAll) {
            return null;
        }
        if (ordinary == null) {
            return null;
        }
        try {
            java.security.Signature.getInstance(sigAlg).initVerify(ordinary);
            return Boolean.TRUE;
        } catch (java.security.InvalidKeyException refusedThatToo) {
            // A statement ABOUT THE PAIR: this algorithm cannot be used with a key of this kind.
            return Boolean.FALSE;
        } catch (Exception couldNotAsk) {
            // A ProviderException, a transient failure — "could not ask", which is not evidence
            // that the package contradicts itself (Codex, eleventh review, P1).
            return null;
        }
    }

    /** One ordinary public key per key algorithm, so the question is asked at most once. */
    private static final java.util.Map<String, java.security.PublicKey> ORDINARY_KEYS =
            new java.util.concurrent.ConcurrentHashMap<>();

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
