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
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two dedupe-stage failures used to reach only the log.
 *
 * <p>Both matter because the import continues afterwards and reports success:
 *
 * <ul>
 *   <li>{@code replace} deletes the existing document and then creates the replacement. When the
 *       delete failed, the code fell through and created it anyway — leaving BOTH documents — and
 *       said nothing.</li>
 *   <li>{@code replace_relationships_on_resync} exists to leave the object with only the incoming
 *       relationships. When deletions failed, the stale edges survived, again silently. Worse, the
 *       pagination re-fetches from offset zero after each pass (indices shift as rows are removed),
 *       so progress depended entirely on deletions succeeding: with every delete failing the same
 *       page came back for ever and {@code hasMoreItems()} stayed true. The import hung.</li>
 * </ul>
 *
 * <p>The timeout on the second test is the point of it — without the loop fix it does not fail,
 * it never returns.
 */
class IngestDedupeFailuresReachCallerTest {

    private CanonicalImportServiceImpl service;
    private jp.aegif.nemaki.cmis.service.ObjectService objectService;
    private jp.aegif.nemaki.cmis.service.RelationshipService relationshipService;
    private final List<jp.aegif.nemaki.model.Content> children = new ArrayList<>();

    private void wire(String dedupePolicy) {
        service = new CanonicalImportServiceImpl();
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ImportProfileDefinitionService profileService = mock(ImportProfileDefinitionService.class);
        objectService = mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        relationshipService = mock(jp.aegif.nemaki.cmis.service.RelationshipService.class);
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setRelationshipService(relationshipService);
        service.setContentService(contentService);
        service.setContentDaoService(contentDaoService);
        service.setIngestMetadataService(mock(IngestMetadataService.class));

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setDedupePolicy(dedupePolicy);
        when(profileService.get("p1")).thenReturn(profile);
        when(profileService.getForRepository("p1", "bedroom")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("acme");
        when(connectorService.get("c1")).thenReturn(connector);
        when(connectorService.countIndexFree("c1")).thenReturn(1);
        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                any(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("new-obj-id");

        Aspect integration = new Aspect();
        integration.setName("nemaki:externalIntegration");
        integration.setProperties(new ArrayList<>(List.of(
                new Property("nemaki:sourceObjectId", "file-1"),
                new Property("nemaki:sourceSystem", "acme"),
                new Property("nemaki:sourceObjectType", "files"))));
        jp.aegif.nemaki.model.Document existing = new jp.aegif.nemaki.model.Document();
        existing.setId("existing-1");
        existing.setType("cmis:document");
        existing.setName("test.txt");
        existing.setAspects(new ArrayList<>(List.of(integration)));
        children.add(existing);
        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(children);
        when(contentService.getContent("bedroom", "existing-1")).thenReturn(existing);
        when(contentService.getContent("bedroom", "new-obj-id")).thenReturn(existing);
    }

    private ExternalIngestResult run() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("file-1");
        req.setSourceObjectType("files");
        req.setFileName("test.txt");
        org.apache.chemistry.opencmis.commons.server.CallContext ctx = mock(
                org.apache.chemistry.opencmis.commons.server.CallContext.class);
        org.mockito.Mockito.when(ctx.getUsername()).thenReturn("test-user");
        return service.execute(ctx, req);
    }

    @Test
    @DisplayName("a failed replace-delete is reported, because the replacement is created anyway")
    void failedReplaceDeleteIsReported() {
        wire("replace");
        doThrow(new IllegalStateException("object is locked"))
                .when(objectService).deleteObject(any(), anyString(), eq("existing-1"),
                        any(), any());

        ExternalIngestResult result = run();

        assertTrue(result.isSuccess(), "the replacement was created, so this is a warning");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("could not be deleted")),
                "the old document survives next to the new one and the caller was told nothing. "
                        + "Got: " + result.warnings());
    }

    @Test
    @Timeout(30)
    @DisplayName("an empty page that still claims more is an incomplete listing, not an end")
    void anEmptyPageClaimingMoreIsIncomplete() {
        // The plan is formed by a read now, and the read used to stop on an empty page without
        // looking at hasMoreItems — returning the ids collected so far as a COMPLETE plan. The
        // deletion would then remove part of the edges and report success, leaving the rest
        // with no warning at all. Same silent-truncation shape as the size cap; a review found
        // it surviving the cap fix.
        wire("replace_relationships_on_resync");

        org.apache.chemistry.opencmis.commons.data.ObjectList empty =
                mock(org.apache.chemistry.opencmis.commons.data.ObjectList.class);
        when(empty.getObjects()).thenReturn(new ArrayList<>());
        when(empty.hasMoreItems()).thenReturn(Boolean.TRUE);
        when(relationshipService.getObjectRelationships(any(), anyString(), anyString(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(empty);

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("did not remove")),
                "an incomplete listing was treated as a complete plan and nothing was said. "
                        + "Got: " + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not established")),
                "the warning asserts what remains instead of saying it could not be checked. "
                        + "Got: " + result.warnings());
        verify(objectService, never()).deleteObject(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @Timeout(30)
    @DisplayName("a listing beyond the cap refuses instead of deleting part of the edges")
    void aListingBeyondTheCapRefuses() {
        // The cap used to return what it had collected, so the resync deleted 10,000 edges,
        // reported success, and left the rest without either warning.
        wire("replace_relationships_on_resync");

        java.util.List<org.apache.chemistry.opencmis.commons.data.ObjectData> many =
                new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            org.apache.chemistry.opencmis.commons.data.ObjectData rel =
                    mock(org.apache.chemistry.opencmis.commons.data.ObjectData.class);
            when(rel.getId()).thenReturn("rel-" + i);
            many.add(rel);
        }
        org.apache.chemistry.opencmis.commons.data.ObjectList page =
                mock(org.apache.chemistry.opencmis.commons.data.ObjectList.class);
        when(page.getObjects()).thenReturn(many);
        when(page.hasMoreItems()).thenReturn(Boolean.TRUE);   // never ends
        when(relationshipService.getObjectRelationships(any(), anyString(), anyString(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("did not remove")),
                "a listing too large to replace was treated as a complete plan. Got: "
                        + result.warnings());
        verify(objectService, never()).deleteObject(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @Timeout(30)
    @DisplayName("resync terminates and reports when every relationship delete fails")
    void failedResyncTerminatesAndIsReported() {
        wire("replace_relationships_on_resync");

        org.apache.chemistry.opencmis.commons.data.ObjectData rel =
                mock(org.apache.chemistry.opencmis.commons.data.ObjectData.class);
        when(rel.getId()).thenReturn("rel-1");
        org.apache.chemistry.opencmis.commons.data.ObjectList page =
                mock(org.apache.chemistry.opencmis.commons.data.ObjectList.class);
        when(page.getObjects()).thenReturn(new ArrayList<>(List.of(rel)));
        // The page never empties, because the deletes never succeed — and it keeps claiming there
        // is more. That combination is what looped for ever.
        when(page.hasMoreItems()).thenReturn(Boolean.TRUE);
        when(relationshipService.getObjectRelationships(any(), anyString(), anyString(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(page);
        doThrow(new IllegalStateException("relationship is referenced"))
                .when(objectService).deleteObject(any(), anyString(), eq("rel-1"), any(), any());

        ExternalIngestResult result = run();

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("did not remove")),
                "the policy promises the object is left with only the incoming relationships; "
                        + "stale edges surviving is not something to keep to the log. Got: "
                        + result.warnings());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("an unwired relationship service is not an empty edge list")
    void anUnwiredRelationshipServiceIsNotAnEmptyEdgeList() throws Exception {
        // An empty list is "this object has no relationships", and the caller acts on it:
        // replace_relationships_on_resync deletes nothing, warns about nothing, and reports
        // success — while its promise is that the object ends up with ONLY the incoming edges.
        // This file's own subject closed the identical arm twice before, in lookUpRelationship
        // and findExistingDocument, whose comments name each other. A review found the third.
        CanonicalImportServiceImpl bare = new CanonicalImportServiceImpl();
        java.lang.reflect.Method collect = CanonicalImportServiceImpl.class.getDeclaredMethod(
                "collectExistingRelationshipIds",
                org.apache.chemistry.opencmis.commons.server.CallContext.class,
                String.class, String.class);
        collect.setAccessible(true);

        java.lang.reflect.InvocationTargetException wrapped =
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> collect.invoke(bare, null, "bedroom", "doc-1"),
                        "an unwired relationship service answered 'this object has no edges',"
                                + " and the resync then reported success without replacing any");
        org.junit.jupiter.api.Assertions.assertTrue(
                wrapped.getCause().getMessage().contains("not wired"),
                "the refusal does not say what happened: " + wrapped.getCause().getMessage());
    }
}