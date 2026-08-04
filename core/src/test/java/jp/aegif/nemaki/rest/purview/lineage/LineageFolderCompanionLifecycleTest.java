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
package jp.aegif.nemaki.rest.purview.lineage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/**
 * Every folder event, and what it is allowed to do to the companion.
 *
 * <p>The one thing under test throughout is that deletion of a folder never becomes deletion of
 * its companion: past lineage points at it, and a Process whose input vanished reads as a bug in
 * the lineage rather than as the history it exists to record.
 */
public class LineageFolderCompanionLifecycleTest {

    private static final String REPO = "bedroom";
    private static final String COMPANION_QN = "nemaki://bedroom/folders/f-1/dataset";

    private PurviewEntityRegistryClient client;
    private LineageFolderCompanionLifecycleImpl lifecycle;

    private static Folder folder(String id, String name) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setName(name);
        folder.setType("cmis:folder");
        return folder;
    }

    /** What the catalog would return for an existing companion. */
    private static Map<String, Object> storedCompanion(String name, String sourceState) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", COMPANION_QN);
        attributes.put("name", name);
        attributes.put("repositoryId", REPO);
        attributes.put("objectId", "f-1");
        attributes.put("active", true);
        attributes.put("sourceState", sourceState);
        return Map.of("entity", Map.of("attributes", attributes));
    }

    @BeforeEach
    void setUp() {
        client = mock(PurviewEntityRegistryClient.class);
        MetadataCatalogConnectionResolver resolver =
                mock(MetadataCatalogConnectionResolver.class);
        when(resolver.buildConnectionRequest()).thenReturn(null);
        lifecycle = new LineageFolderCompanionLifecycleImpl(
                resolver, new PurviewEntityPayloadFactory(), client);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedAttributes() throws Exception {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).bulkCreateOrUpdateEntities(any(), payload.capture());
        List<Map<String, Object>> entities =
                (List<Map<String, Object>>) payload.getValue().get("entities");
        return (Map<String, Object>) entities.get(0).get("attributes");
    }

    @Nested
    @DisplayName("create and update")
    class CreateAndUpdate {

        @Test
        @DisplayName("a folder gets a companion; a document does not")
        void onlyFoldersGetCompanions() {
            assertNull(lifecycle.companionFor(REPO, null));
            Document document = new Document();
            document.setId("d-1");
            document.setType("cmis:document");
            assertNull(lifecycle.companionFor(REPO, document));

            Map<String, Object> companion = lifecycle.companionFor(REPO, folder("f-1", "Reports"));
            assertEquals("nemaki_folder_dataset", companion.get("typeName"));
        }

        /**
         * A rename must reach the companion, or the catalog keeps showing the old name for a
         * folder that no longer has it. The identity does not move, because it never depended
         * on the name.
         */
        @Test
        @DisplayName("a rename updates the name and leaves the identity alone")
        void renameUpdatesNameNotIdentity() {
            @SuppressWarnings("unchecked")
            Map<String, Object> before = (Map<String, Object>)
                    lifecycle.companionFor(REPO, folder("f-1", "Old")).get("attributes");
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>)
                    lifecycle.companionFor(REPO, folder("f-1", "New")).get("attributes");

            assertEquals("Old", before.get("name"));
            assertEquals("New", after.get("name"));
            assertEquals(before.get("qualifiedName"), after.get("qualifiedName"));
            assertEquals(COMPANION_QN, after.get("qualifiedName"));
        }

        /** A move changes the parent, and the companion depends on neither parent nor path. */
        @Test
        @DisplayName("a move changes nothing about the companion")
        void moveChangesNothing() {
            Folder before = folder("f-1", "Reports");
            before.setParentId("p-1");
            Folder after = folder("f-1", "Reports");
            after.setParentId("p-2");

            assertEquals(lifecycle.companionFor(REPO, before), lifecycle.companionFor(REPO, after));
        }

        @Test
        @DisplayName("re-running create produces the identical payload")
        void createIsIdempotent() {
            assertEquals(lifecycle.companionFor(REPO, folder("f-1", "Reports")),
                    lifecycle.companionFor(REPO, folder("f-1", "Reports")));
        }
    }

    @Nested
    @DisplayName("ties")
    class Ties {

        @Test
        @DisplayName("one relationship per folder, and none for a document")
        void tiesOnlyFolders() throws Exception {
            when(client.createRelationship(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.success(1, "created"));
            Document document = new Document();
            document.setId("d-1");
            document.setType("cmis:document");

            int tied = lifecycle.tie(REPO,
                    List.of(folder("f-1", "a"), document, folder("f-2", "b")), Map.of());

            assertEquals(2, tied);
            verify(client, times(2)).createRelationship(any(), any());
        }

        /** The client turns the catalog's duplicate answer into success; a retry converges. */
        @Test
        @DisplayName("a repeat is a success, not a failure")
        void repeatIsSuccess() throws Exception {
            when(client.createRelationship(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.success(1, "relationship already exists"));

            assertEquals(1, lifecycle.tie(REPO, List.of(folder("f-1", "a")), Map.of()));
            assertEquals(1, lifecycle.tie(REPO, List.of(folder("f-1", "a")), Map.of()));
        }

        /**
         * One relationship failing must not take the batch with it: the entities it was for are
         * already published, and an exception here would lose that work.
         */
        @Test
        @DisplayName("a failed tie is counted, not thrown")
        void failedTieDoesNotThrow() throws Exception {
            when(client.createRelationship(any(), any()))
                    .thenThrow(new PurviewClientException("boom"));

            assertEquals(0, lifecycle.tie(REPO, List.of(folder("f-1", "a")), Map.of()));
        }

        @Test
        @DisplayName("a client that answers nothing is a failed tie, not an NPE")
        void nullResultIsAFailedTie() throws Exception {
            when(client.createRelationship(any(), any())).thenReturn(null);
            assertEquals(0, lifecycle.tie(REPO, List.of(folder("f-1", "a")), Map.of()));
        }
    }

    @Nested
    @DisplayName("lifecycle transitions")
    class Transitions {

        @BeforeEach
        void companionExists() throws Exception {
            when(client.getEntityByUniqueAttribute(any(), eq("nemaki_folder_dataset"),
                    eq("qualifiedName"), anyString()))
                    .thenReturn(storedCompanion("Reports", "ACTIVE"));
            when(client.bulkCreateOrUpdateEntities(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.success(1, "ok"));
        }

        @Test
        @DisplayName("archiving marks the companion, and does not delete it")
        void archiveMarksAndKeeps() throws Exception {
            assertTrue(lifecycle.markState(REPO, "f-1",
                    PurviewEntityPayloadFactory.SOURCE_STATE_ARCHIVED));

            Map<String, Object> attributes = capturedAttributes();
            assertEquals("ARCHIVED", attributes.get("sourceState"));
            assertEquals(Boolean.FALSE, attributes.get("active"));
            verify(client, never()).deleteByUniqueAttribute(any(), any(), any(), any());
        }

        @Test
        @DisplayName("purging marks the companion, and does not delete it either")
        void purgeMarksAndKeeps() throws Exception {
            assertTrue(lifecycle.markState(REPO, "f-1",
                    PurviewEntityPayloadFactory.SOURCE_STATE_PURGED));

            assertEquals("PURGED", capturedAttributes().get("sourceState"));
            verify(client, never()).deleteByUniqueAttribute(any(), any(), any(), any());
        }

        @Test
        @DisplayName("restoring puts it back")
        void restoreReactivates() throws Exception {
            assertTrue(lifecycle.markState(REPO, "f-1",
                    PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE));

            Map<String, Object> attributes = capturedAttributes();
            assertEquals("ACTIVE", attributes.get("sourceState"));
            assertEquals(Boolean.TRUE, attributes.get("active"));
        }

        /**
         * The name is the only record left of what the folder was called once it is gone, so a
         * transition must not overwrite it with a placeholder.
         */
        @Test
        @DisplayName("a transition keeps every attribute it does not own")
        void transitionPreservesTheRest() throws Exception {
            lifecycle.markState(REPO, "f-1", PurviewEntityPayloadFactory.SOURCE_STATE_PURGED);

            Map<String, Object> attributes = capturedAttributes();
            assertEquals("Reports", attributes.get("name"));
            assertEquals(REPO, attributes.get("repositoryId"));
            assertEquals("f-1", attributes.get("objectId"));
            assertEquals(COMPANION_QN, attributes.get("qualifiedName"));
        }

        /**
         * A catalog bulk write MERGES, so an attribute the payload omits keeps its old value.
         * Writing back whatever the read returned would therefore preserve a field this build
         * no longer declares — forever, and with nothing to say why it is there.
         */
        @Test
        @DisplayName("an attribute this build no longer declares is dropped, not carried forward")
        void staleAttributesAreRemoved() throws Exception {
            java.util.Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("qualifiedName", COMPANION_QN);
            stored.put("name", "Reports");
            stored.put("repositoryId", REPO);
            stored.put("objectId", "f-1");
            stored.put("active", true);
            stored.put("sourceState", "ACTIVE");
            // Left behind by an older shape of the type.
            stored.put("legacyFolderPath", "/old/location");
            stored.put("proxyVersion", 1);
            when(client.getEntityByUniqueAttribute(any(), any(), any(), any()))
                    .thenReturn(Map.of("entity", Map.of("attributes", stored)));

            assertTrue(lifecycle.markState(REPO, "f-1",
                    PurviewEntityPayloadFactory.SOURCE_STATE_ARCHIVED));

            Map<String, Object> written = capturedAttributes();
            assertFalse(written.containsKey("legacyFolderPath"),
                    "a stale attribute survived a transition and would survive every later one");
            assertFalse(written.containsKey("proxyVersion"));
            // And the declared ones are still all there.
            assertEquals("Reports", written.get("name"));
            assertEquals(REPO, written.get("repositoryId"));
            assertEquals("f-1", written.get("objectId"));
            assertEquals("ARCHIVED", written.get("sourceState"));
            assertEquals(Boolean.FALSE, written.get("active"));
        }

        @Test
        @DisplayName("re-running a transition is a no-op that still succeeds")
        void transitionIsIdempotent() throws Exception {
            assertTrue(lifecycle.markState(REPO, "f-1",
                    PurviewEntityPayloadFactory.SOURCE_STATE_ARCHIVED));
            assertTrue(lifecycle.markState(REPO, "f-1",
                    PurviewEntityPayloadFactory.SOURCE_STATE_ARCHIVED));
            verify(client, times(2)).bulkCreateOrUpdateEntities(any(), any());
        }
    }

    @Nested
    @DisplayName("when the companion is not there")
    class Missing {

        /**
         * Inventing one at purge time would assert a history nobody observed — there is no
         * folder left to read a name from. Reconciliation reports the gap instead.
         */
        @Test
        @DisplayName("a transition does not create one")
        void doesNotCreateOnTransition() throws Exception {
            when(client.getEntityByUniqueAttribute(any(), any(), any(), any())).thenReturn(null);

            assertFalse(lifecycle.markState(REPO, "f-1",
                    PurviewEntityPayloadFactory.SOURCE_STATE_PURGED));
            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
        }

        @Test
        @DisplayName("an unreadable catalog is not mistaken for an absent companion")
        void unreadableIsNotAbsent() throws Exception {
            when(client.getEntityByUniqueAttribute(any(), any(), any(), any()))
                    .thenThrow(new PurviewClientException("unreachable"));

            assertFalse(lifecycle.markState(REPO, "f-1",
                    PurviewEntityPayloadFactory.SOURCE_STATE_PURGED));
            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
        }

        /** Both shapes are in use across catalog versions; guessing wrong reads as "absent". */
        @Test
        @DisplayName("an unwrapped read is understood too")
        void unwrappedReadIsUnderstood() throws Exception {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("qualifiedName", COMPANION_QN);
            attributes.put("name", "Reports");
            when(client.getEntityByUniqueAttribute(any(), any(), any(), any()))
                    .thenReturn(Map.of("attributes", attributes));
            when(client.bulkCreateOrUpdateEntities(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.success(1, "ok"));

            assertTrue(lifecycle.markState(REPO, "f-1",
                    PurviewEntityPayloadFactory.SOURCE_STATE_ARCHIVED));
            assertEquals("Reports", capturedAttributes().get("name"));
        }
    }

    @Test
    @DisplayName("a companion never carries a location, whatever the folder is called")
    public void companionCarriesNoLocation() {
        Folder awkward = folder("f-1", "https://contoso.sharepoint.com/:x:/g/TOKEN");
        awkward.setParentId("p-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes =
                (Map<String, Object>) lifecycle.companionFor(REPO, awkward).get("attributes");

        // The name is a name — it is not refused for looking like one, because the boundary
        // judges the attribute it is in, and `name` is where a folder's name goes. What matters
        // is that no attribute of the companion is a location in the first place.
        assertFalse(attributes.containsKey("folderPath"));
        assertFalse(attributes.containsKey("externalPath"));
        assertFalse(attributes.containsKey("cloudFileUrl"));
    }

    @Test
    @DisplayName("null arguments are refused rather than turned into a call")
    public void nullArgumentsAreRefused() throws Exception {
        assertFalse(lifecycle.markState(null, "f-1", "ACTIVE"));
        assertFalse(lifecycle.markState(REPO, null, "ACTIVE"));
        assertFalse(lifecycle.markState(REPO, "f-1", null));
        assertEquals(0, lifecycle.tie(REPO, null, Map.of()));
        assertEquals(0, lifecycle.tie(REPO, List.<Content>of(), Map.of()));
        verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
    }
}
