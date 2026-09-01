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
package jp.aegif.nemaki.businesslogic;

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every consumer that acts destructively or irreversibly on a child listing checks whether the
 * listing was SHORT.
 *
 * <h2>Why this is one test over seven files</h2>
 *
 * <p>Rows the repository cannot decode are absent from {@code getChildren} without any
 * exception, and it took ten review rounds to notice that the READ paths had all been corrected
 * while {@code deleteTree} — the destructive one — deleted parents over invisible children. The
 * siblings were then found the same day: two find-or-create scans that would create duplicates,
 * two exporters that would ship a package presenting itself as the folder's contents, the ACL
 * refresh whose skipped children keep stale permissions in the search index, and the two
 * reindex walks. Each earlier correction had reached exactly one consumer; this pins the SET,
 * so the next consumer of a listing that can silently shorten arrives with a red test naming
 * this file rather than a review round eleven passes later.
 *
 * <p>The individual behaviours are pinned in their own tests (deleteTree, the reindexes, the
 * canonical import). This one asserts PRESENCE of the check in every listed consumer — it is a
 * roster, not a behavioural proof, and the roster is what kept being incomplete.
 */
class ShortListingsDoNotReachDestructiveConsumersTest {

    /**
     * File → the methods in it that consume a listing destructively or irreversibly.
     *
     * <p>Adding a consumer here is the EXPECTED maintenance: if a new method deletes, creates,
     * exports or permanently records from {@code getChildren}, it belongs in this map the same
     * day, with a {@code lastUnreadableChildCount} check in its body.
     */
    private static final Map<String, List<String>> DESTRUCTIVE_CONSUMERS = Map.ofEntries(
            Map.entry("src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceImpl.java",
                    List.of("private void deleteTreeDFS")),
            Map.entry("src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceInternalImpl.java",
                    List.of("public void deleteObjectInternal")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/AuthTokenResource.java",
                    List.of("private Folder getOrCreateUsersFolder")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/BulkCheckInResource.java",
                    List.of("public String saveAllVersions")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/CloudDriveResource.java",
                    List.of("public String importFromCloud")),

            Map.entry("src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java",
            List.of("public List<String> deleteTree", "public Folder getOrCreateSystemSubFolder")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java",
                    List.of("public ImportResult importCustomFormat",
                            "private String ensureFolderPath")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/importexport/FilesystemExporter.java",
                    List.of("private void exportFolderToFilesystem")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/importexport/ZipExporter.java",
                    List.of("public void exportFolderRecursive")),
            Map.entry("src/main/java/jp/aegif/nemaki/sync/service/DirectorySyncServiceImpl.java",
                    List.of("private Folder getOrCreateUsersFolder",
                            "private Folder getOrCreateGroupsFolder")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java",
                    List.of("private Content findExistingDocument")),
            Map.entry("src/main/java/jp/aegif/nemaki/cmis/service/impl/AclServiceImpl.java",
                    List.of("private void updateSearchIndexACLRecursively")),
            Map.entry("src/main/java/jp/aegif/nemaki/patch/Patch_SystemFolderSetup.java",
                    List.of("private Folder findExistingSystemFolder")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/purview/relationship/PurviewContainmentRelationshipServiceImpl.java",
                    List.of("private List<ContainmentEdge> loadContainmentEdges")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewCloudMetadataPublishServiceImpl.java",
                    List.of("private List<Content> loadCloudMetadataDocuments",
                            "public int retryRepositoryCloudSyncLineage")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewDocumentPublishServiceImpl.java",
                    List.of("public int publishRepositoryHierarchy")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/purview/lineage/LineageFolderCompanionBackfillImpl.java",
                    List.of("private List<Content> childFolders")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/purview/lineage/LineageCatalogReconciliationServiceImpl.java",
                    List.of("private List<Content> childFolders")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/purview/publish/PurviewArchivePublishServiceImpl.java",
                    List.of("private List<Archive> loadValidArchives")),
            Map.entry("src/main/java/jp/aegif/nemaki/cmis/service/impl/NavigationServiceImpl.java",
                    List.of("private ObjectInFolderList getChildrenInternal")),
            Map.entry("src/main/java/jp/aegif/nemaki/rest/purview/sync/PurviewIncrementalSyncServiceImpl.java",
                    List.of("private List<Change> loadChanges",
                            "private List<String> collectDescendantIdsForChangedFolders")),
            Map.entry("src/main/java/jp/aegif/nemaki/api/v1/resource/ArchiveResource.java",
                    List.of("public Response emptyTrash", "public Response listArchives")),
            Map.entry("src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/ChangeEventServiceDelegate.java",
                    List.of("public List<Change> getLatestChanges")),
            Map.entry("src/main/java/jp/aegif/nemaki/rss/RssFeedService.java",
                    List.of("private List<Change> getChangesForFolder",
                            "private List<Change> getChangesForDocument",
                            "private void collectChildFolderIds")));

    @Test
    @DisplayName("every destructive consumer of a child listing checks for a short one")
    void everyDestructiveConsumerChecks() throws Exception {
        List<String> blind = new java.util.ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, List<String>> entry : DESTRUCTIVE_CONSUMERS.entrySet()) {
            String source = JavaSource.read(entry.getKey());
            for (String method : entry.getValue()) {
                checked++;
                // ALL overloads, concatenated. ZipExporter has a two-line delegator with the
                // same signature prefix as the real walk, and taking only the first body put
                // the delegator on trial for a check that lives in its target.
                String body = allBodiesOf(source, method);
                if (body.isEmpty()) {
                    blind.add(entry.getKey() + " :: " + method + " (method not found — renamed? "
                            + "update this roster with it)");
                    continue;
                }
                // Either spelling of awareness: walkers read the DAO counter directly;
                // consumers of a walker's result read the incompleteness flag the walker set
                // from it. Both are the same rule one hop apart.
                if (!body.contains("lastUnreadableChildCount")
                        && !body.contains("lastUnreadableArchiveCount")
                        && !body.contains("lastUnreadableChangeCount")
                        && !body.contains("lastWalkIncomplete")) {
                    blind.add(entry.getKey() + " :: " + method);
                }
            }
        }
        assertTrue(checked >= 32,
                "only " + checked + " consumers were checked, so the roster no longer covers "
                        + "the set it exists for");
        if (!blind.isEmpty()) {
            fail("a destructive or irreversible consumer trusts a child listing that can be "
                    + "silently short — rows the repository cannot decode are absent without "
                    + "any exception, so this consumer acts as if they do not exist:\n  "
                    + String.join("\n  ", blind));
        }
    }

    private static String allBodiesOf(String source, String signaturePrefix) {
        StringBuilder all = new StringBuilder();
        String remaining = source;
        while (true) {
            int at = remaining.indexOf(signaturePrefix);
            if (at < 0) {
                break;
            }
            String slice = remaining.substring(at);
            try {
                all.append(JavaSource.withoutComments(
                        JavaSource.methodBody(slice, signaturePrefix)));
            } catch (RuntimeException e) {
                break;
            }
            remaining = remaining.substring(at + signaturePrefix.length());
        }
        return all.toString();
    }
}
