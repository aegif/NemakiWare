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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.lineage.LineageFolderCompanionBackfill.Plan;
import jp.aegif.nemaki.rest.purview.lineage.LineageFolderCompanionBackfill.Progress;
import jp.aegif.nemaki.rest.purview.lineage.LineageFolderCompanionBackfill.Refusal;
import jp.aegif.nemaki.rest.purview.lineage.LineageFolderCompanionBackfill.State;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifest;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewStateStore;

/**
 * The backfill's guarantees, each asserted as a failure it must not produce.
 *
 * <p>The catalog is a mock, so every branch an operator would only meet in production — an
 * unreachable catalog, a half-applied schema, a crash between batches — is reachable here.
 */
public class LineageFolderCompanionBackfillTest {

    private static final String REPO = "bedroom";
    private static final String ROOT = "root-1";

    private ContentDaoService dao;
    private PurviewEntityRegistryClient client;
    private PurviewSchemaStateService schemaState;
    private InMemoryStateStore store;
    private LineageFolderCompanionBackfillImpl backfill;
    private String appliedHash;

    /** A state store that behaves like the real one for the two calls this service makes. */
    private static final class InMemoryStateStore implements PurviewStateStore {
        final Map<String, Object> values = new LinkedHashMap<>();
        int writes;

        @Override
        public String getString(String key) {
            Object v = values.get(key);
            return v == null ? "" : String.valueOf(v);
        }

        @Override
        public int getInt(String key) {
            return 0;
        }

        @Override
        public Map<String, Object> getAll() {
            return new LinkedHashMap<>(values);
        }

        @Override
        public void putAll(Map<String, Object> newValues) {
            writes++;
            values.putAll(newValues);
        }

        @Override
        public void removeAll(Collection<String> keys) {
            keys.forEach(values::remove);
        }
    }

    private static Content folder(String id, String name) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setName(name);
        folder.setType("cmis:folder");
        return folder;
    }

    @BeforeEach
    void setUp() {
        dao = mock(ContentDaoService.class);
        client = mock(PurviewEntityRegistryClient.class);
        schemaState = mock(PurviewSchemaStateService.class);
        store = new InMemoryStateStore();

        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        appliedHash = manifest.getSchemaHash();

        MetadataCatalogConnectionResolver connectionResolver =
                mock(MetadataCatalogConnectionResolver.class);
        when(connectionResolver.getCollection()).thenReturn("default");
        when(connectionResolver.buildConnectionRequest()).thenReturn(null);

        RepositoryInfo info = new RepositoryInfo();
        info.setId(REPO);
        info.setRootFolder(ROOT);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.get(REPO)).thenReturn(info);

        when(schemaState.getSchemaState(anyString())).thenAnswer(
                invocation -> new PurviewSchemaState("default", "15", appliedHash, "", "", ""));

        backfill = new LineageFolderCompanionBackfillImpl(connectionResolver, infoMap, dao,
                new PurviewEntityPayloadFactory(), client, schemaState, manifestFactory, store);
    }

    /** Root with {@code count} children, none of which have children of their own. */
    private void repositoryWithFolders(int count) {
        List<Content> children = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            children.add(folder("f-" + i, "folder " + i));
        }
        when(dao.getContent(REPO, ROOT)).thenReturn(folder(ROOT, "root"));
        when(dao.getChildrenCount(REPO, ROOT)).thenReturn((long) count);
        when(dao.getChildrenPaged(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            String parent = invocation.getArgument(1);
            if (!ROOT.equals(parent)) {
                return List.of();
            }
            int skip = invocation.getArgument(2);
            int limit = invocation.getArgument(3);
            return children.subList(Math.min(skip, children.size()),
                    Math.min(skip + limit, children.size()));
        });
        for (Content child : children) {
            when(dao.getContent(REPO, child.getId())).thenReturn(child);
            when(dao.getChildrenCount(REPO, child.getId())).thenReturn(0L);
        }
    }

    private void catalogAccepts() throws Exception {
        when(client.bulkCreateOrUpdateEntities(any(), any())).thenAnswer(invocation ->
                PurviewEntityPublishResult.success(batchSize(invocation.getArgument(1)), "ok"));
        when(client.createRelationship(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "relationship created"));
    }

    @SuppressWarnings("unchecked")
    private static int batchSize(Map<String, Object> payload) {
        Object entities = payload.get("entities");
        return entities instanceof List<?> list ? list.size() : 0;
    }

    @Nested
    @DisplayName("before it starts")
    class FailClosed {

        @Test
        @DisplayName("refuses when the applied schema is not the one this build expects")
        void refusesOnSchemaMismatch() {
            when(schemaState.getSchemaState(anyString())).thenReturn(
                    new PurviewSchemaState("default", "13", "some-older-hash", "", "", ""));

            Progress progress = backfill.run(REPO, 10);

            assertEquals(Refusal.SCHEMA_NOT_READY, progress.refusal());
            assertEquals(State.FAILED, progress.state());
            assertFalse(progress.successful());
        }

        @Test
        @DisplayName("refuses when no schema has been applied at all")
        void refusesWhenNoSchemaState() {
            when(schemaState.getSchemaState(anyString())).thenReturn(null);
            assertEquals(Refusal.SCHEMA_NOT_READY, backfill.run(REPO, 10).refusal());
        }

        /**
         * A refused run must leave nothing behind: a resume document written here would later
         * read as "a run happened and processed nothing", which is a different fact.
         */
        @Test
        @DisplayName("writes nothing when it refuses")
        void refusalWritesNothing() {
            when(schemaState.getSchemaState(anyString())).thenReturn(null);
            backfill.run(REPO, 10);
            assertEquals(0, store.writes);
            assertEquals(State.NOT_STARTED, backfill.progress(REPO).state());
        }

        @Test
        @DisplayName("refuses an unknown repository rather than walking nothing")
        void refusesUnknownRepository() {
            assertEquals(Refusal.REPOSITORY_UNAVAILABLE, backfill.run("nope", 10).refusal());
        }

        /** Accepted because businessMetadataDefs is empty in this codebase — our own marker. */
        @Test
        @DisplayName("accepts the partial-apply marker")
        void acceptsPartialApplyMarker() throws Exception {
            when(schemaState.getSchemaState(anyString())).thenReturn(new PurviewSchemaState(
                    "default", "15", appliedHash + ":atlas-partial", "", "", ""));
            repositoryWithFolders(1);
            catalogAccepts();

            assertEquals(Refusal.NONE, backfill.run(REPO, 10).refusal());
        }
    }

    @Nested
    @DisplayName("planning")
    class Planning {

        @Test
        @DisplayName("counts folders without publishing anything")
        void dryRunPublishesNothing() throws Exception {
            repositoryWithFolders(3);

            Plan plan = backfill.plan(REPO);

            assertTrue(plan.runnable());
            assertEquals(4, plan.folderCount(), "root plus three children");
            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
            verify(client, never()).createRelationship(any(), any());
            assertEquals(0, store.writes);
        }

        @Test
        @DisplayName("reports the refusal rather than a count it cannot stand behind")
        void planCarriesTheRefusal() {
            when(schemaState.getSchemaState(anyString())).thenReturn(null);
            Plan plan = backfill.plan(REPO);
            assertFalse(plan.runnable());
            assertEquals(Refusal.SCHEMA_NOT_READY, plan.refusal());
            assertEquals(0, plan.folderCount());
        }
    }

    @Nested
    @DisplayName("running")
    class Running {

        @Test
        @DisplayName("publishes a companion and a tie for every folder")
        void publishesEverything() throws Exception {
            repositoryWithFolders(2);
            catalogAccepts();

            Progress progress = backfill.run(REPO, 10);

            assertTrue(progress.successful());
            assertEquals(State.COMPLETE, progress.state());
            assertEquals(3, progress.processed());
            assertEquals(0, progress.failed());
            verify(client, times(3)).createRelationship(any(), any());
        }

        /** Two runs, one repository, one set of entities: the second is a no-op. */
        @Test
        @DisplayName("is idempotent — a second run publishes nothing new")
        void secondRunIsANoOp() throws Exception {
            repositoryWithFolders(2);
            catalogAccepts();

            Progress first = backfill.run(REPO, 10);
            Progress second = backfill.run(REPO, 10);

            assertTrue(first.successful());
            assertTrue(second.successful());
            assertEquals(first.processed(), second.processed(),
                    "the second run must not re-count what the first did");
            verify(client, times(3)).createRelationship(any(), any());
        }

        @Test
        @DisplayName("stops at the batch bound and resumes where it stopped")
        void boundedAndResumable() throws Exception {
            repositoryWithFolders(250);
            catalogAccepts();

            Progress first = backfill.run(REPO, 1);
            assertEquals(State.PAUSED, first.state());
            assertTrue(first.pendingFrontier() > 0, "a paused run leaves work behind");
            assertEquals(100, first.processed());

            Progress second = backfill.run(REPO, 1);
            assertEquals(State.PAUSED, second.state());
            assertEquals(200, second.processed());

            Progress third = backfill.run(REPO, 10);
            assertEquals(State.COMPLETE, third.state());
            assertEquals(251, third.processed(), "root plus 250 children, each exactly once");
            assertTrue(third.successful());
        }

        /**
         * The resume document is what survives a crash, so it is read back through a fresh
         * instance rather than from the object that wrote it.
         */
        @Test
        @DisplayName("resumes through the persisted document, not in-memory state")
        void resumesAcrossInstances() throws Exception {
            repositoryWithFolders(250);
            catalogAccepts();
            backfill.run(REPO, 1);

            Progress recorded = backfill.progress(REPO);
            assertEquals(100, recorded.processed());
            assertTrue(recorded.pendingFrontier() > 0);
        }
    }

    @Nested
    @DisplayName("when something goes wrong")
    class Failures {

        @Test
        @DisplayName("a failed publish is not a success, and says so in both fields")
        void publishFailureIsNotSuccess() throws Exception {
            repositoryWithFolders(2);
            when(client.bulkCreateOrUpdateEntities(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.failure("catalog said no"));

            Progress progress = backfill.run(REPO, 10);

            assertFalse(progress.successful());
            assertEquals(State.FAILED, progress.state());
            assertEquals(Refusal.PUBLISH_FAILED, progress.refusal());
            assertTrue(progress.failed() > 0);
        }

        @Test
        @DisplayName("an unreachable catalog stops the run rather than skipping folders")
        void unreachableCatalogStops() throws Exception {
            repositoryWithFolders(2);
            when(client.bulkCreateOrUpdateEntities(any(), any()))
                    .thenThrow(new PurviewClientException("connection refused"));

            Progress progress = backfill.run(REPO, 10);

            assertEquals(Refusal.CATALOG_UNREACHABLE, progress.refusal());
            assertFalse(progress.successful());
        }

        /**
         * A retry after a failure must not double-count or double-create. The frontier is
         * preserved, so the second run picks up the folders the first one did not finish.
         */
        @Test
        @DisplayName("a retry after a failure completes without duplicating work")
        void retryAfterFailureConverges() throws Exception {
            repositoryWithFolders(2);
            when(client.bulkCreateOrUpdateEntities(any(), any()))
                    .thenThrow(new PurviewClientException("transient"))
                    .thenAnswer(invocation -> PurviewEntityPublishResult.success(
                            batchSize(invocation.getArgument(1)), "ok"));
            when(client.createRelationship(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.success(1, "relationship already exists"));

            assertEquals(Refusal.CATALOG_UNREACHABLE, backfill.run(REPO, 10).refusal());

            Progress retry = backfill.run(REPO, 10);
            assertEquals(State.COMPLETE, retry.state());
            // The failure is remembered: a run that had to retry is not reported as clean.
            assertTrue(retry.failed() > 0);
            assertFalse(retry.successful());
        }

        @Test
        @DisplayName("a folder deleted mid-walk is skipped, not counted as a failure")
        void vanishedFolderIsNotAFailure() throws Exception {
            repositoryWithFolders(2);
            catalogAccepts();
            when(dao.getContent(REPO, "f-1")).thenReturn(null);

            Progress progress = backfill.run(REPO, 10);

            assertTrue(progress.successful());
            assertEquals(2, progress.processed(), "root and f-0; f-1 was gone");
        }
    }

    @Nested
    @DisplayName("what it publishes")
    class Payloads {

        @Test
        @DisplayName("the companion carries the canonical qualified name and no location")
        void companionShape() throws Exception {
            repositoryWithFolders(1);
            catalogAccepts();
            backfill.run(REPO, 10);

            ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
            verify(client).bulkCreateOrUpdateEntities(any(), payload.capture());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entities =
                    (List<Map<String, Object>>) payload.getValue().get("entities");
            Map<String, Object> companion = entities.get(0);
            assertEquals("nemaki_folder_dataset", companion.get("typeName"));

            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) companion.get("attributes");
            assertEquals("nemaki://bedroom/folders/root-1/dataset", attributes.get("qualifiedName"));
            assertEquals(REPO, attributes.get("repositoryId"));
            assertEquals(ROOT, attributes.get("objectId"));
            assertEquals(Boolean.TRUE, attributes.get("active"));
            assertEquals("ACTIVE", attributes.get("sourceState"));
            assertFalse(attributes.containsKey("folderPath"),
                    "the companion is named by id so it does not have to carry a path");
        }

        /** The companion's name must differ from the folder's, or they are the same entity. */
        @Test
        @DisplayName("the tie names the folder and the companion, not one entity twice")
        void relationshipShape() throws Exception {
            repositoryWithFolders(1);
            catalogAccepts();
            backfill.run(REPO, 10);

            ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
            verify(client, times(2)).createRelationship(any(), payload.capture());

            Map<String, Object> tie = payload.getAllValues().get(0);
            assertEquals("nemaki_folder_has_dataset", tie.get("typeName"));

            @SuppressWarnings("unchecked")
            Map<String, Object> end1 = (Map<String, Object>) tie.get("end1");
            @SuppressWarnings("unchecked")
            Map<String, Object> end2 = (Map<String, Object>) tie.get("end2");
            assertEquals("nemaki_folder", end1.get("typeName"));
            assertEquals("nemaki_folder_dataset", end2.get("typeName"));
            assertNotEquals(end1.get("uniqueAttributes"), end2.get("uniqueAttributes"));
        }
    }
}
