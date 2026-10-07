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
package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A half-written export file is never taken for a document.
 *
 * <h2>The claim that was false</h2>
 *
 * <p>The filesystem exporter writes each attachment to a {@code .nemaki-export-*.part} file
 * beside its destination and moves it onto the destination only once the copy has finished, so
 * a failed export cannot destroy the previous one. The comment explaining that added "which no
 * importer reads" — and a review checked it. {@code FilesystemImporter} collects every regular
 * file under the source directory and excluded only metadata sidecars and version files, so a
 * staging file that outlived a failed cleanup, or one seen by an import running at the same
 * time as an export, was ingested as a document carrying whatever bytes had been written.
 *
 * <p>That is the same substitution the export refusals exist to prevent — bytes with no
 * sidecar becoming a record — arriving from the other direction, introduced by the fix for it.
 *
 * <p>The name is declared once in {@link ImportExportUtils} and used by both sides, because
 * this file has already produced four separate one-arm defects from writing the same rule
 * twice.
 */
class StagingFilesAreNotImportableTest {

    @Test
    @DisplayName("the importer skips a staging file the exporter may have left behind")
    void aStagingFileIsSkipped() {
        assertTrue(ImportExportUtils.isExportStagingFile(".nemaki-export-12345.part"),
                "the importer would read a half-written export as a document");
        assertTrue(ImportExportUtils.isExportStagingFile("2026/records/.nemaki-export-9.part"),
                "a staging file in a subdirectory is not recognised, and the importer walks "
                        + "the whole tree");
    }

    @Test
    @DisplayName("ordinary documents are still imported — the control")
    void anOrdinaryFileIsStillImported() {
        // A rule that skips too much loses records silently, which is worse than the leftover
        // it was written for.
        assertFalse(ImportExportUtils.isExportStagingFile("report.pdf"));
        assertFalse(ImportExportUtils.isExportStagingFile("2026/report.pdf"));
        // Deliberate: a document a user genuinely named ".part" is not a staging file. Only
        // the prefix AND the suffix together are.
        assertFalse(ImportExportUtils.isExportStagingFile("quarterly.part"),
                "any file ending .part is treated as staging, so a document named that way "
                        + "is dropped from every import with nothing reporting it");
        assertFalse(ImportExportUtils.isExportStagingFile(".nemaki-export-notes.txt"),
                "the prefix alone is enough, so a file merely starting that way is dropped");
    }

    @Test
    @DisplayName("the importer actually consults the rule — not just that the rule exists")
    void theImporterConsultsTheRule() throws Exception {
        // The two tests above measure a predicate. What has to hold is that the WALK uses it:
        // a correct rule nothing calls is the shape this batch keeps finding, and the
        // exporter's own false comment is what made it worth checking here rather than
        // assuming.
        String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemImporter.java"));
        assertTrue(source.contains("isExportStagingFile(relativePath)"),
                "the importer no longer skips staging files, so a leftover half-written "
                        + "export is ingested as a document");
    }

    @Test
    @DisplayName("the ZIP importer consults the rule too — the same consumer, one format over")
    void theZipImporterConsultsTheRuleToo() throws Exception {
        // Round 5 added the skip to FilesystemImporter and stopped. ZipImporter walks the
        // same kind of tree (an administrator can zip a filesystem-export directory and
        // upload it as the custom format) with the same exclusion list — sidecars and
        // version files — so a .part file inside the archive was imported as a document
        // carrying the truncated bytes of a failed export. A round-6 sibling sweep found
        // it; the one-arm shape, across two importers this time.
        String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java"));
        assertTrue(source.contains("isExportStagingFile(path)"),
                "the ZIP importer no longer skips staging files, so a leftover half-written "
                        + "export inside an uploaded archive is ingested as a document");
    }
}
