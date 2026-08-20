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

import jp.aegif.nemaki.model.Content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ACL sync policy must actually take effect, and must say so when it does not.
 *
 * <p>It did neither. The import wrote its metadata with
 * {@code contentService.update(callContext, repositoryId, content)} and DISCARDED the result. The
 * DAO wraps the model in a {@code CouchDocument} and the new revision lands on the WRAPPER, so the
 * caller's object kept the revision it came in with. That same stale object was then handed to
 * {@code applyAclSyncPolicy}, whose own update was therefore a guaranteed conflict — and the
 * conflict was reduced to a {@code logger.warn} inside a {@code void} method. So
 * {@code aclSyncPolicy = none} and {@code copy_from_source} appear never to have taken effect on
 * this path, and nothing above the log line could tell (external review).
 *
 * <p>Both halves are pinned here: the revision that reaches the second update, and the failure
 * reaching the caller. Fixing only the staleness would leave the silence; fixing only the silence
 * would leave every such import reporting a warning forever.
 */
class IngestAclSyncPolicyTest {

    private CanonicalImportServiceImpl service;
    private jp.aegif.nemaki.businesslogic.ContentService contentService;
    private final List<Content> updated = new ArrayList<>();

    private ExternalIngestResult runImport(String aclSyncPolicy, boolean aclUpdateFails) {
        return runImport(aclSyncPolicy, aclUpdateFails, false);
    }

    private ExternalIngestResult runImport(String aclSyncPolicy, boolean aclUpdateFails,
            boolean withSourceAcl) {
        service = new CanonicalImportServiceImpl();
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ImportProfileDefinitionService profileService = mock(ImportProfileDefinitionService.class);
        jp.aegif.nemaki.cmis.service.ObjectService objectService =
                mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        contentService = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);
        service.setIngestMetadataService(mock(IngestMetadataService.class));

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setAclSyncPolicy(aclSyncPolicy);
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("acme");
        when(connectorService.get("c1")).thenReturn(connector);
        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                any(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("new-obj-id");

        // The stored object as first read back: revision rev-1.
        jp.aegif.nemaki.model.Document stored = new jp.aegif.nemaki.model.Document();
        stored.setId("new-obj-id");
        stored.setType("cmis:document");
        stored.setAspects(new ArrayList<>());
        stored.setSecondaryIds(new ArrayList<>());
        stored.setRevision("rev-1");
        when(contentService.getContent("bedroom", "new-obj-id")).thenReturn(stored);

        // update() returns a DIFFERENT object carrying the NEW revision — exactly what the real
        // DAO does, and exactly what the caller used to throw away.
        when(contentService.update(any(), eq("bedroom"), any())).thenAnswer(inv -> {
            Content in = inv.getArgument(2);
            updated.add(in);
            if (aclUpdateFails && updated.size() > 1) {
                throw new IllegalStateException("Document update conflict");
            }
            jp.aegif.nemaki.model.Document out = new jp.aegif.nemaki.model.Document();
            out.setId(in.getId());
            out.setType("cmis:document");
            out.setAspects(in.getAspects());
            out.setSecondaryIds(in.getSecondaryIds());
            out.setRevision("rev-" + (updated.size() + 1));
            return out;
        });

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("file-1");
        req.setSourceObjectType("files");
        req.setFileName("test.txt");
        if (withSourceAcl) {
            // copy_from_source only reaches its update when the persisted externalContext
            // actually carries a sourceAcl; without it the method is a no-op and a failure
            // injected at the update would never be reached.
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("sourceAcl", List.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                    "principalId", "otsuka", "permissions", List.of("cmis:read")))));
            req.setMetadata(metadata);
        }
        return service.execute(mock(
                org.apache.chemistry.opencmis.commons.server.CallContext.class), req);
    }

    @Test
    @DisplayName("the ACL step is handed the revision the metadata write produced, not the stale one")
    void aclStepReceivesTheFreshRevision() {
        ExternalIngestResult result = runImport("none", false);

        assertTrue(result.isSuccess(), "control: " + result.errors());
        assertEquals(2, updated.size(),
                "control: the metadata write and the ACL write are two separate updates");
        assertEquals("rev-1", updated.get(0).getRevision(), "control: the first update is rev-1");
        assertNotEquals("rev-1", updated.get(1).getRevision(),
                "the ACL update was handed the SAME object the metadata write came in with, so "
                        + "its revision was already superseded and the write was a guaranteed "
                        + "conflict. It must carry the revision the first update produced.");
        assertEquals("rev-2", updated.get(1).getRevision());
    }

    @Test
    @DisplayName("a failed ACL break reaches the caller instead of only the log")
    void failedAclBreakIsReported() {
        ExternalIngestResult result = runImport("none", true);

        assertTrue(result.isSuccess(),
                "the document is stored, so this is a warning rather than an error");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("ACL inheritance was NOT")),
                "a void method with a logger.warn told the caller nothing, and who can read the "
                        + "imported document is exactly what this decides. Got: "
                        + result.warnings());
    }

    @Test
    @DisplayName("a failed source-ACL copy reaches the caller too")
    void failedSourceAclCopyIsReported() {
        ExternalIngestResult result = runImport("copy_from_source", true, true);

        assertTrue(result.isSuccess());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Source ACL was NOT")),
                "under copy_from_source a failure leaves the document on the INHERITED ACL, "
                        + "which may be wider. Got: " + result.warnings());
    }

    @Test
    @DisplayName("having no source ACL to copy is a no-op, not a reported failure")
    void nothingToCopyIsNotAFailure() {
        // The distinction the design keeps insisting on: "there was nothing to do" must not be
        // reported the same way as "we tried and failed".
        ExternalIngestResult result = runImport("copy_from_source", false);

        assertTrue(result.isSuccess());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("Source ACL was NOT")),
                "no externalContext was recorded, so there was nothing to copy. Got: "
                        + result.warnings());
    }

    @Test
    @DisplayName("inherit_from_folder stays a no-op and writes no ACL")
    void inheritFromFolderIsANoOp() {
        ExternalIngestResult result = runImport("inherit_from_folder", false);

        assertTrue(result.isSuccess());
        assertEquals(1, updated.size(),
                "the CMIS default needs no ACL write at all — only the metadata update");
    }
}
