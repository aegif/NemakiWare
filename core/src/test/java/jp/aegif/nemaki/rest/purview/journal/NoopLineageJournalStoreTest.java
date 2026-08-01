package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

public class NoopLineageJournalStoreTest {

    private final NoopLineageJournalStore store = new NoopLineageJournalStore();

    @Test
    public void testAppendIsNoOp() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .addInputObject("bedroom", "doc-1")
                .build();

        // Should not throw
        store.append(event);
    }

    @Test
    public void testAppendAllIsNoOp() {
        LineageEvent event1 = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .build();
        LineageEvent event2 = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .build();

        // Should not throw
        store.appendAll(List.of(event1, event2));
    }

    @Test
    public void testFindByRepositoryIdReturnsEmptyList() {
        List<LineageJournalRow> result = store.findByRepositoryId("bedroom", 100, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testFindByProcessTypeReturnsEmptyList() {
        List<LineageJournalRow> result = store.findByProcessType("bedroom", LineageProcessType.ARCHIVE_COLD, 100, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testFindByProcessTypeGlobalReturnsEmptyList() {
        List<LineageJournalRow> result = store.findByProcessType(LineageProcessType.ARCHIVE_COLD, 100, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testUpdatePublishStatusReturnsZero() {
        int updated = store.updatePublishStatus("evt-1", "purview", LineagePublishStatus.PUBLISHED);
        assertEquals(0, updated);
    }

    @Test
    public void testPurgeOlderThanReturnsZero() {
        int purged = store.purgeOlderThan(Instant.now());
        assertEquals(0, purged);
    }

    @Test
    public void testDiscardEventReturnsZero() {
        int result = store.discardEvent("evt-1", "purview");
        assertEquals(0, result);
    }

    @Test
    public void testCountNonTerminalByTargetReturnsZero() {
        long count = store.countNonTerminalByTarget("purview");
        assertEquals(0, count);
    }

    @Test
    public void testIsActiveReturnsFalse() {
        assertFalse(store.isActive());
    }
}
