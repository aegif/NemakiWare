package jp.aegif.nemaki.api.setup.resource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.model.CouchDbConnectionRequest;
import jp.aegif.nemaki.api.setup.model.MigrationRequest;
import jp.aegif.nemaki.init.CouchDbConnectionResult;
import jp.aegif.nemaki.init.RepositoryOverview;
import jp.aegif.nemaki.init.StartupProbeService;

/**
 * Tests for SetupCouchDbResource.
 * Validates SSRF hardening: port restriction, response sanitisation, cloud metadata blocking.
 */
public class SetupCouchDbResourceTest {

    private SetupCouchDbResource resource;
    private StartupProbeService probeService;

    @BeforeEach
    public void setUp() throws Exception {
        resource = new SetupCouchDbResource();
        probeService = mock(StartupProbeService.class);

        Field field = SetupCouchDbResource.class.getDeclaredField("startupProbeService");
        field.setAccessible(true);
        field.set(resource, probeService);
    }

    // ================================================================
    // test-connection
    // ================================================================

    @Nested
    class TestConnection {

        @Test
        public void testSuccess() {
            CouchDbConnectionResult result = new CouchDbConnectionResult();
            result.setReachable(true);
            result.setCouchDbVersion("3.3.3");
            result.setResponseTimeMs(42);

            when(probeService.testConnection("http://couchdb:5984", "admin", "pass"))
                    .thenReturn(result);

            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://couchdb:5984");
            req.setUsername("admin");
            req.setPassword("pass");

            Response resp = resource.testConnection(req);

            assertEquals(200, resp.getStatus());
            CouchDbConnectionResult body = (CouchDbConnectionResult) resp.getEntity();
            assertTrue(body.isReachable());
            assertEquals("3.3.3", body.getCouchDbVersion());
        }

        @Test
        public void testNullRequest() {
            assertEquals(400, resource.testConnection(null).getStatus());
        }

        @Test
        public void testEmptyUrl() {
            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("");
            assertEquals(400, resource.testConnection(req).getStatus());
        }

        @Test
        public void testNullUrl() {
            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl(null);
            assertEquals(400, resource.testConnection(req).getStatus());
        }

        // Setup Mode allows localhost on CouchDB port
        @Test
        public void testLocalhostAllowedOnCouchDbPort() {
            CouchDbConnectionResult result = new CouchDbConnectionResult();
            result.setReachable(true);
            when(probeService.testConnection("http://127.0.0.1:5984", "admin", "pass"))
                    .thenReturn(result);

            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://127.0.0.1:5984");
            req.setUsername("admin");
            req.setPassword("pass");

            assertEquals(200, resource.testConnection(req).getStatus());
        }

        // Setup Mode allows Docker private IPs on CouchDB port
        @Test
        public void testDockerPrivateIpAllowed() {
            CouchDbConnectionResult result = new CouchDbConnectionResult();
            result.setReachable(true);
            when(probeService.testConnection("http://172.17.0.2:5984", "admin", "pass"))
                    .thenReturn(result);

            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://172.17.0.2:5984");
            req.setUsername("admin");
            req.setPassword("pass");

            assertEquals(200, resource.testConnection(req).getStatus());
        }

        // Port restriction: non-service ports blocked even in Setup Mode
        @Test
        public void testNonServicePortBlocked() {
            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://internal-host:3306");
            req.setUsername("admin");
            req.setPassword("pass");

            Response resp = resource.testConnection(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("Port") || body.contains("not allowed"), body);
        }

        // Cloud metadata still blocked
        @Test
        public void testCloudMetadataBlocked() {
            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://169.254.169.254/latest/meta-data/");
            req.setUsername("admin");
            req.setPassword("pass");

            Response resp = resource.testConnection(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("metadata"), body);
        }

        // Response sanitisation: error details stripped
        @Test
        public void testErrorResponseSanitised() {
            CouchDbConnectionResult result = new CouchDbConnectionResult();
            result.setReachable(false);
            result.setErrorMessage("UnknownHostException: internal-db.corp.local");

            when(probeService.testConnection("http://couchdb:5984", "admin", "pass"))
                    .thenReturn(result);

            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://couchdb:5984");
            req.setUsername("admin");
            req.setPassword("pass");

            Response resp = resource.testConnection(req);
            assertEquals(200, resp.getStatus());
            CouchDbConnectionResult body = (CouchDbConnectionResult) resp.getEntity();
            assertFalse(body.isReachable());
            assertEquals("Connection failed", body.getErrorMessage(),
                    "Error message should be sanitised, not leak internal details");
        }

        @Test
        public void testDefaultCredentials() {
            CouchDbConnectionResult result = new CouchDbConnectionResult();
            result.setReachable(false);
            result.setErrorMessage("timeout");
            when(probeService.testConnection("http://couchdb:5984", "admin", ""))
                    .thenReturn(result);

            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://couchdb:5984");

            Response resp = resource.testConnection(req);
            assertEquals(200, resp.getStatus());
            verify(probeService).testConnection("http://couchdb:5984", "admin", "");
        }

        @Test
        public void testNullProbeService() throws Exception {
            Field field = SetupCouchDbResource.class.getDeclaredField("startupProbeService");
            field.setAccessible(true);
            field.set(resource, null);

            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://couchdb:5984");

            assertEquals(500, resource.testConnection(req).getStatus());
        }
    }

    // ================================================================
    // probe
    // ================================================================

    @Nested
    class Probe {

        @Test
        public void testSuccess() {
            RepositoryOverview ov = new RepositoryOverview();
            ov.setRepositoryId("bedroom");
            ov.setJudgment("current");

            when(probeService.probeRepositories("http://couchdb:5984", "admin", "pass"))
                    .thenReturn(List.of(ov));

            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://couchdb:5984");
            req.setUsername("admin");
            req.setPassword("pass");

            Response resp = resource.probe(req);
            assertEquals(200, resp.getStatus());
            @SuppressWarnings("unchecked")
            List<RepositoryOverview> repos = (List<RepositoryOverview>) resp.getEntity();
            assertEquals(1, repos.size());
            assertEquals("bedroom", repos.get(0).getRepositoryId());
        }

        @Test
        public void testNullRequest() {
            assertEquals(400, resource.probe(null).getStatus());
        }

        @Test
        public void testCloudMetadataBlocked() {
            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://169.254.169.254/latest/meta-data/");
            assertEquals(400, resource.probe(req).getStatus());
        }

        @Test
        public void testLocalhostAllowed() {
            when(probeService.probeRepositories("http://localhost:5984", "admin", ""))
                    .thenReturn(List.of());

            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://localhost:5984");

            assertEquals(200, resource.probe(req).getStatus());
        }

        @Test
        public void testNonServicePortBlocked() {
            CouchDbConnectionRequest req = new CouchDbConnectionRequest();
            req.setUrl("http://internal:9200");  // Elasticsearch port
            assertEquals(400, resource.probe(req).getStatus());
        }
    }

    // ================================================================
    // migrate
    // ================================================================

    @Test
    public void testMigrateReturns501() {
        MigrationRequest req = new MigrationRequest();
        req.setConfirmed(true);
        req.setRepositoryIds(List.of("bedroom"));
        assertEquals(501, resource.migrate(req).getStatus());
    }

    @Test
    public void testMigrateNotConfirmed() {
        MigrationRequest req = new MigrationRequest();
        req.setConfirmed(false);
        assertEquals(400, resource.migrate(req).getStatus());
    }

    @Test
    public void testMigrateNullRequest() {
        assertEquals(400, resource.migrate(null).getStatus());
    }
}
