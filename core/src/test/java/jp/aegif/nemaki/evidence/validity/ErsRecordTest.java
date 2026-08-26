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
package jp.aegif.nemaki.evidence.validity;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RFC 4998 evidence record this product produces, checked against a real TSA (P2-3).
 *
 * <h2>Why a real timestamp authority and not a stub</h2>
 *
 * <p>The whole point of the record is that a standard tool can read it, and a stub token would
 * let a wrong ASN.1 structure or a wrong reduction rule pass. The TSA here is generated in the
 * test: a key, a self-signed certificate with the timestamping EKU, and BouncyCastle's own
 * token generator. That produces genuine RFC 3161 tokens, whose message imprints we did not
 * choose, over values we did.
 *
 * <p>It is NOT a trusted TSA, and that is the point of the other half of these tests: the
 * verifier must not report a token as validated on the strength of a certificate nobody
 * vouched for.
 */
class ErsRecordTest {

    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";
    private static final String SHA512_OID = "2.16.840.1.101.3.4.2.3";

    private static TimeStampTokenGenerator tokenGenerator;

    @BeforeAll
    static void tsa() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair keyPair = kpg.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=Test TSA");
        java.util.Date from = new java.util.Date(System.currentTimeMillis() - 86_400_000L);
        java.util.Date to = new java.util.Date(System.currentTimeMillis() + 86_400_000L);
        org.bouncycastle.cert.X509v3CertificateBuilder certBuilder =
                new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                        subject, BigInteger.ONE, from, to, subject, keyPair.getPublic());
        // RFC 3161 2.3: critical, or BouncyCastle refuses to build the generator.
        certBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                        org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
        org.bouncycastle.operator.ContentSigner signer =
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                        .build(keyPair.getPrivate());
        java.security.cert.X509Certificate certificate =
                new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                        .getCertificate(certBuilder.build(signer));
        tokenGenerator = new TimeStampTokenGenerator(
                new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                        .build("SHA256withRSA", keyPair.getPrivate(), certificate),
                new org.bouncycastle.operator.bc.BcDigestCalculatorProvider()
                        .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                new ASN1ObjectIdentifier(SHA256_OID))),
                new ASN1ObjectIdentifier("1.2.3.4.1"));
        tokenGenerator.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                List.of(certificate)));
    }

    /** A genuine token over {@code imprint}, as a TSA would issue it. */
    private static byte[] tokenOver(byte[] imprint) throws Exception {
        return tokenOver(imprint, SHA256_OID);
    }

    /**
     * A token whose imprint algorithm matches the imprint's length.
     *
     * <p>Parameterised because a hash-tree renewal exists to change algorithm: sending a
     * 64-byte SHA-512 imprint under the SHA-256 OID makes the TSA return a rejection with no
     * token at all, which surfaces as an NPE three frames away from the cause.
     */
    private static byte[] tokenOver(byte[] imprint, String oid) throws Exception {
        TimeStampRequest request = new TimeStampRequestGenerator()
                .generate(new ASN1ObjectIdentifier(oid), imprint);
        TimeStampResponse response = new TimeStampResponseGenerator(tokenGenerator,
                java.util.Set.of(SHA256_OID, SHA512_OID))
                .generate(request, BigInteger.ONE, new java.util.Date());
        assertNotNull(response.getTimeStampToken(),
                "the test TSA refused to issue a token: " + response.getStatusString());
        return response.getTimeStampToken().getEncoded();
    }

    /** The data object: the canonical bytes of a checkpoint, as the ledger would serialise it. */
    private static byte[] dataObject() {
        return "checkpoint-1|from=0|to=4|root=abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    /** {@code H(d)} — which, in this product, IS the ledger's checkpoint hash. */
    private static byte[] dataObjectHash() throws Exception {
        return sha256(dataObject());
    }

    /** A record whose first token covers exactly what RFC 4998 §4.2/§4.3 requires. */
    private static ErsRecord firstRecord() throws Exception {
        byte[] h = dataObjectHash();
        return ErsRecord.first(h, tokenOver(ErsRecord.imprintForFirst(h)));
    }

    @Test
    @DisplayName("a record round-trips through DER and keeps its structure")
    void roundTrip() throws Exception {
        ErsRecord read = ErsRecord.parse(firstRecord().der());

        assertEquals(1, read.chains().size());
        assertEquals(1, read.timestampsInCurrentChain());
        assertTrue(read.chains().get(0).get(0).firstHashList().isEmpty(),
                "the first Archive Timestamp carries a reduced hash tree; RFC 4998 §4.2 lets it "
                        + "carry none, and none is what lets this repository's existing anchor "
                        + "be the token");
        assertEquals(List.of(SHA256_OID), read.digestAlgorithmOids());
    }

    @Test
    @DisplayName("the first token must cover H(d) UNCHANGED — the §4.3 degenerate case")
    void theFirstTokenCoversTheDataObjectHash() throws Exception {
        byte[] h = dataObjectHash();

        assertArrayEquals(h, ErsRecord.imprintForFirst(h),
                "the first imprint is not the data object's hash, so a standard verifier "
                        + "computing H(d) and comparing it with hashedMessage will reject");
        assertTrue(ErsVerifier.verify(firstRecord().der(), h).linksHold());
    }

    @Test
    @DisplayName("a token over H(H(d)) does NOT verify — the control for the rule above")
    void theOldWrongInterpretationIsCaught() throws Exception {
        // What the first implementation produced: the checkpoint hash in a one-node tree with a
        // token over H(of that). A standard verifier rejects it, and so must this one — the two
        // agreeing with each other was the whole defect.
        byte[] h = dataObjectHash();
        ErsRecord wrong = ErsRecord.first(h, tokenOver(sha256(h)));

        assertFalse(ErsVerifier.verify(wrong.der(), h).linksHold(),
                "a token over H(H(d)) was accepted as covering d");
    }

    @Test
    @DisplayName("a token over somebody else's data does not verify")
    void aRecordAboutAnotherPartysDataIsRefused() throws Exception {
        byte[] theirs = sha256("theirs".getBytes("UTF-8"));
        ErsRecord record = ErsRecord.first(theirs, tokenOver(ErsRecord.imprintForFirst(theirs)));

        assertTrue(ErsVerifier.verify(record.der(), theirs).linksHold(),
                "the fixture is not internally consistent, so this test measures nothing");
        assertFalse(ErsVerifier.verify(record.der(), dataObjectHash()).linksHold(),
                "a record about another party's data passed as ours");
    }

    @Test
    @DisplayName("a §5.2 renewal covers H(previous token), and stays in the same chain")
    void timestampRenewalCoversThePreviousToken() throws Exception {
        ErsRecord first = firstRecord();
        byte[] imprint = first.imprintForTimestampRenewal();

        assertArrayEquals(sha256(first.chains().get(0).get(0).timeStampDer()), imprint,
                "§5.2 says the CONTENT of the old timeStamp field is hashed and timestamped");
        ErsRecord renewed = first.withTimestampRenewal(tokenOver(imprint));

        assertEquals(1, renewed.chains().size(), "a timestamp renewal started a new chain");
        assertEquals(2, renewed.timestampsInCurrentChain());
        ErsVerifier.Report report = ErsVerifier.verify(renewed.der(), dataObjectHash());
        assertTrue(report.linksHold(), String.valueOf(report.asMap()));
        assertEquals(2, report.timestampsChecked());
    }

    @Test
    @DisplayName("a renewal token over an unrelated value is caught")
    void anUnrelatedRenewalIsCaught() throws Exception {
        // The defect the first verifier had: it hashed whatever value was filed beside the
        // token and compared THAT, so a chain of mutually unrelated tokens reported success.
        ErsRecord renewed = firstRecord()
                .withTimestampRenewal(tokenOver(sha256("unrelated".getBytes("UTF-8"))));

        ErsVerifier.Report report = ErsVerifier.verify(renewed.der(), dataObjectHash());

        assertFalse(report.linksHold(), "a renewal that does not cover the previous token passed");
        assertTrue(report.results().get(1).detail().contains("does not belong here"),
                report.results().get(1).detail());
    }

    @Test
    @DisplayName("a §5.3 renewal commits to every previous chain")
    void hashTreeRenewalCommitsToTheOldChains() throws Exception {
        ErsRecord first = firstRecord();
        // A new algorithm, which is why a hash-tree renewal happens at all.
        byte[] hUnderNew = MessageDigest.getInstance("SHA-512").digest(dataObject());
        ErsRecord.HashTreeRenewalInputs inputs =
                first.inputsForHashTreeRenewal(hUnderNew, SHA512_OID);

        // ha is over the DER of the whole previous sequence, tags and lengths included (§5.3-3).
        assertArrayEquals(MessageDigest.getInstance("SHA-512")
                        .digest(ErsRecord.encodeSequence(first.chains())),
                inputs.previousSequenceHash());

        ErsRecord renewed = first.withHashTreeRenewal(inputs.hPrime(),
                tokenOver(inputs.imprint(), SHA512_OID), SHA512_OID);

        assertEquals(2, renewed.chains().size(), "a hash-tree renewal extended the old chain");
        assertEquals(List.of(SHA256_OID, SHA512_OID), renewed.digestAlgorithmOids(),
                "the record does not declare the algorithm its new chain uses");
        ErsVerifier.Report report = ErsVerifier.verify(renewed.der(), dataObjectHash(),
                java.util.Map.of(SHA512_OID, hUnderNew));
        assertTrue(report.linksHold(), String.valueOf(report.asMap()));
    }

    @Test
    @DisplayName("a new chain that does NOT commit to the old ones is caught")
    void anUnrelatedNewChainIsCaught() throws Exception {
        // What the first implementation built: an arbitrary hash, timestamped, filed next to
        // the old chains. Nothing tied the new chain to them, so nothing carried the old time
        // forward — which is the entire purpose of a hash-tree renewal.
        ErsRecord first = firstRecord();
        byte[] unrelated = MessageDigest.getInstance("SHA-512").digest("rebuilt".getBytes("UTF-8"));
        byte[] hUnderNew = MessageDigest.getInstance("SHA-512").digest(dataObject());

        ErsRecord renewed = first.withHashTreeRenewal(unrelated,
                tokenOver(MessageDigest.getInstance("SHA-512").digest(unrelated), SHA512_OID), SHA512_OID);

        ErsVerifier.Report report = ErsVerifier.verify(renewed.der(), dataObjectHash(),
                java.util.Map.of(SHA512_OID, hUnderNew));

        assertFalse(report.linksHold(),
                "a new chain unrelated to the old ones was accepted as renewing them");
        assertTrue(report.results().get(1).detail().contains("does not renew them"),
                report.results().get(1).detail());
    }

    @Test
    @DisplayName("without H(d) under the new algorithm, the §5.3 link is not claimed")
    void anUncheckableLinkIsNotClaimed() throws Exception {
        // The relationship needs H(d) under the NEW algorithm, which cannot be derived from the
        // old hash. A caller that cannot supply it must not be told the link holds — but the
        // token's own imprint is still checkable, so that part is.
        ErsRecord first = firstRecord();
        byte[] hUnderNew = MessageDigest.getInstance("SHA-512").digest(dataObject());
        ErsRecord.HashTreeRenewalInputs inputs =
                first.inputsForHashTreeRenewal(hUnderNew, SHA512_OID);
        ErsRecord renewed = first.withHashTreeRenewal(inputs.hPrime(),
                tokenOver(inputs.imprint(), SHA512_OID), SHA512_OID);

        ErsVerifier.Report report = ErsVerifier.verify(renewed.der(), dataObjectHash());

        assertTrue(report.linksHold(),
                "the imprint check alone should still pass: " + report.asMap());
    }

    @Test
    @DisplayName("verifying with no data object hash is refused, not passed")
    void aRecordVerifiedAgainstItselfIsNotVerified() throws Exception {
        ErsVerifier.Report report = ErsVerifier.verify(firstRecord().der(), null);

        assertFalse(report.linksHold());
        assertTrue(report.notChecked().contains("nothing to check the record AGAINST"),
                report.notChecked());
    }

    @Test
    @DisplayName("the report says the TSA signature was not checked, before anything else")
    void theUncheckedPartIsSaidFirst() throws Exception {
        ErsVerifier.Report report = ErsVerifier.verify(firstRecord().der(), dataObjectHash());
        List<String> keys = List.copyOf(report.asMap().keySet());

        assertEquals("limits", keys.get(0), keys.toString());
        assertEquals("notChecked", keys.get(1), keys.toString());
        assertTrue(report.notChecked().contains("SIGNATURE"), report.notChecked());
        assertTrue(report.notChecked().contains("no trust anchors"), report.notChecked());
    }

    @Test
    @DisplayName("the limits say the data object is a checkpoint's bytes, not a document")
    void theLimitsNameWhatItIsAbout() {
        assertTrue(ErsRecord.LIMITS.contains("canonical serialisation"), ErsRecord.LIMITS);
        assertTrue(ErsRecord.LIMITS.contains("not a document"), ErsRecord.LIMITS);
        assertTrue(ErsRecord.LIMITS.contains("does NOT"), ErsRecord.LIMITS);
    }

    @Test
    @DisplayName("garbage is a parse failure, not a record with no timestamps")
    void garbageIsNotAnEmptyRecord() throws Exception {
        ErsVerifier.Report report = ErsVerifier.verify("not DER".getBytes(), dataObjectHash());

        assertFalse(report.linksHold());
        assertTrue(report.notChecked().contains("could not be parsed"), report.notChecked());
    }

    @Test
    @DisplayName("an empty component is refused at construction")
    void emptyComponentsAreRefused() throws Exception {
        byte[] h = dataObjectHash();

        assertThrows(IllegalArgumentException.class,
                () -> ErsRecord.first(new byte[0], tokenOver(h)));
        assertThrows(IllegalArgumentException.class,
                () -> ErsRecord.first(h, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> firstRecord().withHashTreeRenewal(h, tokenOver(h), "  "));
    }
}
