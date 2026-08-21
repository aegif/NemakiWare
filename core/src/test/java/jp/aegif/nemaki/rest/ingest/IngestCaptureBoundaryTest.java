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
import jp.aegif.nemaki.rest.ingest.capture.CaptureScope;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The boundary as the ingest path actually uses it: intent first, or no change at all.
 *
 * <p>What is being prevented is a document whose origin nothing records. Content goes to the
 * repository database and evidence to the shared lineage database, with no transaction across
 * the two, so the only thing that makes the bad state unreachable is writing the evidence
 * <em>first</em>.
 *
 * <h2>What these tests do NOT cover</h2>
 *
 * <p>They drive {@code execute} with mocked CMIS services, so they establish the ordering and the
 * outcome bookkeeping, not that CouchDB accepted anything. The store's own behaviour is covered
 * by {@code CouchCaptureIntentStoreTest}.
 */
class IngestCaptureBoundaryTest {

    /** Records the order of intent writes against document writes. */
    private static class RecordingStore implements CaptureIntentStore {
        final List<String> events = new ArrayList<>();
        final List<Map<String, Object>> completed = new ArrayList<>();
        boolean openSucceeds = true;
        boolean active = true;

        @Override public boolean openIntent(CaptureIntent intent) {
            events.add("openIntent");
            return openSucceeds;
        }

        @Override public CaptureCompletion completeIntent(CaptureIntent intent,
                Map<String, Object> evidence) {
            events.add("completeIntent");
            completed.add(evidence);
            return CaptureCompletion.COMPLETED;
        }

        @Override public boolean isActive() {
            return active;
        }

        @Override public boolean appliesTo(String repositoryId) {
            return applies;
        }

        boolean applies = true;
    }

    private CanonicalImportServiceImpl service;
    private jp.aegif.nemaki.cmis.service.ObjectService objectService;
    private jp.aegif.nemaki.businesslogic.ContentService contentService;
    private RecordingStore store;
    private ImportProfileDefinitionService profileService;
    private final List<jp.aegif.nemaki.model.Content> children = new ArrayList<>();

    private void wire() {
        service = new CanonicalImportServiceImpl();
        store = new RecordingStore();
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        profileService = mock(ImportProfileDefinitionService.class);
        objectService = mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        contentService = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);

        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);
        service.setVersioningService(mock(jp.aegif.nemaki.cmis.service.VersioningService.class));
        service.setIngestMetadataService(mock(IngestMetadataService.class));
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
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("acme");
        when(connectorService.get("c1")).thenReturn(connector);
        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(children);

        // createDocument records its own position in the same sequence as the intent write.
        when(objectService.createDocument(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenAnswer(inv -> {
                    store.events.add("createDocument");
                    return "obj-1";
                });
        // The created object must be readable back. Leaving getContent unstubbed made
        // applySourceMetadata report "could not read it back" — correctly recorded as
        // INDETERMINATE — so no capture could ever complete, and the harness would have been
        // testing a permanently broken repository.
        jp.aegif.nemaki.model.Document created = new jp.aegif.nemaki.model.Document();
        created.setId("obj-1");
        created.setType("cmis:document");
        created.setName("doc.txt");
        created.setAspects(new ArrayList<>());
        when(contentService.getContent("bedroom", "obj-1")).thenReturn(created);
        when(contentService.update(any(), any(), any())).thenReturn(created);
    }

    private ExternalIngestRequest request(String sourceObjectId) {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId(sourceObjectId);
        req.setSourceObjectType("file");
        req.setFileName("doc.txt");
        req.setContentStream(new java.io.ByteArrayInputStream("hello".getBytes()));
        req.setMimeType("text/plain");
        return req;
    }

    private static CallContext ctx() {
        CallContext c = mock(CallContext.class);
        when(c.getUsername()).thenReturn("admin");
        return c;
    }

    private void alreadyImported(String objectId, String sourceObjectId) {
        Aspect integration = new Aspect();
        integration.setName("nemaki:externalIntegration");
        integration.setProperties(new ArrayList<>(List.of(
                new Property("nemaki:sourceObjectId", sourceObjectId),
                new Property("nemaki:sourceSystem", "acme"),
                new Property("nemaki:sourceObjectType", "file"))));
        jp.aegif.nemaki.model.Document doc = new jp.aegif.nemaki.model.Document();
        doc.setId(objectId);
        doc.setType("cmis:document");
        doc.setName(objectId);
        doc.setAspects(new ArrayList<>(List.of(integration)));
        children.add(doc);
        when(contentService.getContent("bedroom", objectId)).thenReturn(doc);
    }

    // ── Ordering ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the intent is written before the document is created")
    void intentPrecedesTheDocument() {
        // The whole point. Reverse these two and a crash in between leaves a document that
        // nothing records the origin of — silently, because the evidence write is swallowed.
        wire();

        service.execute(ctx(), request("src-1"));

        assertEquals(List.of("openIntent", "createDocument", "completeIntent"), store.events);
    }

    @Test
    @DisplayName("a dry run opens no intent at all")
    void dryRunOpensNothing() {
        // Rule 1: no change, no row. An intent opened here could never be completed — there is
        // no object — and the sweeper would call it unresolved, which is false.
        wire();
        ExternalIngestRequest req = request("src-1");
        req.setDryRun(true);

        service.execute(ctx(), req);

        assertTrue(store.events.isEmpty(), "dry run must leave no trace: " + store.events);
    }

    @Test
    @DisplayName("a skip that changes nothing opens no intent")
    void skipOpensNothing() {
        wire();
        alreadyImported("obj-1", "src-1");
        service.execute(ctx(), request("src-1"));

        assertTrue(store.events.isEmpty(), store.events.toString());
    }

    @Test
    @DisplayName("a validation failure before any change opens no intent")
    void earlyValidationFailureOpensNothing() {
        wire();
        ExternalIngestRequest req = request("src-1");
        req.setProfileId("does-not-exist");

        ExternalIngestResult result = service.execute(ctx(), req);

        assertFalse(result.isSuccess());
        assertTrue(store.events.isEmpty(), store.events.toString());
    }

    // ── Fail-closed ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an intent that cannot be written stops the ingest before it creates anything")
    void unwritableIntentStopsTheIngest() {
        wire();
        store.openSucceeds = false;

        ExternalIngestResult result = service.execute(ctx(), request("src-1"));

        assertFalse(result.isSuccess(), "the caller must not be told the import succeeded");
        verify(objectService, never()).createDocument(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("with no store wired the ingest proceeds untouched")
    void noStoreMeansNoBoundary() {
        // The condition the fail-closed behaviour hangs on is the collaborator being present.
        // A deployment that does not run lineage at all must keep working.
        wire();
        service.setCaptureIntentStore(null);

        ExternalIngestResult result = service.execute(ctx(), request("src-1"));

        assertTrue(result.isSuccess(), "errors=" + result.errors());
        // Only the intent events: the createDocument marker shares this list, and it SHOULD be
        // there — the ingest must still work with no boundary wired.
        assertTrue(store.events.stream().noneMatch(e -> e.startsWith("openIntent")
                || e.startsWith("completeIntent")), store.events.toString());
        assertTrue(store.events.contains("createDocument"),
                "the document must still be created: " + store.events);
    }

    @Test
    @DisplayName("an unreachable store on an applicable repository refuses the ingest")
    void unreachableStoreRefusesWhenApplicable() {
        // Changed deliberately. An inert scope here used to let the ingest create the document
        // and report success with no evidence and no warning — a provisioning failure silently
        // turning the whole boundary off (external review). Under a mode the operator chose,
        // "we cannot record this" is a refusal.
        wire();
        store.active = false;

        ExternalIngestResult result = service.execute(ctx(), request("src-1"));

        assertFalse(result.isSuccess());
        verify(objectService, never()).createDocument(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unreachable store on a repository the boundary skips changes nothing")
    void unreachableStoreIsIrrelevantWhenNotApplicable() {
        // The mode is asked BEFORE the store is touched, so a disabled repository never reaches
        // the availability question — and never provisions the lineage database either.
        wire();
        store.active = false;
        store.applies = false;

        assertTrue(service.execute(ctx(), request("src-1")).isSuccess());
        assertTrue(store.events.stream().noneMatch(e -> e.startsWith("openIntent")));
    }

    // ── Completion ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a successful ingest completes the capture and records the object")
    void successCompletesWithTheObjectId() {
        wire();

        service.execute(ctx(), request("src-1"));

        assertEquals(1, store.completed.size());
        assertEquals("obj-1", store.completed.get(0).get("objectId"));
        assertTrue(store.completed.get(0).get("mutations").toString().contains("createDocument"));
    }

    @Test
    @DisplayName("an ingest that returns errors is not recorded as captured")
    void failedIngestIsNotCaptured() {
        // CAPTURED means the operation ran to the end. Every individual change can have
        // succeeded and the operation still have failed after them.
        wire();
        when(objectService.createDocument(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenThrow(new RuntimeException("disk full"));

        ExternalIngestResult result = service.execute(ctx(), request("src-1"));

        assertFalse(result.isSuccess());
        assertTrue(store.completed.isEmpty(),
                "the row must stay open so the sweeper reports it as unresolved");
    }

    @Test
    @DisplayName("a capture that could not be recorded is a warning, not a failed import")
    void unrecordedCaptureIsAWarning() {
        // The content is already committed by this point. Turning it into an error would tell
        // the caller nothing was imported when something was.
        wire();
        CaptureIntentStore refusing = new RecordingStore() {
            @Override public CaptureCompletion completeIntent(CaptureIntent intent,
                    Map<String, Object> evidence) {
                return CaptureCompletion.ROW_UNREADABLE;
            }
        };
        service.setCaptureIntentStore(refusing);

        ExternalIngestResult result = service.execute(ctx(), request("src-1"));

        assertTrue(result.isSuccess(), "the document WAS created; the import did not fail");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("could not be read")),
                "the caller has to be told the evidence is missing: " + result.warnings());
    }

    // ── The fail-closed exception must not be swallowed ──────────────────────────────────

    @Test
    @DisplayName("an unwritable intent stops the REPLACE path too, instead of replacing anyway")
    void unwritableIntentStopsReplace() {
        // The replace branch deletes the existing document inside a local try whose catch turns
        // any exception into a warning and then falls through to create the replacement. With
        // the fail-closed exception caught there, an intent that could not be written produced
        // a warning and the ingest carried on changing things (external review).
        wire();
        alreadyImported("obj-1", "src-1");
        ImportProfileDefinition replacing = new ImportProfileDefinition();
        replacing.setProfileId("p1");
        replacing.setEnabled(true);
        replacing.setTargetFolderId("folder-1");
        replacing.setRepositoryId("bedroom");
        replacing.setDedupePolicy("replace");
        when(profileService.get("p1")).thenReturn(replacing);
        store.openSucceeds = false;

        ExternalIngestResult result = service.execute(ctx(), request("src-1"));

        assertFalse(result.isSuccess(),
                "an unwritable intent must fail the ingest, not become a warning: "
                        + result.warnings());
        verify(objectService, never()).deleteObject(any(), any(), any(), any(), any());
        verify(objectService, never()).createDocument(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unwritable intent stops a metadata-only update too")
    void unwritableIntentStopsMetadataOnlyUpdate() {
        // On this path applySourceMetadata holds the FIRST change, and its catch turns anything
        // thrown into a warning string while the ingest returns success.
        wire();
        alreadyImported("obj-1", "src-1");
        ImportProfileDefinition metadataOnly = new ImportProfileDefinition();
        metadataOnly.setProfileId("p1");
        metadataOnly.setEnabled(true);
        metadataOnly.setTargetFolderId("folder-1");
        metadataOnly.setRepositoryId("bedroom");
        // The default dedupe policy skips outright, which never reaches the update policy.
        metadataOnly.setDedupePolicy("update_existing");
        metadataOnly.setUpdatePolicy("update_metadata_only");
        when(profileService.get("p1")).thenReturn(metadataOnly);
        store.openSucceeds = false;

        ExternalIngestResult result = service.execute(ctx(), request("src-1"));

        assertFalse(result.isSuccess(), "warnings=" + result.warnings());
        verify(contentService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("a writable intent still lets the replace path run — the control")
    void writableIntentLetsReplaceRun() {
        // Without this, throwing unconditionally would pass both tests above while breaking
        // every replace import.
        wire();
        alreadyImported("obj-1", "src-1");
        ImportProfileDefinition replacing = new ImportProfileDefinition();
        replacing.setProfileId("p1");
        replacing.setEnabled(true);
        replacing.setTargetFolderId("folder-1");
        replacing.setRepositoryId("bedroom");
        replacing.setDedupePolicy("replace");
        when(profileService.get("p1")).thenReturn(replacing);

        ExternalIngestResult result = service.execute(ctx(), request("src-1"));

        assertTrue(result.isSuccess(), "errors=" + result.errors());
        verify(objectService).deleteObject(any(), any(), any(), any(), any());
    }

    // ── The scope itself ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the scope is created un-opened")
    void scopeStartsUnopened() {
        wire();

        CaptureScope scope = service.newCaptureScope(ctx(), request("src-1"));

        assertTrue(scope.isActive());
        assertFalse(scope.isOpened());
        assertTrue(store.events.isEmpty());
    }

    @Test
    @DisplayName("the row carries the connector's source system and archetype")
    void rowIsDescribedBeforeItIsWritten() {
        // Neither is knowable when the wrapper builds the scope; both are resolved inside
        // execute. Nothing is written until the first change, so they still reach the row.
        wire();
        final List<CaptureIntent> opened = new ArrayList<>();
        service.setCaptureIntentStore(new RecordingStore() {
            @Override public boolean openIntent(CaptureIntent intent) {
                opened.add(intent);
                return true;
            }
        });

        service.execute(ctx(), request("src-1"));

        assertEquals(1, opened.size());
        assertEquals("acme", opened.get(0).sourceSystem());
        assertEquals("FILE_SHARE", opened.get(0).processType());
        assertEquals("bedroom", opened.get(0).repositoryId());
        assertEquals("src-1", opened.get(0).sourceObjectId());
        assertEquals("admin", opened.get(0).executedBy());
    }
}
