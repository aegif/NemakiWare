package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Verifies that the EventBuilder patterns used at each emit point produce
 * correct processType, inputs, outputs, and snapshotAttributes — without
 * requiring a running server or real REST endpoints.
 */
public class LineageEmitIntegrationTest {

    private static final String REPO = "bedroom";

    // =========================================================================
    // ImportExportResource emit patterns
    // =========================================================================

    @Test
    public void testFilesystemImportEvent() {
        String sourcePath = "/data/import/docs";
        String folderId = "folder-001";
        long objectCount = 42;
        String user = "admin";

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.IMPORT_FILESYSTEM)
                .addInput("file://" + sourcePath)
                .addOutputObject(REPO, folderId)
                .snapshotAttribute("sourcePath", sourcePath)
                .snapshotAttribute("objectCount", String.valueOf(objectCount))
                .snapshotAttribute("requestedBy", user)
                .targets(List.of("purview"))
                .build();

        assertEquals(LineageProcessType.IMPORT_FILESYSTEM, event.processType());
        assertEquals(REPO, event.repositoryId());
        assertEquals(1, event.inputs().size());
        assertEquals("file:///data/import/docs", event.inputs().get(0));
        assertEquals(1, event.outputs().size());
        assertEquals("nemaki://bedroom/objects/folder-001", event.outputs().get(0));
        assertEquals("/data/import/docs", event.snapshotAttributes().get("sourcePath"));
        assertEquals("42", event.snapshotAttributes().get("objectCount"));
        assertEquals("admin", event.snapshotAttributes().get("requestedBy"));
        assertNotNull(event.eventId());
        assertFalse(event.eventId().isEmpty());
        assertNotNull(event.eventKey());
    }

    @Test
    public void testUploadedImportEvent() {
        String importMode = "zip-upload";
        String folderId = "folder-002";
        long objectCount = 10;

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInput("upload://" + importMode)
                .addOutputObject(REPO, folderId)
                .snapshotAttribute("importMode", importMode)
                .snapshotAttribute("objectCount", String.valueOf(objectCount))
                .snapshotAttribute("requestedBy", "admin")
                .targets(List.of("purview"))
                .build();

        assertEquals(LineageProcessType.IMPORT_UPLOADED, event.processType());
        assertEquals("upload://zip-upload", event.inputs().get(0));
        assertEquals("nemaki://bedroom/objects/folder-002", event.outputs().get(0));
        assertEquals("zip-upload", event.snapshotAttributes().get("importMode"));
    }

    @Test
    public void testFilesystemExportEvent() {
        String folderId = "folder-003";
        String targetPath = "/data/export/output";
        long objectCount = 25;

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.EXPORT_FILESYSTEM)
                .addInputObject(REPO, folderId)
                .addOutput("file://" + targetPath)
                .snapshotAttribute("targetPath", targetPath)
                .snapshotAttribute("objectCount", String.valueOf(objectCount))
                .snapshotAttribute("requestedBy", "admin")
                .targets(List.of("purview"))
                .build();

        assertEquals(LineageProcessType.EXPORT_FILESYSTEM, event.processType());
        assertEquals("nemaki://bedroom/objects/folder-003", event.inputs().get(0));
        assertEquals("file:///data/export/output", event.outputs().get(0));
        assertEquals("/data/export/output", event.snapshotAttributes().get("targetPath"));
    }

    @Test
    public void testZipFolderExportEvent() {
        String folderId = "folder-004";
        String folderName = "MyFolder";
        int objectCount = 15;

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .addInputObject(REPO, folderId)
                .snapshotAttribute("folderName", folderName)
                .snapshotAttribute("objectCount", String.valueOf(objectCount))
                .snapshotAttribute("requestedBy", "admin")
                .targets(List.of("purview"))
                .build();

        assertEquals(LineageProcessType.EXPORT_ZIP_FOLDER, event.processType());
        assertEquals("nemaki://bedroom/objects/folder-004", event.inputs().get(0));
        assertTrue(event.outputs().isEmpty());
        assertEquals("MyFolder", event.snapshotAttributes().get("folderName"));
    }

    @Test
    public void testSelectedObjectsExportEvent() {
        List<String> objectIds = List.of("doc-A", "doc-B", "doc-C");
        int objectCount = 3;

        LineageEventBuilder b = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.EXPORT_SELECTED_OBJECTS)
                .snapshotAttribute("objectCount", String.valueOf(objectCount))
                .snapshotAttribute("requestedBy", "admin");
        for (String id : objectIds) {
            b.addInputObject(REPO, id);
        }
        b.targets(List.of("purview"));
        LineageEvent event = b.build();

        assertEquals(LineageProcessType.EXPORT_SELECTED_OBJECTS, event.processType());
        assertEquals(3, event.inputs().size());
        assertEquals("nemaki://bedroom/objects/doc-A", event.inputs().get(0));
        assertEquals("nemaki://bedroom/objects/doc-B", event.inputs().get(1));
        assertEquals("nemaki://bedroom/objects/doc-C", event.inputs().get(2));
        assertTrue(event.outputs().isEmpty());
    }

    // =========================================================================
    // RetentionScheduler emit patterns
    // =========================================================================

    @Test
    public void testArchiveLocalEvent() {
        String documentId = "doc-expired-001";
        String reason = "expired";

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, documentId)
                .addOutput("nemaki://" + REPO + "/archives/" + documentId)
                .snapshotAttribute("reason", reason)
                .targets(List.of("purview"))
                .build();

        assertEquals(LineageProcessType.ARCHIVE_LOCAL, event.processType());
        assertEquals("nemaki://bedroom/objects/doc-expired-001", event.inputs().get(0));
        assertEquals("nemaki://bedroom/archives/doc-expired-001", event.outputs().get(0));
        assertEquals("expired", event.snapshotAttributes().get("reason"));
    }

    @Test
    public void testArchiveColdEvent() {
        String archiveId = "arc-001";
        String storageRef = "s3://bucket/arc-001";
        String originalId = "doc-001";

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_COLD)
                .addInput("nemaki://" + REPO + "/archives/" + archiveId)
                .addOutput("cold://" + storageRef)
                .snapshotAttribute("originalId", originalId)
                .snapshotAttribute("coldMoveMode", "MOVE")
                .targets(List.of("purview"))
                .build();

        assertEquals(LineageProcessType.ARCHIVE_COLD, event.processType());
        assertEquals("nemaki://bedroom/archives/arc-001", event.inputs().get(0));
        assertEquals("cold://s3://bucket/arc-001", event.outputs().get(0));
        assertEquals("doc-001", event.snapshotAttributes().get("originalId"));
        assertEquals("MOVE", event.snapshotAttributes().get("coldMoveMode"));
    }

    // =========================================================================
    // ArchiveResource emit pattern
    // =========================================================================

    @Test
    public void testForceArchiveEvent() {
        String objectId = "doc-force-001";

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, objectId)
                .addOutput("nemaki://" + REPO + "/archives/" + objectId)
                .snapshotAttribute("reason", "force-archive")
                .snapshotAttribute("requestedBy", "admin")
                .targets(List.of("purview"))
                .build();

        assertEquals(LineageProcessType.ARCHIVE_LOCAL, event.processType());
        assertEquals("nemaki://bedroom/objects/doc-force-001", event.inputs().get(0));
        assertEquals("nemaki://bedroom/archives/doc-force-001", event.outputs().get(0));
        assertEquals("force-archive", event.snapshotAttributes().get("reason"));
        assertEquals("admin", event.snapshotAttributes().get("requestedBy"));
    }

    // =========================================================================
    // CloudDriveResource emit patterns
    // =========================================================================

    @Test
    public void testCloudSyncUploadEvent() {
        String objectId = "doc-push-001";
        String provider = "google";
        String cloudFileId = "gd-file-abc123";

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.CLOUD_SYNC_UPLOAD)
                .addInputObject(REPO, objectId)
                .addOutput("cloud://" + provider + "/" + cloudFileId)
                .snapshotAttribute("provider", provider)
                .targets(List.of("purview"))
                .build();

        assertEquals(LineageProcessType.CLOUD_SYNC_UPLOAD, event.processType());
        assertEquals("nemaki://bedroom/objects/doc-push-001", event.inputs().get(0));
        assertEquals("cloud://google/gd-file-abc123", event.outputs().get(0));
        assertEquals("google", event.snapshotAttributes().get("provider"));
    }

    @Test
    public void testCloudSyncDownloadEvent() {
        String objectId = "doc-pull-001";
        String provider = "microsoft";
        String cloudFileId = "od-file-xyz789";

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.CLOUD_SYNC_DOWNLOAD)
                .addInput("cloud://" + provider + "/" + cloudFileId)
                .addOutputObject(REPO, objectId)
                .snapshotAttribute("provider", provider)
                .targets(List.of("purview"))
                .build();

        assertEquals(LineageProcessType.CLOUD_SYNC_DOWNLOAD, event.processType());
        assertEquals("cloud://microsoft/od-file-xyz789", event.inputs().get(0));
        assertEquals("nemaki://bedroom/objects/doc-pull-001", event.outputs().get(0));
        assertEquals("microsoft", event.snapshotAttributes().get("provider"));
    }

    // =========================================================================
    // Cross-cutting: eventKey idempotency
    // =========================================================================

    @Test
    public void testEventKeyDeterminism() {
        LineageEventBuilder template = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-001")
                .addOutput("nemaki://bedroom/archives/doc-001");

        LineageEvent a = template.build();
        // Rebuild with same parameters
        LineageEvent b = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-001")
                .addOutput("nemaki://bedroom/archives/doc-001")
                .build();

        assertEquals(a.eventKey(), b.eventKey(), "same business identity should produce same eventKey");
    }
}
