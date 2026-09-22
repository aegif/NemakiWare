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

    /** A minimal RFC 4998-shaped record: version, then a hash list holding {@code digest}. */
    private static byte[] ersOver(byte[] digest, int version) throws Exception {
        ASN1EncodableVector record = new ASN1EncodableVector();
        record.add(new ASN1Integer(version));
        ASN1EncodableVector hashes = new ASN1EncodableVector();
        hashes.add(new DEROctetString(digest));
        record.add(new DERSequence(hashes));
        return new DERSequence(record).getEncoded();
    }

    /** A Merkle root the way the ledger writes one. */
    private static final String ROOT =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    /** An anchor target document carrying {@code root}. */
    private static byte[] anchorTarget(String root) {
        return ("{\"domain\":\"record-content\",\"merkleRoot\":\"" + root + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The data object is the anchor target's MERKLE ROOT, because the record's first Archive
     * Timestamp is the RFC 3161 token that anchored it and that token is over the root's bytes.
     * This fixture asked for {@code SHA-256(c14n)} — a value no token in this system covers —
     * which is why it agreed with a verifier that looked for the same wrong thing (R70,
     * 2026-09-22).
     */
    @Test
    @DisplayName("an evidence record covering the anchor target's Merkle root passes")
    void anEvidenceRecordOverTheAnchorTargetPasses() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json", anchorTarget(ROOT));
        entries.put(DIR + "anchors/ers.der",
                ersOver(java.util.HexFormat.of().parseHex(ROOT), 1));

        List<Outcome.Check> checks = LongTermErs.check(entries);

        assertEquals(Outcome.PASSED, named(checks, "ers parse").outcome(), checks + "");
        assertEquals(Outcome.PASSED, named(checks, "ers data object").outcome(), checks + "");
    }

    @Test
    @DisplayName("an evidence record covering something else FAILS, however well formed")
    void anErsOverSomethingElseFails() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchor-target-checkpoint.json", anchorTarget(ROOT));
        entries.put(DIR + "anchors/ers.der",
                ersOver(Canonical.sha256("something else".getBytes(StandardCharsets.UTF_8)), 1));

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
    void anUnknownErsVersionIsNotAMismatch() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchors/ers.der", ersOver(new byte[32], 2));

        Outcome.Check parse = named(LongTermErs.check(entries), "ers parse");

        assertEquals(Outcome.UNAVAILABLE, parse.outcome(),
                "a version this reader does not know has not been checked, and nothing about "
                        + "it is a finding");
        assertEquals("UNSUPPORTED_ERS_VERSION", parse.reasonCode());
    }

    @Test
    @DisplayName("a digest algorithm this version cannot compute is UNKNOWN, not a mismatch")
    void anUnknownDigestAlgorithmIsNotAMismatch() throws Exception {
        // A record declaring SHA3-512 (2.16.840.1.101.3.4.2.10).
        ASN1EncodableVector record = new ASN1EncodableVector();
        record.add(new ASN1Integer(1));
        record.add(new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.10"));
        record.add(new DERSequence(new DEROctetString(new byte[32])));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "anchors/ers.der", new DERSequence(record).getEncoded());

        Outcome.Check algorithms = named(LongTermErs.check(entries), "ers algorithms");

        assertEquals(Outcome.UNAVAILABLE, algorithms.outcome());
        assertEquals("UNKNOWN_ALGORITHM", algorithms.reasonCode(),
                "§14 is explicit: an algorithm this version cannot compute means the record "
                        + "has not been checked, and calling it a mismatch would report a "
                        + "defect nobody found");
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
