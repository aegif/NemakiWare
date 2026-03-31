package jp.aegif.nemaki.rest.purview;

import jp.aegif.nemaki.rest.purview.client.PurviewApiClient;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewProbeResult;
import jp.aegif.nemaki.rest.purview.journal.AtlasConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class PurviewConnectionServiceImplTest {

    private PurviewConfig config;
    private AtlasConfig atlasConfig;
    private PurviewApiClient apiClient;
    private PurviewConnectionServiceImpl service;

    @BeforeEach
    public void setUp() {
        config = mock(PurviewConfig.class);
        atlasConfig = mock(AtlasConfig.class);
        apiClient = mock(PurviewApiClient.class);
        service = new PurviewConnectionServiceImpl(config, atlasConfig, apiClient);
    }

    @Nested
    class PurviewTests {

        @Test
        public void testFallsBackToAlternateAtlasBasePathWhenConfiguredPathReturns404() throws Exception {
            when(config.isEnabled()).thenReturn(true);
            when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
            when(config.getAtlasBasePath()).thenReturn("catalog/api/atlas/v2");
            when(config.getTenantId()).thenReturn("tenant-123");
            when(config.getClientId()).thenReturn("client-123");
            when(config.getClientSecret()).thenReturn("secret-123");
            when(config.getConnectTimeoutMs()).thenReturn(5000);
            when(config.getReadTimeoutMs()).thenReturn(30000);

            when(apiClient.probeConnection(argThat(request ->
                    request != null && "catalog/api/atlas/v2".equals(request.getAtlasBasePath()))))
                    .thenReturn(PurviewProbeResult.failure(404, "Not Found"));
            when(apiClient.probeConnection(argThat(request ->
                    request != null && "datamap/api/atlas/v2".equals(request.getAtlasBasePath()))))
                    .thenReturn(PurviewProbeResult.success(200, "OK"));

            PurviewConnectionStatus status = service.testConnection();

            assertTrue(status.isConnected());
            assertEquals("datamap/api/atlas/v2", status.getAtlasBasePath());
            assertEquals("https://example-account.purview.azure.com", status.getEndpoint());
            assertTrue(status.getMessage().contains("datamap/api/atlas/v2"));
        }

        @Test
        public void testReturnsFailureWhenRequiredConfigurationIsMissing() throws Exception {
            when(config.isEnabled()).thenReturn(true);
            when(config.getEndpoint()).thenReturn(" ");
            when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
            when(config.getTenantId()).thenReturn("");
            when(config.getClientId()).thenReturn("client-123");
            when(config.getClientSecret()).thenReturn("");
            when(config.getConnectTimeoutMs()).thenReturn(5000);
            when(config.getReadTimeoutMs()).thenReturn(30000);

            PurviewConnectionStatus status = service.testConnection();

            assertFalse(status.isConnected());
            assertTrue(status.getMessage().contains("endpoint"));
            assertTrue(status.getMessage().contains("tenantId"));
            assertTrue(status.getMessage().contains("clientSecret"));
            verifyNoInteractions(apiClient);
        }

        @Test
        public void testReturnsSuccessOnFirstCandidatePathWithoutFallback() throws Exception {
            configureValidConfig();
            when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");

            when(apiClient.probeConnection(argThat(request ->
                    request != null && "datamap/api/atlas/v2".equals(request.getAtlasBasePath()))))
                    .thenReturn(PurviewProbeResult.success(200, "OK"));

            PurviewConnectionStatus status = service.testConnection();

            assertTrue(status.isConnected());
            assertEquals("datamap/api/atlas/v2", status.getAtlasBasePath());
            assertTrue(status.getMessage().contains("datamap/api/atlas/v2"));
        }

        @Test
        public void testReturnsFailureWhenPurviewClientExceptionIsThrown() throws Exception {
            configureValidConfig();
            when(apiClient.probeConnection(any()))
                    .thenThrow(new PurviewClientException("Connection timed out"));

            PurviewConnectionStatus status = service.testConnection();

            assertFalse(status.isConnected());
            assertTrue(status.getMessage().contains("Connection timed out"));
        }

        @Test
        public void testReturnsFailureWhenFirstPathReturnsNon404Error() throws Exception {
            configureValidConfig();
            when(config.getAtlasBasePath()).thenReturn("catalog/api/atlas/v2");

            when(apiClient.probeConnection(argThat(request ->
                    request != null && "catalog/api/atlas/v2".equals(request.getAtlasBasePath()))))
                    .thenReturn(PurviewProbeResult.failure(403, "Forbidden"));

            PurviewConnectionStatus status = service.testConnection();

            assertFalse(status.isConnected());
            assertTrue(status.getMessage().contains("Forbidden"));
        }

        @Test
        public void testFeatureEnabledFlagIsPreservedInStatus() throws Exception {
            configureValidConfig();
            when(config.isEnabled()).thenReturn(true);

            when(apiClient.probeConnection(any()))
                    .thenReturn(PurviewProbeResult.success(200, "OK"));

            PurviewConnectionStatus status = service.testConnection();

            assertTrue(status.isConnected());
            assertTrue(status.isFeatureEnabled());
        }

        @Test
        public void testReturnsFailureWhenOnlyClientIdIsMissing() throws Exception {
            when(config.isEnabled()).thenReturn(true);
            when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
            when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
            when(config.getTenantId()).thenReturn("tenant-123");
            when(config.getClientId()).thenReturn("");
            when(config.getClientSecret()).thenReturn("secret-123");
            when(config.getConnectTimeoutMs()).thenReturn(5000);
            when(config.getReadTimeoutMs()).thenReturn(30000);

            PurviewConnectionStatus status = service.testConnection();

            assertFalse(status.isConnected());
            assertTrue(status.getMessage().contains("clientId"));
            assertFalse(status.getMessage().contains("endpoint"));
            verifyNoInteractions(apiClient);
        }

        private void configureValidConfig() {
            when(config.isEnabled()).thenReturn(true);
            when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
            when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
            when(config.getTenantId()).thenReturn("tenant-123");
            when(config.getClientId()).thenReturn("client-123");
            when(config.getClientSecret()).thenReturn("secret-123");
            when(config.getConnectTimeoutMs()).thenReturn(5000);
            when(config.getReadTimeoutMs()).thenReturn(30000);
        }
    }

    @Nested
    class AtlasTests {

        @Test
        public void testAtlasReturnsDisabledWhenNotEnabled() {
            when(atlasConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.getEndpoint()).thenReturn("http://localhost:21000");
            when(atlasConfig.getUsername()).thenReturn("admin");
            when(atlasConfig.getPassword()).thenReturn("admin");

            PurviewConnectionStatus status = service.testAtlasConnection(null);

            assertFalse(status.isConnected());
            assertFalse(status.isFeatureEnabled());
            assertTrue(status.getMessage().contains("disabled"));
        }

        @Test
        public void testAtlasReturnsFailureWhenUsernameIsMissing() {
            when(atlasConfig.isEnabled()).thenReturn(true);
            when(atlasConfig.getEndpoint()).thenReturn("http://localhost:21000");
            when(atlasConfig.getUsername()).thenReturn("");
            when(atlasConfig.getPassword()).thenReturn("admin");

            PurviewConnectionStatus status = service.testAtlasConnection(null);

            assertFalse(status.isConnected());
            assertTrue(status.getMessage().contains("username"));
            verifyNoInteractions(apiClient);
        }

        @Test
        public void testAtlasReturnsFailureWhenPasswordIsMissing() {
            when(atlasConfig.isEnabled()).thenReturn(true);
            when(atlasConfig.getEndpoint()).thenReturn("http://localhost:21000");
            when(atlasConfig.getUsername()).thenReturn("admin");
            when(atlasConfig.getPassword()).thenReturn("");

            PurviewConnectionStatus status = service.testAtlasConnection(null);

            assertFalse(status.isConnected());
            assertTrue(status.getMessage().contains("password"));
            verifyNoInteractions(apiClient);
        }

        @Test
        public void testAtlasConnectionSucceeds() throws Exception {
            when(atlasConfig.isEnabled()).thenReturn(true);
            when(atlasConfig.getEndpoint()).thenReturn("http://localhost:21000");
            when(atlasConfig.getUsername()).thenReturn("admin");
            when(atlasConfig.getPassword()).thenReturn("admin");
            when(atlasConfig.getConnectTimeoutMs()).thenReturn(5000);
            when(atlasConfig.getReadTimeoutMs()).thenReturn(30000);

            when(apiClient.probeConnection(argThat(request ->
                    request != null && request.isBasicAuth())))
                    .thenReturn(PurviewProbeResult.success(200, "OK"));

            PurviewConnectionStatus status = service.testAtlasConnection(null);

            assertTrue(status.isConnected());
            assertTrue(status.isFeatureEnabled());
        }

        @Test
        public void testAtlasConnectionWithFormOverrides() throws Exception {
            when(atlasConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.getEndpoint()).thenReturn("http://old:21000");
            when(atlasConfig.getUsername()).thenReturn("old-user");
            when(atlasConfig.getPassword()).thenReturn("old-pass");
            when(atlasConfig.getConnectTimeoutMs()).thenReturn(5000);
            when(atlasConfig.getReadTimeoutMs()).thenReturn(30000);

            when(apiClient.probeConnection(any()))
                    .thenReturn(PurviewProbeResult.success(200, "OK"));

            PurviewConnectionStatus status = service.testAtlasConnection(Map.of(
                    "atlas.enabled", "true",
                    "atlas.endpoint", "http://new:21000",
                    "atlas.username", "new-user",
                    "atlas.password", "new-pass"));

            assertTrue(status.isConnected());
            assertTrue(status.isFeatureEnabled());
        }
    }
}
