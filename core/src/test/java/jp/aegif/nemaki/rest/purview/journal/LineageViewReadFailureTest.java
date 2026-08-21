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
package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A view that could not be read must not answer with an empty list.
 *
 * <p>The capture boundary's entire visible surface is a listing of unresolved ingests. An empty
 * listing reads as "every ingest completed" — so a read failure rendered as an empty list is a
 * failure that presents itself as reassurance, at exactly the moment an operator is looking
 * (external review).
 *
 * <p>This drives {@code CouchLineageJournalStore.queryRawView} itself. The capture store's own
 * tests use a fake support, so they would keep passing if this behaviour were reverted — a test
 * that checks the double rather than the thing.
 */
class LineageViewReadFailureTest {

    private static CouchLineageJournalStore storeWith(CloudantClientPool pool) {
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        try {
            java.lang.reflect.Field f =
                    CouchLineageJournalStore.class.getDeclaredField("connectorPool");
            f.setAccessible(true);
            f.set(store, pool);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return store;
    }

    @Test
    @DisplayName("a view query that cannot reach the database throws rather than returning empty")
    void unreadableViewThrows() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThrows(RuntimeException.class,
                () -> storeWith(pool).queryRawView("lineage_capture", "unresolved_by_opened_at",
                        Map.of()));
    }

    @Test
    @DisplayName("the failure names the view, so an operator knows which index to look at")
    void failureNamesTheView() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenThrow(new RuntimeException("connection refused"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> storeWith(pool).queryRawView("lineage_capture", "unresolved_by_opened_at",
                        Map.of()));

        String message = String.valueOf(thrown.getMessage())
                + String.valueOf(thrown.getCause() == null ? "" : thrown.getCause().getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("unresolved_by_opened_at") || message.contains("lineage_capture")
                        || message.contains("provisioning"),
                "the message should point at what could not be read. Got: " + message);
    }
}
