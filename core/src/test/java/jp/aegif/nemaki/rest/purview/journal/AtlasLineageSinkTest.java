package jp.aegif.nemaki.rest.purview.journal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpResponse;
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

        Map<String, Object> payload = sink.buildAtlasPayload(event);
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

        Map<String, Object> payload = sink.buildAtlasPayload(event);
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payload.get("entities");
        Map<String, Object> attrs = (Map<String, Object>) entities.get(0).get("attributes");

        assertEquals("nemakiware:bedroom:import_uploaded:" + event.eventKey(),
                attrs.get("qualifiedName"));
    }

    /**
     * Fail closed: an event whose endpoints Atlas will not resolve as a DataSet must not be
     * published. Atlas accepts a bulk write with a dangling reference — it either links the
     * Process to nothing or invents a shell entity — so "it returned 2xx" is not evidence the
     * lineage graph is right. Folders (Referenceable, not DataSet) and the upload:// file://
     * cloud:// cold:// endpoints real producers emit are all in this category.
     */
    @Test
    void publish_failsClosedWhenAnEndpointIsNotADataSet() throws Exception {
        when(mockConfig.isEnabled()).thenReturn(true);
        when(mockConfig.getEndpoint()).thenReturn("http://atlas:21000");

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .addInputObject("bedroom", "folder-not-a-dataset")
                .targets(List.of("atlas"))
                .build();

        // A sink whose Atlas answers 404 for the endpoint lookup and 200 for anything else.
        SimpleHttpClient client = mock(SimpleHttpClient.class);
        when(client.withBasicAuth(any(), any())).thenReturn(client);
        HttpResponse<String> notFound = mock(HttpResponse.class);
        when(notFound.statusCode()).thenReturn(404);
        when(client.getJson(anyString())).thenReturn(notFound);
        Field hc = AtlasLineageSink.class.getDeclaredField("httpClient");
        hc.setAccessible(true);
        hc.set(sink, client);

        LineageTargetSinkResult result = sink.publish(event);

        assertFalse(result.success(), "an unrepresentable endpoint must not publish as success");
        assertTrue(result.message().contains("folder-not-a-dataset"),
                "the failure must name the endpoint that could not be resolved: " + result.message());
        verify(client, never()).postJson(anyString(), any());
    }

    /** Resolvable endpoints still publish. */
    @Test
    void publish_proceedsWhenEveryEndpointResolves() throws Exception {
        when(mockConfig.isEnabled()).thenReturn(true);
        when(mockConfig.getEndpoint()).thenReturn("http://atlas:21000");

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-input")
                .targets(List.of("atlas"))
                .build();

        SimpleHttpClient client = mock(SimpleHttpClient.class);
        when(client.withBasicAuth(any(), any())).thenReturn(client);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{}");
        when(client.getJson(anyString())).thenReturn(ok);
        when(client.postJson(anyString(), any())).thenReturn(ok);
        Field hc = AtlasLineageSink.class.getDeclaredField("httpClient");
        hc.setAccessible(true);
        hc.set(sink, client);

        LineageTargetSinkResult result = sink.publish(event);

        assertTrue(result.success(), "a fully resolvable event must still publish: " + result.message());
        verify(client).postJson(anyString(), any());
    }
}
