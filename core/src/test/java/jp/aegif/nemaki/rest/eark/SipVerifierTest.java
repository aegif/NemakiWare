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
package jp.aegif.nemaki.rest.eark;

import jp.aegif.nemaki.evidence.MerkleTree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A verifier that says "verified" has to be able to say "not verified".
 *
 * <h2>What is being defended</h2>
 *
 * <p>The temptation in a verification tool is the check that cannot fail: reading a value and
 * comparing it with itself, accepting a root as given, or reporting success because nothing went
 * wrong while nothing was examined. Any of those produces a tool that always agrees with the
 * package, which is worse than no tool — an operator would rely on it.
 *
 * <p>So every check here is measured in both directions, and the "nothing was checked" case is
 * pinned separately: a package carrying no evidence at all must NOT report verified.
 *
 * <h2>Independence</h2>
 *
 * <p>{@link SipVerifier} recomputes the Merkle rule from the prefixes rather than importing
 * {@code MerkleTree}. This test uses the real {@code MerkleTree} to build the fixture, so the
 * two implementations are checked against each other — if the verifier's restatement of the
 * rule drifts from the product's, the fixture stops verifying.
 */
class SipVerifierTest {

    private static Path zip(Path dir, String name, Map<String, String> entries) throws Exception {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private static String premisWithDigest(String digest) {
        // The namespace IS declared, as PremisWriter declares it. The reader parses rather than
        // string-matches (a prefix is not part of an XML name), so a fixture that left the
        // prefix unbound would measure the parse failure instead of the check.
        return "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\">"
                + "<premis:object><premis:objectCharacteristics><premis:fixity>"
                + "<premis:messageDigestAlgorithm>SHA-256</premis:messageDigestAlgorithm>"
                + "<premis:messageDigest>" + digest + "</premis:messageDigest>"
                + "</premis:fixity></premis:objectCharacteristics></premis:object>"
                + "</premis:premis>";
    }

    /** A real four-leaf tree, so the audit path is genuine rather than hand-written. */
    private static Map<String, String> realProofFor(int index) {
        // The LEAF HASHES are what root()/proof() take, matching how the ledger builds a tree
        // out of entryHash values.
        List<String> leaves = List.of("e0hash", "e1hash", "e2hash", "e3hash");
        String root = MerkleTree.root(leaves);
        List<MerkleTree.ProofStep> path = MerkleTree.proof(leaves, index);
        StringBuilder steps = new StringBuilder();
        for (MerkleTree.ProofStep step : path) {
            if (steps.length() > 0) {
                steps.append(", ");
            }
            steps.append("{ \"siblingHash\" : \"").append(step.siblingHash())
                    .append("\", \"siblingIsLeft\" : ").append(step.siblingIsLeft())
                    .append(" }");
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("leafHash", leaves.get(index));
        out.put("merkleRoot", root);
        out.put("json", "{ \"status\" : \"success\", \"inclusionProof\" : { \"leafHash\" : \""
                + leaves.get(index) + "\", \"merkleRoot\" : \"" + root
                + "\", \"auditPath\" : [ " + steps + " ] } }");
        return out;
    }

    @Test
    @DisplayName("two PREMIS documents are ambiguous, not a free choice of digest")
    void twoPremisDocumentsAreAmbiguous(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> proof = realProofFor(2);
        // A derived copy brings its own PREMIS. The one describing the payload records the
        // right digest; the other records a different one. Which arrives first depends on zip
        // order, so a verifier that took the first would sometimes PASS and sometimes FAIL the
        // same package — and when it passed, it would be checking bytes against a digest it
        // chose rather than one the package assigned to them.
        Path sip = zip(tmp, "two-premis.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/derived/premis.xml", premisWithDigest("b".repeat(64)),
                "sip/metadata/other/nemaki-evidence.json", proof.get("json")));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "payload digest"),
                "ambiguity is UNAVAILABLE, not NOT_PRESENT and certainly not PASSED: the "
                        + "package HAS fixity metadata and this verifier cannot tell which "
                        + "document is about the payload. " + result.asMap());
        assertEquals(SipVerifier.Verdict.INDETERMINATE, result.verdict(),
                "and a required check that could not be carried out makes the whole answer "
                        + "INDETERMINATE: " + result.asMap());
    }

    @Test
    @DisplayName("a well-formed package verifies, and both checks actually ran")
    void aGoodPackageVerifies(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> proof = realProofFor(2);
        Path sip = zip(tmp, "good.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", proof.get("json")));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertTrue(result.allPassed(), result.asMap().toString());
        assertEquals(SipVerifier.Verdict.VERIFIED, result.verdict(), result.asMap().toString());
        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "payload digest"),
                result.asMap().toString());
        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "audit path"),
                result.asMap().toString());
    }

    /**
     * The product's endpoint gives the same answer as the independent verifier.
     *
     * <p>Both read one PREMIS document, and until now they read it differently: this one took
     * the first {@code "<premis:messageDigest>"} it could find as TEXT and assumed SHA-256 when
     * no algorithm was stated, so an adversarial PREMIS the CLI answered {@code UNAVAILABLE}
     * was answered {@code PASSED} here — and an operator reaches this endpoint far more often
     * than the CLI (subagent, fifth review, P2).
     *
     * <p>The twin of these two cases is
     * {@code PackageIntegrityIsCheckedNotAssumedTest#aSecondDigestUnderAnotherPrefixIsFound}
     * and {@code #aDigestWithNoAlgorithmIsNotPresent}. The modules cannot share the code, so
     * they share the expected answers.
     */
    @Test
    @DisplayName("a second digest under another prefix is ambiguous here too")
    void aSecondDigestUnderAnotherPrefixIsAmbiguousHereToo(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        String matching = SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8));
        String other = SipVerifier.sha256Hex("something else".getBytes(StandardCharsets.UTF_8));
        Map<String, String> proof = realProofFor(2);
        Path sip = zip(tmp, "two-prefixes.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\" "
                        + "xmlns:p=\"http://www.loc.gov/premis/v3\">"
                        + "<premis:object><premis:objectCharacteristics><premis:fixity>"
                        + "<premis:messageDigestAlgorithm>SHA-256"
                        + "</premis:messageDigestAlgorithm>"
                        + "<premis:messageDigest>" + matching + "</premis:messageDigest>"
                        // One algorithm, so only the DIGEST arm can answer here either.
                        + "<p:messageDigest>" + other + "</p:messageDigest>"
                        + "</premis:fixity></premis:objectCharacteristics></premis:object>"
                        + "</premis:premis>",
                "sip/metadata/other/nemaki-evidence.json", proof.get("json")));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "payload digest"),
                "a PREMIS with a matching digest and a contradicting one under a different "
                        + "prefix was read as carrying one. The CLI answers UNAVAILABLE for the "
                        + "same file: " + result.asMap());
    }

    @Test
    @DisplayName("a digest with no algorithm is NOT_PRESENT here too — SHA-256 was an assumption")
    void aDigestWithNoAlgorithmIsNotAssumedHereEither(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> proof = realProofFor(2);
        Path sip = zip(tmp, "no-algorithm.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\">"
                        + "<premis:object><premis:objectCharacteristics><premis:fixity>"
                        + "<premis:messageDigest>"
                        + SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))
                        + "</premis:messageDigest>"
                        + "</premis:fixity></premis:objectCharacteristics></premis:object>"
                        + "</premis:premis>",
                "sip/metadata/other/nemaki-evidence.json", proof.get("json")));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "payload digest"),
                "a digest with no stated algorithm was checked as SHA-256. It happens to be "
                        + "right for packages this product writes, which is no reason to accept "
                        + "it from someone else: " + result.asMap());
    }

    @Test
    @DisplayName("a payload edited after packaging FAILS the digest check")
    void anEditedPayloadFails(@TempDir Path tmp) throws Exception {
        // The whole point of the digest check. If this passes, the tool always agrees with the
        // package and an operator relies on a tool that checks nothing.
        Map<String, String> proof = realProofFor(0);
        Path sip = zip(tmp, "edited.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", "the minutes, edited",
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(
                        "the minutes".getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", proof.get("json")));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertFalse(result.allPassed(), "an edited payload was reported as verified");
        assertEquals(SipVerifier.Outcome.FAILED, outcomeOf(result, "payload digest"));
    }

    @Test
    @DisplayName("an audit path that does not reach the claimed root FAILS")
    void aBrokenAuditPathFails(@TempDir Path tmp) throws Exception {
        Map<String, String> proof = realProofFor(1);
        // One sibling changed: the path now leads somewhere else.
        String tampered = proof.get("json").replaceFirst(
                "\"siblingHash\" : \"[0-9a-f]{64}\"",
                "\"siblingHash\" : \"" + "0".repeat(64) + "\"");
        assertFalse(tampered.equals(proof.get("json")), "the fixture was not actually tampered");

        String payload = "the minutes";
        Path sip = zip(tmp, "brokenpath.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", tampered));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertFalse(result.allPassed(), "a broken audit path was reported as verified");
        assertEquals(SipVerifier.Outcome.FAILED, outcomeOf(result, "audit path"));
    }

    @Test
    @DisplayName("a leaf and a root with NO audit path is not a proof of inclusion")
    void aMissingAuditPathIsNotAnEmptyOne(@TempDir Path tmp) throws Exception {
        // Found by review. An absent path walked as an empty one compares leaf(leafHash) with
        // merkleRoot directly — so a package that puts the leaf's own hash in its merkleRoot
        // passed the check while carrying no proof at all. Both values are the package's own.
        String payload = "the minutes";
        String evidence = "{ \"status\" : \"success\", \"inclusionProof\" : { \"leafHash\" : "
                + "\"e0hash\", \"merkleRoot\" : \"" + SipVerifier.leaf("e0hash") + "\" } }";
        Path sip = zip(tmp, "nopath.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "audit path"),
                "a package with no auditPath was treated as one with an empty path: "
                        + result.asMap());
        assertEquals(SipVerifier.Verdict.INDETERMINATE, result.verdict(),
                result.asMap().toString());
    }

    @Test
    @DisplayName("an EMPTY audit path is UNAVAILABLE — the package cannot tell the two apart")
    void anEmptyAuditPathEstablishesNothing(@TempDir Path tmp) throws Exception {
        // This test used to assert VERIFIED, on the reasoning that a checkpoint which sealed a
        // single entry genuinely produces an empty path. That is true — MerkleTree.root of one
        // leaf IS that leaf's hash — and it is also exactly what a package gets by writing a
        // leaf and hashing it into its own merkleRoot. One public function call. Both values
        // come from the package, so an empty path makes the check arithmetic-free, and the
        // lock was PINNING THE HOLE OPEN (review, 2026-09-19).
        //
        // Not FAILED either: the package may be perfectly genuine. Whoever holds the checkpoint
        // settles it in one look at its span; this verifier cannot, and says so.
        List<String> oneLeaf = List.of("e0hash");
        String root = MerkleTree.root(oneLeaf);
        assertEquals(SipVerifier.leaf("e0hash"), root,
                "the premise of this test is gone: a one-leaf root is no longer the leaf's hash");
        String payload = "the minutes";
        String evidence = "{ \"status\" : \"success\", \"inclusionProof\" : { \"leafHash\" : "
                + "\"e0hash\", \"merkleRoot\" : \"" + root + "\", \"auditPath\" : [ ] } }";
        Path sip = zip(tmp, "oneleaf.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"),
                result.asMap().toString());
        assertEquals(SipVerifier.Verdict.INDETERMINATE, result.verdict(),
                result.asMap().toString());
    }

    @Test
    @DisplayName("an array elsewhere in the document is not the audit path")
    void anotherArrayIsNotTheAuditPath(@TempDir Path tmp) throws Exception {
        // `auditPath: null` plus any other array reached the same arithmetic-free comparison,
        // because the reader took the next '[' anywhere after the key. "Could not read it"
        // answered with a value — in the verifier whose whole job is not to do that.
        String payload = "the minutes";
        // The decoy is STEP-SHAPED on purpose: an empty decoy would land on the empty-path
        // arm, which now answers UNAVAILABLE for its own reason, and the control would measure
        // nothing. With a usable step in it, taking the wrong array walks somewhere and reports
        // FAILED — a different sentence from the one this test pins.
        String evidence = "{ \"inclusionProof\" : { \"leafHash\" : \"e0hash\", "
                + "\"merkleRoot\" : \"" + SipVerifier.leaf("e0hash") + "\", "
                + "\"auditPath\" : null }, \"chainedEntries\" : [ { \"siblingHash\" : \""
                + "0".repeat(64) + "\", \"siblingIsLeft\" : false } ] }";
        Path sip = zip(tmp, "decoyarray.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"),
                result.asMap().toString());
        assertEquals(SipVerifier.Verdict.INDETERMINATE, result.verdict(),
                result.asMap().toString());
    }

    @Test
    @DisplayName("a leafHash that is not a string does not become the next key's name")
    void aNonStringFieldIsNotReadAsTheNextKey(@TempDir Path tmp) throws Exception {
        // The reader searched forward for the next quote, so `"leafHash": 42` returned
        // "merkleRoot" — the NAME of the following field — and the verifier walked the path
        // from a leaf it had invented.
        Map<String, String> proof = realProofFor(1);
        String payload = "the minutes";
        String evidence = proof.get("json").replaceFirst(
                "\"leafHash\" : \"[^\"]+\"", "\"leafHash\" : 42");
        assertFalse(evidence.equals(proof.get("json")), "the fixture was not actually altered");
        Path sip = zip(tmp, "numericleaf.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"),
                result.asMap().toString());
    }

    @Test
    @DisplayName("a proof reformatted by an ordinary JSON tool still verifies")
    void aReformattedProofStillVerifies(@TempDir Path tmp) throws Exception {
        // The over-throw side of the same fix. `siblingIsLeft` used to be read by matching two
        // exact spellings; every other layout was silently FALSE, which combined the siblings
        // on the wrong side and reported FAILED — "the entry was not in that span" — about a
        // genuine package that had merely been through `jq`.
        Map<String, String> proof = realProofFor(2);
        String reformatted = proof.get("json").replace("\" : ", "\": ");
        assertFalse(reformatted.equals(proof.get("json")), "the fixture was not reformatted");
        String payload = "the minutes";
        Path sip = zip(tmp, "reformatted.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", reformatted));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "audit path"),
                result.asMap().toString());
        assertEquals(SipVerifier.Verdict.VERIFIED, result.verdict(), result.asMap().toString());
    }

    @Test
    @DisplayName("a package this product built WITH a ledger verifies, audit path and all")
    void aRealExportedPackageWithALedgerVerifies(@TempDir Path tmp) throws Exception {
        // The round trip the class javadoc claims, finally reaching the audit path. Every other
        // fixture here hand-builds the proof, so all of them could pass while the exporter
        // wrote a shape the verifier cannot read — and the earlier round-trip test wires no
        // ledger, so it stops at NOT_PRESENT. A reviewer pointed out that the previous attempt
        // at this (serialising a HAND-BUILT proof through the product's mapper) measured the
        // mapper's formatting and nothing about the product's own proof shape: rename
        // `siblingIsLeft` in EvidenceLedgerService and it stayed green. This one goes through
        // EvidenceLedgerService.inclusionProof and EarkSipExporter, so the key names, the
        // nesting and the compact serialisation are all the product's.
        jp.aegif.nemaki.evidence.EvidenceLedgerEntry first =
                jp.aegif.nemaki.evidence.EvidenceLedgerEntry.of("bedroom", 1,
                        jp.aegif.nemaki.evidence.EvidenceLedgerEntry.SubjectKind.CAPTURE_COMPLETED,
                        "doc-1", "digest-1", "2026-09-20T00:00:00Z", null);
        jp.aegif.nemaki.evidence.EvidenceLedgerEntry second =
                jp.aegif.nemaki.evidence.EvidenceLedgerEntry.of("bedroom", 2,
                        jp.aegif.nemaki.evidence.EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT,
                        "doc-1", "digest-2", "2026-09-20T00:01:00Z", first.entryHash());
        // Two leaves, so the audit path has a step in it. A checkpoint sealing ONE entry
        // produces an empty path, which this verifier answers UNAVAILABLE for its own reasons.
        String root = MerkleTree.root(List.of(first.entryHash(), second.entryHash()));
        jp.aegif.nemaki.evidence.EvidenceCheckpoint checkpoint =
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("bedroom", 1L, 2L, root, null,
                        "2026-09-20T00:02:00Z");
        jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                org.mockito.Mockito.mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        org.mockito.Mockito.when(store.isActive()).thenReturn(true);
        org.mockito.Mockito.when(store.findBySubject(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(first, second));
        org.mockito.Mockito.when(store.latestCheckpoint("bedroom")).thenReturn(checkpoint);
        org.mockito.Mockito.when(store.range(org.mockito.ArgumentMatchers.eq("bedroom"),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(first, second));
        jp.aegif.nemaki.evidence.EvidenceLedgerService ledgerService =
                new jp.aegif.nemaki.evidence.EvidenceLedgerService();
        ledgerService.setStore(store);

        EarkSipExporter exporter = exporterWithContent("the minutes");
        exporter.setLedgerStore(store);
        exporter.setLedgerService(ledgerService);
        EarkSipExporter.Exported exported = exporter.export("bedroom", "doc-1",
                EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

        SipVerifier.Result result = SipVerifier.verify(exported.sip());

        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "audit path"),
                "the verifier could not read the inclusion proof this product wrote:\n"
                        + result.asMap());
        assertEquals(SipVerifier.Verdict.VERIFIED, result.verdict(), result.asMap().toString());
    }

    @Test
    @DisplayName("keys beside the proof are not read as the proof")
    void looseKeysDoNotShadowTheProof(@TempDir Path tmp) throws Exception {
        // The reader used to take the first key of each name ANYWHERE in the document, so a
        // package whose real inclusionProof is null could carry a working leaf, root and path
        // beside it and be read as proved. The proof is read from INSIDE inclusionProof
        // (review, 2026-09-20).
        Map<String, String> real = realProofFor(2);
        String json = real.get("json");
        String pathText = json.substring(json.indexOf("\"auditPath\""), json.lastIndexOf("]") + 1);
        String evidence = "{ \"leafHash\" : \"" + real.get("leafHash") + "\", "
                + "\"merkleRoot\" : \"" + real.get("merkleRoot") + "\", " + pathText
                + ", \"inclusionProof\" : null }";
        String payload = "the minutes";
        Path sip = zip(tmp, "shadow.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "audit path"),
                "loose keys beside a null inclusionProof were read as the proof: "
                        + result.asMap());
    }

    @Test
    @DisplayName("a null in the step array is not absorbed by its neighbour")
    void aNullStepIsNotAbsorbed(@TempDir Path tmp) throws Exception {
        // Splitting the array text on '}' put a leading `null` in the same chunk as the step
        // after it, and the step's own siblingHash satisfied the read — so an element the
        // verifier could not read vanished and the path came back one step shorter, walked,
        // and PASSED (review, 2026-09-20).
        Map<String, String> real = realProofFor(2);
        String withNull = real.get("json").replace(
                "\"auditPath\" : [ ", "\"auditPath\" : [ null, ");
        assertFalse(withNull.equals(real.get("json")), "the fixture was not altered");
        String payload = "the minutes";
        Path sip = zip(tmp, "nullstep.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", withNull));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"),
                result.asMap().toString());
    }

    @Test
    @DisplayName("evidence that is not JSON at all is UNAVAILABLE, not absent")
    void unparseableEvidenceIsUnavailable(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Path sip = zip(tmp, "notjson.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", "{ \"inclusionProof\" : {"));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"),
                result.asMap().toString());
    }

    @Test
    @DisplayName("a package that says WHY it has no proof is quoted, not overwritten")
    void thePackagesOwnReasonIsUsed(@TempDir Path tmp) throws Exception {
        // Four states came out as one sentence about the chain — including the two where the
        // exporter had written "This is NOT a statement that the record was never chained".
        // `not-chained` is the ONE that means what that sentence says (review, 2026-09-20).
        String payload = "the minutes";
        String notChained = "{ \"status\" : \"not-chained\", \"inclusionProof\" : null, "
                + "\"message\" : \"no ledger entry names this object.\" }";
        Path chained = zip(tmp, "notchained.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", notChained));
        String couldNotBuild = "{ \"status\" : \"error\", \"inclusionProof\" : null, "
                + "\"inclusionProofFailed\" : \"the audit path could not be built (timeout).\" }";
        Path failed = zip(tmp, "proofFailed.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", couldNotBuild));

        assertEquals(SipVerifier.Outcome.NOT_PRESENT,
                outcomeOf(SipVerifier.verify(chained), "audit path"),
                "a package that says no entry names the object is not an unreadable one");
        SipVerifier.Result failedResult = SipVerifier.verify(failed);
        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(failedResult, "audit path"),
                "a package that says the path could NOT BE BUILT was reported as one that was "
                        + "never chained: " + failedResult.asMap());
        assertTrue(detailOf(failedResult, "audit path").contains("could not be built"),
                detailOf(failedResult, "audit path"));
    }

    @Test
    @DisplayName("a package whose proof could not be built says so — through the real exporter")
    void aProofThatCouldNotBeBuiltSaysWhy(@TempDir Path tmp) throws Exception {
        // The arm for this was UNREACHABLE for every package this product builds, and its lock
        // used a shape the exporter never writes: `inclusionProof: null` beside
        // `inclusionProofFailed`. The exporter always puts a MAP there — it fills in
        // provesEntry/provesSequence and merges the FAILED proof body into it — so the verifier
        // went to the "fields cannot be read" sentence and described a correctly formed package
        // as broken (review, 2026-09-20). Built here through the exporter for that reason.
        jp.aegif.nemaki.evidence.EvidenceLedgerEntry only =
                jp.aegif.nemaki.evidence.EvidenceLedgerEntry.of("bedroom", 7,
                        jp.aegif.nemaki.evidence.EvidenceLedgerEntry.SubjectKind.CAPTURE_COMPLETED,
                        "doc-1", "digest-7", "2026-09-20T00:00:00Z", null);
        // A checkpoint that stops BEFORE this entry: the ordinary "not sealed yet" state.
        jp.aegif.nemaki.evidence.EvidenceCheckpoint earlier =
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("bedroom", 1L, 5L,
                        MerkleTree.root(List.of("e0hash")), null, "2026-09-20T00:02:00Z");
        jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                org.mockito.Mockito.mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        org.mockito.Mockito.when(store.isActive()).thenReturn(true);
        org.mockito.Mockito.when(store.findBySubject(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(only));
        org.mockito.Mockito.when(store.latestCheckpoint("bedroom")).thenReturn(earlier);
        jp.aegif.nemaki.evidence.EvidenceLedgerService ledgerService =
                new jp.aegif.nemaki.evidence.EvidenceLedgerService();
        ledgerService.setStore(store);

        EarkSipExporter exporter = exporterWithContent("the minutes");
        exporter.setLedgerStore(store);
        exporter.setLedgerService(ledgerService);
        EarkSipExporter.Exported exported = exporter.export("bedroom", "doc-1",
                EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

        SipVerifier.Result result = SipVerifier.verify(exported.sip());
        String detail = detailOf(result, "audit path");

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        assertTrue(detail.contains("could not be built"),
                "the verifier does not repeat the reason the package carries beside the proof — "
                        + "it says something else instead: " + detail);
        assertFalse(detail.contains("is not written"),
                "a correctly formed package was described as one whose fields are missing: "
                        + detail);
    }

    @Test
    @DisplayName("a proof that says it is unavailable is quoted, not called unreadable")
    void aProofThatSaysItIsUnavailableIsQuoted(@TempDir Path tmp) throws Exception {
        // The reason also lives INSIDE the proof object, and a package may carry it there and
        // nowhere else. Hand-built because this exporter writes both places.
        String payload = "the minutes";
        String evidence = "{ \"inclusionProof\" : { \"provesSequence\" : 7, "
                + "\"status\" : \"unavailable\", \"message\" : \"no checkpoint covers "
                + "sequence 7 yet.\" } }";
        Path sip = zip(tmp, "proofsaysunavailable.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);
        String detail = detailOf(result, "audit path");

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        assertTrue(detail.contains("no checkpoint covers sequence 7"),
                "the proof's own reason was replaced with a sentence about unreadable fields: "
                        + detail);
    }

    @Test
    @DisplayName("a duplicate key is an error, not a silent last-one-wins")
    void aDuplicateKeyIsRefused(@TempDir Path tmp) throws Exception {
        // Jackson's default takes the LAST of two same-named keys, so a step could carry
        // `"siblingHash": null, "siblingHash": "<the real one>"` and read differently here than
        // in a first-wins reader — two readers, two answers, about one package (review).
        Map<String, String> proof = realProofFor(1);
        String doubled = proof.get("json").replace("\"siblingHash\" : \"",
                "\"siblingHash\" : null, \"siblingHash\" : \"");
        assertFalse(doubled.equals(proof.get("json")), "the fixture was not altered");
        String payload = "the minutes";
        Path sip = zip(tmp, "duplicatekey.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", doubled));

        SipVerifier.Result result = SipVerifier.verify(sip);

        String detail = detailOf(result, "audit path");
        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        // The outcome alone would be satisfied by any other unreadable-shape arm; this says the
        // DUPLICATE is what was detected (review, 2026-09-20).
        assertTrue(detail.contains("Duplicate"), detail);
    }

    @Test
    @DisplayName("a byte order mark does not make a good package unreadable")
    void aByteOrderMarkIsSkipped(@TempDir Path tmp) throws Exception {
        // Jackson skips a BOM when it reads bytes and not when it reads a String, and a
        // re-zipped package can acquire one. Refusing there would report a package this
        // verifier could have read (review, over-throw side).
        Map<String, String> proof = realProofFor(2);
        String payload = "the minutes";
        // ONE and TWO. The one-BOM package is the common case; the two-BOM one is a package
        // that went through two tools that each add one. A fixture with only the second lets
        // "strip exactly two" pass (review, 2026-09-20).
        for (String bom : List.of("\uFEFF", "\uFEFF\uFEFF")) {
            Path sip = zip(tmp, "bom-" + bom.length() + ".zip", Map.of(
                    "sip/representations/rep1/data/minutes.txt", payload,
                    "sip/metadata/preservation/premis.xml",
                    premisWithDigest(
                            SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                    "sip/metadata/other/nemaki-evidence.json", bom + proof.get("json")));

            SipVerifier.Result result = SipVerifier.verify(sip);

            assertEquals(SipVerifier.Verdict.VERIFIED, result.verdict(),
                    bom.length() + " BOM(s): " + result.asMap());
        }
    }

    @Test
    @DisplayName("the same reason gets the same answer wherever the package wrote it")
    void theReasonRuleIsOneRule(@TempDir Path tmp) throws Exception {
        // The rule lived in two places and they disagreed: written inside the proof object,
        // `not-chained` came back UNAVAILABLE; written beside it, NOT_PRESENT. A third party
        // implementing the canon's table would then get a different answer from this verifier
        // than the canon states, depending only on WHERE the reason was put (review).
        String payload = "the minutes";
        String inside = "{ \"inclusionProof\" : { \"provesSequence\" : 7, "
                + "\"status\" : \"not-chained\", \"message\" : \"no ledger entry names "
                + "this object.\" } }";
        String beside = "{ \"inclusionProof\" : null, \"status\" : \"not-chained\", "
                + "\"message\" : \"no ledger entry names this object.\" }";
        // The third combination, and the one the canon newly promises: the proof object IS
        // there and the reason is only BESIDE it. Without this the test varies two things at
        // once (where the reason is AND whether a proof object exists), so the document-side
        // fallback inside auditPathCheck was never measured (review, 2026-09-20).
        String besideAProof = "{ \"inclusionProof\" : { \"provesSequence\" : 7 }, "
                + "\"status\" : \"not-chained\", \"message\" : \"no ledger entry names "
                + "this object.\" }";
        Path besideAProofPkg = zip(tmp, "reason-beside-a-proof.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", besideAProof));
        Path insidePkg = zip(tmp, "reason-inside.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", inside));
        Path besidePkg = zip(tmp, "reason-beside.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", beside));

        SipVerifier.Result fromInside = SipVerifier.verify(insidePkg);
        SipVerifier.Result fromBeside = SipVerifier.verify(besidePkg);

        SipVerifier.Result fromBesideAProof = SipVerifier.verify(besideAProofPkg);

        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(fromInside, "audit path"),
                "a reason written INSIDE the proof got a different answer: "
                        + fromInside.asMap());
        assertEquals(outcomeOf(fromBeside, "audit path"), outcomeOf(fromInside, "audit path"),
                "the same reason, written in the two places the canon names, gets two answers");
        assertEquals(SipVerifier.Outcome.NOT_PRESENT,
                outcomeOf(fromBesideAProof, "audit path"),
                "a reason written beside a proof object was not read: "
                        + fromBesideAProof.asMap());
        // The detail too, not just the outcome: another arm can reach NOT_PRESENT for its own
        // reason and leave this green.
        assertTrue(detailOf(fromBesideAProof, "audit path").contains("no ledger entry names"),
                detailOf(fromBesideAProof, "audit path"));
    }

    @Test
    @DisplayName("a reason written in a shape this verifier cannot read is still a reason")
    void anUnreadableReasonIsStillAReason(@TempDir Path tmp) throws Exception {
        // `{"message": {"en": "…"}}` — an i18n object. asString answers null for it, and the
        // arm used to decide "says something" from that null, so a package that HAD said why
        // was reported as one that "does not say why" (review, 2026-09-20).
        String payload = "the minutes";
        String evidence = "{ \"inclusionProof\" : null, "
                + "\"message\" : { \"en\" : \"ledger unreachable\" } }";
        Path sip = zip(tmp, "objectreason.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);
        String detail = detailOf(result, "audit path");

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        assertFalse(detail.contains("does not say why"),
                "a package that said why was reported as one that did not: " + detail);
    }

    @Test
    @DisplayName("a package that says its proof succeeded, with no proof, contradicts itself")
    void aSuccessWithNoProofIsNotSilence(@TempDir Path tmp) throws Exception {
        // `{"inclusionProof": null, "status": "success"}` used to fall through to "carries no
        // inclusion proof, and DOES NOT SAY WHY" — about a package that had said something,
        // and with the chain sentence attached. The same word inside a proof object answered
        // differently, which is what this batch claimed to have fixed (review, 2026-09-20).
        String payload = "the minutes";
        String evidence = "{ \"inclusionProof\" : null, \"status\" : \"success\" }";
        Path sip = zip(tmp, "successnoproof.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);
        String detail = detailOf(result, "audit path");

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        assertFalse(detail.contains("does not say why"),
                "a package that said its proof succeeded was reported as saying nothing: "
                        + detail);
    }

    @Test
    @DisplayName("half a readable reason keeps the half that was read")
    void aPartlyReadableReasonKeepsWhatItRead(@TempDir Path tmp) throws Exception {
        // status readable, message not. Reporting "the reason is not a readable string" threw
        // away the status — and the status is the token a reader acts on (review, 2026-09-20).
        String payload = "the minutes";
        String evidence = "{ \"inclusionProof\" : { \"provesSequence\" : 7, "
                + "\"status\" : \"pending-checkpoint\", "
                + "\"message\" : { \"en\" : \"no checkpoint covers sequence 7 yet\" } } }";
        Path sip = zip(tmp, "halfreadable.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);
        String detail = detailOf(result, "audit path");

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        assertTrue(detail.contains("pending-checkpoint"),
                "the readable half of the reason was thrown away: " + detail);
        assertTrue(detail.contains("not a readable string"),
                "the unreadable half was not mentioned at all: " + detail);
    }

    @Test
    @DisplayName("the 'part of it is unreadable' note is on every answer, not one of four")
    void theUnreadablePartIsNotedOnEveryArm(@TempDir Path tmp) throws Exception {
        // The note hung off the unknown-status arm alone, so a package whose status IS known
        // (`unavailable`, `not-chained`, `success`) dropped the fact that a message was there
        // and could not be read — the same loss as 10 巡目, on the other side (both reviews).
        String payload = "the minutes";
        for (String status : List.of("unavailable", "not-chained", "success")) {
            String evidence = "{ \"inclusionProof\" : null, \"status\" : \"" + status
                    + "\", \"message\" : { \"en\" : \"why\" } }";
            Path sip = zip(tmp, "note-" + status + ".zip", Map.of(
                    "sip/representations/rep1/data/minutes.txt", payload,
                    "sip/metadata/preservation/premis.xml",
                    premisWithDigest(
                            SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                    "sip/metadata/other/nemaki-evidence.json", evidence));

            String detail = detailOf(SipVerifier.verify(sip), "audit path");

            assertTrue(detail.contains("Part of what the package says is not a readable string"),
                    "status " + status + ": the unreadable message was dropped: " + detail);
        }
    }

    @Test
    @DisplayName("an EMPTY proof object is not 'there is no proof to read'")
    void anEmptyProofObjectIsNotAnAbsentOne(@TempDir Path tmp) throws Exception {
        // `proof.isEmpty()` stood in for "there is no proof object", so a package carrying an
        // empty one — or a usable-looking one with unreadable fields — was told "carries no
        // proof to read", which is false, and the diagnosis of its fields disappeared
        // (both reviewers, 2026-09-20).
        String payload = "the minutes";
        // An EMPTY one first — the case the canon's "空でも" names and the one a revert to
        // `proof == null || proof.isEmpty()` would break. The earlier version of this test used
        // a three-key proof and therefore never measured it (both reviews, 2026-09-20).
        String empty = "{ \"status\" : \"success\", \"inclusionProof\" : { } }";
        Path emptyProof = zip(tmp, "emptyproof.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", empty));
        String evidence = "{ \"inclusionProof\" : { \"provesSequence\" : 7, "
                + "\"status\" : \"success\", \"merkleRoot\" : 42 } }";
        Path sip = zip(tmp, "successbutunreadable.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        String emptyDetail = detailOf(SipVerifier.verify(emptyProof), "audit path");
        assertFalse(emptyDetail.contains("carries no proof to read"),
                "a package carrying an EMPTY proof object was told it carries none: "
                        + emptyDetail);
        assertTrue(emptyDetail.contains("is not written"),
                "the empty proof's own fields were not described: " + emptyDetail);

        SipVerifier.Result result = SipVerifier.verify(sip);
        String detail = detailOf(result, "audit path");

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        assertTrue(detail.contains("merkleRoot is not a string"),
                "the diagnosis of the proof's own fields was replaced with a sentence about "
                        + "there being no proof: " + detail);
        assertFalse(detail.contains("carries no proof to read"),
                "a package WITH a proof object was told it carries none: " + detail);
    }

    @Test
    @DisplayName("a proof that says only a message does not hide a status beside it")
    void aProofWithOnlyAMessageTakesTheReasonWithIt(@TempDir Path tmp) throws Exception {
        // The consequence of reading status and message as a PAIR from one place: a proof
        // object that carries only a message takes the whole reason with it, so a
        // `not-chained` written beside it is not read. Pinned because it is a consequence a
        // reader has to know, not because it is obviously right (review, 2026-09-20).
        String payload = "the minutes";
        String evidence = "{ \"inclusionProof\" : { \"provesSequence\" : 7, "
                + "\"message\" : \"no checkpoint covers sequence 7 yet\" }, "
                + "\"status\" : \"not-chained\" }";
        Path sip = zip(tmp, "messageonlyproof.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);
        String detail = detailOf(result, "audit path");

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        assertTrue(detail.contains("no checkpoint covers sequence 7"),
                "the proof's own message was not used: " + detail);
    }

    @Test
    @DisplayName("a reason that cannot be read says THAT, not 'unrecognised'")
    void anUnreadableReasonSaysItIsUnreadable(@TempDir Path tmp) throws Exception {
        // "we do not recognise this state" and "we could not read what it says" are different
        // sentences, and the second was being reported as the first (review, 2026-09-20).
        String payload = "the minutes";
        String evidence = "{ \"inclusionProof\" : null, "
                + "\"message\" : { \"en\" : \"ledger unreachable\" } }";
        Path sip = zip(tmp, "unreadablereason.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);
        String detail = detailOf(result, "audit path");

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        // The WHOLE sentence, not the fragment: the "part of what it says" note also contains
        // "not a readable string", so the fragment alone left the sabotage green (measured).
        assertTrue(detail.contains("the reason it gives is not a readable string"),
                "an unreadable reason was reported as an unrecognised one: " + detail);
    }

    @Test
    @DisplayName("a reason this verifier does not recognise is not 'no proof is present'")
    void anUnrecognisedReasonIsUnavailable(@TempDir Path tmp) throws Exception {
        // A third-party or older package that says WHY in words this verifier has no state for.
        // Classifying that as "the proof is absent" attaches the chain sentence to a sentence
        // we did not understand (review, 2026-09-20).
        String payload = "the minutes";
        String evidence = "{ \"inclusionProof\" : null, "
                + "\"message\" : \"ledger temporarily unreachable\" }";
        Path sip = zip(tmp, "unknownreason.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);
        String detail = detailOf(result, "audit path");

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        assertTrue(detail.contains("ledger temporarily unreachable"), detail);
    }

    @Test
    @DisplayName("a package that says nothing at all is NOT_PRESENT — the absent proof it is")
    void aSilentPackageIsNotPresent(@TempDir Path tmp) throws Exception {
        // The over-throw side of the arm above: no proof, no reason, nothing to misread.
        String payload = "the minutes";
        Path sip = zip(tmp, "silent.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", "{ \"objectId\" : \"doc-1\" }"));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "audit path"),
                result.asMap().toString());
    }

    @Test
    @DisplayName("a half-written proof says which half, about BOTH fields")
    void aHalfWrittenProofDescribesBothFields(@TempDir Path tmp) throws Exception {
        // leafHash is not written at all; merkleRoot IS written but is not a string. Naming
        // only the first said "the other one is fine" about a field that is equally unusable —
        // the conflation this branch exists to remove, one level down (review, 2026-09-20).
        String payload = "the minutes";
        String evidence = "{ \"inclusionProof\" : { \"merkleRoot\" : 42, "
                + "\"auditPath\" : [ ] } }";
        Path sip = zip(tmp, "halfproof.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);

        String detail = detailOf(result, "audit path");
        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"), detail);
        assertTrue(detail.contains("leafHash is not written"),
                "the detail does not say that leafHash is absent: " + detail);
        assertTrue(detail.contains("merkleRoot is not a string"),
                "the detail does not say what is wrong with merkleRoot — naming one field and "
                        + "stopping asserts the other is usable: " + detail);
    }

    @Test
    @DisplayName("a literal with something stuck to it is not that literal")
    void aTruncatedLiteralIsNotRead(@TempDir Path tmp) throws Exception {
        // `startsWith("true", …)` accepted `truegarbage`, so a step whose side could not be read
        // combined on a side the verifier had invented — and a package built around THAT reading
        // reached PASSED (review, 2026-09-19).
        // BOTH spellings. The first fix stopped at "a space follows", so `true garbage` still
        // read as true while `truegarbage` did not — and this lock only measured the second
        // one, which is how a half-fix stays green (review, 2026-09-20). The hand-built
        // fixtures here vary in spacing (some around the colon, aReformattedProofStillVerifies
        // only after it); what none of them was, before the ledger round trip above, is the
        // product's own output.
        String payload = "the minutes";
        for (String garbage : List.of("truegarbage", "true garbage")) {
            Map<String, String> proof = realProofFor(3);
            String broken = proof.get("json").replaceFirst(
                    "\"siblingIsLeft\" : (true|false)", "\"siblingIsLeft\" : " + garbage);
            assertFalse(broken.equals(proof.get("json")), "the fixture was not altered");
            Path sip = zip(tmp, "garbage-" + garbage.indexOf(' ') + ".zip", Map.of(
                    "sip/representations/rep1/data/minutes.txt", payload,
                    "sip/metadata/preservation/premis.xml",
                    premisWithDigest(
                            SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                    "sip/metadata/other/nemaki-evidence.json", broken));

            SipVerifier.Result result = SipVerifier.verify(sip);

            assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"),
                    "`" + garbage + "` was read as a literal: " + result.asMap());
        }
    }

    @Test
    @DisplayName("a step that does not say which side its sibling is on is UNAVAILABLE")
    void aStepWithNoSideIsUnavailable(@TempDir Path tmp) throws Exception {
        Map<String, String> proof = realProofFor(0);
        String stripped = proof.get("json").replaceAll(",\\s*\"siblingIsLeft\" : (true|false)", "");
        assertFalse(stripped.equals(proof.get("json")), "the fixture still has its flags");
        String payload = "the minutes";
        Path sip = zip(tmp, "noside.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", stripped));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"),
                result.asMap().toString());
    }

    @Test
    @DisplayName("a step this verifier cannot read is UNAVAILABLE, not a broken path")
    void anUnreadableStepIsNotAFailure(@TempDir Path tmp) throws Exception {
        // Dropping the unreadable step silently would shorten the path, land on a different
        // root, and report FAILED — "the entry was not in that span" — about a package we did
        // not manage to read.
        String payload = "the minutes";
        // One READABLE step and one unreadable one. With only the unreadable step, dropping it
        // leaves an empty path — which the empty-path arm answers UNAVAILABLE for its own
        // reason, so the control could not tell the two apart. With a readable step beside it,
        // dropping the other one walks a shorter path onto a different root: FAILED.
        Map<String, String> real = realProofFor(0);
        String firstStep = real.get("json").substring(real.get("json").indexOf("{ \"siblingHash\""));
        firstStep = firstStep.substring(0, firstStep.indexOf("}") + 1);
        String evidence = "{ \"inclusionProof\" : { \"leafHash\" : \"e0hash\", "
                + "\"merkleRoot\" : \"" + SipVerifier.leaf("e0hash") + "\", "
                + "\"auditPath\" : [ " + firstStep + ", { \"siblingIsLeft\" : false } ] } }";
        Path sip = zip(tmp, "unreadablestep.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8))),
                "sip/metadata/other/nemaki-evidence.json", evidence));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"),
                result.asMap().toString());
        assertEquals(SipVerifier.Verdict.INDETERMINATE, result.verdict(),
                result.asMap().toString());
    }

    @Test
    @DisplayName("a package with nothing to check does NOT report verified")
    void anEmptyPackageIsNotVerified(@TempDir Path tmp) throws Exception {
        // "Nothing was wrong" and "nothing was checked" are the same sentence with opposite
        // meanings, and only one of them is a reason to trust a package.
        Path sip = zip(tmp, "empty.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", "the minutes"));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertFalse(result.allPassed(),
                "a package carrying no digest and no proof was reported as verified: "
                        + result.asMap());
        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "payload digest"));
        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "audit path"));
    }

    @Test
    @DisplayName("bytes that hash, with no proof they were ever recorded, is INDETERMINATE")
    void aDigestWithoutAnAuditPathIsIndeterminate(@TempDir Path tmp) throws Exception {
        // Half the sentence. The digest says the packaged bytes are the bytes PREMIS was written
        // over — by the same hand, at the same moment, inside the same package. It says nothing
        // about whether a chain outside this zip ever held them. Reporting that as verified is
        // this verifier committing the error it exists to catch, one level up: an absent check
        // read as assurance.
        String payload = "the minutes";
        Path sip = zip(tmp, "digestonly.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8)))));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "payload digest"),
                result.asMap().toString());
        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "audit path"),
                result.asMap().toString());
        assertEquals(SipVerifier.Verdict.INDETERMINATE, result.verdict(),
                "a package carrying no inclusion proof was verified on its payload digest "
                        + "alone: " + result.asMap());
        assertFalse(result.allPassed(), result.asMap().toString());
    }

    @Test
    @DisplayName("a proof with no digest of the bytes it covers is INDETERMINATE")
    void anAuditPathWithoutADigestIsIndeterminate(@TempDir Path tmp) throws Exception {
        // The other half, and the one easier to miss. The path proves an ENTRY was in the span a
        // checkpoint sealed. Nothing in it reaches the bytes lying next to it in the package, so
        // the payload here could be any file at all.
        Map<String, String> proof = realProofFor(3);
        Path sip = zip(tmp, "proofonly.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", "any bytes at all",
                "sip/metadata/other/nemaki-evidence.json", proof.get("json")));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "audit path"),
                result.asMap().toString());
        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "payload digest"),
                result.asMap().toString());
        assertEquals(SipVerifier.Verdict.INDETERMINATE, result.verdict(),
                "a package whose bytes were never checked was verified on its inclusion proof "
                        + "alone: " + result.asMap());
        assertFalse(result.allPassed(), result.asMap().toString());
    }

    @Test
    @DisplayName("a finding is not diluted into INDETERMINATE by an absent check")
    void aFindingOutranksAnAbsence(@TempDir Path tmp) throws Exception {
        // The tri-state runs both ways. Having taught the verdict that "we could not tell" is
        // not success, the next error is letting it swallow "we found this wrong": a package
        // whose bytes DISAGREE with their digest must read as FAILED even though the other
        // required check never ran. FAILED and INDETERMINATE are acted on differently — one is
        // a reason to go looking, the other a reason to go collecting.
        Path sip = zip(tmp, "failedandabsent.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", "the minutes, edited",
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(
                        "the minutes".getBytes(StandardCharsets.UTF_8)))));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.FAILED, outcomeOf(result, "payload digest"));
        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "audit path"));
        assertEquals(SipVerifier.Verdict.FAILED, result.verdict(),
                "a package whose bytes do not match their digest was reported as merely "
                        + "inconclusive: " + result.asMap());
    }

    @Test
    @DisplayName("the reported verdict is the one a reader gets from the body")
    void theBodyCarriesTheVerdict(@TempDir Path tmp) throws Exception {
        // `verified` is a boolean and cannot carry three values, so a caller reading only that
        // key sees INDETERMINATE and FAILED as the same answer. The body has to state the
        // verdict itself, and state the same one the object does.
        String payload = "the minutes";
        Path sip = zip(tmp, "body.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8)))));

        SipVerifier.Result result = SipVerifier.verify(sip);
        Map<String, Object> body = result.asMap();

        assertEquals(result.verdict().name(), body.get("verdict"),
                "the body reports a different verdict than the result: " + body);
        assertEquals(SipVerifier.Verdict.INDETERMINATE.name(), body.get("verdict"), body.toString());
        assertEquals(Boolean.FALSE, body.get("verified"), body.toString());
    }

    @Test
    @DisplayName("an unreadable package is UNAVAILABLE, not failed and not verified")
    void anUnreadablePackageSaysSo(@TempDir Path tmp) throws Exception {
        Path notAZip = Files.writeString(tmp.resolve("broken.zip"), "this is not a zip");

        SipVerifier.Result result = SipVerifier.verify(notAZip);

        assertFalse(result.allPassed());
        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "package readable"),
                result.asMap().toString());
        // The result carries ONE row and neither required check is in it. Asserting the verdict
        // here says what that means: a file that is not a package establishes nothing, which is
        // INDETERMINATE and not FAILED. Without this the test passed under the old rule too.
        assertEquals(SipVerifier.Verdict.INDETERMINATE, result.verdict(),
                result.asMap().toString());
    }

    @Test
    @DisplayName("a digest in an algorithm this verifier cannot compute is UNAVAILABLE")
    void anUnknownAlgorithmIsNotAFailure(@TempDir Path tmp) throws Exception {
        // Reporting FAILED here would say the bytes are wrong, when what happened is that we
        // did not check them.
        Path sip = zip(tmp, "sha512.zip", Map.of(
                "sip/representations/rep1/data/minutes.txt", "the minutes",
                "sip/metadata/preservation/premis.xml",
                premisWithDigest("deadbeef").replace("SHA-256", "SHA-512")));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "payload digest"),
                result.asMap().toString());
        assertFalse(result.allPassed());
    }

    @Test
    @DisplayName("the verifier's restatement of the Merkle rule matches the product's")
    void theRestatedRuleMatchesTheProduct() {
        // SipVerifier deliberately does NOT import MerkleTree — a verifier a third party is
        // meant to reimplement must not depend on the thing it verifies. That independence is
        // only worth having if the restatement is right, so the two are compared here.
        assertEquals(MerkleTree.hashLeaf("abc"), SipVerifier.leaf("abc"),
                "the verifier's leaf rule has drifted from the product's, so a genuine package "
                        + "would fail verification");
        assertEquals(MerkleTree.hashNode("aa", "bb"), SipVerifier.node("aa", "bb"),
                "the verifier's node rule has drifted from the product's");
    }

    @Test
    @DisplayName("every result carries what a pass does not establish")
    void everyResultCarriesItsLimits(@TempDir Path tmp) throws Exception {
        String payload = "x";
        Path sip = zip(tmp, "limits.zip", Map.of(
                "sip/representations/rep1/data/x.txt", payload,
                "sip/metadata/preservation/premis.xml",
                premisWithDigest(SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8)))));

        SipVerifier.Result result = SipVerifier.verify(sip);

        assertTrue(result.limits().contains("INTERNALLY CONSISTENT"),
                "a passing result does not say what it fails to establish: " + result.limits());
        assertTrue(result.limits().contains("tampered"),
                "the result does not say that a package from a tampered repository verifies "
                        + "perfectly, which is the one thing a reader must not assume away");
    }

    @Test
    @DisplayName("a package this product actually built is read by its own verifier")
    void aRealExportedPackageIsRead(@TempDir Path tmp) throws Exception {
        // The round trip, and the only test here that proves the two halves agree. Every other
        // fixture is hand-built, so all of them could pass while the real exporter wrote
        // something the verifier cannot read — a verifier that only verifies its own fixtures.
        EarkSipExporter exporter = exporterWithContent("the minutes");
        EarkSipExporter.Exported exported = exporter.export("bedroom", "doc-1",
                EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

        SipVerifier.Result result = SipVerifier.verify(exported.sip());

        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "payload digest"),
                "the verifier could not check a package this product built:\n" + result.asMap());
        // No ledger was wired. This test used to assert NOT_PRESENT here, and a reviewer
        // pointed out what that sentence said: "the chain only holds what was written to it …
        // this says nothing about whether the record is genuine" — chain semantics, about a
        // package whose own status field says the LEDGER WAS NOT REACHABLE and, in as many
        // words, "This is NOT a statement that the record was never chained". The lock was
        // pinning the conflation this branch exists to remove, with the material to tell them
        // apart sitting in the same document.
        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "audit path"),
                result.asMap().toString());
        assertTrue(detailOf(result, "audit path").contains("could not be read"),
                "the verifier does not say that the package could not read its own evidence: "
                        + detailOf(result, "audit path"));
        assertTrue(detailOf(result, "audit path").contains("not wired"),
                "the verifier does not repeat what the package says about its own evidence: "
                        + detailOf(result, "audit path"));
        // And therefore the package this exporter produces here is INDETERMINATE, not verified.
        // Worth pinning at the round trip rather than only on hand-built fixtures: this is the
        // shape a real deployment without a ledger ships, and under the old rule it came back
        // `verified: true`.
        assertEquals(SipVerifier.Verdict.INDETERMINATE, result.verdict(),
                result.asMap().toString());
    }

    /** The real exporter over one document whose bytes are {@code payload}. */
    private static EarkSipExporter exporterWithContent(String payload) {
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                org.mockito.Mockito.mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        jp.aegif.nemaki.model.Document document = new jp.aegif.nemaki.model.Document();
        document.setId("doc-1");
        document.setName("minutes.txt");
        document.setType("cmis:document");
        document.setAttachmentNodeId("att-1");
        org.mockito.Mockito.when(contentService.getContent("bedroom", "doc-1"))
                .thenReturn(document);
        jp.aegif.nemaki.model.AttachmentNode attachment =
                org.mockito.Mockito.mock(jp.aegif.nemaki.model.AttachmentNode.class);
        org.mockito.Mockito.when(attachment.getName()).thenReturn("minutes.txt");
        org.mockito.Mockito.when(attachment.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
        org.mockito.Mockito.when(contentService.getAttachment("bedroom", "att-1"))
                .thenReturn(attachment);

        // A content section carrying the digest of the bytes above, so PREMIS records a real one.
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("recordedDigest",
                SipVerifier.sha256Hex(payload.getBytes(StandardCharsets.UTF_8)));
        content.put("algorithm", "SHA-256");
        jp.aegif.nemaki.evidence.AuthenticityReport report =
                new jp.aegif.nemaki.evidence.AuthenticityReport("bedroom", "doc-1",
                        "2026-08-26T00:00:00Z",
                        List.of(new jp.aegif.nemaki.evidence.AuthenticityReport.Section("content",
                                jp.aegif.nemaki.evidence.AuthenticityReport.Verdict.VERIFIED,
                                content, "limits")));
        jp.aegif.nemaki.evidence.AuthenticityReportAssembler assembler =
                org.mockito.Mockito.mock(
                        jp.aegif.nemaki.evidence.AuthenticityReportAssembler.class);
        org.mockito.Mockito.when(assembler.assemble(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(report);

        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);
        return exporter;
    }

    private static String detailOf(SipVerifier.Result result, String name) {
        for (SipVerifier.Check check : result.checks()) {
            if (check.name().equals(name)) {
                return check.detail();
            }
        }
        return "(no check named " + name + ")";
    }

    private static SipVerifier.Outcome outcomeOf(SipVerifier.Result result, String name) {
        for (SipVerifier.Check check : result.checks()) {
            if (check.name().equals(name)) {
                return check.outcome();
            }
        }
        return null;
    }
}
