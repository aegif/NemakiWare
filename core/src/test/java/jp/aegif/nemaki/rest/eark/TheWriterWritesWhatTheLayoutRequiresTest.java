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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A package this product really builds satisfies the layout the verifier now enforces — R71.
 *
 * <h2>Why this lock is here and not beside the check</h2>
 *
 * <p>{@code evidence-verifier-core} may not depend on {@code core} — the whole point of that
 * module is that a receiving organisation can run it without a WAR, a Spring context or a
 * CouchDB — and {@code core} does not depend on it either. So the check and the writer cannot be
 * put in one test, and the fixtures on the verifier's side are hand-built.
 *
 * <p>Hand-built fixtures are exactly how a verifier comes to refuse every real package: the
 * fixture is made from the reader's understanding, so it agrees with the reader whether or not
 * the reader is right. That has already happened twice on this branch — a timestamp imprint
 * hashed twice on both sides, and a P2 fixture with no {@code profile.json} that stood for "a
 * real package" for weeks.
 *
 * <p>This is the other end. It takes the package the exporter actually produces and asserts the
 * sentences of §4.2 and §5.2 that {@code PackageIntegrity.v1Layout} turns into a verdict. If the
 * writer drifts, this goes red here rather than in a receiving organisation's verifier.
 */
class TheWriterWritesWhatTheLayoutRequiresTest {

    private static final String ROOT = "nemaki-bedroom-doc-1/";
    private static final String SECTION = ROOT + "metadata/other/nemaki-evidence/";

    private static Map<String, byte[]> entriesOf(Path zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }

    /** The section of a package the exporter built, keyed by section-relative path. */
    private static Map<String, byte[]> section(Path tmp) throws Exception {
        Path sip = EarkSipExporterTest.buildOneWithBundle(tmp, assemblerReturning(bundle()));
        Map<String, byte[]> section = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entriesOf(sip).entrySet()) {
            if (entry.getKey().startsWith(SECTION) && !entry.getKey().endsWith("/")) {
                section.put(entry.getKey().substring(SECTION.length()), entry.getValue());
            }
        }
        assertFalse(section.isEmpty(),
                "the exporter wrote no v1 section, so everything below would assert over an "
                        + "empty map and pass without measuring anything");
        return section;
    }

    /** An assembler that hands back one complete bundle, so a real v1 section is written. */
    private static jp.aegif.nemaki.evidence.EvidenceBundleAssembler assemblerReturning(
            jp.aegif.nemaki.evidence.EvidenceBundle bundle) {
        return new jp.aegif.nemaki.evidence.EvidenceBundleAssembler() {
            @Override
            public jp.aegif.nemaki.evidence.EvidenceBundle assemble(String repositoryId,
                    String objectId, String versionObjectId) {
                return bundle;
            }
        };
    }

    /** One complete bundle, with a prior pair and an anchor so the optional parts are present. */
    private static jp.aegif.nemaki.evidence.EvidenceBundle bundle() {
        jp.aegif.nemaki.evidence.RecordContentStatementV1 statement =
                new jp.aegif.nemaki.evidence.RecordContentStatementV1("bedroom", "doc-1", "doc-1",
                        "att-1", "a".repeat(64), 11L,
                        jp.aegif.nemaki.evidence.RecordContentStatementV1.CommitmentKind.CAPTURED,
                        null, "2026-09-20T00:00:00Z");
        jp.aegif.nemaki.evidence.EvidenceLedgerEntry entry =
                jp.aegif.nemaki.evidence.EvidenceLedgerEntry.of("record-content", 2L,
                        jp.aegif.nemaki.evidence.EvidenceLedgerEntry.SubjectKind
                                .RECORD_CONTENT_STATE,
                        "doc-1", statement.digest(), "2026-09-20T00:00:00Z", null);
        jp.aegif.nemaki.evidence.EvidenceCheckpoint covering =
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("record-content", 1, 10,
                        "b".repeat(64), null, "2026-09-20T00:00:00Z");
        Map<jp.aegif.nemaki.rest.purview.anchor.AnchorKind,
                jp.aegif.nemaki.evidence.EvidenceBundle.AnchorPart> anchors =
                new LinkedHashMap<>();
        anchors.put(jp.aegif.nemaki.rest.purview.anchor.AnchorKind.RFC3161_TSA,
                new jp.aegif.nemaki.evidence.EvidenceBundle.AnchorPart(
                        jp.aegif.nemaki.evidence.EvidenceBundle.AnchorPart.State.PRESENT,
                        "not really DER".getBytes(StandardCharsets.UTF_8), null));
        anchors.put(jp.aegif.nemaki.rest.purview.anchor.AnchorKind.OPENTIMESTAMPS,
                new jp.aegif.nemaki.evidence.EvidenceBundle.AnchorPart(
                        jp.aegif.nemaki.evidence.EvidenceBundle.AnchorPart.State.NOT_CONFIGURED,
                        null, "no calendar is configured on this node"));
        return new jp.aegif.nemaki.evidence.EvidenceBundle("bedroom", "doc-1", "doc-1", statement,
                entry,
                new jp.aegif.nemaki.evidence.EvidenceBundle.InclusionProof(
                        jp.aegif.nemaki.evidence.MerkleTree.hashLeaf(entry.entryHash()),
                        List.of(), null),
                covering, List.of(covering), covering, anchors, "2026-09-20T01:00:00Z");
    }

    private static String hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** {@code "path"} / {@code "sha256"} pairs, read out of the manifest without a JSON parser. */
    private static Map<String, String> manifestFiles(byte[] manifest) {
        Map<String, String> files = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\"path\":\"([^\"]+)\",\"sha256\":\"([0-9a-f]{64})\"\\}")
                .matcher(new String(manifest, StandardCharsets.UTF_8));
        while (m.find()) {
            files.put(m.group(1), m.group(2));
        }
        return files;
    }

    @Test
    @DisplayName("the manifest names every file beside it, with the digest that file really has")
    void theManifestCoversTheSection(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> section = section(tmp);
        byte[] manifest = section.get("bundle-manifest.json");
        assertTrue(manifest != null, "the section has no bundle-manifest.json: " + section.keySet());

        Map<String, String> named = manifestFiles(manifest);
        assertFalse(named.isEmpty(),
                "no {path, sha256} pair was read out of the manifest, so the comparisons below "
                        + "would hold over an empty list. The manifest's shape changed: "
                        + new String(manifest, StandardCharsets.UTF_8));

        List<String> unlisted = new ArrayList<>();
        for (String relative : section.keySet()) {
            if (!"bundle-manifest.json".equals(relative) && !named.containsKey(relative)) {
                unlisted.add(relative);
            }
        }
        assertEquals(List.of(), unlisted,
                "the writer put file(s) in the section that its own manifest does not name. §5.2 "
                        + "makes that FAILED, so every package this product writes would be "
                        + "refused by the verifier at P0");

        for (Map.Entry<String, String> file : named.entrySet()) {
            byte[] bytes = section.get(file.getKey());
            assertTrue(bytes != null, "the manifest names " + file.getKey()
                    + ", which the package does not carry: " + section.keySet());
            assertEquals(file.getValue(), hex(bytes),
                    "the manifest's digest for " + file.getKey() + " is not the digest of the "
                            + "bytes shipped beside it");
        }
    }

    /**
     * The manifest does not name itself, and the verifier exempts exactly that one file.
     *
     * <p>Stated from this side too: if the writer ever started listing it, the exemption in
     * {@code PackageIntegrity} would become a hole nobody was watching — one file in the section
     * whose digest is never compared.
     */
    @Test
    @DisplayName("bundle-manifest.json is the one file the manifest does not list")
    void theManifestDoesNotListItself(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> section = section(tmp);

        assertFalse(manifestFiles(section.get("bundle-manifest.json"))
                        .containsKey("bundle-manifest.json"),
                "the manifest lists itself. Its digest cannot be its own digest, so either the "
                        + "value is wrong or it was computed over a different document — and the "
                        + "verifier's exemption for this one file now hides whichever it is");
    }

    @Test
    @DisplayName("the section declares v1, and carries no legacy file beside it")
    void theSectionDeclaresItsProfileAndStandsAlone(@TempDir Path tmp) throws Exception {
        Path sip = EarkSipExporterTest.buildOneWithBundle(tmp, assemblerReturning(bundle()));
        Map<String, byte[]> entries = entriesOf(sip);

        byte[] profile = entries.get(SECTION + "profile.json");
        assertTrue(profile != null,
                "the package has a v1 section and no profile.json. §4.2 requires it, and without "
                        + "it nothing states which contract the documents beside it were written "
                        + "to: " + entries.keySet());
        String declared = new String(profile, StandardCharsets.UTF_8);
        assertTrue(declared.contains("\"profileVersion\":\"1\""),
                "profile.json does not declare profileVersion \"1\" as a STRING: " + declared);
        assertTrue(declared.contains("\"declaredProfiles\""),
                "profile.json lists no declaredProfiles, which §5.1 requires: " + declared);

        assertFalse(entries.containsKey(ROOT + "metadata/other/nemaki-evidence.json"),
                "the package carries BOTH the legacy evidence file and a v1 section, which §4.2 "
                        + "makes FAILED: a verifier would have to choose which one is the "
                        + "evidence");
    }

    /**
     * An absent anchor leaves no file, and the manifest says why.
     *
     * <p>The layout check reads the manifest against the section, so a rung written as a
     * zero-byte {@code .der} would be a listed file that parses as nothing — "no material" shown
     * as "material we could not read". The writer's rule is the opposite one, and it is measured
     * here because the verifier cannot tell the two apart from the package alone.
     */
    @Test
    @DisplayName("an anchor with no material is a manifest entry, not an empty file")
    void anAbsentAnchorIsNotAnEmptyFile(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> section = section(tmp);

        assertTrue(section.containsKey("anchors/rfc3161.der"),
                "the present rung wrote no file, so the absent one below proves nothing: "
                        + section.keySet());
        assertFalse(section.containsKey("anchors/ots.ots"),
                "a rung with no material wrote a file anyway. An empty DER is reported by a "
                        + "verifier as a parse failure, which reads as tampering rather than as "
                        + "'nobody kept the answer'");
        String manifest = new String(section.get("bundle-manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("NOT_CONFIGURED"),
                "the manifest does not record WHY the absent rung has no file, so its absence is "
                        + "indistinguishable from a file that was removed: " + manifest);
    }
}
