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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.lineage.LineageCatalogReconciliationService.Report;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/**
 * Reconciliation converges, and does not report agreement it did not observe.
 *
 * <p>The two failures under test throughout: calling a catalog that did not answer "in sync", and
 * deleting a companion whose folder is gone. The first invents a verdict; the second erases the
 * lineage the companion exists for.
 */
public class LineageCatalogReconciliationServiceTest {

    private static final String REPO = "bedroom";
    private static final String ROOT = "root-1";

    private ContentDaoService dao;
    private PurviewEntityRegistryClient client;
    private LineageFolderCompanionLifecycle lifecycle;
    private LineageCatalogReconciliationServiceImpl reconciliation;

    /** Companion qualified names the catalog will admit to holding. */
    private final Set<String> companionsInCatalog = new HashSet<>();

    private static Folder folder(String id, String name) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setName(name);
        folder.setType("cmis:folder");
        return folder;
    }

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(ContentDaoService.class);
        client = mock(PurviewEntityRegistryClient.class);

        MetadataCatalogConnectionResolver resolver =
                mock(MetadataCatalogConnectionResolver.class);
        when(resolver.buildConnectionRequest()).thenReturn(null);

        RepositoryInfo info = new RepositoryInfo();
        info.setId(REPO);
        info.setRootFolder(ROOT);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.get(REPO)).thenReturn(info);

        PurviewEntityPayloadFactory payloadFactory = new PurviewEntityPayloadFactory();
        lifecycle = new LineageFolderCompanionLifecycleImpl(resolver, payloadFactory, client);

        when(client.getEntityByUniqueAttribute(any(), eq("nemaki_folder_dataset"),
                eq("qualifiedName"), anyString())).thenAnswer(invocation -> {
                    String qualifiedName = invocation.getArgument(3);
                    return companionsInCatalog.contains(qualifiedName)
                            ? Map.of("entity", Map.of("attributes",
                                    Map.of("qualifiedName", qualifiedName, "name", "recorded")))
                            : null;
                });
        when(client.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "ok"));
        when(client.createRelationship(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "relationship created"));

        reconciliation = new LineageCatalogReconciliationServiceImpl(
                resolver, infoMap, dao, payloadFactory, client, lifecycle);
    }

    /** Root plus {@code count} flat children, all present in the repository. */
    private List<Content> repositoryWithFolders(int count) {
        List<Content> children = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            children.add(folder("f-" + i, "folder " + i));
        }
        when(dao.getContent(REPO, ROOT)).thenReturn(folder(ROOT, "root"));
        when(dao.getChildrenCount(REPO, ROOT)).thenReturn((long) count);
        when(dao.getChildrenPaged(any(), any(), anyInt(), anyInt())).thenAnswer(invocation ->
                ROOT.equals(invocation.getArgument(1)) ? children : List.of());
        for (Content child : children) {
            when(dao.getContent(REPO, child.getId())).thenReturn(child);
            when(dao.getChildrenCount(REPO, child.getId())).thenReturn(0L);
        }
        return children;
    }

    private static String companionQn(String folderId) {
        return "nemaki://bedroom/folders/" + folderId + "/dataset";
    }

    @Nested
    @DisplayName("folder and companion")
    class Correspondence {

        @Test
        @DisplayName("both present is in sync, and touches nothing")
        void inSync() throws Exception {
            repositoryWithFolders(2);
            companionsInCatalog.add(companionQn(ROOT));
            companionsInCatalog.add(companionQn("f-0"));
            companionsInCatalog.add(companionQn("f-1"));

            Report report = reconciliation.reconcile(REPO, 100, true);

            assertTrue(report.clean());
            assertEquals(3, report.checked());
            assertEquals(3, report.inSync());
            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
        }

        @Test
        @DisplayName("a missing companion is found and republished")
        void missingCompanionIsRepaired() throws Exception {
            repositoryWithFolders(2);
            companionsInCatalog.add(companionQn(ROOT));

            Report report = reconciliation.reconcile(REPO, 100, true);

            assertFalse(report.clean());
            assertEquals(2, report.companionMissing());
            assertEquals(2, report.repaired());
            verify(client, times(2)).bulkCreateOrUpdateEntities(any(), any());
            verify(client, times(2)).createRelationship(any(), any());
        }

        @Test
        @DisplayName("without repair it reports and changes nothing")
        void reportOnly() throws Exception {
            repositoryWithFolders(2);

            Report report = reconciliation.reconcile(REPO, 100, false);

            assertEquals(3, report.companionMissing());
            assertEquals(0, report.repaired());
            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
        }
    }

    @Nested
    @DisplayName("when the folder is gone")
    class StaleEntities {

        /**
         * The companion outlives the folder on purpose. Deleting it would break every Process
         * that already names it — which is the whole reason it was created.
         */
        @Test
        @DisplayName("the companion is marked ORPHAN and never deleted")
        void orphanIsMarkedNotDeleted() throws Exception {
            when(dao.getContent(REPO, ROOT)).thenReturn(null);
            when(dao.getChildrenCount(REPO, ROOT)).thenReturn(0L);
            companionsInCatalog.add(companionQn(ROOT));

            Report report = reconciliation.reconcile(REPO, 100, true);

            assertEquals(1, report.sourceMissing());
            assertEquals(1, report.markedOrphan());
            assertFalse(report.clean());
            verify(client, never()).deleteByUniqueAttribute(any(), any(), any(), any());
            verify(client, never()).deleteRelationshipByGuid(any(), any());
        }

        @Test
        @DisplayName("neither side present is not a finding")
        void neitherSideIsNotAFinding() throws Exception {
            when(dao.getContent(REPO, ROOT)).thenReturn(null);
            when(dao.getChildrenCount(REPO, ROOT)).thenReturn(0L);

            Report report = reconciliation.reconcile(REPO, 100, true);

            assertTrue(report.clean());
            assertEquals(0, report.sourceMissing());
            assertEquals(0, report.companionMissing());
        }
    }

    @Nested
    @DisplayName("when the catalog does not answer")
    class Undetermined {

        /** Silence is not agreement. A pass that could not look has not found nothing wrong. */
        @Test
        @DisplayName("an unreadable companion is undetermined, not in sync")
        void unreadableIsNotInSync() throws Exception {
            repositoryWithFolders(1);
            when(client.getEntityByUniqueAttribute(any(), any(), any(), any()))
                    .thenThrow(new PurviewClientException("unreachable"));

            Report report = reconciliation.reconcile(REPO, 100, true);

            assertEquals(2, report.undetermined());
            assertEquals(0, report.inSync());
            assertFalse(report.clean());
        }

        @Test
        @DisplayName("nothing is repaired on a verdict that was never reached")
        void noRepairWithoutAVerdict() throws Exception {
            repositoryWithFolders(1);
            when(client.getEntityByUniqueAttribute(any(), any(), any(), any()))
                    .thenThrow(new PurviewClientException("unreachable"));

            reconciliation.reconcile(REPO, 100, true);

            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
        }

        @Test
        @DisplayName("a repository with no root is undetermined, not an empty clean pass")
        void noRootIsUndetermined() {
            Report report = reconciliation.reconcile("nope", 100, true);
            assertFalse(report.clean());
            assertEquals(1, report.undetermined());
            assertEquals(0, report.checked());
        }
    }

    @Nested
    @DisplayName("convergence")
    class Convergence {

        /**
         * The repair and the ordinary sync publish the same entity under the same qualified
         * name, so overlapping does not produce two entities or a conflict.
         */
        @Test
        @DisplayName("a repair racing the ordinary sync converges")
        void repairRacingSyncConverges() throws Exception {
            List<Content> children = repositoryWithFolders(1);

            // The sync publishes first; the reconciliation pass then finds it and does nothing.
            companionsInCatalog.add(companionQn(ROOT));
            companionsInCatalog.add(companionQn(children.get(0).getId()));
            Report afterSync = reconciliation.reconcile(REPO, 100, true);
            assertTrue(afterSync.clean());

            // And the other order: reconciliation repairs, the sync republishes the same thing.
            companionsInCatalog.clear();
            Report afterRepair = reconciliation.reconcile(REPO, 100, true);
            assertEquals(2, afterRepair.repaired());
            assertEquals(lifecycle.companionFor(REPO, children.get(0)),
                    lifecycle.companionFor(REPO, children.get(0)),
                    "both paths build the identical payload, so neither can win differently");
        }

        @Test
        @DisplayName("a second pass after a repair is clean")
        void secondPassIsClean() throws Exception {
            repositoryWithFolders(1);

            Report first = reconciliation.reconcile(REPO, 100, true);
            assertEquals(2, first.repaired());

            // What the repair published is now in the catalog.
            companionsInCatalog.add(companionQn(ROOT));
            companionsInCatalog.add(companionQn("f-0"));

            assertTrue(reconciliation.reconcile(REPO, 100, true).clean());
        }

        /** A publish that fails is not counted as a repair, so the next pass tries again. */
        @Test
        @DisplayName("a failed repair is retried by the next pass")
        void failedRepairIsRetried() throws Exception {
            repositoryWithFolders(0);
            when(client.bulkCreateOrUpdateEntities(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.failure("catalog said no"))
                    .thenReturn(PurviewEntityPublishResult.success(1, "ok"));

            Report first = reconciliation.reconcile(REPO, 100, true);
            assertEquals(1, first.companionMissing());
            assertEquals(0, first.repaired(), "a failed publish is not a repair");

            Report second = reconciliation.reconcile(REPO, 100, true);
            assertEquals(1, second.repaired());
        }
    }

    @Test
    @DisplayName("the pass is bounded, like the backfill")
    public void boundedPass() throws Exception {
        repositoryWithFolders(50);
        Report report = reconciliation.reconcile(REPO, 10, false);
        assertEquals(10, report.checked());
    }
}
