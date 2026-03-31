package jp.aegif.nemaki.rest.purview.governance;

import jp.aegif.nemaki.rest.purview.CatalogBackendKind;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Relationship;

public class PurviewGovernanceServiceImplTest {

    private MetadataCatalogConnectionResolver connectionResolver;
    private ContentService contentService;
    private ExceptionService exceptionService;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewGovernanceServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        connectionResolver = mock(MetadataCatalogConnectionResolver.class);
        contentService = mock(ContentService.class);
        exceptionService = mock(ExceptionService.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        when(connectionResolver.isAnyEnabled()).thenReturn(true);
        when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.PURVIEW);
        when(connectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("https://example-account.purview.azure.com",
                        "datamap/api/atlas/v2", "tenant-123", "client-123", "secret-123", 5000, 30000));
        when(connectionResolver.buildConnectionRequest(eq("catalog/api/atlas/v2"))).thenReturn(
                new PurviewConnectionRequest("https://example-account.purview.azure.com",
                        "catalog/api/atlas/v2", "tenant-123", "client-123", "secret-123", 5000, 30000));
        when(connectionResolver.buildConnectionRequest(eq("datamap/api/atlas/v2"))).thenReturn(
                new PurviewConnectionRequest("https://example-account.purview.azure.com",
                        "datamap/api/atlas/v2", "tenant-123", "client-123", "secret-123", 5000, 30000));

        service = new PurviewGovernanceServiceImpl(
                connectionResolver,
                contentService,
                exceptionService,
                entityRegistryClient,
                new PurviewEntityPayloadFactory());
    }

    @Test
    public void testGetGovernanceLoadsClassificationsTermsLabelsAndBusinessMetadata() throws Exception {
        // Override default basePath so that catalog is tried first, then fallback to datamap
        when(connectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("https://example-account.purview.azure.com",
                        "catalog/api/atlas/v2", "tenant-123", "client-123", "secret-123", 5000, 30000));

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
        verify(entityRegistryClient).getEntityByUniqueAttribute(
                argThat(request -> request != null && "catalog/api/atlas/v2".equals(request.getAtlasBasePath())),
                eq("nemaki_document"),
                eq("qualifiedName"),
                eq("nemaki://bedroom/objects/doc-001"));
        verify(entityRegistryClient).getEntityByUniqueAttribute(
                argThat(request -> request != null && "datamap/api/atlas/v2".equals(request.getAtlasBasePath())),
                eq("nemaki_document"),
                eq("qualifiedName"),
                eq("nemaki://bedroom/objects/doc-001"));
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
        when(connectionResolver.isAnyEnabled()).thenReturn(false);

        PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

        assertFalse(view.isFeatureEnabled());
        assertFalse(view.isAvailable());
        assertTrue(view.isSupportedObjectType());
        assertFalse(view.isEntityFound());
        verifyNoInteractions(entityRegistryClient);
    }

    @Test
    public void testGetGovernanceReturnsUnavailableWhenConfigurationIsMissing() {
        Document document = new Document();
        document.setId("doc-001");
        document.setObjectType("cmis:document");
        when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
        when(connectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("", "datamap/api/atlas/v2",
                        "tenant-123", "client-123", "secret-123", 5000, 30000));

        PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

        assertTrue(view.isFeatureEnabled());
        assertFalse(view.isAvailable());
        assertTrue(view.getMessage().contains("endpoint"));
        verifyNoInteractions(entityRegistryClient);
    }

    @Test
    public void testGetGovernanceReturnsErrorViewWhenClientExceptionOccurs() throws Exception {
        Document document = new Document();
        document.setId("doc-001");
        document.setObjectType("cmis:document");
        when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
        when(entityRegistryClient.getEntityByUniqueAttribute(any(), any(), any(), any()))
                .thenThrow(new PurviewClientException("Connection timed out"));

        PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

        assertTrue(view.isFeatureEnabled());
        assertFalse(view.isAvailable());
        assertFalse(view.isEntityFound());
        assertTrue(view.getMessage().contains("Connection timed out"));
        verify(entityRegistryClient, times(1)).getEntityByUniqueAttribute(any(), any(), any(), any());
    }

    @Test
    public void testGetGovernanceReturnsNotSyncedWhenEntityNotFoundOnAnyPath() throws Exception {
        Document document = new Document();
        document.setId("doc-001");
        document.setObjectType("cmis:document");
        when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
        when(entityRegistryClient.getEntityByUniqueAttribute(any(), any(), any(), any()))
                .thenReturn(null);

        PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

        assertTrue(view.isFeatureEnabled());
        assertTrue(view.isAvailable());
        assertFalse(view.isEntityFound());
        assertTrue(view.getMessage().contains("not synced"));
        verify(entityRegistryClient, times(2)).getEntityByUniqueAttribute(any(), any(), any(), any());
    }

    @Test
    public void testGetGovernanceBulkReturnsEmptyListForNullInput() {
        List<PurviewGovernanceBulkItemView> items = service.getGovernanceBulk(
                "bedroom", null, mock(CallContext.class));

        assertTrue(items.isEmpty());
    }

    @Test
    public void testGetGovernanceBulkReturnsEmptyListForEmptyInput() {
        List<PurviewGovernanceBulkItemView> items = service.getGovernanceBulk(
                "bedroom", List.of(), mock(CallContext.class));

        assertTrue(items.isEmpty());
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

    @Nested
    @DisplayName("getMissingConfiguration backend-specific validation")
    class MissingConfigurationValidation {

        @Test
        @DisplayName("OAuth2 mode reports missing tenantId, clientId, clientSecret")
        public void testOAuth2MissingCredentials() {
            Document document = new Document();
            document.setId("doc-001");
            document.setObjectType("cmis:document");
            when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
            when(connectionResolver.buildConnectionRequest()).thenReturn(
                    new PurviewConnectionRequest("https://purview.azure.com",
                            "datamap/api/atlas/v2", "oauth2",
                            "", "", "",  // missing tenantId, clientId, clientSecret
                            "", "",
                            5000, 30000));

            PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

            assertTrue(view.isFeatureEnabled());
            assertFalse(view.isAvailable());
            assertTrue(view.getMessage().contains("tenantId"));
            assertTrue(view.getMessage().contains("clientId"));
            assertTrue(view.getMessage().contains("clientSecret"));
        }

        @Test
        @DisplayName("Basic auth mode reports missing username and password")
        public void testBasicAuthMissingCredentials() {
            Document document = new Document();
            document.setId("doc-001");
            document.setObjectType("cmis:document");
            when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
            when(connectionResolver.buildConnectionRequest()).thenReturn(
                    new PurviewConnectionRequest("https://atlas.example.com",
                            "api/atlas/v2", "basic",
                            "", "", "",
                            "", "",  // missing username, password
                            5000, 30000));

            PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

            assertTrue(view.isFeatureEnabled());
            assertFalse(view.isAvailable());
            assertTrue(view.getMessage().contains("username"));
            assertTrue(view.getMessage().contains("password"));
        }

        @Test
        @DisplayName("No backend enabled reports appropriate message")
        public void testNoBackendEnabled() {
            Document document = new Document();
            document.setId("doc-001");
            document.setObjectType("cmis:document");
            when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
            when(connectionResolver.buildConnectionRequest())
                    .thenThrow(new IllegalStateException("No catalog backend enabled"));

            PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

            assertTrue(view.isFeatureEnabled());
            assertFalse(view.isAvailable());
            assertTrue(view.getMessage().contains("No catalog backend enabled"));
        }

        @Test
        @DisplayName("Fully configured OAuth2 passes validation")
        public void testOAuth2FullyConfiguredPassesValidation() throws Exception {
            Document document = new Document();
            document.setId("doc-001");
            document.setObjectType("cmis:document");
            when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
            when(connectionResolver.buildConnectionRequest()).thenReturn(
                    new PurviewConnectionRequest("https://purview.azure.com",
                            "datamap/api/atlas/v2", "oauth2",
                            "tenant-id", "client-id", "client-secret",
                            "", "",
                            5000, 30000));
            when(entityRegistryClient.getEntityByUniqueAttribute(any(), any(), any(), any()))
                    .thenReturn(null);

            PurviewGovernanceView view = service.getGovernance("bedroom", "doc-001", mock(CallContext.class));

            assertTrue(view.isAvailable());
            // Configuration is valid, so it proceeds to entity lookup (may try multiple candidate paths)
            verify(entityRegistryClient, atLeastOnce()).getEntityByUniqueAttribute(any(), any(), any(), any());
        }
    }
}
