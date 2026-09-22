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
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.bouncycastle.tsp.ers.ERSEvidenceRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An evidence record this product writes is read by a reader that is not ours (RC condition 6).
 *
 * <h2>Why a foreign reader</h2>
 *
 * <p>{@code ErsVerifier} is ours and {@code LongTermErs} is ours, and two implementations of the
 * same misreading agree with each other — which is exactly how the RFC 3161 imprint went wrong
 * on both sides of this branch until a reviewer read the writer's encoder (2026-09-22). RFC 4998
 * exists so that someone else's tool can read the record; the only measurement that says so is
 * someone else's tool reading it.
 *
 * <p>BouncyCastle's {@code org.bouncycastle.tsp.ers} is an independent implementation of
 * RFC 4998 — it is on the classpath for the RFC 3161 work, it did not consult this product's
 * design, and it parses, walks and verifies an evidence record end to end. What it accepts here
 * is the DER {@link ErsRecord} produces, over the data object hash {@link EvidenceRecordService}
 * covers: the anchor target's MERKLE ROOT, as bytes.
 *
 * <h2>What this does NOT say</h2>
 *
 * <p><b>Not that a deployment with no TSA ships one.</b> These call {@link ErsRecord}
 * directly; the service builds a record only where an RFC 3161 anchor is configured and
 * confirmed. What the service asks for is measured separately below.
 *
 * <p>Not that every archival product accepts it — one reader is one reader. Not that RODA or
 * Archivematica ingest it (they are separate, environment-bound measurements, still open).
 * And not that the record proves anything about the records the checkpoint covers: an evidence
 * record is a statement about a checkpoint.
 */
class AStandardReaderAcceptsOurEvidenceRecordTest {

    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    private static TimeStampTokenGenerator tokenGenerator;
    private static X509Certificate tsaCertificate;

    @BeforeAll
    static void tsa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=Test TSA");
        Date from = new Date(System.currentTimeMillis() - 86_400_000L);
        Date to = new Date(System.currentTimeMillis() + 86_400_000L);
        org.bouncycastle.cert.X509v3CertificateBuilder certBuilder =
                new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                        subject, BigInteger.ONE, from, to, subject, keyPair.getPublic());
        certBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                        org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
        org.bouncycastle.operator.ContentSigner signer =
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                        .build(keyPair.getPrivate());
        tsaCertificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));
        tokenGenerator = new TimeStampTokenGenerator(
                new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                        .build("SHA256withRSA", keyPair.getPrivate(), tsaCertificate),
                new org.bouncycastle.operator.bc.BcDigestCalculatorProvider()
                        .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                new ASN1ObjectIdentifier(SHA256_OID))),
                new ASN1ObjectIdentifier("1.2.3.4.1"));
        tokenGenerator.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                List.of(tsaCertificate)));
    }

    /**
     * A token over {@code imprint}, from the TSA above — requested the way the product requests
     * them.
     *
     * <p>{@code setCertReq(true)} is not decoration: without it RFC 3161 REQUIRES the TSA to
     * omit its certificate, and the record then carries a timestamp nobody outside this
     * deployment can attribute. {@code Rfc3161AnchorTarget} sets it and calls the omission
     * "Pitfall 2"; the first version of this fixture did not, and the foreign reader found no
     * signing certificate — a fixture differing from the writer again (measured, 2026-09-22).
     */
    private static byte[] tokenOver(byte[] imprint) throws Exception {
        TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
        requests.setCertReq(true);
        TimeStampRequest request =
                requests.generate(new ASN1ObjectIdentifier(SHA256_OID), imprint);
        TimeStampResponse response = new TimeStampResponseGenerator(tokenGenerator,
                Set.of(SHA256_OID)).generate(request, BigInteger.ONE, new Date());
        assertNotNull(response.getTimeStampToken(),
                "the test TSA refused: " + response.getStatusString());
        return response.getTimeStampToken().getEncoded();
    }

    /** The Merkle root a checkpoint carries, as the ledger writes one. */
    private static final String MERKLE_ROOT =
            "3fdba35f04dc8c462986c992bcf875546257113072a909c162f7e470e581e278";

    /**
     * The data object an evidence record covers, as {@link EvidenceRecordService} chooses it:
     * the anchor target's MERKLE ROOT, as bytes. The root is already a digest, so it is
     * decoded, never hashed again — the same rule the RFC 3161 anchor follows, because the
     * record's first Archive Timestamp IS that anchor's token.
     */
    private static byte[] dataObjectHash() {
        return HexFormat.of().parseHex(MERKLE_ROOT);
    }

    private static byte[] ourRecord() throws Exception {
        byte[] h = dataObjectHash();
        return ErsRecord.first(h, tokenOver(ErsRecord.imprintForFirst(h))).der();
    }

    @Test
    @DisplayName("BouncyCastle's RFC 4998 reader parses the record this product writes")
    void aForeignReaderParsesIt() throws Exception {
        ERSEvidenceRecord read = new ERSEvidenceRecord(ourRecord(),
                new JcaDigestCalculatorProviderBuilder().build());

        assertNotNull(read.toASN1Structure(), "the foreign reader produced no structure");
        assertNotNull(read.getSigningCertificate(),
                "the record carries no signer certificate a foreign reader can find, so nobody "
                        + "outside this deployment can check who timestamped it");
    }

    @Test
    @DisplayName("that reader verifies the record's own timestamp signature")
    void aForeignReaderVerifiesTheSignature() throws Exception {
        ERSEvidenceRecord read = new ERSEvidenceRecord(ourRecord(),
                new JcaDigestCalculatorProviderBuilder().build());

        assertDoesNotThrow(() -> read.validate(new JcaSimpleSignerInfoVerifierBuilder()
                        .build(new X509CertificateHolder(tsaCertificate.getEncoded()))),
                "a reader that is not ours could not verify the timestamp inside a record we "
                        + "wrote, so the record does not travel");
    }

    /**
     * The claim that matters: the foreign reader agrees the record COVERS our data object.
     * {@code validatePresent} walks the reduced hash tree itself, by RFC 4998's rules — not
     * ours.
     */
    @Test
    @DisplayName("that reader agrees the record covers the Merkle root we anchored")
    void aForeignReaderAgreesItCoversOurDataObject() throws Exception {
        ERSEvidenceRecord read = new ERSEvidenceRecord(ourRecord(),
                new JcaDigestCalculatorProviderBuilder().build());

        assertDoesNotThrow(() -> read.validatePresent(false, dataObjectHash(), new Date()),
                "a reader that is not ours says this record does not cover the data object we "
                        + "built it over. Either the record is not RFC 4998, or what we think "
                        + "it covers is not what it covers");
    }

    @Test
    @DisplayName("that reader refuses a data object the record does not cover — the control")
    void aForeignReaderRefusesSomethingElse() throws Exception {
        ERSEvidenceRecord read = new ERSEvidenceRecord(ourRecord(),
                new JcaDigestCalculatorProviderBuilder().build());
        byte[] somethingElse = MessageDigest.getInstance("SHA-256").digest("not it".getBytes());

        assertThrows(Exception.class,
                () -> read.validatePresent(false, somethingElse, new Date()),
                "the foreign reader accepted a data object the record is not about, so its "
                        + "agreement above says nothing");
    }

    /**
     * Our own reader and the foreign one answer the same way about the same bytes. They are
     * allowed to differ in what they report; they are not allowed to differ about whether the
     * record covers the data object.
     */
    @Test
    @DisplayName("our reader and the foreign one agree, both ways")
    void bothReadersAgree() throws Exception {
        byte[] der = ourRecord();
        byte[] ours = dataObjectHash();
        byte[] other = MessageDigest.getInstance("SHA-256").digest("not it".getBytes());

        assertTrue(ErsVerifier.verify(der, ours).linksHold(),
                "our verifier rejects a record a foreign reader accepts");
        ERSEvidenceRecord read = new ERSEvidenceRecord(der,
                new JcaDigestCalculatorProviderBuilder().build());
        assertDoesNotThrow(() -> read.validatePresent(false, ours, new Date()));

        assertFalse(ErsVerifier.verify(der, other).linksHold(),
                "our verifier accepts a data object the record is not about");
        assertThrows(Exception.class, () -> read.validatePresent(false, other, new Date()));
    }

    /**
     * The service asks for a receipt over the value the anchor actually carries.
     *
     * <p>It asked for the CHECKPOINT HASH until 2026-09-22 while {@code AnchorService} anchors
     * {@code checkpoint.merkleRoot()}. The two are different by construction, so the comparison
     * never held: this deployment built NO evidence record at all, and wrote "the token is
     * about a different value" into a package that left the organisation. Two reviewers found
     * it independently; the product, the specification and the verifier now name one value
     * (residual R70, closed).
     */
    @Test
    @DisplayName("the service asks for the digest the anchor carries — the Merkle root")
    void theServiceAsksForWhatTheAnchorCarries() throws Exception {
        String anchorService = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/main/java/jp/aegif/nemaki/evidence/anchor/AnchorService.java"),
                        java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
        assertTrue(anchorService.contains("receiptFrom(target, checkpoint.merkleRoot())"),
                "AnchorService no longer anchors the Merkle root; the evidence record's data "
                        + "object follows what is anchored, so both have to move together");

        String recordService = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/main/java/jp/aegif/nemaki/evidence/validity/EvidenceRecordService.java"),
                        java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
        assertTrue(recordService.contains("HexFormat.of().parseHex(checkpoint.merkleRoot())"),
                "EvidenceRecordService no longer builds the record over the Merkle root's "
                        + "bytes. Whatever else it uses, no token in this system covers it, so "
                        + "no record would ever be built");
        assertTrue(recordService.contains(
                        "checkpoint.merkleRoot().equalsIgnoreCase(token.anchoredDigest())"),
                "the service no longer requires the receipt to be over the Merkle root, so a "
                        + "receipt about another value could produce a record about neither");
        assertFalse(recordService.contains("parseHex(checkpoint.checkpointHash())"),
                "the checkpoint hash is back as the data object. It is not what is anchored, "
                        + "and asking for it is what made this deployment ship no record at all");
    }

    /**
     * The SERVICE chooses the data object the same way this fixture does: the checkpoint hash,
     * decoded. Read from the writer's source, because the fixture above cannot testify about
     * the code that runs in production — if the service hashed the hex text instead, every
     * test here would still pass over its own consistent world (the shape the RFC 3161 imprint
     * had on both sides).
     */
    @Test
    @DisplayName("the service builds the record over the Merkle root's bytes, not over its text")
    void theDataObjectIsTheMerkleRootsBytes() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/main/java/jp/aegif/nemaki/evidence/validity/EvidenceRecordService.java"),
                        java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "");
        assertTrue(source.contains("HexFormat.of().parseHex(checkpoint.merkleRoot())"),
                "EvidenceRecordService no longer decodes the Merkle root into the data object. "
                        + "A hash of its TEXT is a different value, and a foreign RFC 4998 "
                        + "reader would say the record is not about this checkpoint");
        assertTrue(source.contains("java.util.Arrays.equals(imprint, dataObjectHash)")
                        || source.contains("Arrays.equals(imprint, dataObjectHash)"),
                "the service no longer compares the TOKEN's imprint with the data object it is "
                        + "building over, so a receipt whose field says one thing and whose "
                        + "proof is over another would produce a record about neither");
    }

    /**
     * The data object hash is the Merkle root's BYTES — decoded, not hashed again. The same
     * misreading that went wrong for RFC 3161 imprints would go wrong here, and this is the
     * side a fixture cannot paper over: the foreign reader decides.
     */
    @Test
    @DisplayName("a record built over the hash of the hex text is refused by the foreign reader")
    void theDoubleHashedDataObjectIsRefused() throws Exception {
        byte[] doubled = MessageDigest.getInstance("SHA-256")
                .digest(MERKLE_ROOT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // The two readings must not coincide, or what follows would hold under either.
        assertFalse(java.util.Arrays.equals(doubled, dataObjectHash()),
                "hashing the hex TEXT produced the same bytes as decoding it, so this fixture "
                        + "cannot tell the correct reading from the withdrawn one");
        byte[] der = ErsRecord.first(doubled, tokenOver(ErsRecord.imprintForFirst(doubled))).der();

        ERSEvidenceRecord read = new ERSEvidenceRecord(der,
                new JcaDigestCalculatorProviderBuilder().build());
        // It covers what it was built over — and that is NOT the root's bytes.
        assertThrows(Exception.class,
                () -> read.validatePresent(false, dataObjectHash(), new Date()),
                "a record built over the hash of the hex TEXT was read as covering the root's "
                        + "bytes, so the two readings cannot be told apart");
    }
}
