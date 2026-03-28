package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

public class LineageEventBuilderTest {

    @Test
    public void testAddInputObjectGeneratesQualifiedName() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .addInputObject("bedroom", "doc-1")
                .addOutputObject("bedroom", "arc-1")
                .build();

        assertEquals(1, event.inputs().size());
        assertEquals("nemaki://bedroom/objects/doc-1", event.inputs().get(0));
        assertEquals(1, event.outputs().size());
        assertEquals("nemaki://bedroom/objects/arc-1", event.outputs().get(0));
    }

    @Test
    public void testTargetsInitializeAllAsPending() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .targets(List.of("purview", "atlas", "dataplex"))
                .build();

        assertEquals(3, event.publishStatusByTarget().size());
        assertEquals(LineagePublishStatus.PENDING, event.publishStatusByTarget().get("purview"));
        assertEquals(LineagePublishStatus.PENDING, event.publishStatusByTarget().get("atlas"));
        assertEquals(LineagePublishStatus.PENDING, event.publishStatusByTarget().get("dataplex"));
    }

    @Test
    public void testBuildGeneratesEventIdAndOccurredAt() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.CLOUD_SYNC_UPLOAD)
                .build();

        assertNotNull(event.eventId());
        assertFalse(event.eventId().isEmpty());
        assertNotNull(event.occurredAt());
        assertFalse(event.occurredAt().isEmpty());
    }

    @Test
    public void testSnapshotAttributes() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_SELECTED_OBJECTS)
                .snapshotAttribute("name", "report.pdf")
                .snapshotAttribute("mimeType", "application/pdf")
                .build();

        assertEquals("report.pdf", event.snapshotAttributes().get("name"));
        assertEquals("application/pdf", event.snapshotAttributes().get("mimeType"));
    }

    @Test
    public void testRunIdAndCorrelationId() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("canopy")
                .processType(LineageProcessType.IMPORT_FILESYSTEM)
                .runId("run-abc")
                .correlationId("corr-xyz")
                .build();

        assertEquals("run-abc", event.runId());
        assertEquals("corr-xyz", event.correlationId());
        assertEquals("canopy", event.repositoryId());
    }

    @Test
    public void testNullTargetsIsHandled() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .targets(null)
                .build();

        assertTrue(event.publishStatusByTarget().isEmpty());
    }

    @Test
    public void testTruncateShortensLongStrings() {
        assertEquals("abc", LineageEventBuilder.truncate("abcdef", 3));
        assertEquals("abcdef", LineageEventBuilder.truncate("abcdef", 100));
        assertEquals(null, LineageEventBuilder.truncate(null, 10));
        assertEquals("abcdef", LineageEventBuilder.truncate("abcdef", 0));
    }

    @Test
    public void testEventKeyIsGenerated() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .addInputObject("bedroom", "doc-1")
                .build();

        assertNotNull(event.eventKey());
        assertTrue(event.eventKey().startsWith("bedroom:ARCHIVE_COLD:"));
    }

    @Test
    public void testVersionDefaultsToOne() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .build();

        assertEquals(1, event.version());
    }

    @Test
    public void testVersionCanBeSet() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .version(3)
                .build();

        assertEquals(3, event.version());
    }

    @Test
    public void testMultipleInputsAndOutputs() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.CLOUD_SYNC_DOWNLOAD)
                .addInputObject("bedroom", "doc-1")
                .addInputObject("bedroom", "doc-2")
                .addOutputObject("bedroom", "sync-1")
                .addOutputObject("bedroom", "sync-2")
                .addOutputObject("bedroom", "sync-3")
                .build();

        assertEquals(2, event.inputs().size());
        assertEquals(3, event.outputs().size());
    }

    @Test
    public void testSnapshotNameTruncationAddsAuditTrail() throws Exception {
        LineageConfig config = newConfig(5, 2048, true);
        String longName = "abcdefghij"; // 10 chars, limit 5

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .snapshotName(longName, config)
                .build();

        assertEquals("abcde", event.snapshotAttributes().get("name"));
        assertEquals("true", event.snapshotAttributes().get("name_truncated"));
        assertNotNull(event.snapshotAttributes().get("name_hash"));
        assertEquals(64, event.snapshotAttributes().get("name_hash").length()); // SHA-256 hex
    }

    @Test
    public void testSnapshotNameNoTruncationNoAuditTrail() throws Exception {
        LineageConfig config = newConfig(512, 2048, true);
        String shortName = "report.pdf";

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .snapshotName(shortName, config)
                .build();

        assertEquals("report.pdf", event.snapshotAttributes().get("name"));
        assertNull(event.snapshotAttributes().get("name_truncated"));
        assertNull(event.snapshotAttributes().get("name_hash"));
    }

    @Test
    public void testSnapshotFolderPathTruncationAddsAuditTrail() throws Exception {
        LineageConfig config = newConfig(512, 10, true);
        String longPath = "/very/long/path/to/folder/somewhere"; // > 10 chars

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .snapshotFolderPath(longPath, config)
                .build();

        assertEquals("/very/long", event.snapshotAttributes().get("folderPath"));
        assertEquals("true", event.snapshotAttributes().get("folderPath_truncated"));
        assertNotNull(event.snapshotAttributes().get("folderPath_hash"));
        assertEquals(64, event.snapshotAttributes().get("folderPath_hash").length());
    }

    @Test
    public void testSnapshotFolderPathNoTruncationNoAuditTrail() throws Exception {
        LineageConfig config = newConfig(512, 2048, true);
        String shortPath = "/docs/reports";

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .snapshotFolderPath(shortPath, config)
                .build();

        assertEquals("/docs/reports", event.snapshotAttributes().get("folderPath"));
        assertNull(event.snapshotAttributes().get("folderPath_truncated"));
        assertNull(event.snapshotAttributes().get("folderPath_hash"));
    }

    @Test
    public void testSnapshotFolderPathSkippedWhenCaptureDisabled() throws Exception {
        LineageConfig config = newConfig(512, 2048, false);

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .snapshotFolderPath("/some/path", config)
                .build();

        assertNull(event.snapshotAttributes().get("folderPath"));
    }

    @Test
    public void testSha256HexIsDeterministic() {
        String hash1 = LineageEventBuilder.sha256Hex("hello world");
        String hash2 = LineageEventBuilder.sha256Hex("hello world");
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
    }

    @Test
    public void testSha256HexDiffersForDifferentInputs() {
        String hash1 = LineageEventBuilder.sha256Hex("hello");
        String hash2 = LineageEventBuilder.sha256Hex("world");
        assertFalse(hash1.equals(hash2));
    }

    private static LineageConfig newConfig(int maxNameLength, int maxPathLength, boolean capturePath) throws Exception {
        LineageConfig config = new LineageConfig();
        setField(config, "mode", "disabled");
        setField(config, "targets", "");
        setField(config, "retentionDays", 90);
        setField(config, "captureVersionEvents", false);
        setField(config, "captureGenericRelationships", false);
        setField(config, "purgeCron", "");
        setField(config, "snapshotMaxNameLength", maxNameLength);
        setField(config, "snapshotMaxPathLength", maxPathLength);
        setField(config, "snapshotCapturePath", capturePath);
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
