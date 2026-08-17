package jp.aegif.nemaki.rest.purview.journal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtlasLineageSinkTest {

    private AtlasLineageSink sink;
    private AtlasConfig mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        sink = new AtlasLineageSink();
        mockConfig = mock(AtlasConfig.class);

        Field f = AtlasLineageSink.class.getDeclaredField("atlasConfig");
        f.setAccessible(true);
        f.set(sink, mockConfig);
    }

    @Test
    void targetName_returnsAtlas() {
        assertEquals("atlas", sink.targetName());
    }

    @Test
    void isAvailable_returnsFalseWhenDisabled() {
        when(mockConfig.isEnabled()).thenReturn(false);
        assertFalse(sink.isAvailable());
    }

    @Test
    void isAvailable_returnsFalseWhenEndpointBlank() {
        when(mockConfig.isEnabled()).thenReturn(true);
        when(mockConfig.getEndpoint()).thenReturn("");
        assertFalse(sink.isAvailable());
    }

    @Test
    void isAvailable_returnsTrueWhenConfigured() {
        when(mockConfig.isEnabled()).thenReturn(true);
        when(mockConfig.getEndpoint()).thenReturn("https://atlas.example.com");
        assertTrue(sink.isAvailable());
    }

    @Test
    void publish_returnsFailureWhenNotAvailable() throws Exception {
        when(mockConfig.isEnabled()).thenReturn(false);

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .targets(List.of("atlas"))
                .build();

        LineageTargetSinkResult result = sink.publish(LineageRecord.fromV1(event));
        assertFalse(result.success());
    }

    @SuppressWarnings("unchecked")
    @Test
    void buildAtlasPayload_hasCorrectStructure() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-input")
                .addOutputObject("bedroom", "doc-output")
                .targets(List.of("atlas"))
                .build();

        Map<String, Object> payload = sink.buildAtlasPayload(LineageRecord.fromV1(event));
        assertNotNull(payload);
        assertTrue(payload.containsKey("entities"));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) payload.get("entities");
        assertEquals(1, entities.size());

        Map<String, Object> processEntity = entities.get(0);
        assertEquals("Process", processEntity.get("typeName"));

        Map<String, Object> attrs = (Map<String, Object>) processEntity.get("attributes");
        assertNotNull(attrs.get("qualifiedName"));
        assertTrue(((String) attrs.get("qualifiedName")).startsWith("nemakiware:bedroom:"));
    }

    /**
     * A Process input/output must be the qualifiedName of the entity the catalog sync already
     * published, verbatim. The sink used to prefix it a second time:
     *
     *   event input   nemaki://bedroom/objects/doc-input
     *   sink produced nemakiware:bedroom:nemaki://bedroom/objects/doc-input
     *
     * No entity in Atlas carries that name, so every Process linked to nothing at all — and
     * nothing noticed, because until the Cloudant _id fix the projector never ran. The prefix
     * belongs to the Process's own qualifiedName, which has no other source.
     */
    @SuppressWarnings("unchecked")
    @Test
    void buildAtlasPayload_referencesInputsAndOutputsByTheirOwnQualifiedName() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-input")
                .addOutputObject("bedroom", "doc-output")
                .targets(List.of("atlas"))
                .build();

        Map<String, Object> payload = sink.buildAtlasPayload(LineageRecord.fromV1(event));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payload.get("entities");
        Map<String, Object> attrs = (Map<String, Object>) entities.get(0).get("attributes");

        List<Map<String, Object>> inputs = (List<Map<String, Object>>) attrs.get("inputs");
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) attrs.get("outputs");
        assertEquals(1, inputs.size());
        assertEquals(1, outputs.size());

        assertEquals(event.inputs().get(0),
                ((Map<String, Object>) inputs.get(0).get("uniqueAttributes")).get("qualifiedName"),
                "the input reference must match the published entity exactly");
        assertEquals(event.outputs().get(0),
                ((Map<String, Object>) outputs.get(0).get("uniqueAttributes")).get("qualifiedName"),
                "the output reference must match the published entity exactly");

        // nemaki_document extends DataSet, which is what Process.inputs/outputs accept.
        assertEquals("DataSet", inputs.get(0).get("typeName"));
        assertEquals("DataSet", outputs.get(0).get("typeName"));
    }

    /** The Process's own qualifiedName is the one place the nemakiware: prefix belongs. */
    @SuppressWarnings("unchecked")
    @Test
    void buildAtlasPayload_processQualifiedNameCarriesThePrefix() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-input")
                .targets(List.of("atlas"))
                .build();

        Map<String, Object> payload = sink.buildAtlasPayload(LineageRecord.fromV1(event));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payload.get("entities");
        Map<String, Object> attrs = (Map<String, Object>) entities.get(0).get("attributes");

        assertEquals("nemakiware:bedroom:import_uploaded:" + event.eventKey(),
                attrs.get("qualifiedName"));
    }
}
