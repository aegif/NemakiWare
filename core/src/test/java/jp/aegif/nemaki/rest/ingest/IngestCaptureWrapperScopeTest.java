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
import jp.aegif.nemaki.rest.ingest.capture.CaptureIntent;
import jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore;

import org.apache.chemistry.opencmis.commons.server.CallContext;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wrapper entry points and their child operations — the half of the boundary that the
 * plain-{@code execute} tests never reach.
 *
 * <p>The design's rules 3, 4 and 5 all live here: the wrapper owns a root scope because it keeps
 * writing after the internal execute returns; each attachment and the raw {@code .eml} own their
 * own scope; and a relationship made as part of a child's work belongs to that child. Every one
 * of those was got wrong at least once, and none of it was covered — the first review round
 * found four defects in this area precisely because nothing exercised it (external review).
 */
class IngestCaptureWrapperScopeTest {

    /** Records which intents were opened and completed, keyed by source object id. */
    private static class TrackingStore implements CaptureIntentStore {
        final List<CaptureIntent> opened = new ArrayList<>();
        final Map<String, Map<String, Object>> completed = new LinkedHashMap<>();
        final List<String> order = new ArrayList<>();
        boolean applies = true;

        @Override public boolean openIntent(CaptureIntent intent) {
            opened.add(intent);
            return true;
        }

        @Override public CaptureCompletion completeIntent(CaptureIntent intent,
                Map<String, Object> evidence) {
            completed.put(intent.sourceObjectId(), evidence);
            order.add("completeIntent:" + intent.sourceObjectId());
            return CaptureCompletion.COMPLETED;
        }

        @Override public boolean isActive() {
            return true;
        }

        @Override public Applicability appliesTo(String repositoryId) {
            return applies ? Applicability.APPLIES : Applicability.NOT_APPLICABLE;
        }

        /** The intents opened for a given source object id. */
        long openedFor(String sourceObjectId) {
            return opened.stream().filter(i -> sourceObjectId.equals(i.sourceObjectId())).count();
        }
    }

    private CanonicalImportServiceImpl service;
    private TrackingStore store;
    private IngestMetadataService metadataService;
    private jp.aegif.nemaki.cmis.service.ObjectService objectService;
    private final List<jp.aegif.nemaki.model.Content> children = new ArrayList<>();

    private String importPolicy;

    private void wire(SourceArchetype archetype, String importPolicy) {
        this.importPolicy = importPolicy;
        service = new CanonicalImportServiceImpl();
        store = new TrackingStore();
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ImportProfileDefinitionService profileService = mock(ImportProfileDefinitionService.class);
        objectService = mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        metadataService = mock(IngestMetadataService.class);
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);

        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);
        service.setVersioningService(mock(jp.aegif.nemaki.cmis.service.VersioningService.class));
        service.setIngestMetadataService(metadataService);
        service.setContentDaoService(contentDaoService);
        service.setCaptureIntentStore(store);

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

        final int[] n = {0};
        when(objectService.createDocument(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenAnswer(inv -> "obj-" + (++n[0]));
        // Fixed stubs rather than an Answer: an Answer that rebuilds the document on every call
        // hands applySourceMetadata a different object each time, and the aspects it just wrote
        // are gone by the time it reads them back.
        for (int i = 1; i <= 8; i++) {
            jp.aegif.nemaki.model.Document d = new jp.aegif.nemaki.model.Document();
            d.setId("obj-" + i);
            d.setType("cmis:document");
            d.setName("doc-" + i);
            d.setAspects(new ArrayList<>());
            org.mockito.Mockito.lenient()
                    .when(contentService.getContent("bedroom", "obj-" + i)).thenReturn(d);
            org.mockito.Mockito.lenient()
                    .when(contentService.update(any(), any(), org.mockito.ArgumentMatchers.eq(d)))
                    .thenReturn(d);
        }
        // The metadata helpers report that they will write, so the wrapper opens and records.
        when(metadataService.willWriteNoteMetadata(any())).thenReturn(true);
        when(metadataService.willWriteArchetypeMetadata(any(), any())).thenReturn(true);
    }

    private static CallContext ctx() {
        CallContext c = mock(CallContext.class);
        when(c.getUsername()).thenReturn("admin");
        return c;
    }

    private ExternalIngestRequest noteRequest(String sourceObjectId, String attachmentName) {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId(sourceObjectId);
        req.setSourceObjectType("page");
        req.setFileName("page.txt");
        // The policy is read from the REQUEST, not the profile.
        req.setImportPolicy(importPolicy);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pageId", sourceObjectId);
        meta.put("bodyText", "hello");
        if (attachmentName != null) {
            Map<String, Object> att = new LinkedHashMap<>();
            att.put("filename", attachmentName);
            att.put("contentBase64", java.util.Base64.getEncoder()
                    .encodeToString("attachment bytes".getBytes()));
            meta.put("attachments", List.of(att));
        }
        req.setMetadata(meta);
        return req;
    }

    // ── D5: what a pass decided NOT to take is on its completion row ─────────────────────

    @Test
    @DisplayName("a pseudo-file attachment lands in the page pass's completion evidence")
    void aSkippedAttachmentIsRecordedOnTheParentRow(){
        // A .DS_Store attachment produces no document, no aspect, no event and no row of its
        // own — until this, "we saw it and took nothing" had no durable record anywhere
        // (p1-1d-evidence-data-model.md D5). The parent page's completed row is where it lives.
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");

        service.executeNoteImport(ctx(), noteRequest("page-1", ".DS_Store"));

        Map<String, Object> evidence = store.completed.get("page-1");
        org.junit.jupiter.api.Assertions.assertNotNull(evidence, "the page pass must complete");
        Object recorded = evidence.get("attachmentsNotIngested");
        org.junit.jupiter.api.Assertions.assertNotNull(recorded,
                "the skipped attachment left no durable record: " + evidence.keySet());
        String flat = String.valueOf(recorded);
        assertTrue(flat.contains(".DS_Store"), flat);
        assertTrue(flat.toLowerCase().contains("pseudo"),
                "the record does not say WHY it was not ingested: " + flat);
    }

    @Test
    @DisplayName("an imported attachment is NOT in the not-ingested record — the control")
    void anImportedAttachmentIsNotRecordedAsSkipped(){
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");

        service.executeNoteImport(ctx(), noteRequest("page-1", "spec.pdf"));

        Map<String, Object> evidence = store.completed.get("page-1");
        org.junit.jupiter.api.Assertions.assertNotNull(evidence);
        org.junit.jupiter.api.Assertions.assertNull(evidence.get("attachmentsNotIngested"),
                "an attachment that WAS imported appeared in the not-ingested record — the "
                        + "record would cry wolf on every pass");
    }

    // ── Rules 4 and 5: children own their scope, and their links belong to them ───────────

    @Test
    @DisplayName("files_and_body gives the attachment its own intent, distinct from the page's")
    void filesAndBodyAttachmentOwnsItsIntent() {
        // This branch used the PUBLIC execute, which completed the attachment's row BEFORE the
        // page→attachment relationship existed and then recorded that relationship against the
        // PARENT. A failed link marked the attachment captured and the page unresolved — both
        // directions of misattribution rules 4 and 5 exist to prevent (external review).
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");

        service.executeNoteImport(ctx(), noteRequest("page-1", "spec.pdf"));

        assertEquals(1, store.openedFor("page-1"), "the page opens exactly one intent");
        assertTrue(store.opened.stream().anyMatch(i -> i.sourceObjectId() != null
                        && i.sourceObjectId().startsWith("page-1/att")),
                "the attachment must have an intent of its own: "
                        + store.opened.stream().map(CaptureIntent::sourceObjectId).toList());
        assertTrue(store.opened.size() >= 2,
                "one intent for the page and one for the attachment, not a shared one");
    }

    @Test
    @DisplayName("a failed attachment link blocks the ATTACHMENT's capture, not the page's")
    void failedLinkBlocksTheChildNotTheParent() {
        // The discriminating one. Both the public execute and the scope-taking overload give the
        // attachment an intent of its own, so counting rows proves nothing. What separates them
        // is WHEN the row completes and WHOSE scope the relationship lands on: through the public
        // execute the attachment is already CAPTURED before the link is attempted, and the
        // failure is recorded against the page (external review).
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");
        when(objectService.createRelationship(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("relationship type missing"));

        service.executeNoteImport(ctx(), noteRequest("page-8", "spec.pdf"));

        String attachmentId = store.opened.stream().map(CaptureIntent::sourceObjectId)
                .filter(id -> id != null && id.startsWith("page-8/att")).findFirst().orElse(null);
        org.junit.jupiter.api.Assertions.assertNotNull(attachmentId,
                "the attachment must have an intent: "
                        + store.opened.stream().map(CaptureIntent::sourceObjectId).toList());
        assertFalse(store.completed.containsKey(attachmentId),
                "the attachment's own link failed, so ITS capture must not complete: "
                        + store.completed.keySet());
        assertTrue(store.completed.containsKey("page-8"),
                "and the page, whose own changes all succeeded, must not be blamed for it: "
                        + store.completed.keySet());
    }

    @Test
    @DisplayName("each attachment gets its own intent, so one failure does not condemn the rest")
    void eachAttachmentOwnsItsIntent() {
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");
        ExternalIngestRequest req = noteRequest("page-2", null);
        Map<String, Object> meta = new LinkedHashMap<>(req.getMetadata());
        meta.put("attachments", List.of(
                attachment("a.pdf"), attachment("b.pdf"), attachment("c.pdf")));
        req.setMetadata(meta);

        service.executeNoteImport(ctx(), req);

        assertTrue(store.opened.size() >= 4,
                "one per attachment plus the page, not one shared row: " + store.opened.size());
    }

    private static Map<String, Object> attachment(String name) {
        Map<String, Object> att = new LinkedHashMap<>();
        att.put("filename", name);
        att.put("contentBase64",
                java.util.Base64.getEncoder().encodeToString(("bytes of " + name).getBytes()));
        return att;
    }

    // ── Rule 3: the wrapper's post-processing is inside its own scope ─────────────────────

    @Test
    @DisplayName("the wrapper's metadata update happens before its scope completes")
    void wrapperMetadataIsInsideTheScope() {
        // Completing inside the internal execute would snapshot a state that is not final — the
        // wrapper keeps writing after it returns.
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");
        when(metadataService.applyNoteMetadata(any(), any(), any(), any()))
                .thenAnswer(inv -> { store.order.add("applyNoteMetadata"); return null; });

        service.executeNoteImport(ctx(), noteRequest("page-3", null));

        int meta = store.order.indexOf("applyNoteMetadata");
        int done = store.order.indexOf("completeIntent:page-3");
        assertTrue(meta >= 0 && done >= 0 && meta < done,
                "the metadata update must be inside the boundary: " + store.order);
    }

    @Test
    @DisplayName("a metadata failure stops the capture completing")
    void wrapperMetadataFailureBlocksCapture() {
        // CAPTURED means every tracked change succeeded. Without the wrapper tracker a failed
        // metadata write still completed the row.
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");
        when(metadataService.applyNoteMetadata(any(), any(), any(), any()))
                .thenReturn("note metadata failed: conflict");

        ExternalIngestResult result = service.executeNoteImport(ctx(), noteRequest("page-4", null));

        assertTrue(store.completed.isEmpty(),
                "the row must stay open so the sweeper reports it as unresolved");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("note metadata failed")),
                result.warnings().toString());
    }

    @Test
    @DisplayName("a metadata helper with nothing to write neither opens nor records")
    void nothingToWriteOpensNothing() {
        // These helpers return null both when they wrote and when there was nothing to write.
        // Opening unconditionally produced a row that could never be completed, and recording it
        // as SUCCEEDED put a change in the evidence that never happened (external review).
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");
        when(metadataService.willWriteNoteMetadata(any())).thenReturn(false);
        alreadyImported("page-5-obj", "page-5", "page");

        service.executeNoteImport(ctx(), noteRequest("page-5", null));

        assertTrue(store.opened.isEmpty(),
                "a dedupe-skipped page whose metadata writes nothing changes nothing at all: "
                        + store.opened.stream().map(CaptureIntent::sourceObjectId).toList());
    }

    @Test
    @DisplayName("a metadata helper that WILL write does open — the control")
    void willWriteOpens() {
        // Without this, a fill that never ran its before-write hook would pass the test above
        // while writing outside the boundary. D-7 moved the skip path from willWrite+apply to
        // fillMissingNoteMetadata with an intent-opening hook, so the stub simulates a fill
        // that genuinely writes: it runs the hook, exactly as the real service does between
        // its decision and its write.
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");
        alreadyImported("page-6-obj", "page-6", "page");
        when(metadataService.fillMissingNoteMetadata(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    ((Runnable) inv.getArgument(4)).run();
                    return new IngestMetadataService.FillOutcome(null,
                            List.of("nemaki:notePageId"), List.of());
                });

        service.executeNoteImport(ctx(), noteRequest("page-6", null));

        assertFalse(store.opened.isEmpty(),
                "a dedupe skip whose metadata DOES write is a change, and needs an intent");
    }

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

    // ── AC 19 through a real entry point ─────────────────────────────────────────────────

    @Test
    @DisplayName("a repository the boundary does not cover is untouched by any wrapper")
    void notApplicableRepositoryIsUntouchedByWrappers() {
        // AC 19 was only ever covered against the fake scope, never through the ingest.
        wire(SourceArchetype.COMPOUND_NOTE, "files_and_body");
        store.applies = false;

        ExternalIngestResult result =
                service.executeNoteImport(ctx(), noteRequest("page-7", "spec.pdf"));

        assertTrue(result.isSuccess(), "errors=" + result.errors());
        assertTrue(store.opened.isEmpty());
        assertTrue(store.completed.isEmpty());
    }
}
