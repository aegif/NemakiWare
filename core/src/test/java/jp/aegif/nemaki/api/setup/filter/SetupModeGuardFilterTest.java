package jp.aegif.nemaki.api.setup.filter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.init.StartupProbeService;

/**
 * Tests for SetupModeGuardFilter.
 * Verifies:
 *   - Fail-closed: null ProbeService blocks ALL (503)
 *   - Normal Mode: only /state allowed (403 otherwise)
 *   - Setup Mode + valid token: all endpoints allowed
 *   - Setup Mode + missing/invalid token: 401 (except /state)
 */
public class SetupModeGuardFilterTest {

    private static final String SETUP_TOKEN = "test-setup-token-uuid";

    private SetupModeGuardFilter filter;
    private StartupProbeService probeService;
    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;

    @BeforeEach
    public void setUp() throws Exception {
        filter = new SetupModeGuardFilter();
        probeService = mock(StartupProbeService.class);
        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);

        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(probeService.getSetupToken()).thenReturn(SETUP_TOKEN);

        Field field = SetupModeGuardFilter.class.getDeclaredField("startupProbeService");
        field.setAccessible(true);
        field.set(filter, probeService);
    }

    // ================================================================
    // Fail-closed: null ProbeService blocks everything (503)
    // ================================================================

    @Nested
    class FailClosed {

        @BeforeEach
        public void clearProbeService() throws Exception {
            Field field = SetupModeGuardFilter.class.getDeclaredField("startupProbeService");
            field.setAccessible(true);
            field.set(filter, null);
        }

        @Test
        public void testNullProbeServiceBlocksState() throws Exception {
            when(uriInfo.getPath()).thenReturn("state");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 503));
        }

        @Test
        public void testNullProbeServiceBlocksTestConnection() throws Exception {
            when(uriInfo.getPath()).thenReturn("couchdb/test-connection");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 503));
        }

        @Test
        public void testNullProbeServiceBlocksApply() throws Exception {
            when(uriInfo.getPath()).thenReturn("apply");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 503));
        }
    }

    // ================================================================
    // Setup Mode + valid token: all endpoints allowed
    // ================================================================

    @Nested
    class SetupModeWithToken {

        @BeforeEach
        public void setSetupMode() {
            when(probeService.isSetupRequired()).thenReturn(true);
            when(requestContext.getHeaderString("X-Setup-Token")).thenReturn(SETUP_TOKEN);
        }

        @Test
        public void testAllowsStatePath() throws Exception {
            when(uriInfo.getPath()).thenReturn("state");
            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }

        @Test
        public void testAllowsCouchDbTestConnection() throws Exception {
            when(uriInfo.getPath()).thenReturn("couchdb/test-connection");
            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }

        @Test
        public void testAllowsApply() throws Exception {
            when(uriInfo.getPath()).thenReturn("apply");
            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }

        @Test
        public void testAllowsAuthState() throws Exception {
            when(uriInfo.getPath()).thenReturn("auth/state");
            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }

        @Test
        public void testAllowsProbe() throws Exception {
            when(uriInfo.getPath()).thenReturn("couchdb/probe");
            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }

        @Test
        public void testAllowsMigrate() throws Exception {
            when(uriInfo.getPath()).thenReturn("couchdb/migrate");
            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }
    }

    // ================================================================
    // Setup Mode + missing/invalid token: 401 (except /state)
    // ================================================================

    @Nested
    class SetupModeNoToken {

        @BeforeEach
        public void setSetupMode() {
            when(probeService.isSetupRequired()).thenReturn(true);
            // No X-Setup-Token header
            when(requestContext.getHeaderString("X-Setup-Token")).thenReturn(null);
        }

        @Test
        public void testStateAllowedWithoutToken() throws Exception {
            when(uriInfo.getPath()).thenReturn("state");
            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }

        @Test
        public void testBlocksTestConnectionWithoutToken() throws Exception {
            when(uriInfo.getPath()).thenReturn("couchdb/test-connection");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 401));
        }

        @Test
        public void testBlocksApplyWithoutToken() throws Exception {
            when(uriInfo.getPath()).thenReturn("apply");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 401));
        }

        @Test
        public void testBlocksAuthStateWithoutToken() throws Exception {
            when(uriInfo.getPath()).thenReturn("auth/state");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 401));
        }

        @Test
        public void testBlocksProbeWithoutToken() throws Exception {
            when(uriInfo.getPath()).thenReturn("couchdb/probe");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 401));
        }
    }

    @Nested
    class SetupModeWrongToken {

        @BeforeEach
        public void setSetupMode() {
            when(probeService.isSetupRequired()).thenReturn(true);
            when(requestContext.getHeaderString("X-Setup-Token")).thenReturn("wrong-token");
        }

        @Test
        public void testBlocksWithWrongToken() throws Exception {
            when(uriInfo.getPath()).thenReturn("couchdb/test-connection");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 401));
        }

        @Test
        public void testStateStillAllowedWithWrongToken() throws Exception {
            when(uriInfo.getPath()).thenReturn("state");
            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }
    }

    // ================================================================
    // Reprobe transition: Normal → Setup requires token
    // ================================================================

    @Nested
    class ReprobeTransition {

        /**
         * Simulates: system was in Normal Mode, reprobe flipped to Setup Mode.
         * Requests with the correct (new) token should pass.
         */
        @Test
        public void testReprobeToSetupRequiresCorrectToken() throws Exception {
            // Phase 1: Normal Mode
            when(probeService.isSetupRequired()).thenReturn(false);
            when(uriInfo.getPath()).thenReturn("couchdb/test-connection");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 403));

            // Phase 2: reprobe flips to Setup Mode, clear invocations
            reset(requestContext);
            when(requestContext.getUriInfo()).thenReturn(uriInfo);
            when(probeService.isSetupRequired()).thenReturn(true);
            when(uriInfo.getPath()).thenReturn("couchdb/test-connection");
            when(requestContext.getHeaderString("X-Setup-Token")).thenReturn(SETUP_TOKEN);

            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }

        /**
         * After reprobe to Setup Mode, requests WITHOUT the token are rejected.
         */
        @Test
        public void testReprobeToSetupRejectsWithoutToken() throws Exception {
            when(probeService.isSetupRequired()).thenReturn(true);
            when(uriInfo.getPath()).thenReturn("couchdb/probe");
            when(requestContext.getHeaderString("X-Setup-Token")).thenReturn(null);

            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 401));
        }

        /**
         * After reprobe to Setup Mode, /state remains accessible without token.
         */
        @Test
        public void testReprobeToSetupStateStillOpen() throws Exception {
            when(probeService.isSetupRequired()).thenReturn(true);
            when(uriInfo.getPath()).thenReturn("state");
            when(requestContext.getHeaderString("X-Setup-Token")).thenReturn(null);

            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }
    }

    // ================================================================
    // Normal Mode (setupRequired=false): only /state allowed
    // ================================================================

    @Nested
    class NormalMode {

        @BeforeEach
        public void setNormalMode() {
            when(probeService.isSetupRequired()).thenReturn(false);
        }

        @Test
        public void testAllowsStatePath() throws Exception {
            when(uriInfo.getPath()).thenReturn("state");
            filter.filter(requestContext);
            verify(requestContext, never()).abortWith(any(Response.class));
        }

        @Test
        public void testBlocksCouchDbTestConnection() throws Exception {
            when(uriInfo.getPath()).thenReturn("couchdb/test-connection");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 403));
        }

        @Test
        public void testBlocksApply() throws Exception {
            when(uriInfo.getPath()).thenReturn("apply");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 403));
        }

        @Test
        public void testBlocksAuthState() throws Exception {
            when(uriInfo.getPath()).thenReturn("auth/state");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 403));
        }

        @Test
        public void testBlocksVectorState() throws Exception {
            when(uriInfo.getPath()).thenReturn("vector/state");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 403));
        }

        @Test
        public void testBlocksMigrate() throws Exception {
            when(uriInfo.getPath()).thenReturn("couchdb/migrate");
            filter.filter(requestContext);
            verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 403));
        }
    }
}
