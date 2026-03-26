package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PurviewConnectionRequestTest {

    @Test
    public void testBackwardCompatibleConstructorDefaultsToOAuth2() {
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "https://example.purview.azure.com",
                "datamap/api/atlas/v2",
                "tenant-123",
                "client-123",
                "secret-123",
                5000,
                30000);

        assertEquals("oauth2", request.getAuthType());
        assertFalse(request.isBasicAuth());
        assertEquals("", request.getBasicUsername());
        assertEquals("", request.getBasicPassword());
    }

    @Test
    public void testFullConstructorWithBasicAuth() {
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "http://localhost:21000",
                "api/atlas/v2",
                "basic",
                "",
                "",
                "",
                "admin",
                "admin",
                5000,
                30000);

        assertEquals("basic", request.getAuthType());
        assertTrue(request.isBasicAuth());
        assertEquals("admin", request.getBasicUsername());
        assertEquals("admin", request.getBasicPassword());
    }

    @Test
    public void testFullConstructorWithOAuth2() {
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "https://example.purview.azure.com",
                "datamap/api/atlas/v2",
                "oauth2",
                "tenant-123",
                "client-123",
                "secret-123",
                "",
                "",
                5000,
                30000);

        assertEquals("oauth2", request.getAuthType());
        assertFalse(request.isBasicAuth());
        assertEquals("tenant-123", request.getTenantId());
        assertEquals("client-123", request.getClientId());
        assertEquals("secret-123", request.getClientSecret());
    }

    @Test
    public void testNullAuthTypeDefaultsToOAuth2() {
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "http://localhost:21000",
                "api/atlas/v2",
                null,
                "",
                "",
                "",
                "admin",
                "admin",
                5000,
                30000);

        assertEquals("oauth2", request.getAuthType());
        assertFalse(request.isBasicAuth());
    }

    @Test
    public void testNullBasicCredentialsDefaultToEmpty() {
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "http://localhost:21000",
                "api/atlas/v2",
                "basic",
                "",
                "",
                "",
                null,
                null,
                5000,
                30000);

        assertEquals("", request.getBasicUsername());
        assertEquals("", request.getBasicPassword());
    }

    @Test
    public void testIsPurviewDataMapTrueForDatamapBasePath() {
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "https://example.purview.azure.com",
                "datamap/api/atlas/v2",
                "tenant-123",
                "client-123",
                "secret-123",
                5000,
                30000);

        assertTrue(request.isPurviewDataMap());
    }

    @Test
    public void testIsPurviewDataMapFalseForAtlasBasePath() {
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "http://localhost:21000",
                "api/atlas/v2",
                "basic",
                "",
                "",
                "",
                "admin",
                "admin",
                5000,
                30000);

        assertFalse(request.isPurviewDataMap());
    }

    @Test
    public void testIsPurviewDataMapFalseForNullBasePath() {
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "http://localhost:21000",
                null,
                "basic",
                "",
                "",
                "",
                "admin",
                "admin",
                5000,
                30000);

        assertFalse(request.isPurviewDataMap());
    }

    @Test
    public void testIsBasicAuthCaseInsensitive() {
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "http://localhost:21000",
                "api/atlas/v2",
                "BASIC",
                "",
                "",
                "",
                "admin",
                "admin",
                5000,
                30000);

        assertTrue(request.isBasicAuth());
    }
}
