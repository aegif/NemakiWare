package jp.aegif.nemaki.rest.purview.journal;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CouchLineageJournalStore} and {@link CouchLineageEvent}.
 *
 * <p>Uses mocked {@link CloudantClientWrapper} to verify store logic
 * without requiring a live CouchDB instance. Uses {@code doReturn().when()}
 * pattern to avoid Mockito/Java overload ambiguity with CloudantClientWrapper's
 * multiple queryView/get signatures.
 */
class CouchLineageJournalStoreTest {

    private CouchLineageJournalStore store;
    private CloudantClientWrapper mockClient;
    private LineageConfig mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        store = new CouchLineageJournalStore();
        mockClient = mock(CloudantClientWrapper.class);
        mockConfig = mock(LineageConfig.class);

        when(mockConfig.getMode()).thenReturn(LineageMode.JOURNALED);

        // Inject mocks via reflection (store uses @Autowired fields)
        setField(store, "lineageClient", mockClient);
        setField(store, "lineageConfig", mockConfig);
        setField(store, "dbProvisioned", new AtomicBoolean(true));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Helper: create a Cloudant SDK {@link Document} with custom properties.
     * {@code Document} extends {@code DynamicModel<Object>} (HashMap-backed).
     * We put ALL properties (including _id/_rev) via setProperties() so they
     * are accessible via the HashMap interface when the Document is cast to
     * {@code Map<String, Object>} in store code.
     */
    private static Document toDocument(Map<String, Object> props) {
        Document doc = new Document();
        // Put all properties (including _id, _rev) into the HashMap backing
        doc.setProperties(new HashMap<>(props));
        return doc;
    }

    // ---------------------------------------------------------------
    // Document → CouchLineageEvent conversion test
    // ---------------------------------------------------------------

    @Test
    void document_to_couchLineageEvent_via_getProperties() {
        Map<String, Object> docProps = new HashMap<>();
        docProps.put("eventId", "evt-1");
        docProps.put("type", "lineage_event");
        docProps.put("repositoryId", "bedroom");
        docProps.put("occurredAt", "2024-01-01T00:00:00Z");
        docProps.put("processType", "ARCHIVE_COLD");
        Map<String, String> statusMap = new LinkedHashMap<>();
        statusMap.put("purview", "PUBLISHED");
        docProps.put("publishStatusByTarget", statusMap);

        Document doc = toDocument(docProps);

        // Document does NOT implement Map — use getProperties() instead
        Map<String, Object> props = new HashMap<>();
        if (doc.getId() != null) props.put("_id", doc.getId());
        if (doc.getRev() != null) props.put("_rev", doc.getRev());
        if (doc.getProperties() != null) props.putAll(doc.getProperties());

        assertNotNull(props.get("eventId"), "eventId should be present");
        assertEquals("evt-1", props.get("eventId"));
        assertNotNull(props.get("publishStatusByTarget"), "publishStatusByTarget should be present");

        CouchLineageEvent couchEvent = new CouchLineageEvent(props);
        LineageEvent event = couchEvent.toLineageEvent();

        assertEquals("evt-1", event.eventId());
        assertFalse(event.publishStatusByTarget().isEmpty());
        assertEquals(LineagePublishStatus.PUBLISHED, event.publishStatusByTarget().get("purview"));
        assertTrue(event.publishStatusByTarget().values().stream()
                .allMatch(LineagePublishStatus::isTerminal));
    }

    // ---------------------------------------------------------------
    // CouchLineageEvent round-trip tests
    // ---------------------------------------------------------------

    @Test
    void couchLineageEvent_roundTrip() {
        LineageEvent original = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .addInputObject("bedroom", "doc-1")
                .addOutputObject("bedroom", "archive-1")
                .targets(List.of("purview"))
                .build();

        CouchLineageEvent couch = new CouchLineageEvent(original);
        assertEquals("lineage:" + original.eventId(), couch.getId());
        assertEquals("lineage_event", couch.getType());
        assertEquals(original.eventKey(), couch.getEventKey());
        assertEquals("PENDING", couch.getPublishStatusByTarget().get("purview"));

        // Round-trip to Map and back
        Map<String, Object> map = couch.toMap();
        CouchLineageEvent restored = new CouchLineageEvent(map);
        LineageEvent converted = restored.toLineageEvent();

        assertEquals(original.eventId(), converted.eventId());
        assertEquals(original.eventKey(), converted.eventKey());
        assertEquals(original.repositoryId(), converted.repositoryId());
        assertEquals(original.processType(), converted.processType());
        assertEquals(original.inputs(), converted.inputs());
        assertEquals(original.outputs(), converted.outputs());
        assertEquals(LineagePublishStatus.PENDING, converted.publishStatusByTarget().get("purview"));
    }

    // ---------------------------------------------------------------
    // append tests
    // ---------------------------------------------------------------

    @Test
    void append_assignsSequenceNumber() {
        // eventKey does not exist
        ViewResult emptyResult = mock(ViewResult.class);
        when(emptyResult.getRows()).thenReturn(List.of());
        doReturn(emptyResult).when(mockClient).queryView(eq("lineage"), eq("by_event_key"), any(Map.class));

        // Sequence counter doesn't exist → create with seq=1
        doReturn(null).when(mockClient).get(eq(Map.class), eq("lineage_seq:bedroom"), isNull());
        DocumentResult createResult = mock(DocumentResult.class);
        when(createResult.getId()).thenReturn("lineage_seq:bedroom");
        doReturn(createResult).when(mockClient).create(any(Map.class));

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .build();

        assertDoesNotThrow(() -> store.append(event));

        // Verify event was created (at least 2 creates: seq counter + event doc)
        verify(mockClient, atLeast(2)).create(any(Map.class));
    }

    @Test
    void append_skipsOnDuplicateEventKey() {
        // eventKey already exists
        ViewResult existingResult = mock(ViewResult.class);
        ViewResultRow row = mock(ViewResultRow.class);
        when(existingResult.getRows()).thenReturn(List.of(row));
        doReturn(existingResult).when(mockClient).queryView(eq("lineage"), eq("by_event_key"), any(Map.class));

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .build();

        assertDoesNotThrow(() -> store.append(event));

        // Verify no document creation was attempted
        verify(mockClient, never()).create(any(Map.class));
    }

    @Test
    void append_retriesOnCasConflict() {
        // eventKey does not exist
        ViewResult emptyResult = mock(ViewResult.class);
        when(emptyResult.getRows()).thenReturn(List.of());
        doReturn(emptyResult).when(mockClient).queryView(eq("lineage"), eq("by_event_key"), any(Map.class));

        // First read: seq counter exists at seq=5
        Map<String, Object> seqDoc = new HashMap<>();
        seqDoc.put("_id", "lineage_seq:bedroom");
        seqDoc.put("_rev", "1-abc");
        seqDoc.put("seq", 5L);

        Map<String, Object> seqDoc2 = new HashMap<>();
        seqDoc2.put("_id", "lineage_seq:bedroom");
        seqDoc2.put("_rev", "2-def");
        seqDoc2.put("seq", 6L);

        doReturn(seqDoc).doReturn(seqDoc2)
                .when(mockClient).get(eq(Map.class), eq("lineage_seq:bedroom"), isNull());

        // First update fails (CAS conflict), second succeeds
        DocumentResult failResult = mock(DocumentResult.class);
        when(failResult.isOk()).thenReturn(false);
        DocumentResult successResult = mock(DocumentResult.class);
        when(successResult.isOk()).thenReturn(true);
        when(mockClient.update(any(Map.class)))
                .thenReturn(failResult)
                .thenReturn(successResult);

        // Event creation succeeds
        DocumentResult eventResult = mock(DocumentResult.class);
        when(eventResult.getId()).thenReturn("lineage:test-id");
        doReturn(eventResult).when(mockClient).create(any(Map.class));

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .addInputObject("bedroom", "folder-1")
                .build();

        assertDoesNotThrow(() -> store.append(event));

        // Verify update was called at least twice (first fail, then success)
        verify(mockClient, atLeast(2)).update(any(Map.class));
    }

    // ---------------------------------------------------------------
    // updatePublishStatus tests
    // ---------------------------------------------------------------

    @Test
    void updatePublishStatus_returnsOneOnSuccess() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("_id", "lineage:evt-1");
        doc.put("_rev", "1-abc");
        Map<String, String> statusMap = new LinkedHashMap<>();
        statusMap.put("purview", "PENDING");
        doc.put("publishStatusByTarget", statusMap);

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage:evt-1"), isNull());
        DocumentResult result = mock(DocumentResult.class);
        when(result.isOk()).thenReturn(true);
        when(mockClient.update(any(Map.class))).thenReturn(result);

        int updated = store.updatePublishStatus("evt-1", "purview", LineagePublishStatus.PROJECTING);
        assertEquals(1, updated);
    }

    @Test
    void updatePublishStatus_returnsZeroWhenNotFound() {
        doReturn(null).when(mockClient).get(eq(Map.class), eq("lineage:evt-missing"), isNull());
        int updated = store.updatePublishStatus("evt-missing", "purview", LineagePublishStatus.PUBLISHED);
        assertEquals(0, updated);
    }

    // ---------------------------------------------------------------
    // purgeOlderThan tests
    // ---------------------------------------------------------------

    @Test
    void purgeOlderThan_skipsNonTerminalEvents() {
        ViewResult viewResult = mock(ViewResult.class);
        ViewResultRow row = mock(ViewResultRow.class);

        Map<String, Object> docProps = new HashMap<>();
        docProps.put("eventId", "evt-1");
        docProps.put("type", "lineage_event");
        docProps.put("repositoryId", "bedroom");
        docProps.put("occurredAt", "2024-01-01T00:00:00Z");
        docProps.put("processType", "ARCHIVE_COLD");
        Map<String, String> statusMap = new LinkedHashMap<>();
        statusMap.put("purview", "PENDING"); // non-terminal
        docProps.put("publishStatusByTarget", statusMap);

        when(row.getDoc()).thenReturn(toDocument(docProps));
        when(viewResult.getRows()).thenReturn(List.of(row));
        doReturn(viewResult).when(mockClient).queryView(eq("lineage"), eq("by_repository_and_time"), any(Map.class));

        int purged = store.purgeOlderThan(Instant.now());
        assertEquals(0, purged);

        verify(mockClient, never()).delete(anyString(), anyString());
    }

    @Test
    void purgeOlderThan_deletesTerminalEvents() {
        ViewResult viewResult = mock(ViewResult.class);
        ViewResultRow row = mock(ViewResultRow.class);

        Map<String, Object> docProps = new HashMap<>();
        docProps.put("eventId", "evt-1");
        docProps.put("type", "lineage_event");
        docProps.put("repositoryId", "bedroom");
        docProps.put("occurredAt", "2024-01-01T00:00:00Z");
        docProps.put("processType", "ARCHIVE_COLD");
        Map<String, String> statusMap = new LinkedHashMap<>();
        statusMap.put("purview", "PUBLISHED"); // terminal
        docProps.put("publishStatusByTarget", statusMap);

        when(row.getDoc()).thenReturn(toDocument(docProps));
        when(viewResult.getRows()).thenReturn(List.of(row));
        doReturn(viewResult).when(mockClient).queryView(eq("lineage"), eq("by_repository_and_time"), any(Map.class));

        // Return doc with _rev for deletion
        Map<String, Object> fullDoc = new HashMap<>(docProps);
        fullDoc.put("_id", "lineage:evt-1");
        fullDoc.put("_rev", "1-abc");
        doReturn(fullDoc).when(mockClient).get(eq(Map.class), eq("lineage:evt-1"), isNull());

        DocumentResult deleteResult = mock(DocumentResult.class);
        when(deleteResult.isOk()).thenReturn(true);
        when(mockClient.delete("lineage:evt-1", "1-abc")).thenReturn(deleteResult);

        int purged = store.purgeOlderThan(Instant.now());
        assertEquals(1, purged);
    }

    // ---------------------------------------------------------------
    // discardEvent tests
    // ---------------------------------------------------------------

    @Test
    void discardEvent_transitionsFromPendingToDiscarded() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("_id", "lineage:evt-1");
        doc.put("_rev", "1-abc");
        Map<String, String> statusMap = new LinkedHashMap<>();
        statusMap.put("purview", "PENDING");
        doc.put("publishStatusByTarget", statusMap);

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage:evt-1"), isNull());
        DocumentResult result = mock(DocumentResult.class);
        when(result.isOk()).thenReturn(true);
        when(mockClient.update(any(Map.class))).thenReturn(result);

        int discarded = store.discardEvent("evt-1", "purview");
        assertEquals(1, discarded);

        verify(mockClient).update(argThat(map -> {
            @SuppressWarnings("unchecked")
            Map<String, String> ps = (Map<String, String>) ((Map<String, Object>) map).get("publishStatusByTarget");
            return "DISCARDED".equals(ps.get("purview"));
        }));
    }

    @Test
    void discardEvent_rejectsTerminalState() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("_id", "lineage:evt-1");
        doc.put("_rev", "1-abc");
        Map<String, String> statusMap = new LinkedHashMap<>();
        statusMap.put("purview", "PUBLISHED"); // already terminal
        doc.put("publishStatusByTarget", statusMap);

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage:evt-1"), isNull());

        int discarded = store.discardEvent("evt-1", "purview");
        assertEquals(0, discarded);

        verify(mockClient, never()).update(any(Map.class));
    }

    // ---------------------------------------------------------------
    // countNonTerminalByTarget tests
    // ---------------------------------------------------------------

    @Test
    void countNonTerminalByTarget_queriesView() {
        ViewResult countResult = mock(ViewResult.class);
        ViewResultRow countRow = mock(ViewResultRow.class);
        when(countRow.getValue()).thenReturn(2L);
        when(countResult.getRows()).thenReturn(List.of(countRow));
        doReturn(countResult).when(mockClient).queryView(eq("lineage"), eq("by_target_status"), any(Map.class));

        // 3 non-terminal statuses (PENDING, PROJECTING, FAILED) × 2 each = 6
        long count = store.countNonTerminalByTarget("purview");
        assertEquals(6, count);
    }

    // ---------------------------------------------------------------
    // isActive tests
    // ---------------------------------------------------------------

    @Test
    void isActive_returnsTrueWhenDbProvisioned() {
        assertTrue(store.isActive());
    }

    @Test
    void isActive_returnsFalseWhenModeIsDisabled() {
        when(mockConfig.getMode()).thenReturn(LineageMode.DISABLED);
        assertFalse(store.isActive());
    }
}
