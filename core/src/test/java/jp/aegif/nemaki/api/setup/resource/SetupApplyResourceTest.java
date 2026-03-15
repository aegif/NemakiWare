package jp.aegif.nemaki.api.setup.resource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.model.CouchDbConnectionRequest;
import jp.aegif.nemaki.api.setup.model.SetupApplyRequest;
import jp.aegif.nemaki.api.setup.model.AuthConfigRequest;
import jp.aegif.nemaki.api.setup.model.VectorTestRequest;
import jp.aegif.nemaki.init.CMISPostInitializer;
import jp.aegif.nemaki.init.CouchDbConnectionResult;
import jp.aegif.nemaki.init.DatabasePreInitializer;
import jp.aegif.nemaki.init.StartupProbeService;
import jp.aegif.nemaki.patch.PatchService;

/**
 * Tests for SetupApplyResource.
 * Verifies validation, error handling, deferred initialization,
 * and reprobe/markSetupComplete lifecycle.
 */
public class SetupApplyResourceTest {

    private SetupApplyResource resource;
    private StartupProbeService probeService;
    private CMISPostInitializer cmisPostInitializer;
    private PatchService patchService;

    @BeforeEach
    public void setUp() throws Exception {
        resource = new SetupApplyResource();
        probeService = mock(StartupProbeService.class);
        cmisPostInitializer = mock(CMISPostInitializer.class);
        patchService = mock(PatchService.class);

        setField("startupProbeService", probeService);
        setField("cmisPostInitializer", cmisPostInitializer);
        setField("patchService", patchService);

        // Default: deferred init succeeds
        when(cmisPostInitializer.executePatches()).thenReturn(true);
        when(patchService.executePatchService()).thenReturn(true);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = SetupApplyResource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(resource, value);
    }

    private SetupApplyRequest validRequest() {
        SetupApplyRequest req = new SetupApplyRequest();
        CouchDbConnectionRequest couch = new CouchDbConnectionRequest();
        couch.setUrl("http://couchdb:5984");
        couch.setUsername("admin");
        couch.setPassword("password");
        req.setCouchdb(couch);

        CouchDbConnectionResult reachable = new CouchDbConnectionResult();
        reachable.setReachable(true);
        reachable.setCouchDbVersion("3.3.3");
        when(probeService.testConnection("http://couchdb:5984", "admin", "password"))
                .thenReturn(reachable);
        return req;
    }

    @Test
    public void testApplyNullRequest() {
        Response resp = resource.apply(null);
        assertEquals(400, resp.getStatus());
        String body = (String) resp.getEntity();
        assertTrue(body.contains("CouchDB configuration is required"), body);
    }

    @Test
    public void testApplyMissingCouchdb() {
        SetupApplyRequest req = new SetupApplyRequest();
        Response resp = resource.apply(req);
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void testApplyCouchdbUnreachable() {
        SetupApplyRequest req = new SetupApplyRequest();
        CouchDbConnectionRequest couch = new CouchDbConnectionRequest();
        couch.setUrl("http://nonexistent:5984");
        couch.setUsername("admin");
        couch.setPassword("password");
        req.setCouchdb(couch);

        CouchDbConnectionResult unreachable = new CouchDbConnectionResult();
        unreachable.setReachable(false);
        unreachable.setErrorMessage("Connection refused");
        when(probeService.testConnection("http://nonexistent:5984", "admin", "password"))
                .thenReturn(unreachable);

        Response resp = resource.apply(req);
        assertEquals(400, resp.getStatus());
        verify(probeService, never()).markSetupComplete();
        verify(probeService, never()).reprobeState();
    }

    @Test
    public void testApplyNullProbeService() throws Exception {
        setField("startupProbeService", null);

        SetupApplyRequest req = new SetupApplyRequest();
        Response resp = resource.apply(req);

        assertEquals(500, resp.getStatus());
    }

    /**
     * Success path (no warnings): reprobeState → state check → deferred init → markSetupComplete.
     * reprobe() must NOT be called (it would flip setupRequired prematurely).
     */
    @Test
    public void testApplySuccessCallsReprobeStateAndMarkSetupComplete() {
        SetupApplyRequest req = validRequest();
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);

        Response resp = resource.apply(req);
        assertEquals(200, resp.getStatus());

        InOrder inOrder = inOrder(probeService, cmisPostInitializer, patchService);
        inOrder.verify(probeService).reprobeState();
        inOrder.verify(probeService).getCurrentState();
        inOrder.verify(cmisPostInitializer).executePatches();
        inOrder.verify(patchService).executePatchService();
        inOrder.verify(probeService).markSetupComplete();

        verify(probeService, never()).reprobe();

        String body = (String) resp.getEntity();
        assertTrue(body.contains("\"setupComplete\":true"), body);
    }

    /**
     * When deferred Phase 2 fails, markSetupComplete must NOT be called
     * and the response must include warnings.
     */
    @Test
    public void testApplyDeferredPhase2FailureBlocksSetupComplete() {
        SetupApplyRequest req = validRequest();
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);
        when(cmisPostInitializer.executePatches()).thenReturn(false);

        Response resp = resource.apply(req);
        assertEquals(200, resp.getStatus());

        String body = (String) resp.getEntity();
        assertTrue(body.contains("\"setupComplete\":false"), body);
        assertTrue(body.contains("Phase 2"), body);

        verify(probeService, never()).markSetupComplete();
    }

    /**
     * Warnings path (e.g. auth config persistence fails): neither reprobeState()
     * nor markSetupComplete() must be called, preserving Setup Mode for
     * the /apply/mark-complete endpoint.
     */
    @Test
    public void testApplyWithWarningsDoesNotCallReprobeOrMarkComplete() {
        SetupApplyRequest req = validRequest();

        // Add auth config that will trigger CouchDB write → fails because
        // there is no CouchDB running in unit tests, generating a warning.
        AuthConfigRequest auth = new AuthConfigRequest();
        auth.setPasswordEnabled(true);
        req.setAuth(auth);

        Response resp = resource.apply(req);
        assertEquals(200, resp.getStatus());

        String body = (String) resp.getEntity();
        assertTrue(body.contains("\"setupComplete\":false"), body);
        assertTrue(body.contains("\"warnings\""), body);

        verify(probeService, never()).reprobeState();
        verify(probeService, never()).reprobe();
        verify(probeService, never()).markSetupComplete();
    }

    /**
     * POST /apply/mark-complete: reprobeState → state check → deferred init → markSetupComplete.
     * reprobe() must NOT be called (it would flip setupRequired prematurely).
     */
    @Test
    public void testMarkCompleteCallsReprobeStateAndMarkSetupComplete() {
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);

        Response resp = resource.markComplete();
        assertEquals(200, resp.getStatus());

        InOrder inOrder = inOrder(probeService, cmisPostInitializer, patchService);
        inOrder.verify(probeService).reprobeState();
        inOrder.verify(probeService).getCurrentState();
        inOrder.verify(cmisPostInitializer).executePatches();
        inOrder.verify(patchService).executePatchService();
        inOrder.verify(probeService).markSetupComplete();

        verify(probeService, never()).reprobe();
    }

    /**
     * POST /apply/mark-complete: when deferred init fails, returns 500 and does NOT markSetupComplete.
     * Setup Mode must remain open (reprobe() never called, only reprobeState()).
     */
    @Test
    public void testMarkCompleteDeferredFailureReturns500AndKeepsSetupMode() {
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);
        when(patchService.executePatchService()).thenReturn(false);

        Response resp = resource.markComplete();
        assertEquals(500, resp.getStatus());

        String body = (String) resp.getEntity();
        assertTrue(body.contains("Phase 3"), body);

        verify(probeService, never()).markSetupComplete();
        // reprobe() must not be called — it would flip setupRequired=false
        verify(probeService, never()).reprobe();
        // reprobeState() IS called (state-only check, doesn't flip setupRequired)
        verify(probeService).reprobeState();
    }

    /**
     * POST /apply/mark-complete with null probeService returns 500.
     */
    @Test
    public void testMarkCompleteNullProbeService() throws Exception {
        setField("startupProbeService", null);

        Response resp = resource.markComplete();
        assertEquals(500, resp.getStatus());
    }

    /**
     * Admin password shorter than 8 characters returns 400.
     */
    @Test
    public void testApplyAdminPasswordTooShort() {
        SetupApplyRequest req = validRequest();
        req.setAdminPassword("short");

        Response resp = resource.apply(req);
        assertEquals(400, resp.getStatus());
        String body = (String) resp.getEntity();
        assertTrue(body.contains("at least 8 characters"), body);
    }

    /**
     * Phase 3 fails on first /apply, then succeeds on /apply/mark-complete retry.
     * Verifies that re-execution actually happens (not cached false-success).
     */
    @Test
    public void testMarkCompleteRetryAfterPhase3Failure() {
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);

        // First call: Phase 3 fails
        when(patchService.executePatchService()).thenReturn(false);

        Response resp1 = resource.markComplete();
        assertEquals(500, resp1.getStatus());
        verify(probeService, never()).markSetupComplete();

        // Second call: Phase 3 succeeds (user fixed the issue)
        reset(probeService, cmisPostInitializer, patchService);
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);
        when(cmisPostInitializer.executePatches()).thenReturn(true);
        when(patchService.executePatchService()).thenReturn(true);

        Response resp2 = resource.markComplete();
        assertEquals(200, resp2.getStatus());
        verify(probeService).markSetupComplete();
    }

    /**
     * Phase 2 fails on first /apply, then succeeds on /apply/mark-complete retry.
     */
    @Test
    public void testMarkCompleteRetryAfterPhase2Failure() {
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);

        // First call: Phase 2 fails
        when(cmisPostInitializer.executePatches()).thenReturn(false);

        Response resp1 = resource.markComplete();
        assertEquals(500, resp1.getStatus());
        verify(probeService, never()).markSetupComplete();

        // Second call: Phase 2 succeeds
        reset(probeService, cmisPostInitializer, patchService);
        when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);
        when(cmisPostInitializer.executePatches()).thenReturn(true);
        when(patchService.executePatchService()).thenReturn(true);

        Response resp2 = resource.markComplete();
        assertEquals(200, resp2.getStatus());
        verify(probeService).markSetupComplete();
    }

    // ================================================================
    // Vector validation (P2): reject incomplete payloads in one-shot /apply
    // ================================================================

    @Nested
    @DisplayName("One-shot /apply: vector validation")
    class VectorValidation {

        @Test
        @DisplayName("TEI with null URL → 400")
        public void testTeiNullUrl() {
            SetupApplyRequest req = validRequest();
            VectorTestRequest v = new VectorTestRequest();
            v.setType("tei");
            v.setUrl(null);
            req.setVector(v);

            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("url is required"), body);
        }

        @Test
        @DisplayName("TEI with empty URL → 400")
        public void testTeiEmptyUrl() {
            SetupApplyRequest req = validRequest();
            VectorTestRequest v = new VectorTestRequest();
            v.setType("tei");
            v.setUrl("  ");
            req.setVector(v);

            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
        }

        @Test
        @DisplayName("TEI with SSRF URL → 400")
        public void testTeiSsrfUrl() {
            SetupApplyRequest req = validRequest();
            VectorTestRequest v = new VectorTestRequest();
            v.setType("tei");
            v.setUrl("http://169.254.169.254/latest/meta-data/");
            req.setVector(v);

            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
        }

        @Test
        @DisplayName("Bedrock with null region → 400")
        public void testBedrockNullRegion() {
            SetupApplyRequest req = validRequest();
            VectorTestRequest v = new VectorTestRequest();
            v.setType("bedrock");
            v.setRegion(null);
            req.setVector(v);

            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("region is required"), body);
        }

        @Test
        @DisplayName("Bedrock with empty region → 400")
        public void testBedrockEmptyRegion() {
            SetupApplyRequest req = validRequest();
            VectorTestRequest v = new VectorTestRequest();
            v.setType("bedrock");
            v.setRegion("  ");
            req.setVector(v);

            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
        }
    }

    // ================================================================
    // Vector credential lifecycle (P1): provider-switch cleanup in one-shot /apply
    // ================================================================

    @Nested
    @DisplayName("One-shot /apply: Bedrock credential lifecycle")
    class VectorCredentialLifecycle {

        private static final String[] VECTOR_PROPS = {
                "rag.enabled", "rag.embedding.provider",
                "rag.tei.url",
                "rag.bedrock.region", "rag.bedrock.model.id",
                "rag.bedrock.access.key.id", "rag.bedrock.secret.access.key"
        };

        @AfterEach
        public void cleanupSystemProperties() {
            for (String prop : VECTOR_PROPS) {
                System.clearProperty(prop);
            }
        }

        @Test
        @DisplayName("Bedrock → TEI switch clears rag.bedrock.* credentials")
        public void testBedrockToTei_clearsBedrockCredentials() {
            System.setProperty("rag.bedrock.access.key.id", "AKIAIOSFODNN7EXAMPLE");
            System.setProperty("rag.bedrock.secret.access.key", "wJalrXUtnFEMI/K7MDENG");

            SetupApplyRequest req = validRequest();
            VectorTestRequest v = new VectorTestRequest();
            v.setType("tei");
            v.setUrl("http://tei:80");
            req.setVector(v);

            when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);

            try (MockedStatic<CouchDbConfigWriter> mocked = mockStatic(CouchDbConfigWriter.class)) {
                mocked.when(() -> CouchDbConfigWriter.putConfigValue(anyString(), anyString(), anyString(), anyString()))
                        .then(invocation -> null);
                mocked.when(() -> CouchDbConfigWriter.basicAuth(anyString(), anyString()))
                        .thenCallRealMethod();
                mocked.when(() -> CouchDbConfigWriter.escapeJson(anyString()))
                        .thenCallRealMethod();

                Response resp = resource.apply(req);
                assertEquals(200, resp.getStatus());
            }

            assertNull(System.getProperty("rag.bedrock.access.key.id"),
                    "rag.bedrock.access.key.id should be cleared after switching to TEI");
            assertNull(System.getProperty("rag.bedrock.secret.access.key"),
                    "rag.bedrock.secret.access.key should be cleared after switching to TEI");
            assertEquals("tei", System.getProperty("rag.embedding.provider"));
        }

        @Test
        @DisplayName("Bedrock → none switch clears rag.bedrock.* credentials")
        public void testBedrockToNone_clearsBedrockCredentials() {
            System.setProperty("rag.bedrock.access.key.id", "AKIAIOSFODNN7EXAMPLE");
            System.setProperty("rag.bedrock.secret.access.key", "wJalrXUtnFEMI/K7MDENG");

            SetupApplyRequest req = validRequest();
            VectorTestRequest v = new VectorTestRequest();
            v.setType("none");
            req.setVector(v);

            when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);

            try (MockedStatic<CouchDbConfigWriter> mocked = mockStatic(CouchDbConfigWriter.class)) {
                mocked.when(() -> CouchDbConfigWriter.putConfigValue(anyString(), anyString(), anyString(), anyString()))
                        .then(invocation -> null);
                mocked.when(() -> CouchDbConfigWriter.basicAuth(anyString(), anyString()))
                        .thenCallRealMethod();
                mocked.when(() -> CouchDbConfigWriter.escapeJson(anyString()))
                        .thenCallRealMethod();

                Response resp = resource.apply(req);
                assertEquals(200, resp.getStatus());
            }

            assertNull(System.getProperty("rag.bedrock.access.key.id"),
                    "rag.bedrock.access.key.id should be cleared after disabling vector search");
            assertNull(System.getProperty("rag.bedrock.secret.access.key"),
                    "rag.bedrock.secret.access.key should be cleared after disabling vector search");
            assertEquals("false", System.getProperty("rag.enabled"));
        }

        @Test
        @DisplayName("Bedrock with empty credentials clears rag.bedrock.* (IAM fallback)")
        public void testBedrockEmptyCredentials_clearsBedrockCredentials() {
            System.setProperty("rag.bedrock.access.key.id", "OLD_KEY");
            System.setProperty("rag.bedrock.secret.access.key", "OLD_SECRET");

            SetupApplyRequest req = validRequest();
            VectorTestRequest v = new VectorTestRequest();
            v.setType("bedrock");
            v.setRegion("us-east-1");
            v.setModelId("amazon.titan-embed-text-v2:0");
            v.setAccessKeyId("");
            v.setSecretAccessKey("");
            req.setVector(v);

            when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);

            try (MockedStatic<CouchDbConfigWriter> mocked = mockStatic(CouchDbConfigWriter.class)) {
                mocked.when(() -> CouchDbConfigWriter.putConfigValue(anyString(), anyString(), anyString(), anyString()))
                        .then(invocation -> null);
                mocked.when(() -> CouchDbConfigWriter.basicAuth(anyString(), anyString()))
                        .thenCallRealMethod();
                mocked.when(() -> CouchDbConfigWriter.escapeJson(anyString()))
                        .thenCallRealMethod();

                Response resp = resource.apply(req);
                assertEquals(200, resp.getStatus());
            }

            assertNull(System.getProperty("rag.bedrock.access.key.id"),
                    "rag.bedrock.access.key.id should be cleared for IAM role fallback");
            assertNull(System.getProperty("rag.bedrock.secret.access.key"),
                    "rag.bedrock.secret.access.key should be cleared for IAM role fallback");
            assertEquals("bedrock", System.getProperty("rag.embedding.provider"));
            assertEquals("us-east-1", System.getProperty("rag.bedrock.region"));
        }

        @Test
        @DisplayName("Bedrock with explicit credentials sets only rag.bedrock.* (not aws.*)")
        public void testBedrockWithCredentials_setsOnlyRagProperties() {
            SetupApplyRequest req = validRequest();
            VectorTestRequest v = new VectorTestRequest();
            v.setType("bedrock");
            v.setRegion("ap-northeast-1");
            v.setModelId("amazon.titan-embed-text-v2:0");
            v.setAccessKeyId("AKIAEXAMPLE");
            v.setSecretAccessKey("SECRET123");
            req.setVector(v);

            when(probeService.getCurrentState()).thenReturn(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);

            try (MockedStatic<CouchDbConfigWriter> mocked = mockStatic(CouchDbConfigWriter.class)) {
                mocked.when(() -> CouchDbConfigWriter.putConfigValue(anyString(), anyString(), anyString(), anyString()))
                        .then(invocation -> null);
                mocked.when(() -> CouchDbConfigWriter.basicAuth(anyString(), anyString()))
                        .thenCallRealMethod();
                mocked.when(() -> CouchDbConfigWriter.escapeJson(anyString()))
                        .thenCallRealMethod();

                Response resp = resource.apply(req);
                assertEquals(200, resp.getStatus());
            }

            assertEquals("AKIAEXAMPLE", System.getProperty("rag.bedrock.access.key.id"));
            assertEquals("SECRET123", System.getProperty("rag.bedrock.secret.access.key"));
            assertEquals("ap-northeast-1", System.getProperty("rag.bedrock.region"));
            // aws.* must NOT be set — BedrockEmbeddingService reads rag.bedrock.* directly
            assertNull(System.getProperty("aws.accessKeyId"),
                    "aws.accessKeyId must not be set — it is JVM-global and affects other AWS clients");
            assertNull(System.getProperty("aws.secretAccessKey"),
                    "aws.secretAccessKey must not be set — it is JVM-global and affects other AWS clients");
        }
    }
}
