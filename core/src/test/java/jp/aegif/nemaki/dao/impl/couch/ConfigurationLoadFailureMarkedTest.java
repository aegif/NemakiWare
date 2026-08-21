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
package jp.aegif.nemaki.dao.impl.couch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.constant.SystemConst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The side that PRODUCES the failed-read marker — and, just as importantly, the side that does
 * NOT set it.
 *
 * <p>The consumer-side tests all mock the read, so none of them would notice if the marker
 * stopped being set here, and none would notice if it started being set unconditionally. The
 * second is the dangerous direction: an always-marked read is never cached, and this method sits
 * under {@code PropertyManager.readValue}, which is on per-request and per-object paths. A
 * healthy deployment would issue a Mango {@code _find} for every property read (external review).
 */
class ConfigurationLoadFailureMarkedTest {

    private static ContentDaoServiceImpl daoWith(CloudantClientPool pool) {
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        dao.setConnectorPool(pool);
        return dao;
    }

    /** A pool whose client answers one {@code _find} page, with no bookmark to continue from. */
    private static CloudantClientPool poolReturning(List<Document> docs) {
        return poolReturning(docs, null, null);
    }

    /**
     * A pool answering {@code firstPage} then {@code secondPage}, reporting {@code bookmark}.
     *
     * <p>A null {@code bookmark} is the shape that matters here: CouchDB gave us a page and no way
     * to ask for the next one.
     */
    @SuppressWarnings("unchecked")
    private static CloudantClientPool poolReturning(List<Document> docs, List<Document> secondPage,
            String bookmark) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
        Cloudant client = mock(Cloudant.class);
        ServiceCall<FindResult> call = mock(ServiceCall.class);
        Response<FindResult> response = mock(Response.class);
        FindResult result = mock(FindResult.class);

        when(pool.getClient(anyString())).thenReturn(wrapper);
        when(wrapper.getDatabaseName()).thenReturn(SystemConst.NEMAKI_CONF_DB);
        when(wrapper.getClient()).thenReturn(client);
        when(client.postFind(any(PostFindOptions.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.getResult()).thenReturn(result);
        if (secondPage == null) {
            when(result.getDocs()).thenReturn(docs);
        } else {
            when(result.getDocs()).thenReturn(docs, secondPage);
        }
        when(result.getBookmark()).thenReturn(bookmark);
        return pool;
    }

    private static Document configDoc(String key, Object value) {
        Document doc = new Document();
        doc.setProperties(Map.of("type", "configuration", "key", key, "value", value));
        return doc;
    }

    // ── The marker IS set ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a read that throws is marked")
    void throwingReadIsMarked() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertTrue(daoWith(pool).getConfiguration("bedroom").isLoadFailed());
    }

    @Test
    @DisplayName("a query that throws mid-read is marked")
    void throwingQueryIsMarked() {
        // The realistic outage: the client exists, the request fails. CloudantClientPool.getClient
        // never actually returns null in production — it returns or throws — so this is the branch
        // that fires, not the null-client one.
        CloudantClientPool pool = poolReturning(List.of());
        when(pool.getClient(anyString()).getClient().postFind(any(PostFindOptions.class)))
                .thenThrow(new RuntimeException("socket timeout"));

        assertTrue(daoWith(pool).getConfiguration("bedroom").isLoadFailed());
    }

    @Test
    @DisplayName("an unavailable configuration database is marked")
    void unavailableClientIsMarked() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(null);

        assertTrue(daoWith(pool).getConfiguration("bedroom").isLoadFailed());
    }

    // ── The marker is NOT set ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a successful read leaves the marker clear, so it can still be cached")
    void successfulReadIsNotMarked() {
        // Without this, moving setLoadFailed(true) to the top of the method unconditionally would
        // pass every other test in this file and every consumer test — while making the product
        // re-read the configuration from CouchDB on every single property lookup.
        Configuration config = daoWith(poolReturning(List.of(
                configDoc("lineage.mode", "journaled")))).getConfiguration(SystemConst.NEMAKI_CONF_DB);

        assertFalse(config.isLoadFailed(),
                "a read that succeeded must be cacheable; marking it would defeat the cache");
        assertEquals("journaled", config.getConfiguration().get("lineage.mode"),
                "control: the read really did reach the documents, so the clear marker above is "
                        + "the success path and not an early return");
    }

    @Test
    @DisplayName("a successful read that finds nothing is still not marked")
    void successfulEmptyReadIsNotMarked() {
        // "Nothing is configured" is a real answer and must be distinguishable from "could not
        // read" — that distinction is the entire point of the marker.
        Configuration config = daoWith(poolReturning(List.of()))
                .getConfiguration(SystemConst.NEMAKI_CONF_DB);

        assertFalse(config.isLoadFailed());
        assertTrue(config.getConfiguration().isEmpty());
    }

    @Test
    @DisplayName("a page that fills up with nothing to continue from is marked")
    void truncatedPageIsMarked() {
        // A full page and no bookmark: there may be settings we cannot reach. Stopping silently
        // returned a PARTIAL configuration indistinguishable from a complete one — which was then
        // cached (external review). Partial is a failure to read, not a small answer.
        List<Document> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            fullPage.add(configDoc("key." + i, "v" + i));
        }
        CloudantClientPool pool = poolReturning(fullPage);   // poolReturning gives a null bookmark

        Configuration config = daoWith(pool).getConfiguration(SystemConst.NEMAKI_CONF_DB);

        assertTrue(config.isLoadFailed(),
                "200 documents and no way to ask for more is an incomplete read");
    }

    @Test
    @DisplayName("a page that fills up WITH a bookmark keeps paging and is not marked")
    void fullPageWithBookmarkIsNotMarked() {
        // The control: a full page is normal when there IS a bookmark to continue from. Marking
        // that would fail every installation with more than 200 settings.
        List<Document> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            fullPage.add(configDoc("key." + i, "v" + i));
        }
        // Page 1: full, with a bookmark. Page 2: empty, ending the loop.
        CloudantClientPool pool = poolReturning(fullPage, List.of(), "bm-1");

        Configuration config = daoWith(pool).getConfiguration(SystemConst.NEMAKI_CONF_DB);

        assertFalse(config.isLoadFailed(),
                "paging is not a failure; only a full page with nowhere to continue is");
        assertEquals(200, config.getConfiguration().size());
    }

    @Test
    @DisplayName("a fresh Configuration is not marked")
    void unmarkedByDefault() {
        // If the field defaulted to true, every assertion above would hold while meaning nothing.
        assertFalse(new Configuration().isLoadFailed());
    }
}
