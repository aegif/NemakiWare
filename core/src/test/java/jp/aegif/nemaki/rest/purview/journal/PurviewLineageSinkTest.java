package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PurviewLineageSink}.
 */
class PurviewLineageSinkTest {

    private PurviewLineageSink sink;
    private PurviewEntityRegistryClient mockClient;
    private MetadataCatalogConnectionResolver mockConnectionResolver;

    @BeforeEach
    void setUp() throws Exception {
        sink = new PurviewLineageSink();
        mockClient = mock(PurviewEntityRegistryClient.class);
        mockConnectionResolver = mock(MetadataCatalogConnectionResolver.class);

        when(mockConnectionResolver.isAnyEnabled()).thenReturn(true);
        when(mockConnectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("https://test.purview.azure.com",
                        "api/atlas/v2", "tenant-1", "client-1", "secret", 5000, 30000));

        setField(sink, "registryClient", mockClient);
        setField(sink, "connectionResolver", mockConnectionResolver);
    }

    @Test
    void targetName_returnsPurview() {
        assertEquals("purview", sink.targetName());
    }

    @Test
    void isAvailable_trueWhenConfigured() {
        assertTrue(sink.isAvailable());
    }

    @Test
    void isAvailable_falseWhenDisabled() {
        when(mockConnectionResolver.isAnyEnabled()).thenReturn(false);
        assertFalse(sink.isAvailable());
    }

    @Test
    void isAvailable_falseWhenNoEndpoint() {
        when(mockConnectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("",
                        "api/atlas/v2", "tenant-1", "client-1", "secret", 5000, 30000));
        assertFalse(sink.isAvailable());
    }

    // --- Type mapping tests ---

    @Test
    void mapProcessTypeName_archive() {
        assertEquals("nemaki_archive_process", PurviewLineageSink.mapProcessTypeName(LineageProcessType.ARCHIVE_COLD));
        assertEquals("nemaki_archive_process", PurviewLineageSink.mapProcessTypeName(LineageProcessType.ARCHIVE_LOCAL));
    }

    @Test
    void mapProcessTypeName_import() {
        assertEquals("nemaki_import_process", PurviewLineageSink.mapProcessTypeName(LineageProcessType.IMPORT_FILESYSTEM));
        assertEquals("nemaki_import_process", PurviewLineageSink.mapProcessTypeName(LineageProcessType.IMPORT_UPLOADED));
    }

    @Test
    void mapProcessTypeName_export() {
        assertEquals("nemaki_export_process", PurviewLineageSink.mapProcessTypeName(LineageProcessType.EXPORT_FILESYSTEM));
        assertEquals("nemaki_export_process", PurviewLineageSink.mapProcessTypeName(LineageProcessType.EXPORT_ZIP_FOLDER));
        assertEquals("nemaki_export_process", PurviewLineageSink.mapProcessTypeName(LineageProcessType.EXPORT_SELECTED_OBJECTS));
    }

    @Test
    void mapProcessTypeName_cloudSync() {
        assertEquals("nemaki_cloud_sync_process", PurviewLineageSink.mapProcessTypeName(LineageProcessType.CLOUD_SYNC_UPLOAD));
        assertEquals("nemaki_cloud_sync_process", PurviewLineageSink.mapProcessTypeName(LineageProcessType.CLOUD_SYNC_DOWNLOAD));
    }

    @Test
    void mapProcessTypeName_null() {
        assertEquals("nemaki_unknown_process", PurviewLineageSink.mapProcessTypeName(null));
    }

    // --- URI inference tests ---

    @Test
    void inferAssetTypeName_objects() {
        assertEquals("nemaki_document", PurviewLineageSink.inferAssetTypeName("nemaki://bedroom/objects/doc-1"));
    }

    @Test
    void inferAssetTypeName_archives() {
        assertEquals("nemaki_archive", PurviewLineageSink.inferAssetTypeName("nemaki://bedroom/archives/arc-1"));
    }

    @Test
    void inferAssetTypeName_externalAssets() {
        assertEquals("nemaki_external_asset", PurviewLineageSink.inferAssetTypeName("nemaki://bedroom/external-assets/ext-1"));
    }

    @Test
    void inferAssetTypeName_nullOrEmpty() {
        assertEquals("nemaki_document", PurviewLineageSink.inferAssetTypeName(null));
        assertEquals("nemaki_document", PurviewLineageSink.inferAssetTypeName(""));
    }

    // --- Process qualifiedName test ---

    @Test
    void buildProcessQualifiedName_importProcess() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_FILESYSTEM)
                .addInputObject("bedroom", "doc-1")
                .build();

        String qn = PurviewLineageSink.buildProcessQualifiedName(event);
        assertTrue(qn.startsWith("nemaki://bedroom/import-processes/"));
        assertTrue(qn.contains(event.eventKey()));
    }

    // --- extractNameFromUri test ---

    @Test
    void extractNameFromUri_normal() {
        assertEquals("doc-1", PurviewLineageSink.extractNameFromUri("nemaki://bedroom/objects/doc-1"));
    }

    @Test
    void extractNameFromUri_nullOrEmpty() {
        assertEquals("unknown", PurviewLineageSink.extractNameFromUri(null));
        assertEquals("unknown", PurviewLineageSink.extractNameFromUri(""));
    }

    // --- Publish tests ---

    @Test
    void publish_successCallsBulkCreate() throws Exception {
        when(mockClient.bulkCreateOrUpdateEntities(any(PurviewConnectionRequest.class), any(Map.class)))
                .thenReturn(PurviewEntityPublishResult.success(3, "OK"));

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .addOutputObject("bedroom", "imported-1")
                .targets(List.of("purview"))
                .build();

        LineageTargetSinkResult result = sink.publish(event);

        assertTrue(result.success());
        assertEquals(3, result.entityCount());
        verify(mockClient).bulkCreateOrUpdateEntities(any(), any());
    }

    @Test
    void publish_failureReturnsFailureResult() throws Exception {
        when(mockClient.bulkCreateOrUpdateEntities(any(PurviewConnectionRequest.class), any(Map.class)))
                .thenReturn(PurviewEntityPublishResult.failure("Auth error"));

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_FILESYSTEM)
                .addInputObject("bedroom", "doc-1")
                .build();

        LineageTargetSinkResult result = sink.publish(event);

        assertFalse(result.success());
        assertTrue(result.message().contains("Auth error"));
    }

    @Test
    void publish_nullEventReturnsSkipped() throws Exception {
        LineageTargetSinkResult result = sink.publish(null);
        assertTrue(result.success());
        assertEquals(0, result.entityCount());
        assertTrue(result.message().contains("skipped"));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
