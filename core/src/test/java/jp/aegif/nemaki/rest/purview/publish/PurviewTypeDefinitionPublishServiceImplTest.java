package jp.aegif.nemaki.rest.purview.publish;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.sync.PurviewTypeDefinitionSyncResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

public class PurviewTypeDefinitionPublishServiceImplTest {

    private MetadataCatalogConnectionResolver connectionResolver;
    private ContentDaoService contentDaoService;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewTypeDefinitionPublishServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        connectionResolver = mock(MetadataCatalogConnectionResolver.class);
        contentDaoService = mock(ContentDaoService.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        when(connectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("https://example-account.purview.azure.com",
                        "datamap/api/atlas/v2", "tenant-123", "client-123", "secret-123", 5000, 30000));
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(2, "published"));

        service = new PurviewTypeDefinitionPublishServiceImpl(
                connectionResolver,
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

    @Test
    public void testPublishRepositoryTypeDefinitionsReturnsZeroWhenNoTypeDefinitionsExist() throws Exception {
        when(contentDaoService.getTypeDefinitions("bedroom"))
                .thenReturn(List.of());

        int processedCount = service.publishRepositoryTypeDefinitions("bedroom");

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @Test
    public void testPublishRepositoryTypeDefinitionsThrowsOnClientException() throws Exception {
        when(contentDaoService.getTypeDefinitions("bedroom"))
                .thenReturn(List.of(typeDefinition("D:custom:report")));
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenThrow(new PurviewClientException("Connection refused"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.publishRepositoryTypeDefinitions("bedroom"));

        assertTrue(error.getMessage().contains("Connection refused"));
    }

    @Test
    public void testPublishRepositoryTypeDefinitionsFiltersOutNullAndBlankTypeIds() throws Exception {
        NemakiTypeDefinition validType = typeDefinition("D:custom:report");
        NemakiTypeDefinition blankType = new NemakiTypeDefinition();
        blankType.setId("node-blank");
        blankType.setTypeId("");
        List<NemakiTypeDefinition> typesWithNull = new ArrayList<>(Arrays.asList(validType, blankType, null));
        when(contentDaoService.getTypeDefinitions("bedroom"))
                .thenReturn(typesWithNull);

        int processedCount = service.publishRepositoryTypeDefinitions("bedroom");

        assertEquals(1, processedCount);
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
