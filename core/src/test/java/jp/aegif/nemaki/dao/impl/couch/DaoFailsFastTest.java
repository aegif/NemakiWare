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

import static org.junit.jupiter.api.Assertions.assertSame;
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
        //
        // An EMPTY folder is a view that ANSWERED with no rows — not a null. The first draft
        // of this test stubbed null and asserted an empty list, which pinned the very hole the
        // rest of the class exists to close: an undeployed design document read as an empty
        // folder (external review). The stub is now the shape the store actually returns when
        // there is nothing there.
        offStartupThread(() -> {
            ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
            CloudantClientPool pool = mock(CloudantClientPool.class);
            CloudantClientWrapper client = mock(CloudantClientWrapper.class);
            when(pool.getClient(anyString())).thenReturn(client);
            com.ibm.cloud.cloudant.v1.model.ViewResult empty =
                    mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
            when(empty.getRows()).thenReturn(java.util.List.of());
            when(client.queryView(anyString(), anyString(),
                    org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                    .thenReturn(empty);
            dao.setConnectorPool(pool);

            assertTrue(dao.getChildren("bedroom", "folder-1").isEmpty(),
                    "a folder that genuinely has no children must still answer with an empty "
                            + "list — over-throwing would break every empty folder");
        });
    }

    @Test
    @DisplayName("counting children refuses rather than answering zero")
    void countingChildrenFailsFast() throws Exception {
        // Zero meant three things at once: "no children", "we could not count", and "the
        // reduce is not deployed". A caller that only asks for the COUNT has no probe to fall
        // back on, so it writes the same lie the empty index wrote (external review).
        offStartupThread(() -> {
            ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
            CloudantClientPool pool = mock(CloudantClientPool.class);
            CloudantClientWrapper client = mock(CloudantClientWrapper.class);
            when(pool.getClient(anyString())).thenReturn(client);
            when(client.queryView(anyString(), anyString(),
                    org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                    .thenThrow(new RuntimeException("connection reset"));
            dao.setConnectorPool(pool);

            assertThrows(RuntimeException.class,
                    () -> dao.getChildrenCount("bedroom", "folder-1"),
                    "a failed count was reported as zero children");
        });
    }

    @Test
    @DisplayName("a folder that really is empty still counts zero — the control")
    void anEmptyFolderStillCountsZero() throws Exception {
        offStartupThread(() -> {
            ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
            CloudantClientPool pool = mock(CloudantClientPool.class);
            CloudantClientWrapper client = mock(CloudantClientWrapper.class);
            when(pool.getClient(anyString())).thenReturn(client);
            com.ibm.cloud.cloudant.v1.model.ViewResult empty =
                    mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
            when(empty.getRows()).thenReturn(java.util.List.of());
            when(client.queryView(anyString(), anyString(),
                    org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                    .thenReturn(empty);
            dao.setConnectorPool(pool);

            org.junit.jupiter.api.Assertions.assertEquals(0,
                    dao.getChildrenCount("bedroom", "folder-1"));
        });
    }

    @Test
    @DisplayName("a write that failed is reported — the 24 callers that never checked null")
    void updateFailsFast() throws Exception {
        // CloudantClientWrapper.update(Map) caught every exception and returned null, and of
        // the two dozen call sites not one checked. "The write did not happen" reached nobody.
        //
        // Driven through the real method with no client behind it, which is the cheapest way
        // to make the write fail. What is under test is the CATCH — that a failure leaves by
        // the exception path rather than as a null every caller reads as success.
        offStartupThread(() -> {
            CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
            when(wrapper.update(
                    org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                    .thenCallRealMethod();

            assertThrows(RuntimeException.class,
                    () -> wrapper.update(new java.util.HashMap<>(
                            java.util.Map.of("_id", "doc-1", "type", "x"))),
                    "a failed write returned null, which every caller reads as success");
        });
    }

    @Test
    @DisplayName("a create that failed is reported, not returned as null")
    void createFailsFast() throws Exception {
        // The last fail-open on the write path, and the reason an extra existence GET follows
        // every attachment create (redundant-round-trips V2): create() caught everything and
        // returned null with the comment "This is normal during initial startup" — true during
        // startup, and a lie for every request after it. A create that never happened reached
        // callers as an ordinary result, and the whole system paid a verification read to
        // notice.
        offStartupThread(() -> {
            CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
            when(wrapper.create(
                    org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                    .thenCallRealMethod();

            assertThrows(RuntimeException.class,
                    () -> wrapper.create(new java.util.HashMap<>(
                            java.util.Map.of("type", "x"))),
                    "a failed create returned null, which callers read as a created document");
        });
    }

    @Test
    @DisplayName("a ConflictException reaches the caller AS a ConflictException")
    void aConflictKeepsItsType() {
        // AttachmentDaoDelegate's create loop catches ConflictException BY TYPE and retries.
        // The first version of the fail-fast change wrapped every exception in a plain
        // RuntimeException, so that catch stopped matching and an ordinary CAS loss became a
        // hard failure of the upload. Nothing in the suite noticed, because nothing pinned the
        // TYPE — only that something was thrown.
        com.ibm.cloud.sdk.core.service.exception.ConflictException conflict =
                mock(com.ibm.cloud.sdk.core.service.exception.ConflictException.class);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> CloudantClientWrapper.rethrowIfUnchecked(conflict));

        assertSame(conflict, thrown,
                "the conflict was wrapped; AttachmentDaoDelegate catches ConflictException by "
                        + "type, so its retry loop stops seeing ordinary CAS losses and an "
                        + "upload that should have retried fails outright");
    }

    @Test
    @DisplayName("BOTH create overloads let a ConflictException through by type")
    void bothCreateOverloadsPreserveConflictType() throws Exception {
        // The helper being right is not enough. A reviewer showed that deleting the two
        // `rethrowIfUnchecked(e);` CALL SITES left every test passing — so the regression that
        // broke AttachmentDaoDelegate's retry loop could return unnoticed. This drives the real
        // catch blocks: a REAL wrapper over a Cloudant client that throws a conflict.
        offStartupThread(() -> {
            for (boolean explicitId : new boolean[] { false, true }) {
                com.ibm.cloud.sdk.core.service.exception.ConflictException conflict =
                        mock(com.ibm.cloud.sdk.core.service.exception.ConflictException.class);
                com.ibm.cloud.cloudant.v1.Cloudant cloudant =
                        mock(com.ibm.cloud.cloudant.v1.Cloudant.class);
                // create(Map) posts, create(String, Map) puts. Stubbing one and asserting on
                // the other is how the first version of this test "failed" against correct code.
                when(cloudant.postDocument(org.mockito.ArgumentMatchers.any()))
                        .thenThrow(conflict);
                when(cloudant.putDocument(org.mockito.ArgumentMatchers.any()))
                        .thenThrow(conflict);
                CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudant,
                        "nemaki_evidence_ledger",
                        jp.aegif.nemaki.config.ObjectMapperFactory.createCouchdbObjectMapper());

                Throwable thrown = assertThrows(Throwable.class, () -> {
                    if (explicitId) {
                        wrapper.create("doc-1",
                                new java.util.HashMap<>(java.util.Map.of("type", "x")));
                    } else {
                        wrapper.create(new java.util.HashMap<>(java.util.Map.of("type", "x")));
                    }
                });

                assertSame(conflict, thrown,
                        (explicitId ? "create(String, Map)" : "create(Map)") + " wrapped the "
                                + "conflict. AttachmentDaoDelegate catches ConflictException by "
                                + "TYPE to retry, so an ordinary CAS loss becomes a hard upload "
                                + "failure — the regression the previous fix introduced.");
            }
        });
    }

    @Test
    @DisplayName("a checked failure is NOT rethrown as-is — the control")
    void aCheckedFailureIsNotRethrown() {
        // Without this, "always rethrow" would pass the test above while handing callers a
        // checked exception the signature does not declare.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> CloudantClientWrapper.rethrowIfUnchecked(
                        new java.io.IOException("disk gone")),
                "a checked exception was rethrown; the caller cannot catch what is not declared");
    }

    @Test
    @DisplayName("a create during STARTUP is still lenient — the control")
    void createStaysLenientDuringStartup() {
        // Provisioning runs against a database that may not exist yet. Making this a hard
        // failure would stop deployments that have always come up — the reason roadmap 2-2
        // refused "convert all sixteen" applies here too.
        CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
        when(wrapper.create(org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                .thenCallRealMethod();

        // The current thread is the test runner's; isStartupPhase() keys on the thread NAME,
        // so run it on one that looks like startup.
        java.util.concurrent.atomic.AtomicReference<Object> result =
                new java.util.concurrent.atomic.AtomicReference<>("not-run");
        Thread startup = new Thread(() -> {
            try {
                result.set(wrapper.create(new java.util.HashMap<>(
                        java.util.Map.of("type", "x"))));
            } catch (Throwable t) {
                result.set(t);
            }
        }, "main");
        startup.start();
        try {
            startup.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        org.junit.jupiter.api.Assertions.assertNull(result.get(),
                "startup stopped being lenient (" + result.get() + "); provisioning would fail "
                        + "on a database that does not exist yet");
    }

    @Test
    @DisplayName("a view that is NOT DEPLOYED is refused, not read as an empty folder")
    void anUndeployedViewIsRefused() {
        // The other door into the same lie (external review). The exception path was closed
        // first, and queryView kept turning a NotFoundException — a design document that is
        // not there — into a null, which getChildren turned into an empty folder. Startup
        // stays lenient because provisioning legitimately runs before the views exist.
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        CloudantClientPool pool = mock(CloudantClientPool.class);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(pool.getClient(anyString())).thenReturn(client);
        when(client.queryView(anyString(), anyString(),
                org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any()))
                .thenReturn(null);
        dao.setConnectorPool(pool);

        // Not on a worker thread on purpose: getChildren's own guard is what must reject a
        // null view result, whatever the wrapper decided to do about startup.
        assertThrows(RuntimeException.class, () -> dao.getChildren("bedroom", "folder-1"),
                "an undeployed view was reported as a folder with no children");
    }
}
