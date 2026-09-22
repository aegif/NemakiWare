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

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 and P5: what a verifier that can SEE material but cannot READ it is allowed to say.
 *
 * <p>Almost nothing. The temptation in both profiles is to treat presence as verification — an
 * {@code .ots} file is there, an {@code ers.der} is there — and both would be a claim no check
 * performed. These tests pin the refusals.
 */
class PresenceIsNotVerificationTest {

    private static final String DIR = "sip/metadata/other/nemaki-evidence/";

    private static Outcome.Check named(List<Outcome.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static byte[] otsProof() {
        // The OpenTimestamps magic begins with a NUL. Written as an ESCAPE: a literal NUL
        // byte makes git treat the whole file as binary and its diffs disappear —
        // SourceTreeNulByteTest caught exactly that here.
        byte[] magic = "\u0000OpenTimestamps".getBytes(StandardCharsets.ISO_8859_1);
        byte[] proof = new byte[magic.length + 8];
        System.arraycopy(magic, 0, proof, 0, magic.length);
        return proof;
    }

    @Test
    @DisplayName("an OTS proof that is present but unreadable is UNKNOWN, never a time claim")
    void anOtsProofPresentIsNotATimeClaim() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchors/ots.ots", otsProof());

        List<Outcome.Check> checks = AnchoredOts.check(entries);

        assertEquals(Outcome.UNAVAILABLE, named(checks, "ots attestation").outcome());
        assertEquals("NO_BLOCK_HEADER_SOURCE", named(checks, "ots attestation").reasonCode(),
                "an attestation is evidence only against a block header, and this version is "
                        + "given no header source and opens no sockets");
        assertEquals(Outcome.Verdict.INDETERMINATE,
                Outcome.combine(checks, checks),
                "'the proof is present' is not 'the time is established'");
    }

    @Test
    @DisplayName("a file presented as an OTS proof and not one is a FINDING")
    void aFileThatIsNotAnOtsProofIsAFinding() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchors/ots.ots", "not a proof".getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.FAILED, named(AnchoredOts.check(entries), "ots parse").outcome(),
                "the package presents the file AS a proof; that it is not one is a fact about "
                        + "the package");
    }

    @Test
    @DisplayName("no OTS proof is absent, not failed")
    void noOtsProofIsAbsent() {
        List<Outcome.Check> checks = AnchoredOts.check(new LinkedHashMap<>());
        for (String name : AnchoredOts.REQUIRED) {
            assertEquals(Outcome.NOT_PRESENT, named(checks, name).outcome(), name + ": " + checks);
        }
    }

    /**
     * The data object is the anchor target's MERKLE ROOT, because the record's first Archive
     * Timestamp IS the RFC 3161 token that anchored it, and that token is over the root's bytes.
     *
     * <p>The fixture is the record this product writes ({@link GoldenErs}), not a shape built
     * from the verifier's own reading. The earlier one was a two-element sequence with the
     * digest at the top level — a record {@code ErsRecord.parse} rejects outright — and it
     * passed because the check scanned the DER for any 32-byte {@code OCTET STRING}. A real
     * record keeps the root inside the token, so the check would have failed every package this
     * product ships (both reviews, fifth round).
     */
    @Test
    @DisplayName("the evidence record this product writes passes, over the root it anchored")
    void anEvidenceRecordOverTheAnchorTargetPasses() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", GoldenErs.der());

        List<Outcome.Check> checks = LongTermErs.check(entries);

        for (String name : LongTermErs.REQUIRED) {
            assertEquals(Outcome.PASSED, named(checks, name).outcome(),
                    name + " did not pass over a record this product really wrote. Refusing our "
                            + "own package is the same defect as passing someone else's: "
                            + checks);
        }
    }

    @Test
    @DisplayName("an evidence record covering something else FAILS, however well formed")
    void anErsOverSomethingElseFails() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        // The same real record, beside a package that says a different root was anchored.
        entries.put(DIR + "anchor-target-checkpoint.json", GoldenErs.anchorTarget(
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"));
        entries.put(DIR + "anchors/ers.der", GoldenErs.der());

        Outcome.Check dataObject = named(LongTermErs.check(entries), "ers data object");

        assertEquals(Outcome.FAILED, dataObject.outcome(),
                "an evidence record beside a document is read by almost everyone as a long-term "
                        + "signature ON THAT DOCUMENT. One covering something else is not this "
                        + "package's evidence");
        assertTrue(dataObject.detail().contains("NOT a signature on the document"),
                dataObject.detail());
    }

    @Test
    @DisplayName("an ERS version this reader does not know is UNSUPPORTED, not a mismatch")
    void anUnknownErsVersionIsNotAMismatch() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchors/ers.der", GoldenErs.withElement(0, new ASN1Integer(2)));

        Outcome.Check parse = named(LongTermErs.check(entries), "ers parse");

        assertEquals(Outcome.UNAVAILABLE, parse.outcome(),
                "a version this reader does not know has not been checked, and nothing about "
                        + "it is a finding");
        assertEquals("UNSUPPORTED_ERS_VERSION", parse.reasonCode());
    }

    @Test
    @DisplayName("a digest algorithm this version cannot compute is UNKNOWN, not a mismatch")
    void anUnknownDigestAlgorithmIsNotAMismatch() {
        // The real record, declaring SHA3-512 (2.16.840.1.101.3.4.2.10) where it declared
        // SHA-256. Everything else is exactly what the writer produced.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", GoldenErs.withElement(1, new DERSequence(
                new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                        new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                                "2.16.840.1.101.3.4.2.10")))));

        Outcome.Check algorithms = named(LongTermErs.check(entries), "ers algorithms");

        assertEquals(Outcome.UNAVAILABLE, algorithms.outcome(), algorithms + "");
        assertEquals("UNKNOWN_ALGORITHM", algorithms.reasonCode(),
                "§14 is explicit: an algorithm this version cannot compute means the record "
                        + "has not been checked, and calling it a mismatch would report a "
                        + "defect nobody found");
    }

    /**
     * A DER that declares version 1 and carries the wanted digest somewhere is not a record.
     *
     * <p>This exact shape — {@code SEQUENCE { INTEGER 1, SEQUENCE { OCTET STRING h } }} — WAS
     * the fixture this class used, and it passed all three checks, because the check scanned
     * the whole DER for any 32-byte {@code OCTET STRING}. {@code ErsRecord.parse} rejects it
     * outright: an EvidenceRecord has at least version, digestAlgorithms and an
     * archiveTimeStampSequence, and every Archive Timestamp carries an RFC 3161 token (residual
     * R72). Nothing in it fixes a time, which is the entire point of an evidence record.
     */
    @Test
    @DisplayName("version 1 with the digest lying loose in the DER is not an evidence record")
    void aDerThatMerelyCarriesTheDigestIsNotARecord() throws Exception {
        ASN1EncodableVector record = new ASN1EncodableVector();
        record.add(new ASN1Integer(1));
        ASN1EncodableVector hashes = new ASN1EncodableVector();
        hashes.add(new DEROctetString(
                java.util.HexFormat.of().parseHex(GoldenErs.root())));
        record.add(new DERSequence(hashes));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", new DERSequence(record).getEncoded());

        List<Outcome.Check> checks = LongTermErs.check(entries);

        assertEquals(Outcome.FAILED, named(checks, "ers parse").outcome(),
                "a two-element SEQUENCE with the wanted digest inside it was accepted as an "
                        + "RFC 4998 evidence record: " + checks);
        for (String name : List.of("ers data object", "ers chain", "ers algorithms")) {
            assertEquals(Outcome.NOT_PRESENT, named(checks, name).outcome(),
                    name + " answered about a record that did not parse: " + checks);
        }
    }

    /**
     * The conformant alternative: a ONE-NODE tree holding the root, under the same token.
     *
     * <p>RFC 4998 §4.2 hashes a data group's document hashes only "for each data group
     * containing MORE THAN ONE document", so a list of one is its own node hash — which
     * BouncyCastle's {@code ERSUtil.computeNodeHash} implements by returning {@code values[0]}.
     * This verifier hashed unconditionally, so it refused every record any standard tool
     * builds with a reduced tree (subagent, sixth review, P1). The design document also
     * rejected this shape for a reason that is not true: it said a one-node tree would need a
     * NEW token over {@code H(H(root))}. It does not.
     */
    @Test
    @DisplayName("a one-node tree holding the root verifies under the token we already had")
    void aOneNodeTreeOverTheRootPasses() throws Exception {
        byte[] der = withTree(new DERSequence(new DERSequence(new DEROctetString(
                java.util.HexFormat.of().parseHex(GoldenErs.root())))));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", der);

        List<Outcome.Check> checks = LongTermErs.check(entries);
        for (String name : LongTermErs.REQUIRED) {
            assertEquals(Outcome.PASSED, named(checks, name).outcome(),
                    name + " refused a record whose one-node tree reduces to the value its own "
                            + "token covers. Hashing a one-element list produces a root no "
                            + "conformant record carries: " + checks);
        }
    }

    /**
     * A record whose digest is carried by a hash tree the token does not cover.
     *
     * <p>TWO elements in the first list, so §4.2's "more than one document" rule applies and the
     * reduction really is a hash — of something the token is not about. A one-element list
     * would reduce to the value itself and legitimately pass (above).
     */
    @Test
    @DisplayName("a hash list the token does not cover FAILS")
    void aTreeTheTokenDoesNotCoverFails() throws Exception {
        byte[] other = new byte[32];
        java.util.Arrays.fill(other, (byte) 0x5a);
        byte[] der = withTree(new DERSequence(new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] {
                new DEROctetString(java.util.HexFormat.of().parseHex(GoldenErs.root())),
                new DEROctetString(other) })));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", der);

        List<Outcome.Check> checks = LongTermErs.check(entries);
        assertEquals(Outcome.PASSED, named(checks, "ers parse").outcome(),
                "this fixture stopped parsing, so what follows measures the parse and not the "
                        + "data object: " + checks);
        assertEquals(Outcome.FAILED, named(checks, "ers data object").outcome(),
                "a hash list holding the right value beside a timestamp that does not cover "
                        + "its reduction was accepted: " + checks);
    }

    /**
     * {@code digestAlgorithm [0]} is OPTIONAL — RFC 4998 §4.2.
     *
     * <p>"If the optional field digestAlgorithm is not present, the digest algorithm of the
     * timestamp MUST be used." BouncyCastle's own generator omits it for a single data object,
     * and this product's own reader ({@code ErsRecord.parse}) already implements the fallback —
     * so requiring it made two readers of one format answer the same bytes differently, and
     * every record a standard tool builds came back FAILED (subagent, sixth review, P1).
     */
    @Test
    @DisplayName("an Archive Timestamp with no digestAlgorithm falls back to the token's")
    void anAbsentDigestAlgorithmIsReadFromTheToken() throws Exception {
        ASN1Sequence sequence = GoldenErs.timestampSequence();
        ASN1Sequence chain = ASN1Sequence.getInstance(sequence.getObjectAt(0));
        ASN1Sequence ats = ASN1Sequence.getInstance(chain.getObjectAt(0));
        ASN1EncodableVector withoutTag = new ASN1EncodableVector();
        for (int i = 0; i < ats.size(); i++) {
            if (!(ats.getObjectAt(i) instanceof org.bouncycastle.asn1.ASN1TaggedObject)) {
                withoutTag.add(ats.getObjectAt(i));
            }
        }
        assertEquals(ats.size() - 1, withoutTag.size(),
                "this fixture removed no tagged field, so it is the same record as the golden "
                        + "and measures nothing");
        byte[] der = GoldenErs.withElement(2,
                new DERSequence(new DERSequence(new DERSequence(withoutTag))));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", der);

        List<Outcome.Check> checks = LongTermErs.check(entries);
        for (String name : LongTermErs.REQUIRED) {
            assertEquals(Outcome.PASSED, named(checks, name).outcome(),
                    name + " refused a record whose digestAlgorithm is absent. RFC 4998 makes "
                            + "that field optional and BouncyCastle's generator omits it: "
                            + checks);
        }
    }

    /**
     * A new chain's first timestamp has to cover its OWN tree.
     *
     * <p>§5.3 membership alone left the newest chain's time claim tied to nothing: a tree naming
     * the right value beside a timestamp about anything at all passed, and the whole profile
     * composed to VERIFIED (subagent, sixth review, P2).
     */
    @Test
    @DisplayName("a second chain whose timestamp is about something else FAILS")
    void aSecondChainMustCoverItsOwnTree() throws Exception {
        ASN1Sequence sequence = GoldenErs.timestampSequence();
        ASN1Sequence chain = ASN1Sequence.getInstance(sequence.getObjectAt(0));
        ASN1Sequence ats = ASN1Sequence.getInstance(chain.getObjectAt(0));

        // ha over chain 0 alone, and h' = H(sorted(root, ha)) — what §5.3 puts in the new
        // chain's first list. The token, though, is the golden's, which covers the root.
        byte[] previous = new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] {
                sequence.getObjectAt(0) }).getEncoded("DER");
        byte[] ha = java.security.MessageDigest.getInstance("SHA-256").digest(previous);
        byte[] root = java.util.HexFormat.of().parseHex(GoldenErs.root());
        byte[] hPrime = java.security.MessageDigest.getInstance("SHA-256")
                .digest(LongTermErs.sortedConcat(List.of(root, ha)));

        ASN1EncodableVector renewal = new ASN1EncodableVector();
        for (int i = 0; i < ats.size(); i++) {
            renewal.add(ats.getObjectAt(i));
            if (i == 0) {
                renewal.add(new org.bouncycastle.asn1.DERTaggedObject(false, 2,
                        new DERSequence(new DERSequence(new DEROctetString(hPrime)))));
            }
        }
        byte[] der = GoldenErs.withElement(2, new DERSequence(
                new org.bouncycastle.asn1.ASN1Encodable[] {
                        sequence.getObjectAt(0), new DERSequence(new DERSequence(renewal)) }));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", der);

        List<Outcome.Check> checks = LongTermErs.check(entries);
        assertEquals(Outcome.PASSED, named(checks, "ers parse").outcome(),
                "this fixture stopped parsing: " + checks);
        assertEquals(Outcome.FAILED, named(checks, "ers chain").outcome(),
                "a chain whose first hash list commits to the chains before it, beside a "
                        + "timestamp that is about something else, was accepted. Membership "
                        + "alone ties the newest time claim to nothing: " + checks);
    }

    /**
     * A §5.2 renewal MAY carry a reduced hash tree, and then the tree decides.
     *
     * <p>"The new Archive Timestamp MAY not contain a reducedHashtree field, if the timestamp
     * only simply covers the previous timestamp" — may not, not must not. Comparing the
     * imprint directly in both cases was wrong in both directions at once: a renewal of the
     * legitimate tree-bearing shape was refused, and a renewal carrying ANY tree beside a token
     * over the previous one passed (Codex, sixth review, P1).
     *
     * <p>The renewal's token here covers EXACTLY {@code H(previous timeStamp DER)}, so the
     * imprint comparison is satisfied and only the tree can decide. A fixture whose imprint was
     * also wrong would be refused by that other arm and would measure nothing — which is how
     * the first version of this lock stayed green under the sabotage (measured).
     */
    @Test
    @DisplayName("a renewal carrying a tree the token does not cover FAILS")
    void aRenewalWhoseTreeTheTokenDoesNotCoverFails() throws Exception {
        ASN1Sequence chain = ASN1Sequence.getInstance(GoldenErs.timestampSequence()
                .getObjectAt(0));
        ASN1Sequence ats = ASN1Sequence.getInstance(chain.getObjectAt(0));
        byte[] previousTokenDer = null;
        for (int i = 0; i < ats.size(); i++) {
            if (!(ats.getObjectAt(i) instanceof org.bouncycastle.asn1.ASN1TaggedObject)) {
                previousTokenDer = ASN1Sequence.getInstance(ats.getObjectAt(i))
                        .getEncoded("DER");
            }
        }
        assertTrue(previousTokenDer != null, "the golden's own timestamp token was not found");
        byte[] renewalImprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(previousTokenDer);

        // A tree about something the renewal does not renew, beside a token that DOES cover
        // what §5.2 requires.
        byte[] unrelated = new byte[32];
        java.util.Arrays.fill(unrelated, (byte) 0x33);
        ASN1EncodableVector renewal = new ASN1EncodableVector();
        renewal.add(new org.bouncycastle.asn1.DERTaggedObject(false, 0,
                new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                        new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                                "2.16.840.1.101.3.4.2.1"))));
        // The list DOES hold what §5.2 requires, beside one more value — so membership holds
        // and only the REDUCTION can decide. Two elements, because a list of one reduces to
        // itself (§4.2) and would legitimately match.
        renewal.add(new org.bouncycastle.asn1.DERTaggedObject(false, 2,
                new DERSequence(new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] {
                        new DEROctetString(renewalImprint), new DEROctetString(unrelated) }))));
        renewal.add(ASN1Sequence.getInstance(org.bouncycastle.asn1.ASN1Primitive.fromByteArray(
                TestAuthority.tokenOver(renewalImprint))));
        byte[] der = GoldenErs.withElement(2, new DERSequence(new DERSequence(
                new org.bouncycastle.asn1.ASN1Encodable[] {
                        ats, new DERSequence(renewal) })));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", der);

        List<Outcome.Check> checks = LongTermErs.check(entries);
        assertEquals(Outcome.PASSED, named(checks, "ers parse").outcome(),
                "this fixture stopped parsing: " + checks);
        assertEquals(Outcome.FAILED, named(checks, "ers chain").outcome(),
                "a renewal carrying a hash tree about something else was accepted because only "
                        + "its token's imprint was compared — and that imprint is right: "
                        + checks);
        assertTrue(named(checks, "ers chain").detail().contains("reduces to"),
                "the finding came from the imprint comparison, not from the tree, so the tree "
                        + "branch is still unmeasured: " + named(checks, "ers chain").detail());
    }

    @Test
    @DisplayName("an Archive Timestamp carrying a field RFC 4998 does not define is a finding")
    void anUnknownTaggedFieldIsAFinding() throws Exception {
        ASN1Sequence sequence = GoldenErs.timestampSequence();
        ASN1Sequence ats = ASN1Sequence.getInstance(
                ASN1Sequence.getInstance(sequence.getObjectAt(0)).getObjectAt(0));
        ASN1EncodableVector withExtra = new ASN1EncodableVector();
        for (int i = 0; i < ats.size(); i++) {
            withExtra.add(ats.getObjectAt(i));
            if (i == 0) {
                withExtra.add(new org.bouncycastle.asn1.DERTaggedObject(false, 3,
                        new DEROctetString(new byte[] { 1, 2, 3 })));
            }
        }
        byte[] der = GoldenErs.withElement(2,
                new DERSequence(new DERSequence(new DERSequence(withExtra))));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", der);

        assertEquals(Outcome.FAILED,
                named(LongTermErs.check(entries), "ers parse").outcome(),
                "a record carrying a field this reader does not understand passed every check. "
                        + "BouncyCastle's ASN.1 reader refuses it, so the two disagree about "
                        + "the same bytes");
    }

    /** The golden record's Archive Timestamp, given {@code tree} as its reducedHashtree. */
    private static byte[] withTree(DERSequence tree) throws Exception {
        ASN1Sequence sequence = GoldenErs.timestampSequence();
        ASN1Sequence chain = ASN1Sequence.getInstance(sequence.getObjectAt(0));
        ASN1Sequence ats = ASN1Sequence.getInstance(chain.getObjectAt(0));
        ASN1EncodableVector rebuilt = new ASN1EncodableVector();
        for (int i = 0; i < ats.size(); i++) {
            rebuilt.add(ats.getObjectAt(i));
            if (i == 0) {
                rebuilt.add(new org.bouncycastle.asn1.DERTaggedObject(false, 2, tree));
            }
        }
        return GoldenErs.withElement(2, new DERSequence(new DERSequence(new DERSequence(rebuilt))));
    }

    /**
     * A renewal that covers nothing is a finding, and {@code ers chain} is required.
     *
     * <p>§14 lists {@code ERS_CHAIN} and {@code LongTermErs.REQUIRED} did not, so a record whose
     * second Archive Timestamp was about anything at all composed to {@code VERIFIED} at P5
     * (residual R72).
     *
     * <p>The fixture is the golden record's own Archive Timestamp, repeated. That is a shape
     * this product does not write — renewals are evaluated and never executed — so it is built
     * here from real bytes rather than taken from a writer that has none. What makes it a
     * finding is §5.2: the second token must cover {@code H(the first timeStamp field's DER)},
     * and a copy of the first covers the Merkle root instead.
     */
    @Test
    @DisplayName("a renewal that does not cover what it renews FAILS, and the check is required")
    void aRenewalThatCoversNothingFails() throws Exception {
        assertTrue(LongTermErs.REQUIRED.contains("ers chain"),
                "ers chain is reported but not required, so a record whose renewals cover "
                        + "nothing still composes to VERIFIED at LONG_TERM_ERS_V1");

        ASN1Sequence sequence = GoldenErs.timestampSequence();
        ASN1Sequence chain = ASN1Sequence.getInstance(sequence.getObjectAt(0));
        ASN1EncodableVector twice = new ASN1EncodableVector();
        twice.add(chain.getObjectAt(0));
        twice.add(chain.getObjectAt(0));
        byte[] der = GoldenErs.withElement(2, new DERSequence(new DERSequence(twice)));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json",
                GoldenErs.anchorTarget(GoldenErs.root()));
        entries.put(DIR + "anchors/ers.der", der);

        List<Outcome.Check> checks = LongTermErs.check(entries);
        assertEquals(Outcome.PASSED, named(checks, "ers parse").outcome(),
                "this fixture stopped parsing, so what follows measures the parse: " + checks);
        assertEquals(Outcome.PASSED, named(checks, "ers data object").outcome(),
                "the FIRST timestamp still covers the root, so only the chain is at issue: "
                        + checks);
        assertEquals(Outcome.FAILED, named(checks, "ers chain").outcome(),
                "a second Archive Timestamp that covers the data object instead of the "
                        + "timestamp before it was accepted as a renewal. §5.2 makes a renewal "
                        + "cover H(previous ContentInfo DER); anything else is a timestamp "
                        + "filed beside the record: " + checks);
    }

    @Test
    @DisplayName("a file presented as an evidence record and not one is a FINDING")
    void aFileThatIsNotAnErsIsAFinding() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchors/ers.der", "not a record".getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.FAILED, named(LongTermErs.check(entries), "ers parse").outcome());
    }

    @Test
    @DisplayName("no evidence record is absent, not failed")
    void noErsIsAbsent() {
        List<Outcome.Check> checks = LongTermErs.check(new LinkedHashMap<>());
        for (String name : LongTermErs.REQUIRED) {
            assertEquals(Outcome.NOT_PRESENT, named(checks, name).outcome(), name + ": " + checks);
        }
    }
}
