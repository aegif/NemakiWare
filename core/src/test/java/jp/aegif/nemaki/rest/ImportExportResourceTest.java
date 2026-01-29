package jp.aegif.nemaki.rest;

import org.junit.Test;
import org.junit.Before;
import org.junit.Ignore;
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Unit tests for ImportExportResource.
 * 
 * Tests security and validation functionality including:
 * - ZIP path traversal prevention
 * - Size limit enforcement
 * - Version sorting logic
 */
public class ImportExportResourceTest {

    private ImportExportResource resource;

    @Before
    public void setUp() throws Exception {
        resource = new ImportExportResource();
    }

    // ========== isValidZipEntryName Tests ==========

    @Test
    public void testValidZipEntryNameSimple() throws Exception {
        assertTrue("Simple filename should be valid", 
                invokeIsValidZipEntryName("test.txt"));
    }

    @Test
    public void testValidZipEntryNameWithPath() throws Exception {
        assertTrue("Path with subdirectory should be valid", 
                invokeIsValidZipEntryName("folder/test.txt"));
    }

    @Test
    public void testValidZipEntryNameDeepPath() throws Exception {
        assertTrue("Deep path should be valid", 
                invokeIsValidZipEntryName("a/b/c/d/test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameNull() throws Exception {
        assertFalse("Null should be invalid", 
                invokeIsValidZipEntryName(null));
    }

    @Test
    public void testInvalidZipEntryNameEmpty() throws Exception {
        assertFalse("Empty string should be invalid", 
                invokeIsValidZipEntryName(""));
    }

    @Test
    public void testInvalidZipEntryNameWithDoubleDot() throws Exception {
        assertFalse("Path with .. should be invalid", 
                invokeIsValidZipEntryName("../test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameWithDoubleDotMiddle() throws Exception {
        assertFalse("Path with .. in middle should be invalid", 
                invokeIsValidZipEntryName("folder/../test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameAbsolutePath() throws Exception {
        assertFalse("Absolute path starting with / should be invalid", 
                invokeIsValidZipEntryName("/etc/passwd"));
    }

    @Test
    public void testInvalidZipEntryNameWindowsBackslash() throws Exception {
        assertFalse("Path with backslash should be invalid", 
                invokeIsValidZipEntryName("folder\\test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameWindowsDriveLetter() throws Exception {
        assertFalse("Windows drive letter path should be invalid", 
                invokeIsValidZipEntryName("C:\\Windows\\System32"));
    }

    @Test
    public void testInvalidZipEntryNameWithColon() throws Exception {
        assertFalse("Path with colon should be invalid", 
                invokeIsValidZipEntryName("C:test.txt"));
    }

    @Test
    public void testInvalidZipEntryNameNullByte() throws Exception {
        assertFalse("Path with null byte should be invalid", 
                invokeIsValidZipEntryName("test\0.txt"));
    }

    @Test
    public void testInvalidZipEntryNameStartsWithBackslash() throws Exception {
        assertFalse("Path starting with backslash should be invalid", 
                invokeIsValidZipEntryName("\\test.txt"));
    }

    // ========== Version Sorting Tests ==========

    @Test
    public void testExtractVersionNumber() throws Exception {
        assertEquals("Should extract version 1", 1, invokeExtractVersionNumber("file.txt.v1"));
        assertEquals("Should extract version 2", 2, invokeExtractVersionNumber("file.txt.v2"));
        assertEquals("Should extract version 10", 10, invokeExtractVersionNumber("file.txt.v10"));
        assertEquals("Should extract version 99", 99, invokeExtractVersionNumber("file.txt.v99"));
    }

    @Test
    public void testExtractVersionNumberNoVersion() throws Exception {
        assertEquals("Should return 0 for no version", 0, invokeExtractVersionNumber("file.txt"));
    }

    @Test
    public void testExtractVersionNumberMiddle() throws Exception {
        assertEquals("Should extract version from middle", 3, invokeExtractVersionNumber("file.v3.txt"));
    }

    // ========== isVersionFile Tests ==========

    @Test
    public void testIsVersionFileTrue() throws Exception {
        assertTrue("file.txt.v1 should be version file", invokeIsVersionFile("file.txt.v1"));
        assertTrue("file.txt.v10 should be version file", invokeIsVersionFile("file.txt.v10"));
        assertTrue("folder/file.txt.v2 should be version file", invokeIsVersionFile("folder/file.txt.v2"));
    }

    @Test
    public void testIsVersionFileFalse() throws Exception {
        assertFalse("file.txt should not be version file", invokeIsVersionFile("file.txt"));
        assertFalse("file.meta.json should not be version file", invokeIsVersionFile("file.meta.json"));
    }

    // ========== isVersionFileFor Tests ==========

    @Test
    public void testIsVersionFileForTrue() throws Exception {
        assertTrue("file.txt.v1 should be version of file.txt", 
                invokeIsVersionFileFor("file.txt.v1", "file.txt"));
        assertTrue("file.txt.v10 should be version of file.txt", 
                invokeIsVersionFileFor("file.txt.v10", "file.txt"));
    }

    @Test
    public void testIsVersionFileForFalse() throws Exception {
        assertFalse("other.txt.v1 should not be version of file.txt", 
                invokeIsVersionFileFor("other.txt.v1", "file.txt"));
        assertFalse("file.txt should not be version of file.txt", 
                invokeIsVersionFileFor("file.txt", "file.txt"));
    }

    // ========== getFileName Tests ==========

    @Test
    public void testGetFileName() throws Exception {
        assertEquals("Should extract filename", "test.txt", invokeGetFileName("folder/test.txt"));
        assertEquals("Should handle no folder", "test.txt", invokeGetFileName("test.txt"));
        assertEquals("Should handle deep path", "test.txt", invokeGetFileName("a/b/c/test.txt"));
    }

    // ========== getParentPath Tests ==========

    @Test
    public void testGetParentPath() throws Exception {
        assertEquals("Should extract parent path", "folder", invokeGetParentPath("folder/test.txt"));
        assertEquals("Should handle no parent", "", invokeGetParentPath("test.txt"));
        assertEquals("Should handle deep path", "a/b/c", invokeGetParentPath("a/b/c/test.txt"));
    }

    // ========== guessMimeType Tests ==========

    @Test
    public void testGuessMimeTypePdf() throws Exception {
        assertEquals("application/pdf", invokeGuessMimeType("test.pdf"));
    }

    @Test
    public void testGuessMimeTypeTxt() throws Exception {
        assertEquals("text/plain", invokeGuessMimeType("test.txt"));
    }

    @Test
    public void testGuessMimeTypeHtml() throws Exception {
        assertEquals("text/html", invokeGuessMimeType("test.html"));
        assertEquals("text/html", invokeGuessMimeType("test.htm"));
    }

    @Test
    public void testGuessMimeTypeJson() throws Exception {
        assertEquals("application/json", invokeGuessMimeType("test.json"));
    }

    @Test
    public void testGuessMimeTypeXml() throws Exception {
        assertEquals("application/xml", invokeGuessMimeType("test.xml"));
    }

    @Test
    public void testGuessMimeTypeDocx() throws Exception {
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", 
                invokeGuessMimeType("test.docx"));
    }

    @Test
    public void testGuessMimeTypeUnknown() throws Exception {
        assertEquals("application/octet-stream", invokeGuessMimeType("test.xyz"));
    }

    @Test
    public void testGuessMimeTypeNull() throws Exception {
        assertEquals("application/octet-stream", invokeGuessMimeType(null));
    }

    @Test
    public void testGuessMimeTypeCaseInsensitive() throws Exception {
        assertEquals("application/pdf", invokeGuessMimeType("TEST.PDF"));
    }

    // ========== Size Limit Tests ==========

    @Test
    public void testReadZipEntryWithSizeLimit() throws Exception {
        // Create a temporary ZIP file with a small entry
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("small.txt");
            zos.putNextEntry(entry);
            byte[] content = "Hello World".getBytes();
            zos.write(content);
            zos.closeEntry();
        }
        
        // Read the entry using readZipEntry
        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = invokeReadZipEntry(zf, "small.txt");
            assertNotNull("Should read small file successfully", result);
            assertEquals("Content should match", "Hello World", new String(result));
        }
    }

    @Test
    public void testReadZipEntryNonExistent() throws Exception {
        // Create a temporary ZIP file
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("exists.txt");
            zos.putNextEntry(entry);
            zos.write("content".getBytes());
            zos.closeEntry();
        }
        
        // Try to read non-existent entry
        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = invokeReadZipEntry(zf, "nonexistent.txt");
            assertNull("Should return null for non-existent entry", result);
        }
    }

    @Test
    public void testReadZipEntryDirectory() throws Exception {
        // Create a temporary ZIP file with a directory entry
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry dirEntry = new ZipEntry("folder/");
            zos.putNextEntry(dirEntry);
            zos.closeEntry();
        }
        
        // Try to read directory entry
        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = invokeReadZipEntry(zf, "folder/");
            assertNull("Should return null for directory entry", result);
        }
    }

    /**
     * Test that files exceeding MAX_SINGLE_FILE_SIZE are rejected.
     * This test is ignored by default because it creates a 100MB+ file which is slow.
     * Run manually for integration testing: mvn test -Dtest=ImportExportResourceTest#testReadZipEntryExceedsSizeLimit
     */
    @Test
    @Ignore("Integration test - creates 100MB+ file, too slow for regular CI")
    public void testReadZipEntryExceedsSizeLimit() throws Exception {
        // Create a temporary ZIP file with a large entry
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();
        
        // Get MAX_SINGLE_FILE_SIZE from the resource class
        long maxSize = getMaxSingleFileSize();
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("large.txt");
            zos.putNextEntry(entry);
            // Write content larger than MAX_SINGLE_FILE_SIZE
            // We'll write in chunks to avoid memory issues
            byte[] chunk = new byte[1024 * 1024]; // 1MB chunk
            java.util.Arrays.fill(chunk, (byte) 'A');
            long written = 0;
            while (written < maxSize + 1024) {
                zos.write(chunk);
                written += chunk.length;
            }
            zos.closeEntry();
        }
        
        // Try to read large entry - should return null due to size limit
        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = invokeReadZipEntry(zf, "large.txt");
            assertNull("Should return null for file exceeding size limit", result);
        }
    }

    @Test
    public void testReadZipEntryWithUnknownSize() throws Exception {
        // Create a temporary ZIP file
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
            ZipEntry entry = new ZipEntry("test.txt");
            zos.putNextEntry(entry);
            zos.write("Test content".getBytes());
            zos.closeEntry();
        }
        
        // Read the entry - the size monitoring should work even if getSize() returns -1
        try (ZipFile zf = new ZipFile(tempZip)) {
            byte[] result = invokeReadZipEntry(zf, "test.txt");
            assertNotNull("Should read file with unknown size", result);
            assertEquals("Content should match", "Test content", new String(result));
        }
    }

    /**
     * Test size limit enforcement with a small custom limit (lightweight test for CI).
     * Uses readZipEntryWithLimit directly with a small limit to verify size checking logic.
     */
    @Test
    public void testReadZipEntryWithCustomSizeLimit() throws Exception {
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();
        
        // Create a ZIP with a 100-byte file
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
            byte[] result = resource.readZipEntryWithLimit(zf, "medium.txt", 200);
            assertNotNull("Should read file within limit", result);
            assertEquals("Content length should match", 100, result.length);
            
            // Should fail with limit of 50 bytes
            byte[] resultSmall = resource.readZipEntryWithLimit(zf, "medium.txt", 50);
            assertNull("Should return null for file exceeding custom limit", resultSmall);
        }
    }

    /**
     * Test size limit enforcement during stream read (when entry.getSize() is known).
     * Verifies that the size check before reading works correctly.
     */
    @Test
    public void testReadZipEntryWithKnownSizeExceedsLimit() throws Exception {
        File tempZip = File.createTempFile("test", ".zip");
        tempZip.deleteOnExit();
        
        // Create a ZIP with a 1KB file
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
            byte[] result = resource.readZipEntryWithLimit(zf, "kilobyte.txt", 512);
            assertNull("Should return null when known size exceeds limit", result);
        }
    }

    // ========== Helper Methods ==========

    private boolean invokeIsValidZipEntryName(String name) throws Exception {
        Method method = ImportExportResource.class.getDeclaredMethod("isValidZipEntryName", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(resource, name);
    }

    private int invokeExtractVersionNumber(String path) throws Exception {
        Method method = ImportExportResource.class.getDeclaredMethod("extractVersionNumber", String.class);
        method.setAccessible(true);
        return (Integer) method.invoke(resource, path);
    }

    private boolean invokeIsVersionFile(String path) throws Exception {
        Method method = ImportExportResource.class.getDeclaredMethod("isVersionFile", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(resource, path);
    }

    private boolean invokeIsVersionFileFor(String path, String baseFileName) throws Exception {
        Method method = ImportExportResource.class.getDeclaredMethod("isVersionFileFor", String.class, String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(resource, path, baseFileName);
    }

    private String invokeGetFileName(String path) throws Exception {
        Method method = ImportExportResource.class.getDeclaredMethod("getFileName", String.class);
        method.setAccessible(true);
        return (String) method.invoke(resource, path);
    }

    private String invokeGetParentPath(String path) throws Exception {
        Method method = ImportExportResource.class.getDeclaredMethod("getParentPath", String.class);
        method.setAccessible(true);
        return (String) method.invoke(resource, path);
    }

    private String invokeGuessMimeType(String fileName) throws Exception {
        Method method = ImportExportResource.class.getDeclaredMethod("guessMimeType", String.class);
        method.setAccessible(true);
        return (String) method.invoke(resource, fileName);
    }

    private byte[] invokeReadZipEntry(ZipFile zf, String entryName) throws Exception {
        Method method = ImportExportResource.class.getDeclaredMethod("readZipEntry", ZipFile.class, String.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(resource, zf, entryName);
    }

    private long getMaxSingleFileSize() throws Exception {
        Field field = ImportExportResource.class.getDeclaredField("MAX_SINGLE_FILE_SIZE");
        field.setAccessible(true);
        return (Long) field.get(null);
    }
}
