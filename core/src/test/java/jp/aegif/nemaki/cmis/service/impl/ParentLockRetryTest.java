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
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.lock.impl.ThreadLockServiceImpl;

/**
 * A parent read that overlaps a move must answer with the NEW placement, not fail.
 *
 * <h2>The window this closes</h2>
 *
 * <p>{@code getFolderParent} / {@code getObjectParents} lock the object and its parent as one
 * ordered set, which requires knowing the parent BEFORE locking. A request that arrives while a
 * move holds the object's lock previews the old parent, waits, and on waking finds the parent
 * changed. The first version of the guard answered that with a deterministic 409 — turning "a
 * read that politely waited for a move" into an error the old (inversion-prone) code did not
 * produce. The retry re-previews and relocks, so the common case converges to the correct new
 * answer and only a sustained move loop ends in the conflict, which by then is describing
 * reality.
 */
class ParentLockRetryTest {

    private NavigationServiceImpl service;
    private ContentService contentService;
    private ThreadLockServiceImpl lockService;

    @BeforeEach
    void setUp() {
        service = new NavigationServiceImpl();
        contentService = mock(ContentService.class);
        lockService = new ThreadLockServiceImpl();
        service.setContentService(contentService);
        service.setThreadLockService(lockService);
    }

    private static Folder folder(String id) {
        Folder f = mock(Folder.class);
        when(f.getId()).thenReturn(id);
        return f;
    }

    @Test
    @DisplayName("ロック待ちの間に move が完了していたら、再プレビューして新しい親を返す")
    void aMoveThatLandedWhileWaitingConvergesToTheNewParent() {
        Folder oldParent = folder("parent-old");
        Folder newParent = folder("parent-new");
        // Preview sees the old parent; the locked re-read sees the new one (the move committed in
        // between); the second preview and re-read agree on the new one.
        when(contentService.getParent(eq("bedroom"), eq("child")))
                .thenReturn(oldParent, newParent, newParent, newParent);

        NavigationServiceImpl.ParentLockHold hold =
                service.lockObjectAndCurrentParent("bedroom", "child");
        try {
            assertEquals("parent-new", hold.parent().getId(),
                    "the answer must be the placement whose locks are actually held — and that is"
                            + " one re-preview away, not a 409");
            assertFalse(hold.locks().isEmpty());
        } finally {
            lockService.bulkUnlock(hold.locks());
        }
    }

    @Test
    @DisplayName("move が続く限り一致しない場合は有界に諦めて 409")
    void aSustainedMoveLoopEndsInAConflict() {
        AtomicInteger n = new AtomicInteger();
        // Every read returns a different parent: preview and locked re-read can never agree.
        when(contentService.getParent(eq("bedroom"), eq("child")))
                .thenAnswer(inv -> folder("parent-" + n.incrementAndGet()));

        assertThrows(CmisUpdateConflictException.class,
                () -> service.lockObjectAndCurrentParent("bedroom", "child"),
                "unbounded retries against a move loop would be a livelock; the conflict is the"
                        + " honest bounded answer");
    }

    @Test
    @DisplayName("親が無い (root / unfiled) なら null 親のまま整合して返る")
    void aParentlessObjectIsHeldWithANullParent() {
        when(contentService.getParent(eq("bedroom"), eq("root"))).thenReturn(null);

        NavigationServiceImpl.ParentLockHold hold =
                service.lockObjectAndCurrentParent("bedroom", "root");
        try {
            assertNull(hold.parent());
            assertEquals(1, hold.locks().size(), "only the object itself is locked");
        } finally {
            lockService.bulkUnlock(hold.locks());
        }
    }

    @Test
    @DisplayName("プレビュー後に親が消えた場合も再プレビューで収束する (null へ)")
    void aParentThatDisappearedConvergesToTheParentlessAnswer() {
        Folder oldParent = folder("parent-old");
        // Preview sees a parent; under the locks it is gone (deleted / unfiled mid-flight); the
        // retry previews null and the locked re-read agrees.
        when(contentService.getParent(eq("bedroom"), eq("child")))
                .thenReturn(oldParent, (Folder) null, (Folder) null);

        NavigationServiceImpl.ParentLockHold hold =
                service.lockObjectAndCurrentParent("bedroom", "child");
        try {
            assertNull(hold.parent(),
                    "the locked truth is 'no parent'; answering with the stale preview would"
                            + " compile an object whose lock was never taken");
        } finally {
            lockService.bulkUnlock(hold.locks());
        }
    }

    @Test
    @DisplayName("ロック取得後の再読で例外が出てもロックはリークしない")
    void anExceptionDuringTheLockedRereadReleasesTheLocks() {
        Folder parent = folder("parent");
        // Preview succeeds; the locked re-read throws (a transient CouchDB failure). Without the
        // release, the child's and parent's READ holds would stay attached to an unwound thread
        // forever — one backend hiccup becoming indefinite write failure on those stripes, which
        // is precisely the unbounded outcome this whole area exists to remove.
        when(contentService.getParent(eq("bedroom"), eq("child")))
                .thenReturn(parent)
                .thenThrow(new IllegalStateException("couchdb hiccup"));

        assertThrows(IllegalStateException.class,
                () -> service.lockObjectAndCurrentParent("bedroom", "child"),
                "the backend failure itself must propagate, not be swallowed");

        List<Lock> writeSet = lockService.orderedLocks("bedroom",
                List.of("child", "parent"), true);
        for (Lock l : writeSet) {
            assertTrue(l.tryLock(),
                    "a read hold leaked through the exception path — writes to this stripe would"
                            + " now fail until restart");
        }
        for (int i = writeSet.size() - 1; i >= 0; i--) {
            writeSet.get(i).unlock();
        }
    }

    @Test
    @DisplayName("収束した hold の locks は解放後に他者が取得できる")
    void heldLocksAreReleasable() {
        Folder parent = folder("parent");
        when(contentService.getParent(eq("bedroom"), eq("child"))).thenReturn(parent);

        NavigationServiceImpl.ParentLockHold hold =
                service.lockObjectAndCurrentParent("bedroom", "child");
        lockService.bulkUnlock(hold.locks());

        // After release, a writer can take both — proves nothing leaked. The retry loop unlocks
        // between attempts too, so a leak here would say the loop's bookkeeping is wrong.
        List<Lock> writeSet = lockService.orderedLocks("bedroom",
                List.of("child", "parent"), true);
        for (Lock l : writeSet) {
            assertTrue(l.tryLock(), "a lock leaked from the released hold");
        }
        for (int i = writeSet.size() - 1; i >= 0; i--) {
            writeSet.get(i).unlock();
        }
    }
}
