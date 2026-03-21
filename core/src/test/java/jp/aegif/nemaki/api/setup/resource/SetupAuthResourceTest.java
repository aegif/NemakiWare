package jp.aegif.nemaki.api.setup.resource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.model.AuthConfigRequest;
import jp.aegif.nemaki.api.setup.model.OidcTestRequest;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * Tests for SetupAuthResource.
 * Validates SSRF hardening, lockout prevention, response sanitisation.
 */
public class SetupAuthResourceTest {

    private SetupAuthResource resource;
    private PropertyManager propertyManager;

    @BeforeEach
    public void setUp() throws Exception {
        resource = new SetupAuthResource();
        propertyManager = mock(PropertyManager.class);

        Field field = SetupAuthResource.class.getDeclaredField("propertyManager");
        field.setAccessible(true);
        field.set(resource, propertyManager);
    }

    // ================================================================
    // GET /auth/state
    // ================================================================

    @Nested
    class GetState {

        @Test
        public void testDefaults() {
            when(propertyManager.readValue("auth.password.enabled")).thenReturn(null);
            when(propertyManager.readValue("cloud.auth.google.enabled")).thenReturn(null);
            when(propertyManager.readValue("cloud.auth.microsoft.enabled")).thenReturn(null);

            Response resp = resource.getState();
            assertEquals(200, resp.getStatus());

            AuthConfigRequest body = (AuthConfigRequest) resp.getEntity();
            assertTrue(body.isPasswordEnabled(), "Password should be enabled by default");
            assertFalse(body.isGoogleEnabled());
            assertFalse(body.isMicrosoftEnabled());
        }

        @Test
        public void testPasswordDisabled() {
            when(propertyManager.readValue("auth.password.enabled")).thenReturn("false");
            when(propertyManager.readValue("cloud.auth.google.enabled")).thenReturn("true");
            when(propertyManager.readValue("cloud.auth.microsoft.enabled")).thenReturn("true");

            Response resp = resource.getState();
            AuthConfigRequest body = (AuthConfigRequest) resp.getEntity();
            assertFalse(body.isPasswordEnabled());
            assertTrue(body.isGoogleEnabled());
            assertTrue(body.isMicrosoftEnabled());
        }

        @Test
        public void testNullPropertyManager() throws Exception {
            Field field = SetupAuthResource.class.getDeclaredField("propertyManager");
            field.setAccessible(true);
            field.set(resource, null);

            Response resp = resource.getState();
            assertEquals(200, resp.getStatus());
            AuthConfigRequest body = (AuthConfigRequest) resp.getEntity();
            assertTrue(body.isPasswordEnabled());
        }
    }

    // ================================================================
    // POST /auth/test-oidc
    // ================================================================

    @Nested
    class TestOidc {

        @Test
        public void testNullRequest() {
            assertEquals(400, resource.testOidc(null).getStatus());
        }

        @Test
        public void testEmptyIssuerUrl() {
            OidcTestRequest req = new OidcTestRequest();
            req.setIssuerUrl("");
            assertEquals(400, resource.testOidc(req).getStatus());
        }

        @Test
        public void testCloudMetadataBlocked() {
            OidcTestRequest req = new OidcTestRequest();
            req.setIssuerUrl("http://169.254.169.254/latest");

            Response resp = resource.testOidc(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("metadata"), body);
        }

        // Private IPs allowed on service ports in Setup Mode
        @Test
        public void testLocalhostAllowedOnServicePort() {
            OidcTestRequest req = new OidcTestRequest();
            req.setIssuerUrl("http://127.0.0.1:8080");

            Response resp = resource.testOidc(req);
            // URL passes validation, actual connection will fail → reachable:false
            assertEquals(200, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("reachable"), body);
        }

        // Non-service ports blocked
        @Test
        public void testNonServicePortBlocked() {
            OidcTestRequest req = new OidcTestRequest();
            req.setIssuerUrl("http://internal:3306");

            Response resp = resource.testOidc(req);
            assertEquals(400, resp.getStatus());
        }

        // Error response sanitised (no exception class/message)
        @Test
        public void testErrorResponseSanitised() {
            OidcTestRequest req = new OidcTestRequest();
            req.setIssuerUrl("http://nonexistent-host-12345.local:8080");

            Response resp = resource.testOidc(req);
            assertEquals(200, resp.getStatus());
            String body = (String) resp.getEntity();
            // Should NOT contain Java exception class names
            assertFalse(body.contains("UnknownHostException"), "Should not leak exception details: " + body);
            assertFalse(body.contains("ConnectException"), "Should not leak exception details: " + body);
            assertTrue(body.contains("Connection failed"), body);
        }
    }

    // ================================================================
    // POST /auth/apply
    // ================================================================

    @Nested
    class Apply {

        @Test
        public void testNullRequest() {
            assertEquals(400, resource.apply(null).getStatus());
        }

        @Test
        public void testLockoutPrevention() {
            AuthConfigRequest req = new AuthConfigRequest();
            req.setPasswordEnabled(false);
            req.setGoogleEnabled(false);
            req.setMicrosoftEnabled(false);

            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("At least one"), body);
        }

        @Test
        public void testApplyWithPassword() {
            // Apply will try CouchDB connection which will fail in unit test
            // but validation passes (at least one method enabled)
            AuthConfigRequest req = new AuthConfigRequest();
            req.setPasswordEnabled(true);
            Response resp = resource.apply(req);
            // Returns 500 because CouchDB is not available in unit test
            int status = resp.getStatus();
            assertTrue(status == 200 || status == 500,
                    "Expected 200 (success) or 500 (CouchDB unavailable), got " + status);
        }

        @Test
        public void testApplyWithGoogleNoClientId() {
            // Google enabled but no clientId → 400
            AuthConfigRequest req = new AuthConfigRequest();
            req.setPasswordEnabled(false);
            req.setGoogleEnabled(true);
            req.setMicrosoftEnabled(false);
            // googleClientId is null
            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("Google Client ID is required"), body);
        }

        @Test
        public void testApplyWithGoogleAndClientId() {
            // Google enabled with clientId → passes validation, CouchDB may fail
            AuthConfigRequest req = new AuthConfigRequest();
            req.setPasswordEnabled(false);
            req.setGoogleEnabled(true);
            req.setMicrosoftEnabled(false);
            req.setGoogleClientId("123456.apps.googleusercontent.com");
            Response resp = resource.apply(req);
            int status = resp.getStatus();
            assertTrue(status == 200 || status == 500,
                    "Expected 200 (success) or 500 (CouchDB unavailable), got " + status);
        }

        @Test
        public void testApplyWithMicrosoftNoClientId() {
            // Microsoft enabled but no clientId → 400
            AuthConfigRequest req = new AuthConfigRequest();
            req.setPasswordEnabled(false);
            req.setGoogleEnabled(false);
            req.setMicrosoftEnabled(true);
            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("Microsoft Client ID is required"), body);
        }
    }
}
