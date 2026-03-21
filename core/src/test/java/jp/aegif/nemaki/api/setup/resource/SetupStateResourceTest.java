package jp.aegif.nemaki.api.setup.resource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.model.SetupStateResponse;
import jp.aegif.nemaki.init.StartupProbeService;

/**
 * Tests for SetupStateResource.
 */
public class SetupStateResourceTest {

    private SetupStateResource resource;
    private StartupProbeService probeService;

    @BeforeEach
    public void setUp() throws Exception {
        resource = new SetupStateResource();
        probeService = mock(StartupProbeService.class);

        Field field = SetupStateResource.class.getDeclaredField("startupProbeService");
        field.setAccessible(true);
        field.set(resource, probeService);
    }

    @Test
    public void testGetStateInSetupMode() {
        when(probeService.isSetupRequired()).thenReturn(true);
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_UNREACHABLE);

        Response resp = resource.getState();

        assertEquals(200, resp.getStatus());
        SetupStateResponse body = (SetupStateResponse) resp.getEntity();
        assertTrue(body.isSetupRequired());
        assertEquals("DB_UNREACHABLE", body.getState());
        assertNotNull(body.getServerVersion());
        assertFalse(body.getServerVersion().contains("${"),
                "Version should not contain unresolved Maven placeholder");
        assertNotNull(body.getCompletedSteps());
        assertTrue(body.getCompletedSteps().isEmpty());
    }

    @Test
    public void testGetStateInNormalMode() {
        when(probeService.isSetupRequired()).thenReturn(false);
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);

        Response resp = resource.getState();

        assertEquals(200, resp.getStatus());
        SetupStateResponse body = (SetupStateResponse) resp.getEntity();
        assertFalse(body.isSetupRequired());
        assertEquals("DB_CONNECTED_CURRENT", body.getState());
    }

    @Test
    public void testGetStateDbConnectedEmpty() {
        when(probeService.isSetupRequired()).thenReturn(true);
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_EMPTY);

        Response resp = resource.getState();

        assertEquals(200, resp.getStatus());
        SetupStateResponse body = (SetupStateResponse) resp.getEntity();
        assertTrue(body.isSetupRequired());
        assertEquals("DB_CONNECTED_EMPTY", body.getState());
    }

    @Test
    public void testGetStateDbConnectedLegacy() {
        when(probeService.isSetupRequired()).thenReturn(true);
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_LEGACY);

        Response resp = resource.getState();

        assertEquals(200, resp.getStatus());
        SetupStateResponse body = (SetupStateResponse) resp.getEntity();
        assertEquals("DB_CONNECTED_LEGACY", body.getState());
    }

    @Test
    public void testGetStateWithNullProbeService() throws Exception {
        Field field = SetupStateResource.class.getDeclaredField("startupProbeService");
        field.setAccessible(true);
        field.set(resource, null);

        Response resp = resource.getState();

        assertEquals(200, resp.getStatus());
        SetupStateResponse body = (SetupStateResponse) resp.getEntity();
        assertFalse(body.isSetupRequired());
        assertEquals("UNKNOWN", body.getState());
        assertNotNull(body.getServerVersion());
    }

    @Test
    public void testServerVersionIsNotHardcoded() {
        // SERVER_VERSION should come from version.properties (Maven resource filtering)
        // In test context, properties file may not be filtered, so it falls back to "unknown"
        // The important thing is it should NOT be a hardcoded RC-specific string
        String version = SetupStateResource.SERVER_VERSION;
        assertNotNull(version);
        assertFalse(version.isEmpty());
        // Should not contain unresolved Maven placeholder
        assertFalse(version.contains("${"), "Version should be resolved, not a Maven placeholder");
    }
}
