package jp.aegif.nemaki.rest.purview.journal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataplexLineageSinkTest {

    private DataplexLineageSink sink;
    private DataplexConfig mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        sink = new DataplexLineageSink();
        mockConfig = mock(DataplexConfig.class);

        Field f = DataplexLineageSink.class.getDeclaredField("dataplexConfig");
        f.setAccessible(true);
        f.set(sink, mockConfig);
    }

    @Test
    void targetName_returnsDataplex() {
        assertEquals("dataplex", sink.targetName());
    }

    @Test
    void isAvailable_returnsFalseWhenDisabled() {
        when(mockConfig.isEnabled()).thenReturn(false);
        assertFalse(sink.isAvailable());
    }

    @Test
    void isAvailable_returnsFalseWhenProjectIdBlank() {
        when(mockConfig.isEnabled()).thenReturn(true);
        when(mockConfig.getProjectId()).thenReturn("");
        assertFalse(sink.isAvailable());
    }

    @Test
    void isAvailable_returnsTrueWhenConfigured() {
        when(mockConfig.isEnabled()).thenReturn(true);
        when(mockConfig.getProjectId()).thenReturn("my-project");
        when(mockConfig.getLocation()).thenReturn("us-central1");
        assertTrue(sink.isAvailable());
    }

    @Test
    void publish_returnsFailureWhenNotAvailable() throws Exception {
        when(mockConfig.isEnabled()).thenReturn(false);

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .addInputObject("bedroom", "folder-1")
                .targets(List.of("dataplex"))
                .build();

        LineageTargetSinkResult result = sink.publish(event);
        assertFalse(result.success());
    }

    @SuppressWarnings("unchecked")
    @Test
    void buildProcessPayload_hasCorrectStructure() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .targets(List.of("dataplex"))
                .build();

        Map<String, Object> payload = sink.buildProcessPayload(event, "test-process");
        assertEquals("test-process", payload.get("name"));
        assertNotNull(payload.get("displayName"));
        assertNotNull(payload.get("origin"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void buildEventPayload_hasLinks() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-input")
                .addOutputObject("bedroom", "doc-output")
                .targets(List.of("dataplex"))
                .build();

        Map<String, Object> payload = sink.buildEventPayload(event);
        assertNotNull(payload.get("startTime"));
        assertNotNull(payload.get("endTime"));

        List<Map<String, Object>> links = (List<Map<String, Object>>) payload.get("links");
        assertNotNull(links);
        assertEquals(1, links.size());
    }
}
