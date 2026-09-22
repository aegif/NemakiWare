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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a package's files actually land, OBSERVED (plan Phase 2).
 *
 * <h2>Why this exists before the profile spec</h2>
 *
 * <p>The plan's package contract names ten files under {@code metadata/other/nemaki-evidence/}.
 * That is a path in a design document. What a receiving organisation opens is whatever
 * commons-ip2 wrote, and the exporter does not choose it: it hands files to a staging
 * {@code workDir/metadata/} and the library decides where each one goes. Writing a verifier
 * spec against the document's string would produce a verifier that looks in the wrong place.
 *
 * <p>So this pins the OBSERVED layout, and the spec is written from here. The plan says as
 * much — 「配置の正本は fixture で commons-ip2 の実 path に固定する」.
 *
 * <h2>What it found</h2>
 *
 * <p>Our JSON goes to {@code metadata/other/}, as a FILE, beside the authenticity report.
 *
 * <p><b>The open question is answered.</b> This class used to say the contract's directory did
 * not exist and that whether commons-ip2 would carry one had to be answered by building a
 * package and looking. It was, on 2026-09-20: given an {@code IPFile} with relative folders,
 * commons-ip2 recreates the directory inside {@code metadata/other/}, and a package built with
 * an evidence bundle carries all twelve files at the paths the spec names. The test below is
 * that measurement, not a prediction.
 */
class TheSipLayoutIsWhereCommonsIpPutsItTest {

    /** The archival root commons-ip2 names from the repository and object id. */
    private static final String ROOT = "nemaki-bedroom-doc-1/";

    private static List<String> entryNames(Path zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    @Test
    @DisplayName("the evidence file is at metadata/other/, which is where a verifier must look")
    void theEvidenceIsUnderMetadataOther(@TempDir Path tmp) throws Exception {
        List<String> names = entryNames(EarkSipExporterTest.buildOne(tmp));

        assertTrue(names.contains(ROOT + "metadata/other/nemaki-evidence.json"),
                "the evidence package is not where the spec will tell a third party to look. "
                        + "Entries: " + names);
        assertTrue(names.contains(ROOT + "metadata/other/nemaki-authenticity-report.json"),
                "the report moved out from beside it: " + names);
    }

    /**
     * An assembler that hands back one complete bundle, so a real package is built with the
     * profile v1 layout rather than the legacy file.
     */
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

    private static jp.aegif.nemaki.evidence.EvidenceBundle oneBundle() {
        jp.aegif.nemaki.evidence.RecordContentStatementV1 statement =
                new jp.aegif.nemaki.evidence.RecordContentStatementV1("bedroom", "doc-1", "doc-1",
                        "att-1", "a".repeat(64), 11L,
                        jp.aegif.nemaki.evidence.RecordContentStatementV1.CommitmentKind.CAPTURED,
                        null, "2026-09-20T00:00:00Z");
        jp.aegif.nemaki.evidence.EvidenceLedgerEntry entry =
                jp.aegif.nemaki.evidence.EvidenceLedgerEntry.of("record-content", 1L,
                        jp.aegif.nemaki.evidence.EvidenceLedgerEntry.SubjectKind
                                .RECORD_CONTENT_STATE,
                        "doc-1", statement.digest(), "2026-09-20T00:00:00Z", null);
        jp.aegif.nemaki.evidence.EvidenceCheckpoint covering =
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("record-content", 1, 10, "aa", null,
                        "2026-09-20T00:00:00Z");
        return new jp.aegif.nemaki.evidence.EvidenceBundle("bedroom", "doc-1", "doc-1", statement,
                entry,
                new jp.aegif.nemaki.evidence.EvidenceBundle.InclusionProof(
                        jp.aegif.nemaki.evidence.MerkleTree.hashLeaf(entry.entryHash()),
                        java.util.List.of(), null),
                covering, java.util.List.of(covering), covering, java.util.Map.of(),
                "2026-09-20T01:00:00Z");
    }

    @Test
    @DisplayName("with a bundle, the package carries the v1 directory and NOT the legacy file")
    void theV1LayoutReplacesTheLegacyFile(@TempDir Path tmp) throws Exception {
        List<String> names = entryNames(
                EarkSipExporterTest.buildOneWithBundle(tmp, assemblerReturning(oneBundle())));

        assertFalse(names.contains(ROOT + "metadata/other/nemaki-evidence.json"),
                "a package must not carry both layouts: the spec makes that FAILED, because a "
                        + "verifier would have to choose which one is the evidence. Entries: "
                        + names);
        for (String expected : List.of(
                "metadata/other/nemaki-evidence/profile.json",
                "metadata/other/nemaki-evidence/bundle-manifest.json",
                "metadata/other/nemaki-evidence/record-content-statement.json",
                "metadata/other/nemaki-evidence/record-content-statement.c14n",
                "metadata/other/nemaki-evidence/ledger-entry.json",
                "metadata/other/nemaki-evidence/ledger-entry.c14n",
                "metadata/other/nemaki-evidence/inclusion-proof.json",
                "metadata/other/nemaki-evidence/covering-checkpoint.json",
                "metadata/other/nemaki-evidence/covering-checkpoint.c14n",
                "metadata/other/nemaki-evidence/checkpoint-chain.json",
                "metadata/other/nemaki-evidence/anchor-target-checkpoint.json",
                "metadata/other/nemaki-evidence/anchor-target-checkpoint.c14n")) {
            assertTrue(names.contains(ROOT + expected),
                    expected + " is not in the package. The spec tells a third party to open "
                            + "exactly this path, and commons-ip2 — not the exporter — decides "
                            + "where a file lands. Entries: " + names);
        }
    }

    @Test
    @DisplayName("the whole layout is pinned, so a library upgrade that moves a file is visible")
    void theLayoutIsExactlyThis(@TempDir Path tmp) throws Exception {
        List<String> names = entryNames(EarkSipExporterTest.buildOne(tmp));

        // A SET, not a sequence. The first version compared an ordered List, so commons-ip2
        // writing the same eleven paths in a different order would have been rejected — and
        // nothing a verifier does depends on zip order (Codex review, P2).
        //
        // Pinned in full rather than by prefix: an upgrade that renames `representations/rep1`
        // or moves METS.xml changes what every external verifier has to open, and the only way
        // that is noticed is if something states the whole set.
        //
        // THIS FIXTURE'S configuration: no evidence-record service is wired, so no `ers.der`.
        // The product adds one when it is (EarkSipExporter's addOtherMetadata for the record),
        // and a spec written from this list alone would reject that legitimate package — so the
        // optional entry is named here rather than discovered later.
        assertEquals(java.util.Set.of(
                ROOT + "metadata/descriptive/dc.xml",
                ROOT + "metadata/preservation/premis.xml",
                ROOT + "metadata/other/nemaki-authenticity-report.json",
                ROOT + "metadata/other/nemaki-evidence.json",
                ROOT + "representations/rep1/data/minutes.txt",
                ROOT + "representations/rep1/METS.xml",
                ROOT + "schemas/DILCISExtensionMETS.xsd",
                ROOT + "schemas/DILCISExtensionSIPMETS.xsd",
                ROOT + "schemas/mets1_12.xsd",
                ROOT + "schemas/xlink.xsd",
                ROOT + "METS.xml"), new java.util.LinkedHashSet<>(names),
                "the package layout changed. This is not a formatting detail: the spec in "
                        + "docs/design tells a third party which paths to open, and it is "
                        + "written from this list. Known OPTIONAL entry, absent here because "
                        + "this fixture wires no evidence-record service: "
                        + ROOT + "metadata/other/ers.der");
    }

    /**
     * Every METS reference resolves under the METS's OWN directory.
     *
     * <p>This is the assumption the independent verifier's {@code mets closure} now rests on:
     * it resolves an {@code xlink:href} against the directory of the METS that wrote it, and
     * falls back to a loose suffix match only outside the payload. Before that it matched any
     * entry whose path ENDED with the href, which let a copy inside the payload stand in for a
     * file the package did not carry — while {@code payload fixity}, searching for the same
     * name with the payload excluded, answered "no PREMIS". Two checks, one name, opposite
     * answers (subagent, ninth review, P3).
     *
     * <p>The verifier cannot measure this: it may not depend on {@code core}, so "the product's
     * own packages resolve exactly" was a claim on the other side of a module boundary. It is
     * measured HERE, against what commons-ip2 actually wrote — the same reason the rest of this
     * class exists. If a library upgrade starts writing {@code ../} hrefs, this goes red before
     * an external verifier refuses a package we shipped.
     */
    @Test
    @DisplayName("every METS href resolves under its own METS's directory, exactly")
    void everyMetsReferenceResolvesBesideItsOwnMets(@TempDir Path tmp) throws Exception {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(
                Files.newInputStream(EarkSipExporterTest.buildOne(tmp)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }

        List<String> metsPaths = entries.keySet().stream()
                .filter(name -> name.endsWith("METS.xml")).toList();
        assertFalse(metsPaths.isEmpty(), "the package carries no METS: " + entries.keySet());

        List<String> unresolved = new ArrayList<>();
        int references = 0;
        for (String metsPath : metsPaths) {
            String directory = metsPath.substring(0, metsPath.lastIndexOf('/') + 1);
            org.w3c.dom.Document document = metsDocument(entries.get(metsPath));
            org.w3c.dom.NodeList all = document.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                org.w3c.dom.Element element = (org.w3c.dom.Element) all.item(i);
                String href = element.getAttributeNS(
                        "http://www.w3.org/1999/xlink", "href");
                if (href == null || href.isBlank() || href.contains(":")) {
                    // Absent, or not a local reference (a URL, a URN, a schema location).
                    continue;
                }
                references++;
                String wanted = href.replace('\\', '/');
                while (wanted.startsWith("./") || wanted.startsWith("/")) {
                    wanted = wanted.startsWith("./") ? wanted.substring(2) : wanted.substring(1);
                }
                if (!entries.containsKey(directory + wanted)) {
                    unresolved.add(metsPath + " -> " + href);
                }
            }
        }

        assertTrue(references > 0,
                "no local xlink:href was found in any METS, so this lock measured nothing. "
                        + "commons-ip2 changed how it writes references: " + metsPaths);
        assertEquals(List.of(), unresolved,
                "a METS this product wrote names a file that is not where the METS is. The "
                        + "independent verifier resolves references that way, so these are the "
                        + "references it would report as missing from a package we shipped. "
                        + "Entries: " + entries.keySet());
    }

    private static org.w3c.dom.Document metsDocument(byte[] xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
    }

    @Test
    @DisplayName("the contract's nemaki-evidence/ DIRECTORY does not exist yet — recorded, not assumed")
    void theContractDirectoryIsNotThereYet(@TempDir Path tmp) throws Exception {
        // The plan's §7 lists ten files under metadata/other/nemaki-evidence/. Today there is
        // one file called nemaki-evidence.json and no such directory. Stating that here keeps
        // the spec honest while the remaining nine are built: a reader comparing the plan with
        // the tree should find the difference written down rather than discover it.
        List<String> names = entryNames(EarkSipExporterTest.buildOne(tmp));

        assertTrue(names.stream().noneMatch(n -> n.startsWith(ROOT + "metadata/other/nemaki-evidence/")),
                "a nemaki-evidence/ directory has appeared — the contract is being built, and "
                        + "this test and the spec's layout section have to be written from what "
                        + "commons-ip2 actually produced: " + names);
    }
}
