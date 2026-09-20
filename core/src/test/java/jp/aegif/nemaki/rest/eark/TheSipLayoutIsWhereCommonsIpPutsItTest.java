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
 * <p>Our JSON goes to {@code metadata/other/}, as a FILE, beside the authenticity report. The
 * contract's directory does not exist today; whether commons-ip2 will carry one is a question
 * for the phase that adds the other nine files, and it has to be answered the same way — by
 * building a package and looking.
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

    @Test
    @DisplayName("the whole layout is pinned, so a library upgrade that moves a file is visible")
    void theLayoutIsExactlyThis(@TempDir Path tmp) throws Exception {
        List<String> names = entryNames(EarkSipExporterTest.buildOne(tmp));

        // Pinned in full rather than by prefix. A commons-ip2 upgrade that renames
        // `representations/rep1` or moves METS.xml changes what every external verifier has to
        // open, and the only way that is noticed is if something states the whole set.
        assertEquals(List.of(
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
                ROOT + "METS.xml"), names,
                "the package layout changed. This is not a formatting detail: the spec in "
                        + "docs/design tells a third party which paths to open, and it is "
                        + "written from this list");
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
