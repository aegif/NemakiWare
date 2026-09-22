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
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TSPValidationException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One token, one answer — whichever profile is asked.
 *
 * <h2>The defect this holds shut</h2>
 *
 * <p>{@code ANCHORED_CHECKPOINT_V1} and {@code TRUSTED_RFC3161_V1} both verify the timestamp
 * token's CMS signature, and each decided separately what a failure meant. The disagreement
 * moved twice: first an absent signer certificate (UNAVAILABLE at P2, NOT_PRESENT at P3), then
 * a signature algorithm with no provider (UNAVAILABLE at P2, FAILED at P3 — exit 3 from one
 * profile and exit 2 from the other, over the same bytes). Each time the fix touched one side
 * (both reviews, fourth and fifth rounds).
 *
 * <p>So the answer is compared here, for every token that can be built, rather than being
 * corrected once more on whichever side was noticed. The classification itself lives in
 * {@code TokenSignature} and is measured below on the one case no fixture can produce: an
 * algorithm this JVM has no provider for.
 */
class OneTokenGetsOneAnswerTest {

    private static final String SHA256 = "2.16.840.1.101.3.4.2.1";
    private static final String DIR = "sip/metadata/other/nemaki-evidence/";
    private static final String ROOT =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private static KeyPair keys;
    private static X509Certificate certificate;

    @BeforeAll
    static void authority() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keys = kpg.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=One Answer TSA");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                BigInteger.ONE, new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), subject, keys.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                        org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
        certificate = new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }

    private static byte[] tokenOver(String merkleRoot, boolean certReq) throws Exception {
        TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
        requests.setCertReq(certReq);
        TimeStampRequest request = requests.generate(new ASN1ObjectIdentifier(SHA256),
                HexFormat.of().parseHex(merkleRoot));
        TimeStampTokenGenerator generator = new TimeStampTokenGenerator(
                new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                        .build("SHA256withRSA", keys.getPrivate(), certificate),
                new JcaDigestCalculatorProviderBuilder().build().get(
                        new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                new ASN1ObjectIdentifier(SHA256))),
                new ASN1ObjectIdentifier("1.2.3.4.1"));
        generator.addCertificates(
                new org.bouncycastle.cert.jcajce.JcaCertStore(List.of(certificate)));
        return generator.generate(request, BigInteger.ONE, new Date()).getEncoded();
    }

    /** A P0+P2-shaped package holding {@code token} as the RFC 3161 rung. */
    private static Map<String, byte[]> packageWith(byte[] token) {
        String checkpoint = "{\"domain\":\"record-content\",\"fromSequence\":1,"
                + "\"toSequence\":10,\"merkleRoot\":\"" + ROOT + "\","
                + "\"prevCheckpointHash\":null,\"createdAt\":\"2026-09-20T00:00:00Z\","
                + "\"checkpointHash\":\"cc\"}";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                checkpoint.getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "checkpoint-chain.json",
                ("{\"links\":[" + checkpoint + "]}").getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "covering-checkpoint.json", checkpoint.getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "bundle-manifest.json", ("{\"bundleId\":\"b\",\"files\":[],"
                + "\"anchors\":[{\"kind\":\"RFC3161_TSA\",\"state\":\"PRESENT\","
                + "\"path\":\"anchors/rfc3161.der\"}]}").getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "anchors/rfc3161.der", token);
        return entries;
    }

    private static Outcome.Check named(List<Outcome.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    /** What P2 and P3 each say about the signature on the same token. */
    private static void bothAgree(byte[] token, Outcome expected, String why) {
        Map<String, byte[]> entries = packageWith(token);
        Outcome.Check p2 = named(AnchoredCheckpoint.check(entries, null), "anchor commits root");
        Outcome.Check p3 = named(TrustedRfc3161.check(entries, TrustProfile.empty()),
                "token signature");

        assertEquals(expected, p2.outcome(),
                "ANCHORED_CHECKPOINT_V1: " + why + " — " + p2.detail());
        assertEquals(expected, p3.outcome(),
                "TRUSTED_RFC3161_V1: " + why + " — " + p3.detail());
        assertEquals(p2.reasonCode(), p3.reasonCode(),
                "the two profiles give the same token the same outcome and different reason "
                        + "codes, so a caller branching on reasonCode still sees two answers: "
                        + p2 + " / " + p3);
    }

    @Test
    @DisplayName("a token that verifies passes at both profiles")
    void aGoodTokenPassesAtBoth() throws Exception {
        bothAgree(tokenOver(ROOT, true), Outcome.PASSED,
                "a token whose signature verifies against its own certificate was not passed");
    }

    @Test
    @DisplayName("a token whose signature does not verify FAILS at both profiles")
    void aBrokenSignatureFailsAtBoth() throws Exception {
        byte[] token = tokenOver(ROOT, true);
        // The last byte of the DER is inside the signature value, so the imprint is untouched
        // and only the signature is wrong.
        byte[] tampered = token.clone();
        tampered[tampered.length - 1] ^= 0x01;

        bothAgree(tampered, Outcome.FAILED,
                "a token whose signature does not verify was not reported as a finding");
    }

    @Test
    @DisplayName("a token carrying no signer certificate is NOT_PRESENT at both profiles")
    void anAbsentCertificateIsAbsentAtBoth() throws Exception {
        // certReq false: RFC 3161 then REQUIRES the authority to omit its certificate, so this
        // is a legitimate token about which neither profile can say anything.
        bothAgree(tokenOver(ROOT, false), Outcome.NOT_PRESENT,
                "a token with no signer certificate was answered as something other than an "
                        + "absence. Nothing about its signature was established either way");
    }

    /**
     * A SUBSTITUTED signer certificate is a finding — measured, not assumed.
     *
     * <p>A review predicted that swapping the embedded certificate for one carrying another
     * key would end in {@code InvalidKeyException} and be excused as "this build cannot compute
     * it". It does not: BouncyCastle compares the ESSCertID hash BEFORE it tries the signature,
     * so the token itself says this is not the certificate it was signed with, and that is a
     * fact about the package. Measured here rather than reasoned about, because the answer
     * decides whether a substitution exits 2 or 3.
     *
     * <p>What remains for {@code SIGNATURE_NOT_COMPUTED} is the narrower case the seventh
     * review named: the certID matches and no installed provider will initialise with the key.
     * No fixture can produce that, so the rule is measured on its own below.
     */
    @Test
    @DisplayName("a substituted signer certificate is a FINDING, caught by the certID hash")
    void aSubstitutedCertificateIsAFinding() throws Exception {
        java.security.KeyPairGenerator ec = java.security.KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        java.security.KeyPair other = ec.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=One Answer TSA");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                BigInteger.ONE, new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), subject, other.getPublic());
        X509Certificate wrongKey = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA")
                        .build(other.getPrivate())));

        org.bouncycastle.tsp.TimeStampToken token = new org.bouncycastle.tsp.TimeStampToken(
                new org.bouncycastle.cms.CMSSignedData(tokenOver(ROOT, true)));
        Outcome.Check answer = TokenSignature.verify("token signature", "the token", token,
                new X509CertificateHolder(wrongKey.getEncoded()));

        assertEquals(Outcome.FAILED, answer.outcome(),
                "a token checked against a certificate it was not signed with was not reported "
                        + "as a finding. The token names its own certificate by hash, so this "
                        + "is something the package says about itself: " + answer.detail());
        assertTrue(answer.detail().contains("does not verify"), answer.detail());
    }

    /**
     * A token naming an algorithm its OWN key cannot be used with is a finding.
     *
     * <p>The certificate and its certID hash are untouched, so BouncyCastle's first check
     * passes; what is wrong is inside the token — the SignerInfo names an RSA signature over a
     * certificate carrying an EC key. Reporting "this build could not compute it" would excuse
     * a contradiction the package states about itself (Codex, eighth review, P1).
     */
    @Test
    @DisplayName("a token whose algorithm does not go with its own key is a FINDING")
    void anAlgorithmThatDoesNotGoWithItsOwnKeyIsAFinding() throws Exception {
        java.security.KeyPairGenerator ec = java.security.KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        java.security.KeyPair other = ec.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=One Answer TSA");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                BigInteger.ONE, new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), subject, other.getPublic());
        X509Certificate ecCertificate = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA")
                        .build(other.getPrivate())));

        // The token is RSA-signed; the certificate handed to the check carries an EC key. The
        // ALGORITHM FAMILY disagrees with the key, which cannotSetUp deliberately does not
        // excuse.
        org.bouncycastle.tsp.TimeStampToken token = new org.bouncycastle.tsp.TimeStampToken(
                new org.bouncycastle.cms.CMSSignedData(tokenOver(ROOT, true)));
        Outcome.Check answer = TokenSignature.verify("token signature", "the token", token,
                new X509CertificateHolder(ecCertificate.getEncoded()));

        assertEquals(Outcome.FAILED, answer.outcome(),
                "a token naming a signature algorithm its own certificate's key cannot be used "
                        + "with was excused as something this build could not compute: "
                        + answer);
    }

    /**
     * A signature whose ENCODING is wrong is a finding, not "not checked".
     *
     * <p>The existing broken-signature fixtures flip one bit, which leaves a syntactically
     * valid signature that simply does not verify — BouncyCastle raises
     * {@code TSPValidationException} and both the old and the new classification answer FAILED.
     * A signature of the WRONG LENGTH is different: {@code Signature.verify} raises
     * {@code SignatureException}, which a previous round excused as "this build could not
     * compute it" (subagent, eighth review, P1, measured). Whether this build can compute the
     * signature is now asked BEFORE the attempt, so anything that fails after it is about the
     * token.
     */
    @Test
    @DisplayName("a signature of the wrong length is a FINDING, not 'not checked'")
    void aMalformedSignatureIsAFinding() throws Exception {
        byte[] der = tokenOver(ROOT, true);
        org.bouncycastle.asn1.ASN1Sequence contentInfo = org.bouncycastle.asn1.ASN1Sequence
                .getInstance(org.bouncycastle.asn1.ASN1Primitive.fromByteArray(der));
        org.bouncycastle.asn1.cms.ContentInfo parsed =
                org.bouncycastle.asn1.cms.ContentInfo.getInstance(contentInfo);
        org.bouncycastle.asn1.cms.SignedData signed =
                org.bouncycastle.asn1.cms.SignedData.getInstance(parsed.getContent());
        org.bouncycastle.asn1.cms.SignerInfo info = org.bouncycastle.asn1.cms.SignerInfo
                .getInstance(signed.getSignerInfos().getObjectAt(0));
        // An RSA signature is 256 bytes here; 64 is not a length any RSA signature has.
        byte[] tooShort = new byte[64];
        java.util.Arrays.fill(tooShort, (byte) 0x7f);
        org.bouncycastle.asn1.cms.SignerInfo mangled = new org.bouncycastle.asn1.cms.SignerInfo(
                info.getSID(), info.getDigestAlgorithm(), info.getAuthenticatedAttributes(),
                info.getDigestEncryptionAlgorithm(),
                new org.bouncycastle.asn1.DEROctetString(tooShort),
                info.getUnauthenticatedAttributes());
        org.bouncycastle.asn1.cms.SignedData rebuiltData =
                new org.bouncycastle.asn1.cms.SignedData(signed.getDigestAlgorithms(),
                        signed.getEncapContentInfo(), signed.getCertificates(),
                        signed.getCRLs(), new org.bouncycastle.asn1.DERSet(mangled));
        byte[] rebuilt = new org.bouncycastle.asn1.cms.ContentInfo(
                parsed.getContentType(), rebuiltData).getEncoded("DER");

        org.bouncycastle.tsp.TimeStampToken token = new org.bouncycastle.tsp.TimeStampToken(
                new org.bouncycastle.cms.CMSSignedData(rebuilt));
        X509CertificateHolder signer = null;
        for (Object held : token.getCertificates().getMatches(token.getSID())) {
            signer = (X509CertificateHolder) held;
            break;
        }
        assertTrue(signer != null, "the rebuilt token lost its signer certificate");

        Outcome.Check answer = TokenSignature.verify("token signature", "the token", token,
                signer);

        assertEquals(Outcome.FAILED, answer.outcome(),
                "a signature whose encoding is not a signature at all was excused as something "
                        + "this build could not compute: " + answer);
    }

    /**
     * When the PROBE cannot run, nothing is concluded from that.
     *
     * <p>{@code cannotSetUp} exists to tell two kinds of "nothing was compared" apart. Reading
     * the SignerInfo, converting the certificate and naming the algorithm are its own work, and
     * a failure there is not evidence that the signature cannot be computed — it is evidence
     * that this probe could not run. Returning a reason for it would make every such token
     * answer {@code SIGNATURE_NOT_COMPUTED} without the algorithm ever being looked at: a
     * blanket excuse wearing a specific reason, which is the shape three reviews in a row have
     * found in this method.
     *
     * <p>Measured with a certificate the converter refuses: the answer has to come from the
     * real verification, not from the probe.
     */
    @Test
    @DisplayName("a probe that cannot run does not become 'this build cannot compute it'")
    void aProbeThatCannotRunConcludesNothing() throws Exception {
        org.bouncycastle.tsp.TimeStampToken token = new org.bouncycastle.tsp.TimeStampToken(
                new org.bouncycastle.cms.CMSSignedData(tokenOver(ROOT, true)));
        // A holder whose bytes are not a certificate the converter can build. Everything the
        // probe wants to read is unreachable; the token itself is fine.
        X509CertificateHolder unreadable = new X509CertificateHolder(brokenCertificate());
        // The fixture has to actually defeat the probe, or this measures nothing.
        assertThrows(Exception.class, () -> new org.bouncycastle.cert.jcajce
                        .JcaX509CertificateConverter().getCertificate(unreadable),
                "the converter accepted this certificate, so the probe runs and this lock is "
                        + "measuring the ordinary path");

        Outcome.Check answer = TokenSignature.verify("token signature", "the token", token,
                unreadable);

        assertFalse("SIGNATURE_NOT_COMPUTED".equals(answer.reasonCode()),
                "a probe that could not read the token at all answered as though it had "
                        + "established that this build cannot compute the signature: " + answer);
    }

    /** A structurally valid certificate the JCA converter will not accept. */
    private static org.bouncycastle.asn1.x509.Certificate brokenCertificate() throws Exception {
        // The real signer certificate, with its signature algorithm replaced by an OID no
        // provider knows — enough to make the converter refuse while the DER stays well formed.
        org.bouncycastle.asn1.x509.Certificate real =
                new X509CertificateHolder(certificate.getEncoded()).toASN1Structure();
        return org.bouncycastle.asn1.x509.Certificate.getInstance(new org.bouncycastle.asn1
                .DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] {
                        real.getTBSCertificate(),
                        new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                new ASN1ObjectIdentifier("1.2.3.4.5.6.7.8.9")),
                        real.getSignature() }));
    }

    /**
     * The one case no fixture here can produce: a signature algorithm with no provider.
     *
     * <p>Every algorithm this test could sign with is one this JVM implements, so the token
     * cannot be built. What CAN be measured is the rule — and the rule is the part that was
     * wrong: BouncyCastle wraps the provider failure several layers down inside a plain
     * {@code TSPException}, which the old code caught as "the signature does not verify". The
     * exception TYPE does not separate the two; the cause does.
     */
    @Test
    @DisplayName("a provider failure several wrappers down is 'not checked', not 'does not verify'")
    void aProviderFailureIsNotAFinding() {
        // The message deliberately does NOT contain "no such algorithm": the classifier has a
        // second, message-based arm for providers that raise a bare GeneralSecurityException,
        // and a fixture matching both would stay green with the TYPE arm removed — satisfied by
        // a branch it is not about (measured: control OM3 did not fire).
        TSPException providerMissing = new TSPException("unable to process signature",
                new CMSException("can't create digest calculator",
                        new OperatorCreationException("exception on setup",
                                new NoSuchAlgorithmException("1.2.3.4 MessageDigest not "
                                        + "available"))));

        assertTrue(TokenSignature.uncheckable(providerMissing),
                "a signature this build has no provider for was classified as a bad signature, "
                        + "which names a defect nobody found. The failure arrives as a plain "
                        + "TSPException, so catching that type as a finding is what produced "
                        + "exit 2 for an unchecked signature");

        // And the other direction, which matters just as much: a real validation failure must
        // NOT be excused as something this build could not compute.
        assertFalse(TokenSignature.uncheckable(
                        new TSPValidationException("certificate hash does not match certID hash")),
                "a token that genuinely does not verify was excused as uncheckable, so "
                        + "tampering would be reported as 'we could not tell'");
        assertFalse(TokenSignature.uncheckable(new TSPException("unable to process signature",
                        new CMSException("message-digest attribute value does not match "
                                + "calculated value"))),
                "a CMS signature that does not match was excused as uncheckable. Only a MISSING "
                        + "provider is a limit of this build; a mismatch is a finding");

        // A key that will not initialise is its OWN answer. Two reviews pushed this in
        // opposite directions — one called UNAVAILABLE "a mismatch excused", the other called
        // FAILED "a comparison nobody made" — and the resolution is that neither
        // UNKNOWN_ALGORITHM nor "does not verify" is true. Nothing was compared, and the reason
        // code says which kind of nothing.
        TSPException keyUnusable = new TSPException("unable to process signature",
                new CMSException("can't create digest calculator",
                        new OperatorCreationException("exception on setup",
                                new java.security.InvalidKeyException(
                                        "EC key given for RSA signature"))));
        assertTrue(TokenSignature.keyNotUsable(keyUnusable),
                "a key the token's own certificate carries that will not initialise was not "
                        + "recognised, so this case falls into one of the two answers that are "
                        + "both wrong for it");
        assertFalse(TokenSignature.keyNotUsable(providerMissing),
                "a MISSING provider was classified as an unusable key, so the two kinds of "
                        + "'nothing was compared' can no longer be told apart");

        // The second arm, on its own fixture: a provider that reports the absence as a bare
        // GeneralSecurityException rather than as one of the three types above.
        assertTrue(TokenSignature.uncheckable(new TSPException("unable to process signature",
                        new java.security.GeneralSecurityException(
                                "no such algorithm: 1.2.840.113549.1.1.10"))),
                "a provider reporting a missing algorithm as a plain GeneralSecurityException "
                        + "was classified as a bad signature");
    }
}
