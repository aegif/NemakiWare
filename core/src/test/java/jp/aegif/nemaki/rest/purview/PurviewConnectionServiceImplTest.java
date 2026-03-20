package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
