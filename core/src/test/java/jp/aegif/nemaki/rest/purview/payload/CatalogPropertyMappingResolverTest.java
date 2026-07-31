package jp.aegif.nemaki.rest.purview.payload;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogPropertyMappingResolverTest {

    private CatalogPropertyMappingResolver resolver(IntegrationSettingsService service) {
        return new CatalogPropertyMappingResolver(service, null, null);
    }

    private CatalogPropertyMappingResolver resolver(IntegrationSettingsService service, TypeService typeService) {
        return new CatalogPropertyMappingResolver(service, typeService, null);
    }

    /**
     * Configuration that never went through {@code saveMappings}.
     *
     * <p>{@code validateMappings} only ever ran on save, so the reserved-name rule protected
     * exactly one of the ways a mapping arrives. A configuration written before the rule existed,
     * a restore, a hand-edited CouchDB document and a corrupted value all reach the payload
     * through {@code loadMappings}, and each could name a core attribute — including
     * {@code cloudFileUrl}, which is deliberately null so that no stored cloud URL reaches the
     * catalog. These tests therefore feed the persisted JSON directly.
     */
    @Nested
    class PersistedConfigurationBypassingSave {

        private CatalogPropertyMappingResolver withStoredJson(String json) {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(json);
            return resolver(service);
        }

        @Test
        void testReservedCatalogNameInStoredJsonIsDropped() {
            CatalogPropertyMappingResolver r = withStoredJson("""
                    { "nemaki:document": {
                        "nemaki:cloudFileUrl": { "enabled": true, "catalogName": "cloudFileUrl" } } }
                    """);

            assertTrue(r.loadMappings("bedroom").getOrDefault("nemaki:document", Map.of()).isEmpty(),
                    "a mapping onto a core attribute must not survive the load");
            assertTrue(r.getEnabledMappings("bedroom", "nemaki:document").isEmpty());
            assertEquals(1, r.getRejectedMappingCount());
        }

        @Test
        void testEveryReservedNameIsRejectedFromStoredJson() {
            for (String reserved : CatalogPropertyMappingResolver.RESERVED_ATTRIBUTE_NAMES) {
                CatalogPropertyMappingResolver r = withStoredJson("""
                        { "nemaki:document": {
                            "nemaki:x": { "enabled": true, "catalogName": "%s" } } }
                        """.formatted(reserved));
                assertTrue(r.getEnabledMappings("bedroom", "nemaki:document").isEmpty(),
                        reserved + " survived the load");
            }
        }

        @Test
        void testBlankAndWhitespaceCatalogNamesAreDropped() {
            for (String blank : List.of("", " ", "   ")) {
                CatalogPropertyMappingResolver r = withStoredJson("""
                        { "nemaki:document": {
                            "nemaki:x": { "enabled": true, "catalogName": "%s" } } }
                        """.formatted(blank));
                assertTrue(r.getEnabledMappings("bedroom", "nemaki:document").isEmpty(),
                        "blank name '" + blank + "' survived the load");
            }
            // a padded reserved name is a mistake either way
            CatalogPropertyMappingResolver padded = withStoredJson("""
                    { "nemaki:document": {
                        "nemaki:x": { "enabled": true, "catalogName": " cloudFileUrl " } } }
                    """);
            assertTrue(padded.getEnabledMappings("bedroom", "nemaki:document").isEmpty());
        }

        /** One bad mapping must not stop the rest of the projection. */
        @Test
        void testOnlyTheOffendingMappingIsDropped() {
            CatalogPropertyMappingResolver r = withStoredJson("""
                    { "nemaki:document": {
                        "nemaki:cloudFileUrl": { "enabled": true, "catalogName": "cloudFileUrl" },
                        "nemaki:dept":         { "enabled": true, "catalogName": "department" } } }
                    """);

            Map<String, String> enabled = r.getEnabledMappings("bedroom", "nemaki:document");
            assertEquals(Map.of("nemaki:dept", "department"), enabled);
            assertEquals(1, r.getRejectedMappingCount());
        }

        /** A disabled mapping is inert, so it is kept as configuration rather than dropped. */
        @Test
        void testADisabledReservedMappingIsNotCounted() {
            CatalogPropertyMappingResolver r = withStoredJson("""
                    { "nemaki:document": {
                        "nemaki:x": { "enabled": false, "catalogName": "cloudFileUrl" } } }
                    """);
            assertTrue(r.getEnabledMappings("bedroom", "nemaki:document").isEmpty());
            assertEquals(0, r.getRejectedMappingCount());
        }

        /**
         * The output name is innocuous; the input property is the secret carrier.
         *
         * <p>{@code nemaki:cloudFileUrl -> legacyCloudUrl} passes the reserved-name rule (nothing
         * is called legacyCloudUrl) and the payload boundary (no such attribute exists yet), and
         * would put in Atlas exactly the URL increment A-1g removed. The property is real on older
         * documents — {@code CloudDriveResource} still reads cloud metadata from
         * {@code subTypeProperties} as a legacy fallback.
         */
        @Test
        void testAForbiddenSourcePropertyIsDroppedWhateverTheOutputNameIs() {
            for (String outputName : List.of("legacyCloudUrl", "myUrl", "department")) {
                CatalogPropertyMappingResolver r = withStoredJson("""
                        { "nemaki:document": {
                            "nemaki:cloudFileUrl": { "enabled": true, "catalogName": "%s" } } }
                        """.formatted(outputName));
                assertTrue(r.getEnabledMappings("bedroom", "nemaki:document").isEmpty(),
                        "nemaki:cloudFileUrl projected as '" + outputName + "'");
                assertEquals(1, r.getRejectedMappingCount());
            }
        }

        /** And it must not take the rest of the configuration down with it. */
        @Test
        void testOtherMappingsSurviveAForbiddenSourceProperty() {
            CatalogPropertyMappingResolver r = withStoredJson("""
                    { "nemaki:document": {
                        "nemaki:cloudFileUrl": { "enabled": true, "catalogName": "legacyCloudUrl" },
                        "nemaki:dept":         { "enabled": true, "catalogName": "department" } } }
                    """);
            assertEquals(Map.of("nemaki:dept", "department"),
                    r.getEnabledMappings("bedroom", "nemaki:document"));
        }

        /** Save and load must agree, or one of them is the hole. */
        @Test
        void testSaveRejectsWhatLoadRejects() {
            for (String name : List.of("cloudFileUrl", "qualifiedName", "externalFileId", " ")) {
                assertNotNull(CatalogPropertyMappingResolver.rejectionFor("nemaki:x", name), name);
                assertFalse(CatalogPropertyMappingResolver.validateMappings(Map.of(
                                "nemaki:document", Map.of("nemaki:x",
                                        new CatalogPropertyMappingResolver.PropertyMapping(true, name))))
                        .isEmpty(), name + " passed validateMappings");
            }
            // the input side, through the same entry point and the same validator
            assertEquals(CatalogPropertyMappingResolver.Rejection.FORBIDDEN_SOURCE_PROPERTY,
                    CatalogPropertyMappingResolver.rejectionFor(
                            "nemaki:cloudFileUrl", "legacyCloudUrl"));
            assertFalse(CatalogPropertyMappingResolver.validateMappings(Map.of(
                            "nemaki:document", Map.of("nemaki:cloudFileUrl",
                                    new CatalogPropertyMappingResolver.PropertyMapping(
                                            true, "legacyCloudUrl"))))
                    .isEmpty(), "a forbidden source property passed validateMappings");

            assertNull(CatalogPropertyMappingResolver.rejectionFor("nemaki:dept", "department"));
        }

        /**
         * The rejection names the rule that fired, so an operator is not told a forbidden source
         * property has a blank or reserved name.
         */
        @Test
        void testTheRejectionSaysWhichRuleFired() {
            assertEquals(CatalogPropertyMappingResolver.Rejection.FORBIDDEN_SOURCE_PROPERTY,
                    CatalogPropertyMappingResolver.rejectionFor("nemaki:cloudFileUrl", "anything"));
            assertEquals(CatalogPropertyMappingResolver.Rejection.BLANK_CATALOG_NAME,
                    CatalogPropertyMappingResolver.rejectionFor("nemaki:dept", "  "));
            assertEquals(CatalogPropertyMappingResolver.Rejection.RESERVED_CATALOG_NAME,
                    CatalogPropertyMappingResolver.rejectionFor("nemaki:dept", "cloudFileUrl"));

            for (CatalogPropertyMappingResolver.Rejection r
                    : CatalogPropertyMappingResolver.Rejection.values()) {
                assertFalse(r.reason().isBlank(), r + " has no operator message");
            }
        }

        /** validateMappings reports the specific reason, not one message for every case. */
        @Test
        void testSaveErrorsNameTheRuleThatFired() {
            List<String> forbidden = CatalogPropertyMappingResolver.validateMappings(Map.of(
                    "nemaki:document", Map.of("nemaki:cloudFileUrl",
                            new CatalogPropertyMappingResolver.PropertyMapping(
                                    true, "legacyCloudUrl"))));
            assertEquals(1, forbidden.size());
            assertTrue(forbidden.get(0).contains("under any name"), forbidden.get(0));

            List<String> reserved = CatalogPropertyMappingResolver.validateMappings(Map.of(
                    "nemaki:document", Map.of("nemaki:dept",
                            new CatalogPropertyMappingResolver.PropertyMapping(
                                    true, "cloudFileUrl"))));
            assertEquals(1, reserved.size());
            assertTrue(reserved.get(0).contains("reserved"), reserved.get(0));
        }
    }

    @Nested
    class RepositoryScopedLoadSave {

        @Test
        void testLoadMappingsReturnsEmptyWhenNoSetting() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(null);
            CatalogPropertyMappingResolver r = resolver(service);

            assertTrue(r.loadMappings("bedroom").isEmpty());
        }

        @Test
        void testLoadMappingsParsesRepositoryScopedKey() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            String json = """
                    { "nemaki:document": { "nemaki:dept": { "enabled": true, "catalogName": "department" } } }
                    """;
            when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(json);
            CatalogPropertyMappingResolver r = resolver(service);

            var result = r.loadMappings("bedroom");
            assertEquals(1, result.size());
            assertTrue(result.get("nemaki:document").get("nemaki:dept").enabled());
            assertEquals("department", result.get("nemaki:document").get("nemaki:dept").catalogName());
        }

        @Test
        void testLoadMappingsWarnsOnLegacyGlobalKey() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(null);
            when(service.readSetting("catalog.sync.propertyMappings")).thenReturn("{\"foo\":{}}");
            CatalogPropertyMappingResolver r = resolver(service);

            // Should return empty (legacy data is not migrated)
            assertTrue(r.loadMappings("bedroom").isEmpty());
        }

        @Test
        void testSaveMappingsWritesToRepositoryScopedKey() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            CatalogPropertyMappingResolver r = resolver(service);

            Map<String, Map<String, CatalogPropertyMappingResolver.PropertyMapping>> mappings = Map.of(
                    "nemaki:doc", Map.of(
                            "nemaki:field1", new CatalogPropertyMappingResolver.PropertyMapping(true, "field1")));

            r.saveMappings("bedroom", mappings);
            verify(service).writeSetting(eq("catalog.sync.propertyMappings.bedroom"), anyString());
        }

        @Test
        void testRepositoryIsolation() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            String bedroomJson = """
                    { "nemaki:doc": { "nemaki:dept": { "enabled": true, "catalogName": "dept_a" } } }
                    """;
            String canopyJson = """
                    { "nemaki:doc": { "nemaki:dept": { "enabled": true, "catalogName": "dept_b" } } }
                    """;
            when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(bedroomJson);
            when(service.readSetting("catalog.sync.propertyMappings.canopy")).thenReturn(canopyJson);
            CatalogPropertyMappingResolver r = resolver(service);

            assertEquals("dept_a", r.getEnabledMappings("bedroom", "nemaki:doc").get("nemaki:dept"));
            assertEquals("dept_b", r.getEnabledMappings("canopy", "nemaki:doc").get("nemaki:dept"));
        }

        @Test
        void testMappingDoesNotStorePropertyTypeOrCardinality() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            CatalogPropertyMappingResolver r = resolver(service);

            Map<String, Map<String, CatalogPropertyMappingResolver.PropertyMapping>> mappings = Map.of(
                    "nemaki:doc", Map.of(
                            "nemaki:f", new CatalogPropertyMappingResolver.PropertyMapping(true, "f")));
            r.saveMappings("bedroom", mappings);

            // Verify the persisted JSON does NOT contain propertyType or cardinality
            verify(service).writeSetting(eq("catalog.sync.propertyMappings.bedroom"), argThat(json ->
                    !json.contains("propertyType") && !json.contains("cardinality")));
        }
    }

    @Nested
    class Validation {

        @Test
        void testRejectsReservedCatalogName() {
            Map<String, Map<String, CatalogPropertyMappingResolver.PropertyMapping>> mappings = Map.of(
                    "nemaki:doc", Map.of(
                            "nemaki:f", new CatalogPropertyMappingResolver.PropertyMapping(true, "objectId")));

            List<String> errors = CatalogPropertyMappingResolver.validateMappings(mappings);
            assertEquals(1, errors.size());
        }

        @Test
        void testRejectsBlankCatalogName() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            CatalogPropertyMappingResolver r = resolver(service);

            Map<String, Map<String, CatalogPropertyMappingResolver.PropertyMapping>> mappings = Map.of(
                    "nemaki:doc", Map.of(
                            "nemaki:f", new CatalogPropertyMappingResolver.PropertyMapping(true, "")));

            assertThrows(IllegalArgumentException.class, () -> r.saveMappings("bedroom", mappings));
            verify(service, never()).writeSetting(anyString(), anyString());
        }

        @Test
        void testRejectsWhitespaceCatalogName() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            CatalogPropertyMappingResolver r = resolver(service);

            Map<String, Map<String, CatalogPropertyMappingResolver.PropertyMapping>> mappings = Map.of(
                    "nemaki:doc", Map.of(
                            "nemaki:f", new CatalogPropertyMappingResolver.PropertyMapping(true, "   ")));

            assertThrows(IllegalArgumentException.class, () -> r.saveMappings("bedroom", mappings));
        }

        @Test
        void testAllowsReservedNameWhenDisabled() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            CatalogPropertyMappingResolver r = resolver(service);

            Map<String, Map<String, CatalogPropertyMappingResolver.PropertyMapping>> mappings = Map.of(
                    "nemaki:doc", Map.of(
                            "nemaki:f", new CatalogPropertyMappingResolver.PropertyMapping(false, "objectId")));

            r.saveMappings("bedroom", mappings);
            verify(service).writeSetting(eq("catalog.sync.propertyMappings.bedroom"), anyString());
        }
    }

    @Nested
    class TypeDefinitionResolution {

        @Test
        void testResolvedMappingsUsesTypeServiceForTypeInfo() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            String json = """
                    { "nemaki:doc": { "nemaki:count": { "enabled": true, "catalogName": "count" } } }
                    """;
            when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(json);

            TypeService typeService = mock(TypeService.class);
            NemakiPropertyDefinitionCore core = mock(NemakiPropertyDefinitionCore.class);
            when(core.getPropertyType()).thenReturn(PropertyType.INTEGER);
            when(core.getCardinality()).thenReturn(Cardinality.SINGLE);
            when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:count")).thenReturn(core);

            CatalogPropertyMappingResolver r = resolver(service, typeService);
            var resolved = r.getResolvedMappings("bedroom");

            assertEquals(1, resolved.size());
            assertEquals(PropertyType.INTEGER, resolved.get("count").propertyType());
            assertEquals(Cardinality.SINGLE, resolved.get("count").cardinality());
        }

        @Test
        void testResolvedMappingsSkipsWhenTypeNotFound() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            String json = """
                    { "nemaki:doc": { "nemaki:missing": { "enabled": true, "catalogName": "missing" } } }
                    """;
            when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(json);

            TypeService typeService = mock(TypeService.class);
            when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:missing")).thenReturn(null);

            CatalogPropertyMappingResolver r = resolver(service, typeService);
            var resolved = r.getResolvedMappings("bedroom");

            // Missing type definition → mapping is skipped entirely (fail-safe)
            assertTrue(resolved.isEmpty(), "Missing type definition should skip mapping, not fall back");
        }

        @Test
        void testResolvedMappingsSkipsWhenTypeServiceNull() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            String json = """
                    { "nemaki:doc": { "nemaki:x": { "enabled": true, "catalogName": "x" } } }
                    """;
            when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(json);

            // No TypeService → all mappings skipped
            CatalogPropertyMappingResolver r = resolver(service);
            var resolved = r.getResolvedMappings("bedroom");

            assertTrue(resolved.isEmpty());
        }

        @Test
        void testFingerprintChangesWhenTypeDefinitionChanges() {
            IntegrationSettingsService service = mock(IntegrationSettingsService.class);
            String json = """
                    { "nemaki:doc": { "nemaki:x": { "enabled": true, "catalogName": "x" } } }
                    """;
            when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(json);

            TypeService typeService = mock(TypeService.class);
            NemakiPropertyDefinitionCore stringCore = mock(NemakiPropertyDefinitionCore.class);
            when(stringCore.getPropertyType()).thenReturn(PropertyType.STRING);
            when(stringCore.getCardinality()).thenReturn(Cardinality.SINGLE);

            NemakiPropertyDefinitionCore intCore = mock(NemakiPropertyDefinitionCore.class);
            when(intCore.getPropertyType()).thenReturn(PropertyType.INTEGER);
            when(intCore.getCardinality()).thenReturn(Cardinality.SINGLE);

            when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:x")).thenReturn(stringCore);
            CatalogPropertyMappingResolver r = resolver(service, typeService);
            String fp1 = r.computeMappingFingerprint("bedroom");

            // Simulate type definition change: clear cache and re-resolve
            r.clearResolvedCache();
            when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:x")).thenReturn(intCore);
            String fp2 = r.computeMappingFingerprint("bedroom");

            assertNotEquals(fp1, fp2, "Fingerprint should change when type definition changes");
        }
    }

    @Nested
    class TypeConversion {

        @Test
        void testToAtlasTypeName() {
            assertEquals("string", CatalogPropertyMappingResolver.toAtlasTypeName(PropertyType.STRING));
            assertEquals("long", CatalogPropertyMappingResolver.toAtlasTypeName(PropertyType.INTEGER));
            assertEquals("boolean", CatalogPropertyMappingResolver.toAtlasTypeName(PropertyType.BOOLEAN));
            assertEquals("long", CatalogPropertyMappingResolver.toAtlasTypeName(PropertyType.DATETIME));
            assertEquals("double", CatalogPropertyMappingResolver.toAtlasTypeName(PropertyType.DECIMAL));
            assertEquals("string", CatalogPropertyMappingResolver.toAtlasTypeName(PropertyType.ID));
            assertEquals("string", CatalogPropertyMappingResolver.toAtlasTypeName(null));
        }
    }
}
