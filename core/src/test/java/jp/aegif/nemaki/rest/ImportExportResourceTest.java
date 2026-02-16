package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.rest.importexport.ImportExportUtils;
import jp.aegif.nemaki.rest.importexport.ZipImporter;

import org.junit.Test;
import org.junit.Before;
import org.junit.Ignore;
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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

    @Before
    public void setUp() throws Exception {
        zipImporter = new ZipImporter();
    }

    // ========== isValidZipEntryName Tests ==========

    @Test
    public void testValidZipEntryNameSimple() {
        assertTrue("Simple filename should be valid",
                ImportExportUtils.isValidZipEntryName("test.txt"));
    }

    @Test
    public void testValidZipEntryNameWithPath() {
        assertTrue("Path with subdirectory should be valid",
                ImportExportUtils.isValidZipEntryName("folder/test.txt"));
    }

    @Test
    public void testValidZipEntryNameDeepPath() {
        assertTrue("Deep path should be valid",
                ImportExportUtils.isValidZipEntryName("a/b/c/d/test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameNull() {
        assertFalse("Null should be invalid",
                ImportExportUtils.isValidZipEntryName(null));
    }

    @Test
    public void testInvalidZipEntryNameEmpty() {
        assertFalse("Empty string should be invalid",
                ImportExportUtils.isValidZipEntryName(""));
    }

    @Test
    public void testInvalidZipEntryNameWithDoubleDot() {
        assertFalse("Path with .. should be invalid",
                ImportExportUtils.isValidZipEntryName("../test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameWithDoubleDotMiddle() {
        assertFalse("Path with .. in middle should be invalid",
                ImportExportUtils.isValidZipEntryName("folder/../test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameAbsolutePath() {
        assertFalse("Absolute path starting with / should be invalid",
                ImportExportUtils.isValidZipEntryName("/etc/passwd"));
    }

    @Test
    public void testInvalidZipEntryNameWindowsBackslash() {
        assertFalse("Path with backslash should be invalid",
                ImportExportUtils.isValidZipEntryName("folder\\test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameWindowsDriveLetter() {
        assertFalse("Windows drive letter path should be invalid",
                ImportExportUtils.isValidZipEntryName("C:\\Windows\\System32"));
    }

    @Test
    public void testInvalidZipEntryNameWithColon() {
        assertFalse("Path with colon should be invalid",
                ImportExportUtils.isValidZipEntryName("C:test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameNullByte() {
        assertFalse("Path with null byte should be invalid",
                ImportExportUtils.isValidZipEntryName("test\0.txt"));
    }

    @Test
    public void testInvalidZipEntryNameStartsWithBackslash() {
        assertFalse("Path starting with backslash should be invalid",
                ImportExportUtils.isValidZipEntryName("\\test.txt"));
    }

    // ========== Version Sorting Tests ==========

    @Test
    public void testExtractVersionNumber() {
        assertEquals("Should extract version 1", 1, ImportExportUtils.extractVersionNumber("file.txt.v1"));
        assertEquals("Should extract version 2", 2, ImportExportUtils.extractVersionNumber("file.txt.v2"));
        assertEquals("Should extract version 10", 10, ImportExportUtils.extractVersionNumber("file.txt.v10"));
        assertEquals("Should extract version 99", 99, ImportExportUtils.extractVersionNumber("file.txt.v99"));
    }

    @Test
    public void testExtractVersionNumberNoVersion() {
        assertEquals("Should return 0 for no version", 0, ImportExportUtils.extractVersionNumber("file.txt"));
    }

    @Test
    public void testExtractVersionNumberMiddle() {
        assertEquals("Should extract version from middle", 3, ImportExportUtils.extractVersionNumber("file.v3.txt"));
    }

    // ========== isVersionFile Tests ==========

    @Test
    public void testIsVersionFileTrue() {
        assertTrue("file.txt.v1 should be version file", ImportExportUtils.isVersionFile("file.txt.v1"));
        assertTrue("file.txt.v10 should be version file", ImportExportUtils.isVersionFile("file.txt.v10"));
        assertTrue("folder/file.txt.v2 should be version file", ImportExportUtils.isVersionFile("folder/file.txt.v2"));
    }

    @Test
    public void testIsVersionFileFalse() {
        assertFalse("file.txt should not be version file", ImportExportUtils.isVersionFile("file.txt"));
        assertFalse("file.meta.json should not be version file", ImportExportUtils.isVersionFile("file.meta.json"));
    }

    // ========== isVersionFileFor Tests ==========

    @Test
    public void testIsVersionFileForTrue() {
        assertTrue("file.txt.v1 should be version of file.txt",
                ImportExportUtils.isVersionFileFor("file.txt.v1", "file.txt"));
        assertTrue("file.txt.v10 should be version of file.txt",
                ImportExportUtils.isVersionFileFor("file.txt.v10", "file.txt"));
    }

    @Test
    public void testIsVersionFileForFalse() {
        assertFalse("other.txt.v1 should not be version of file.txt",
                ImportExportUtils.isVersionFileFor("other.txt.v1", "file.txt"));
        assertFalse("file.txt should not be version of file.txt",
                ImportExportUtils.isVersionFileFor("file.txt", "file.txt"));
    }

    // ========== getFileName Tests ==========

    @Test
    public void testGetFileName() {
        assertEquals("Should extract filename", "test.txt", ImportExportUtils.getFileName("folder/test.txt"));
        assertEquals("Should handle no folder", "test.txt", ImportExportUtils.getFileName("test.txt"));
        assertEquals("Should handle deep path", "test.txt", ImportExportUtils.getFileName("a/b/c/test.txt"));
    }

    // ========== getParentPath Tests ==========

    @Test
    public void testGetParentPath() {
        assertEquals("Should extract parent path", "folder", ImportExportUtils.getParentPath("folder/test.txt"));
        assertEquals("Should handle no parent", "", ImportExportUtils.getParentPath("test.txt"));
        assertEquals("Should handle deep path", "a/b/c", ImportExportUtils.getParentPath("a/b/c/test.txt"));
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
            assertNotNull("Should read small file successfully", result);
            assertEquals("Content should match", "Hello World", new String(result));
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
            assertNull("Should return null for non-existent entry", result);
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
            assertNull("Should return null for directory entry", result);
        }
    }

    @Test
    @Ignore("Integration test - creates 100MB+ file, too slow for regular CI")
    public void testReadZipEntryExceedsSizeLimit() throws Exception {
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();

        long maxSize = ImportExportUtils.MAX_SINGLE_FILE_SIZE;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("large.txt");
            zos.putNextEntry(entry);
            byte[] chunk = new byte[1024 * 1024]; // 1MB chunk
            java.util.Arrays.fill(chunk, (byte) 'A');
            long written = 0;
            while (written < maxSize + 1024) {
                zos.write(chunk);
                written += chunk.length;
            }
            zos.closeEntry();
        }

        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = zipImporter.readZipEntryWithLimit(zf, "large.txt", maxSize);
            assertNull("Should return null for file exceeding size limit", result);
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
            assertNotNull("Should read file with unknown size", result);
            assertEquals("Content should match", "Test content", new String(result));
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
            assertNotNull("Should read file within limit", result);
            assertEquals("Content length should match", 100, result.length);

            // Should fail with limit of 50 bytes
            byte[] resultSmall = zipImporter.readZipEntryWithLimit(zf, "medium.txt", 50);
            assertNull("Should return null for file exceeding custom limit", resultSmall);
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
            assertNull("Should return null when known size exceeds limit", result);
        }
    }
}
