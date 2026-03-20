package jp.aegif.nemaki.rest.purview.governance;

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Relationship;

public class PurviewGovernanceServiceImplTest {

    private PurviewConfig purviewConfig;
    private ContentService contentService;
    private ExceptionService exceptionService;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewGovernanceServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        purviewConfig = mock(PurviewConfig.class);
        contentService = mock(ContentService.class);
        exceptionService = mock(ExceptionService.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        when(purviewConfig.isEnabled()).thenReturn(true);
        when(purviewConfig.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(purviewConfig.getAtlasBasePath()).thenReturn("catalog/api/atlas/v2");
        when(purviewConfig.getTenantId()).thenReturn("tenant-123");
        when(purviewConfig.getClientId()).thenReturn("client-123");
        when(purviewConfig.getClientSecret()).thenReturn("secret-123");
        when(purviewConfig.getConnectTimeoutMs()).thenReturn(5000);
        when(purviewConfig.getReadTimeoutMs()).thenReturn(30000);

        service = new PurviewGovernanceServiceImpl(
                purviewConfig,
                contentService,
                exceptionService,
                entityRegistryClient,
                new PurviewEntityPayloadFactory());
    }

    @Test
    public void testGetGovernanceLoadsClassificationsTermsLabelsAndBusinessMetadata() throws Exception {
        Document document = new Document();
        document.setId("doc-001");
        document.setObjectType("D:custom:report");
        when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);

        when(entityRegistryClient.getEntityByUniqueAttribute(
                argThat(request -> request != null && "catalog/api/atlas/v2".equals(request.getAtlasBasePath())),
                any(),
                any(),
                any())).thenReturn(null);
        when(entityRegistryClient.getEntityByUniqueAttribute(
                argThat(request -> request != null && "datamap/api/atlas/v2".equals(request.getAtlasBasePath())),
                any(),
                any(),
                any())).thenReturn(Map.of(
                        "entity", Map.of(
                                "typeName", "nemaki_document",
                                "classifications", List.of(Map.of(
                                        "typeName", "HighlyConfidential",
                                        "entityStatus", "ACTIVE")),
                                "meanings", List.of(Map.of(
                                        "displayText", "Quarterly Report",
                                        "termGuid", "term-001",
                                        "relationGuid", "rel-001",
                                        "status", "DISCOVERED")),
                                "labels", List.of("finance", "quarterly"),
                                "businessAttributes", Map.of(
                                        "nemakiGovernance", Map.of(
                                                "ownerDepartment", "Finance",
                                                "retentionPolicy", "7y")))));

        PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

        assertTrue(view.isFeatureEnabled());
        assertTrue(view.isAvailable());
        assertTrue(view.isSupportedObjectType());
        assertTrue(view.isEntityFound());
        assertEquals("nemaki_document", view.getEntityTypeName());
        assertEquals("nemaki://bedroom/objects/doc-001", view.getQualifiedName());
        assertEquals("datamap/api/atlas/v2", view.getAtlasBasePath());
        assertEquals(1, view.getClassifications().size());
        assertEquals("HighlyConfidential", view.getClassifications().get(0).get("typeName"));
        assertEquals(1, view.getGlossaryTerms().size());
        assertEquals("Quarterly Report", view.getGlossaryTerms().get(0).get("displayText"));
        assertEquals(List.of("finance", "quarterly"), view.getLabels());
        assertEquals("Finance", view.getBusinessMetadata().get("nemakiGovernance").get("ownerDepartment"));
        verify(exceptionService).permissionDenied(any(), any(), any(), any());
    }

    @Test
    public void testGetGovernanceReturnsUnsupportedStateForNonFolderOrDocument() {
        Relationship relationship = new Relationship();
        relationship.setId("rel-001");
        when(contentService.getContent("bedroom", "rel-001")).thenReturn(relationship);

        PurviewGovernanceView view = service.getGovernance("bedroom", "rel-001", mock(CallContext.class));

        assertFalse(view.isAvailable());
        assertFalse(view.isSupportedObjectType());
        assertFalse(view.isEntityFound());
        verifyNoInteractions(entityRegistryClient);
    }

    @Test
    public void testGetGovernanceReturnsUnavailableStateWhenPurviewIsDisabled() {
        Document document = new Document();
        document.setId("doc-001");
        when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
        when(purviewConfig.isEnabled()).thenReturn(false);

        PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

        assertFalse(view.isFeatureEnabled());
        assertFalse(view.isAvailable());
        assertTrue(view.isSupportedObjectType());
        assertFalse(view.isEntityFound());
        verifyNoInteractions(entityRegistryClient);
    }

    @Test
    public void testGetGovernanceBulkReturnsMixedStatuses() {
        Document document = new Document();
        document.setId("doc-001");
        when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
        when(contentService.getContent("bedroom", "missing-001")).thenReturn(null);

        List<PurviewGovernanceBulkItemView> items = service.getGovernanceBulk(
                "bedroom",
                List.of("doc-001", "missing-001", "doc-001"),
                mock(CallContext.class));

        assertEquals(2, items.size());
        assertEquals("doc-001", items.get(0).getObjectId());
        assertEquals("OK", items.get(0).getStatus());
        assertEquals("missing-001", items.get(1).getObjectId());
        assertEquals("NOT_FOUND", items.get(1).getStatus());
    }
}
