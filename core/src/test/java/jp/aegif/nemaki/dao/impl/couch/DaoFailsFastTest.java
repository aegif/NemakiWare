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

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Could not read" must not arrive as "there is nothing there" (roadmap §2-1).
 *
 * <h2>Why this is the prerequisite for everything else</h2>
 *
 * <p>The authenticity work claims a record is complete. That claim cannot stand on a store whose
 * reads and writes can fail silently — and they did:
 *
 * <ul>
 *   <li>{@code CloudantClientWrapper.update(Map)} caught every exception and returned null. Of
 *       the two dozen call sites, <b>not one checked</b>, so "the write did not happen" reached
 *       nobody.</li>
 *   <li>{@code get(String)} returned null for a connection failure exactly as it does for a
 *       missing document, and logged "This is normal during initial startup" either way.</li>
 *   <li>{@code ContentDaoServiceImpl.getChildren} returned an EMPTY LIST on failure — a
 *       statement of fact about the repository. The v3.3 "empty index reported complete"
 *       incident grew from that: a reindex walked a folder whose listing had failed, recorded
 *       it as empty, and finished green.</li>
 * </ul>
 *
 * <p>Startup stays lenient in all three, matching the rule {@code delete()} already used:
 * provisioning and patches run against a database that may not be ready, and turning those into
 * hard failures would stop deployments that have always come up. These tests therefore run on a
 * worker thread — {@code isStartupPhase()} keys on the thread name.
 */
class DaoFailsFastTest {

    /** Runs the body on a thread whose name is not "main"/"startup"/"init". */
    private static void offStartupThread(Runnable body) throws Exception {
        Throwable[] thrown = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                thrown[0] = e;
            }
        }, "nemaki-worker");
        t.start();
        t.join();
        if (thrown[0] instanceof AssertionError err) {
            throw err;
        }
        if (thrown[0] != null) {
            throw new AssertionError("unexpected failure in the worker", thrown[0]);
        }
    }

    @Test
    @DisplayName("getChildren refuses rather than reporting an empty folder")
    void getChildrenFailsFast() throws Exception {
        offStartupThread(() -> {
            ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
            CloudantClientPool pool = mock(CloudantClientPool.class);
            CloudantClientWrapper client = mock(CloudantClientWrapper.class);
            when(pool.getClient(anyString())).thenReturn(client);
            when(client.queryView(anyString(), anyString(),
                    org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                    .thenThrow(new RuntimeException("connection reset"));
            dao.setConnectorPool(pool);

            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> dao.getChildren("bedroom", "folder-1"),
                    "a failed enumeration was reported as 'this folder has no children' — the "
                            + "caller cannot tell the two apart, and a reindex would record the "
                            + "folder as empty and finish green");
            assertTrue(e.getMessage().contains("folder-1"), e.getMessage());
        });
    }

    @Test
    @DisplayName("an empty folder is still an empty list — the control")
    void anEmptyFolderIsStillEmpty() throws Exception {
        // Without this, a dao that threw for everything would pass the test above.
        offStartupThread(() -> {
            ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
            CloudantClientPool pool = mock(CloudantClientPool.class);
            CloudantClientWrapper client = mock(CloudantClientWrapper.class);
            when(pool.getClient(anyString())).thenReturn(client);
            when(client.queryView(anyString(), anyString(),
                    org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                    .thenReturn(null);
            dao.setConnectorPool(pool);

            assertTrue(dao.getChildren("bedroom", "folder-1").isEmpty(),
                    "a folder that genuinely has no children must still answer with an empty "
                            + "list — over-throwing would break every empty folder");
        });
    }
}
