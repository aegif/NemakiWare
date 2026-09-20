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

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3, and the one rule that makes it worth anything: <b>the trust comes from outside</b>.
 *
 * <p>Every other check here can be satisfied by a package that built its own timestamp with its
 * own certificate. What cannot be is a path to an anchor the VERIFIER chose — so the tests
 * below spend most of their attention on the absence of a trust profile, which must never look
 * like a permissive one.
 */
class TheTrustComesFromOutsideTest {

    private static final String DIR = "sip/metadata/other/nemaki-evidence/";
    private static final String POLICY = "1.2.3.4.5";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /** A self-signed CA and a TSA certificate under it. */
    private record Authority(X509Certificate ca, KeyPair caKeys, X509Certificate tsa,
                             KeyPair tsaKeys) {
    }

    private static Authority authority(boolean ekuCritical, boolean withEku) throws Exception {
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        KeyPair caKeys = keys.generateKeyPair();
        KeyPair tsaKeys = keys.generateKeyPair();

        Date from = new Date(System.currentTimeMillis() - 86_400_000L);
        Date to = new Date(System.currentTimeMillis() + 86_400_000L);

        X500Name caName = new X500Name("CN=Test CA");
        X509CertificateHolder caHolder = new JcaX509v3CertificateBuilder(caName,
                BigInteger.ONE, from, to, caName, caKeys.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate()));

        X500Name tsaName = new X500Name("CN=Test TSA");
        org.bouncycastle.cert.X509v3CertificateBuilder tsaBuilder =
                new JcaX509v3CertificateBuilder(caName, BigInteger.TWO, from, to, tsaName,
                        tsaKeys.getPublic())
                        .addExtension(Extension.basicConstraints, true,
                                new BasicConstraints(false));
        if (withEku) {
            tsaBuilder = tsaBuilder.addExtension(Extension.extendedKeyUsage, ekuCritical,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        }
        X509CertificateHolder tsaHolder = tsaBuilder
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate()));

        JcaX509CertificateConverter converter =
                new JcaX509CertificateConverter().setProvider("BC");
        return new Authority(converter.getCertificate(caHolder), caKeys,
                converter.getCertificate(tsaHolder), tsaKeys);
    }

    /** A real RFC 3161 token over the UTF-8 bytes of {@code merkleRoot}. */
    private static byte[] tokenOver(Authority authority, String merkleRoot) throws Exception {
        byte[] imprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(merkleRoot.getBytes(StandardCharsets.UTF_8));
        // certReq MUST be set, or BouncyCastle omits the signer certificate from the token and
        // every check that needs it reports "the token carries no signer certificate" — which
        // is a legitimate answer for such a token, and not the case under test here. The
        // product's own anchor code carries the same warning.
        TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
        requests.setCertReq(true);
        TimeStampRequest request =
                requests.generate(new ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1"), imprint);

        org.bouncycastle.cms.SignerInfoGenerator signerInfo =
                new org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC")
                                        .build(authority.tsaKeys().getPrivate()),
                                new X509CertificateHolder(authority.tsa().getEncoded()));
        TimeStampTokenGenerator generator = new TimeStampTokenGenerator(signerInfo,
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
                        .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                new ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1"))),
                new ASN1ObjectIdentifier(POLICY));
        generator.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                List.of(authority.tsa(), authority.ca())));
        return generator.generate(request, BigInteger.ONE, new Date()).getEncoded();
    }

    private static Map<String, byte[]> packageWith(byte[] token, String merkleRoot) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("domain", "record-content");
        target.put("fromSequence", 1L);
        target.put("toSequence", 10L);
        target.put("merkleRoot", merkleRoot);
        target.put("prevCheckpointHash", null);
        target.put("createdAt", "2026-09-20T00:00:00Z");
        target.put("checkpointHash", "cc");

        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : target.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            json.append(v == null ? "null" : v instanceof String s ? "\"" + s + "\"" : v);
        }
        json.append('}');

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                json.toString().getBytes(StandardCharsets.UTF_8));
        if (token != null) {
            entries.put(DIR + "anchors/rfc3161.der", token);
        }
        return entries;
    }

    private static TrustProfile profileWith(Path dir, X509Certificate anchor, String policy)
            throws Exception {
        String pem = Base64.getEncoder().encodeToString(anchor.getEncoded());
        String body = "{\"anchors\":[\"" + pem + "\"]"
                + (policy == null ? "" : ",\"policyOids\":[\"" + policy + "\"]") + "}";
        Path file = Files.writeString(dir.resolve("trust.json"), body);
        return TrustProfile.read(file);
    }

    private static Outcome.Check named(List<Outcome.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a real token under a chosen anchor passes parse, imprint, signature, EKU and path")
    void aRealTokenUnderAChosenAnchorPasses(@TempDir Path tmp) throws Exception {
        Authority authority = authority(true, true);
        String root = "aabbcc";
        List<Outcome.Check> checks = TrustedRfc3161.check(
                packageWith(tokenOver(authority, root), root),
                profileWith(tmp, authority.ca(), POLICY));

        for (String name : List.of("token parse", "token imprint", "token signature",
                "token eku", "token pkix", "token policy")) {
            assertEquals(Outcome.PASSED, named(checks, name).outcome(), name + ": " + checks);
        }
    }

    @Test
    @DisplayName("WITHOUT a trust profile the path check is NOT_PRESENT — never passed")
    void withoutATrustProfileThePathIsNotPresent(@TempDir Path tmp) throws Exception {
        Authority authority = authority(true, true);
        String root = "aabbcc";
        List<Outcome.Check> checks = TrustedRfc3161.check(
                packageWith(tokenOver(authority, root), root), TrustProfile.empty());

        Outcome.Check pkix = named(checks, "token pkix");
        assertEquals(Outcome.NOT_PRESENT, pkix.outcome(),
                "the token verifies against its OWN certificate — that is what a package that "
                        + "minted its own timestamp looks like. Only a path to an anchor the "
                        + "verifier chose distinguishes the two, and with no profile there is "
                        + "no such anchor");
        assertEquals(Outcome.PASSED, named(checks, "token signature").outcome(),
                "and the signature check passes, which is exactly why it is not enough");
    }

    @Test
    @DisplayName("a token from an authority the profile does not anchor fails the path")
    void aTokenFromAnotherAuthorityFailsThePath(@TempDir Path tmp) throws Exception {
        Authority mine = authority(true, true);
        Authority someoneElse = authority(true, true);
        String root = "aabbcc";

        List<Outcome.Check> checks = TrustedRfc3161.check(
                packageWith(tokenOver(someoneElse, root), root),
                profileWith(tmp, mine.ca(), POLICY));

        assertEquals(Outcome.FAILED, named(checks, "token pkix").outcome(),
                "a package that minted its own timestamp presents a perfectly valid token; the "
                        + "anchor is the only thing that tells it apart");
    }

    @Test
    @DisplayName("a token over something else fails the imprint")
    void aTokenOverSomethingElseFailsTheImprint(@TempDir Path tmp) throws Exception {
        Authority authority = authority(true, true);
        List<Outcome.Check> checks = TrustedRfc3161.check(
                packageWith(tokenOver(authority, "a different root"), "aabbcc"),
                profileWith(tmp, authority.ca(), POLICY));

        assertEquals(Outcome.FAILED, named(checks, "token imprint").outcome(),
                "a valid timestamp over the wrong thing is still a valid timestamp");
    }

    @Test
    @DisplayName("a signer certificate without id-kp-timeStamping fails")
    void aSignerWithoutTimestampingEkuFails() throws Exception {
        // The CHECK, not an end-to-end package. BouncyCastle's TimeStampTokenGenerator refuses
        // to mint a token whose certificate lacks a critical timestamping EKU — measured:
        // "Certificate must have an ExtendedKeyUsage extension." So a token like this cannot be
        // built with it at all, and the fixture would be a DER patched by hand. What the check
        // defends against is a token minted by something less careful, which is exactly what a
        // hostile package would use.
        Authority authority = authority(true, false);
        Outcome.Check check = TrustedRfc3161.eku(
                new X509CertificateHolder(authority.tsa().getEncoded()));

        assertEquals(Outcome.FAILED, check.outcome(),
                "a certificate not issued to sign timestamps signing one is the case the EKU "
                        + "exists to prevent");
        assertTrue(check.detail().contains("id-kp-timeStamping"), check.detail());
    }

    @Test
    @DisplayName("a non-critical EKU fails, because a relying party may ignore it")
    void aNonCriticalEkuFails() throws Exception {
        // Same reason as above: BouncyCastle refuses to mint it ("...marked as critical").
        Authority authority = authority(false, true);
        Outcome.Check check = TrustedRfc3161.eku(
                new X509CertificateHolder(authority.tsa().getEncoded()));

        assertEquals(Outcome.FAILED, check.outcome(),
                "RFC 3161 §2.3 requires it critical: otherwise a relying party that does not "
                        + "understand the extension ignores the restriction");
        assertTrue(check.detail().contains("critical"), check.detail());
    }

    @Test
    @DisplayName("a policy the profile does not accept fails")
    void anUnacceptedPolicyFails(@TempDir Path tmp) throws Exception {
        Authority authority = authority(true, true);
        String root = "aabbcc";
        List<Outcome.Check> checks = TrustedRfc3161.check(
                packageWith(tokenOver(authority, root), root),
                profileWith(tmp, authority.ca(), "9.9.9.9"));

        assertEquals(Outcome.FAILED, named(checks, "token policy").outcome());
    }

    @Test
    @DisplayName("no revocation material captured at issuance is UNKNOWN, not a pass and not a finding")
    void missingRevocationMaterialIsUnknown(@TempDir Path tmp) throws Exception {
        Authority authority = authority(true, true);
        String root = "aabbcc";
        Outcome.Check revocation = named(TrustedRfc3161.check(
                packageWith(tokenOver(authority, root), root),
                profileWith(tmp, authority.ca(), POLICY)), "token revocation");

        assertEquals(Outcome.UNAVAILABLE, revocation.outcome(),
                "whether the signer was valid WHEN THE TOKEN WAS MADE is unknown without "
                        + "material captured then. A current OCSP answer is a different question");
        assertEquals("REVOCATION_NOT_CAPTURED", revocation.reasonCode());
        assertTrue(revocation.detail().contains("not a finding that it was revoked"),
                revocation.detail());
    }

    @Test
    @DisplayName("a file presented as a token and not parsing is a FINDING")
    void aFileThatIsNotATokenIsAFinding() {
        Map<String, byte[]> entries = packageWith("not a token".getBytes(StandardCharsets.UTF_8),
                "aabbcc");
        assertEquals(Outcome.FAILED,
                named(TrustedRfc3161.check(entries, TrustProfile.empty()), "token parse")
                        .outcome(),
                "the package presents the file AS a timestamp token; that it is not one is a "
                        + "fact about the package");
    }

    @Test
    @DisplayName("no token at all is absent, not failed")
    void noTokenIsAbsent() {
        List<Outcome.Check> checks =
                TrustedRfc3161.check(packageWith(null, "aabbcc"), TrustProfile.empty());
        for (String name : TrustedRfc3161.REQUIRED) {
            assertEquals(Outcome.NOT_PRESENT, named(checks, name).outcome(), name + ": " + checks);
        }
    }

    @Test
    @DisplayName("a trust profile naming no anchor is refused, not treated as empty")
    void aProfileWithNoAnchorsIsRefused(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("empty.json"), "{\"anchors\":[]}");
        TrustProfile.Unreadable refusal =
                assertThrows(TrustProfile.Unreadable.class, () -> TrustProfile.read(file));
        assertTrue(refusal.getMessage().contains("behaves exactly like supplying none"),
                "a profile FILE with no anchors is almost certainly a mistake, and returning "
                        + "the empty profile would hide it: " + refusal.getMessage());
    }

    @Test
    @DisplayName("requireRevocationAtIssuance defaults to true when the profile omits it")
    void revocationIsRequiredUnlessTheProfileSaysOtherwise(@TempDir Path tmp) throws Exception {
        Authority authority = authority(true, true);
        String pem = Base64.getEncoder().encodeToString(authority.ca().getEncoded());
        Path file = Files.writeString(tmp.resolve("t.json"), "{\"anchors\":[\"" + pem + "\"]}");

        assertTrue(TrustProfile.read(file).requireRevocationAtIssuance(),
                "the safe default for 'must the issuance-time material be there' is yes; a "
                        + "profile that omitted it and got no would weaken P3 by silence");
    }
}
