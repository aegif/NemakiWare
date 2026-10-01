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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * {@code getContent}'s null means ABSENT, and nothing else.
 *
 * <h2>Why the wrapper's care must survive this layer</h2>
 *
 * <p>{@code CloudantClientWrapper.get()} already splits the answers: null ONLY for a genuine
 * {@code NotFoundException}, a throw for everything else — its own comment says a failure "is
 * NOT absence". The delegate's catch flattened that throw back into null, and the consumers
 * that act on absence acted on it: tombstone resolution deleted external catalog entities for
 * documents that still exist, archive reconciliation read a hiccup as "the original is gone",
 * and principal deletion skipped the parent it could not re-fetch. Found by the round-32
 * sibling sweep as the root under three separate P1s.
 */
class ContentLookupFailuresAreNotAbsenceTest {

    private static final String REPO = "bedroom";

    private ContentDaoServiceImpl serviceWith(CloudantClientWrapper client) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(REPO)).thenReturn(client);
        ContentDaoServiceImpl service = new ContentDaoServiceImpl();
        service.setConnectorPool(pool);
        return service;
    }

    @Test
    @DisplayName("a failed lookup throws — it is NOT 'the object does not exist'")
    void aFailedLookupThrows() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get("doc-1")).thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> serviceWith(client).getContent(REPO, "doc-1"),
                "a CouchDB hiccup was served as 'does not exist', and the consumers that "
                        + "delete on absence deleted");
    }

    @Test
    @DisplayName("a failed version-series lookup throws — restore re-creates a series on null")
    void aFailedVersionSeriesLookupThrows() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(jp.aegif.nemaki.model.couch.CouchVersionSeries.class, "vs-1"))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> serviceWith(client).getVersionSeries(REPO, "vs-1"),
                "a failed read was served as 'no version series exists'");
    }

    @Test
    @DisplayName("a failed bulk read throws — a partial map reads as the whole world")
    void aFailedBulkReadThrows() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.getBulkDocuments(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> serviceWith(client).getContentsByIds(REPO,
                        java.util.List.of("doc-1", "doc-2")),
                "the incremental sync filtered the missing entries away, published nothing "
                        + "for them, advanced its cursor, and reported COMPLETED");
    }

    @Test
    @DisplayName("a fetched document that will not convert throws — not silently absent")
    void anUnconvertibleBulkRowThrows() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.Document broken =
                mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        // Null properties alone converts leniently (an empty content); a document whose
        // very reads blow up is the shape that lands in the conversion catch.
        when(broken.getId()).thenThrow(new RuntimeException("corrupt row"));
        java.util.Map<String, com.ibm.cloud.cloudant.v1.model.Document> bulk =
                new java.util.HashMap<>();
        bulk.put("doc-1", broken);
        when(client.getBulkDocuments(org.mockito.ArgumentMatchers.anyList())).thenReturn(bulk);

        assertThrows(IllegalStateException.class,
                () -> serviceWith(client).getContentsByIds(REPO, java.util.List.of("doc-1")));
    }

    @Test
    @DisplayName("the childrenNames liveness probe refuses when it cannot tell")
    void aFailedLivenessProbeRefuses() throws Exception {
        // "Say alive" blessed a blind uniqueness check in the one combination the probe
        // exists for: the names view answers zero rows (rebuild) while the count probe
        // fails on connection. A refused create retries; a duplicate name does not.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryViewCount("_repo", "childrenNames"))
                .thenThrow(new org.apache.chemistry.opencmis.commons.exceptions
                        .CmisRuntimeException("count failed"));
        ContentDaoServiceImpl service = serviceWith(client);

        java.lang.reflect.Method probe = ContentDaoServiceImpl.class
                .getDeclaredMethod("childrenNamesViewIsAlive", String.class);
        probe.setAccessible(true);
        java.lang.reflect.InvocationTargetException wrapped =
                assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> probe.invoke(service, REPO));
        org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class,
                wrapped.getCause(), String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("the liveness probe refuses when there is no client at all")
    void aClientlessLivenessProbeRefuses() throws Exception {
        // The catch was closed in round 33 while this door — one line above it — still
        // said "the view is alive", which blesses the same blind uniqueness check.
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(REPO)).thenReturn(null);
        ContentDaoServiceImpl service = new ContentDaoServiceImpl();
        service.setConnectorPool(pool);

        java.lang.reflect.Method probe = ContentDaoServiceImpl.class
                .getDeclaredMethod("childrenNamesViewIsAlive", String.class);
        probe.setAccessible(true);
        java.lang.reflect.InvocationTargetException wrapped =
                assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> probe.invoke(service, REPO));
        org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class,
                wrapped.getCause(), String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("the probe refuses when the document count does not answer")
    void aCountlessLivenessProbeRefuses() throws Exception {
        // The client==null and catch doors were closed while the separating fact itself —
        // the database's document count — could still come back null and be read as "alive".
        CloudantClientPool pool = mock(CloudantClientPool.class);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(pool.getClient(REPO)).thenReturn(client);
        when(client.queryViewCount("_repo", "childrenNames")).thenReturn(0L);
        when(client.getDatabaseInfo()).thenReturn(null);
        ContentDaoServiceImpl service = new ContentDaoServiceImpl();
        service.setConnectorPool(pool);

        java.lang.reflect.Method probe = ContentDaoServiceImpl.class
                .getDeclaredMethod("childrenNamesViewIsAlive", String.class);
        probe.setAccessible(true);
        java.lang.reflect.InvocationTargetException wrapped =
                assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> probe.invoke(service, REPO));
        org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class,
                wrapped.getCause(), String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("a genuine not-found is still null — the absence contract holds")
    void aGenuineNotFoundIsStillNull() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        // The wrapper returns null ONLY for NotFoundException; this models that answer.
        when(client.get("doc-2")).thenReturn(null);

        assertNull(serviceWith(client).getContent(REPO, "doc-2"),
                "the refusal arm broke the ordinary not-found answer");
    }
}
