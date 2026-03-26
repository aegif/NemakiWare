package jp.aegif.nemaki.rest.purview.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AtlasSchemaCompatHelperTest {

    private AtlasSchemaCompatHelper helper;

    @BeforeEach
    public void setUp() {
        helper = new AtlasSchemaCompatHelper();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPatchAddsMaxStrLengthWhenMissing() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityDefs", List.of());
        payload.put("businessMetadataDefs", List.of(
                Map.of("name", "nemakiGovernance",
                        "attributeDefs", List.of(
                                Map.of("name", "classification", "typeName", "string")))));

        Map<String, Object> patched = helper.patchForAtlas(payload);

        List<Map<String, Object>> defs = (List<Map<String, Object>>) patched.get("businessMetadataDefs");
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) defs.get(0).get("attributeDefs");
        Map<String, Object> options = (Map<String, Object>) attrs.get(0).get("options");
        assertEquals("500", options.get("maxStrLength"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPatchPreservesExistingOptions() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("businessMetadataDefs", List.of(
                Map.of("name", "nemakiGovernance",
                        "attributeDefs", List.of(
                                Map.of("name", "classification", "typeName", "string",
                                        "options", Map.of("maxStrLength", "1000"))))));

        Map<String, Object> patched = helper.patchForAtlas(payload);

        List<Map<String, Object>> defs = (List<Map<String, Object>>) patched.get("businessMetadataDefs");
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) defs.get(0).get("attributeDefs");
        Map<String, Object> options = (Map<String, Object>) attrs.get(0).get("options");
        assertEquals("1000", options.get("maxStrLength"));
    }

    @Test
    public void testPatchDoesNotMutateInput() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("entityDefs", List.of());
        original.put("businessMetadataDefs", List.of(
                Map.of("name", "nemakiGovernance",
                        "attributeDefs", List.of(
                                Map.of("name", "classification", "typeName", "string")))));

        Map<String, Object> patched = helper.patchForAtlas(original);

        assertNotSame(original, patched);
        // Original should not have options added
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> originalDefs = (List<Map<String, Object>>) original.get("businessMetadataDefs");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> originalAttrs = (List<Map<String, Object>>) originalDefs.get(0).get("attributeDefs");
        assertEquals(false, originalAttrs.get(0).containsKey("options"));
    }

    @Test
    public void testPatchHandlesEmptyBusinessMetadataDefs() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityDefs", List.of());
        payload.put("businessMetadataDefs", List.of());

        Map<String, Object> patched = helper.patchForAtlas(payload);

        @SuppressWarnings("unchecked")
        List<?> defs = (List<?>) patched.get("businessMetadataDefs");
        assertTrue(defs.isEmpty());
    }

    @Test
    public void testPatchHandlesMissingBusinessMetadataDefsKey() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityDefs", List.of());

        Map<String, Object> patched = helper.patchForAtlas(payload);

        assertEquals(null, patched.get("businessMetadataDefs"));
        assertEquals(List.of(), patched.get("entityDefs"));
    }

    @Test
    public void testRemoveReplacesWithEmptyList() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityDefs", List.of(Map.of("name", "test")));
        payload.put("businessMetadataDefs", List.of(Map.of("name", "nemakiGovernance")));

        Map<String, Object> result = helper.removeBusinessMetadataDefs(payload);

        @SuppressWarnings("unchecked")
        List<?> defs = (List<?>) result.get("businessMetadataDefs");
        assertTrue(defs.isEmpty());
    }

    @Test
    public void testRemovePreservesOtherDefs() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityDefs", List.of(Map.of("name", "test")));
        payload.put("relationshipDefs", List.of(Map.of("name", "rel")));
        payload.put("businessMetadataDefs", List.of(Map.of("name", "nemakiGovernance")));

        Map<String, Object> result = helper.removeBusinessMetadataDefs(payload);

        @SuppressWarnings("unchecked")
        List<?> entityDefs = (List<?>) result.get("entityDefs");
        assertEquals(1, entityDefs.size());
        @SuppressWarnings("unchecked")
        List<?> relDefs = (List<?>) result.get("relationshipDefs");
        assertEquals(1, relDefs.size());
    }
}
