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

        // Nothing here closes the stream, so no central directory is written — which is a
        // property of THIS FIXTURE, not of production. In the resource the same guarantee
        // comes from the refusal path stopping the sink BEFORE it closes the archive, so the
        // central directory is produced and discarded rather than sent; that is measured by
        // ExportRefusalReachesTheClientTest#theRefusalStopsForwardingBeforeClosing. Saying it was a
        // property of the throw itself was wrong, and shipped in the ledger for two rounds.
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

        // The TYPE alone proves nothing here: writeContent ends in a catch-all that wraps
        // anything at all into this same exception, so deleting the arm under test leaves an
        // NPE that arrives as an identical ExportRefusedException and keeps this green. Its
        // sibling one method up already asserts the message for that exact reason; this arm
        // — whose message was REWRITTEN in this batch because the old one asserted the
        // opposite of what null means — had nothing holding the new wording at all.
        ZipExporter.ExportRefusedException refused = assertThrows(
                ZipExporter.ExportRefusedException.class,
                () -> exporter.writeContentForTest(REPO, "att-d1", "report.pdf", zos, cs));
        assertTrue(refused.getMessage().contains("is not in the store"),
                "refused by the catch-all rather than by the absent-attachment arm, so the "
                        + "arm could be deleted with this test still green: "
                        + refused.getMessage());
        assertFalse(refused.getMessage().contains("could not be read"),
                "the message says the attachment could not be READ, which is what the "
                        + "delegate now refuses on; null here means it is ABSENT, and saying "
                        + "the stronger thing is what this wording was changed to stop: "
                        + refused.getMessage());
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

    @Test
    @DisplayName("a type definition the archive refers to and cannot read aborts it too")
    void anUnreadableTypeDefinitionAbortsTheZip() throws Exception {
        // The other half of the same package. Objects' .meta sidecars name their custom type;
        // .nemaki-types/ is what lets an importer recreate it. Skipping a definition with a
        // warn produced an archive that unpacks and cannot be restored — and nothing in it
        // said so. Deferred for one round as "a different arm", which it is: this arm carries
        // no bytes of content, only the shape the content needs.
        jp.aegif.nemaki.businesslogic.TypeService ts =
                mock(jp.aegif.nemaki.businesslogic.TypeService.class);
        when(ts.getTypeDefinition(eq(REPO), anyString())).thenReturn(null);

        ZipExporter exporter = new ZipExporter();
        ZipOutputStream zos = new ZipOutputStream(new ByteArrayOutputStream());

        assertThrows(ZipExporter.ExportRefusedException.class,
                () -> exporter.exportTypeDefinitions(REPO, java.util.Set.of("nemaki:custom"),
                        zos, ts),
                "a type used by an object in this export had no definition written, and the "
                        + "archive was finished anyway");
    }

    @Test
    @DisplayName("an unwired type service aborts rather than shipping a package with no types")
    void anUnwiredTypeServiceAborts() {
        ZipOutputStream zos = new ZipOutputStream(new ByteArrayOutputStream());
        assertThrows(ZipExporter.ExportRefusedException.class,
                () -> new ZipExporter().exportTypeDefinitions(REPO,
                        java.util.Set.of("nemaki:custom"), zos, null));
    }

    @Test
    @DisplayName("a readable type definition is still written — the control")
    void aReadableTypeDefinitionIsStillWritten() throws Exception {
        jp.aegif.nemaki.model.NemakiTypeDefinition td =
                new jp.aegif.nemaki.model.NemakiTypeDefinition();
        td.setTypeId("nemaki:custom");
        td.setId("nemaki:custom");
        td.setParentId("cmis:document");
        td.setBaseId(org.apache.chemistry.opencmis.commons.enums.BaseTypeId.CMIS_DOCUMENT);

        jp.aegif.nemaki.businesslogic.TypeService ts =
                mock(jp.aegif.nemaki.businesslogic.TypeService.class);
        when(ts.getTypeDefinition(eq(REPO), eq("nemaki:custom"))).thenReturn(td);

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(sink)) {
            new ZipExporter().exportTypeDefinitions(REPO,
                    java.util.Set.of("nemaki:custom"), zos, ts);
        }

        assertTrue(entryNames(sink.toByteArray()).stream()
                        .anyMatch(n -> n.contains("nemaki:custom")),
                "the refusal arms broke the ordinary type export: "
                        + entryNames(sink.toByteArray()));
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
    @DisplayName("a refused document leaves NO file behind — not even a 0-byte one")
    void aRefusedDocumentLeavesNoFile(@TempDir Path targetDir) throws Exception {
        // try-with-resources initialises left to right, so checking the stream inside the
        // body meant Files.newOutputStream had already created the file. The catch then left
        // a 0-byte file with no .meta.json beside it — and the importer reads a sidecar-less
        // content file as a document, so the bytes this arm REFUSED to export came back as
        // an empty record. The refusal produced the substitution it exists to prevent.
        Folder root = folder("f1", "records");
        Document broken = documentWithContent("d1", "report.pdf");

        AttachmentNode streamless = new AttachmentNode();
        streamless.setId("att-d1");
        streamless.setName("payload.bin");
        streamless.setLength(1234L);
        // No input stream: the attachment node is there and its body is not.

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(broken));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1"))).thenReturn(streamless);

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);

        assertFalse(result.errors.isEmpty(), "the refusal was not reported");
        assertFalse(Files.exists(targetDir.resolve("report.pdf")),
                "a 0-byte file was left where the content should have been; a round trip "
                        + "through the importer turns it into an empty record");
        // The REASON, not just the absence of a file. A review found that with the check
        // moved back inside the try-with-resources, the file IS created, the copy NPEs on
        // the null stream, and the cleanup added in the same round deletes it — so the
        // absence assertion above stayed green and the control measured nothing. What only
        // the reorder produces is this diagnosis instead of a null NPE message.
        // The file-absence assertion above can no longer fail — moving the stream check back
        // inside the copy would create the STAGING file, not the destination — so what
        // discriminates here is the REASON. An audit found the headline assertion dead and
        // the reason assertion alive; the reason is what this test is now for.
        assertTrue(result.errors.stream().anyMatch(e -> e.contains("produced no stream")),
                "the export did not report WHY it refused — with the stream checked only "
                        + "after the file is opened, the failure is an NPE from the copy and "
                        + "the cleanup hides it: " + result.errors);
    }

    @Test
    @DisplayName("a copy that dies PART WAY leaves no partial file either")
    void aMidCopyFailureLeavesNoPartialFile(@TempDir Path targetDir) throws Exception {
        // The reorder stops the STREAMLESS refusal from creating a file. A read that dies
        // mid-copy is the other half: the file is already open, so partial bytes stay on
        // disk with no .meta.json — and a sidecar-less content file is what the importer
        // reads as a document. A review noted the cleanup was written with no lock, so
        // removing it left everything green.
        Folder root = folder("f1", "records");
        Document broken = documentWithContent("d1", "report.pdf");

        AttachmentNode dying = new AttachmentNode();
        dying.setId("att-d1");
        dying.setName("payload.bin");
        dying.setLength(1234L);
        dying.setInputStream(new java.io.InputStream() {
            private int served = 0;

            @Override
            public int read() throws java.io.IOException {
                if (served++ < 4) {
                    return 'a';
                }
                throw new java.io.IOException("the attachment stream died part way");
            }
        });

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(broken));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1"))).thenReturn(dying);

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);

        assertFalse(result.errors.isEmpty(), "the mid-copy failure was not reported");
        assertFalse(Files.exists(targetDir.resolve("report.pdf")),
                "the partial file was left on disk with no metadata sidecar, which the "
                        + "importer takes as an empty record");
        // The line above stopped being able to fail when the copy moved to a staging file:
        // the destination is now written ONLY by a move after a complete copy, so no
        // implementation puts partial bytes there. The cleanup this test exists for moved
        // with it, and an audit found the test still passing with that cleanup deleted —
        // the same sentence its own comment above records from the round before.
        assertTrue(leftoverPartFiles(targetDir).isEmpty(),
                "the half-written copy was left on disk: " + leftoverPartFiles(targetDir));
    }

    @Test
    @DisplayName("a copy that dies part way over an EXISTING export leaves it intact")
    void aMidCopyFailureDoesNotDestroyThePreviousExport(@TempDir Path targetDir)
            throws Exception {
        // The case the two earlier attempts both destroyed, and that no test reached: the
        // combination of allowOverwrite=true, a complete earlier export at the destination,
        // and a read that dies MID-COPY.
        //
        // Deleting the target unconditionally destroyed it. An ownership flag was added and
        // still destroyed it, because the flag records "this invocation OPENED the file" and
        // overwrite opens with TRUNCATE_EXISTING — so by the time the copy died the earlier
        // export was already empty, and the cleanup removed what was left. The failed export
        // ended with neither the old artefact nor the new one.
        //
        // The two existing tests miss it from opposite sides: the mid-copy one writes to a
        // path that does not exist with overwrite off, and the existing-file one fails
        // before the destination is touched at all.
        Folder root = folder("f1", "records");
        Document broken = documentWithContent("d1", "report.pdf");

        Path existing = targetDir.resolve("report.pdf");
        Files.writeString(existing, "an earlier, complete export");

        AttachmentNode dying = new AttachmentNode();
        dying.setId("att-d1");
        dying.setName("payload.bin");
        dying.setLength(1234L);
        dying.setInputStream(new java.io.InputStream() {
            private int served = 0;

            @Override
            public int read() throws java.io.IOException {
                if (served++ < 4) {
                    return 'z';
                }
                throw new java.io.IOException("the attachment stream died part way");
            }
        });

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(broken));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1"))).thenReturn(dying);

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), true, cs);

        assertFalse(result.errors.isEmpty(), "the mid-copy failure was not reported");
        assertTrue(Files.exists(existing),
                "a failed export destroyed the previous, complete one");
        assertEquals("an earlier, complete export", Files.readString(existing),
                "the previous export was truncated or partially overwritten by an export "
                        + "that then failed — it must be untouched unless the copy finishes");
        assertTrue(leftoverPartFiles(targetDir).isEmpty(),
                "the staging file was left behind: " + leftoverPartFiles(targetDir));
    }

    @Test
    @DisplayName("the VERSION arm keeps an existing export through a mid-copy death too")
    void aMidCopyVersionFailureDoesNotDestroyThePreviousExport(@TempDir Path targetDir)
            throws Exception {
        // The twin. Every fix in this file has landed on one arm and missed the other —
        // four times, each caught by a different reviewer — so the copy is now ONE method
        // with two callers, and this test is what says the version caller reaches it.
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

        Path existing = targetDir.resolve("report.pdf.v1");
        Files.writeString(existing, "an earlier, complete version export");

        AttachmentNode dying = new AttachmentNode();
        dying.setId("att-d0");
        dying.setName("payload.bin");
        dying.setLength(1234L);
        dying.setInputStream(new java.io.InputStream() {
            private int served = 0;

            @Override
            public int read() throws java.io.IOException {
                if (served++ < 4) {
                    return 'z';
                }
                throw new java.io.IOException("the version stream died part way");
            }
        });

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(latest));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "current".getBytes()));
        when(cs.getVersionSeries(eq(REPO), eq(latest))).thenReturn(series);
        when(cs.getAllVersions(org.mockito.ArgumentMatchers.any(), eq(REPO), eq("vs-1")))
                .thenReturn(new java.util.ArrayList<>(Arrays.asList(latest, earlier)));
        when(cs.getAttachment(eq(REPO), eq("att-d0"))).thenReturn(dying);

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), true, cs);

        assertFalse(result.errors.isEmpty(), "the mid-copy version failure was not reported");
        // Existence first. Without it the truncate-then-delete shape arrives as a
        // NoSuchFileException out of readString — a stack trace, where what the run should
        // report is the claim that failed.
        assertTrue(Files.exists(existing),
                "a failed version export DELETED the previous, complete one");
        assertEquals("an earlier, complete version export", Files.readString(existing),
                "a failed version export truncated or overwrote the previous, complete one");
        assertTrue(leftoverPartFiles(targetDir).isEmpty(),
                "the staging file was left behind: " + leftoverPartFiles(targetDir));
    }

    @Test
    @DisplayName("overwriting an existing export still REPLACES it — the control")
    void aSuccessfulOverwriteStillReplacesTheFile(@TempDir Path targetDir) throws Exception {
        // The other half of the staging change, and it had no test either. Every assertion
        // added for the destructive case is satisfied by a copy that never writes anything
        // at all, so "the previous export survives" has to be paired with "a good export
        // still replaces it" or the safe answer is indistinguishable from a broken one.
        Folder root = folder("f1", "records");
        Document doc = documentWithContent("d1", "report.pdf");

        Path existing = targetDir.resolve("report.pdf");
        Files.writeString(existing, "an earlier, complete export");

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(doc));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "the newer export".getBytes()));

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), true, cs);

        assertTrue(result.errors.isEmpty(), "an ordinary overwrite was refused: "
                + result.errors);
        assertEquals("the newer export", Files.readString(existing),
                "the export succeeded but the destination still holds the OLD bytes, so the "
                        + "staging copy is never moved onto it");
        assertTrue(leftoverPartFiles(targetDir).isEmpty(),
                "a successful export left its staging file behind: "
                        + leftoverPartFiles(targetDir));
    }

    @Test
    @DisplayName("staging does not make exported files owner-only, and does not downgrade "
            + "a file it replaces")
    void aStagedExportKeepsTheModeAnOrdinaryCreateWouldGive(@TempDir Path targetDir)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                targetDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are what this measures");

        // Files.createTempFile creates 0600 by design and Files.move replaces the inode, so
        // switching from "open the destination" to "stage and move" silently turned every
        // exported file from umask-derived 0644 into 0600 — and turned an existing 0644 file
        // into 0600 when re-exported over. An export directory read by a backup agent or
        // another service account stops being readable, with nothing in the response saying
        // so. Two reviewers read the staging change without catching it.
        Path reference = targetDir.resolve("what-an-ordinary-create-gives");
        Files.newOutputStream(reference, java.nio.file.StandardOpenOption.CREATE_NEW).close();
        var expected = Files.getPosixFilePermissions(reference);

        Folder root = folder("f1", "records");
        Document doc = documentWithContent("d1", "report.pdf");
        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(doc));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "bytes".getBytes()));

        ExportResult fresh = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);
        assertTrue(fresh.errors.isEmpty(), "the export failed: " + fresh.errors);
        assertEquals(expected, Files.getPosixFilePermissions(targetDir.resolve("report.pdf")),
                "the exported file does not carry the mode an ordinary create would give — "
                        + "staging made it owner-only");

        // And the replacement case: a destination that is readable stays readable.
        Path widened = targetDir.resolve("report.pdf");
        var readableByAll = java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--");
        Files.setPosixFilePermissions(widened, readableByAll);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "newer bytes".getBytes()));

        ExportResult again = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), true, cs);
        assertTrue(again.errors.isEmpty(), "the overwrite failed: " + again.errors);
        assertEquals(readableByAll, Files.getPosixFilePermissions(widened),
                "re-exporting over an existing file stripped its group and other read");
    }

    @Test
    @DisplayName("an ordinary export with overwrite OFF still writes the document — the "
            + "default path, which had no lock at all")
    void aPlainExportStillWritesTheDocument(@TempDir Path targetDir) throws Exception {
        // allowOverwrite=false is what the endpoint passes by default, and the only test
        // proving the staged copy is ever INSTALLED used allowOverwrite=true. Replacing the
        // else branch of the move with a delete left all nineteen tests green while the
        // exporter produced no document files whatsoever. The one-arm shape again, in the
        // arm that runs most often — found by an audit, not by a run.
        Folder root = folder("f1", "records");
        Document doc = documentWithContent("d1", "report.pdf");
        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(doc));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "the exported bytes".getBytes()));

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);

        assertTrue(result.errors.isEmpty(), "an ordinary export failed: " + result.errors);
        // Existence first: without it the "no move at all" case arrives as a
        // NoSuchFileException out of readString, and a run should report the claim that
        // failed rather than a stack trace. The runner called that out as firing for the
        // wrong reason, which is the distinction it exists to make.
        assertTrue(Files.exists(targetDir.resolve("report.pdf")),
                "the export reported success and wrote no document file at all");
        assertEquals("the exported bytes",
                Files.readString(targetDir.resolve("report.pdf")),
                "the export reported success and the document file holds the wrong bytes");
        assertTrue(leftoverPartFiles(targetDir).isEmpty(),
                "a successful export left staging files: " + leftoverPartFiles(targetDir));
    }

    @Test
    @DisplayName("the metadata sidecars go through the staging helper too — no direct write")
    void theSidecarsGoThroughTheStagingHelperToo() throws Exception {
        // Round 5 staged the CONTENT copies and left both sidecar writes on FileWriter,
        // which with allowOverwrite TRUNCATES the existing sidecar before writing — so a
        // mid-write failure destroys the old complete metadata while the bytes beside it
        // are protected. The importer then reads the document without its type and
        // properties. Same-file one-arm shape, fifth occurrence; a round-6 sibling sweep
        // caught it. Structural, because a mid-write disk-full cannot be injected through
        // the fixtures: what is decidable at the source is that every write to the export
        // tree goes through the one protected method.
        String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java"));
        assertFalse(source.contains("new FileWriter("),
                "a direct FileWriter write is back in the exporter, so an overwrite "
                        + "truncates the destination before the new bytes are safe");
        int calls = countOf(source, "copyLeavingTheTargetIntactOnFailure(") - 1;
        assertEquals(4, calls,
                "the staging helper must be called from exactly four sites — document "
                        + "content, version content, document sidecar, version sidecar — "
                        + "but " + calls + " call(s) were found; an arm has been detached "
                        + "from the protection");
    }

    @Test
    @DisplayName("a mode that could not be set reaches the RESPONSE, not only the log")
    void aModeFailureIsReportedNotJustLogged() throws Exception {
        // The round-5 mode fix caught its own failure and only logged — fail-open: the
        // export said SUCCESS while the file came out 0600 and a backup agent cannot read
        // it. A POSIX permission failure cannot be injected through these fixtures either,
        // so the decidable property is that the catch feeds result.errors (which is what
        // turns the response status to "partial").
        String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java"));
        String body = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "private static void giveTheStagingFileTheModeTheDestinationShouldHave(");
        assertTrue(body.contains("result.errors.add("),
                "the mode-failure catch no longer reports into result.errors, so an export "
                        + "whose file came out unreadable reports clean success: " + body);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    @Test
    @DisplayName("overwriting an existing SIDECAR still replaces it — the staged positive")
    void anOverwrittenSidecarIsReplacedToo(@TempDir Path targetDir) throws Exception {
        // The positive half of routing sidecars through the helper: a helper that refused
        // or skipped the metadata write would leave stale metadata beside new content,
        // which is quieter than the truncation it replaced.
        Folder root = folder("f1", "records");
        Document doc = documentWithContent("d1", "report.pdf");

        Path metaPath = targetDir.resolve("report.pdf" + ImportExportUtils.META_SUFFIX);
        Files.writeString(metaPath, "{\"stale\": true}");

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(doc));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "bytes".getBytes()));

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), true, cs);

        assertTrue(result.errors.isEmpty(), "the overwrite failed: " + result.errors);
        String written = Files.readString(metaPath);
        assertFalse(written.contains("stale"),
                "the sidecar still holds the OLD metadata beside newly exported content");
        assertTrue(written.contains("report.pdf"),
                "the replaced sidecar does not describe the document: " + written);
        assertTrue(leftoverPartFiles(targetDir).isEmpty(),
                "the sidecar write left staging files: " + leftoverPartFiles(targetDir));
    }

    /** Staging files the copy should have cleaned up or moved. */
    private static List<String> leftoverPartFiles(Path dir) throws Exception {
        try (var entries = Files.list(dir)) {
            return entries.map(pth -> pth.getFileName().toString())
                    .filter(n -> n.endsWith(".part"))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    @Test
    @DisplayName("a failure BEFORE the file is opened does not delete an existing one")
    void aFailureBeforeOpeningKeepsAnExistingFile(@TempDir Path targetDir) throws Exception {
        // The cleanup added for the mid-copy case deleted unconditionally at first, so a
        // failure that happened before this export opened anything — the attachment read
        // itself — removed the file a PREVIOUS, successful export had left. A refusal that
        // destroys data is worse than the partial file it was cleaning up. A review caught
        // it before it ran.
        Folder root = folder("f1", "records");
        Document broken = documentWithContent("d1", "report.pdf");

        Path existing = targetDir.resolve("report.pdf");
        Files.writeString(existing, "an earlier, complete export");

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(broken));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenThrow(new IllegalStateException("the attachment could not be read"));

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), true, cs);

        assertFalse(result.errors.isEmpty(), "the failure was not reported");
        assertTrue(Files.exists(existing),
                "the refusal deleted a file this export never created — an earlier, valid "
                        + "export was destroyed by a failed one");
        assertEquals("an earlier, complete export", Files.readString(existing),
                "the earlier export's content was replaced");
        // Honest note, because the two assertions above can no longer fail: the deletion
        // they were written against lived in a per-arm cleanup that the staging rewrite
        // removed, and this fixture never enters the copy at all (getAttachment throws
        // first). They are kept as a statement of the rule, not as a measurement of it —
        // aMidCopyFailureDoesNotDestroyThePreviousExport is what measures it now. What this
        // one can still decide is that a refusal before the copy leaves nothing behind.
        assertTrue(leftoverPartFiles(targetDir).isEmpty(),
                "a failure before the copy still created a staging file: "
                        + leftoverPartFiles(targetDir));
    }

    @Test
    @DisplayName("a VERSION failure before the file is opened keeps an existing one")
    void aVersionFailureBeforeOpeningKeepsAnExistingFile(@TempDir Path targetDir)
            throws Exception {
        // The version twin of the document-arm data-loss lock. The ownership flag exists in
        // both arms; only the document one was measured, which is the NL→NM gap a third
        // time in the same file.
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

        Path existing = targetDir.resolve("report.pdf.v1");
        Files.writeString(existing, "an earlier, complete version export");

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(latest));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "current".getBytes()));
        when(cs.getVersionSeries(eq(REPO), eq(latest))).thenReturn(series);
        when(cs.getAllVersions(org.mockito.ArgumentMatchers.any(), eq(REPO), eq("vs-1")))
                .thenReturn(new java.util.ArrayList<>(Arrays.asList(latest, earlier)));
        when(cs.getAttachment(eq(REPO), eq("att-d0")))
                .thenThrow(new IllegalStateException("the attachment could not be read"));

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), true, cs);

        assertFalse(result.errors.isEmpty());
        assertTrue(Files.exists(existing),
                "the version refusal deleted a file this export never created");
        assertEquals("an earlier, complete version export", Files.readString(existing));
        // Same standing as its document twin above: kept as the rule, measured elsewhere.
        assertTrue(leftoverPartFiles(targetDir).isEmpty(),
                "a version failure before the copy still created a staging file: "
                        + leftoverPartFiles(targetDir));
    }

    @Test
    @DisplayName("a VERSION copy that dies part way leaves no partial file — the twin of "
            + "the document arm")
    void aMidCopyVersionFailureLeavesNoPartialFile(@TempDir Path targetDir) throws Exception {
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

        AttachmentNode dying = new AttachmentNode();
        dying.setId("att-d0");
        dying.setName("payload.bin");
        dying.setLength(1234L);
        dying.setInputStream(new java.io.InputStream() {
            private int served = 0;

            @Override
            public int read() throws java.io.IOException {
                if (served++ < 4) {
                    return 'a';
                }
                throw new java.io.IOException("the version stream died part way");
            }
        });

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(latest));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "current".getBytes()));
        when(cs.getVersionSeries(eq(REPO), eq(latest))).thenReturn(series);
        when(cs.getAllVersions(org.mockito.ArgumentMatchers.any(), eq(REPO), eq("vs-1")))
                .thenReturn(new java.util.ArrayList<>(Arrays.asList(latest, earlier)));
        when(cs.getAttachment(eq(REPO), eq("att-d0"))).thenReturn(dying);

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);

        assertFalse(result.errors.isEmpty(), "the mid-copy version failure was not reported");
        assertFalse(Files.exists(targetDir.resolve("report.pdf.v1")),
                "a partial version file was left on disk with no sidecar");
        assertTrue(leftoverPartFiles(targetDir).isEmpty(),
                "the half-written version copy was left on disk: "
                        + leftoverPartFiles(targetDir));
    }

    @Test
    @DisplayName("a refused VERSION leaves no file either — the twin NL did not cover")
    void aRefusedVersionLeavesNoFile(@TempDir Path targetDir) throws Exception {
        // The document body and the version body have the same open-before-check hazard, and
        // the first control covered only the first of them. A one-arm lock is the shape this
        // batch keeps finding; a review pointed at this one.
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

        AttachmentNode streamless = new AttachmentNode();
        streamless.setId("att-d0");
        streamless.setName("payload.bin");
        streamless.setLength(1234L);

        ContentService cs = mock(ContentService.class);
        when(cs.getChildren(eq(REPO), eq("f1"))).thenReturn(Arrays.<Content>asList(latest));
        when(cs.lastUnreadableChildCount()).thenReturn(0);
        when(cs.getAttachment(eq(REPO), eq("att-d1")))
                .thenReturn(readableAttachment("att-d1", "current".getBytes()));
        when(cs.getVersionSeries(eq(REPO), eq(latest))).thenReturn(series);
        when(cs.getAllVersions(org.mockito.ArgumentMatchers.any(), eq(REPO), eq("vs-1")))
                .thenReturn(new java.util.ArrayList<>(Arrays.asList(latest, earlier)));
        when(cs.getAttachment(eq(REPO), eq("att-d0"))).thenReturn(streamless);

        ExportResult result = new FilesystemExporter().exportToFilesystemDirectory(
                REPO, root, targetDir, mock(CallContext.class), false, cs);

        assertFalse(result.errors.isEmpty(), "the version refusal was not reported");
        assertFalse(Files.exists(targetDir.resolve("report.pdf.v1")),
                "a 0-byte version file was left behind, which the importer reads as an "
                        + "empty record");
        // The file-absence assertion above can no longer fail — moving the stream check back
        // inside the copy would create the STAGING file, not the destination — so what
        // discriminates here is the REASON. An audit found the headline assertion dead and
        // the reason assertion alive; the reason is what this test is now for.
        assertTrue(result.errors.stream().anyMatch(e -> e.contains("produced no stream")),
                "same as the document arm: without the reorder the failure is an NPE and "
                        + "the cleanup hides the file, so only the reason discriminates: "
                        + result.errors);
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
