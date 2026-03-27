package jp.aegif.nemaki.rest.purview.schema;

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifest;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewSchemaPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewSchemaRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaStateService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PurviewSchemaApplyServiceImplTest {

    private PurviewConfig config;
    private PurviewSchemaPlannerService plannerService;
    private PurviewSchemaStateService schemaStateService;
    private PurviewSchemaManifestFactory manifestFactory;
    private PurviewSchemaPayloadFactory payloadFactory;
    private PurviewSchemaRegistryClient registryClient;
    private PurviewSchemaApplyServiceImpl applyService;

    @BeforeEach
    public void setUp() {
        config = mock(PurviewConfig.class);
        plannerService = mock(PurviewSchemaPlannerService.class);
        schemaStateService = mock(PurviewSchemaStateService.class);
        manifestFactory = new PurviewSchemaManifestFactory();
        payloadFactory = new PurviewSchemaPayloadFactory();
        registryClient = mock(PurviewSchemaRegistryClient.class);
        applyService = new PurviewSchemaApplyServiceImpl(
                config,
                plannerService,
                schemaStateService,
                manifestFactory,
                payloadFactory,
                registryClient,
                new AtlasSchemaCompatHelper());

        when(config.getCollection()).thenReturn("NemakiWare");
        when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-123");
        when(config.getClientId()).thenReturn("client-123");
        when(config.getClientSecret()).thenReturn("secret-123");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);
        // Default: Purview cloud mode (existing tests assume Purview mode, not Atlas on-prem)
        when(config.isAtlasOnPrem()).thenReturn(false);
    }

    @Test
    public void testApplySchemaSkipsWhenNoDiffExists() throws Exception {
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "1", "same-hash", "2026-03-20T00:00:00Z", "admin", "up to date");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "1", "same-hash", "1", "same-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        when(plannerService.getCurrentSchemaState()).thenReturn(currentState);
        when(plannerService.getSchemaDiff()).thenReturn(diff);

        PurviewSchemaApplyResult result = applyService.applySchema("admin");

        assertFalse(result.isApplied());
        assertEquals("up to date", result.getMessage());
        verify(registryClient, never()).applySchema(any(), any());
        verify(schemaStateService, never()).saveSchemaState(any());
    }

    @Test
    public void testApplySchemaPublishesPayloadAndSavesState() throws Exception {
        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "", "", "", "", "");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "", "", manifest.getSchemaVersion(), manifest.getSchemaHash(), true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of());
        PurviewSchemaState savedState = new PurviewSchemaState(
                "NemakiWare", manifest.getSchemaVersion(), manifest.getSchemaHash(),
                "2026-03-20T01:00:00Z", "admin", "applied");
        when(plannerService.getCurrentSchemaState()).thenReturn(currentState);
        when(plannerService.getSchemaDiff()).thenReturn(diff);
        when(registryClient.applySchema(any(), argThat(payload -> payload != null
                && ((java.util.List<?>) payload.get("entityDefs")).size() >= 1
                && ((java.util.List<?>) payload.get("relationshipDefs")).size() >= 1)))
                .thenReturn(PurviewSchemaPublishResult.success("schema applied"));
        when(schemaStateService.saveSchemaState(any())).thenReturn(savedState);

        PurviewSchemaApplyResult result = applyService.applySchema("admin");

        assertTrue(result.isApplied());
        assertEquals("schema applied", result.getMessage());
        assertEquals(manifest.getSchemaHash(), result.getSchemaState().getSchemaHash());
        verify(schemaStateService).saveSchemaState(argThat(state ->
                "NemakiWare".equals(state.getCollection())
                        && manifest.getSchemaVersion().equals(state.getSchemaVersion())
                        && manifest.getSchemaHash().equals(state.getSchemaHash())
                        && "admin".equals(state.getLastAppliedBy())));
    }

    @Test
    public void testApplySchemaReturnsFailureWhenPublishFails() throws Exception {
        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "", "", "", "", "");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "", "", manifest.getSchemaVersion(), manifest.getSchemaHash(), true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of());
        when(plannerService.getCurrentSchemaState()).thenReturn(currentState);
        when(plannerService.getSchemaDiff()).thenReturn(diff);
        when(registryClient.applySchema(any(), any()))
                .thenReturn(PurviewSchemaPublishResult.failure("HTTP 409: Type already exists"));

        PurviewSchemaApplyResult result = applyService.applySchema("admin");

        assertFalse(result.isApplied());
        assertTrue(result.getMessage().contains("409"));
        verify(schemaStateService, never()).saveSchemaState(any());
    }

    @Test
    public void testApplySchemaReturnsFailureWhenClientExceptionIsThrown() throws Exception {
        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "", "", "", "", "");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "", "", manifest.getSchemaVersion(), manifest.getSchemaHash(), true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of());
        when(plannerService.getCurrentSchemaState()).thenReturn(currentState);
        when(plannerService.getSchemaDiff()).thenReturn(diff);
        when(registryClient.applySchema(any(), any()))
                .thenThrow(new PurviewClientException("Connection timed out"));

        PurviewSchemaApplyResult result = applyService.applySchema("admin");

        assertFalse(result.isApplied());
        assertTrue(result.getMessage().contains("Connection timed out"));
        verify(schemaStateService, never()).saveSchemaState(any());
    }

    @Test
    public void testApplySchemaAtlasModeRetriesWithoutBusinessMetadataDefsOnFailure() throws Exception {
        when(config.isAtlasOnPrem()).thenReturn(true);
        when(config.getAtlasBasePath()).thenReturn("api/atlas/v2");

        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "", "", "", "", "");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "", "", manifest.getSchemaVersion(), manifest.getSchemaHash(), true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of());
        String expectedPartialHash = manifest.getSchemaHash() + PurviewSchemaApplyServiceImpl.ATLAS_PARTIAL_SUFFIX;
        PurviewSchemaState savedState = new PurviewSchemaState(
                "NemakiWare", manifest.getSchemaVersion(), expectedPartialHash,
                "2026-03-20T01:00:00Z", "admin", "applied");
        when(plannerService.getCurrentSchemaState()).thenReturn(currentState);
        when(plannerService.getSchemaDiff()).thenReturn(diff);
        // First call fails, second (fallback without businessMetadataDefs) succeeds
        when(registryClient.applySchema(any(), any()))
                .thenReturn(PurviewSchemaPublishResult.failure("businessMetadataDefs error"))
                .thenReturn(PurviewSchemaPublishResult.success("schema applied"));
        when(schemaStateService.saveSchemaState(any())).thenReturn(savedState);

        PurviewSchemaApplyResult result = applyService.applySchema("admin");

        assertTrue(result.isApplied());
        assertEquals("schema applied", result.getMessage());
        verify(registryClient, times(2)).applySchema(any(), any());

        // Partial hash: stored hash differs from manifest hash → next diff triggers re-apply
        verify(schemaStateService).saveSchemaState(argThat(state ->
                state.getSchemaHash().endsWith(PurviewSchemaApplyServiceImpl.ATLAS_PARTIAL_SUFFIX)
                        && !state.getSchemaHash().equals(manifest.getSchemaHash())
                        && state.getLastDiffSummary().contains("atlas-partial")));
    }

    @Test
    public void testApplySchemaPurviewModeSkipsAtlasFallback() throws Exception {
        when(config.isAtlasOnPrem()).thenReturn(false);

        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "", "", "", "", "");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "", "", manifest.getSchemaVersion(), manifest.getSchemaHash(), true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of());
        when(plannerService.getCurrentSchemaState()).thenReturn(currentState);
        when(plannerService.getSchemaDiff()).thenReturn(diff);
        when(registryClient.applySchema(any(), any()))
                .thenReturn(PurviewSchemaPublishResult.failure("HTTP 409: Type already exists"));

        PurviewSchemaApplyResult result = applyService.applySchema("admin");

        assertFalse(result.isApplied());
        assertTrue(result.getMessage().contains("409"));
        // In Purview mode, no retry — only 1 call
        verify(registryClient, times(1)).applySchema(any(), any());
    }

    @Test
    public void testApplySchemaCatalogPathSkipsAtlasFallback() throws Exception {
        // catalog/api/atlas/v2 is a Purview cloud path, NOT Atlas on-prem
        when(config.isAtlasOnPrem()).thenReturn(false);
        when(config.getAtlasBasePath()).thenReturn("catalog/api/atlas/v2");

        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "", "", "", "", "");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "", "", manifest.getSchemaVersion(), manifest.getSchemaHash(), true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of());
        when(plannerService.getCurrentSchemaState()).thenReturn(currentState);
        when(plannerService.getSchemaDiff()).thenReturn(diff);
        when(registryClient.applySchema(any(), any()))
                .thenReturn(PurviewSchemaPublishResult.failure("HTTP 409: Type already exists"));

        PurviewSchemaApplyResult result = applyService.applySchema("admin");

        assertFalse(result.isApplied());
        // catalog/ path should NOT trigger Atlas fallback — only 1 call
        verify(registryClient, times(1)).applySchema(any(), any());
    }

    @Test
    public void testApplySchemaPreservesDiffInResult() throws Exception {
        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "", "", "", "", "");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "", "", manifest.getSchemaVersion(), manifest.getSchemaHash(), true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of());
        PurviewSchemaState savedState = new PurviewSchemaState(
                "NemakiWare", manifest.getSchemaVersion(), manifest.getSchemaHash(),
                "2026-03-20T01:00:00Z", "admin", "applied");
        when(plannerService.getCurrentSchemaState()).thenReturn(currentState);
        when(plannerService.getSchemaDiff()).thenReturn(diff);
        when(registryClient.applySchema(any(), any()))
                .thenReturn(PurviewSchemaPublishResult.success("schema applied"));
        when(schemaStateService.saveSchemaState(any())).thenReturn(savedState);

        PurviewSchemaApplyResult result = applyService.applySchema("admin");

        assertNotNull(result.getSchemaDiff());
        assertTrue(result.getSchemaDiff().isApplyRequired());
    }
}
