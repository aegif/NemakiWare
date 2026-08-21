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
package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.rest.purview.state.PurviewStateStoreImpl;
import jp.aegif.nemaki.util.constant.SystemConst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Settings are never rewritten on top of a read that failed.
 *
 * <p>Both writers replace the whole map: read the configuration, put or remove a key, write it
 * back. A failed read yields an EMPTY map, so the write persists a document containing only what
 * this call adds — every other setting silently dropped. Before the read-failure marker existed
 * there was no way to tell that apart from "nothing was configured yet", which is a legitimate
 * reason to write a one-key document (external review).
 *
 * <h2>What this does NOT claim</h2>
 *
 * <p>Refusing the write does not preserve anything that a previous run already truncated, and it
 * does not make the settings readable. It only stops a transient outage from being written down
 * as a deletion.
 */
class ConfigurationRewriteRefusedTest {

    private static Configuration failedRead() {
        Configuration c = new Configuration();
        c.setLoadFailed(true);
        return c;
    }

    private static Configuration goodRead() {
        Configuration c = new Configuration();
        c.setId("config_" + SystemConst.NEMAKI_CONF_DB);
        c.setConfiguration(new HashMap<>(Map.of("existing.key", "existing-value")));
        return c;
    }

    // ── The Purview legacy path (used when no connector pool is wired) ─────────────────────

    @Test
    @DisplayName("putAll refuses rather than persisting a one-key document")
    void putAllRefusesAfterFailedRead() {
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getConfiguration(SystemConst.NEMAKI_CONF_DB)).thenReturn(failedRead());
        PurviewStateStoreImpl store = new PurviewStateStoreImpl(dao, null);

        assertThrows(IllegalStateException.class,
                () -> store.putAll(Map.of("purview.enabled", "true")));

        verify(dao, never()).update(anyString(), any(Configuration.class));
    }

    @Test
    @DisplayName("removeAll refuses rather than persisting an empty document")
    void removeAllRefusesAfterFailedRead() {
        // The sharper direction: a remove built on a failed read writes back a map that is empty
        // for reasons having nothing to do with the keys being removed.
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getConfiguration(SystemConst.NEMAKI_CONF_DB)).thenReturn(failedRead());
        PurviewStateStoreImpl store = new PurviewStateStoreImpl(dao, null);

        assertThrows(IllegalStateException.class,
                () -> store.removeAll(List.of("purview.enabled")));

        verify(dao, never()).update(anyString(), any(Configuration.class));
    }

    @Test
    @DisplayName("a successful read still writes, and keeps the settings it did not touch")
    void successfulReadStillWrites() {
        // The control. Without it the refusal could be unconditional — which would break every
        // settings update on a healthy system.
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getConfiguration(SystemConst.NEMAKI_CONF_DB)).thenReturn(goodRead());
        PurviewStateStoreImpl store = new PurviewStateStoreImpl(dao, null);

        store.putAll(Map.of("purview.enabled", "true"));

        org.mockito.ArgumentCaptor<Configuration> written =
                org.mockito.ArgumentCaptor.forClass(Configuration.class);
        verify(dao).update(anyString(), written.capture());
        assertEquals("true", written.getValue().getConfiguration().get("purview.enabled"));
        assertEquals("existing-value", written.getValue().getConfiguration().get("existing.key"),
                "the settings the caller did not touch must survive the rewrite");
    }

    @Test
    @DisplayName("an absent configuration is created, which is not the same as an unreadable one")
    void absentConfigurationIsStillCreated() {
        // "Create" in getOrCreateSystemConfiguration covers a first run. That must keep working;
        // only the unreadable case is refused.
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getConfiguration(SystemConst.NEMAKI_CONF_DB)).thenReturn(null);
        PurviewStateStoreImpl store = new PurviewStateStoreImpl(dao, null);

        store.putAll(Map.of("purview.enabled", "true"));

        verify(dao).update(anyString(), any(Configuration.class));
    }

    // ── The admin settings endpoint ───────────────────────────────────────────────────────

    /**
     * Drives the real endpoint. An earlier version of this test re-implemented the endpoint's
     * three statements in the test itself, which would have passed with no guard in the product
     * at all.
     */
    private static ConfigResource endpointWith(ContentDaoService dao) {
        ConfigResource resource = new ConfigResource();
        resource.setContentDaoService(dao);
        resource.setPropertyManager(mock(jp.aegif.nemaki.util.PropertyManager.class));
        jp.aegif.nemaki.util.lock.ThreadLockService locks =
                mock(jp.aegif.nemaki.util.lock.ThreadLockService.class);
        when(locks.getWriteLock(anyString(), anyString()))
                .thenReturn(new java.util.concurrent.locks.ReentrantLock());
        resource.setThreadLockService(locks);
        return resource;
    }

    /** A request that passes both the CSRF check and the admin check. */
    private static jakarta.servlet.http.HttpServletRequest adminRequest() {
        jakarta.servlet.http.HttpServletRequest request =
                mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        org.apache.chemistry.opencmis.commons.server.CallContext ctx =
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        when(ctx.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        return request;
    }

    @Test
    @DisplayName("the endpoint refuses on a failed read and reports failure")
    void configResourceRefusesAfterFailedRead() {
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getConfiguration("bedroom")).thenReturn(failedRead());

        String json = endpointWith(dao)
                .update("bedroom", "lineage.mode", "journaled", adminRequest());

        verify(dao, never()).update(anyString(), any(Configuration.class));
        assertTrue(json.contains("failure"),
                "the caller must be told the setting was not saved. Got: " + json);
    }

    @Test
    @DisplayName("the endpoint writes when the read succeeded, keeping untouched settings")
    void configResourceWritesAfterGoodRead() {
        // The control: the refusal must not be unconditional, or every settings update breaks.
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getConfiguration("bedroom")).thenReturn(goodRead());

        String json = endpointWith(dao)
                .update("bedroom", "lineage.mode", "journaled", adminRequest());

        org.mockito.ArgumentCaptor<Configuration> written =
                org.mockito.ArgumentCaptor.forClass(Configuration.class);
        verify(dao).update(anyString(), written.capture());
        assertEquals("journaled", written.getValue().getConfiguration().get("lineage.mode"));
        assertEquals("existing-value", written.getValue().getConfiguration().get("existing.key"),
                "the settings the caller did not touch must survive the rewrite");
        assertTrue(json.contains("success"), json);
    }
}
