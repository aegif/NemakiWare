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
 * A folder reindex must tell the caller that it just unfenced the subtree.
 *
 * <h2>What this is protecting</h2>
 *
 * <p>{@code POST /search-engine/reindex/folder/{id}} drops {@code effective_acl_epoch} from
 * every document it touches: the batch write path does not carry the field. {@code readers} is
 * recomputed, so search authorization keeps working — which is precisely why nobody notices
 * that the subtree has left the ACL-epoch fence.
 *
 * <p>Measured 2026-08-12 on the dev stack: a folder with 186 of 236 children fenced had 0
 * fenced within five seconds of the reindex, and was still at 0 after three 300-second scanner
 * cycles. That is not slowness. Every pass of {@code AclEpochFinalizationService.scan} selects
 * on the CouchDB fields {@code aclEpochState} / {@code aclEpochMutationId}, and a reindex
 * changes neither — it drops a SOLR field while the CouchDB document stays settled. No pass can
 * ever select these documents, so the epoch never returns without an explicit stamp.
 *
 * <p>The equivalent hazard on the full-reindex path is warned about at the stamp endpoint and
 * written into the runbook. This endpoint said nothing at all.
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
    @DisplayName("folder reindex の応答は epoch fence が外れることを警告する")
    void theResponseWarnsThatTheSubtreeIsUnfenced() throws Exception {
        String body = reindexFolderBody();

        assertTrue(body.contains("response.setNote("),
                "the response must carry the warning: a caller who is told only 'reindex"
                        + " started' has no way to learn that the subtree just left the fence");
        assertTrue(body.contains("acl-epoch/migration/"),
                "and it must name the follow-up. 'Something is wrong' without 'run this' is how"
                        + " the full-reindex hazard stayed in a design document instead of a"
                        + " runbook");
    }

    @Test
    @DisplayName("警告は「自然に直る」と読める書き方をしない")
    void theWarningDoesNotImplySelfHealing() throws Exception {
        String body = reindexFolderBody().toLowerCase();

        assertTrue(body.contains("not") && body.contains("recover"),
                "the measured fact is that it does NOT recover on its own; wording that leaves"
                        + " that open invites an operator to wait instead of acting");
    }
}
