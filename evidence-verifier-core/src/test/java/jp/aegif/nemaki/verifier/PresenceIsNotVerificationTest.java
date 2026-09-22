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
     * A record whose digest is carried by a hash tree the token does not cover.
     *
     * <p>The second half of R72: the scan found {@code h} anywhere, so a first hash list saying
     * the right thing beside a timestamp about something else passed. §4.3 step 3 reduces the
     * tree and requires the result to be what the token covers.
     */
    @Test
    @DisplayName("a hash list holding the root beside a timestamp about something else FAILS")
    void aTreeTheTokenDoesNotCoverFails() throws Exception {
        // The golden's real ArchiveTimeStamp, given a reduced hash tree holding the root. The
        // token covers the root DIRECTLY, so §4.3's reduction — SHA-256 of the sorted
        // concatenation of a one-element list — cannot equal it.
        ASN1Sequence sequence = GoldenErs.timestampSequence();
        ASN1Sequence chain = ASN1Sequence.getInstance(sequence.getObjectAt(0));
        ASN1Sequence ats = ASN1Sequence.getInstance(chain.getObjectAt(0));
        ASN1EncodableVector rebuilt = new ASN1EncodableVector();
        for (int i = 0; i < ats.size(); i++) {
            rebuilt.add(ats.getObjectAt(i));
            if (i == 0) {
                rebuilt.add(new org.bouncycastle.asn1.DERTaggedObject(false, 2,
                        new DERSequence(new DERSequence(new DEROctetString(
                                java.util.HexFormat.of().parseHex(GoldenErs.root()))))));
            }
        }
        byte[] der = GoldenErs.withElement(2, new DERSequence(
                new DERSequence(new DERSequence(rebuilt))));

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
