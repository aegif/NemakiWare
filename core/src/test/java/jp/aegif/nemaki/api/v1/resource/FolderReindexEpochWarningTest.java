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
package jp.aegif.nemaki.api.v1.resource;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.test.JavaSource;

/**
 * A folder reindex must preserve the ACL-epoch fence, and say so.
 *
 * <h2>What this is protecting</h2>
 *
 * <p>{@code POST /search-engine/reindex/folder/{id}} USED to drop
 * {@code effective_acl_epoch} from every document it touched: the batch write path built a full
 * replacement document without the field and without a {@code _version_} CAS. {@code readers}
 * was recomputed, so search authorization kept working — which is precisely why nobody noticed
 * that the subtree had left the ACL-epoch fence.
 *
 * <p>Measured 2026-08-12, before the fix: a folder with 186 of 236 children fenced had 0 fenced
 * within five seconds of the reindex, and was still at 0 after three 300-second scanner cycles.
 * That was not slowness — every pass of {@code AclEpochFinalizationService.scan} selects on the
 * CouchDB fields {@code aclEpochState} / {@code aclEpochMutationId}, and a reindex changes
 * neither, so no pass could ever select those documents.
 *
 * <p>The batch path is fenced now (same machinery as the single-document write). The same folder
 * stays 186/186, and a full recursive reindex of 5,611 documents leaves the migration verdict at
 * COMPLETE with 0 unfenced. The note survives in a weaker form, pointing at the verdict: because
 * {@code readers} is recomputed either way, a damaged index and a healthy one look identical
 * from the outside, and the verdict is the only thing that tells them apart.
 *
 * <h2>Why a source test</h2>
 *
 * <p>Exercising the resource needs the JAX-RS container and an index-maintenance service; the
 * assertion here is only that the response carries the warning, which is a property of the
 * method body. What must not happen is the warning being dropped as noise during an unrelated
 * edit, and that is what this catches.
 */
class FolderReindexEpochWarningTest {

    private static String reindexFolderBody() throws Exception {
        return JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read("src/main/java/jp/aegif/nemaki/api/v1/resource/SearchEngineResource.java"),
                "public Response reindexFolder("));
    }

    @Test
    @DisplayName("folder reindex の応答は epoch fence について触れる")
    void theResponseMentionsTheFence() throws Exception {
        String body = reindexFolderBody();

        assertTrue(body.contains("response.setNote("),
                "the response must carry the note: a caller told only 'reindex started' has no"
                        + " way to know whether the fence survived, and no reason to look");
        assertTrue(body.contains("acl-epoch/migration/"),
                "and it must name the endpoint. 'Check the verdict' without 'here' is how the"
                        + " full-reindex hazard stayed in a design document instead of a runbook");
    }

    @Test
    @DisplayName("応答は verdict の確認に誘導する")
    void theNoteDirectsTheOperatorToTheVerdict() throws Exception {
        String body = reindexFolderBody();

        assertTrue(body.contains("verdict"),
                "readers are recomputed whether or not the fence survived, so a damaged index"
                        + " and a healthy one look the same from outside. The verdict is the"
                        + " only thing that separates them, so the note must point at it");
    }
}
