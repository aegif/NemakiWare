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
import org.bouncycastle.asn1.cms.ContentInfo;
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
        assertTrue(report.results().get(1).detail().contains("renew the ones before it"),
                report.results().get(1).detail());
    }

    @Test
    @DisplayName("without H(d) under the new algorithm, the §5.3 link is NOT claimed to hold")
    void anUncheckableLinkIsNotClaimed() throws Exception {
        // This test previously asserted the opposite of its own name — that the record passed —
        // and it passed because the verifier compared the token with a value it took from the
        // record itself. Any timestamp filed beside the old chains satisfied that. "We cannot
        // check this link" is not "this link holds", and a report that says linksHold=true is
        // read as the second.
        ErsRecord first = firstRecord();
        byte[] hUnderNew = MessageDigest.getInstance("SHA-512").digest(dataObject());
        ErsRecord.HashTreeRenewalInputs inputs =
                first.inputsForHashTreeRenewal(hUnderNew, SHA512_OID);
        ErsRecord renewed = first.withHashTreeRenewal(inputs.hPrime(),
                tokenOver(inputs.imprint(), SHA512_OID), SHA512_OID);

        ErsVerifier.Report report = ErsVerifier.verify(renewed.der(), dataObjectHash());

        assertFalse(report.linksHold(),
                "a link nobody could check was reported as holding: " + report.asMap());
        // And it must be machine-readable as a gap, not as an accusation: a consumer reading
        // booleans is every consumer that is not reading the English.
        assertEquals(ErsVerifier.TimestampResult.Status.NOT_CHECKED,
                report.results().get(1).status(),
                "an unchecked link is indistinguishable from a broken one: " + report.asMap());
        assertEquals(1, report.timestampsNotChecked());
        assertEquals(1, report.timestampsChecked(),
                "the unchecked position was counted as checked, so the report says it looked "
                        + "at something it did not");
        assertTrue(report.results().get(1).detail().contains("not a finding that it is wrong"),
                report.results().get(1).detail());
    }

    @Test
    @DisplayName("the counts always agree with the results they summarise")
    void theCountsAgreeWithTheResults() throws Exception {
        // There are two producers of NOT_CHECKED: the uncheckable-link path, and check() itself
        // when a token cannot be parsed. Only the first incremented notChecked, so an unreadable
        // token would have given timestampsNotChecked=0 beside a NOT_CHECKED row -- readable as
        // A FINDING ABOUT THE RECORD next to prose saying it was not one. p2-3 §8 records fixing
        // that exact shape once already. The production code now counts both.
        //
        // BOTH producers are exercised, and reverting the fix turns this red. That was not
        // true when this test was written: three fixtures had failed to reach check()'s parse
        // failure and the comment here said "THIS TEST DOES NOT MEASURE THAT FIX". A fourth
        // construction does reach it (see recordWithUnparseableToken) -- and the stale sentence
        // survived 30 lines above the fixture that disproved it, which is the same shape as
        // every other correction that reached one place and not its neighbour.
        //
        // What is locked is the invariant: the totals and the rows cannot disagree. A consumer
        // reading numbers and a consumer reading rows must not get different reports.
        for (byte[] der : List.of(uncheckableLinkRecord(), recordWithUnparseableToken())) {
            assertCountsAgree(ErsVerifier.verify(der, dataObjectHash()));
        }
    }

    private static void assertCountsAgree(ErsVerifier.Report report) {

        assertTrue(report.results().stream()
                        .anyMatch(r -> r.status()
                                == ErsVerifier.TimestampResult.Status.NOT_CHECKED),
                "this fixture produced no NOT_CHECKED position, so it does not exercise the "
                        + "counting at all: " + report.asMap());
        long notCheckedRows = report.results().stream()
                .filter(r -> r.status() == ErsVerifier.TimestampResult.Status.NOT_CHECKED)
                .count();
        assertEquals(notCheckedRows, report.timestampsNotChecked(),
                "the counts disagree with the rows they summarise: " + report.asMap());
        assertEquals(report.results().size(),
                report.timestampsChecked() + report.timestampsNotChecked(),
                "positions were lost or double-counted: " + report.asMap());
    }

    /**
     * A single-position record whose token parses as a {@link ContentInfo} and is not a token.
     *
     * <p>This DOES reach {@code check()}'s parse failure — the second producer of NOT_CHECKED.
     *
     * <p><b>Two earlier attempts failed, and why each failed is the point.</b> Damaging a valid
     * token breaks the record's own ASN.1, so {@code verify} returns zero results and the
     * assertion goes vacuous. Building a bad token into a hash-tree RENEWAL fails differently:
     * the position never reaches {@code check()} because {@code expectedImprint} throws first,
     * and the NOT_CHECKED it produces comes from the uncheckable-link path that already counted
     * correctly. After the second failure this file recorded "reachability is unresolved" —
     * better than the "unreachable" it said after the first, and still wrong.
     *
     * <p>What works is a record with the {@code [0]} algorithm field omitted, which the RFC
     * allows: {@code ErsRecord} then calls {@code imprintAlgorithmOf}, which CATCHES every
     * exception and defaults to SHA-256. An unreadable token is therefore designed to survive
     * record parsing — <b>the leniency that made this path look unreachable is what makes it
     * reachable.</b>
     */
    private static byte[] recordWithUnparseableToken() throws Exception {
        byte[] notATimestamp = new ContentInfo(
                org.bouncycastle.asn1.cms.CMSObjectIdentifiers.signedData,
                new org.bouncycastle.asn1.DEROctetString(new byte[] { 1, 2, 3 }))
                .getEncoded(org.bouncycastle.asn1.ASN1Encoding.DER);
        return derWithoutAlgorithm(notATimestamp);
    }

    /** The §5.3 case: a renewal under a new algorithm with no H(d) to check the link against. */
    private byte[] uncheckableLinkRecord() {
        try {
            ErsRecord first = firstRecord();
            byte[] hUnderNew = MessageDigest.getInstance("SHA-512").digest(dataObject());
            ErsRecord.HashTreeRenewalInputs inputs =
                    first.inputsForHashTreeRenewal(hUnderNew, SHA512_OID);
            return first.withHashTreeRenewal(inputs.hPrime(),
                    tokenOver(inputs.imprint(), SHA512_OID), SHA512_OID).der();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the RFC's own reduced tree verifies — pht1=(h2abc,h1), pht2=(h3)")
    void theRfcsOwnExampleVerifies() throws Exception {
        // RFC 4998 §4.2 Figure 2, built exactly as written. A reduced tree stores the SIBLINGS
        // at each level; the computed parent BECOMES a member of the next list rather than
        // being stored in it. Requiring it to be stored — which this verifier did — rejects
        // every standard record and accepts only the shape this product was writing.
        byte[] h1 = dataObjectHash();
        byte[] h2abc = sha256("group 2".getBytes("UTF-8"));
        byte[] h3 = sha256("group 3".getBytes("UTF-8"));
        byte[] h12 = sha256(ErsRecord.sortedConcat(List.of(h2abc, h1)));
        byte[] h123 = sha256(ErsRecord.sortedConcat(List.of(h12, h3)));
        ErsRecord.ArchiveTimeStamp rfcShape = new ErsRecord.ArchiveTimeStamp(
                List.of(List.of(h2abc, h1), List.of(h3)), tokenOver(h123), SHA256_OID);

        ErsVerifier.Report report = ErsVerifier.verify(derOf(rfcShape), h1);

        assertTrue(report.linksHold(),
                "the RFC's own example was rejected: " + report.asMap());
    }

    @Test
    @DisplayName("a tree whose upper level is wrong is caught")
    void aTreeThatDoesNotJoinUpIsCaught() throws Exception {
        // Same shape as the RFC example, with a sibling nobody's tree contains — so the root
        // computed by walking it is not the one the token covers.
        byte[] h1 = dataObjectHash();
        byte[] h2abc = sha256("group 2".getBytes("UTF-8"));
        byte[] h3 = sha256("group 3".getBytes("UTF-8"));
        byte[] h12 = sha256(ErsRecord.sortedConcat(List.of(h2abc, h1)));
        byte[] realRoot = sha256(ErsRecord.sortedConcat(List.of(h12, h3)));
        ErsRecord.ArchiveTimeStamp broken = new ErsRecord.ArchiveTimeStamp(
                List.of(List.of(h2abc, h1), List.of(sha256("someone else".getBytes("UTF-8")))),
                tokenOver(realRoot), SHA256_OID);

        ErsVerifier.Report report = ErsVerifier.verify(derOf(broken), h1);

        assertFalse(report.linksHold(), "a tree that walks to another root verified");
        assertTrue(report.results().get(0).detail().contains("does not belong here"),
                report.results().get(0).detail());
    }

    @Test
    @DisplayName("the data object's hash must be in the FIRST list")
    void theDataObjectMustBeInTheFirstList() throws Exception {
        // §4.3 step 2. Without it a tree could walk to the right root while saying nothing
        // about the data object it claims to be for.
        byte[] h1 = dataObjectHash();
        byte[] other = sha256("not ours".getBytes("UTF-8"));
        byte[] root = sha256(ErsRecord.sortedConcat(List.of(other)));
        ErsRecord.ArchiveTimeStamp notOurs = new ErsRecord.ArchiveTimeStamp(
                List.of(List.of(other)), tokenOver(root), SHA256_OID);

        ErsVerifier.Report report = ErsVerifier.verify(derOf(notOurs), h1);

        assertFalse(report.linksHold());
        assertTrue(report.results().get(0).detail().contains("about something else"),
                report.results().get(0).detail());
    }

    @Test
    @DisplayName("a token taken under a different algorithm than the tree declares is caught")
    void anAlgorithmMismatchIsCaught() throws Exception {
        // §4.2 step 5: the timestamp's hash algorithm MUST be the tree's. A record declaring
        // SHA-512 with a SHA-256 token is not one whose tree was built under SHA-512.
        byte[] h = dataObjectHash();
        ErsRecord.ArchiveTimeStamp mismatched = new ErsRecord.ArchiveTimeStamp(
                List.of(), tokenOver(h), SHA512_OID);

        ErsVerifier.Report report = ErsVerifier.verify(derOf(mismatched), h);

        assertFalse(report.linksHold());
        assertTrue(report.results().get(0).detail().contains("not about the same"),
                report.results().get(0).detail());
    }

    /** A record around one hand-built Archive Timestamp, for the malformed cases. */
    private static byte[] derOf(ErsRecord.ArchiveTimeStamp ats) throws Exception {
        java.lang.reflect.Method encode = ErsRecord.class.getDeclaredMethod("encode",
                List.class, List.class);
        encode.setAccessible(true);
        return (byte[]) encode.invoke(null, List.of(List.of(ats)),
                List.of(SHA256_OID, SHA512_OID));
    }

    @Test
    @DisplayName("an absent digestAlgorithm is read from the TOKEN, not assumed SHA-256")
    void anAbsentAlgorithmComesFromTheToken() throws Exception {
        // §4.1: "If the optional field digestAlgorithm is not present, the digest algorithm of
        // the timestamp MUST be used." Assuming SHA-256 rejects every valid SHA-384/SHA-512
        // record — and the rejection reads as a broken record rather than an unsupported one.
        byte[] h512 = MessageDigest.getInstance("SHA-512").digest(dataObject());
        byte[] der = derWithoutAlgorithm(tokenOver(h512, SHA512_OID));

        ErsRecord parsed = ErsRecord.parse(der);

        assertEquals(SHA512_OID, parsed.chains().get(0).get(0).digestAlgorithmOid(),
                "the algorithm was assumed rather than read from the token");
        assertTrue(ErsVerifier.verify(der, h512).linksHold(),
                "a SHA-512 record with no explicit digestAlgorithm was rejected");
    }

    /** An ArchiveTimeStamp with the [0] field omitted, which the RFC allows. */
    private static byte[] derWithoutAlgorithm(byte[] tokenDer) throws Exception {
        org.bouncycastle.asn1.ASN1EncodableVector ats =
                new org.bouncycastle.asn1.ASN1EncodableVector();
        ats.add(org.bouncycastle.asn1.cms.ContentInfo.getInstance(
                org.bouncycastle.asn1.ASN1Primitive.fromByteArray(tokenDer)));
        org.bouncycastle.asn1.ASN1EncodableVector chain =
                new org.bouncycastle.asn1.ASN1EncodableVector();
        chain.add(new org.bouncycastle.asn1.DERSequence(ats));
        org.bouncycastle.asn1.ASN1EncodableVector sequence =
                new org.bouncycastle.asn1.ASN1EncodableVector();
        sequence.add(new org.bouncycastle.asn1.DERSequence(chain));
        org.bouncycastle.asn1.ASN1EncodableVector record =
                new org.bouncycastle.asn1.ASN1EncodableVector();
        record.add(new org.bouncycastle.asn1.ASN1Integer(1));
        record.add(new org.bouncycastle.asn1.DERSequence(
                new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                        new ASN1ObjectIdentifier(SHA512_OID))));
        record.add(new org.bouncycastle.asn1.DERSequence(sequence));
        return new org.bouncycastle.asn1.DERSequence(record)
                .getEncoded(org.bouncycastle.asn1.ASN1Encoding.DER);
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

    @Test
    @DisplayName("the two shipped limits strings describe the same artefact")
    void theFormatLimitsDescribeWhatIsActuallyBuilt() {
        // ErsFormat.LIMITS goes out to callers as renewalFormatLimits; ErsRecord.LIMITS travels
        // with every record. They disagreed: one called the checkpoint HASH the data object
        // (p2-3 §8 records that doing so produced records no standard tool could read) and said
        // "the reduced hash tree carries one node" (§8 rejected that alternative, and
        // ErsRecord.first() passes List.of() -- there is no tree). A caller reading one and a
        // reader holding the other got different descriptions of one artefact.
        assertFalse(ErsFormat.LIMITS.contains("DATA OBJECT is a checkpoint hash"),
                "the format limits call the checkpoint's HASH the data object: "
                        + ErsFormat.LIMITS);
        assertFalse(ErsFormat.LIMITS.contains("hash tree carries one node"),
                "the format limits describe a one-node hash tree that is not built: "
                        + ErsFormat.LIMITS);
        assertFalse(ErsFormat.LIMITS.contains("nothing generates a record automatically"),
                "the format limits deny generating records, in a sentence its own next clause "
                        + "contradicts: " + ErsFormat.LIMITS);
        assertTrue(ErsFormat.LIMITS.contains("canonical serialisation"),
                "the format limits no longer say what the data object IS: " + ErsFormat.LIMITS);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName(
            "'this build does not know that algorithm' is not a finding about the record")
    void aBuildLimitIsNotAFindingAgainstTheRecord() throws Exception {
        // The catch that turns a computation failure into DOES_NOT_MATCH takes two messages
        // that are about THE READER, not the record: "this build does not know digest algorithm
        // <oid>" and "this JVM does not provide <name>". DOES_NOT_MATCH says the timestamp
        // covers something else — a finding against a record that may be perfectly sound and
        // merely written with an algorithm this build has not been taught.
        //
        // Latent today (production builds SHA-256 records and algorithmNameFor knows
        // SHA-256/384/512) and certain on the first renewal into a new family, which is what
        // renewal is FOR. Driven through the classifier rather than through a forged record,
        // because building one in an unknown algorithm needs the very code that refuses.
        // Thrown by ErsRecord, not typed out here. The classifier matches on the MESSAGE, so a
        // hand-written string tests the test's own spelling: rewording ErsRecord.digest's
        // message would send the classification silently back to DOES_NOT_MATCH with the suite
        // still green. The javadoc claims "a test pins that they still are" — this is what
        // makes that true.
        RuntimeException unknownAlgorithm = assertThrows(IllegalArgumentException.class,
                () -> ErsRecord.digest("2.16.840.1.101.3.4.2.8", new byte[] {1, 2, 3}),
                "fixture check: this build now KNOWS that OID, so the throw under test no "
                        + "longer happens and nothing is being classified");
        assertTrue(isAboutThisBuild(unknownAlgorithm),
                "an unknown algorithm is still reported as the record disagreeing with itself: "
                        + unknownAlgorithm.getMessage());
        // The JVM-provider arm cannot be driven without removing a provider, so its string is
        // pinned against the source that writes it rather than against a throw.
        assertTrue(jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/evidence/validity/ErsRecord.java")
                        .contains("\"this JVM does not provide \""),
                "ErsRecord no longer writes the message the classifier looks for, so a JVM "
                        + "missing a provider is reported as a finding about the record");
        assertTrue(isAboutThisBuild(new IllegalStateException(
                        "this JVM does not provide SHA3-256")),
                "a missing JVM provider is still reported as a finding about the record");
        // The control: a real structural disagreement must NOT be excused as a build limit,
        // which would turn a genuine mismatch into "not checked".
        assertFalse(isAboutThisBuild(new IllegalStateException(
                        "the reduced hash tree does not contain the data object hash")),
                "a real disagreement was excused as a limit of this build, so a record that "
                        + "does not hold would be reported as unchecked");
        assertFalse(isAboutThisBuild(new IllegalArgumentException((String) null)),
                "a message-less failure was treated as a build limit");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName(
            "the verifier USES the classifier, not merely defines it")
    void theVerifierAppliesTheClassifier() throws Exception {
        // The test above drives the helper directly, so deleting the branch that CALLS it —
        // leaving the helper compiled and unused — kept the suite green and restored
        // DOES_NOT_MATCH. A helper nothing calls is not a protection.
        String catchBody = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/evidence/validity/ErsVerifier.java"));
        int applied = catchBody.split("isAboutThisBuild\\(", -1).length - 1;
        assertTrue(applied >= 2,
                "isAboutThisBuild appears " + applied + " time(s) in ErsVerifier: it is defined "
                        + "but never applied, so a limit of this build is reported as a finding "
                        + "against the record again");
        // Not "if (isAboutThisBuild(e))" -- that pinned the catch variable's NAME and the
        // spacing, so renaming `e` to `ex` would have failed correct code.
        java.util.regex.Matcher guard = java.util.regex.Pattern
                .compile("if\\s*\\(\\s*isAboutThisBuild\\s*\\(").matcher(catchBody);
        assertTrue(guard.find(),
                "the catch no longer asks whether the failure is about this build before "
                        + "calling it DOES_NOT_MATCH");
        int index = guard.start();
        String afterGuard = catchBody.substring(index, Math.min(catchBody.length(), index + 400));
        assertTrue(afterGuard.contains("Status.NOT_CHECKED"),
                "the guard fires but does not produce NOT_CHECKED: " + afterGuard);
    }

    private static boolean isAboutThisBuild(RuntimeException e) {
        try {
            java.lang.reflect.Method m = ErsVerifier.class.getDeclaredMethod(
                    "isAboutThisBuild", RuntimeException.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, e);
        } catch (ReflectiveOperationException ex) {
            throw new jp.aegif.nemaki.util.test.HarnessBroken("ErsVerifier.isAboutThisBuild is gone, so the two messages "
                    + "that are about this deployment are being reported as findings about the "
                    + "record again", ex);
        }
    }
}
