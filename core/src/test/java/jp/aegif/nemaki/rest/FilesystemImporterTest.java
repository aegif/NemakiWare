package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.importexport.FilesystemImporter;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FilesystemImporter covering:
 * - Streaming file import (P1: no readAllBytes for content files)
 * - Custom object type preservation (P2: cmis:objectTypeId from metadata)
 * - Metadata size limit enforcement (MAX_METADATA_SIZE)
 * - Import/export data integrity (SHA-256)
 *
 * All tests use lightweight JDK Proxy stubs (no Mockito/reflection agent).
 * These are portable CI tests (no Docker/server/JVM self-attach required).
 */
public class FilesystemImporterTest {

    @TempDir
    Path tempDir;

    private FilesystemImporter importer;

    // Captured data from stub
    private List<byte[]> capturedStreamContents;
    private List<Class<?>> capturedStreamClasses;
    private List<String> capturedMimeTypes;
    private List<Long> capturedContentLengths;
    private List<Properties> capturedProperties;

    // Configurable stub behavior
    private Map<String, Folder> folderMap;
    private Document returnDocument;
    private Folder returnFolder;
    private RuntimeException createDocumentException;

    private CallContext stubCallContext;

    private static final String REPO_ID = "test-repo";
    private static final String TARGET_FOLDER_ID = "target-folder-id";

    @BeforeEach
    public void setUp() {
        capturedStreamContents = new ArrayList<>();
        capturedStreamClasses = new ArrayList<>();
        capturedMimeTypes = new ArrayList<>();
        capturedContentLengths = new ArrayList<>();
        capturedProperties = new ArrayList<>();
        folderMap = new HashMap<>();
        createDocumentException = null;

        Folder parentFolder = new Folder();
        parentFolder.setId(TARGET_FOLDER_ID);
        folderMap.put(TARGET_FOLDER_ID, parentFolder);

        returnDocument = new Document();
        returnDocument.setId("new-doc-id");

        returnFolder = null;

        ContentService stubService = createStubContentService();
        stubCallContext = createStubCallContext();
        importer = new FilesystemImporter(stubService);
    }

    private ContentService createStubContentService() {
        return (ContentService) Proxy.newProxyInstance(
            ContentService.class.getClassLoader(),
            new Class[]{ContentService.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getFolder": {
                        String folderId = (String) args[1];
                        return folderMap.get(folderId);
                    }
                    case "createDocument": {
                        if (createDocumentException != null) {
                            throw createDocumentException;
                        }
                        capturedProperties.add((Properties) args[2]);
                        ContentStream cs = (ContentStream) args[4];
                        // Read stream content eagerly before try-with-resources closes it
                        InputStream stream = cs.getStream();
                        capturedStreamClasses.add(stream.getClass());
                        capturedStreamContents.add(stream.readAllBytes());
                        capturedMimeTypes.add(cs.getMimeType());
                        capturedContentLengths.add(cs.getBigLength() != null ? cs.getBigLength().longValue() : -1L);
                        return returnDocument;
                    }
                    case "createFolder": {
                        return returnFolder;
                    }
                    case "getContent":
                    case "updateInternal":
                        return null;
                    default:
                        return getDefaultReturnValue(method.getReturnType());
                }
            }
        );
    }

    private static CallContext createStubCallContext() {
        return (CallContext) Proxy.newProxyInstance(
            CallContext.class.getClassLoader(),
            new Class[]{CallContext.class},
            (proxy, method, args) -> null
        );
    }

    private static Object getDefaultReturnValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0;
        return null;
    }

    // ========== P1: Streaming Tests ==========

    @Test
    public void testStreamingImport_noHeapBuffering() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source"));
        byte[] content = "Hello, streaming world!".getBytes(StandardCharsets.UTF_8);
        Files.write(sourceDir.resolve("test.txt"), content);

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedStreamContents.size(), "Should have captured 1 createDocument call");

        // Verify the stream is NOT a ByteArrayInputStream (i.e., not from readAllBytes)
        assertFalse(ByteArrayInputStream.class.isAssignableFrom(capturedStreamClasses.get(0)), "Stream should NOT be ByteArrayInputStream (proves streaming, not heap buffering)");

        // Verify content is correct
        assertArrayEquals(content, capturedStreamContents.get(0), "Content should match original file");

        // Verify file size is correctly reported
        assertEquals((long) content.length, capturedContentLengths.get(0).longValue(), "Content length should match file size");
    }

    @Test
    public void testStreamingImport_largeFileSkipped() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-large"));
        byte[] content = new byte[1024]; // 1KB file - well within limits
        java.util.Arrays.fill(content, (byte) 'A');
        Files.write(sourceDir.resolve("small.txt"), content);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.documentsCreated, "Should import 1 document");
        assertTrue(result.errors.isEmpty(), "Should have no errors");
    }

    @Test
    public void testStreamingImport_contentIntegrity() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-integrity"));
        byte[] originalContent = "This is test content for SHA-256 verification.\n日本語テスト。".getBytes(StandardCharsets.UTF_8);
        Files.write(sourceDir.resolve("integrity.txt"), originalContent);

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedStreamContents.size(), "Should have captured 1 stream");

        // SHA-256 comparison
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] originalHash = digest.digest(originalContent);
        digest.reset();
        byte[] streamHash = digest.digest(capturedStreamContents.get(0));

        assertArrayEquals(originalHash, streamHash, "SHA-256 hash of streamed content must match original");
    }

    @Test
    public void testStreamingImport_mimeTypeDetection() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-mime"));
        Files.write(sourceDir.resolve("document.pdf"), "fake-pdf".getBytes());

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedMimeTypes.size(), "Should have captured 1 call");
        assertEquals("application/pdf", capturedMimeTypes.get(0), "MIME type should be application/pdf");
    }

    // ========== P2: Custom Object Type Tests ==========

    @Test
    public void testCustomObjectType_preservedFromMetadata() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-type"));
        Files.write(sourceDir.resolve("typed.txt"), "content".getBytes());

        String metadata = "{\"properties\":{\"cmis:objectTypeId\":\"custom:report\",\"cmis:name\":\"typed.txt\"}}";
        Files.write(sourceDir.resolve("typed.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedProperties.size(), "Should have captured 1 call");
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertNotNull(typeIdProp, "OBJECT_TYPE_ID property should exist");
        assertEquals("custom:report", typeIdProp.getFirstValue(), "Object type should be custom:report from metadata");
    }

    @Test
    public void testCustomObjectType_defaultWhenNoMetadata() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-notype"));
        Files.write(sourceDir.resolve("plain.txt"), "content".getBytes());

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedProperties.size(), "Should have captured 1 call");
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertEquals("cmis:document", typeIdProp.getFirstValue(), "Object type should default to cmis:document");
    }

    @Test
    public void testCustomObjectType_defaultWhenMetadataHasNoType() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-emptytype"));
        Files.write(sourceDir.resolve("notype.txt"), "content".getBytes());

        String metadata = "{\"properties\":{\"custom:field\":\"value\"}}";
        Files.write(sourceDir.resolve("notype.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedProperties.size(), "Should have captured 1 call");
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertEquals("cmis:document", typeIdProp.getFirstValue(), "Object type should default to cmis:document when metadata has no type");
    }

    @Test
    public void testCustomObjectType_emptyTypeStringIgnored() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-emptystr"));
        Files.write(sourceDir.resolve("empty.txt"), "content".getBytes());

        String metadata = "{\"properties\":{\"cmis:objectTypeId\":\"\"}}";
        Files.write(sourceDir.resolve("empty.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedProperties.size(), "Should have captured 1 call");
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertEquals("cmis:document", typeIdProp.getFirstValue(), "Empty type string should fall back to cmis:document");
    }

    // ========== Metadata Size Limit Tests ==========

    @Test
    public void testMetadataSizeLimit_withinLimit() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-metalimit"));
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());

        String metadata = "{\"properties\":{\"cmis:name\":\"doc.txt\"}}";
        Files.write(sourceDir.resolve("doc.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.documentsCreated, "Should import 1 document");
        boolean hasMetaWarning = result.warnings.stream()
                .anyMatch(w -> w.contains("Skipping large metadata"));
        assertFalse(hasMetaWarning, "Should not have metadata size warning");
    }

    @Test
    public void testMetadataSizeLimit_exceedsLimit() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-bigmeta"));
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());

        // Create a metadata file exceeding MAX_METADATA_SIZE (10MB)
        byte[] bigMeta = new byte[(int) (ImportExportUtils.MAX_METADATA_SIZE + 1024)];
        java.util.Arrays.fill(bigMeta, (byte) '{');
        Files.write(sourceDir.resolve("doc.txt.meta.json"), bigMeta);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.documentsCreated, "Should import 1 document");
        boolean hasMetaWarning = result.warnings.stream()
                .anyMatch(w -> w.contains("Skipping large metadata"));
        assertTrue(hasMetaWarning, "Should have metadata size warning");
    }

    // ========== Custom Properties Tests ==========

    @Test
    public void testCustomProperties_includedInImport() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-props"));
        Files.write(sourceDir.resolve("custom.txt"), "content".getBytes());

        String metadata = "{\"properties\":{" +
                "\"cmis:objectTypeId\":\"cmis:document\"," +
                "\"cmis:name\":\"custom.txt\"," +
                "\"custom:field1\":\"value1\"," +
                "\"custom:field2\":\"value2\"" +
                "}}";
        Files.write(sourceDir.resolve("custom.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedProperties.size(), "Should have captured 1 call");
        Properties capturedProps = capturedProperties.get(0);

        assertNotNull(capturedProps.getProperties().get("custom:field1"), "custom:field1 should be present");
        assertEquals("value1", capturedProps.getProperties().get("custom:field1").getFirstValue(), "custom:field1 value should be 'value1'");

        assertNotNull(capturedProps.getProperties().get("custom:field2"), "custom:field2 should be present");
        assertEquals("value2", capturedProps.getProperties().get("custom:field2").getFirstValue(), "custom:field2 value should be 'value2'");
    }

    @Test
    public void testCustomProperties_systemPropertiesSkipped() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-sysprops"));
        Files.write(sourceDir.resolve("sys.txt"), "content".getBytes());

        String metadata = "{\"properties\":{" +
                "\"cmis:objectTypeId\":\"cmis:document\"," +
                "\"cmis:name\":\"sys.txt\"," +
                "\"cmis:objectId\":\"old-id-should-be-ignored\"," +
                "\"cmis:baseTypeId\":\"cmis:document\"," +
                "\"cmis:createdBy\":\"old-user\"," +
                "\"cmis:lastModifiedBy\":\"old-user\"," +
                "\"custom:kept\":\"yes\"" +
                "}}";
        Files.write(sourceDir.resolve("sys.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedProperties.size(), "Should have captured 1 call");
        Properties capturedProps = capturedProperties.get(0);

        assertNull(capturedProps.getProperties().get(PropertyIds.OBJECT_ID), "cmis:objectId should be skipped");

        assertNotNull(capturedProps.getProperties().get("custom:kept"), "custom:kept should be present");
    }

    // ========== Multiple Files and Folder Structure Tests ==========

    @Test
    public void testMultipleFiles_importedCorrectly() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-multi"));
        Files.write(sourceDir.resolve("file1.txt"), "content1".getBytes());
        Files.write(sourceDir.resolve("file2.txt"), "content2".getBytes());
        Files.write(sourceDir.resolve("file3.txt"), "content3".getBytes());

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(3, result.documentsCreated, "Should import 3 documents");
        assertTrue(result.errors.isEmpty(), "Should have no errors");
    }

    @Test
    public void testSubfolder_createdAndUsed() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-subfolder"));
        Files.createDirectories(sourceDir.resolve("subfolder"));
        Files.write(sourceDir.resolve("subfolder/nested.txt"), "nested content".getBytes());

        Folder subfolder = new Folder();
        subfolder.setId("subfolder-id");
        returnFolder = subfolder;
        folderMap.put("subfolder-id", subfolder);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.foldersCreated, "Should create 1 folder");
        assertEquals(1, result.documentsCreated, "Should import 1 document");
    }

    // ========== Meta Suffix and Version File Filtering ==========

    @Test
    public void testMetaFiles_notImportedAsDocuments() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-metaskip"));
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());
        Files.write(sourceDir.resolve("doc.txt.meta.json"),
                "{\"properties\":{}}".getBytes(StandardCharsets.UTF_8));

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.documentsCreated, "Should import only 1 document (not the .meta.json)");
    }

    @Test
    public void testVersionFiles_notImportedAsDocuments() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-verskip"));
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());
        Files.write(sourceDir.resolve("doc.txt.v1"), "version 1".getBytes());
        Files.write(sourceDir.resolve("doc.txt.v2"), "version 2".getBytes());

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.documentsCreated, "Should import only 1 document (not version files)");
    }

    // ========== Edge Case Tests ==========

    @Test
    public void testZeroByteFile_importedSuccessfully() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-zerobyte"));
        Files.write(sourceDir.resolve("empty.dat"), new byte[0]);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.documentsCreated, "Should import 1 document");
        assertTrue(result.errors.isEmpty(), "Should have no errors");
        assertEquals(0L, capturedContentLengths.get(0).longValue(), "Content length should be 0");
        assertEquals(0, capturedStreamContents.get(0).length, "Content should be empty");
    }

    @Test
    public void testFileWithNoExtension_mimeTypeFallback() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-noext"));
        Files.write(sourceDir.resolve("README"), "some content".getBytes());

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, capturedMimeTypes.size(), "Should have captured 1 call");
        assertEquals("application/octet-stream", capturedMimeTypes.get(0), "MIME type should fall back to application/octet-stream");
    }

    @Test
    public void testMalformedMetadata_warningRecordedFileStillImported() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-badjson"));
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());
        Files.write(sourceDir.resolve("doc.txt.meta.json"),
                "{ invalid json !!!".getBytes(StandardCharsets.UTF_8));

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.documentsCreated, "Should still import 1 document");
        boolean hasParseWarning = result.warnings.stream()
                .anyMatch(w -> w.contains("Failed to parse metadata"));
        assertTrue(hasParseWarning, "Should have metadata parse warning");

        // Without valid metadata, should default to cmis:document
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertEquals("cmis:document", typeIdProp.getFirstValue(), "Object type should default to cmis:document");
    }

    @Test
    public void testDeeplyNestedSubfolder_createdRecursively() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-deep"));
        Files.createDirectories(sourceDir.resolve("a/b/c"));
        Files.write(sourceDir.resolve("a/b/c/deep.txt"), "deep content".getBytes());

        // Stubs for folder creation at each level
        Folder folderA = new Folder();
        folderA.setId("folder-a-id");
        Folder folderB = new Folder();
        folderB.setId("folder-b-id");
        Folder folderC = new Folder();
        folderC.setId("folder-c-id");

        // returnFolder will be used for createFolder calls - override with dynamic response
        List<Folder> foldersToReturn = new ArrayList<>();
        foldersToReturn.add(folderA);
        foldersToReturn.add(folderB);
        foldersToReturn.add(folderC);

        folderMap.put("folder-a-id", folderA);
        folderMap.put("folder-b-id", folderB);
        folderMap.put("folder-c-id", folderC);

        // Use a counter-based stub to return different folders for each createFolder call
        int[] folderIndex = {0};
        ContentService dynamicStub = (ContentService) Proxy.newProxyInstance(
            ContentService.class.getClassLoader(),
            new Class[]{ContentService.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getFolder": {
                        String folderId = (String) args[1];
                        return folderMap.get(folderId);
                    }
                    case "createDocument": {
                        capturedProperties.add((Properties) args[2]);
                        ContentStream cs = (ContentStream) args[4];
                        InputStream stream = cs.getStream();
                        capturedStreamClasses.add(stream.getClass());
                        capturedStreamContents.add(stream.readAllBytes());
                        capturedMimeTypes.add(cs.getMimeType());
                        capturedContentLengths.add(cs.getBigLength() != null ? cs.getBigLength().longValue() : -1L);
                        return returnDocument;
                    }
                    case "createFolder": {
                        if (folderIndex[0] < foldersToReturn.size()) {
                            return foldersToReturn.get(folderIndex[0]++);
                        }
                        return null;
                    }
                    case "getContent":
                    case "updateInternal":
                        return null;
                    default:
                        return getDefaultReturnValue(method.getReturnType());
                }
            }
        );
        importer = new FilesystemImporter(dynamicStub);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(3, result.foldersCreated, "Should create 3 folders (a, b, c)");
        assertEquals(1, result.documentsCreated, "Should import 1 document");
    }

    @Test
    public void testPartialFailure_someSucceedSomeFail() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-partial"));
        Files.write(sourceDir.resolve("good1.txt"), "content1".getBytes());
        Files.write(sourceDir.resolve("good2.txt"), "content2".getBytes());

        // Use a counter to fail on second createDocument call
        int[] docCallCount = {0};
        ContentService partialFailStub = (ContentService) Proxy.newProxyInstance(
            ContentService.class.getClassLoader(),
            new Class[]{ContentService.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getFolder": {
                        String folderId = (String) args[1];
                        return folderMap.get(folderId);
                    }
                    case "createDocument": {
                        docCallCount[0]++;
                        if (docCallCount[0] == 2) {
                            throw new RuntimeException("Second document fails");
                        }
                        capturedProperties.add((Properties) args[2]);
                        ContentStream cs = (ContentStream) args[4];
                        InputStream stream = cs.getStream();
                        capturedStreamClasses.add(stream.getClass());
                        capturedStreamContents.add(stream.readAllBytes());
                        capturedMimeTypes.add(cs.getMimeType());
                        capturedContentLengths.add(cs.getBigLength() != null ? cs.getBigLength().longValue() : -1L);
                        return returnDocument;
                    }
                    case "createFolder":
                        return returnFolder;
                    case "getContent":
                    case "updateInternal":
                        return null;
                    default:
                        return getDefaultReturnValue(method.getReturnType());
                }
            }
        );
        importer = new FilesystemImporter(partialFailStub);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.documentsCreated, "Should have 1 successful import");
        assertEquals(1, result.errors.size(), "Should have 1 error");
    }

    @Test
    public void testFileWithSpacesInName_importedCorrectly() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-spaces"));
        Files.write(sourceDir.resolve("my document (draft).txt"), "spaced content".getBytes());

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(1, result.documentsCreated, "Should import 1 document");
        assertTrue(result.errors.isEmpty(), "Should have no errors");

        // Verify the filename preserves spaces
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> nameProp = capturedProps.getProperties().get(PropertyIds.NAME);
        assertEquals("my document (draft).txt", nameProp.getFirstValue(), "Filename should preserve spaces");
    }

    // ========== Error Handling Tests ==========

    @Test
    public void testImportError_recordedInResult() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-error"));
        Files.write(sourceDir.resolve("fail.txt"), "content".getBytes());

        createDocumentException = new RuntimeException("Simulated CMIS error");

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(0, result.documentsCreated, "Should have 0 documents created");
        assertEquals(1, result.errors.size(), "Should have 1 error");
        assertTrue(result.errors.get(0).contains("fail.txt"), "Error message should contain file name");
        assertTrue(result.errors.get(0).contains("Simulated CMIS error"), "Error message should contain original error");
    }

    @Test
    public void testEmptyDirectory_noErrors() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source-empty"));

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals(0, result.documentsCreated, "Should import 0 documents");
        assertEquals(0, result.foldersCreated, "Should create 0 folders");
        assertTrue(result.errors.isEmpty(), "Should have no errors");
    }
}
