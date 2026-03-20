package jp.aegif.nemaki.rest.purview.schema;

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifest;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaStateService;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PurviewSchemaPlannerServiceImplTest {

    private PurviewConfig config;
    private PurviewSchemaStateService schemaStateService;
    private PurviewSchemaManifestFactory manifestFactory;
    private PurviewSchemaPlannerServiceImpl plannerService;

    @BeforeEach
    public void setUp() {
        config = mock(PurviewConfig.class);
        schemaStateService = mock(PurviewSchemaStateService.class);
        manifestFactory = new PurviewSchemaManifestFactory();
        plannerService = new PurviewSchemaPlannerServiceImpl(config, schemaStateService, manifestFactory);
        when(config.getCollection()).thenReturn("NemakiWare");
    }

    @Test
    public void testSchemaDiffRequiresApplyWhenNothingHasBeenAppliedYet() {
        when(schemaStateService.getSchemaState("NemakiWare"))
                .thenReturn(new PurviewSchemaState("NemakiWare", "", "", "", "", ""));

        PurviewSchemaDiff diff = plannerService.getSchemaDiff();

        assertTrue(diff.isApplyRequired());
        assertFalse(diff.getCustomTypeNames().isEmpty());
        assertNotEquals("", diff.getDesiredSchemaHash());
    }

    @Test
    public void testSchemaDiffIsCleanWhenCurrentStateMatchesDesiredManifest() {
        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare",
                manifest.getSchemaVersion(),
                manifest.getSchemaHash(),
                "2026-03-20T00:00:00Z",
                "admin",
                "up to date");
        when(schemaStateService.getSchemaState("NemakiWare")).thenReturn(currentState);

        PurviewSchemaDiff diff = plannerService.getSchemaDiff();

        assertFalse(diff.isApplyRequired());
    }
}
