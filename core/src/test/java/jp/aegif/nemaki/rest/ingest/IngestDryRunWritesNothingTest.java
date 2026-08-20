/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Property;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A dry run must not change the repository — at ANY entry point.
 *
 * <p>It did not. {@code execute} honoured {@code dryRun} and returned before mutating, but the
 * four wrappers never looked at the flag: they went on to write metadata aspects, import the raw
 * {@code .eml} and every attachment for real, and create relationships. The child requests they
 * built never carried the flag either, so the children imported. {@code ExternalIngestResult
 * .dryRun(...)} reports {@code isSuccess() == true} and {@code skipped() == false}, so no existing
 * guard caught it (external review).
 *
 * <p>The test drives each entry point against an EXISTING document, because that is the shape
 * where {@code execute} returns a preview and the wrapper then decorates. A test that only drove
 * {@code execute} would have passed before the fix and proved nothing.
 *
 * <h2>What these tests do NOT discriminate</h2>
 *
 * <p>Four changed sites are not independently pinned. Stated here rather than implied by an
 * assertion message, because an assertion that passes for a reason other than the one it names is
 * how this repository has been misled before:
 *
 * <ul>
 *   <li>The raw {@code .eml} and mail-attachment requests inherit the flag, but the mail wrapper
 *       returns before building them. Kept as defence in depth for the day someone moves that
 *       guard. The note path, which has no single early return, DOES exercise child inheritance,
 *       and removing it there is caught.</li>
 *   <li>The mail, record and chat result rebuilds no longer hardcode {@code dryRun=false} — but
 *       on a dry run those rebuilds are never reached, because the early return hands back the
 *       result {@code execute} already built with the flag set. So {@code assertTrue(result
 *       .dryRun())} in those three tests passes whether or not the rebuild was fixed. The fix is
 *       still right: it is what makes the flag survive if the early return is ever moved below
 *       the rebuild.</li>
 *   <li>The {@code files_only} skip rebuild is unreached here — this test's attachment previews
 *       successfully, so the "nothing was imported" branch is not taken. It IS reachable in
 *       production (a {@code files_only} dry run of a page with no attachments).</li>
 * </ul>
 */
class IngestDryRunWritesNothingTest {

    private CanonicalImportServiceImpl service;
    private jp.aegif.nemaki.cmis.service.ObjectService objectService;
    private jp.aegif.nemaki.businesslogic.ContentService contentService;
    private IngestMetadataService metadataService;
    private jp.aegif.nemaki.cmis.service.VersioningService versioningService;
    private final List<jp.aegif.nemaki.model.Content> children = new ArrayList<>();

    private void wire(SourceArchetype archetype) {
        service = new CanonicalImportServiceImpl();
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ImportProfileDefinitionService profileService = mock(ImportProfileDefinitionService.class);
        objectService = mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        contentService = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        versioningService = mock(jp.aegif.nemaki.cmis.service.VersioningService.class);
        metadataService = mock(IngestMetadataService.class);
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);
        service.setVersioningService(versioningService);
        service.setIngestMetadataService(metadataService);
        service.setContentDaoService(contentDaoService);

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(archetype);
        connector.setSourceSystem("acme");
        when(connectorService.get("c1")).thenReturn(connector);
        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(children);
    }

    /** Already imported, so execute() previews instead of creating — the shape that decorates. */
    private void alreadyImported(String objectId, String sourceObjectId, String sourceObjectType) {
        Aspect integration = new Aspect();
        integration.setName("nemaki:externalIntegration");
        integration.setProperties(new ArrayList<>(List.of(
                new Property("nemaki:sourceObjectId", sourceObjectId),
                new Property("nemaki:sourceSystem", "acme"),
                new Property("nemaki:sourceObjectType", sourceObjectType))));
        jp.aegif.nemaki.model.Document doc = new jp.aegif.nemaki.model.Document();
        doc.setId(objectId);
        doc.setType("cmis:document");
        doc.setName(objectId);
        doc.setAspects(new ArrayList<>(List.of(integration)));
        children.add(doc);
        when(contentService.getContent("bedroom", objectId)).thenReturn(doc);
    }

    private ExternalIngestRequest dryRunRequest(String sourceObjectId, String fileName) {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId(sourceObjectId);
        req.setFileName(fileName);
        req.setDryRun(true);
        return req;
    }

    private static org.apache.chemistry.opencmis.commons.server.CallContext ctx() {
        return mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
    }

    /** No document was created, no content updated, no version cut, no metadata applied. */
    private void assertNothingWasWritten(String where) {
        // any(), never anyString(): anyString() does not match null, so a write with a null
        // repository or folder would slip past the verify and the control would look green.
        verify(objectService, never()).createDocument(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(objectService, never()).createRelationship(any(), any(), any(), any(), any(),
                any(), any());
        verify(contentService, never()).update(any(), any(), any());
        verify(versioningService, never()).checkOut(any(), any(), any(), any(), any());
        verify(versioningService, never()).checkIn(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(metadataService, never()).applyNoteMetadata(any(), any(), any(), any());
        verify(metadataService, never()).applyArchetypeMetadata(any(), any(), any(), any(), any(),
                any());
        verify(metadataService, never()).applyMessageMetadata(any(), any(), any(), any(), any());
        assertTrue(true, where);
    }

    @Test
    @DisplayName("a dry-run mail import writes nothing, including the raw .eml and attachments")
    void mailDryRunWritesNothing() {
        wire(SourceArchetype.MESSAGE_CONTEXT);
        alreadyImported("mail-obj", "mail-1", "message");
        ExternalIngestRequest req = dryRunRequest("mail-1", "message.eml");
        req.setSourceObjectType("message");
        req.setMetadata(new LinkedHashMap<>(Map.of("mailboxId", "ishii@example.com")));
        req.setContentStream(new java.io.ByteArrayInputStream(
                ("From: otsuka@example.com\r\nTo: ishii@example.com\r\nSubject: minutes\r\n"
                        + "Message-ID: <m1@example.com>\r\n"
                        + "Date: Mon, 1 Jul 2024 09:00:00 +0900\r\n\r\nbody\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ExternalIngestResult result = service.executeMailImport(ctx(), req);

        assertTrue(result.dryRun(), "the result must still say it was a preview");
        assertNothingWasWritten("mail");
    }

    @Test
    @DisplayName("a dry-run business record import writes nothing")
    void businessRecordDryRunWritesNothing() {
        wire(SourceArchetype.BUSINESS_RECORD);
        alreadyImported("rec-obj", "rec-1", "record");
        ExternalIngestRequest req = dryRunRequest("rec-1", "record.json");
        req.setSourceObjectType("record");

        ExternalIngestResult result = service.executeBusinessRecordImport(ctx(), req);

        assertTrue(result.dryRun(), "the result must still say it was a preview");
        assertNothingWasWritten("business record");
    }

    @Test
    @DisplayName("a dry-run chat import writes nothing, including the capture window and custody stamp")
    void chatDryRunWritesNothing() {
        wire(SourceArchetype.CHAT_CONTEXT);
        alreadyImported("chat-obj", "1720000000.000200", "message");
        ExternalIngestRequest req = dryRunRequest("1720000000.000200", "message.txt");
        req.setSourceObjectType("message");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelId", "C02AMPJAY");
        metadata.put("captureWindowStart", "2026-07-14T02:00:00Z");
        metadata.put("captureWindowEnd", "2026-07-14T03:00:00Z");
        req.setMetadata(metadata);

        ExternalIngestResult result = service.executeChatContextImport(ctx(), req);

        assertTrue(result.dryRun(), "the result must still say it was a preview");
        assertNothingWasWritten("chat");
    }

    @Test
    @DisplayName("a dry-run note import writes nothing, and its attachments preview too")
    void noteDryRunWritesNothing() {
        // files_only is the sharp case: the page is never imported, so there is no page result to
        // key on. Every write in this path has to be guarded on the REQUEST.
        wire(SourceArchetype.COMPOUND_NOTE);
        alreadyImported("att-obj", "page-1/att-a", "attachment");
        ExternalIngestRequest req = dryRunRequest("page-1", "page.html");
        req.setSourceObjectType("page");
        req.setImportPolicy("files_only");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attachments", List.of(new LinkedHashMap<>(Map.of(
                "attachmentId", "a", "filename", "first.txt", "contentBase64",
                java.util.Base64.getEncoder().encodeToString(
                        "bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
        req.setMetadata(metadata);

        ExternalIngestResult result = service.executeNoteImport(ctx(), req);

        assertTrue(result.dryRun(),
                "the note aggregate rebuilds the result and used to hardcode dryRun=false");
        assertNothingWasWritten("note files_only");
    }

    @Test
    @DisplayName("a dry-run note import with a body writes nothing either")
    void noteWithBodyDryRunWritesNothing() {
        wire(SourceArchetype.COMPOUND_NOTE);
        alreadyImported("page-obj", "page-2", "page");
        // The attachment must ALSO already exist, so the child preview returns an objectId and
        // the page-to-attachment relationship becomes reachable. Without it that guard is never
        // exercised and removing it looks harmless.
        alreadyImported("att-obj-2", "page-2/att-a", "attachment");
        ExternalIngestRequest req = dryRunRequest("page-2", "page.html");
        req.setSourceObjectType("page");
        req.setImportPolicy("files_and_body");
        req.setContentStream(new java.io.ByteArrayInputStream(
                "<p>body</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attachments", List.of(new LinkedHashMap<>(Map.of(
                "attachmentId", "a", "filename", "only.txt", "contentBase64",
                java.util.Base64.getEncoder().encodeToString(
                        "bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
        req.setMetadata(metadata);

        ExternalIngestResult result = service.executeNoteImport(ctx(), req);

        assertTrue(result.dryRun(), "the result must still say it was a preview");
        assertNothingWasWritten("note files_and_body");
    }

    @Test
    @DisplayName("a dry run that throws does not leave a DLQ entry")
    void dryRunFailureDoesNotWriteToTheDlq() {
        // Anything that throws before the dry-run gate lands in the exception handler, which used
        // to persist the whole request — dryRun:true and the buffered bytes included — as a DLQ
        // document. Retrying that entry then DELETES it without importing, because a dry-run
        // result reports success (external review).
        wire(SourceArchetype.FILE_SHARE);
        IngestJobService jobService = mock(IngestJobService.class);
        service.setIngestJobService(jobService);
        ExternalIngestRequest req = dryRunRequest("file-9", "test.txt");
        req.setSourceObjectType("files");
        req.setContentStream(new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("connection reset mid-upload");
            }
        });

        service.execute(ctx(), req);

        verify(jobService, never()).saveToDlq(any(), any(), any());
    }

    @Test
    @DisplayName("control: the same note import WITHOUT dryRun does reach the attachment import")
    void controlNoteWithoutDryRunImportsTheAttachment() {
        // Without this control the two note tests above could be passing because the attachment
        // loop is never reached, not because the dry run is honoured.
        wire(SourceArchetype.COMPOUND_NOTE);
        alreadyImported("page-obj", "page-3", "page");
        ExternalIngestRequest req = dryRunRequest("page-3", "page.html");
        req.setDryRun(false);
        req.setSourceObjectType("page");
        req.setImportPolicy("files_and_body");
        req.setContentStream(new java.io.ByteArrayInputStream(
                "<p>body</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attachments", List.of(new LinkedHashMap<>(Map.of(
                "attachmentId", "a", "filename", "only.txt", "contentBase64",
                java.util.Base64.getEncoder().encodeToString(
                        "bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
        req.setMetadata(metadata);

        service.executeNoteImport(ctx(), req);

        verify(objectService).createDocument(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("a dry run does not delete an expired idempotency record")
    void dryRunDoesNotDeleteTheExpiredIdempotencyRecord() {
        // This one is inside execute() itself, BEFORE the dry-run gate — so even the entry point
        // that did honour dryRun performed a durable delete (external review).
        wire(SourceArchetype.FILE_SHARE);
        jp.aegif.nemaki.rest.controller.IntegrationSettingsService settings =
                mock(jp.aegif.nemaki.rest.controller.IntegrationSettingsService.class);
        service.setIntegrationSettingsService(settings);
        long expired = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
        when(settings.readSetting(anyString())).thenReturn("old-obj|" + expired);

        ExternalIngestRequest req = dryRunRequest("file-1", "test.txt");
        req.setSourceObjectType("files");
        req.setIdempotencyKey("k-1");

        service.execute(ctx(), req);

        verify(settings, never()).deleteSettings(any());
        assertNothingWasWritten("idempotency");
    }
}
