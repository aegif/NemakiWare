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
import org.bouncycastle.tsp.TimeStampToken;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code TRUSTED_RFC3161_V1} — the checks of {@code evidence-profile-v1.md} §12.
 *
 * <h2>Where the trust comes from</h2>
 *
 * <p>From {@link TrustProfile}, which the person running the verifier supplies. <b>Never from
 * the package.</b> A package naming its own trust anchors would let a package whose signature
 * had been replaced name the replacement's issuer and verify — and every file in it would be
 * well formed.
 *
 * <h2>What this profile still does not establish</h2>
 *
 * <p>That the timestamp authority is an accredited one. Accreditation is a property of
 * contracts and registries, not of a certificate, and a string inside a certificate saying so
 * is the issuer's claim about itself. §12 forbids reading it as a fact and nothing here does.
 */
public final class TrustedRfc3161 {

    /** The checks this profile will not pass without — §12. */
    public static final List<String> REQUIRED = List.of(
            "token parse", "token imprint", "token signature", "token eku",
            "token pkix", "token policy", "token revocation");

    /** id-kp-timeStamping. */
    private static final String EKU_TIMESTAMPING = "1.3.6.1.5.5.7.3.8";

    private TrustedRfc3161() {
    }

    public static List<Outcome.Check> check(Map<String, byte[]> entries, TrustProfile trust) {
        byte[] der = fileIn(entries, "anchors/rfc3161.der");
        if (der == null) {
            List<Outcome.Check> absent = new ArrayList<>();
            for (String name : REQUIRED) {
                absent.add(Outcome.Check.absent(name,
                        "the package carries no RFC 3161 token"));
            }
            return absent;
        }

        TimeStampToken token;
        try {
            token = new TimeStampToken(new org.bouncycastle.cms.CMSSignedData(der));
        } catch (Exception notTsp) {
            // Parse failure IS a finding: the package presents a file as a timestamp token and
            // it is not one.
            List<Outcome.Check> failed = new ArrayList<>();
            failed.add(Outcome.Check.failed("token parse",
                    "the package presents anchors/rfc3161.der as an RFC 3161 token and it does "
                            + "not parse as one: " + notTsp.getMessage()));
            for (String name : REQUIRED.subList(1, REQUIRED.size())) {
                failed.add(Outcome.Check.absent(name, "the token did not parse"));
            }
            return failed;
        }

        List<Outcome.Check> checks = new ArrayList<>();
        checks.add(Outcome.Check.passed("token parse"));
        checks.add(imprint(entries, token));

        X509CertificateHolder signer = signerOf(token);
        checks.add(signature(token, signer));
        checks.add(eku(signer));
        checks.add(pkix(token, signer, trust));
        checks.add(policy(token, trust));
        checks.add(revocation(entries, trust));
        return checks;
    }

    static Outcome.Check imprint(Map<String, byte[]> entries, TimeStampToken token) {
        Map<String, Object> target = documentIn(entries, "anchor-target-checkpoint.json");
        if (target == null) {
            return Outcome.Check.absent("token imprint",
                    "the package carries no anchor target checkpoint, so there is nothing the "
                            + "token could be about");
        }
        Object root = target.get("merkleRoot");
        if (!(root instanceof String merkleRoot)) {
            return Outcome.Check.absent("token imprint",
                    "the anchor target records no Merkle root");
        }
        byte[] imprint = token.getTimeStampInfo().getMessageImprintDigest();
        // The imprint IS the root, as §12 says: the Merkle root is already a SHA-256 digest,
        // and what the product timestamps is that digest's BYTES — it decodes the hex and
        // sends the 32 bytes (Rfc3161AnchorTarget.decodeSha256Hex). This read hashed the hex
        // STRING a second time, so it refused every token the product has ever produced while
        // agreeing with a fixture that hashed it the same wrong way. Found by the third
        // review, from the writer's side (2026-09-22).
        String recorded = Canonical.hex(imprint);
        if (!recorded.equals(merkleRoot)) {
            return Outcome.Check.failed("token imprint",
                    "the token is over " + recorded + " and the anchor target's Merkle root is "
                            + merkleRoot + ", so the token is about something else");
        }
        return Outcome.Check.passed("token imprint");
    }

    /**
     * The SHARED classification (TokenSignature), not a second one.
     *
     * <p>This method used to catch every exception and call it a bad signature, while P2 split
     * "does not verify" from "this build cannot compute it". The same token therefore exited 2
     * here and 3 there — a tampering finding for a signature nobody checked (both reviews,
     * fifth round). The profile still chooses the check's name; it does not choose what an
     * outcome means.
     */
    static Outcome.Check signature(TimeStampToken token, X509CertificateHolder signer) {
        return TokenSignature.verify("token signature", "the token", token, signer);
    }

    static Outcome.Check eku(X509CertificateHolder signer) {
        if (signer == null) {
            return Outcome.Check.absent("token eku", "the token carries no signer certificate");
        }
        try {
            X509Certificate certificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                    .getCertificate(signer);
            List<String> eku = certificate.getExtendedKeyUsage();
            if (eku == null || !eku.contains(EKU_TIMESTAMPING)) {
                return Outcome.Check.failed("token eku",
                        "the signer certificate does not carry id-kp-timeStamping, so it is not "
                                + "a certificate issued to sign timestamps");
            }
            // Critical is required by RFC 3161 §2.3. A non-critical EKU means a relying party
            // that does not understand it will ignore the restriction.
            Set<String> critical = certificate.getCriticalExtensionOIDs();
            if (critical == null || !critical.contains("2.5.29.37")) {
                return Outcome.Check.failed("token eku",
                        "the signer's extended key usage is not marked critical, so a relying "
                                + "party that does not understand it ignores the restriction");
            }
            return Outcome.Check.passed("token eku");
        } catch (Exception unreadable) {
            return Outcome.Check.unavailable("token eku", "CERTIFICATE_UNREADABLE",
                    "the signer certificate could not be read: " + unreadable.getMessage());
        }
    }

    static Outcome.Check pkix(TimeStampToken token, X509CertificateHolder signer,
            TrustProfile trust) {
        if (trust == null || trust.isEmpty()) {
            // The check that must never pass by default. No trust profile means no anchor was
            // chosen by the person verifying, and a path to nothing is not a path.
            return Outcome.Check.absent("token pkix",
                    "no trust profile was supplied. A path can only be built to an anchor the "
                            + "VERIFIER chose; anchors named by the package would let a "
                            + "replaced signature name its own issuer");
        }
        if (signer == null) {
            return Outcome.Check.absent("token pkix", "the token carries no signer certificate");
        }
        try {
            List<X509Certificate> embedded = new ArrayList<>();
            for (Object holder : token.getCertificates().getMatches(null)) {
                embedded.add(new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                        .getCertificate((X509CertificateHolder) holder));
            }
            Set<TrustAnchor> anchors = new HashSet<>();
            for (X509Certificate anchor : trust.anchors()) {
                anchors.add(new TrustAnchor(anchor, null));
            }
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                    .getCertificate(signer));

            PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, target);
            parameters.addCertStore(CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(embedded)));
            // No network, ever. Revocation is checked from the material the package captured at
            // issuance (see revocation()), not by reaching out now — a current OCSP answer is
            // not evidence about the moment the token was made.
            parameters.setRevocationEnabled(false);
            CertPathBuilder.getInstance("PKIX").build(parameters);
            return Outcome.Check.passed("token pkix");
        } catch (java.security.cert.CertPathBuilderException noPath) {
            return Outcome.Check.failed("token pkix",
                    "no path could be built from the token's signer to any anchor in the trust "
                            + "profile: " + noPath.getMessage());
        } catch (Exception broken) {
            return Outcome.Check.unavailable("token pkix", "PKIX_UNAVAILABLE",
                    "the path could not be built: " + broken.getMessage());
        }
    }

    static Outcome.Check policy(TimeStampToken token, TrustProfile trust) {
        if (trust == null || trust.policyOids().isEmpty()) {
            return Outcome.Check.absent("token policy",
                    "the trust profile names no acceptable policy, so there is nothing to "
                            + "check the token's policy against");
        }
        Object policy = token.getTimeStampInfo().getPolicy();
        String oid = policy == null ? null : policy.toString();
        if (oid == null) {
            return Outcome.Check.failed("token policy",
                    "the token states no policy and the trust profile requires one of "
                            + trust.policyOids());
        }
        if (!trust.policyOids().contains(oid)) {
            return Outcome.Check.failed("token policy",
                    "the token's policy " + oid + " is not one the trust profile accepts "
                            + trust.policyOids());
        }
        return Outcome.Check.passed("token policy");
    }

    /**
     * Revocation material captured AT ISSUANCE — §12.
     *
     * <p>Not fetched now. A current OCSP answer says whether the certificate is revoked today,
     * which is a different question from whether it was valid when the token was made, and
     * treating one as the other is the substitution this profile exists to refuse.
     */
    static Outcome.Check revocation(Map<String, byte[]> entries, TrustProfile trust) {
        byte[] material = fileIn(entries, "anchors/rfc3161-revocation.der");
        if (material == null || material.length == 0) {
            if (trust != null && !trust.requireRevocationAtIssuance()) {
                return Outcome.Check.unavailable("token revocation", "REVOCATION_NOT_REQUIRED",
                        "the package captured no revocation material and the trust profile "
                                + "does not require it. Nothing here establishes the signer was "
                                + "unrevoked when the token was made");
            }
            // INDETERMINATE, not FAILED. "We cannot tell whether it was revoked" is not "it was
            // revoked", and §12 says a VERIFIED verdict is not available either.
            return Outcome.Check.unavailable("token revocation", "REVOCATION_NOT_CAPTURED",
                    "the package captured no revocation material at issuance, so whether the "
                            + "signer was valid when the token was made is UNKNOWN. This is not "
                            + "a finding that it was revoked");
        }
        return Outcome.Check.unavailable("token revocation", "REVOCATION_NOT_PARSED",
                "the package carries revocation material and this version does not evaluate "
                        + "it. Its presence is not its verification");
    }

    private static X509CertificateHolder signerOf(TimeStampToken token) {
        try {
            for (Object holder : token.getCertificates()
                    .getMatches(token.getSID())) {
                return (X509CertificateHolder) holder;
            }
        } catch (RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /** Delegated to {@link Section}, which is the ONE place that excludes payload. */
    private static byte[] fileIn(Map<String, byte[]> entries, String name) {
        return Section.fileIn(entries, name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> documentIn(Map<String, byte[]> entries, String name) {
        byte[] bytes = fileIn(entries, name);
        if (bytes == null) {
            return null;
        }
        try {
            Object value = Json.parse(new String(bytes, StandardCharsets.UTF_8));
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
