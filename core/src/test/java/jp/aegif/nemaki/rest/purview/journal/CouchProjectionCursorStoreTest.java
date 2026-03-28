package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CouchProjectionCursorStore}.
 */
class CouchProjectionCursorStoreTest {

    private CouchProjectionCursorStore cursorStore;
    private CloudantClientWrapper mockClient;
    private CouchLineageJournalStore mockJournalStore;

    @BeforeEach
    void setUp() throws Exception {
        cursorStore = new CouchProjectionCursorStore();
        mockClient = mock(CloudantClientWrapper.class);
        mockJournalStore = mock(CouchLineageJournalStore.class);

        when(mockJournalStore.isActive()).thenReturn(true);
        when(mockJournalStore.getLineageClient()).thenReturn(mockClient);

        // Inject mock journal store
        setField(cursorStore, "journalStore", mockJournalStore);
    }

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

    @SuppressWarnings("unchecked")
    @Test
    void getCursor_returnsNullWhenNotFound() {
        doReturn(null).when(mockClient).get(eq(Map.class), eq("projection_cursor:purview:bedroom"), isNull());

        ProjectionCursor cursor = cursorStore.getCursor("purview", "bedroom");
        assertNull(cursor);
    }

    @SuppressWarnings("unchecked")
    @Test
    void getCursor_returnsCursorFromDoc() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "projection_cursor:purview:bedroom");
        doc.put("type", "projection_cursor");
        doc.put("target", "purview");
        doc.put("repositoryId", "bedroom");
        doc.put("lastProcessedSequence", 42L);
        doc.put("updatedAt", Instant.now().toString());

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("projection_cursor:purview:bedroom"), isNull());

        ProjectionCursor cursor = cursorStore.getCursor("purview", "bedroom");

        assertNotNull(cursor);
        assertEquals("purview", cursor.target());
        assertEquals("bedroom", cursor.repositoryId());
        assertEquals(42, cursor.lastProcessedSequence());
    }

    @SuppressWarnings("unchecked")
    @Test
    void updateCursor_createsNewDocWhenNotExists() {
        doReturn(null).when(mockClient).get(eq(Map.class), eq("projection_cursor:purview:bedroom"), isNull());

        ProjectionCursor cursor = new ProjectionCursor("purview", "bedroom", 10, Instant.now());
        cursorStore.updateCursor(cursor);

        verify(mockClient).create(argThat(doc -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) doc;
            return "projection_cursor:purview:bedroom".equals(m.get("_id"))
                    && "projection_cursor".equals(m.get("type"))
                    && "purview".equals(m.get("target"))
                    && "bedroom".equals(m.get("repositoryId"))
                    && ((Number) m.get("lastProcessedSequence")).longValue() == 10;
        }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void updateCursor_updatesExistingDoc() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("_id", "projection_cursor:purview:bedroom");
        existing.put("_rev", "1-abc");
        existing.put("type", "projection_cursor");
        existing.put("target", "purview");
        existing.put("repositoryId", "bedroom");
        existing.put("lastProcessedSequence", 5L);

        doReturn(existing).when(mockClient).get(eq(Map.class), eq("projection_cursor:purview:bedroom"), isNull());

        ProjectionCursor cursor = new ProjectionCursor("purview", "bedroom", 15, Instant.now());
        cursorStore.updateCursor(cursor);

        verify(mockClient).update(argThat(doc -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) doc;
            return ((Number) m.get("lastProcessedSequence")).longValue() == 15;
        }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getAllCursors_returnsEmptyWhenNone() {
        when(mockClient.findBySelector(any(Map.class), eq(Map.class))).thenReturn(List.of());

        List<ProjectionCursor> cursors = cursorStore.getAllCursors();
        assertTrue(cursors.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getAllCursors_returnsParsedCursors() {
        Map<String, Object> doc1 = new LinkedHashMap<>();
        doc1.put("target", "purview");
        doc1.put("repositoryId", "bedroom");
        doc1.put("lastProcessedSequence", 10L);
        doc1.put("updatedAt", Instant.now().toString());

        Map<String, Object> doc2 = new LinkedHashMap<>();
        doc2.put("target", "atlas");
        doc2.put("repositoryId", "canopy");
        doc2.put("lastProcessedSequence", 5L);
        doc2.put("updatedAt", Instant.now().toString());

        when(mockClient.findBySelector(any(Map.class), eq(Map.class))).thenReturn(List.of(doc1, doc2));

        List<ProjectionCursor> cursors = cursorStore.getAllCursors();
        assertEquals(2, cursors.size());
        assertEquals("purview", cursors.get(0).target());
        assertEquals("atlas", cursors.get(1).target());
    }
}
