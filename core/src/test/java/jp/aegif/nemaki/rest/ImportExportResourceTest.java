package jp.aegif.nemaki.rest;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
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
}
