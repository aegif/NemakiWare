package jp.aegif.nemaki.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;

public class IntegrationSettingsControllerTest {

    private IntegrationSettingsService settingsService;
    private IntegrationSettingsController controller;
    private HttpServletRequest httpRequest;
    private CallContext callContext;

    @BeforeEach
    public void setUp() throws Exception {
        settingsService = mock(IntegrationSettingsService.class);
        httpRequest = mock(HttpServletRequest.class);
        callContext = mock(CallContext.class);

        when(httpRequest.getAttribute("CallContext")).thenReturn(callContext);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(true);

        // Default: no settings configured
        when(settingsService.readSetting(any())).thenReturn(null);
        when(settingsService.readSettings(any())).thenReturn(Map.of());
        when(settingsService.readSettingSources(any())).thenReturn(Map.of());

        controller = new IntegrationSettingsController(settingsService);
        controller.setHttpRequest(httpRequest);
    }

    /**
     * The admin PUT must not invent a catalogName from the property id. The resolver's load path
     * stopped accepting property-id reuse (A-1k); a save path that falls back to it persists the
     * invented value, after which it is indistinguishable from one an admin chose.
     */
    @Nested
    class PropertyMappingSave {

        private jp.aegif.nemaki.rest.purview.payload.CatalogPropertyMappingResolver resolver;

        @BeforeEach
        void injectResolver() throws Exception {
            resolver = mock(jp.aegif.nemaki.rest.purview.payload.CatalogPropertyMappingResolver.class);
            java.lang.reflect.Field f =
                    IntegrationSettingsController.class.getDeclaredField("propertyMappingResolver");
            f.setAccessible(true);
            f.set(controller, resolver);
        }

        @Test
        void anEnabledMappingWithoutACatalogNameIsRejectedNotInvented() {
            Map<String, Object> body = Map.of("mappings", Map.of(
                    "nemaki:document", Map.of(
                            "nemaki:cloudProvider", Map.of("enabled", true))));

            var response = controller.updatePropertyMappings("bedroom", new java.util.LinkedHashMap<>(body));

            org.junit.jupiter.api.Assertions.assertEquals(400, response.getStatusCode().value());
            String message = String.valueOf(response.getBody().get("message"));
            org.junit.jupiter.api.Assertions.assertTrue(message.contains("nemaki:cloudProvider"), message);
            org.junit.jupiter.api.Assertions.assertTrue(message.contains("catalogName"), message);
            org.mockito.Mockito.verify(resolver, org.mockito.Mockito.never())
                    .saveMappings(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        void aBlankCatalogNameOnAnEnabledMappingIsAlsoRejected() {
            Map<String, Object> body = Map.of("mappings", Map.of(
                    "nemaki:document", Map.of(
                            "nemaki:cloudProvider", Map.of("enabled", true, "catalogName", "  "))));

            var response = controller.updatePropertyMappings("bedroom", new java.util.LinkedHashMap<>(body));
            org.junit.jupiter.api.Assertions.assertEquals(400, response.getStatusCode().value());
        }

        @Test
        void aDisabledMappingMayOmitTheCatalogNameAndStoresNoInventedOne() {
            Map<String, Object> body = Map.of("mappings", Map.of(
                    "nemaki:document", Map.of(
                            "nemaki:cloudProvider", Map.of("enabled", false))));

            var response = controller.updatePropertyMappings("bedroom", new java.util.LinkedHashMap<>(body));

            org.junit.jupiter.api.Assertions.assertEquals(200, response.getStatusCode().value());
            org.mockito.Mockito.verify(resolver).saveMappings(
                    org.mockito.ArgumentMatchers.eq("bedroom"),
                    org.mockito.ArgumentMatchers.argThat(parsed -> {
                        var mapping = parsed.get("nemaki:document").get("nemaki:cloudProvider");
                        return !mapping.enabled() && mapping.catalogName() == null;
                    }));
        }

        @Test
        void anEnabledMappingWithACatalogNameSavesExactlyThatName() {
            Map<String, Object> body = Map.of("mappings", Map.of(
                    "nemaki:document", Map.of(
                            "nemaki:cloudProvider", Map.of("enabled", true, "catalogName", "provider"))));

            var response = controller.updatePropertyMappings("bedroom", new java.util.LinkedHashMap<>(body));

            org.junit.jupiter.api.Assertions.assertEquals(200, response.getStatusCode().value());
            org.mockito.Mockito.verify(resolver).saveMappings(
                    org.mockito.ArgumentMatchers.eq("bedroom"),
                    org.mockito.ArgumentMatchers.argThat(parsed ->
                            "provider".equals(parsed.get("nemaki:document")
                                    .get("nemaki:cloudProvider").catalogName())));
        }
    }

    @Nested
    @DisplayName("Dual backend warning")
    class DualBackendWarning {

        @Test
        @DisplayName("updatePurviewSettings returns warning when Atlas is also enabled")
        public void testPurviewUpdateWarnsWhenAtlasEnabled() {
            when(settingsService.readSetting("purview.enabled")).thenReturn("true");
            when(settingsService.readSetting("atlas.enabled")).thenReturn("true");

            ResponseEntity<Map<String, Object>> response = controller.updatePurviewSettings(
                    Map.of("purview.enabled", "true"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().containsKey("warning"));
            String warning = (String) response.getBody().get("warning");
            assertTrue(warning.contains("Purview") && warning.contains("Atlas"));
        }

        @Test
        @DisplayName("updateAtlasSettings returns warning when Purview is also enabled")
        public void testAtlasUpdateWarnsWhenPurviewEnabled() {
            when(settingsService.readSetting("purview.enabled")).thenReturn("true");
            when(settingsService.readSetting("atlas.enabled")).thenReturn("true");

            ResponseEntity<Map<String, Object>> response = controller.updateAtlasSettings(
                    Map.of("atlas.enabled", "true"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().containsKey("warning"));
            String warning = (String) response.getBody().get("warning");
            assertTrue(warning.contains("Purview") && warning.contains("Atlas"));
        }

        @Test
        @DisplayName("updatePurviewSettings has no warning when Atlas is disabled")
        public void testPurviewUpdateNoWarningWhenAtlasDisabled() {
            when(settingsService.readSetting("purview.enabled")).thenReturn("true");
            when(settingsService.readSetting("atlas.enabled")).thenReturn("false");

            ResponseEntity<Map<String, Object>> response = controller.updatePurviewSettings(
                    Map.of("purview.enabled", "true"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(response.getBody().containsKey("warning"));
        }

        @Test
        @DisplayName("updateAtlasSettings has no warning when Purview is disabled")
        public void testAtlasUpdateNoWarningWhenPurviewDisabled() {
            when(settingsService.readSetting("purview.enabled")).thenReturn(null);
            when(settingsService.readSetting("atlas.enabled")).thenReturn("true");

            ResponseEntity<Map<String, Object>> response = controller.updateAtlasSettings(
                    Map.of("atlas.enabled", "true"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(response.getBody().containsKey("warning"));
        }

        @Test
        @DisplayName("getPurviewSettings returns warning on load when both backends are enabled")
        public void testPurviewGetWarnsWhenBothEnabled() {
            when(settingsService.readSetting("purview.enabled")).thenReturn("true");
            when(settingsService.readSetting("atlas.enabled")).thenReturn("true");

            ResponseEntity<Map<String, Object>> response = controller.getPurviewSettings();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().containsKey("warning"));
        }

        @Test
        @DisplayName("getAtlasSettings returns warning on load when both backends are enabled")
        public void testAtlasGetWarnsWhenBothEnabled() {
            when(settingsService.readSetting("purview.enabled")).thenReturn("true");
            when(settingsService.readSetting("atlas.enabled")).thenReturn("true");

            ResponseEntity<Map<String, Object>> response = controller.getAtlasSettings();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().containsKey("warning"));
        }

        @Test
        @DisplayName("getPurviewSettings has no warning when only Purview is enabled")
        public void testPurviewGetNoWarningWhenAlone() {
            when(settingsService.readSetting("purview.enabled")).thenReturn("true");
            when(settingsService.readSetting("atlas.enabled")).thenReturn("false");

            ResponseEntity<Map<String, Object>> response = controller.getPurviewSettings();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(response.getBody().containsKey("warning"));
        }

        @Test
        @DisplayName("getAtlasSettings has no warning when only Atlas is enabled")
        public void testAtlasGetNoWarningWhenAlone() {
            when(settingsService.readSetting("purview.enabled")).thenReturn(null);
            when(settingsService.readSetting("atlas.enabled")).thenReturn("true");

            ResponseEntity<Map<String, Object>> response = controller.getAtlasSettings();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(response.getBody().containsKey("warning"));
        }
    }

    @Nested
    @DisplayName("Outbound endpoint SSRF check (opt-in)")
    class OutboundEndpointSsrf {

        private void setEnforce(boolean v) throws Exception {
            java.lang.reflect.Field f = IntegrationSettingsController.class
                    .getDeclaredField("validateOutboundInternal");
            f.setAccessible(true);
            f.set(controller, v);
        }

        @Test
        @DisplayName("disabled (default): internal endpoint is saved unchanged")
        void disabledAllowsInternalEndpoint() throws Exception {
            setEnforce(false);
            ResponseEntity<Map<String, Object>> response = controller.updateAtlasSettings(
                    Map.of("atlas.endpoint", "http://127.0.0.1:21000/api"));
            assertEquals(HttpStatus.OK, response.getStatusCode());
            org.mockito.Mockito.verify(settingsService).writeSettings(
                    org.mockito.ArgumentMatchers.argThat(m ->
                            "http://127.0.0.1:21000/api".equals(m.get("atlas.endpoint"))));
        }

        @Test
        @DisplayName("enabled: internal endpoint is rejected with 400 and not persisted")
        void enabledRejectsInternalEndpoint() throws Exception {
            setEnforce(true);
            ResponseEntity<Map<String, Object>> response = controller.updateAtlasSettings(
                    Map.of("atlas.endpoint", "http://127.0.0.1:21000/api"));
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals("error", response.getBody().get("status"));
            org.mockito.Mockito.verify(settingsService, org.mockito.Mockito.never())
                    .writeSettings(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("enabled: same check applies to purview.endpoint")
        void enabledRejectsInternalPurviewEndpoint() throws Exception {
            setEnforce(true);
            ResponseEntity<Map<String, Object>> response = controller.updatePurviewSettings(
                    Map.of("purview.endpoint", "http://169.254.169.254/"));
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            org.mockito.Mockito.verify(settingsService, org.mockito.Mockito.never())
                    .writeSettings(org.mockito.ArgumentMatchers.any());
        }
    }
}
