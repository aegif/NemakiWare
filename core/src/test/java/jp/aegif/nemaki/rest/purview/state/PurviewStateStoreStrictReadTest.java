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
package jp.aegif.nemaki.rest.purview.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * The PRODUCTION store's strict reads (4b preflight, v2.3.27).
 *
 * <p>These exist because the round-4 review found the gap they close: every ordinary path here
 * degrades gracefully on a database failure — the DAO answers with an empty configuration, the
 * client wrapper answers {@code null}, and the client resolver falls back from the dedicated
 * database to the legacy one. All three are right for a read and wrong for an acceptance check,
 * where an unreadable store must be {@code ERROR} and never a clean absence.
 */
public class PurviewStateStoreStrictReadTest {

    private static PurviewStateStoreImpl storeWith(CloudantClientPool pool) throws Exception {
        Constructor<PurviewStateStoreImpl> ctor = PurviewStateStoreImpl.class
                .getDeclaredConstructor(ContentDaoService.class, CloudantClientPool.class);
        ctor.setAccessible(true);
        PurviewStateStoreImpl store = ctor.newInstance(mock(ContentDaoService.class), pool);
        Field field = PurviewStateStoreImpl.class.getDeclaredField("connectorPool");
        field.setAccessible(true);
        field.set(store, pool);
        return store;
    }

    /** An unreadable dedicated store is ERROR, not absence. */
    @Test
    public void aFailingDedicatedStoreIsError() throws Exception {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("dedicated DB unreachable"))
                .when(pool).getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doThrow(new IllegalStateException("legacy DB unreachable"))
                .when(pool).getClient(SystemConst.NEMAKI_CONF_DB);

        List<PurviewStateStore.RawEntry> entries =
                storeWith(pool).getRawEverywhere("purview.cursor.state.bedroom.x.cursor");
        assertEquals(2, entries.size(), "both stores are reported, independently");
        assertTrue(entries.stream().allMatch(
                        e -> e.presence() == PurviewStateStore.Presence.ERROR),
                "a store nobody could read is never clean");
    }

    /**
     * The one the review found: a legacy store that fails must not read as absent just because
     * the DAO swallows the failure. This drives the strict path, which throws instead.
     */
    @Test
    public void aFailingLegacyStoreIsErrorNotAbsence() throws Exception {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        CloudantClientWrapper dedicated = mock(CloudantClientWrapper.class);
        when(dedicated.getDatabaseName()).thenReturn(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doThrow(mock(
                        com.ibm.cloud.sdk.core.service.exception.NotFoundException.class))
                .when(dedicated).getClient();
        org.mockito.Mockito.doReturn(dedicated).when(pool)
                .getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doThrow(new IllegalStateException("legacy DB unreachable"))
                .when(pool).getClient(SystemConst.NEMAKI_CONF_DB);

        List<PurviewStateStore.RawEntry> entries =
                storeWith(pool).getRawEverywhere("purview.cursor.state.bedroom.x.cursor");
        assertEquals(PurviewStateStore.Presence.ERROR, entries.get(1).presence(),
                "the legacy store failed; reporting ABSENT would call it clean");
    }

    /** A missing dedicated database must not silently become the legacy one. */
    @Test
    public void theStrictDedicatedReadDoesNotFallBackToLegacy() throws Exception {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        org.mockito.Mockito.doReturn(null).when(pool)
                .getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        CloudantClientWrapper legacy = mock(CloudantClientWrapper.class);
        org.mockito.Mockito.doReturn(legacy).when(pool)
                .getClient(SystemConst.NEMAKI_CONF_DB);

        List<PurviewStateStore.RawEntry> entries =
                storeWith(pool).getRawEverywhere("purview.cursor.state.bedroom.x.cursor");
        assertEquals(PurviewStateStore.Presence.ERROR, entries.get(0).presence(),
                "no dedicated database means no dedicated answer — not the legacy one's");
    }

    /**
     * The legacy database stores configuration as one {@code key}/{@code value} document per
     * key, not as one aggregate map. Reading only the aggregate shape — which an earlier
     * version of this code did — reports a dirty legacy cursor as ABSENT.
     */
    @Test
    public void aDirtyLegacyKeyValueDocumentIsFound() throws Exception {
        String cursorKey = "purview.cursor.state.bedroom.cloud-metadata-snapshot.cursor";
        String dirty = "doc-1|onedrive|file-1|https://x/:x:/g/tok?authkey=abc|2026-01-01";

        CloudantClientPool pool = mock(CloudantClientPool.class);
        // A working-but-empty dedicated store, so this test isolates the LEGACY path rather
        // than tripping the (correct) fail-closed behaviour of a missing dedicated database.
        org.mockito.Mockito.doReturn(emptyDedicatedClient()).when(pool)
                .getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doReturn(legacyClientHolding(cursorKey, dirty)).when(pool)
                .getClient(SystemConst.NEMAKI_CONF_DB);

        PurviewStateStoreImpl store = storeWith(pool);
        assertTrue(store.getAllStrict().containsKey(cursorKey),
                "the per-key legacy document must be enumerated");

        List<PurviewStateStore.RawEntry> entries = store.getRawEverywhere(cursorKey);
        PurviewStateStore.RawEntry legacyEntry = entries.get(entries.size() - 1);
        assertEquals(PurviewStateStore.Presence.PRESENT_VALUE, legacyEntry.presence(),
                "a dirty legacy cursor reported as ABSENT is exactly the false green");
        assertEquals(dirty, legacyEntry.value());
    }

    /**
     * A dedicated row whose document id does not derive from its key would be enumerated here
     * and then 404 on the point read — reporting ABSENT for a key that is demonstrably there.
     */
    @Test
    public void aDedicatedRowWhoseIdDoesNotMatchItsKeyFailsTheInventory() throws Exception {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        org.mockito.Mockito.doReturn(dedicatedClientWithRow("wrong_id",
                        "purview.cursor.state.bedroom.cloud-metadata-snapshot.cursor", "x"))
                .when(pool).getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doReturn(legacyClientHolding("other", "y")).when(pool)
                .getClient(SystemConst.NEMAKI_CONF_DB);

        assertThrows(RuntimeException.class, storeWith(pool)::getAllStrict,
                "an id/key mismatch must fail the inventory, not be enumerated");
    }

    /**
     * Both legacy shapes can coexist. Last-write-wins would let a clean per-key document
     * overwrite a dirty aggregate value and produce green.
     */
    @Test
    public void theSameLegacyKeyInTwoShapesFailsClosed() throws Exception {
        String key = "purview.cursor.state.bedroom.cloud-metadata-snapshot.cursor";

        com.ibm.cloud.cloudant.v1.model.Document perKey =
                new com.ibm.cloud.cloudant.v1.model.Document();
        perKey.setId("conf-1");
        perKey.put("key", key);
        perKey.put("value", "doc-1|onedrive|f||2026-01-01");   // clean

        com.ibm.cloud.cloudant.v1.model.Document aggregate =
                new com.ibm.cloud.cloudant.v1.model.Document();
        aggregate.setId("conf-2");
        aggregate.put("configuration", java.util.Map.of(key,
                "doc-1|onedrive|f|https://x/?authkey=abc|2026-01-01"));   // dirty

        CloudantClientPool pool = mock(CloudantClientPool.class);
        org.mockito.Mockito.doReturn(emptyDedicatedClient()).when(pool)
                .getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doReturn(legacyClientWith(List.of(perKey, aggregate))).when(pool)
                .getClient(SystemConst.NEMAKI_CONF_DB);

        assertThrows(RuntimeException.class, storeWith(pool)::getAllStrict,
                "there is no basis for choosing between two stored copies, so neither wins");
    }

    /**
     * The duplicate check must not depend on the FIRST copy having a value: a null-valued row
     * followed by a clean one would otherwise let the clean one win silently.
     */
    @Test
    public void aDuplicateWhoseFirstCopyIsNullStillFailsClosed() throws Exception {
        String key = "purview.cursor.state.bedroom.cloud-metadata-snapshot.cursor";

        com.ibm.cloud.cloudant.v1.model.Document nullFirst =
                new com.ibm.cloud.cloudant.v1.model.Document();
        nullFirst.setId("conf-1");
        nullFirst.put("key", key);
        nullFirst.put("value", null);

        com.ibm.cloud.cloudant.v1.model.Document cleanSecond =
                new com.ibm.cloud.cloudant.v1.model.Document();
        cleanSecond.setId("conf-2");
        cleanSecond.put("key", key);
        cleanSecond.put("value", "doc-1|onedrive|f||2026-01-01");

        CloudantClientPool pool = mock(CloudantClientPool.class);
        org.mockito.Mockito.doReturn(emptyDedicatedClient()).when(pool)
                .getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doReturn(legacyClientWith(List.of(nullFirst, cleanSecond)))
                .when(pool).getClient(SystemConst.NEMAKI_CONF_DB);

        assertThrows(RuntimeException.class, storeWith(pool)::getAllStrict,
                "a null first copy must not let the second one win by default");
    }

    /**
     * {@code buildDocumentId} maps '.' to '_', so two different keys can share one id. The
     * point read must confirm the document it fetched is the key it asked for.
     */
    @Test
    public void aCollidingDocumentIdIsNotReadAsTheRequestedKey() throws Exception {
        String requested = "purview.cursor.state.bedroom.cloud-metadata-snapshot.cursor";
        String collider = "purview_cursor_state_bedroom_cloud-metadata-snapshot_cursor";

        com.ibm.cloud.cloudant.v1.model.Document doc =
                new com.ibm.cloud.cloudant.v1.model.Document();
        doc.setId("system_config_" + collider);
        doc.put("key", collider);              // a DIFFERENT key, same derived id
        doc.put("value", "doc-1|onedrive|f||2026-01-01");   // and it looks clean

        CloudantClientWrapper dedicated = mock(CloudantClientWrapper.class);
        when(dedicated.getDatabaseName()).thenReturn(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = mock(
                com.ibm.cloud.cloudant.v1.Cloudant.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(cloudant.getDocument(org.mockito.ArgumentMatchers.any()).execute().getResult())
                .thenReturn(doc);
        when(dedicated.getClient()).thenReturn(cloudant);

        CloudantClientPool pool = mock(CloudantClientPool.class);
        org.mockito.Mockito.doReturn(dedicated).when(pool)
                .getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doThrow(new IllegalStateException("legacy unavailable")).when(pool)
                .getClient(SystemConst.NEMAKI_CONF_DB);

        List<PurviewStateStore.RawEntry> entries =
                storeWith(pool).getRawEverywhere(requested);
        assertEquals(PurviewStateStore.Presence.ERROR, entries.get(0).presence(),
                "a colliding document must not be read as the requested key");
    }

    /** The same duplicate rule, exercised on the DEDICATED enumeration branch. */
    @Test
    public void aDuplicateInTheDedicatedStoreAlsoFailsClosed() throws Exception {
        String key = "purview.cursor.state.bedroom.cloud-metadata-snapshot.cursor";
        com.ibm.cloud.cloudant.v1.model.Document first =
                new com.ibm.cloud.cloudant.v1.model.Document();
        first.setId("system_config_purview_cursor_state_bedroom_cloud-metadata-snapshot_cursor");
        first.put("key", key);
        first.put("value", null);
        com.ibm.cloud.cloudant.v1.model.Document second =
                new com.ibm.cloud.cloudant.v1.model.Document();
        second.setId("system_config_purview_cursor_state_bedroom_cloud-metadata-snapshot_cursor");
        second.put("key", key);
        second.put("value", "doc-1|onedrive|f||2026-01-01");

        CloudantClientPool pool = mock(CloudantClientPool.class);
        org.mockito.Mockito.doReturn(clientReturning(SystemConst.NEMAKI_PURVIEW_STATE_DB,
                        List.of(first, second)))
                .when(pool).getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doReturn(legacyClientWith(List.of())).when(pool)
                .getClient(SystemConst.NEMAKI_CONF_DB);

        assertThrows(RuntimeException.class, storeWith(pool)::getAllStrict);
    }

    private static CloudantClientWrapper dedicatedClientWithRow(String documentId, String key,
            String value) {
        com.ibm.cloud.cloudant.v1.model.Document doc =
                new com.ibm.cloud.cloudant.v1.model.Document();
        doc.setId(documentId);
        doc.put("key", key);
        doc.put("value", value);
        CloudantClientWrapper wrapper = clientReturning(SystemConst.NEMAKI_PURVIEW_STATE_DB,
                List.of(doc));
        return wrapper;
    }

    private static CloudantClientWrapper legacyClientWith(
            List<com.ibm.cloud.cloudant.v1.model.Document> docs) {
        return clientReturning(SystemConst.NEMAKI_CONF_DB, docs);
    }

    private static CloudantClientWrapper clientReturning(String database,
            List<com.ibm.cloud.cloudant.v1.model.Document> docs) {
        CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
        when(wrapper.getDatabaseName()).thenReturn(database);
        List<com.ibm.cloud.cloudant.v1.model.DocsResultRow> rows = new java.util.ArrayList<>();
        for (com.ibm.cloud.cloudant.v1.model.Document doc : docs) {
            com.ibm.cloud.cloudant.v1.model.DocsResultRow row =
                    mock(com.ibm.cloud.cloudant.v1.model.DocsResultRow.class);
            when(row.getId()).thenReturn(doc.getId());
            when(row.getDoc()).thenReturn(doc);
            when(row.getValue()).thenReturn(null);
            rows.add(row);
        }
        com.ibm.cloud.cloudant.v1.model.AllDocsResult result =
                mock(com.ibm.cloud.cloudant.v1.model.AllDocsResult.class);
        when(result.getRows()).thenReturn(rows);
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = mock(
                com.ibm.cloud.cloudant.v1.Cloudant.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(cloudant.postAllDocs(org.mockito.ArgumentMatchers.any()).execute().getResult())
                .thenReturn(result);
        when(wrapper.getClient()).thenReturn(cloudant);
        return wrapper;
    }

    /** A dedicated store that exists and holds nothing. */
    private static CloudantClientWrapper emptyDedicatedClient() {
        CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
        when(wrapper.getDatabaseName()).thenReturn(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        com.ibm.cloud.cloudant.v1.model.AllDocsResult empty =
                mock(com.ibm.cloud.cloudant.v1.model.AllDocsResult.class);
        when(empty.getRows()).thenReturn(List.of());
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = mock(
                com.ibm.cloud.cloudant.v1.Cloudant.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(cloudant.postAllDocs(org.mockito.ArgumentMatchers.any()).execute().getResult())
                .thenReturn(empty);
        when(cloudant.getDocument(org.mockito.ArgumentMatchers.any()).execute().getResult())
                .thenThrow(mock(
                        com.ibm.cloud.sdk.core.service.exception.NotFoundException.class));
        when(wrapper.getClient()).thenReturn(cloudant);
        return wrapper;
    }

    /** A legacy client whose postAllDocs returns one global key/value configuration document. */
    private static CloudantClientWrapper legacyClientHolding(String key, String value) {
        CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
        when(wrapper.getDatabaseName()).thenReturn(SystemConst.NEMAKI_CONF_DB);

        com.ibm.cloud.cloudant.v1.model.Document doc =
                new com.ibm.cloud.cloudant.v1.model.Document();
        doc.setId("conf-1");
        doc.put("key", key);
        doc.put("value", value);

        com.ibm.cloud.cloudant.v1.model.DocsResultRow row =
                mock(com.ibm.cloud.cloudant.v1.model.DocsResultRow.class);
        when(row.getId()).thenReturn("conf-1");
        when(row.getDoc()).thenReturn(doc);
        when(row.getValue()).thenReturn(null);

        com.ibm.cloud.cloudant.v1.model.AllDocsResult result =
                mock(com.ibm.cloud.cloudant.v1.model.AllDocsResult.class);
        when(result.getRows()).thenReturn(List.of(row));

        com.ibm.cloud.cloudant.v1.Cloudant cloudant =
                mock(com.ibm.cloud.cloudant.v1.Cloudant.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(cloudant.postAllDocs(org.mockito.ArgumentMatchers.any()).execute().getResult())
                .thenReturn(result);
        when(wrapper.getClient()).thenReturn(cloudant);
        return wrapper;
    }

    /** An enumeration that cannot read either store must throw, not return a short list. */
    @Test
    public void strictEnumerationThrowsRatherThanReturningAShortInventory() throws Exception {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("dedicated DB unreachable"))
                .when(pool).getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        org.mockito.Mockito.doThrow(new IllegalStateException("legacy DB unreachable"))
                .when(pool).getClient(SystemConst.NEMAKI_CONF_DB);

        PurviewStateStoreImpl store = storeWith(pool);
        assertThrows(RuntimeException.class, store::getAllStrict,
                "a suppressed enumeration failure is how a short inventory looks complete");
    }
}
