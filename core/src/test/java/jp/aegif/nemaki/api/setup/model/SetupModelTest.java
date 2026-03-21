package jp.aegif.nemaki.api.setup.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for Setup API DTO model classes.
 * Verifies getter/setter round-trips and default values.
 */
public class SetupModelTest {

    // ---- CouchDbConnectionRequest ----

    @Test
    public void testCouchDbConnectionRequest() {
        CouchDbConnectionRequest req = new CouchDbConnectionRequest();
        req.setUrl("http://couchdb:5984");
        req.setUsername("admin");
        req.setPassword("secret");

        assertEquals("http://couchdb:5984", req.getUrl());
        assertEquals("admin", req.getUsername());
        assertEquals("secret", req.getPassword());
    }

    @Test
    public void testCouchDbConnectionRequestDefaults() {
        CouchDbConnectionRequest req = new CouchDbConnectionRequest();
        assertNull(req.getUrl());
        assertNull(req.getUsername());
        assertNull(req.getPassword());
    }

    // ---- MigrationRequest ----

    @Test
    public void testMigrationRequest() {
        MigrationRequest req = new MigrationRequest();
        req.setRepositoryIds(List.of("bedroom", "canopy"));
        req.setConfirmed(true);

        assertEquals(List.of("bedroom", "canopy"), req.getRepositoryIds());
        assertTrue(req.isConfirmed());
    }

    @Test
    public void testMigrationRequestDefaults() {
        MigrationRequest req = new MigrationRequest();
        assertNull(req.getRepositoryIds());
        assertFalse(req.isConfirmed());
    }

    // ---- OidcTestRequest ----

    @Test
    public void testOidcTestRequest() {
        OidcTestRequest req = new OidcTestRequest();
        req.setIssuerUrl("https://accounts.google.com");
        req.setClientId("client123");
        req.setClientSecret("secret456");

        assertEquals("https://accounts.google.com", req.getIssuerUrl());
        assertEquals("client123", req.getClientId());
        assertEquals("secret456", req.getClientSecret());
    }

    // ---- AuthConfigRequest ----

    @Test
    public void testAuthConfigRequest() {
        AuthConfigRequest req = new AuthConfigRequest();
        req.setPasswordEnabled(false);
        req.setGoogleEnabled(true);
        req.setMicrosoftEnabled(true);

        assertFalse(req.isPasswordEnabled());
        assertTrue(req.isGoogleEnabled());
        assertTrue(req.isMicrosoftEnabled());
    }

    @Test
    public void testAuthConfigRequestDefaultPasswordEnabled() {
        AuthConfigRequest req = new AuthConfigRequest();
        assertTrue(req.isPasswordEnabled(), "Password should be enabled by default");
        assertFalse(req.isGoogleEnabled());
        assertFalse(req.isMicrosoftEnabled());
    }

    @Test
    public void testAuthConfigRequestProviderFields() {
        AuthConfigRequest req = new AuthConfigRequest();
        req.setGoogleClientId("google-client-123");
        req.setMicrosoftClientId("ms-client-456");
        req.setMicrosoftTenantId("tenant-789");

        assertEquals("google-client-123", req.getGoogleClientId());
        assertEquals("ms-client-456", req.getMicrosoftClientId());
        assertEquals("tenant-789", req.getMicrosoftTenantId());
    }

    @Test
    public void testAuthConfigRequestOidcSettings() {
        AuthConfigRequest.OidcSettings oidc = new AuthConfigRequest.OidcSettings();
        oidc.setIssuerUrl("https://example.com");
        oidc.setClientId("id");
        oidc.setClientSecret("secret");

        AuthConfigRequest req = new AuthConfigRequest();
        req.setOidcSettings(oidc);

        assertNotNull(req.getOidcSettings());
        assertEquals("https://example.com", req.getOidcSettings().getIssuerUrl());
        assertEquals("id", req.getOidcSettings().getClientId());
        assertEquals("secret", req.getOidcSettings().getClientSecret());
    }

    // ---- VectorTestRequest ----

    @Test
    public void testVectorTestRequest() {
        VectorTestRequest req = new VectorTestRequest();
        req.setType("tei");
        req.setUrl("http://tei:8080");
        req.setAccessKeyId("key");

        assertEquals("tei", req.getType());
        assertEquals("http://tei:8080", req.getUrl());
        assertEquals("key", req.getAccessKeyId());
    }

    // ---- SetupApplyRequest ----

    @Test
    public void testSetupApplyRequest() {
        SetupApplyRequest req = new SetupApplyRequest();
        CouchDbConnectionRequest couch = new CouchDbConnectionRequest();
        couch.setUrl("http://couchdb:5984");
        req.setCouchdb(couch);

        AuthConfigRequest auth = new AuthConfigRequest();
        auth.setPasswordEnabled(true);
        req.setAuth(auth);

        VectorTestRequest vector = new VectorTestRequest();
        vector.setType("none");
        req.setVector(vector);

        assertNotNull(req.getCouchdb());
        assertEquals("http://couchdb:5984", req.getCouchdb().getUrl());
        assertNotNull(req.getAuth());
        assertTrue(req.getAuth().isPasswordEnabled());
        assertNotNull(req.getVector());
        assertEquals("none", req.getVector().getType());
    }

    // ---- AdminSetupRequest ----

    @Test
    public void testAdminSetupRequest() {
        AdminSetupRequest req = new AdminSetupRequest();
        req.setNewPassword("newpass123");
        assertEquals("newpass123", req.getNewPassword());
    }

    @Test
    public void testAdminSetupRequestDefaults() {
        AdminSetupRequest req = new AdminSetupRequest();
        assertNull(req.getNewPassword());
    }

    // ---- SetupApplyRequest adminPassword field ----

    @Test
    public void testSetupApplyRequestAdminPassword() {
        SetupApplyRequest req = new SetupApplyRequest();
        req.setAdminPassword("secret123");
        assertEquals("secret123", req.getAdminPassword());
    }

    // ---- SetupStateResponse ----

    @Test
    public void testSetupStateResponse() {
        SetupStateResponse resp = new SetupStateResponse();
        resp.setSetupRequired(true);
        resp.setState("DB_UNREACHABLE");
        resp.setServerVersion("3.1.0-RC8");
        resp.setCompletedSteps(List.of("couchdb", "auth"));

        assertTrue(resp.isSetupRequired());
        assertEquals("DB_UNREACHABLE", resp.getState());
        assertEquals("3.1.0-RC8", resp.getServerVersion());
        assertEquals(List.of("couchdb", "auth"), resp.getCompletedSteps());
    }
}
