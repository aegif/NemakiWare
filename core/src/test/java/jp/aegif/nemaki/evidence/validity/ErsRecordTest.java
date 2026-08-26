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
        TimeStampRequest request = new TimeStampRequestGenerator()
                .generate(new ASN1ObjectIdentifier(SHA256_OID), imprint);
        TimeStampResponse response = new TimeStampResponseGenerator(tokenGenerator,
                java.util.Set.of(SHA256_OID))
                .generate(request, BigInteger.ONE, new java.util.Date());
        return response.getTimeStampToken().getEncoded();
    }

    /** RFC 4998 §4.2 on a single-value node: the hash of that value. */
    private static byte[] reduced(byte[]... values) throws Exception {
        byte[][] sorted = values.clone();
        Arrays.sort(sorted, Arrays::compareUnsigned);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] value : sorted) {
            digest.update(value);
        }
        return digest.digest();
    }

    private static byte[] checkpointHash() throws Exception {
        return MessageDigest.getInstance("SHA-256").digest("checkpoint-1".getBytes("UTF-8"));
    }

    @Test
    @DisplayName("a record round-trips through DER and keeps its timestamps")
    void roundTrip() throws Exception {
        byte[] value = checkpointHash();
        ErsRecord record = ErsRecord.first(value, tokenOver(reduced(value)));

        ErsRecord read = ErsRecord.parse(record.der());

        assertEquals(1, read.chains().size());
        assertEquals(1, read.timestampsInCurrentChain());
        assertArrayEquals(value, read.chains().get(0).get(0).reducedHashtreeFirstNode().get(0),
                "the value the record covers did not survive encoding");
    }

    @Test
    @DisplayName("the verifier accepts a record whose token covers what the record says")
    void theLinksHold() throws Exception {
        byte[] value = checkpointHash();
        ErsRecord record = ErsRecord.first(value, tokenOver(reduced(value)));

        ErsVerifier.Report report = ErsVerifier.verify(record.der(), value);

        assertTrue(report.linksHold(), String.valueOf(report.asMap()));
        assertEquals(1, report.timestampsChecked());
    }

    @Test
    @DisplayName("a token over a DIFFERENT value does not hold — the control")
    void aTokenAboutSomethingElseIsCaught() throws Exception {
        // Without the imprint comparison, any token at all would satisfy any record: the record
        // would say "this value, at this time" with the time taken from a timestamp over
        // something entirely unrelated.
        byte[] value = checkpointHash();
        byte[] other = MessageDigest.getInstance("SHA-256").digest("other".getBytes("UTF-8"));
        ErsRecord record = ErsRecord.first(value, tokenOver(reduced(other)));

        ErsVerifier.Report report = ErsVerifier.verify(record.der(), value);

        assertFalse(report.linksHold(),
                "a timestamp over an unrelated value was accepted as covering this one");
        assertTrue(report.results().get(0).detail().contains("about a different value"),
                report.results().get(0).detail());
    }

    @Test
    @DisplayName("a record about SOMEBODY ELSE'S value is refused when we say what ours is")
    void aRecordAboutAnotherPartysDataIsRefused() throws Exception {
        // Internally perfect and about the wrong thing. Verifying without naming the expected
        // value cannot tell the difference, which is why the parameter exists.
        byte[] theirs = MessageDigest.getInstance("SHA-256").digest("theirs".getBytes("UTF-8"));
        byte[] ours = checkpointHash();
        ErsRecord record = ErsRecord.first(theirs, tokenOver(reduced(theirs)));

        assertTrue(ErsVerifier.verify(record.der(), null).linksHold(),
                "the fixture is not internally consistent, so this test measures nothing");
        ErsVerifier.Report report = ErsVerifier.verify(record.der(), ours);

        assertFalse(report.linksHold());
        assertTrue(report.results().stream()
                        .anyMatch(r -> r.detail().contains("about something else")),
                String.valueOf(report.asMap()));
    }

    @Test
    @DisplayName("a timestamp renewal lengthens the chain and covers the previous token")
    void timestampRenewalAppends() throws Exception {
        byte[] value = checkpointHash();
        ErsRecord first = ErsRecord.first(value, tokenOver(reduced(value)));
        byte[] previousToken = first.chains().get(0).get(0).timeStampDer();

        ErsRecord renewed = first.withTimestampRenewal(tokenOver(reduced(previousToken)));

        assertEquals(1, renewed.chains().size(), "a timestamp renewal started a new chain");
        assertEquals(2, renewed.timestampsInCurrentChain());
        ErsVerifier.Report report = ErsVerifier.verify(renewed.der(), value);
        assertTrue(report.linksHold(), String.valueOf(report.asMap()));
        assertEquals(2, report.timestampsChecked());
    }

    @Test
    @DisplayName("a hash-tree renewal starts a NEW chain, it does not extend the old one")
    void hashTreeRenewalStartsAChain() throws Exception {
        // The digest algorithm changed. Adding to the old chain would say the new algorithm had
        // been in use since the beginning.
        byte[] value = checkpointHash();
        byte[] rebuilt = MessageDigest.getInstance("SHA-256").digest("rebuilt".getBytes("UTF-8"));
        ErsRecord first = ErsRecord.first(value, tokenOver(reduced(value)));

        ErsRecord renewed = first.withHashTreeRenewal(rebuilt, tokenOver(reduced(rebuilt)));

        assertEquals(2, renewed.chains().size());
        assertEquals(1, renewed.timestampsInCurrentChain());
        assertTrue(ErsVerifier.verify(renewed.der(), value).linksHold());
    }

    @Test
    @DisplayName("the report says the TSA signature was not checked, before it says anything else")
    void theUncheckedPartIsSaidFirst() throws Exception {
        byte[] value = checkpointHash();
        ErsRecord record = ErsRecord.first(value, tokenOver(reduced(value)));

        ErsVerifier.Report report = ErsVerifier.verify(record.der(), value);
        List<String> keys = List.copyOf(report.asMap().keySet());

        assertEquals("limits", keys.get(0), keys.toString());
        assertEquals("notChecked", keys.get(1), keys.toString());
        assertTrue(report.notChecked().contains("SIGNATURE"), report.notChecked());
        assertTrue(report.notChecked().contains("no trust anchors"), report.notChecked());
    }

    @Test
    @DisplayName("the record's limits say its data object is a checkpoint, not a document")
    void theLimitsNameWhatItIsAbout() {
        assertTrue(ErsRecord.LIMITS.contains("CHECKPOINT HASH"), ErsRecord.LIMITS);
        assertTrue(ErsRecord.LIMITS.contains("not a document"), ErsRecord.LIMITS);
        assertTrue(ErsRecord.LIMITS.contains("does NOT"), ErsRecord.LIMITS);
    }

    @Test
    @DisplayName("garbage is a parse failure, not a record with no timestamps")
    void garbageIsNotAnEmptyRecord() {
        // "Nothing to check" and "could not read it" both end with linksHold=false, and only
        // one of them is a statement about the record.
        ErsVerifier.Report report = ErsVerifier.verify("not DER".getBytes(), null);

        assertFalse(report.linksHold());
        assertTrue(report.notChecked().contains("could not be parsed"), report.notChecked());
    }

    @Test
    @DisplayName("an empty component is refused at construction")
    void emptyComponentsAreRefused() throws Exception {
        byte[] value = checkpointHash();

        assertThrows(IllegalArgumentException.class,
                () -> ErsRecord.first(new byte[0], tokenOver(reduced(value))));
        assertThrows(IllegalArgumentException.class,
                () -> ErsRecord.first(value, new byte[0]));
    }
}
