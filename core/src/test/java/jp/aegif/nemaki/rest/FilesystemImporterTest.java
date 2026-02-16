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
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

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

import static org.junit.Assert.*;

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

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

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

    @Before
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
        Path sourceDir = tempFolder.newFolder("source").toPath();
        byte[] content = "Hello, streaming world!".getBytes(StandardCharsets.UTF_8);
        Files.write(sourceDir.resolve("test.txt"), content);

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have captured 1 createDocument call", 1, capturedStreamContents.size());

        // Verify the stream is NOT a ByteArrayInputStream (i.e., not from readAllBytes)
        assertFalse("Stream should NOT be ByteArrayInputStream (proves streaming, not heap buffering)",
                ByteArrayInputStream.class.isAssignableFrom(capturedStreamClasses.get(0)));

        // Verify content is correct
        assertArrayEquals("Content should match original file", content, capturedStreamContents.get(0));

        // Verify file size is correctly reported
        assertEquals("Content length should match file size",
                (long) content.length, capturedContentLengths.get(0).longValue());
    }

    @Test
    public void testStreamingImport_largeFileSkipped() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-large").toPath();
        byte[] content = new byte[1024]; // 1KB file - well within limits
        java.util.Arrays.fill(content, (byte) 'A');
        Files.write(sourceDir.resolve("small.txt"), content);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should import 1 document", 1, result.documentsCreated);
        assertTrue("Should have no errors", result.errors.isEmpty());
    }

    @Test
    public void testStreamingImport_contentIntegrity() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-integrity").toPath();
        byte[] originalContent = "This is test content for SHA-256 verification.\n日本語テスト。".getBytes(StandardCharsets.UTF_8);
        Files.write(sourceDir.resolve("integrity.txt"), originalContent);

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have captured 1 stream", 1, capturedStreamContents.size());

        // SHA-256 comparison
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] originalHash = digest.digest(originalContent);
        digest.reset();
        byte[] streamHash = digest.digest(capturedStreamContents.get(0));

        assertArrayEquals("SHA-256 hash of streamed content must match original", originalHash, streamHash);
    }

    @Test
    public void testStreamingImport_mimeTypeDetection() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-mime").toPath();
        Files.write(sourceDir.resolve("document.pdf"), "fake-pdf".getBytes());

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have captured 1 call", 1, capturedMimeTypes.size());
        assertEquals("MIME type should be application/pdf",
                "application/pdf", capturedMimeTypes.get(0));
    }

    // ========== P2: Custom Object Type Tests ==========

    @Test
    public void testCustomObjectType_preservedFromMetadata() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-type").toPath();
        Files.write(sourceDir.resolve("typed.txt"), "content".getBytes());

        String metadata = "{\"properties\":{\"cmis:objectTypeId\":\"custom:report\",\"cmis:name\":\"typed.txt\"}}";
        Files.write(sourceDir.resolve("typed.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have captured 1 call", 1, capturedProperties.size());
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertNotNull("OBJECT_TYPE_ID property should exist", typeIdProp);
        assertEquals("Object type should be custom:report from metadata",
                "custom:report", typeIdProp.getFirstValue());
    }

    @Test
    public void testCustomObjectType_defaultWhenNoMetadata() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-notype").toPath();
        Files.write(sourceDir.resolve("plain.txt"), "content".getBytes());

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have captured 1 call", 1, capturedProperties.size());
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertEquals("Object type should default to cmis:document",
                "cmis:document", typeIdProp.getFirstValue());
    }

    @Test
    public void testCustomObjectType_defaultWhenMetadataHasNoType() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-emptytype").toPath();
        Files.write(sourceDir.resolve("notype.txt"), "content".getBytes());

        String metadata = "{\"properties\":{\"custom:field\":\"value\"}}";
        Files.write(sourceDir.resolve("notype.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have captured 1 call", 1, capturedProperties.size());
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertEquals("Object type should default to cmis:document when metadata has no type",
                "cmis:document", typeIdProp.getFirstValue());
    }

    @Test
    public void testCustomObjectType_emptyTypeStringIgnored() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-emptystr").toPath();
        Files.write(sourceDir.resolve("empty.txt"), "content".getBytes());

        String metadata = "{\"properties\":{\"cmis:objectTypeId\":\"\"}}";
        Files.write(sourceDir.resolve("empty.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have captured 1 call", 1, capturedProperties.size());
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertEquals("Empty type string should fall back to cmis:document",
                "cmis:document", typeIdProp.getFirstValue());
    }

    // ========== Metadata Size Limit Tests ==========

    @Test
    public void testMetadataSizeLimit_withinLimit() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-metalimit").toPath();
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());

        String metadata = "{\"properties\":{\"cmis:name\":\"doc.txt\"}}";
        Files.write(sourceDir.resolve("doc.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should import 1 document", 1, result.documentsCreated);
        boolean hasMetaWarning = result.warnings.stream()
                .anyMatch(w -> w.contains("Skipping large metadata"));
        assertFalse("Should not have metadata size warning", hasMetaWarning);
    }

    @Test
    public void testMetadataSizeLimit_exceedsLimit() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-bigmeta").toPath();
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());

        // Create a metadata file exceeding MAX_METADATA_SIZE (10MB)
        byte[] bigMeta = new byte[(int) (ImportExportUtils.MAX_METADATA_SIZE + 1024)];
        java.util.Arrays.fill(bigMeta, (byte) '{');
        Files.write(sourceDir.resolve("doc.txt.meta.json"), bigMeta);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should import 1 document", 1, result.documentsCreated);
        boolean hasMetaWarning = result.warnings.stream()
                .anyMatch(w -> w.contains("Skipping large metadata"));
        assertTrue("Should have metadata size warning", hasMetaWarning);
    }

    // ========== Custom Properties Tests ==========

    @Test
    public void testCustomProperties_includedInImport() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-props").toPath();
        Files.write(sourceDir.resolve("custom.txt"), "content".getBytes());

        String metadata = "{\"properties\":{" +
                "\"cmis:objectTypeId\":\"cmis:document\"," +
                "\"cmis:name\":\"custom.txt\"," +
                "\"custom:field1\":\"value1\"," +
                "\"custom:field2\":\"value2\"" +
                "}}";
        Files.write(sourceDir.resolve("custom.txt.meta.json"), metadata.getBytes(StandardCharsets.UTF_8));

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have captured 1 call", 1, capturedProperties.size());
        Properties capturedProps = capturedProperties.get(0);

        assertNotNull("custom:field1 should be present",
                capturedProps.getProperties().get("custom:field1"));
        assertEquals("custom:field1 value should be 'value1'",
                "value1", capturedProps.getProperties().get("custom:field1").getFirstValue());

        assertNotNull("custom:field2 should be present",
                capturedProps.getProperties().get("custom:field2"));
        assertEquals("custom:field2 value should be 'value2'",
                "value2", capturedProps.getProperties().get("custom:field2").getFirstValue());
    }

    @Test
    public void testCustomProperties_systemPropertiesSkipped() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-sysprops").toPath();
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

        assertEquals("Should have captured 1 call", 1, capturedProperties.size());
        Properties capturedProps = capturedProperties.get(0);

        assertNull("cmis:objectId should be skipped",
                capturedProps.getProperties().get(PropertyIds.OBJECT_ID));

        assertNotNull("custom:kept should be present",
                capturedProps.getProperties().get("custom:kept"));
    }

    // ========== Multiple Files and Folder Structure Tests ==========

    @Test
    public void testMultipleFiles_importedCorrectly() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-multi").toPath();
        Files.write(sourceDir.resolve("file1.txt"), "content1".getBytes());
        Files.write(sourceDir.resolve("file2.txt"), "content2".getBytes());
        Files.write(sourceDir.resolve("file3.txt"), "content3".getBytes());

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should import 3 documents", 3, result.documentsCreated);
        assertTrue("Should have no errors", result.errors.isEmpty());
    }

    @Test
    public void testSubfolder_createdAndUsed() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-subfolder").toPath();
        Files.createDirectories(sourceDir.resolve("subfolder"));
        Files.write(sourceDir.resolve("subfolder/nested.txt"), "nested content".getBytes());

        Folder subfolder = new Folder();
        subfolder.setId("subfolder-id");
        returnFolder = subfolder;
        folderMap.put("subfolder-id", subfolder);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should create 1 folder", 1, result.foldersCreated);
        assertEquals("Should import 1 document", 1, result.documentsCreated);
    }

    // ========== Meta Suffix and Version File Filtering ==========

    @Test
    public void testMetaFiles_notImportedAsDocuments() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-metaskip").toPath();
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());
        Files.write(sourceDir.resolve("doc.txt.meta.json"),
                "{\"properties\":{}}".getBytes(StandardCharsets.UTF_8));

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should import only 1 document (not the .meta.json)", 1, result.documentsCreated);
    }

    @Test
    public void testVersionFiles_notImportedAsDocuments() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-verskip").toPath();
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());
        Files.write(sourceDir.resolve("doc.txt.v1"), "version 1".getBytes());
        Files.write(sourceDir.resolve("doc.txt.v2"), "version 2".getBytes());

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should import only 1 document (not version files)", 1, result.documentsCreated);
    }

    // ========== Edge Case Tests ==========

    @Test
    public void testZeroByteFile_importedSuccessfully() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-zerobyte").toPath();
        Files.write(sourceDir.resolve("empty.dat"), new byte[0]);

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should import 1 document", 1, result.documentsCreated);
        assertTrue("Should have no errors", result.errors.isEmpty());
        assertEquals("Content length should be 0", 0L, capturedContentLengths.get(0).longValue());
        assertEquals("Content should be empty", 0, capturedStreamContents.get(0).length);
    }

    @Test
    public void testFileWithNoExtension_mimeTypeFallback() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-noext").toPath();
        Files.write(sourceDir.resolve("README"), "some content".getBytes());

        importer.importFromFilesystemDirectory(REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have captured 1 call", 1, capturedMimeTypes.size());
        assertEquals("MIME type should fall back to application/octet-stream",
                "application/octet-stream", capturedMimeTypes.get(0));
    }

    @Test
    public void testMalformedMetadata_warningRecordedFileStillImported() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-badjson").toPath();
        Files.write(sourceDir.resolve("doc.txt"), "content".getBytes());
        Files.write(sourceDir.resolve("doc.txt.meta.json"),
                "{ invalid json !!!".getBytes(StandardCharsets.UTF_8));

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should still import 1 document", 1, result.documentsCreated);
        boolean hasParseWarning = result.warnings.stream()
                .anyMatch(w -> w.contains("Failed to parse metadata"));
        assertTrue("Should have metadata parse warning", hasParseWarning);

        // Without valid metadata, should default to cmis:document
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> typeIdProp = capturedProps.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        assertEquals("Object type should default to cmis:document",
                "cmis:document", typeIdProp.getFirstValue());
    }

    @Test
    public void testDeeplyNestedSubfolder_createdRecursively() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-deep").toPath();
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

        assertEquals("Should create 3 folders (a, b, c)", 3, result.foldersCreated);
        assertEquals("Should import 1 document", 1, result.documentsCreated);
    }

    @Test
    public void testPartialFailure_someSucceedSomeFail() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-partial").toPath();
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

        assertEquals("Should have 1 successful import", 1, result.documentsCreated);
        assertEquals("Should have 1 error", 1, result.errors.size());
    }

    @Test
    public void testFileWithSpacesInName_importedCorrectly() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-spaces").toPath();
        Files.write(sourceDir.resolve("my document (draft).txt"), "spaced content".getBytes());

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should import 1 document", 1, result.documentsCreated);
        assertTrue("Should have no errors", result.errors.isEmpty());

        // Verify the filename preserves spaces
        Properties capturedProps = capturedProperties.get(0);
        PropertyData<?> nameProp = capturedProps.getProperties().get(PropertyIds.NAME);
        assertEquals("Filename should preserve spaces",
                "my document (draft).txt", nameProp.getFirstValue());
    }

    // ========== Error Handling Tests ==========

    @Test
    public void testImportError_recordedInResult() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-error").toPath();
        Files.write(sourceDir.resolve("fail.txt"), "content".getBytes());

        createDocumentException = new RuntimeException("Simulated CMIS error");

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should have 0 documents created", 0, result.documentsCreated);
        assertEquals("Should have 1 error", 1, result.errors.size());
        assertTrue("Error message should contain file name",
                result.errors.get(0).contains("fail.txt"));
        assertTrue("Error message should contain original error",
                result.errors.get(0).contains("Simulated CMIS error"));
    }

    @Test
    public void testEmptyDirectory_noErrors() throws Exception {
        Path sourceDir = tempFolder.newFolder("source-empty").toPath();

        ImportResult result = importer.importFromFilesystemDirectory(
                REPO_ID, TARGET_FOLDER_ID, sourceDir, stubCallContext);

        assertEquals("Should import 0 documents", 0, result.documentsCreated);
        assertEquals("Should create 0 folders", 0, result.foldersCreated);
        assertTrue("Should have no errors", result.errors.isEmpty());
    }
}
