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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether an import CREATED its object, reported honestly all the way out.
 *
 * <p>The flag decides whether custody time may be recorded at all: only an object this operation
 * made has a knowable moment of custody, and for anything already here the answer is unknown
 * (see {@code CanonicalImportServiceImpl.applyChatCapturedAt}). Each public entry point rebuilds
 * the inner result before returning it, and every one of those rebuilds originally used the
 * legacy constructor arity — so a freshly created mail, note, record or chat object was reported
 * to its caller as pre-existing (external review).
 *
 * <p>The chat entry point is covered in {@code IngestEvidenceSnapshotTest} alongside the stamp it
 * gates. This covers the other three, and the note aggregate's harder question: the flag has to
 * describe the object {@code primaryObjectId} actually NAMES, not whether the import created
 * anything at all.
 */
class IngestCreatedObjectPropagationTest {

    private CanonicalImportServiceImpl service;
    private ConnectorDefinitionService connectorService;
    private ImportProfileDefinitionService profileService;
    private jp.aegif.nemaki.cmis.service.ObjectService objectService;
    private jp.aegif.nemaki.businesslogic.ContentService contentService;
    private jp.aegif.nemaki.dao.ContentDaoService contentDaoService;
    private IngestMetadataService metadataService;
    private final List<jp.aegif.nemaki.model.Content> existingChildren = new ArrayList<>();

    private void wire(SourceArchetype archetype) {
        service = new CanonicalImportServiceImpl();
        connectorService = mock(ConnectorDefinitionService.class);
        profileService = mock(ImportProfileDefinitionService.class);
        objectService = mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        contentService = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        contentDaoService = mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);
        service.setContentDaoService(contentDaoService);
        metadataService = mock(IngestMetadataService.class);
        service.setIngestMetadataService(metadataService);

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

        // any() for the content stream: note and mail imports supply one.
        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                any(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("new-obj-id");
        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(existingChildren);
    }

    /** An object already in the target folder, so dedupe finds it instead of creating one. */
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
        existingChildren.add(doc);
        when(contentService.getContent("bedroom", objectId)).thenReturn(doc);
    }

    private ExternalIngestRequest baseRequest(String sourceObjectId, String fileName) {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId(sourceObjectId);
        req.setFileName(fileName);
        return req;
    }

    private static org.apache.chemistry.opencmis.commons.server.CallContext ctx() {
        return mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
    }

    @Test
    @DisplayName("a mail import reports that it created the message object")
    void mailImportReportsCreation() {
        wire(SourceArchetype.MESSAGE_CONTEXT);
        ExternalIngestRequest req = baseRequest("mail-1", "message.eml");
        req.setSourceObjectType("message");
        req.setMetadata(new LinkedHashMap<>(Map.of("mailboxId", "ishii@example.com")));
        req.setContentStream(new java.io.ByteArrayInputStream(
                ("From: otsuka@example.com\r\nTo: ishii@example.com\r\n"
                        + "Subject: minutes\r\nMessage-ID: <m1@example.com>\r\n"
                        + "Date: Mon, 1 Jul 2024 09:00:00 +0900\r\n\r\nbody\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ExternalIngestResult result = service.executeMailImport(ctx(), req);

        assertTrue(result.isSuccess(), "control: " + result.errors());
        assertTrue(result.createdObject(),
                "the mail entry point rebuilds the inner result before returning it, and the "
                        + "rebuild dropped this flag — so a message it had just created was "
                        + "reported to its caller as one that was already here");
    }

    @Test
    @DisplayName("a business record import reports that it created the record object")
    void businessRecordImportReportsCreation() {
        wire(SourceArchetype.BUSINESS_RECORD);
        ExternalIngestRequest req = baseRequest("rec-1", "record.json");
        req.setSourceObjectType("record");

        ExternalIngestResult result = service.executeBusinessRecordImport(ctx(), req);

        assertTrue(result.isSuccess(), "control: " + result.errors());
        assertTrue(result.createdObject(), "same rebuild, same dropped flag");
    }

    @Test
    @DisplayName("a re-imported business record reports no creation")
    void businessRecordReimportReportsNoCreation() {
        wire(SourceArchetype.BUSINESS_RECORD);
        alreadyImported("rec-obj", "rec-1", "record");
        ExternalIngestRequest req = baseRequest("rec-1", "record.json");
        req.setSourceObjectType("record");

        ExternalIngestResult result = service.executeBusinessRecordImport(ctx(), req);

        assertTrue(result.isSuccess(), "control: " + result.errors());
        assertFalse(result.createdObject(),
                "nothing was created, and saying otherwise would license a custody stamp for a "
                        + "record that may have been held for years");
    }

    @Test
    @DisplayName("files_and_body: the flag describes the page, which is what primaryObjectId names")
    void notePageIsThePrimaryObject() {
        wire(SourceArchetype.COMPOUND_NOTE);
        ExternalIngestRequest req = baseRequest("page-1", "page.html");
        req.setImportPolicy("files_and_body");
        req.setContentStream(new java.io.ByteArrayInputStream(
                "<p>body</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ExternalIngestResult result = service.executeNoteImport(ctx(), req);

        assertTrue(result.isSuccess(), "control: " + result.errors());
        assertEquals("new-obj-id", result.objectId(), "control: the page is the primary object");
        assertTrue(result.createdObject());
    }

    @Test
    @DisplayName("files_and_body: a pre-existing page stays 'not created' even when an attachment is")
    void notePageDecidesTheFlagEvenWhenAnAttachmentIsCreated() {
        // The discriminating case. primaryObjectId names the PAGE, which was already here, while
        // an attachment was genuinely created. "Did this import create anything" answers true and
        // would license a custody stamp on a page that predates this run — the flag has to answer
        // about the named object instead (external review).
        wire(SourceArchetype.COMPOUND_NOTE);
        alreadyImported("page-obj", "page-3", "page");

        ExternalIngestRequest req = baseRequest("page-3", "page.html");
        req.setSourceObjectType("page");
        req.setImportPolicy("files_and_body");
        req.setContentStream(new java.io.ByteArrayInputStream(
                "<p>body</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attachments", List.of(new LinkedHashMap<>(Map.of(
                "attachmentId", "a", "filename", "new.txt", "contentBase64",
                java.util.Base64.getEncoder().encodeToString(
                        "bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
        req.setMetadata(metadata);

        ExternalIngestResult result = service.executeNoteImport(ctx(), req);

        assertTrue(result.isSuccess(), "control: " + result.errors());
        assertEquals("page-obj", result.objectId(),
                "control: the page is the primary object, and it was already here");
        assertFalse(result.createdObject(),
                "an attachment was created, but the flag describes the page — reporting true "
                        + "would date a page held since before this run from today");
    }

    @Test
    @DisplayName("files_only: the flag describes the FIRST attachment, not whether anything was created")
    void noteFirstAttachmentDecidesTheFlag() {
        // The sharp case. primaryObjectId names the first attachment, and that one was already
        // here — while a LATER attachment was genuinely created. A flag that answered "did this
        // import create anything" would say true and license a custody stamp for an object that
        // predates this run (external review).
        wire(SourceArchetype.COMPOUND_NOTE);
        alreadyImported("att-existing", "page-1/att-a", "attachment");

        ExternalIngestRequest req = baseRequest("page-1", "page.html");
        req.setImportPolicy("files_only");
        Map<String, Object> metadata = new LinkedHashMap<>();
        String content = java.util.Base64.getEncoder().encodeToString(
                "bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        metadata.put("attachments", List.of(
                new LinkedHashMap<>(Map.of("attachmentId", "a", "filename", "first.txt",
                        "contentBase64", content)),
                new LinkedHashMap<>(Map.of("attachmentId", "b", "filename", "second.txt",
                        "contentBase64", content))));
        req.setMetadata(metadata);

        ExternalIngestResult result = service.executeNoteImport(ctx(), req);

        assertTrue(result.isSuccess(), "control: " + result.errors());
        assertEquals("att-existing", result.objectId(),
                "control: the first attachment is the primary object, and it was already here");
        assertFalse(result.createdObject(),
                "the flag must describe the object primaryObjectId NAMES. The second attachment "
                        + "was created, but stamping custody on the first would date a holding "
                        + "that predates this import from today");
    }

    @Test
    @DisplayName("a metadata error on the attachment does not turn a creation into a non-creation")
    void noteAttachmentMetadataErrorKeepsTheCreationFlag() {
        // executeNoteAttachment rebuilds its result on this branch ONLY, to append the warning.
        // Every other note test mocks applyNoteMetadata to succeed, so the rebuild was never
        // entered and its propagation could be reverted while the whole suite stayed green
        // (external review). A partial failure must not erase what the import did establish.
        wire(SourceArchetype.COMPOUND_NOTE);
        when(metadataService.applyNoteMetadata(any(), any(), any(), any()))
                .thenReturn("note metadata could not be applied");

        ExternalIngestRequest req = baseRequest("page-4", "page.html");
        req.setImportPolicy("files_only");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attachments", List.of(new LinkedHashMap<>(Map.of(
                "attachmentId", "a", "filename", "only.txt", "contentBase64",
                java.util.Base64.getEncoder().encodeToString(
                        "bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
        req.setMetadata(metadata);

        ExternalIngestResult result = service.executeNoteImport(ctx(), req);

        assertTrue(result.isSuccess(), "control: a metadata warning is not an error");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("note metadata")),
                "control: the branch under test must actually have been entered: "
                        + result.warnings());
        assertTrue(result.createdObject(),
                "the object WAS created; losing that because a metadata step failed would "
                        + "discard the one moment of custody this deployment can establish");
    }

    @Test
    @DisplayName("files_only: a newly created first attachment is reported as created")
    void noteNewFirstAttachmentIsReported() {
        wire(SourceArchetype.COMPOUND_NOTE);
        ExternalIngestRequest req = baseRequest("page-2", "page.html");
        req.setImportPolicy("files_only");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attachments", List.of(new LinkedHashMap<>(Map.of(
                "attachmentId", "a", "filename", "only.txt", "contentBase64",
                java.util.Base64.getEncoder().encodeToString(
                        "bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
        req.setMetadata(metadata);

        ExternalIngestResult result = service.executeNoteImport(ctx(), req);

        assertTrue(result.isSuccess(), "control: " + result.errors());
        assertEquals("new-obj-id", result.objectId());
        assertTrue(result.createdObject(),
                "a wrong false is not harmless either: it loses the one moment of custody this "
                        + "deployment can actually establish");
    }
}
