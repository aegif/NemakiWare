package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CouchLineageDeadLetterStore}.
 */
class CouchLineageDeadLetterStoreTest {

    private CouchLineageDeadLetterStore deadLetterStore;
    private CloudantClientWrapper mockClient;
    private CouchLineageJournalStore mockJournalStore;
    private LineageConfig mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        deadLetterStore = new CouchLineageDeadLetterStore();
        mockClient = mock(CloudantClientWrapper.class);
        mockJournalStore = mock(CouchLineageJournalStore.class);
        mockConfig = mock(LineageConfig.class);

        when(mockJournalStore.isActive()).thenReturn(true);
        when(mockJournalStore.getLineageClient()).thenReturn(mockClient);

        setField(deadLetterStore, "journalStore", mockJournalStore);
        setField(deadLetterStore, "lineageConfig", mockConfig);
    }

    // ==================== record ====================

    @Test
    void record_createsDocWhenNotExists() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", "doc-1")
                .addOutputObject("bedroom", "arc-1")
                .build();

        when(mockClient.exists("lineage_dl:" + event.eventId())).thenReturn(false);

        deadLetterStore.record(event, "timeout");

        verify(mockClient).create(argThat(doc -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) doc;
            return "lineage_dead_letter".equals(m.get("type"))
                    && event.eventId().equals(m.get("eventId"))
                    && "bedroom".equals(m.get("repositoryId"))
                    && "timeout".equals(m.get("reason"))
                    && Boolean.FALSE.equals(m.get("replayed"));
        }));
    }

    @Test
    void record_skipsDuplicateDoc() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .build();

        when(mockClient.exists("lineage_dl:" + event.eventId())).thenReturn(true);

        deadLetterStore.record(event, "timeout");

        verify(mockClient, never()).create(any());
    }

    @Test
    void record_handlesNullClient() {
        when(mockJournalStore.isActive()).thenReturn(false);
        when(mockJournalStore.getLineageClient()).thenReturn(null);

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .build();

        // Should not throw
        deadLetterStore.record(event, "timeout");
        verify(mockClient, never()).create(any());
    }

    // ==================== findByEventId ====================

    @SuppressWarnings("unchecked")
    @Test
    void findByEventId_returnsDocWithoutRev() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_dl:evt-1");
        doc.put("_rev", "1-abc");
        doc.put("eventId", "evt-1");
        doc.put("reason", "timeout");

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-1"), isNull());

        Map<String, Object> result = deadLetterStore.findByEventId("evt-1");

        assertNotNull(result);
        assertEquals("evt-1", result.get("eventId"));
        assertNull(result.get("_rev"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void findByEventId_returnsNullWhenNotFound() {
        doReturn(null).when(mockClient).get(eq(Map.class), eq("lineage_dl:nonexistent"), isNull());

        Map<String, Object> result = deadLetterStore.findByEventId("nonexistent");
        assertNull(result);
    }

    // ==================== replay ====================

    @SuppressWarnings("unchecked")
    @Test
    void replay_reappendsAndMarksReplayed() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_dl:evt-1");
        doc.put("_rev", "1-abc");
        doc.put("eventId", "evt-1");
        doc.put("eventKey", "bedroom:ARCHIVE_LOCAL:doc-1:arc-1");
        doc.put("repositoryId", "bedroom");
        doc.put("processType", "ARCHIVE_LOCAL");
        doc.put("occurredAt", "2026-01-15T10:00:00Z");
        doc.put("inputs", List.of("nemaki://bedroom/objects/doc-1"));
        doc.put("outputs", List.of("nemaki://bedroom/archives/arc-1"));
        doc.put("snapshotAttributes", Map.of("name", "test.txt"));
        doc.put("replayed", false);

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-1"), isNull());

        boolean result = deadLetterStore.replay("evt-1", targetStore);

        assertTrue(result);
        verify(targetStore).append(any(LineageEvent.class));
        verify(mockClient).update(argThat(updated -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) updated;
            return Boolean.TRUE.equals(m.get("replayed"))
                    && m.get("replayedAt") != null;
        }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_returnsFalseWhenAlreadyReplayed() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_dl:evt-1");
        doc.put("replayed", true);

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-1"), isNull());

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        boolean result = deadLetterStore.replay("evt-1", targetStore);

        assertFalse(result);
        verify(targetStore, never()).append(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_returnsFalseWhenNotFound() {
        doReturn(null).when(mockClient).get(eq(Map.class), eq("lineage_dl:nonexistent"), isNull());

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        boolean result = deadLetterStore.replay("nonexistent", targetStore);

        assertFalse(result);
    }

    // ==================== count ====================

    @Test
    void count_returnsZeroWhenClientNull() {
        when(mockJournalStore.isActive()).thenReturn(false);
        when(mockJournalStore.getLineageClient()).thenReturn(null);

        long count = deadLetterStore.count(null);
        assertEquals(0, count);
    }

    @Test
    void count_returnsSumFromViewWithReplayedFilter() {
        ViewResult viewResult = mock(ViewResult.class);
        ViewResultRow row1 = mock(ViewResultRow.class);
        when(row1.getValue()).thenReturn(3L);
        when(viewResult.getRows()).thenReturn(List.of(row1));

        when(mockClient.queryView(eq("lineage"), eq("dead_letter_by_replayed"), any(Map.class)))
                .thenReturn(viewResult);

        long count = deadLetterStore.count(false);
        assertEquals(3, count);
    }

    @Test
    void count_returnsSumFromViewWithoutFilter() {
        ViewResult viewResult = mock(ViewResult.class);
        ViewResultRow row1 = mock(ViewResultRow.class);
        ViewResultRow row2 = mock(ViewResultRow.class);
        when(row1.getValue()).thenReturn(5L);
        when(row2.getValue()).thenReturn(3L);
        when(viewResult.getRows()).thenReturn(List.of(row1, row2));

        when(mockClient.queryView(eq("lineage"), eq("dead_letter_by_replayed"), any(Map.class)))
                .thenReturn(viewResult);

        long count = deadLetterStore.count(null);
        assertEquals(8, count);
    }

    // ==================== purgeReplayed ====================

    @SuppressWarnings("unchecked")
    @Test
    void purgeReplayed_deletesReplayedDocs() {
        // findAll returns replayed records
        ViewResult viewResult = mock(ViewResult.class);
        ViewResultRow row = mock(ViewResultRow.class);
        com.ibm.cloud.cloudant.v1.model.Document sdkDoc = mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        when(sdkDoc.getId()).thenReturn("lineage_dl:evt-1");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("eventId", "evt-1");
        when(sdkDoc.getProperties()).thenReturn(props);
        when(row.getDoc()).thenReturn(sdkDoc);
        when(viewResult.getRows()).thenReturn(List.of(row));
        when(mockClient.queryView(eq("lineage"), eq("dead_letter_by_replayed"), any(Map.class)))
                .thenReturn(viewResult);

        // get returns the doc with _rev for deletion
        Map<String, Object> fullDoc = new LinkedHashMap<>();
        fullDoc.put("_id", "lineage_dl:evt-1");
        fullDoc.put("_rev", "2-def");
        doReturn(fullDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-1"), isNull());

        int purged = deadLetterStore.purgeReplayed();

        assertEquals(1, purged);
        verify(mockClient).delete("lineage_dl:evt-1", "2-def");
    }

    // ==================== isActive ====================

    @Test
    void isActive_returnsTrueWhenClientAvailable() {
        assertTrue(deadLetterStore.isActive());
    }

    @Test
    void isActive_returnsFalseWhenJournalStoreInactive() {
        when(mockJournalStore.isActive()).thenReturn(false);
        when(mockJournalStore.getLineageClient()).thenReturn(null);

        assertFalse(deadLetterStore.isActive());
    }

    // ==================== Helpers ====================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                var field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
