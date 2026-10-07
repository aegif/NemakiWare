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
package jp.aegif.nemaki.dao.impl.couch.delegate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

/**
 * The type-definition listing never answers with FEWER types than exist.
 *
 * <h2>The two-type repository</h2>
 *
 * <p>The old outer catch synthesized {@code cmis:folder} + {@code cmis:document} on ANY
 * exception — so one transient CouchDB failure answered "this repository has exactly two
 * types". The Purview type-definition sync diffs that answer against its snapshot and DELETES
 * every custom type's external entity; classifications and terms attached to them do not come
 * back when a later run re-creates the entities under new GUIDs. The per-row skip arms had the
 * same effect one type at a time. Found by the round-32 sibling sweep — the archive stream got
 * the refusal one round earlier; this stream did not.
 */
class TypeDefinitionsAreNotSilentlyFewerTest {

    private static final String REPO = "bedroom";

    private CloudantClientWrapper client;
    private TypeDefinitionDaoDelegate delegate;

    private void wire() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        client = mock(CloudantClientWrapper.class);
        when(pool.getClient(REPO)).thenReturn(client);
        delegate = new TypeDefinitionDaoDelegate(pool, mock(DaoHelper.class), () -> {
        }, (a, b) -> {
        });
    }

    private static ViewResult resultWithDocs(com.ibm.cloud.cloudant.v1.model.Document... docs) {
        ViewResult result = mock(ViewResult.class);
        List<ViewResultRow> rows = new ArrayList<>();
        for (com.ibm.cloud.cloudant.v1.model.Document doc : docs) {
            ViewResultRow row = mock(ViewResultRow.class);
            when(row.getDoc()).thenReturn(doc);
            rows.add(row);
        }
        when(result.getRows()).thenReturn(rows);
        return result;
    }

    @Test
    @DisplayName("an unreadable type row refuses instead of serving a smaller type system")
    void anUnreadableTypeRowRefuses() {
        wire();
        // A document whose properties cannot be read: the conversion inside the row loop
        // fails, which the old code warn-skipped — one type at a time.
        com.ibm.cloud.cloudant.v1.model.Document broken =
                mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        when(broken.getProperties()).thenReturn(null);
        // Built BEFORE when(): evaluating the helper inside thenReturn() opens a nested
        // stubbing — the trap this batch has now stepped into three times.
        ViewResult brokenRow = resultWithDocs(broken);
        when(client.queryView(eq("_repo"), eq("typeDefinitions"), anyMap()))
                .thenReturn(brokenRow);

        assertThrows(IllegalStateException.class,
                () -> delegate.getTypeDefinitions(REPO),
                "a type the store could not read was dropped, and the sync diffs the "
                        + "shortened list into external deletions");
    }

    @Test
    @DisplayName("a failed read refuses instead of synthesizing a two-type repository")
    void aFailedReadRefuses() {
        wire();
        when(client.queryView(eq("_repo"), eq("typeDefinitions"), anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getTypeDefinitions(REPO),
                "a transient failure answered 'exactly two types exist'");
    }

    @Test
    @DisplayName("a genuinely empty answer still bootstraps the base types — the control")
    void anEmptyAnswerStillBootstraps() {
        wire();
        ViewResult emptyAnswer = resultWithDocs();
        when(client.queryView(eq("_repo"), eq("typeDefinitions"), anyMap()))
                .thenReturn(emptyAnswer);

        List<NemakiTypeDefinition> types = delegate.getTypeDefinitions(REPO);

        // The bootstrap contract: a view that ANSWERED with no rows (fresh repository,
        // before the type documents exist) still yields the two base types.
        assertEquals(2, types.size(),
                "the refusal arms broke the bootstrap fallback: " + types);
    }
}
