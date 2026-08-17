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

    // ── resolveFactProcessType (the v2 classification; v1 labels stay frozen) ──

    /**
     * The CHAT_CONTEXT inversion (v2.3.13 confirmed bug 1): v1 classifies an attachment as the
     * generic type and a message as the attachment type. The fact classification corrects it;
     * the v1 side keeps the historical labels because they are hashed into every eventKey.
     */
    @Test
    void factType_chatContext_correctsTheInversion() {
        assertEquals(LineageProcessType.CHAT_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveFactProcessType(SourceArchetype.CHAT_CONTEXT, "attachment"));
        assertEquals(LineageProcessType.CHAT_MESSAGE_IMPORT,
                IngestLineageEmitter.resolveFactProcessType(SourceArchetype.CHAT_CONTEXT, "message"));
        // and the frozen v1 labels remain inverted:
        assertEquals(LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveProcessType(SourceArchetype.CHAT_CONTEXT, "attachment"));
        assertEquals(LineageProcessType.CHAT_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveProcessType(SourceArchetype.CHAT_CONTEXT, "message"));
    }

    @Test
    void factType_nullArchetype_isGenericIngestNotUpload() {
        assertEquals(LineageProcessType.GENERIC_EXTERNAL_INGEST,
                IngestLineageEmitter.resolveFactProcessType(null, "message"));
        assertEquals(LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveFactProcessType(null, "attachment"));
    }

    @Test
    void factType_agreesWithV1EverywhereElse() {
        assertEquals(LineageProcessType.FILE_SHARE_SYNC_DOWNLOAD,
                IngestLineageEmitter.resolveFactProcessType(SourceArchetype.FILE_SHARE, "file"));
        assertEquals(LineageProcessType.MAIL_MESSAGE_IMPORT,
                IngestLineageEmitter.resolveFactProcessType(SourceArchetype.MESSAGE_CONTEXT, "message"));
        assertEquals(LineageProcessType.MAIL_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveFactProcessType(SourceArchetype.MESSAGE_CONTEXT, "attachment"));
        assertEquals(LineageProcessType.BUSINESS_RECORD_IMPORT,
                IngestLineageEmitter.resolveFactProcessType(SourceArchetype.BUSINESS_RECORD, "record"));
        assertEquals(LineageProcessType.EXTERNAL_NOTE_IMPORT,
                IngestLineageEmitter.resolveFactProcessType(SourceArchetype.COMPOUND_NOTE, "page"));
        assertEquals(LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT,
                IngestLineageEmitter.resolveFactProcessType(SourceArchetype.COMPOUND_NOTE, "attachment"));
    }

    // ── buildCanonicalSourceUri ──

    /**
     * The production transformation itself must reject a URL-shaped sourceObjectId — the review
     * finding was that percent-encoding ran before validation, so {@code ?} became {@code %3F}
     * and the literal-character checks downstream never saw it. The throw is absorbed by the
     * producer's fail-open boundary; the import is unaffected.
     */
    @Test
    void sourceUri_urlShapedSourceObjectIdIsRejectedBeforeEncoding() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setSourceSystem("google_drive");
        c.setSourceArchetype(null);
        c.setTenantId("t1");
        ExternalIngestRequest r = new ExternalIngestRequest();
        r.setSourceObjectId("https://drive.example/file?sig=SECRET");
        r.setSourceObjectType("document");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> IngestLineageEmitter.buildCanonicalSourceUri(c, r));
        assertTrue(thrown.getMessage().contains("query"), thrown.getMessage());

        r.setSourceObjectId("file%3Fsig%3DSECRET");
        assertThrows(IllegalArgumentException.class,
                () -> IngestLineageEmitter.buildCanonicalSourceUri(c, r),
                "a pre-encoded delimiter is an encoded URL, not an id");
    }

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
