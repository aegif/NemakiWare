package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class LineageEventTest {

    @Test
    public void testRecordImmutability() {
        LineageEvent event = new LineageEvent(
                1, "evt-1", "key-1", 10L, "2026-03-28T00:00:00Z", "bedroom",
                LineageProcessType.ARCHIVE_COLD,
                List.of("nemaki://bedroom/objects/doc1"),
                List.of("nemaki://bedroom/objects/arc1"),
                "run-1", "corr-1", 1,
                Map.of("name", "test.pdf"),
                Map.of("purview", LineagePublishStatus.PENDING));

        assertThrows(UnsupportedOperationException.class,
                () -> event.inputs().add("should-fail"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.outputs().add("should-fail"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.snapshotAttributes().put("k", "v"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.publishStatusByTarget().put("atlas", LineagePublishStatus.PENDING));
    }

    @Test
    public void testNullSafeDefaults() {
        LineageEvent event = new LineageEvent(
                0, null, null, 0L, null, null, LineageProcessType.IMPORT_UPLOADED,
                null, null, null, null, 0, null, null);

        assertEquals(0, event.schemaVersion());
        assertEquals("", event.eventId());
        assertEquals("", event.eventKey());
        assertEquals(0L, event.sequenceNumber());
        assertEquals("", event.occurredAt());
        assertEquals("", event.repositoryId());
        assertTrue(event.inputs().isEmpty());
        assertTrue(event.outputs().isEmpty());
        assertEquals("", event.runId());
        assertEquals("", event.correlationId());
        assertEquals(0, event.version());
        assertTrue(event.snapshotAttributes().isEmpty());
        assertTrue(event.publishStatusByTarget().isEmpty());
    }

    @Test
    public void testQualifiedNameFormat() {
        String qn = LineageEvent.qualifiedName("bedroom", "doc-123");
        assertEquals("nemaki://bedroom/objects/doc-123", qn);
    }

    @Test
    public void testFieldAccessors() {
        LineageEvent event = new LineageEvent(
                1, "e1", "key-e1", 42L, "2026-01-01T00:00:00Z", "canopy",
                LineageProcessType.EXPORT_ZIP_FOLDER,
                List.of("nemaki://canopy/objects/f1"),
                List.of("nemaki://canopy/objects/f2"),
                "run-x", "corr-y", 2,
                Map.of("mimeType", "application/zip"),
                Map.of("dataplex", LineagePublishStatus.SKIPPED));

        assertEquals(1, event.schemaVersion());
        assertEquals("e1", event.eventId());
        assertEquals("key-e1", event.eventKey());
        assertEquals(42L, event.sequenceNumber());
        assertEquals("canopy", event.repositoryId());
        assertEquals(LineageProcessType.EXPORT_ZIP_FOLDER, event.processType());
        assertEquals(1, event.inputs().size());
        assertEquals(1, event.outputs().size());
        assertEquals("run-x", event.runId());
        assertEquals("corr-y", event.correlationId());
        assertEquals(2, event.version());
        assertEquals("application/zip", event.snapshotAttributes().get("mimeType"));
        assertEquals(LineagePublishStatus.SKIPPED, event.publishStatusByTarget().get("dataplex"));
    }

    @Test
    public void testComputeEventKeyDeterministic() {
        String key1 = LineageEvent.computeEventKey("bedroom", LineageProcessType.ARCHIVE_COLD,
                List.of("nemaki://bedroom/objects/doc1"), List.of("nemaki://bedroom/objects/arc1"));
        String key2 = LineageEvent.computeEventKey("bedroom", LineageProcessType.ARCHIVE_COLD,
                List.of("nemaki://bedroom/objects/doc1"), List.of("nemaki://bedroom/objects/arc1"));
        assertEquals(key1, key2);
    }

    @Test
    public void testComputeEventKeyOrderIndependent() {
        String key1 = LineageEvent.computeEventKey("bedroom", LineageProcessType.CLOUD_SYNC_DOWNLOAD,
                List.of("nemaki://bedroom/objects/a", "nemaki://bedroom/objects/b"),
                List.of());
        String key2 = LineageEvent.computeEventKey("bedroom", LineageProcessType.CLOUD_SYNC_DOWNLOAD,
                List.of("nemaki://bedroom/objects/b", "nemaki://bedroom/objects/a"),
                List.of());
        assertEquals(key1, key2);
    }

    @Test
    public void testComputeEventKeyDiffersByContent() {
        String key1 = LineageEvent.computeEventKey("bedroom", LineageProcessType.ARCHIVE_COLD,
                List.of("nemaki://bedroom/objects/doc1"), List.of());
        String key2 = LineageEvent.computeEventKey("bedroom", LineageProcessType.ARCHIVE_COLD,
                List.of("nemaki://bedroom/objects/doc2"), List.of());
        assertFalse(key1.equals(key2));
    }

    @Test
    public void testBuilderGeneratesSchemaVersionAndEventKey() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .addInputObject("bedroom", "doc-1")
                .addOutputObject("bedroom", "arc-1")
                .build();

        assertEquals(LineageEvent.CURRENT_SCHEMA_VERSION, event.schemaVersion());
        assertNotNull(event.eventKey());
        assertFalse(event.eventKey().isEmpty());
        assertTrue(event.eventKey().startsWith("bedroom:ARCHIVE_COLD:"));
        assertEquals(1, event.version());
        assertEquals(0L, event.sequenceNumber()); // default, assigned by store
    }

    @Test
    public void testBuilderSequenceNumber() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .sequenceNumber(100L)
                .build();

        assertEquals(100L, event.sequenceNumber());
    }

    @Test
    public void testCurrentSchemaVersion() {
        assertEquals(1, LineageEvent.CURRENT_SCHEMA_VERSION);
    }
}
