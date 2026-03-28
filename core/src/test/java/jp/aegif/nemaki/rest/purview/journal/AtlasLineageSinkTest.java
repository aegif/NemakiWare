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

        LineageTargetSinkResult result = sink.publish(event);
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

        Map<String, Object> payload = sink.buildAtlasPayload(event);
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
}
