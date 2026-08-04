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

    /**
     * The truncation rule the snapshot path now shares with §2's attribute limits.
     *
     * <p>These used to assert against a private {@code truncate(String, int)} that cut at a raw
     * index. That helper had no production caller and is gone; what production actually does is
     * asserted here, through the builder, including the case the old helper got wrong.
     */
    @Test
    public void testSnapshotNameTruncatesAtTheConfiguredLength() throws Exception {
        LineageConfig config = newConfig(3, 100, true);
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("r")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .snapshotName("abcdef", config)
                .build();

        assertEquals("abc", event.snapshotAttributes().get("name"));
        assertEquals("true", event.snapshotAttributes().get("name_truncated"));
    }

    @Test
    public void testSnapshotNameShorterThanTheLimitIsUntouched() throws Exception {
        LineageConfig config = newConfig(100, 100, true);
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("r")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .snapshotName("abcdef", config)
                .build();

        assertEquals("abcdef", event.snapshotAttributes().get("name"));
        assertNull(event.snapshotAttributes().get("name_truncated"));
    }

    /**
     * A cut that would land inside a surrogate pair steps back instead of splitting it.
     *
     * <p>"\uD83D\uDCC4" is one code point in two UTF-16 units, so a limit of 3 lands between
     * them. The deleted helper returned a lone high surrogate here — a value that is not
     * text and does not survive UTF-8 encoding. The evidence digest still covers the original,
     * so nothing is lost by keeping the stored prefix well-formed.
     */
    @Test
    public void testSnapshotNameNeverSplitsASurrogatePair() throws Exception {
        LineageConfig config = newConfig(3, 100, true);
        String name = "ab\uD83D\uDCC4cd";
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("r")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .snapshotName(name, config)
                .build();

        String stored = event.snapshotAttributes().get("name");
        assertEquals("ab", stored);
        assertEquals("true", event.snapshotAttributes().get("name_truncated"));
        assertFalse(Character.isHighSurrogate(stored.charAt(stored.length() - 1)));
        assertEquals(EndpointAttribute.evidenceDigest(name),
                event.snapshotAttributes().get("name_hash"));
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

    /**
     * {@code name_hash} is the evidence kind: full width, and recomputable from the original.
     *
     * <p>Asserted through the public builder rather than a package-private hash helper, because
     * what callers depend on is that the stored companion matches a recomputation — not that
     * some private method is a hash function.
     */
    @Test
    public void testSnapshotNameHashIsTheEvidenceDigestOfTheOriginal() throws Exception {
        LineageConfig config = newConfig(3, 100, true);
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("r")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .snapshotName("hello world", config)
                .build();

        String stored = event.snapshotAttributes().get("name_hash");
        assertEquals(EndpointAttribute.evidenceDigest("hello world"), stored);
        assertEquals(64, stored.length());
        assertTrue(EndpointAttribute.isEvidenceDigest(stored));
        assertFalse(stored.equals(EndpointAttribute.evidenceDigest("hello")));
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
