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

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A delete the store answers "not there" is the state the delete was for (R292, 3.4.2).
 *
 * <p>Since R288 a delete of an id that does not exist is answered {@code CmisObjectNotFoundException}; before it,
 * the delete returned in silence. The two ingest deletes that run against something another writer may have
 * removed first — the dedupe {@code replace} of the existing document, and the
 * {@code replace_relationships_on_resync} delete of the existing edges — took that answer for a failure: the replace
 * said "both now exist" with only the replacement there, and the resync said "stale edges remain" with none left.
 *
 * <p>The contract (owner, 2026-11-16): only that exception's type is "already gone", and only when the store, asked
 * again, holds no such row — a not-found thrown deeper in the delete is not about the target. Every other exception,
 * a refusal and a failed read stay the failure they were; an intent that cannot be written changes nothing.
 *
 * <p>The window — a target removed between the read that found it and this delete — is made by the fixture: the
 * delete marks the target gone and then throws. It was not made on a running product.
 *
 * <p>Wherever a lock says an exception is NOT "already gone", the fixture has the store answer the target as gone
 * as well, so only the type can tell the arms apart: answered "still there", the re-read alone would produce the
 * same failure and the lock would stay green with the type check removed.
 */
class IngestReadsAnAlreadyAbsentTargetAsGoneTest {

    /** A capture store that opens and completes, and records what each completion carried. */
    private static class RecordingStore implements CaptureIntentStore {
        final List<Map<String, Object>> completed = new ArrayList<>();
        boolean openSucceeds = true;

        @Override public boolean openIntent(CaptureIntent intent) {
            return openSucceeds;
        }

        @Override public CaptureCompletion completeIntent(CaptureIntent intent,
                Map<String, Object> evidence) {
            completed.add(evidence);
            return CaptureCompletion.COMPLETED;
        }

        @Override public boolean isActive() {
            return true;
        }

        @Override public Applicability appliesTo(String repositoryId) {
            return Applicability.APPLIES;
        }
    }

    private static final String EXISTING = "old-1";

    private CanonicalImportServiceImpl service;
    private jp.aegif.nemaki.cmis.service.ObjectService objectService;
    private jp.aegif.nemaki.cmis.service.RelationshipService relationshipService;
    private jp.aegif.nemaki.businesslogic.ContentService contentService;
    private RecordingStore store;
    private final List<jp.aegif.nemaki.model.Content> children = new ArrayList<>();
    /** The ids the fixture's store no longer holds. */
    private final Set<String> gone = new HashSet<>();
    /** The ids whose re-read fails. */
    private final Set<String> unreadable = new HashSet<>();
    /** The ids whose row this node's cache still holds after the store let it go. */
    private final Set<String> staleInCache = new HashSet<>();

    private void wire(String dedupePolicy) {
        service = new CanonicalImportServiceImpl();
        store = new RecordingStore();
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ImportProfileDefinitionService profileService = mock(ImportProfileDefinitionService.class);
        objectService = mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        relationshipService = mock(jp.aegif.nemaki.cmis.service.RelationshipService.class);
        contentService = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setRelationshipService(relationshipService);
        service.setContentService(contentService);
        service.setContentDaoService(contentDaoService);
        service.setVersioningService(mock(jp.aegif.nemaki.cmis.service.VersioningService.class));
        service.setIngestMetadataService(mock(IngestMetadataService.class));
        service.setCaptureIntentStore(store);

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setDedupePolicy(dedupePolicy);
        // The resync keeps the existing document; a metadata-only update keeps it from needing a
        // check-out the fixture would have to answer.
        profile.setUpdatePolicy("update_metadata_only");
        when(profileService.get("p1")).thenReturn(profile);
        when(profileService.getForRepository("p1", "bedroom")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("acme");
        when(connectorService.get("c1")).thenReturn(connector);
        when(connectorService.countIndexFree("c1")).thenReturn(1);
        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(children);

        when(objectService.createDocument(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenReturn("obj-1");
        jp.aegif.nemaki.model.Document created = document("obj-1", List.of());
        jp.aegif.nemaki.model.Document existing = document(EXISTING, List.of(integration()));
        children.add(existing);
        when(contentService.update(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));

        // The store: an id is held until the fixture marks it gone, and a read of an unreadable id
        // throws as a store that cannot be reached does. The node's cache answers the store's row,
        // except for an id whose row it still holds after the store let it go.
        when(contentDaoService.getContentFresh(eq("bedroom"), anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(1);
            if (unreadable.contains(id)) {
                throw new IllegalStateException("the store could not be reached");
            }
            return gone.contains(id) ? null : row(id, created, existing);
        });
        when(contentService.getContent(eq("bedroom"), anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(1);
            if (staleInCache.contains(id)) {
                return row(id, created, existing);
            }
            if (unreadable.contains(id)) {
                throw new IllegalStateException("the store could not be reached");
            }
            return gone.contains(id) ? null : row(id, created, existing);
        });
    }

    private static jp.aegif.nemaki.model.Content row(String id, jp.aegif.nemaki.model.Content created,
            jp.aegif.nemaki.model.Content existing) {
        if ("obj-1".equals(id)) {
            return created;
        }
        if (EXISTING.equals(id)) {
            return existing;
        }
        if (id.startsWith("rel-")) {
            jp.aegif.nemaki.model.Relationship rel = new jp.aegif.nemaki.model.Relationship();
            rel.setId(id);
            rel.setType("cmis:relationship");
            return rel;
        }
        return null;
    }

    private static Aspect integration() {
        Aspect integration = new Aspect();
        integration.setName("nemaki:externalIntegration");
        integration.setProperties(new ArrayList<>(List.of(
                new Property("nemaki:sourceObjectId", "src-1"),
                new Property("nemaki:sourceSystem", "acme"),
                new Property("nemaki:sourceObjectType", "file"))));
        return integration;
    }

    private static jp.aegif.nemaki.model.Document document(String id, List<Aspect> aspects) {
        jp.aegif.nemaki.model.Document doc = new jp.aegif.nemaki.model.Document();
        doc.setId(id);
        doc.setType("cmis:document");
        doc.setName("doc.txt");
        doc.setAspects(new ArrayList<>(aspects));
        return doc;
    }

    /** The existing document's edges, listed in one page. */
    private void edges(String... ids) {
        List<ObjectData> rels = new ArrayList<>();
        for (String id : ids) {
            ObjectData rel = mock(ObjectData.class);
            when(rel.getId()).thenReturn(id);
            rels.add(rel);
        }
        ObjectList page = mock(ObjectList.class);
        when(page.getObjects()).thenReturn(rels);
        when(page.hasMoreItems()).thenReturn(Boolean.FALSE);
        when(relationshipService.getObjectRelationships(any(), anyString(), eq(EXISTING), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(page);
    }

    /** The delete of {@code id} finds it removed by another writer: gone, and answered not found. */
    private void removedConcurrently(String id) {
        doAnswer(inv -> {
            gone.add(id);
            throw new CmisObjectNotFoundException("[objectId:" + id + "]The specified object is not found");
        }).when(objectService).deleteObject(any(), anyString(), eq(id), any(), any());
    }

    /** The delete of {@code id} throws {@code thrown} — with the store answering it gone all the same. */
    private void deleteThrowsWithTheTargetGone(String id, RuntimeException thrown) {
        doAnswer(inv -> {
            gone.add(id);
            throw thrown;
        }).when(objectService).deleteObject(any(), anyString(), eq(id), any(), any());
    }

    private ExternalIngestResult run() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("src-1");
        req.setSourceObjectType("file");
        req.setFileName("doc.txt");
        req.setContentStream(new java.io.ByteArrayInputStream("hello".getBytes()));
        req.setMimeType("text/plain");
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("admin");
        return service.execute(ctx, req);
    }

    /** The operations, with their outcomes, that the completed capture carries. */
    @SuppressWarnings("unchecked")
    private List<String> capturedMutations() {
        assertEquals(1, store.completed.size(),
                "the capture was not completed, so it carries nothing. completed=" + store.completed);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) store.completed.get(0).get("mutations")) {
            out.add(m.get("operation") + "=" + m.get("outcome"));
        }
        return out;
    }

    private static void verifyReplacementCreated(jp.aegif.nemaki.cmis.service.ObjectService objectService) {
        verify(objectService).createDocument(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    // ── replace ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("replace: a document already gone is replaced as usual, with no 'both now exist'")
    void replaceOfAnAlreadyAbsentDocumentIsTheIntendedState() {
        wire("replace");
        removedConcurrently(EXISTING);

        ExternalIngestResult result = run();

        assertTrue(result.isSuccess(), "errors=" + result.errors());
        verifyReplacementCreated(objectService);
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("could not be deleted")
                        || w.contains("both")),
                "a document another writer removed first was reported as surviving next to its"
                        + " replacement. Got: " + result.warnings());
        List<String> mutations = capturedMutations();
        assertTrue(mutations.contains("replaceDeleteAlreadyAbsent=SUCCEEDED"),
                "the capture does not say the document was already absent: " + mutations);
        assertTrue(mutations.stream().noneMatch(m -> m.startsWith("replaceDelete=")),
                "the capture records an ordinary delete for a document this operation did not remove: "
                        + mutations);
    }

    @Test
    @DisplayName("replace: an ordinary delete is recorded as it was, and is not called already absent")
    void replaceOfAPresentDocumentIsRecordedAsBefore() {
        wire("replace");

        ExternalIngestResult result = run();

        assertTrue(result.isSuccess(), "errors=" + result.errors());
        verifyReplacementCreated(objectService);
        assertTrue(result.warnings().isEmpty(), "warnings=" + result.warnings());
        List<String> mutations = capturedMutations();
        assertTrue(mutations.contains("replaceDelete=SUCCEEDED"), "mutations=" + mutations);
        assertFalse(mutations.contains("replaceDeleteAlreadyAbsent=SUCCEEDED"),
                "a delete that removed the document is recorded as finding it absent: " + mutations);
    }

    @Test
    @DisplayName("replace: a not-found while the store still holds the document is a failed delete")
    void replaceNotFoundWhileTheDocumentIsStillThereIsAFailure() {
        // A not-found thrown deeper in the delete — a relationship type being deleted, read while
        // the children are listed — is not about the document. The store still holds it.
        wire("replace");
        doAnswer(inv -> {
            throw new CmisObjectNotFoundException("Type 'x:link' is being deleted");
        }).when(objectService).deleteObject(any(), anyString(), eq(EXISTING), any(), any());

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("could not be deleted")
                        && w.contains("so both now exist")),
                "a document the store still holds was read as already gone. Got: " + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("replaceDelete=FAILED")),
                "a delete the store answered as not taking effect is not recorded as FAILED: "
                        + result.warnings());
        assertTrue(store.completed.isEmpty(),
                "a delete that did not take effect was recorded as complete: " + store.completed);
        verifyReplacementCreated(objectService);
    }

    @Test
    @DisplayName("replace: a not-found the store cannot confirm is not the intended state")
    void replaceNotFoundThatCannotBeReadAgainIsNotEstablished() {
        wire("replace");
        doAnswer(inv -> {
            unreadable.add(EXISTING);
            throw new CmisObjectNotFoundException("[objectId:" + EXISTING + "]The specified object is not found");
        }).when(objectService).deleteObject(any(), anyString(), eq(EXISTING), any(), any());

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("was not established as deleted")
                        && w.contains("could not be read") && w.contains("so both may now exist")),
                "a not-found the store could not confirm was read as gone, or said to leave both. Got: "
                        + result.warnings());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("so both now exist")
                        || w.contains("could not be deleted")),
                "the warning asserts the document survived, which is what could not be read: "
                        + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("replaceDelete=INDETERMINATE")),
                "a delete whose effect could not be read is not recorded as INDETERMINATE: "
                        + result.warnings());
        assertTrue(store.completed.isEmpty(),
                "a delete whose effect is unknown was recorded as complete: " + store.completed);
    }

    @Test
    @DisplayName("replace: another exception is a failed delete, even with the document gone")
    void replaceOtherExceptionIsAFailure() {
        wire("replace");
        deleteThrowsWithTheTargetGone(EXISTING, new IllegalStateException("object is locked"));

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("could not be deleted")
                        && w.contains("object is locked")),
                "an exception that is not a not-found was taken for one. Got: " + result.warnings());
        assertTrue(store.completed.isEmpty(),
                "a failed delete was recorded as complete: " + store.completed);
    }

    @Test
    @DisplayName("replace: a refusal is a failed delete, even with the document gone")
    void replaceRefusalIsAFailure() {
        wire("replace");
        deleteThrowsWithTheTargetGone(EXISTING, new CmisPermissionDeniedException("Permission Denied!"));

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("could not be deleted")
                        && w.contains("Permission Denied!")),
                "a refusal was taken for a not-found. Got: " + result.warnings());
        assertTrue(store.completed.isEmpty(),
                "a refused delete was recorded as complete: " + store.completed);
    }

    @Test
    @DisplayName("replace: a document gone from the store is gone, whatever this node's cache still holds")
    void replaceReadsTheStoreNotTheNodesCache() {
        // The re-read asked the cache-backed content service. A row the cache still held after the
        // store let it go answered "still there", and the replace said both exist (review, Codex P2).
        wire("replace");
        removedConcurrently(EXISTING);
        staleInCache.add(EXISTING);

        ExternalIngestResult result = run();

        assertTrue(result.isSuccess(), "errors=" + result.errors());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("could not be deleted")
                        || w.contains("both")),
                "the node's cache was read for the store's answer. Got: " + result.warnings());
        assertTrue(capturedMutations().contains("replaceDeleteAlreadyAbsent=SUCCEEDED"),
                "the capture does not say the document was already absent");
    }

    @Test
    @DisplayName("replace: a replacement that fails after the document was already gone names no removed document")
    void aFailedReplacementAfterAnAbsentDocumentNamesNoRemovedDocument() {
        // This operation removed nothing, so the failure has no committed object to name — naming
        // the absent document would say this import deleted it.
        wire("replace");
        removedConcurrently(EXISTING);
        when(objectService.createDocument(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenThrow(new IllegalStateException("disk full"));

        ExternalIngestResult result = run();

        assertFalse(result.isSuccess(), "the replacement failed: " + result.warnings());
        assertEquals(null, result.objectId(),
                "the failure names a document this operation did not remove");
    }

    // ── replace_relationships_on_resync ─────────────────────────────────────────────────

    @Test
    @Timeout(30)
    @DisplayName("resync: an edge already gone is not a stale edge")
    void resyncEdgeAlreadyAbsentIsNotAStaleEdge() {
        wire("replace_relationships_on_resync");
        edges("rel-1");
        removedConcurrently("rel-1");

        ExternalIngestResult result = run();

        assertTrue(result.isSuccess(), "errors=" + result.errors());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("did not remove")
                        || w.contains("stale edges")),
                "an edge another writer removed first was reported as remaining. Got: "
                        + result.warnings());
        List<String> mutations = capturedMutations();
        assertTrue(mutations.contains("removeRelationshipAlreadyAbsent=SUCCEEDED"),
                "the capture does not say the edge was already absent: " + mutations);
        assertTrue(mutations.stream().noneMatch(m -> m.startsWith("removeRelationship=")),
                "the capture records an ordinary delete for an edge this operation did not remove: "
                        + mutations);
    }

    @Test
    @Timeout(30)
    @DisplayName("resync: an edge gone from the store is gone, whatever this node's cache still holds")
    void resyncReadsTheStoreNotTheNodesCache() {
        wire("replace_relationships_on_resync");
        edges("rel-1");
        removedConcurrently("rel-1");
        staleInCache.add("rel-1");

        ExternalIngestResult result = run();

        assertTrue(result.isSuccess(), "errors=" + result.errors());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("did not remove")
                        || w.contains("stale edges")),
                "the node's cache was read for the store's answer. Got: " + result.warnings());
        assertTrue(capturedMutations().contains("removeRelationshipAlreadyAbsent=SUCCEEDED"),
                "the capture does not say the edge was already absent");
    }

    @Test
    @Timeout(30)
    @DisplayName("resync: an edge already gone beside one whose delete failed warns about the second only")
    void resyncMixedWarnsOnlyAboutTheFailedEdge() {
        wire("replace_relationships_on_resync");
        edges("rel-1", "rel-2");
        removedConcurrently("rel-1");
        deleteThrowsWithTheTargetGone("rel-2", new IllegalStateException("relationship is referenced"));

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("did not remove 1 existing")),
                "the failed edge must be the one warned about, and only it. Got: " + result.warnings());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("did not remove 2")),
                "the edge that was already gone was counted as remaining. Got: " + result.warnings());
        assertTrue(store.completed.isEmpty(),
                "a resync with a failed delete was recorded as complete: " + store.completed);
    }

    @Test
    @Timeout(30)
    @DisplayName("resync: a refusal is a failed delete, even with the edge gone")
    void resyncRefusalIsAFailure() {
        wire("replace_relationships_on_resync");
        edges("rel-1");
        deleteThrowsWithTheTargetGone("rel-1", new CmisPermissionDeniedException("Permission Denied!"));

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("did not remove 1 existing")),
                "a refusal was taken for a not-found. Got: " + result.warnings());
        assertTrue(store.completed.isEmpty(),
                "a refused delete was recorded as complete: " + store.completed);
    }

    @Test
    @Timeout(30)
    @DisplayName("resync: a not-found while the store still holds the edge is a stale edge")
    void resyncNotFoundWhileTheEdgeIsStillThereIsAFailure() {
        wire("replace_relationships_on_resync");
        edges("rel-1");
        doAnswer(inv -> {
            throw new CmisObjectNotFoundException("Type 'x:link' is being deleted");
        }).when(objectService).deleteObject(any(), anyString(), eq("rel-1"), any(), any());

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("did not remove 1 existing")
                        && w.contains("stale edges remain")),
                "an edge the store still holds was read as already gone. Got: " + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("removeRelationship=FAILED")),
                "a delete the store answered as not taking effect is not recorded as FAILED: "
                        + result.warnings());
        assertTrue(store.completed.isEmpty(),
                "a delete that did not take effect was recorded as complete: " + store.completed);
    }

    @Test
    @Timeout(30)
    @DisplayName("resync: a not-found the store cannot confirm is said to be unestablished, not stale")
    void resyncNotFoundThatCannotBeReadAgainIsNotEstablished() {
        wire("replace_relationships_on_resync");
        edges("rel-1");
        doAnswer(inv -> {
            unreadable.add("rel-1");
            throw new CmisObjectNotFoundException("[objectId:rel-1]The specified object is not found");
        }).when(objectService).deleteObject(any(), anyString(), eq("rel-1"), any(), any());

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("did not establish that 1")
                        && w.contains("was not established")),
                "a not-found the store could not confirm was read as gone. Got: " + result.warnings());
        // The asserting form: "did not remove N …; stale edges remain alongside". The
        // unestablished wording names the same words only inside "whether … was not established".
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("did not remove")
                        || w.contains("; stale edges remain")),
                "the warning asserts stale edges remain, which is what could not be read: "
                        + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("removeRelationship=INDETERMINATE")),
                "a delete whose effect could not be read is not recorded as INDETERMINATE: "
                        + result.warnings());
        assertTrue(store.completed.isEmpty(),
                "a delete whose effect is unknown was recorded as complete: " + store.completed);
    }

    @Test
    @Timeout(30)
    @DisplayName("resync: an ordinary delete is recorded as it was, with no warning")
    void resyncOrdinaryDeleteIsRecordedAsBefore() {
        wire("replace_relationships_on_resync");
        edges("rel-1");

        ExternalIngestResult result = run();

        assertTrue(result.isSuccess(), "errors=" + result.errors());
        assertTrue(result.warnings().isEmpty(), "warnings=" + result.warnings());
        verify(objectService).deleteObject(any(), anyString(), eq("rel-1"), any(), any());
        List<String> mutations = capturedMutations();
        assertTrue(mutations.contains("removeRelationship=SUCCEEDED"), "mutations=" + mutations);
        assertFalse(mutations.contains("removeRelationshipAlreadyAbsent=SUCCEEDED"),
                "a delete that removed the edge is recorded as finding it absent: " + mutations);
    }

    @Test
    @Timeout(30)
    @DisplayName("resync: an intent that cannot be written deletes no edge and updates nothing")
    void resyncUnwritableIntentChangesNothing() {
        wire("replace_relationships_on_resync");
        edges("rel-1");
        removedConcurrently("rel-1");
        store.openSucceeds = false;

        ExternalIngestResult result = run();

        assertFalse(result.isSuccess(), "an unwritable intent became a warning: " + result.warnings());
        verify(objectService, never()).deleteObject(any(), any(), any(), any(), any());
        verify(contentService, never()).update(any(), any(), any());
        verify(objectService, never()).createDocument(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("replace: an intent that cannot be written deletes nothing and creates nothing")
    void replaceUnwritableIntentChangesNothing() {
        wire("replace");
        removedConcurrently(EXISTING);
        store.openSucceeds = false;

        ExternalIngestResult result = run();

        assertFalse(result.isSuccess(), "an unwritable intent became a warning: " + result.warnings());
        verify(objectService, never()).deleteObject(any(), any(), any(), any(), any());
        verify(objectService, never()).createDocument(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }
}
