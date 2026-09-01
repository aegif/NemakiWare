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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ExportResult;

/**
 * An export never presents itself as complete when a document's bytes are missing from it.
 *
 * <h2>Two artifacts, two ways of not lying about them</h2>
 *
 * <p>The ZIP is the response BODY: the 200 is already committed by the time the walk starts, so
 * the only honest signal left is whether the archive unpacks. A document whose content could
 * not be read used to be logged and skipped, and the archive still finished — carrying the
 * document's {@code .meta} sidecar with no bytes beside it, which a receiver reads as a record
 * that had no content. It now aborts before the central directory is written, so the unzip
 * fails instead of succeeding on a hole.
 *
 * <p>The filesystem export answers with JSON and already had a channel for this — the caller
 * turns a non-empty {@code errors} list into {@code status: "partial"}. One of its two content
 * arms recorded nothing at all, so the version file was silently absent while its metadata
 * sidecar was written and the response still said {@code success}.
 *
 * <p>{@code EarkSipExporter} made this decision one increment earlier; these are the two
 * exporters that were left behind.
 */
class ExportsRefuseMissingBytesTest {

    private static final String REPO = "bedroom";

    private static Document documentWithContent(String id, String name) {
        Document doc = new Document();
        doc.setId(id);
        doc.setName(name);
        doc.setType("cmis:document");
        doc.setObjectType("cmis:document");
        doc.setAttachmentNodeId("att-" + id);
        return doc;
    }

    private static Folder folder(String id, String name) {
        Folder f = new Folder();
        f.setId(id);
        f.setName(name);
        f.setType("cmis:folder");
        f.setObjectType("cmis:folder");
        return f;
    }

    private static AttachmentNode readableAttachment(String id, byte[] bytes) {
        AttachmentNode node = new AttachmentNode();
        node.setId(id);
        node.setName("payload.bin");
        node.setMimeType("application/octet-stream");
        node.setLength(bytes.length);
        node.setInputStream(new ByteArrayInputStream(bytes));
        return node;
    }

    // ---------- the ZIP ----------

    @Test
    @DisplayName("a document whose content cannot be read aborts the archive, it does not "
            + "finish one with a hole in it")
    void anUnreadableDocumentAbortsTheZip() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getAttachment(eq(REPO), anyString()))
                .thenThrow(new IllegalStateException("the attachment could not be read"));

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(sink);
        ZipExporter exporter = new ZipExporter();

        // The message is asserted, not merely the type. The runner showed why: with the
        // content arm's refusal removed, the walk carried on to the version history, whose
        // own refusal raised the SAME exception type — so a type-only assertion stayed green
        // while the arm under measurement was gone.
        ZipExporter.ExportRefusedException refused = assertThrows(
                ZipExporter.ExportRefusedException.class,
                () -> exporter.exportSingleDocument(REPO, documentWithContent("d1", "report.pdf"),
                        "report.pdf", zos, mock(CallContext.class), cs),
                "the archive carried the document's metadata with none of its bytes, and "
                        + "unpacked cleanly — which reads as a record that had no content");
        assertTrue(refused.getMessage().contains("the content of report.pdf"),
                "refused somewhere else in the walk, not at the content: "
                        + refused.getMessage());

        // The refusal has to leave an archive that FAILS to open, not one that opens and is
        // quietly short. Nothing called finish(), so there is no central directory.
        assertFalse(entryNames(sink.toByteArray()).contains("report.pdf.meta"),
                "the metadata sidecar reached the archive without the bytes it describes");
    }

    @Test
    @DisplayName("a readable document is still written — the control")
    void aReadableDocumentIsStillWritten() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "hello".getBytes()));

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ZipExporter exporter = new ZipExporter();
        try (ZipOutputStream zos = new ZipOutputStream(sink)) {
            // Only the content arm is exercised here: buildDocumentMetadata reaches for the
            // Spring context, which a unit test has no business standing up.
            exporter.writeContentForTest(REPO, "att-d1", "report.pdf", zos, cs);
        }

        assertTrue(entryNames(sink.toByteArray()).contains("report.pdf"),
                "the refusal arms broke the ordinary export");
    }

    @Test
    @DisplayName("an attachment that reads as absent also refuses — null is not empty content")
    void anAbsentAttachmentAlsoRefuses() {
        ContentService cs = mock(ContentService.class);
        when(cs.getAttachment(eq(REPO), anyString())).thenReturn(null);

        ZipOutputStream zos = new ZipOutputStream(new ByteArrayOutputStream());
        ZipExporter exporter = new ZipExporter();

        assertThrows(ZipExporter.ExportRefusedException.class,
                () -> exporter.writeContentForTest(REPO, "att-d1", "report.pdf", zos, cs));
    }

    private static List<String> entryNames(byte[] archive) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            try {
                while ((entry = zis.getNextEntry()) != null) {
                    names.add(entry.getName());
                }
            } catch (Exception truncated) {
                // An aborted archive is expected to read badly; whatever was named before the
                // abort is what the assertions look at.
            }
        }
        return names;
    }

    // ---------- the filesystem export ----------

    @Test
    @DisplayName("a folder holding one unreadable document reports errors, not success")
    void anUnreadableDocumentIsReportedByTheFilesystemExport(@TempDir Path targetDir)
            throws Exception {
        Folder root = folder("f1", "records");
        Document broken = documentWithContent("d1", "report.pdf");

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1")))
                .thenReturn(Arrays.<Content>asList(broken));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenThrow(new IllegalStateException("the attachment could not be read"));

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);

        assertFalse(result.errors.isEmpty(),
                "the export reported success while the document's bytes never reached the "
                        + "target directory — the caller turns an empty errors list into "
                        + "status \"success\"");
        assertEquals(0, result.documentsExported,
                "a document whose content was lost was counted as exported");
        assertFalse(Files.exists(targetDir.resolve("report.pdf")));
        assertFalse(Files.exists(targetDir.resolve("report.pdf.meta")),
                "a metadata sidecar was written for content that is not there");
    }

    @Test
    @DisplayName("an attachment that reads as absent is reported too — the arm that recorded "
            + "nothing at all")
    void anAbsentAttachmentIsReportedByTheFilesystemExport(@TempDir Path targetDir)
            throws Exception {
        Folder root = folder("f1", "records");
        Document broken = documentWithContent("d1", "report.pdf");

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1")))
                .thenReturn(Arrays.<Content>asList(broken));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        // Not an exception — the old guard was `attachment != null && getInputStream() != null`
        // with no else, so this walked straight past the write, wrote the metadata sidecar,
        // counted the document as exported and reported success.
        when(cs.getAttachment(eq(REPO), eq("att-d1"))).thenReturn(null);

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);

        assertFalse(result.errors.isEmpty(),
                "an attachment that could not be produced left no trace in the response");
        assertEquals(0, result.documentsExported);
        assertFalse(Files.exists(targetDir.resolve("report.pdf.meta")),
                "a metadata sidecar was written beside content that is not there");
    }

    @Test
    @DisplayName("an earlier version whose bytes are missing is reported — its sidecar used "
            + "to be written silently")
    void anUnreadableVersionIsReportedByTheFilesystemExport(@TempDir Path targetDir)
            throws Exception {
        Folder root = folder("f1", "records");
        Document latest = documentWithContent("d1", "report.pdf");
        latest.setLatestVersion(true);
        latest.setVersionSeriesId("vs-1");
        Document earlier = documentWithContent("d0", "report.pdf");
        earlier.setLatestVersion(false);
        earlier.setVersionLabel("1.0");
        earlier.setVersionSeriesId("vs-1");

        jp.aegif.nemaki.model.VersionSeries series = new jp.aegif.nemaki.model.VersionSeries();
        series.setId("vs-1");

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1")))
                .thenReturn(Arrays.<Content>asList(latest));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "current".getBytes()));
        when(cs.getVersionSeries(eq(REPO), eq(latest))).thenReturn(series);
        when(cs.getAllVersions(org.mockito.ArgumentMatchers.any(), eq(REPO), eq("vs-1")))
                .thenReturn(new java.util.ArrayList<>(Arrays.asList(latest, earlier)));
        when(cs.getAttachment(eq(REPO), eq("att-d0")))
                .thenThrow(new IllegalStateException("the attachment could not be read"));

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);

        assertFalse(result.errors.isEmpty(),
                "the version's bytes were lost and the export still reported success — this "
                        + "arm recorded nothing at all, not even a partial status");
        assertFalse(Files.exists(targetDir.resolve("report.pdf.v1.meta")),
                "a version sidecar was written beside a version file that does not exist");
    }

    @Test
    @DisplayName("a folder whose documents are all readable still reports success — the control")
    void aReadableFolderStillSucceeds(@TempDir Path targetDir) throws Exception {
        Folder root = folder("f1", "records");
        Folder child = folder("f2", "2026");

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1")))
                .thenReturn(Arrays.<Content>asList(child));
        when(cs.getChildren(eq(REPO), eq("f2")))
                .thenReturn(java.util.Collections.<Content>emptyList());
        when(cs.lastUnreadableChildCount()).thenReturn(0);

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);

        assertTrue(result.errors.isEmpty(),
                "the refusal arms turned an ordinary export into a partial one: "
                        + result.errors);
        assertEquals(1, result.foldersExported);
        assertTrue(Files.isDirectory(targetDir.resolve("2026")));
    }
}
