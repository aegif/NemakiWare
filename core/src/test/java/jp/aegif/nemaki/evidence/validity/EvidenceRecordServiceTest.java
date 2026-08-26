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

import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;

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
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Assembling an evidence record from an anchor this repository already has (P2-3).
 *
 * <h2>Nothing new is timestamped, so nothing new is claimed</h2>
 *
 * <p>The checkpoint hash IS the SHA-256 of the checkpoint's canonical bytes, and the RFC 3161
 * anchor over that checkpoint is a token whose imprint is exactly that value. So the record is
 * an assembly, not a new attestation — and every reason it cannot be assembled is a statement
 * about what this deployment has anchored, never about the records the checkpoint covers.
 */
class EvidenceRecordServiceTest {

    private static final String DOMAIN = "bedroom";
    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    private static TimeStampTokenGenerator tokenGenerator;

    @BeforeAll
    static void tsa() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair keyPair = kpg.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=Test TSA");
        org.bouncycastle.cert.X509v3CertificateBuilder builder =
                new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(subject,
                        BigInteger.ONE, new java.util.Date(System.currentTimeMillis() - 86_400_000L),
                        new java.util.Date(System.currentTimeMillis() + 86_400_000L), subject,
                        keyPair.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                        org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
        java.security.cert.X509Certificate certificate =
                new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(
                        builder.build(new org.bouncycastle.operator.jcajce
                                .JcaContentSignerBuilder("SHA256withRSA")
                                .build(keyPair.getPrivate())));
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

    private static byte[] tokenOver(byte[] imprint) throws Exception {
        TimeStampRequest request = new TimeStampRequestGenerator()
                .generate(new ASN1ObjectIdentifier(SHA256_OID), imprint);
        TimeStampResponse response = new TimeStampResponseGenerator(tokenGenerator,
                java.util.Set.of(SHA256_OID))
                .generate(request, BigInteger.ONE, new java.util.Date());
        return response.getTimeStampToken().getEncoded();
    }

    private static EvidenceCheckpoint checkpoint() {
        return EvidenceCheckpoint.of(DOMAIN, 0, 4, "mh1:root", null, "2026-08-26T00:00:00Z");
    }

    private static AnchorReceipt confirmedOver(String digest, byte[] token) throws Exception {
        java.lang.reflect.Method confirmed = AnchorReceipt.class.getDeclaredMethod("confirmed",
                AnchorKind.class, String.class, Instant.class, Instant.class, byte[].class,
                String.class, Map.class, AnchorKind.TimeSemantics.class);
        confirmed.setAccessible(true);
        return (AnchorReceipt) confirmed.invoke(null, AnchorKind.RFC3161_TSA, digest,
                Instant.now(), Instant.now(), token, "proof-digest", Map.of(),
                AnchorKind.TimeSemantics.BIDIRECTIONAL_WITHIN_ACCURACY);
    }

    private static EvidenceRecordService serviceWith(EvidenceCheckpoint checkpoint,
            List<AnchorReceipt> receipts) {
        EvidenceLedgerStore ledger = mock(EvidenceLedgerStore.class);
        when(ledger.isActive()).thenReturn(true);
        when(ledger.latestCheckpoint(anyString())).thenReturn(checkpoint);
        AnchorReceiptStore anchors = mock(AnchorReceiptStore.class);
        when(anchors.isActive()).thenReturn(true);
        when(anchors.forCheckpoint(anyString(), anyLong())).thenReturn(receipts);
        EvidenceRecordService service = new EvidenceRecordService();
        service.setLedgerStore(ledger);
        service.setAnchorReceiptStore(anchors);
        return service;
    }

    @Test
    @DisplayName("a confirmed RFC 3161 anchor becomes an evidence record that verifies")
    void anAnchorBecomesAnEvidenceRecord() throws Exception {
        EvidenceCheckpoint checkpoint = checkpoint();
        byte[] imprint = HexFormat.of().parseHex(checkpoint.checkpointHash());
        EvidenceRecordService service = serviceWith(checkpoint,
                List.of(confirmedOver(checkpoint.checkpointHash(), tokenOver(imprint))));

        EvidenceRecordService.Built built = service.latest(DOMAIN);

        assertTrue(built.present(), built.unavailable());
        ErsVerifier.Report report = ErsVerifier.verify(built.der(), imprint);
        assertTrue(report.linksHold(), String.valueOf(report.asMap()));
    }

    @Test
    @DisplayName("a token over a DIFFERENT value is refused, not wrapped")
    void aTokenAboutSomethingElseIsRefused() throws Exception {
        // The anchor layer takes a hex digest from its caller, and the merkle root is also to
        // hand. Wrapping a token over the wrong one produces a record that verifies internally
        // and is about a different thing — which is checkable, so it is believed.
        EvidenceCheckpoint checkpoint = checkpoint();
        byte[] elsewhere = MessageDigest.getInstance("SHA-256").digest("elsewhere".getBytes());
        EvidenceRecordService service = serviceWith(checkpoint,
                List.of(confirmedOver(HexFormat.of().formatHex(elsewhere),
                        tokenOver(elsewhere))));

        EvidenceRecordService.Built built = service.latest(DOMAIN);

        assertFalse(built.present());
        assertTrue(built.unavailable().contains("about a different value"), built.unavailable());
    }

    @Test
    @DisplayName("the FIELD says checkpoint and the TOKEN is over something else — refused")
    void theTokenIsWhatCounts() throws Exception {
        // The dangerous combination, and the one the previous version waved through: the
        // receipt's anchoredDigest is this repository's own note about what it asked for, and
        // the imprint is what the authority actually signed. A receipt whose field says
        // "checkpoint" while its proof covers the merkle root — a value also to hand at
        // anchoring time — assembles cleanly and is about something else.
        EvidenceCheckpoint checkpoint = checkpoint();
        byte[] merkleRoot = MessageDigest.getInstance("SHA-256").digest("mh1:root".getBytes());
        AnchorReceipt lying = confirmedOver(checkpoint.checkpointHash(), tokenOver(merkleRoot));

        EvidenceRecordService.Built built =
                serviceWith(checkpoint, List.of(lying)).latest(DOMAIN);

        assertFalse(built.present(),
                "a token signed over the merkle root was wrapped as a record about the "
                        + "checkpoint, because only the receipt's own field was read");
        assertTrue(built.unavailable().contains("SIGNED over"), built.unavailable());
        assertTrue(built.unavailable().contains("the token is the one that counts"),
                built.unavailable());
    }

    @Test
    @DisplayName("an unreadable token is not built from, and is not called wrong")
    void anUnreadableTokenIsNotAFinding() throws Exception {
        EvidenceCheckpoint checkpoint = checkpoint();
        AnchorReceipt garbage = confirmedOver(checkpoint.checkpointHash(),
                "this is not a token".getBytes());

        EvidenceRecordService.Built built =
                serviceWith(checkpoint, List.of(garbage)).latest(DOMAIN);

        assertFalse(built.present());
        assertTrue(built.unavailable().contains("could not be read"), built.unavailable());
    }

    @Test
    @DisplayName("what is shipped has been read back the way a receiver will read it")
    void whatIsShippedVerifies() throws Exception {
        // Assembling is cheap; shipping a record a standard tool rejects is not. The package
        // goes to another organisation, and "it came out of the exporter" is not a reason for
        // them to accept it.
        EvidenceCheckpoint checkpoint = checkpoint();
        byte[] imprint = HexFormat.of().parseHex(checkpoint.checkpointHash());
        EvidenceRecordService.Built built = serviceWith(checkpoint,
                List.of(confirmedOver(checkpoint.checkpointHash(), tokenOver(imprint))))
                .latest(DOMAIN);

        assertTrue(built.present(), built.unavailable());
        assertTrue(ErsVerifier.verify(built.der(), imprint).linksHold(),
                "the service shipped a record that does not verify");
    }

    @Test
    @DisplayName("a record that assembles and does NOT verify is not shipped")
    void anUnverifiableRecordIsNotShipped() throws Exception {
        // Reachable when the token's imprint algorithm is not the one the record declares:
        // every earlier check passes — the field matches, the imprint bytes match — and the
        // assembled record still fails §4.2 step 5, because the tree and the timestamp are not
        // about the same algorithm. Without the read-back this ships.
        EvidenceCheckpoint checkpoint = checkpoint();
        byte[] imprint = HexFormat.of().parseHex(checkpoint.checkpointHash());
        // SHA3-256: also 32 bytes, so the imprint BYTES still equal the checkpoint hash and
        // every earlier check passes. The record declares SHA-256, and §4.2 step 5 says the
        // timestamp's algorithm must be the tree's — so the assembled record does not verify.
        AnchorReceipt mislabelled = confirmedOver(checkpoint.checkpointHash(),
                tokenOverWithAlgorithm(imprint, "2.16.840.1.101.3.4.2.8"));

        EvidenceRecordService.Built built =
                serviceWith(checkpoint, List.of(mislabelled)).latest(DOMAIN);

        assertFalse(built.present(),
                "a record that does not verify was shipped to another organisation");
        assertTrue(built.unavailable().contains("did not verify"), built.unavailable());
        assertTrue(built.unavailable().contains("not a finding about the records covered"),
                built.unavailable());
    }

    /**
     * A token whose imprint ALGORITHM is {@code oid} while the bytes are the SHA-256 digest.
     *
     * <p>A TSA will issue this: the imprint length is not checked against the algorithm by
     * every implementation, and what matters here is that the record then declares SHA-256
     * (from the checkpoint) while the token says otherwise.
     */
    private static byte[] tokenOverWithAlgorithm(byte[] imprint, String oid) throws Exception {
        TimeStampRequest request = new TimeStampRequestGenerator()
                .generate(new ASN1ObjectIdentifier(oid), imprint);
        TimeStampResponse response = new TimeStampResponseGenerator(tokenGenerator,
                java.util.Set.of(SHA256_OID, oid))
                .generate(request, BigInteger.ONE, new java.util.Date());
        assertNotNull(response.getTimeStampToken(),
                "the test TSA refused to issue a token under " + oid + ", so this fixture does "
                        + "not produce the mismatch it is for: " + response.getStatusString());
        return response.getTimeStampToken().getEncoded();
    }

    @Test
    @DisplayName("no RFC 3161 anchor is an absence of an ANCHOR, not of evidence")
    void noAnchorIsNotAFindingAboutTheRecords() {
        EvidenceRecordService.Built built = serviceWith(checkpoint(), List.of()).latest(DOMAIN);

        assertFalse(built.present());
        assertTrue(built.unavailable().contains("no CONFIRMED RFC 3161 token"),
                built.unavailable());
        assertTrue(built.unavailable().contains("not about the records the checkpoint covers"),
                built.unavailable());
    }

    @Test
    @DisplayName("an OpenTimestamps receipt does not stand in for an RFC 3161 token")
    void otsDoesNotSubstitute() throws Exception {
        // Different evidence with different semantics. Accepting it would produce a record
        // whose timestamp is not the kind RFC 4998 defines, under a name that says it is.
        EvidenceCheckpoint checkpoint = checkpoint();
        java.lang.reflect.Method confirmed = AnchorReceipt.class.getDeclaredMethod("confirmed",
                AnchorKind.class, String.class, Instant.class, Instant.class, byte[].class,
                String.class, Map.class, AnchorKind.TimeSemantics.class);
        confirmed.setAccessible(true);
        AnchorReceipt ots = (AnchorReceipt) confirmed.invoke(null,
                AnchorKind.OPENTIMESTAMPS, checkpoint.checkpointHash(), Instant.now(),
                Instant.now(), new byte[] { 1, 2, 3 }, "d", Map.of(),
                AnchorKind.TimeSemantics.UPPER_BOUND_ONLY);

        EvidenceRecordService.Built built = serviceWith(checkpoint, List.of(ots)).latest(DOMAIN);

        assertFalse(built.present());
        assertTrue(built.unavailable().contains("OpenTimestamps"), built.unavailable());
    }

    @Test
    @DisplayName("a checkpoint whose own row was edited is not wrapped in a standard container")
    void anEditedCheckpointIsNotDressedUp() {
        EvidenceCheckpoint sealed = checkpoint();
        EvidenceCheckpoint edited = new EvidenceCheckpoint(sealed.domain(), sealed.fromSequence(),
                sealed.toSequence(), "mh1:SOMEONE-ELSES-ROOT", sealed.prevCheckpointHash(),
                sealed.createdAt(), sealed.checkpointHash());

        EvidenceRecordService.Built built = serviceWith(edited, List.of()).latest(DOMAIN);

        assertFalse(built.present());
        assertTrue(built.unavailable().contains("does not verify against its own fields"),
                built.unavailable());
    }

    @Test
    @DisplayName("the limits travel whether or not there is a record")
    void theLimitsAlwaysTravel() {
        Map<String, Object> absent = serviceWith(null, List.of()).latest(DOMAIN).asMap();

        assertEquals("limits", List.copyOf(absent.keySet()).get(0), absent.keySet().toString());
        assertTrue(String.valueOf(absent.get("limits")).contains("not a document"),
                String.valueOf(absent.get("limits")));
    }
}
