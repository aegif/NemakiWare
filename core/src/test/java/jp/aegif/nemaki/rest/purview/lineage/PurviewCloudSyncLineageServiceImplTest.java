package jp.aegif.nemaki.rest.purview.lineage;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

public class PurviewCloudSyncLineageServiceImplTest {

    private MetadataCatalogConnectionResolver connectionResolver;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewCloudSyncLineageServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        connectionResolver = mock(MetadataCatalogConnectionResolver.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        when(connectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("https://example-account.purview.azure.com",
                        "datamap/api/atlas/v2", "tenant-123", "client-123", "secret-123", 5000, 30000));
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(2, "published"));
        when(entityRegistryClient.deleteByUniqueAttribute(any(), any(), any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "deleted"));

        service = new PurviewCloudSyncLineageServiceImpl(
                connectionResolver,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertCloudSyncLineagePublishesExternalAssetAndProcess() throws Exception {
        Document document = documentWithCloud("doc-001", "google", "cloud-001", "https://drive.example/doc-001");

        int processedCount = service.upsertCloudSyncLineage("bedroom", List.of(document));

        assertEquals(1, processedCount);
        // 2-phase bulk: Phase 1 = external assets, Phase 2 = process entities
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient, times(2)).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> allPayloads = payloadCaptor.getAllValues();

        // Phase 1: external assets
        List<Map<String, Object>> assetEntities = (List<Map<String, Object>>) allPayloads.get(0).get("entities");
        assertEquals(1, assetEntities.size());
        Map<String, Object> externalAsset = assetEntities.get(0);
        assertEquals("nemaki_external_asset", externalAsset.get("typeName"));
        Map<String, Object> assetAttrs = (Map<String, Object>) externalAsset.get("attributes");
        assertEquals("google", assetAttrs.get("sourceSystem"));
        assertEquals("google:cloud-001", assetAttrs.get("externalStableKey"));

        // Phase 2: process entities
        List<Map<String, Object>> processEntities = (List<Map<String, Object>>) allPayloads.get(1).get("entities");
        assertEquals(1, processEntities.size());
        Map<String, Object> syncProcess = processEntities.get(0);
        assertEquals("nemaki_cloud_sync_process", syncProcess.get("typeName"));
        Map<String, Object> processAttrs = (Map<String, Object>) syncProcess.get("attributes");
        assertEquals("google", processAttrs.get("cloudProvider"));
        assertEquals("google:cloud-001", processAttrs.get("externalStableKey"));
    }

    @Test
    public void testReconcileRemovedCloudSyncLineageDeletesProcessAndExternalAsset() throws Exception {
        int reconciledCount = service.reconcileRemovedCloudSyncLineage(
                "bedroom",
                Map.of("doc-001", "doc-001|google|cloud-001|https://drive.example/doc-001|2026-03-20T03:00:00.000+0000"),
                Set.of());

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

    @Test
    public void testUpsertCloudSyncLineageReturnsZeroForNullList() throws Exception {
        int processedCount = service.upsertCloudSyncLineage("bedroom", null);

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @Test
    public void testUpsertCloudSyncLineageReturnsZeroForEmptyList() throws Exception {
        int processedCount = service.upsertCloudSyncLineage("bedroom", List.of());

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @Test
    public void testUpsertCloudSyncLineageThrowsWhenPublishFails() throws Exception {
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("HTTP 500: Internal Server Error"));

        Document document = documentWithCloud("doc-001", "google", "cloud-001", "https://drive.example/doc-001");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upsertCloudSyncLineage("bedroom", List.of(document)));

        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    public void testUpsertCloudSyncLineageThrowsWhenClientExceptionOccurs() throws Exception {
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenThrow(new PurviewClientException("Connection refused"));

        Document document = documentWithCloud("doc-001", "google", "cloud-001", "https://drive.example/doc-001");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upsertCloudSyncLineage("bedroom", List.of(document)));

        assertTrue(error.getMessage().contains("Connection refused"));
    }

    @Test
    public void testReconcileRemovedCloudSyncLineageReturnsZeroForEmptyMap() throws Exception {
        int reconciledCount = service.reconcileRemovedCloudSyncLineage("bedroom", Map.of(), Set.of());

        assertEquals(0, reconciledCount);
        verify(entityRegistryClient, never()).deleteByUniqueAttribute(any(), any(), any(), any());
    }

    @Test
    public void testReconcileRemovedCloudSyncLineageThrowsWhenDeleteFails() throws Exception {
        when(entityRegistryClient.deleteByUniqueAttribute(any(), any(), any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("HTTP 403: Forbidden"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reconcileRemovedCloudSyncLineage("bedroom",
                        Map.of("doc-001", "doc-001|google|cloud-001|https://drive.example/doc-001|2026-03-20T03:00:00.000+0000"),
                        Set.of()));

        assertTrue(error.getMessage().contains("403"));
    }

    @Test
    public void testDeleteObsoleteExternalAssetDeletesExternalAssetEntity() throws Exception {
        int deletedCount = service.deleteObsoleteExternalAsset("bedroom", "google:cloud-001");

        assertEquals(1, deletedCount);
        verify(entityRegistryClient).deleteByUniqueAttribute(
                any(),
                eq("nemaki_external_asset"),
                eq("qualifiedName"),
                argThat(qn -> qn != null && qn.startsWith("nemaki://bedroom/external-assets/")));
    }

    @Test
    public void testDeleteObsoleteExternalAssetReturnsZeroForNullStableKey() throws Exception {
        int deletedCount = service.deleteObsoleteExternalAsset("bedroom", null);

        assertEquals(0, deletedCount);
        verify(entityRegistryClient, never()).deleteByUniqueAttribute(any(), any(), any(), any());
    }

    @Test
    public void testDeleteObsoleteExternalAssetReturnsZeroForBlankStableKey() throws Exception {
        int deletedCount = service.deleteObsoleteExternalAsset("bedroom", "  ");

        assertEquals(0, deletedCount);
        verify(entityRegistryClient, never()).deleteByUniqueAttribute(any(), any(), any(), any());
    }

    @Test
    public void testDeleteObsoleteExternalAssetIgnores404Error() throws Exception {
        when(entityRegistryClient.deleteByUniqueAttribute(any(), any(), any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("HTTP 404: Entity not found"));

        // 404 errors are caught internally and return 0 (entity already gone)
        int deletedCount = service.deleteObsoleteExternalAsset("bedroom", "google:cloud-001");

        assertEquals(0, deletedCount);
    }

    /**
     * Regression: manual cleanup deletes only the process entity.
     * The external asset is skipped because it may be shared with other documents.
     */
    @Test
    public void testDeleteCloudSyncLineageByObjectIdSkipsExternalAsset() throws Exception {
        int deletedCount = service.deleteCloudSyncLineageByObjectId(
                "bedroom", "doc-001", "google:cloud-001");

        // Only the process entity should be deleted (1 call), not the external asset
        assertEquals(1, deletedCount);
        verify(entityRegistryClient, times(1)).deleteByUniqueAttribute(
                any(), eq("nemaki_cloud_sync_process"), any(), any());
        // External asset delete must NOT be called
        verify(entityRegistryClient, never()).deleteByUniqueAttribute(
                any(), eq("nemaki_external_asset"), any(), any());
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
