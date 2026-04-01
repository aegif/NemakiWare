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
