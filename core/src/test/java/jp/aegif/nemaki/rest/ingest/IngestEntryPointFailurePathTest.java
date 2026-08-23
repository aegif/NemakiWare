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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A failure after the document is committed must not report that nothing happened.
 *
 * <p>Three things were lost at every public entry point, and each is what an operator needs to
 * clean up after:
 *
 * <ul>
 *   <li><b>The object.</b> These paths fail AFTER the commit, and
 *       {@code ExternalIngestResult.error(requestId, message)} hard-codes {@code objectId = null}.
 *       The audit line did the same. So the record said "nothing was created" about a document
 *       sitting in the repository.</li>
 *   <li><b>The warnings.</b> Everything recorded up to the failure — a replaced document that
 *       could not be deleted, an ACL that was not applied, provenance that was not recorded —
 *       was discarded by the same constructor.</li>
 *   <li><b>The DLQ entry.</b> {@code executeMailImport} converted its exception to a value, and
 *       note, business-record and chat had no top-level try at all, so the exception escaped to
 *       the fetch orchestrator. {@code saveToDlq} is only reached from {@code execute()}'s own
 *       catch, so none of these ever produced one — and the orchestrator's high-water mark is
 *       overtaken by a later success in the same batch, after which the source item is never
 *       re-fetched (external review).</li>
 * </ul>
 */
class IngestEntryPointFailurePathTest {

    private CanonicalImportServiceImpl service;
    private IngestJobService jobService;
    private IngestMetadataService metadataService;
    private ImportProfileDefinitionService profileServiceRef;
    private jp.aegif.nemaki.cmis.service.ObjectService objectServiceRef;
    private final List<jp.aegif.nemaki.model.Content> children = new ArrayList<>();

    private void wire(SourceArchetype archetype) {
        service = new CanonicalImportServiceImpl();
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ImportProfileDefinitionService profileService = mock(ImportProfileDefinitionService.class);
        jp.aegif.nemaki.cmis.service.ObjectService objectService =
                mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        metadataService = mock(IngestMetadataService.class);
        jobService = mock(IngestJobService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);
        service.setContentDaoService(contentDaoService);
        service.setIngestMetadataService(metadataService);
        service.setIngestJobService(jobService);
        profileServiceRef = profileService;
        objectServiceRef = objectService;

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
        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                any(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("new-obj-id");
        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(children);

        jp.aegif.nemaki.model.Document stored = new jp.aegif.nemaki.model.Document();
        stored.setId("new-obj-id");
        stored.setType("cmis:document");
        stored.setAspects(new ArrayList<>());
        stored.setSecondaryIds(new ArrayList<>());
        when(contentService.getContent("bedroom", "new-obj-id")).thenReturn(stored);
    }

    /** Already imported, so execute() dedupe-skips and the wrapper decorates the existing one. */
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
    }

    private ExternalIngestRequest request(String sourceObjectId, String fileName) {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId(sourceObjectId);
        req.setFileName(fileName);
        req.setExecutionMode("scheduled");
        return req;
    }

    private static org.apache.chemistry.opencmis.commons.server.CallContext ctx() {
        // These tests simulate AUTONOMOUS (scheduled) ingest failures — the DLQ expectations
        // depend on it. P1-1(e) decides autonomy by the context's type, not executionMode:
        // null = the admin scheduler path. A stubbed real mock here would read as a MANUAL
        // caller and correctly skip the DLQ.
        return null;
    }

    /** Blow up in the wrapper's post-processing, after execute() has committed. */
    private void metadataExplodes() {
        when(metadataService.applyArchetypeMetadata(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("catalogue unreachable"));
        when(metadataService.applyNoteMetadata(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("catalogue unreachable"));
    }

    private void assertFailureIsHonest(ExternalIngestResult result, String expectedObjectId) {
        assertFalse(result.isSuccess(), "control: the entry point must report failure");
        assertEquals(expectedObjectId, result.objectId(),
                "the document IS committed; reporting objectId=null tells the caller the "
                        + "opposite of the truth and leaves nothing to clean up by");
        verify(jobService).saveToDlq(any(), any(), any());
    }

    @Test
    @DisplayName("a business-record failure after the commit reports the object and reaches the DLQ")
    void businessRecordFailureIsHonest() {
        wire(SourceArchetype.BUSINESS_RECORD);
        metadataExplodes();
        ExternalIngestRequest req = request("rec-1", "record.json");
        req.setSourceObjectType("record");

        assertFailureIsHonest(service.executeBusinessRecordImport(ctx(), req), "new-obj-id");
    }

    @Test
    @DisplayName("a chat failure after the commit reports the object and reaches the DLQ")
    void chatFailureIsHonest() {
        wire(SourceArchetype.CHAT_CONTEXT);
        metadataExplodes();
        ExternalIngestRequest req = request("1720000000.000200", "message.txt");
        req.setSourceObjectType("message");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelId", "C02AMPJAY");
        req.setMetadata(metadata);

        assertFailureIsHonest(service.executeChatContextImport(ctx(), req), "new-obj-id");
    }

    @Test
    @DisplayName("a note failure after the commit reports the page and reaches the DLQ")
    void noteFailureIsHonest() {
        wire(SourceArchetype.COMPOUND_NOTE);
        metadataExplodes();
        ExternalIngestRequest req = request("page-1", "page.html");
        req.setSourceObjectType("page");
        req.setImportPolicy("files_and_body");
        req.setContentStream(new java.io.ByteArrayInputStream(
                "<p>body</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertFailureIsHonest(service.executeNoteImport(ctx(), req), "new-obj-id");
    }

    @Test
    @DisplayName("a mail failure after a dedupe skip still names the object and reaches the DLQ")
    void mailFailureAfterSkipIsHonest() {
        // The dedupe-skip shape: execute() returns the existing object, then the wrapper's
        // metadata step throws. The warning from the skip must survive the failure.
        wire(SourceArchetype.MESSAGE_CONTEXT);
        alreadyImported("mail-obj", "mail-1", "message");
        // D-7 moved the dedupe-skip path from applyMessageMetadata to the fill; the failing
        // step this test injects is now the fill.
        when(metadataService.fillMissingMessageMetadata(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("catalogue unreachable"));
        ExternalIngestRequest req = request("mail-1", "message.eml");
        req.setSourceObjectType("message");
        req.setMetadata(new LinkedHashMap<>(Map.of("mailboxId", "ishii@example.com")));
        req.setContentStream(new java.io.ByteArrayInputStream(
                ("From: otsuka@example.com\r\nTo: ishii@example.com\r\nSubject: minutes\r\n"
                        + "Message-ID: <m1@example.com>\r\n"
                        + "Date: Mon, 1 Jul 2024 09:00:00 +0900\r\n\r\nbody\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ExternalIngestResult result = service.executeMailImport(ctx(), req);

        assertFalse(result.isSuccess(), "control");
        assertEquals("mail-obj", result.objectId(),
                "the existing message was decorated, so the caller must be told which object");
        verify(jobService).saveToDlq(any(), any(), any());
    }

    @Test
    @DisplayName("a manual import still does not reach the DLQ")
    void manualImportIsNotDlqd() {
        // The existing rule, kept: an interactive caller sees the error directly and there is
        // nobody to retry on their behalf.
        wire(SourceArchetype.BUSINESS_RECORD);
        metadataExplodes();
        ExternalIngestRequest req = request("rec-2", "record.json");
        req.setSourceObjectType("record");
        // A manual run is a REAL authenticated context — that, not executionMode, is what the
        // gate reads now (P1-1(e) §1.1). The mode string stays purely informational; setting
        // it to "scheduled" here would NOT enroll this failure in the DLQ.
        req.setExecutionMode("scheduled");
        org.apache.chemistry.opencmis.commons.server.CallContext manualCtx =
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        org.mockito.Mockito.when(manualCtx.getUsername()).thenReturn("operator");

        ExternalIngestResult result = service.executeBusinessRecordImport(manualCtx, req);

        assertFalse(result.isSuccess(), "control");
        org.mockito.Mockito.verify(jobService, org.mockito.Mockito.never())
                .saveToDlq(any(), any(), any());
    }

    @Test
    @DisplayName("a failure keeps the warnings recorded before it")
    void failureKeepsEarlierWarnings() {
        // The warnings half of the fix was NOT tested: the dedupe-skip shape it was written
        // against produces an empty warnings list, so passing List.of() would have kept it green
        // (external review). This drives the case the javadoc actually names — a `replace` whose
        // delete failed, followed by a throw — where a real warning exists to lose.
        wire(SourceArchetype.FILE_SHARE);
        alreadyImported("old-obj", "file-7", "files");
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setDedupePolicy("replace");
        when(profileServiceRef.get("p1")).thenReturn(profile);
        org.mockito.Mockito.doThrow(new IllegalStateException("object is locked"))
                .when(objectServiceRef).deleteObject(any(), org.mockito.ArgumentMatchers.anyString(),
                        eq("old-obj"), any(), any());
        IngestLineageEmitter throwing = new IngestLineageEmitter() {
            @Override
            public String emitLineageEvent(String repositoryId, String objectId,
                    String targetFolderId, String documentName, String operationId,
                    ConnectorDefinition connector, ExternalIngestRequest request,
                    CapturedContent content, String executedBy, String onBehalfOf,
                    java.util.Map<CaptureEvidenceField, String> passOutcome) {
                throw new IllegalStateException("journal unreachable");
            }
        };
        service.setIngestLineageEmitter(throwing);

        ExternalIngestRequest req = request("file-7", "test.txt");
        req.setSourceObjectType("files");

        ExternalIngestResult result = service.execute(ctx(), req);

        assertFalse(result.isSuccess(), "control: the import must fail");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("could not be deleted")),
                "the replaced document survived next to the new one, and that is exactly what an "
                        + "operator needs to clean up. Got: " + result.warnings());
    }

    @Test
    @DisplayName("a failure inside execute() reports the committed object too")
    void executeFailureReportsTheCommittedObject() {
        // execute()'s own catch-all had the same defect: objectId was declared inside the try, so
        // the catch could not see it and every failure said "no object".
        //
        // Reaching that catch after the commit needs a step that THROWS, and today every
        // post-commit step catches its own exceptions — metadata, relationship, cache, the
        // idempotency record and the audit line all swallow. The emission call is the one that
        // does not: it is guarded only by a null check, so an emitter that throws propagates.
        // (That is also why this hoist matters: the outbox adds a throwing step right here.)
        wire(SourceArchetype.FILE_SHARE);
        IngestLineageEmitter throwing = new IngestLineageEmitter() {
            @Override
            public String emitLineageEvent(String repositoryId, String objectId,
                    String targetFolderId, String documentName, String operationId,
                    ConnectorDefinition connector, ExternalIngestRequest request,
                    CapturedContent content, String executedBy, String onBehalfOf,
                    java.util.Map<CaptureEvidenceField, String> passOutcome) {
                throw new IllegalStateException("journal unreachable");
            }
        };
        service.setIngestLineageEmitter(throwing);

        ExternalIngestRequest req = request("file-1", "test.txt");
        req.setSourceObjectType("files");

        ExternalIngestResult result = service.execute(ctx(), req);

        assertFalse(result.isSuccess(), "control: " + result.warnings());
        assertEquals("new-obj-id", result.objectId(),
                "the document was created before the failure; the catch reported null because "
                        + "objectId was declared inside the try");
        verify(jobService).saveToDlq(any(), any(), any());
    }
}
