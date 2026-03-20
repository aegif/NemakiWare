package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class PurviewImportExportLineageServiceImplTest {

    private PurviewConfig config;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewImportExportLineageServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(PurviewConfig.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        when(config.isEnabled()).thenReturn(true);
        when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-123");
        when(config.getClientId()).thenReturn("client-123");
        when(config.getClientSecret()).thenReturn("secret-123");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(2, "published"));

        service = new PurviewImportExportLineageServiceImpl(
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertFilesystemImportLineagePublishesExternalAssetAndProcess() throws Exception {
        int processedCount = service.upsertFilesystemImportLineage(
                "bedroom",
                "folder-001",
                "/managed/imports/team-a",
                "alice",
                5);

        assertEquals(1, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        assertEquals(List.of("nemaki_external_asset", "nemaki_import_process"),
                entities.stream().map(entity -> entity.get("typeName").toString()).sorted().toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertFilesystemExportLineagePublishesExternalAssetAndProcess() throws Exception {
        int processedCount = service.upsertFilesystemExportLineage(
                "bedroom",
                "folder-001",
                "/managed/exports/team-a",
                "alice",
                7);

        assertEquals(1, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        assertEquals(List.of("nemaki_export_process", "nemaki_external_asset"),
                entities.stream().map(entity -> entity.get("typeName").toString()).sorted().toList());
    }

    @Test
    public void testUpsertFilesystemLineageSkipsWhenPurviewIsDisabled() throws Exception {
        when(config.isEnabled()).thenReturn(false);

        int importCount = service.upsertFilesystemImportLineage(
                "bedroom",
                "folder-001",
                "/managed/imports/team-a",
                "alice",
                5);
        int exportCount = service.upsertFilesystemExportLineage(
                "bedroom",
                "folder-001",
                "/managed/exports/team-a",
                "alice",
                7);

        assertEquals(0, importCount);
        assertEquals(0, exportCount);
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
    }
}
