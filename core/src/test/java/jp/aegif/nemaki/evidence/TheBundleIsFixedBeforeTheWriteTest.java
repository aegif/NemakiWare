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
package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4's gate, measured on the files that actually get written.
 *
 * <p>The gate is "everything in the package names the same content version and the same anchor
 * target chain". The way that gets broken is not by a wrong value — it is by parts read at
 * different moments, each true when it was read. So the writer is given a bundle and no store,
 * and these tests are about what it does with an INCOMPLETE one: the ways an absence could be
 * written as a presence.
 */
class TheBundleIsFixedBeforeTheWriteTest {

    private static RecordContentStatementV1 statement() {
        return new RecordContentStatementV1("bedroom", "doc-1", "doc-1", "att-1",
                "a".repeat(64), 12L, RecordContentStatementV1.CommitmentKind.CAPTURED, null,
                "2026-09-20T00:00:00Z");
    }

    private static EvidenceLedgerEntry entry(String payloadDigest) {
        return EvidenceLedgerEntry.of("record-content", 7L,
                EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE, "doc-1", payloadDigest,
                "2026-09-20T00:00:00Z", null);
    }

    private static EvidenceCheckpoint checkpoint(long from, long to, String root, String prev) {
        return EvidenceCheckpoint.of("record-content", from, to, root, prev,
                "2026-09-20T00:00:00Z");
    }

    private static EvidenceBundle fullBundle() {
        RecordContentStatementV1 statement = statement();
        EvidenceLedgerEntry entry = entry(statement.digest());
        EvidenceCheckpoint covering = checkpoint(1, 10, "aa", null);
        EvidenceCheckpoint target = checkpoint(11, 20, "bb", covering.checkpointHash());
        Map<AnchorKind, EvidenceBundle.AnchorPart> anchors = new LinkedHashMap<>();
        anchors.put(AnchorKind.RFC3161_TSA, new EvidenceBundle.AnchorPart(
                EvidenceBundle.AnchorPart.State.PRESENT, new byte[] { 0x30, 0x03 }, null));
        anchors.put(AnchorKind.OPENTIMESTAMPS, new EvidenceBundle.AnchorPart(
                EvidenceBundle.AnchorPart.State.NOT_CONFIGURED, null, "no calendar configured"));
        return new EvidenceBundle("bedroom", "doc-1", "doc-1", statement, entry,
                new EvidenceBundle.InclusionProof(MerkleTree.hashLeaf(entry.entryHash()),
                        List.of(new EvidenceBundle.InclusionProof.Step("cc", true)), null),
                covering, List.of(covering, target), target, anchors, "2026-09-20T01:00:00Z");
    }

    private static SortedSet<String> write(EvidenceBundle bundle, Path dir) throws Exception {
        new EvidenceBundleWriter(bundle).writeTo(dir);
        SortedSet<String> found = new TreeSet<>();
        Path root = dir.resolve(EvidenceBundleWriter.DIRECTORY);
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> found.add(root.relativize(p).toString().replace('\\', '/')));
        }
        return found;
    }

    @Test
    @DisplayName("a complete bundle writes the nine entries the spec names")
    void aCompleteBundleWritesTheNineEntries(@TempDir Path dir) throws Exception {
        SortedSet<String> found = write(fullBundle(), dir);

        assertEquals(new TreeSet<>(List.of(
                "anchor-target-checkpoint.c14n",
                "anchor-target-checkpoint.json",
                "anchors/rfc3161.der",
                "bundle-manifest.json",
                "checkpoint-chain.json",
                "covering-checkpoint.c14n",
                "covering-checkpoint.json",
                "inclusion-proof.json",
                "ledger-entry.c14n",
                "ledger-entry.json",
                "profile.json",
                "record-content-statement.c14n",
                "record-content-statement.json")), found,
                "the package's evidence section must be exactly what evidence-profile-v1.md §4.2 "
                        + "defines. A file a verifier does not expect is one it will not read, "
                        + "and one it expects and does not find is NOT_PRESENT");
    }

    @Test
    @DisplayName("a shipped .c14n is the canonical form of the .json beside it")
    void theShippedCanonicalFormMatchesItsDocument(@TempDir Path dir) throws Exception {
        write(fullBundle(), dir);
        Path root = dir.resolve(EvidenceBundleWriter.DIRECTORY);

        for (String name : List.of("record-content-statement", "ledger-entry",
                "covering-checkpoint", "anchor-target-checkpoint")) {
            String json = Files.readString(root.resolve(name + ".json"), StandardCharsets.UTF_8);
            byte[] shipped = Files.readAllBytes(root.resolve(name + ".c14n"));
            // Recomputed from the JSON, exactly as a third party does it. If these ever differ
            // the package ships a canonical form nobody outside this JVM can reproduce.
            assertArrayEquals(CanonicalJson.canonicalBytes(json), shipped,
                    name + ".c14n is not the canonical form of " + name + ".json");
        }
    }

    @Test
    @DisplayName("the ledger entry ships every field its hash is computed from")
    void theEntryCanBeRecomputedFromWhatIsShipped(@TempDir Path dir) throws Exception {
        write(fullBundle(), dir);
        String json = Files.readString(
                dir.resolve(EvidenceBundleWriter.DIRECTORY).resolve("ledger-entry.json"),
                StandardCharsets.UTF_8);

        for (String field : List.of("domain", "sequence", "subjectKind", "subjectId",
                "payloadDigest", "occurredAt", "prevEntryHash", "entryHash")) {
            assertTrue(json.contains("\"" + field + "\""),
                    "the shipped entry omits " + field + ", so a verifier cannot recompute the "
                            + "entry hash — and a check that cannot be recomputed is NOT_PRESENT "
                            + "rather than passed");
        }
    }

    @Test
    @DisplayName("an absent part is omitted, never written as an empty one")
    void anAbsentPartIsOmittedRatherThanWrittenEmpty(@TempDir Path dir) throws Exception {
        // No statement, no entry, no checkpoints — a document whose content predates E1.
        EvidenceBundle thin = new EvidenceBundle("bedroom", "doc-1", "doc-1", null, null,
                new EvidenceBundle.InclusionProof(null, null, "no checkpoint covers this entry"),
                null, List.of(), null, Map.of(), "2026-09-20T01:00:00Z");

        SortedSet<String> found = write(thin, dir);

        assertEquals(new TreeSet<>(List.of("bundle-manifest.json", "inclusion-proof.json",
                "profile.json")), found,
                "a part that is not there must not be written as an empty document. A verifier "
                        + "that finds no file answers NOT_PRESENT; one that finds an empty "
                        + "object has to guess whether the package is claiming something");
        assertEquals("PACKAGE_INTEGRITY_V1", thin.highestProfileSupported());
    }

    @Test
    @DisplayName("an unavailable proof does not ship an empty step list")
    void anUnavailableProofIsNotAnEmptyPath(@TempDir Path dir) throws Exception {
        EvidenceBundle thin = new EvidenceBundle("bedroom", "doc-1", "doc-1", null, null,
                new EvidenceBundle.InclusionProof(null, null, "no checkpoint covers this entry"),
                null, List.of(), null, Map.of(), "2026-09-20T01:00:00Z");
        write(thin, dir);
        String json = Files.readString(
                dir.resolve(EvidenceBundleWriter.DIRECTORY).resolve("inclusion-proof.json"),
                StandardCharsets.UTF_8);

        assertFalse(json.contains("\"steps\""),
                "an empty step list is a VALID proof for a tree of one leaf, so shipping one "
                        + "for a proof that could not be built would make 'no proof' and 'the "
                        + "proof is trivially satisfied' the same file");
        assertTrue(json.contains("unavailableBecause"));
    }

    @Test
    @DisplayName("an anchor that is not present ships no file, and the manifest says why")
    void anAbsentAnchorShipsNoFile(@TempDir Path dir) throws Exception {
        SortedSet<String> found = write(fullBundle(), dir);

        assertFalse(found.contains("anchors/ots.ots"),
                "a rung with no material must not produce a file. A zero-byte DER would be "
                        + "reported by a verifier as a parse failure — which reads as tampering "
                        + "— rather than as the absence it is");
        String manifest = Files.readString(
                dir.resolve(EvidenceBundleWriter.DIRECTORY).resolve("bundle-manifest.json"),
                StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"OPENTIMESTAMPS\"") && manifest.contains("NOT_CONFIGURED"),
                "and the manifest has to say which kind of absence it was");
    }

    @Test
    @DisplayName("an anchor recorded as present with no bytes is refused outright")
    void aPresentAnchorWithNoBytesIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBundle.AnchorPart(EvidenceBundle.AnchorPart.State.PRESENT,
                        new byte[0], null),
                "an empty DER recorded as PRESENT is an absence dressed as a presence, and by "
                        + "the time it is in the package nothing can tell them apart");
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBundle.AnchorPart(EvidenceBundle.AnchorPart.State.NOT_PRESENT,
                        new byte[] { 1 }, null),
                "and material on a rung recorded as absent would be taken by a reader as the "
                        + "anchor");
    }

    @Test
    @DisplayName("the manifest lists every file that was written")
    void theManifestListsWhatWasWritten(@TempDir Path dir) throws Exception {
        SortedSet<String> found = write(fullBundle(), dir);
        String manifest = Files.readString(
                dir.resolve(EvidenceBundleWriter.DIRECTORY).resolve("bundle-manifest.json"),
                StandardCharsets.UTF_8);

        for (String file : found) {
            if (file.equals("bundle-manifest.json")) {
                // The manifest cannot list itself: it would have to be hashed before it was
                // complete. Spec §5.2 expects the other twelve.
                continue;
            }
            assertTrue(manifest.contains("\"" + file + "\""),
                    "the manifest does not list " + file + ", so a verifier checking for "
                            + "unreferenced additions would report a file the package itself "
                            + "wrote");
        }
    }

    @Test
    @DisplayName("the declared profile does not claim more than the bundle holds")
    void theDeclaredProfileTracksWhatIsActuallyThere() {
        EvidenceBundle full = fullBundle();
        assertEquals("TRUSTED_RFC3161_V1", full.highestProfileSupported());

        Map<AnchorKind, EvidenceBundle.AnchorPart> none = new LinkedHashMap<>();
        none.put(AnchorKind.RFC3161_TSA, new EvidenceBundle.AnchorPart(
                EvidenceBundle.AnchorPart.State.NOT_PRESENT, null, "nothing settled yet"));
        EvidenceBundle unanchored = new EvidenceBundle(full.repositoryId(), full.objectId(),
                full.versionObjectId(), full.statement(), full.entry(), full.inclusionProof(),
                full.coveringCheckpoint(), full.checkpointChain(), full.anchorTargetCheckpoint(),
                none, full.createdAt());
        assertEquals("RECORD_LEDGER_V1", unanchored.highestProfileSupported(),
                "a chain with no anchor material on any rung is not an anchored checkpoint, and "
                        + "declaring one would put a claim in the package that its own files "
                        + "cannot support");
    }
}
