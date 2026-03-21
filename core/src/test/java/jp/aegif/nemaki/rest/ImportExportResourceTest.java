package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.rest.importexport.ImportExportUtils;
import jp.aegif.nemaki.rest.importexport.ZipImporter;
import jp.aegif.nemaki.rest.purview.lineage.PurviewImportExportLineageService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

/**
 * Unit tests for Import/Export functionality.
 *
 * Tests security and validation functionality including:
 * - ZIP path traversal prevention
 * - Size limit enforcement
 * - Version sorting logic
 *
 * Methods under test have been moved to ImportExportUtils and ZipImporter
 * as part of the class decomposition refactoring.
 */
public class ImportExportResourceTest {

    private ZipImporter zipImporter;

    @BeforeEach
    public void setUp() throws Exception {
        zipImporter = new ZipImporter();
    }

    // ========== isValidZipEntryName Tests ==========

    @Test
    public void testValidZipEntryNameSimple() {
        assertTrue(ImportExportUtils.isValidZipEntryName("test.txt"), "Simple filename should be valid");
    }

    @Test
    public void testValidZipEntryNameWithPath() {
        assertTrue(ImportExportUtils.isValidZipEntryName("folder/test.txt"), "Path with subdirectory should be valid");
    }

    @Test
    public void testValidZipEntryNameDeepPath() {
        assertTrue(ImportExportUtils.isValidZipEntryName("a/b/c/d/test.txt"), "Deep path should be valid");
    }

    @Test
    public void testInvalidZipEntryNameNull() {
        assertFalse(ImportExportUtils.isValidZipEntryName(null), "Null should be invalid");
    }

    @Test
    public void testInvalidZipEntryNameEmpty() {
        assertFalse(ImportExportUtils.isValidZipEntryName(""), "Empty string should be invalid");
    }

    @Test
    public void testInvalidZipEntryNameWithDoubleDot() {
        assertFalse(ImportExportUtils.isValidZipEntryName("../test.txt"), "Path with .. should be invalid");
    }

    @Test
    public void testInvalidZipEntryNameWithDoubleDotMiddle() {
        assertFalse(ImportExportUtils.isValidZipEntryName("folder/../test.txt"), "Path with .. in middle should be invalid");
    }

    @Test
    public void testInvalidZipEntryNameAbsolutePath() {
        assertFalse(ImportExportUtils.isValidZipEntryName("/etc/passwd"), "Absolute path starting with / should be invalid");
    }

    @Test
    public void testInvalidZipEntryNameWindowsBackslash() {
        assertFalse(ImportExportUtils.isValidZipEntryName("folder\\test.txt"), "Path with backslash should be invalid");
    }

    @Test
    public void testInvalidZipEntryNameWindowsDriveLetter() {
        assertFalse(ImportExportUtils.isValidZipEntryName("C:\\Windows\\System32"), "Windows drive letter path should be invalid");
    }

    @Test
    public void testInvalidZipEntryNameWithColon() {
        assertFalse(ImportExportUtils.isValidZipEntryName("C:test.txt"), "Path with colon should be invalid");
    }

    @Test
    public void testInvalidZipEntryNameNullByte() {
        assertFalse(ImportExportUtils.isValidZipEntryName("test\0.txt"), "Path with null byte should be invalid");
    }

    @Test
    public void testInvalidZipEntryNameStartsWithBackslash() {
        assertFalse(ImportExportUtils.isValidZipEntryName("\\test.txt"), "Path starting with backslash should be invalid");
    }

    // ========== Version Sorting Tests ==========

    @Test
    public void testExtractVersionNumber() {
        assertEquals(1, ImportExportUtils.extractVersionNumber("file.txt.v1"), "Should extract version 1");
        assertEquals(2, ImportExportUtils.extractVersionNumber("file.txt.v2"), "Should extract version 2");
        assertEquals(10, ImportExportUtils.extractVersionNumber("file.txt.v10"), "Should extract version 10");
        assertEquals(99, ImportExportUtils.extractVersionNumber("file.txt.v99"), "Should extract version 99");
    }

    @Test
    public void testExtractVersionNumberNoVersion() {
        assertEquals(0, ImportExportUtils.extractVersionNumber("file.txt"), "Should return 0 for no version");
    }

    @Test
    public void testExtractVersionNumberMiddle() {
        assertEquals(3, ImportExportUtils.extractVersionNumber("file.v3.txt"), "Should extract version from middle");
    }

    // ========== isVersionFile Tests ==========

    @Test
    public void testIsVersionFileTrue() {
        assertTrue(ImportExportUtils.isVersionFile("file.txt.v1"), "file.txt.v1 should be version file");
        assertTrue(ImportExportUtils.isVersionFile("file.txt.v10"), "file.txt.v10 should be version file");
        assertTrue(ImportExportUtils.isVersionFile("folder/file.txt.v2"), "folder/file.txt.v2 should be version file");
    }

    @Test
    public void testIsVersionFileFalse() {
        assertFalse(ImportExportUtils.isVersionFile("file.txt"), "file.txt should not be version file");
        assertFalse(ImportExportUtils.isVersionFile("file.meta.json"), "file.meta.json should not be version file");
    }

    // ========== isVersionFileFor Tests ==========

    @Test
    public void testIsVersionFileForTrue() {
        assertTrue(ImportExportUtils.isVersionFileFor("file.txt.v1", "file.txt"), "file.txt.v1 should be version of file.txt");
        assertTrue(ImportExportUtils.isVersionFileFor("file.txt.v10", "file.txt"), "file.txt.v10 should be version of file.txt");
    }

    @Test
    public void testIsVersionFileForFalse() {
        assertFalse(ImportExportUtils.isVersionFileFor("other.txt.v1", "file.txt"), "other.txt.v1 should not be version of file.txt");
        assertFalse(ImportExportUtils.isVersionFileFor("file.txt", "file.txt"), "file.txt should not be version of file.txt");
    }

    // ========== getFileName Tests ==========

    @Test
    public void testGetFileName() {
        assertEquals("test.txt", ImportExportUtils.getFileName("folder/test.txt"), "Should extract filename");
        assertEquals("test.txt", ImportExportUtils.getFileName("test.txt"), "Should handle no folder");
        assertEquals("test.txt", ImportExportUtils.getFileName("a/b/c/test.txt"), "Should handle deep path");
    }

    // ========== getParentPath Tests ==========

    @Test
    public void testGetParentPath() {
        assertEquals("folder", ImportExportUtils.getParentPath("folder/test.txt"), "Should extract parent path");
        assertEquals("", ImportExportUtils.getParentPath("test.txt"), "Should handle no parent");
        assertEquals("a/b/c", ImportExportUtils.getParentPath("a/b/c/test.txt"), "Should handle deep path");
    }

    // ========== guessMimeType Tests ==========

    @Test
    public void testGuessMimeTypePdf() {
        assertEquals("application/pdf", ImportExportUtils.guessMimeType("test.pdf"));
    }

    @Test
    public void testGuessMimeTypeTxt() {
        assertEquals("text/plain", ImportExportUtils.guessMimeType("test.txt"));
    }

    @Test
    public void testGuessMimeTypeHtml() {
        assertEquals("text/html", ImportExportUtils.guessMimeType("test.html"));
        assertEquals("text/html", ImportExportUtils.guessMimeType("test.htm"));
    }

    @Test
    public void testGuessMimeTypeJson() {
        assertEquals("application/json", ImportExportUtils.guessMimeType("test.json"));
    }

    @Test
    public void testGuessMimeTypeXml() {
        assertEquals("application/xml", ImportExportUtils.guessMimeType("test.xml"));
    }

    @Test
    public void testGuessMimeTypeDocx() {
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ImportExportUtils.guessMimeType("test.docx"));
    }

    @Test
    public void testGuessMimeTypeUnknown() {
        assertEquals("application/octet-stream", ImportExportUtils.guessMimeType("test.xyz"));
    }

    @Test
    public void testGuessMimeTypeNull() {
        assertEquals("application/octet-stream", ImportExportUtils.guessMimeType(null));
    }

    @Test
    public void testGuessMimeTypeCaseInsensitive() {
        assertEquals("application/pdf", ImportExportUtils.guessMimeType("TEST.PDF"));
    }

    // ========== Size Limit Tests ==========

    @Test
    public void testReadZipEntryWithSizeLimit() throws Exception {
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("small.txt");
            zos.putNextEntry(entry);
            byte[] content = "Hello World".getBytes();
            zos.write(content);
            zos.closeEntry();
        }

        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = zipImporter.readZipEntryWithLimit(zf, "small.txt", ImportExportUtils.MAX_METADATA_SIZE);
            assertNotNull(result, "Should read small file successfully");
            assertEquals("Hello World", new String(result), "Content should match");
        }
    }

    @Test
    public void testReadZipEntryNonExistent() throws Exception {
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("exists.txt");
            zos.putNextEntry(entry);
            zos.write("content".getBytes());
            zos.closeEntry();
        }

        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = zipImporter.readZipEntryWithLimit(zf, "nonexistent.txt", ImportExportUtils.MAX_METADATA_SIZE);
            assertNull(result, "Should return null for non-existent entry");
        }
    }

    @Test
    public void testReadZipEntryDirectory() throws Exception {
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry dirEntry = new ZipEntry("folder/");
            zos.putNextEntry(dirEntry);
            zos.closeEntry();
        }

        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = zipImporter.readZipEntryWithLimit(zf, "folder/", ImportExportUtils.MAX_METADATA_SIZE);
            assertNull(result, "Should return null for directory entry");
        }
    }

    @Test
    public void testReadZipEntryExceedsSizeLimit() throws Exception {
        // Use a small custom limit instead of MAX_SINGLE_FILE_SIZE to avoid creating huge files
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();

        long customLimit = 500; // 500 bytes

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("large.txt");
            zos.putNextEntry(entry);
            byte[] content = new byte[1024]; // 1KB - exceeds the 500 byte limit
            java.util.Arrays.fill(content, (byte) 'A');
            zos.write(content);
            zos.closeEntry();
        }

        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = zipImporter.readZipEntryWithLimit(zf, "large.txt", customLimit);
            assertNull(result, "Should return null for file exceeding size limit");
        }
    }

    @Test
    public void testReadZipEntryWithUnknownSize() throws Exception {
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("test.txt");
            zos.putNextEntry(entry);
            zos.write("Test content".getBytes());
            zos.closeEntry();
        }

        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = zipImporter.readZipEntryWithLimit(zf, "test.txt", ImportExportUtils.MAX_METADATA_SIZE);
            assertNotNull(result, "Should read file with unknown size");
            assertEquals("Test content", new String(result), "Content should match");
        }
    }

    @Test
    public void testReadZipEntryWithCustomSizeLimit() throws Exception {
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();

        byte[] content = new byte[100];
        java.util.Arrays.fill(content, (byte) 'X');

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("medium.txt");
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }

        try (ZipFile zf = new ZipFile(tempZip)) {
            // Should succeed with limit of 200 bytes
            byte[] result = zipImporter.readZipEntryWithLimit(zf, "medium.txt", 200);
            assertNotNull(result, "Should read file within limit");
            assertEquals(100, result.length, "Content length should match");

            // Should fail with limit of 50 bytes
            byte[] resultSmall = zipImporter.readZipEntryWithLimit(zf, "medium.txt", 50);
            assertNull(resultSmall, "Should return null for file exceeding custom limit");
        }
    }

    @Test
    public void testReadZipEntryWithKnownSizeExceedsLimit() throws Exception {
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();

        byte[] content = new byte[1024];
        java.util.Arrays.fill(content, (byte) 'Y');

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("kilobyte.txt");
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }

        try (ZipFile zf = new ZipFile(tempZip)) {
            // Should fail with limit of 512 bytes (entry size is known as 1024)
            byte[] result = zipImporter.readZipEntryWithLimit(zf, "kilobyte.txt", 512);
            assertNull(result, "Should return null when known size exceeds limit");
        }
    }

    @Test
    public void testPublishZipFolderExportLineageDelegatesToPurviewService() {
        TestableImportExportResource resource = new TestableImportExportResource();
        PurviewImportExportLineageService lineageService = mock(PurviewImportExportLineageService.class);
        resource.setPurviewImportExportLineageService(lineageService);

        resource.publishZipFolderExportLineageForTest("bedroom", "folder-001", "Contracts", "alice", 4);

        verify(lineageService).upsertZipFolderExportLineage("bedroom", "folder-001", "Contracts", "alice", 4);
    }

    @Test
    public void testPublishUploadedImportLineageDelegatesToPurviewService() {
        TestableImportExportResource resource = new TestableImportExportResource();
        PurviewImportExportLineageService lineageService = mock(PurviewImportExportLineageService.class);
        resource.setPurviewImportExportLineageService(lineageService);

        resource.publishUploadedImportLineageForTest("bedroom", "folder-001", "zip-upload", "alice", 5);

        verify(lineageService).upsertUploadedImportLineage("bedroom", "folder-001", "zip-upload", "alice", 5);
    }

    @Test
    public void testPublishSelectedObjectsExportLineageDelegatesToPurviewService() {
        TestableImportExportResource resource = new TestableImportExportResource();
        PurviewImportExportLineageService lineageService = mock(PurviewImportExportLineageService.class);
        Document document = new Document();
        document.setId("doc-001");
        document.setName("Quarterly Report");
        document.setObjectType("cmis:document");
        Folder folder = new Folder();
        folder.setId("folder-001");
        folder.setName("Contracts");
        folder.setObjectType("cmis:folder");
        resource.setPurviewImportExportLineageService(lineageService);

        resource.publishSelectedObjectsExportLineageForTest("bedroom", List.of(document, folder), "alice", 7);

        verify(lineageService).upsertSelectedObjectsExportLineage("bedroom", List.of(document, folder), "alice", 7);
    }

    @Test
    public void testPublishSelectedObjectsExportLineageSkipsWhenObjectCountIsZero() {
        TestableImportExportResource resource = new TestableImportExportResource();
        PurviewImportExportLineageService lineageService = mock(PurviewImportExportLineageService.class);
        Document document = new Document();
        document.setId("doc-001");
        document.setName("Quarterly Report");
        document.setObjectType("cmis:document");
        resource.setPurviewImportExportLineageService(lineageService);

        resource.publishSelectedObjectsExportLineageForTest("bedroom", List.of(document), "alice", 0);

        verify(lineageService, never()).upsertSelectedObjectsExportLineage("bedroom", List.of(document), "alice", 0);
    }

    @Test
    public void testPublishUploadedImportLineageSkipsWhenObjectCountIsZero() {
        TestableImportExportResource resource = new TestableImportExportResource();
        PurviewImportExportLineageService lineageService = mock(PurviewImportExportLineageService.class);
        resource.setPurviewImportExportLineageService(lineageService);

        resource.publishUploadedImportLineageForTest("bedroom", "folder-001", "zip-upload", "alice", 0);

        verify(lineageService, never()).upsertUploadedImportLineage("bedroom", "folder-001", "zip-upload", "alice", 0);
    }

    private static class TestableImportExportResource extends ImportExportResource {

        void publishZipFolderExportLineageForTest(
                String repositoryId,
                String folderId,
                String folderName,
                String requestedBy,
                long objectCount) {
            publishZipFolderExportLineage(repositoryId, folderId, folderName, requestedBy, objectCount);
        }

        void publishSelectedObjectsExportLineageForTest(
                String repositoryId,
                List<Content> contents,
                String requestedBy,
                long objectCount) {
            publishSelectedObjectsExportLineage(repositoryId, contents, requestedBy, objectCount);
        }

        void publishUploadedImportLineageForTest(
                String repositoryId,
                String folderId,
                String importMode,
                String requestedBy,
                long objectCount) {
            publishUploadedImportLineage(repositoryId, folderId, importMode, requestedBy, objectCount);
        }
    }
}
