package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

public class PurviewCloudSyncLineageServiceImplTest {

    private PurviewConfig config;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewCloudSyncLineageServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(PurviewConfig.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-123");
        when(config.getClientId()).thenReturn("client-123");
        when(config.getClientSecret()).thenReturn("secret-123");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(2, "published"));
        when(entityRegistryClient.deleteByUniqueAttribute(any(), any(), any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "deleted"));

        service = new PurviewCloudSyncLineageServiceImpl(
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertCloudSyncLineagePublishesExternalAssetAndProcess() throws Exception {
        Document document = documentWithCloud("doc-001", "google", "cloud-001", "https://drive.example/doc-001");

        int processedCount = service.upsertCloudSyncLineage("bedroom", List.of(document));

        assertEquals(1, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        assertEquals(List.of("nemaki_cloud_sync_process", "nemaki_external_asset"),
                entities.stream().map(entity -> entity.get("typeName").toString()).sorted().toList());
    }

    @Test
    public void testReconcileRemovedCloudSyncLineageDeletesProcessAndExternalAsset() throws Exception {
        int reconciledCount = service.reconcileRemovedCloudSyncLineage(
                "bedroom",
                Map.of("doc-001", "doc-001|google|cloud-001|https://drive.example/doc-001|2026-03-20T03:00:00.000+0000"));

        assertEquals(2, reconciledCount);
        verify(entityRegistryClient, times(2)).deleteByUniqueAttribute(any(), any(), any(), any());
    }

    @Test
    public void testUpsertCloudSyncLineageSkipsDocumentsWithoutStableKey() throws Exception {
        Document document = documentWithCloud("doc-001", "google", "", "https://drive.example/doc-001");

        int processedCount = service.upsertCloudSyncLineage("bedroom", List.of(document));

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    private Document documentWithCloud(String objectId, String provider, String externalFileId, String cloudFileUrl) {
        Document document = new Document();
        document.setId(objectId);
        document.setName(objectId);
        document.setParentId("folder-001");
        document.setObjectType("cmis:document");
        document.setAspects(List.of(new Aspect("nemaki:cloudDriveMetadata", List.of(
                new Property("nemaki:cloudProvider", provider),
                new Property("nemaki:cloudFileId", externalFileId),
                new Property("nemaki:cloudFileUrl", cloudFileUrl),
                new Property("nemaki:cloudLastSyncedAt", "2026-03-20T03:00:00.000+0000")))));
        return document;
    }
}
