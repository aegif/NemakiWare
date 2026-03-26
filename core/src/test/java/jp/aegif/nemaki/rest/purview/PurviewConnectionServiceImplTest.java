package jp.aegif.nemaki.rest.purview;

import jp.aegif.nemaki.rest.purview.client.PurviewApiClient;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewProbeResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PurviewConnectionServiceImplTest {

    private PurviewConfig config;
    private PurviewApiClient apiClient;
    private PurviewConnectionServiceImpl service;

    @BeforeEach
    public void setUp() {
        config = mock(PurviewConfig.class);
        apiClient = mock(PurviewApiClient.class);
        service = new PurviewConnectionServiceImpl(config, apiClient);
    }

    @Test
    public void testFallsBackToAlternateAtlasBasePathWhenConfiguredPathReturns404() throws Exception {
        when(config.isEnabled()).thenReturn(false);
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
        when(config.isEnabled()).thenReturn(false);
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

        // First path returns 403 (non-404), so no fallback attempted — breaks immediately
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
        when(config.isEnabled()).thenReturn(false);
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

    @Test
    public void testBasicAuthReturnsFailureWhenUsernameIsMissing() throws Exception {
        when(config.isEnabled()).thenReturn(true);
        when(config.isBasicAuth()).thenReturn(true);
        when(config.getAuthType()).thenReturn("basic");
        when(config.getEndpoint()).thenReturn("http://localhost:21000");
        when(config.getAtlasBasePath()).thenReturn("api/atlas/v2");
        when(config.getBasicUsername()).thenReturn("");
        when(config.getBasicPassword()).thenReturn("admin");

        PurviewConnectionStatus status = service.testConnection();

        assertFalse(status.isConnected());
        assertTrue(status.getMessage().contains("basicUsername"));
        verifyNoInteractions(apiClient);
    }

    @Test
    public void testBasicAuthReturnsFailureWhenPasswordIsMissing() throws Exception {
        when(config.isEnabled()).thenReturn(true);
        when(config.isBasicAuth()).thenReturn(true);
        when(config.getAuthType()).thenReturn("basic");
        when(config.getEndpoint()).thenReturn("http://localhost:21000");
        when(config.getAtlasBasePath()).thenReturn("api/atlas/v2");
        when(config.getBasicUsername()).thenReturn("admin");
        when(config.getBasicPassword()).thenReturn("");

        PurviewConnectionStatus status = service.testConnection();

        assertFalse(status.isConnected());
        assertTrue(status.getMessage().contains("basicPassword"));
        verifyNoInteractions(apiClient);
    }

    @Test
    public void testBasicAuthDoesNotRequireOAuth2Credentials() throws Exception {
        when(config.isEnabled()).thenReturn(true);
        when(config.isBasicAuth()).thenReturn(true);
        when(config.getAuthType()).thenReturn("basic");
        when(config.getEndpoint()).thenReturn("http://localhost:21000");
        when(config.getAtlasBasePath()).thenReturn("api/atlas/v2");
        when(config.getBasicUsername()).thenReturn("admin");
        when(config.getBasicPassword()).thenReturn("admin");
        when(config.getTenantId()).thenReturn("");
        when(config.getClientId()).thenReturn("");
        when(config.getClientSecret()).thenReturn("");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);

        when(apiClient.probeConnection(any()))
                .thenReturn(PurviewProbeResult.success(200, "OK"));

        PurviewConnectionStatus status = service.testConnection();

        assertTrue(status.isConnected());
    }

    @Test
    public void testBasicAuthPassesAuthTypeInRequest() throws Exception {
        when(config.isEnabled()).thenReturn(true);
        when(config.isBasicAuth()).thenReturn(true);
        when(config.getAuthType()).thenReturn("basic");
        when(config.getEndpoint()).thenReturn("http://localhost:21000");
        when(config.getAtlasBasePath()).thenReturn("api/atlas/v2");
        when(config.getBasicUsername()).thenReturn("admin");
        when(config.getBasicPassword()).thenReturn("admin");
        when(config.getTenantId()).thenReturn("");
        when(config.getClientId()).thenReturn("");
        when(config.getClientSecret()).thenReturn("");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);

        when(apiClient.probeConnection(argThat(request ->
                request != null && request.isBasicAuth())))
                .thenReturn(PurviewProbeResult.success(200, "OK"));

        PurviewConnectionStatus status = service.testConnection();

        assertTrue(status.isConnected());
    }

    private void configureValidConfig() {
        when(config.isEnabled()).thenReturn(false);
        when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-123");
        when(config.getClientId()).thenReturn("client-123");
        when(config.getClientSecret()).thenReturn("secret-123");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);
    }
}
