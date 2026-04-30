package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IngestLineageEmitter static helpers.
 */
class IngestLineageEmitterTest {

    // ── isAttachmentObjectType ──

    @Test
    void isAttachment_attachment() {
        assertTrue(IngestLineageEmitter.isAttachmentObjectType("attachment"));
    }

    @Test
    void isAttachment_file() {
        assertTrue(IngestLineageEmitter.isAttachmentObjectType("file"));
    }

    @Test
    void isAttachment_caseInsensitive() {
        assertTrue(IngestLineageEmitter.isAttachmentObjectType("ATTACHMENT"));
        assertTrue(IngestLineageEmitter.isAttachmentObjectType("File"));
    }

    @Test
    void isAttachment_message() {
        assertFalse(IngestLineageEmitter.isAttachmentObjectType("message"));
    }

    @Test
    void isAttachment_null() {
        assertFalse(IngestLineageEmitter.isAttachmentObjectType(null));
    }

    // ── resolveProcessType ──

    @Test
    void processType_fileShare() {
        assertEquals(LineageProcessType.FILE_SHARE_SYNC_DOWNLOAD,
                IngestLineageEmitter.resolveProcessType(SourceArchetype.FILE_SHARE, "file"));
    }

    @Test
    void processType_mailMessage() {
        assertEquals(LineageProcessType.MAIL_MESSAGE_IMPORT,
                IngestLineageEmitter.resolveProcessType(SourceArchetype.MESSAGE_CONTEXT, "message"));
    }

    @Test
    void processType_mailAttachment() {
        assertEquals(LineageProcessType.MAIL_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveProcessType(SourceArchetype.MESSAGE_CONTEXT, "attachment"));
    }

    @Test
    void processType_notePage() {
        assertEquals(LineageProcessType.EXTERNAL_NOTE_IMPORT,
                IngestLineageEmitter.resolveProcessType(SourceArchetype.COMPOUND_NOTE, "page"));
    }

    @Test
    void processType_noteAttachment() {
        assertEquals(LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveProcessType(SourceArchetype.COMPOUND_NOTE, "attachment"));
    }

    @Test
    void processType_chatMessage() {
        assertEquals(LineageProcessType.CHAT_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveProcessType(SourceArchetype.CHAT_CONTEXT, "message"));
    }

    @Test
    void processType_businessRecord() {
        assertEquals(LineageProcessType.BUSINESS_RECORD_IMPORT,
                IngestLineageEmitter.resolveProcessType(SourceArchetype.BUSINESS_RECORD, "record"));
    }

    @Test
    void processType_nullArchetype_message() {
        assertEquals(LineageProcessType.IMPORT_UPLOADED,
                IngestLineageEmitter.resolveProcessType(null, "message"));
    }

    @Test
    void processType_nullArchetype_attachment() {
        assertEquals(LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveProcessType(null, "attachment"));
    }

    // ── buildCanonicalSourceUri ──

    @Test
    void sourceUri_fileShare() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setSourceSystem("google_drive");
        c.setSourceArchetype(SourceArchetype.FILE_SHARE);
        c.setTenantId("tenant1");
        ExternalIngestRequest r = new ExternalIngestRequest();
        r.setSourceObjectId("file123");
        r.setSourceObjectType("file");

        String uri = IngestLineageEmitter.buildCanonicalSourceUri(c, r);
        assertTrue(uri.contains("google_drive"));
        assertTrue(uri.contains("file123"));
    }

    @Test
    void sourceUri_chatMessage() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setSourceSystem("slack");
        c.setSourceArchetype(SourceArchetype.CHAT_CONTEXT);
        c.setTenantId("workspace1");
        ExternalIngestRequest r = new ExternalIngestRequest();
        r.setSourceObjectId("msg123");
        r.setSourceObjectType("message");
        r.setMetadata(Map.of("channelId", "C01"));

        String uri = IngestLineageEmitter.buildCanonicalSourceUri(c, r);
        assertTrue(uri.contains("slack"));
        assertTrue(uri.contains("C01"));
        assertTrue(uri.contains("msg123"));
    }
}
