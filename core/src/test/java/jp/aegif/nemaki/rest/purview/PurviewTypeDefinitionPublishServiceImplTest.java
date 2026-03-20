package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

public class PurviewTypeDefinitionPublishServiceImplTest {

    private PurviewConfig config;
    private ContentDaoService contentDaoService;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewTypeDefinitionPublishServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(PurviewConfig.class);
        contentDaoService = mock(ContentDaoService.class);
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

        service = new PurviewTypeDefinitionPublishServiceImpl(
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient,
                contentDaoService);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPublishRepositoryTypeDefinitionsPublishesMappedEntities() throws Exception {
        when(contentDaoService.getTypeDefinitions("bedroom"))
                .thenReturn(List.of(typeDefinition("D:custom:report"), typeDefinition("F:custom:folder")));

        int processedCount = service.publishRepositoryTypeDefinitions("bedroom");

        assertEquals(2, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        assertEquals(List.of("nemaki_type_definition", "nemaki_type_definition"),
                entities.stream().map(entity -> entity.get("typeName").toString()).sorted().toList());
    }

    @Test
    public void testPublishRepositoryTypeDefinitionsFailsWhenPurviewRejectsBulkUpsert() throws Exception {
        when(contentDaoService.getTypeDefinitions("bedroom"))
                .thenReturn(List.of(typeDefinition("D:custom:report")));
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("rate limited"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.publishRepositoryTypeDefinitions("bedroom"));

        assertEquals("rate limited", error.getMessage());
    }

    @Test
    public void testSyncRepositoryTypeDefinitionsIfChangedDeletesMissingTypeDefinitions() throws Exception {
        when(contentDaoService.getTypeDefinitions("bedroom"))
                .thenReturn(List.of(typeDefinition("D:custom:report")));
        when(entityRegistryClient.deleteByUniqueAttribute(any(), any(), any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "deleted"));

        PurviewTypeDefinitionSyncResult result = service.syncRepositoryTypeDefinitionsIfChanged(
                "bedroom",
                "D:custom:report|0||1\nD:custom:legacy|0||1");

        assertTrue(result.isChanged());
        assertEquals(1, result.getPublishedCount());
        assertEquals(1, result.getDeletedCount());
        assertTrue(result.getSnapshot().contains("D:custom:report|"));
        verify(entityRegistryClient).deleteByUniqueAttribute(
                any(),
                any(),
                any(),
                any());
    }

    @Test
    public void testSyncRepositoryTypeDefinitionsIfChangedSkipsPurviewCallsWhenSnapshotIsUnchanged() throws Exception {
        NemakiTypeDefinition typeDefinition = typeDefinition("D:custom:report");
        when(contentDaoService.getTypeDefinitions("bedroom"))
                .thenReturn(List.of(typeDefinition));

        PurviewTypeDefinitionSyncResult result = service.syncRepositoryTypeDefinitionsIfChanged(
                "bedroom",
                service.buildRepositoryTypeDefinitionSnapshot("bedroom"));

        assertFalse(result.isChanged());
        assertEquals(0, result.getProcessedCount());
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
        verify(entityRegistryClient, never()).deleteByUniqueAttribute(any(), any(), any(), any());
    }

    private NemakiTypeDefinition typeDefinition(String typeId) {
        NemakiTypeDefinition typeDefinition = new NemakiTypeDefinition();
        typeDefinition.setId("node-" + typeId);
        typeDefinition.setTypeId(typeId);
        typeDefinition.setDisplayName(typeId);
        typeDefinition.setQueryName(typeId.replace(':', '_'));
        typeDefinition.setBaseId(typeId.startsWith("F:") ? BaseTypeId.CMIS_FOLDER : BaseTypeId.CMIS_DOCUMENT);
        typeDefinition.setProperties(List.of("prop-1"));
        return typeDefinition;
    }
}
