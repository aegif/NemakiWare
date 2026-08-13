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
package jp.aegif.nemaki.patch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.DatabaseInformation;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * Patches must not run against a repository whose views are not answering.
 *
 * <h2>Why one gate instead of fourteen fixes</h2>
 *
 * <p>An audit of all 41 patches found 14 that decide "does this already exist?" by querying a view
 * in {@code _design/_repo} and then create the missing thing with a CouchDB-generated id. Both
 * halves are needed for the failure: a view being rebuilt answers <b>HTTP 200 with zero rows and
 * no exception</b> — measured directly against the server — so the check reports "absent"; and
 * because the create has no stable id, CouchDB cannot reject the duplicate with a conflict.
 *
 * <p>That is not theoretical. On 2026-08-13 {@code bedroom} ended up with two {@code .system}
 * folders and two patch-history records for one patch name. Two objects answering to the path
 * {@code /.system} break CMIS path resolution.
 *
 * <p>{@code Patch_JoinedGroupsSingleEmit} rewrites that design document during a v3.3.0 upgrade,
 * so the window is the documented upgrade path, not an edge case. Repairing fourteen existence
 * checks individually would leave the fifteenth; refusing to patch a repository whose views are
 * demonstrably not answering covers all of them, including patches not yet written.
 *
 * <h2>The test the gate applies</h2>
 *
 * <p>Same shape as the reindex wipe guard: <b>documents in the database, but a core view returning
 * nothing at all</b>, means the views are not built. A genuinely fresh repository has neither and
 * is not blocked — which matters, because that is exactly when patches must run.
 */
class PatchViewCanaryTest {

    private static final String REPO = "bedroom";

    private PatchUtil utilWith(long docCount, long viewRows) {
        return utilWith(docCount, viewRows, true);
    }

    private PatchUtil utilWith(long docCount, long viewRows, boolean viewDeclared) {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        DatabaseInformation info = mock(DatabaseInformation.class);
        when(info.getDocCount()).thenReturn(docCount);
        when(client.getDatabaseInfo()).thenReturn(info);
        when(client.queryViewCount(anyString(), anyString())).thenReturn(viewRows);
        // The gate reads the design document to tell "this view was never installed" from
        // "this view exists and is answering nothing". Both branches must be exercised.
        tools.jackson.databind.node.ObjectNode design =
                jp.aegif.nemaki.config.ObjectMapperFactory.createDefaultObjectMapper()
                        .createObjectNode();
        tools.jackson.databind.node.ObjectNode views = design.putObject("views");
        if (viewDeclared) {
            views.putObject("childrenNames");
        }
        when(client.get(org.mockito.ArgumentMatchers.eq(tools.jackson.databind.JsonNode.class),
                anyString())).thenReturn(design);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);

        PatchUtil util = new PatchUtil();
        util.setConnectorPool(pool);
        return util;
    }

    /**
     * A view that was never installed must NOT block patching — installing it IS a patch's job.
     *
     * <p>Without this distinction the gate deadlocks: {@code Patch_StandardCmisViews} creates
     * {@code childrenNames}, and it is gated like every other patch, so a populated legacy
     * repository missing that view could never be repaired — not on this startup nor any later
     * one, because "try again next time" cannot change a state nothing is allowed to fix.
     */
    @Test
    void aMissingCanaryViewDoesNotBlockThePatchThatInstallsIt() {
        assertTrue(utilWith(5615, 0, false).cmisViewsAreAnswering(REPO),
                "the view does not exist yet — refusing here would stop the patch that creates it");
    }

    /** The failure that actually happened: a populated repository whose views say nothing. */
    @Test
    void aPopulatedRepositoryWithSilentViewsIsRefused() {
        assertFalse(utilWith(5615, 0).cmisViewsAreAnswering(REPO),
                "the database holds thousands of documents and the childrenNames view returns none — "
                        + "every view-based existence check is about to answer 'absent' and every "
                        + "patch that trusts one will create a duplicate");
    }

    /**
     * The other half. Without it, "always refuse" would pass the test above and stop every patch
     * from ever being applied — including on the fresh repository that needs them most.
     */
    @Test
    void aFreshRepositoryIsNotBlocked() {
        assertTrue(utilWith(3, 0).cmisViewsAreAnswering(REPO),
                "a brand-new repository legitimately has empty views and nothing to lose");
    }

    /** A healthy repository proceeds. */
    @Test
    void aHealthyRepositoryProceeds() {
        assertTrue(utilWith(5615, 270).cmisViewsAreAnswering(REPO));
    }

    /**
     * If the state cannot be established at all, that is not permission to proceed: the whole
     * point is that an unreadable repository looks identical to an empty one.
     */
    @Test
    void anUnreadableRepositoryIsRefused() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.getDatabaseInfo()).thenThrow(new RuntimeException("CouchDB is unreachable"));
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);
        PatchUtil util = new PatchUtil();
        util.setConnectorPool(pool);

        assertFalse(util.cmisViewsAreAnswering(REPO));
    }
}
