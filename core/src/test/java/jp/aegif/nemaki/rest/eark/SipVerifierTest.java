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
        return "<premis:premis><premis:object><premis:objectCharacteristics><premis:fixity>"
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
        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "payload digest"),
                result.asMap().toString());
        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "audit path"),
                result.asMap().toString());
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
    @DisplayName("an unreadable package is UNAVAILABLE, not failed and not verified")
    void anUnreadablePackageSaysSo(@TempDir Path tmp) throws Exception {
        Path notAZip = Files.writeString(tmp.resolve("broken.zip"), "this is not a zip");

        SipVerifier.Result result = SipVerifier.verify(notAZip);

        assertFalse(result.allPassed());
        assertEquals(SipVerifier.Outcome.UNAVAILABLE, outcomeOf(result, "package readable"),
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
    @DisplayName("a package this product actually built passes its own verifier")
    void aRealExportedPackageVerifies(@TempDir Path tmp) throws Exception {
        // The round trip, and the only test here that proves the two halves agree. Every other
        // fixture is hand-built, so all of them could pass while the real exporter wrote
        // something the verifier cannot read — a verifier that only verifies its own fixtures.
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
                new java.io.ByteArrayInputStream("the minutes".getBytes(StandardCharsets.UTF_8)));
        org.mockito.Mockito.when(contentService.getAttachment("bedroom", "att-1"))
                .thenReturn(attachment);

        // A content section carrying the digest of the bytes above, so PREMIS records a real one.
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("recordedDigest",
                SipVerifier.sha256Hex("the minutes".getBytes(StandardCharsets.UTF_8)));
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
        EarkSipExporter.Exported exported = exporter.export("bedroom", "doc-1",
                EarkSipExporter.Options.withholdingPersonalData(), tmp);

        SipVerifier.Result result = SipVerifier.verify(exported.sip());

        assertEquals(SipVerifier.Outcome.PASSED, outcomeOf(result, "payload digest"),
                "the verifier could not check a package this product built:\n" + result.asMap());
        // No ledger was wired, so there IS no inclusion proof, and saying so is the right
        // answer — not a failure, and not a pass either.
        assertEquals(SipVerifier.Outcome.NOT_PRESENT, outcomeOf(result, "audit path"),
                result.asMap().toString());
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
