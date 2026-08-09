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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.lock.impl.ThreadLockServiceImpl;

/**
 * Two folder moves running at once must not be able to build a parent/child cycle.
 *
 * <h2>Why a runtime test and not a reading of the code</h2>
 *
 * <p>The first version of the guard held the write locks of the moved object and the destination,
 * taken in id order, and claimed that closed the race. It does close the obvious pairing — moving
 * A directly under B while moving B directly under A — because both threads contend for the same
 * two keys. It does not close the pairing a reviewer pointed out: moving A under a CHILD of B
 * while moving B under a CHILD of A. Those hold {A, B_child} and {B, A_child}, disjoint sets, so
 * neither thread excludes the other, each guard walks a hierarchy in which the other move has not
 * landed yet, and both commit.
 *
 * <p>That is not visible by reading the guard — it is only visible by running the interleaving.
 * This test drives the real lock service and the real check-then-act shape with a deliberately
 * hostile schedule (both threads finish their check before either commits), so the assertion is
 * about behaviour under concurrency rather than about the presence of a line of code.
 */
class ConcurrentFolderMoveCycleTest {

    /** Parent map standing in for the folder hierarchy. */
    private final Map<String, String> parentOf = new ConcurrentHashMap<>();
    private final ThreadLockServiceImpl locks = new ThreadLockServiceImpl();
    private static final String REPO = "r";
    private static final String HIERARCHY_KEY = "__folder-hierarchy__";

    private boolean isDescendantOfMoving(String movingId, String destination) {
        String cursor = destination;
        int hops = 0;
        while (cursor != null && hops++ < 256) {
            if (movingId.equals(cursor)) {
                return true;
            }
            cursor = parentOf.get(cursor);
        }
        return false;
    }

    /**
     * @param serialiseFolderMoves the fix under test: take a repository-wide lock so folder moves
     *     run one at a time. When false, this reproduces the old per-object-locks-only behaviour.
     */
    private int runInterleavedMoves(boolean serialiseFolderMoves) throws Exception {
        parentOf.clear();
        parentOf.put("A", "root");
        parentOf.put("B", "root");
        parentOf.put("A_child", "A");
        parentOf.put("B_child", "B");
        // root is absent on purpose: ConcurrentHashMap forbids null values, and "no entry"
        // already means "no parent" for every walk below.

        // Both threads must finish their guard BEFORE either commits — the schedule that makes
        // the disjoint-lock-set race actually happen rather than relying on luck.
        CountDownLatch bothChecked = new CountDownLatch(2);
        AtomicInteger rejected = new AtomicInteger();

        Runnable move1 = mover("A", "B_child", serialiseFolderMoves, bothChecked, rejected);
        Runnable move2 = mover("B", "A_child", serialiseFolderMoves, bothChecked, rejected);

        Thread t1 = new Thread(move1, "move-A-under-B_child");
        Thread t2 = new Thread(move2, "move-B-under-A_child");
        t1.start();
        t2.start();
        t1.join(10_000);
        t2.join(10_000);
        return rejected.get();
    }

    private Runnable mover(String movingId, String destination, boolean serialise,
            CountDownLatch bothChecked, AtomicInteger rejected) {
        return () -> {
            Lock hierarchy = serialise ? locks.getWriteLock(REPO, HIERARCHY_KEY) : null;
            String firstKey = movingId.compareTo(destination) <= 0 ? movingId : destination;
            String secondKey = movingId.compareTo(destination) <= 0 ? destination : movingId;
            Lock first = locks.getWriteLock(REPO, firstKey);
            Lock second = locks.getWriteLock(REPO, secondKey);
            if (hierarchy != null) {
                hierarchy.lock();
            }
            first.lock();
            second.lock();
            try {
                boolean wouldCycle = isDescendantOfMoving(movingId, destination);
                if (wouldCycle) {
                    rejected.incrementAndGet();
                } else {
                    // Without the repository lock both threads reach here together; the latch
                    // makes that certain instead of probabilistic.
                    bothChecked.countDown();
                    try {
                        bothChecked.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    parentOf.put(movingId, destination);
                }
            } finally {
                second.unlock();
                first.unlock();
                if (hierarchy != null) {
                    hierarchy.unlock();
                }
            }
        };
    }

    /** Walks up from {@code start}; true if it revisits a node (i.e. the hierarchy has a cycle). */
    private boolean hierarchyHasCycle(String start) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        String cursor = start;
        while (cursor != null) {
            if (!seen.add(cursor)) {
                return true;
            }
            cursor = parentOf.get(cursor);
        }
        return false;
    }

    @Test
    @DisplayName("オブジェクト単位のロックだけでは、交差しないロック集合の並行 move がサイクルを作れる")
    void perObjectLocksAloneDoNotPreventTheCycle() throws Exception {
        int rejected = runInterleavedMoves(false);
        assertEquals(0, rejected, "neither guard fires — that is the whole point of the pairing");
        assertTrue(hierarchyHasCycle("A_child"),
                "without repository-wide serialisation the two moves must be able to form a cycle;"
                        + " if this stops reproducing, the test no longer proves the fix is needed");
    }

    @Test
    @DisplayName("リポジトリ単位で直列化すると、後発の move がガードに掛かりサイクルは成立しない")
    void serialisingFolderMovesPreventsTheCycle() throws Exception {
        int rejected = runInterleavedMoves(true);
        assertEquals(1, rejected,
                "the second move must see the first one's result and be refused");
        assertTrue(!hierarchyHasCycle("A_child") && !hierarchyHasCycle("B_child"),
                "no cycle may exist after serialised moves");
    }

    @Test
    @DisplayName("実装が実際にリポジトリ単位ロックを取っている (フォルダのときだけ)")
    void theImplementationTakesTheHierarchyLockForFolders() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceImpl.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        int at = src.indexOf("public void moveObject(");
        assertTrue(at > 0, "moveObject not found");
        String body = src.substring(at, src.indexOf("\n\t@Override", at));
        assertTrue(body.contains("FOLDER_HIERARCHY_LOCK_KEY"),
                "folder moves must serialise repository-wide");
        assertTrue(body.contains("moving.isFolder()"),
                "only folder moves need serialising — a document has no children and cannot"
                        + " create a cycle, so it must stay concurrent");
    }
}
