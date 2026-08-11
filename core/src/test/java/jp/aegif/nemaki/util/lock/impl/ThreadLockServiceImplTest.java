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
package jp.aegif.nemaki.util.lock.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.lock.LockAcquisitionTimeoutException;
import jp.aegif.nemaki.util.lock.LockOrderMonitor;

/**
 * The failure paths of the bounded lock service — the paths that had never been executed.
 *
 * <h2>Why these tests inject tiny bounds</h2>
 *
 * <p>The whole point of this service is what happens when an acquisition CANNOT succeed: the
 * timeout that turns a deadlock into one failed request. At production values that path takes five
 * minutes to reach, so no test had ever reached it — the full suite's celebrated "zero exceptions"
 * also meant the throw, the release-between-retries, and the 503 mapping had never once run. A
 * safety net that has never caught anything is not known to be a net. The tuning constructor
 * exists so these tests can put the bounds in the tens of milliseconds and actually watch the net
 * catch.
 */
class ThreadLockServiceImplTest {

    /** Aggressive bounds: attempt 100ms / total 300ms / single 250ms / warn 50ms. */
    private ThreadLockServiceImpl fast;

    private final List<Thread> spawned = new ArrayList<>();

    @BeforeEach
    void setUp() {
        LockOrderMonitor.resetForTests();
        fast = new ThreadLockServiceImpl(100, 300, 250, 50);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Thread t : spawned) {
            t.interrupt();
            t.join(2_000);
        }
        LockOrderMonitor.resetForTests();
    }

    /** Runs {@code body} on a spawned thread that will be joined (not abandoned) at teardown. */
    private Thread spawn(Runnable body) {
        Thread t = new Thread(body, "lock-test-holder");
        spawned.add(t);
        t.start();
        return t;
    }

    /** Parks a write hold on {@code objectId} until the returned latch is counted down. */
    private CountDownLatch holdWriteElsewhere(ThreadLockServiceImpl svc, String objectId)
            throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        spawn(() -> {
            Lock lock = svc.getWriteLock("bedroom", objectId);
            lock.lock();
            try {
                acquired.countDown();
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        assertTrue(acquired.await(5, TimeUnit.SECONDS), "the holder thread should get the lock");
        return release;
    }

    @Test
    @DisplayName("単独 lock() はタイムアウトで LockAcquisitionTimeoutException を投げる (503 契約込み)")
    void aSingleLockTimesOutWithTheRetryableException() throws Exception {
        CountDownLatch release = holdWriteElsewhere(fast, "contended");
        try {
            Lock mine = fast.getWriteLock("bedroom", "contended");
            long start = System.nanoTime();
            LockAcquisitionTimeoutException thrown =
                    assertThrows(LockAcquisitionTimeoutException.class, mine::lock);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            // The 503 contract IS the fix: a plain RuntimeException reaches clients as a 500 they
            // will not retry. This class already drifted from its javadoc once (claimed to carry
            // the objects, did not), so both halves are pinned.
            assertTrue(thrown instanceof
                    org.apache.chemistry.opencmis.commons.exceptions.CmisServiceUnavailableException,
                    "the timeout must be the CMIS 503, or every binding maps it to a dead-end 500");
            assertEquals(List.of("bedroom/contended"), thrown.getObjects(),
                    "the failure must name the lock — a dump taken later cannot recover it");
            assertTrue(elapsedMs >= 200 && elapsedMs < 5_000,
                    "bounded by the configured deadline, not the production five minutes: "
                            + elapsedMs + "ms");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("bulkLock はタイムアウト時に部分取得を全解放してから投げる")
    void aBulkTimeoutReleasesEverythingItTook() throws Exception {
        // Roles assigned BY STRIPE ORDER, not by name. bulkLock acquires in stripe order, so only
        // "free sorts before contended" makes it actually HOLD the free lock while blocking on the
        // contended one; the other way round it blocks first, holds nothing, and the
        // release-everything assertion passes without testing any release. Deriving the roles
        // from the stripes (rather than hunting for an id that fits fixed roles) keeps this true
        // even if the hash ever changes.
        String[] pair = lowerAndHigherStripeIds(fast, "obj-a", "obj-b");
        String free = pair[0];
        String contended = pair[1];
        CountDownLatch release = holdWriteElsewhere(fast, contended);
        try {
            List<Lock> set = fast.orderedLocks("bedroom", List.of(free, contended), true);
            assertThrows(LockAcquisitionTimeoutException.class, () -> fast.bulkLock(set));

            // The release-between-retries is what breaks cycles: whoever gives up must leave
            // NOTHING behind, or the partial hold keeps the other side stuck and the "bounded"
            // failure quietly wasn't. The free lock must be immediately takeable by someone else.
            CountDownLatch gotFree = new CountDownLatch(1);
            spawn(() -> {
                Lock probe = fast.getWriteLock("bedroom", free);
                if (probe.tryLock()) {
                    gotFree.countDown();
                    probe.unlock();
                }
            });
            assertTrue(gotFree.await(5, TimeUnit.SECONDS),
                    "the non-contended lock must be free after the bulk failure — a leaked"
                            + " partial hold defeats the entire release-and-retry design");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("試行の締切は総締切で打ち切られる (300s が 330s にならない)")
    void anAttemptCannotOvershootTheTotalDeadline() throws Exception {
        // total (150ms) < attempt (10s): if attempts were not capped by the total, this would
        // wait ten seconds. The documented bound has to be the real bound — an operator sizing
        // client timeouts from the docs must not be lied to by a third again.
        ThreadLockServiceImpl svc = new ThreadLockServiceImpl(10_000, 150, 10_000, 50);
        CountDownLatch release = holdWriteElsewhere(svc, "contended");
        try {
            List<Lock> set = svc.orderedLocks("bedroom", List.of("contended"), true);
            long start = System.nanoTime();
            assertThrows(LockAcquisitionTimeoutException.class, () -> svc.bulkLock(set));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(elapsedMs < 5_000,
                    "the total deadline must cap the attempt: " + elapsedMs + "ms");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("orderedLocks はストライプ昇順を返す (入力順と無関係に同一列)")
    void orderedLocksReturnStripeAscendingOrder() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add("obj-" + i);
        }
        List<String> reversed = new ArrayList<>(ids);
        Collections.reverse(reversed);

        List<Integer> forward = stripesOf(fast.orderedLocks("bedroom", ids, false));
        List<Integer> backward = stripesOf(fast.orderedLocks("bedroom", reversed, false));

        // The ORDER is the deadlock guarantee, and it must be the STRIPE order specifically. An
        // id-lexicographic sort is deterministic, passes any size-only assertion, and was the
        // exact bug removed from moveObject: id order says nothing about which underlying locks
        // are taken first, so two sets can still take a shared stripe pair in opposite orders.
        List<Integer> sorted = new ArrayList<>(forward);
        Collections.sort(sorted);
        assertEquals(sorted, forward, "the sequence must be stripe-ascending");
        assertEquals(forward, backward,
                "the same set must produce the same SEQUENCE whatever order it arrives in —"
                        + " equal sizes are not enough, the acquisition order is the guarantee");
    }

    @Test
    @DisplayName("orderedLocks の write=true は本当に排他ロックを返す")
    void writeModeActuallyReturnsExclusiveLocks() throws Exception {
        // The probe MUST run on another thread: ReentrantReadWriteLock grants the write-holding
        // thread its own read lock (the downgrade idiom), so a same-thread probe would "pass"
        // against shared locks and exclusive locks alike — asserting nothing.
        List<Lock> writeSet = fast.orderedLocks("bedroom", List.of("obj-x"), true);
        fast.bulkLock(writeSet);
        try {
            // A mode mix-up would hand out SHARED locks here, and every mutual-exclusion caller
            // (moveObject, applyPolicy) would silently run concurrently — no exception, no test
            // failure, just lost updates found at data-inspection time.
            assertFalse(tryReadFromAnotherThread("obj-x"),
                    "a read must be excluded while the write-mode set holds the stripe");
        } finally {
            fast.bulkUnlock(writeSet);
        }

        List<Lock> readSet = fast.orderedLocks("bedroom", List.of("obj-x"), false);
        fast.bulkLock(readSet);
        try {
            assertTrue(tryReadFromAnotherThread("obj-x"), "read mode must remain shared");
        } finally {
            fast.bulkUnlock(readSet);
        }
    }

    @Test
    @DisplayName("__名前付き__ ロックはどのオブジェクトのストライプとも衝突しない")
    void aNamedLockCollidesWithNoObjectStripe() throws Exception {
        // The hierarchy lock is taken FIRST and held across an ordered set. That rule is only
        // safe if nobody else's ordered set contains the same underlying lock — which hashing
        // into 4096 shared stripes cannot guarantee (~1/4096 of all objects would BE this lock).
        // Named keys get a dedicated instance, so holding it must exclude no object whatsoever.
        Lock hierarchy = fast.getWriteLock("bedroom", "__folder-hierarchy__");
        hierarchy.lock();
        try {
            for (int i = 0; i < 64; i++) {
                Lock object = fast.getWriteLock("bedroom", "probe-" + i);
                assertTrue(object.tryLock(),
                        "object probe-" + i + " shares a lock with the named hierarchy key —"
                                + " the dedicated-instance guarantee is broken");
                object.unlock();
            }
            assertTrue(ThreadLockServiceImpl.stripeIdOf(hierarchy) >= 4096,
                    "the named lock's monitor identity must live outside the stripe range");
        } finally {
            hierarchy.unlock();
        }
    }

    /** Attempts a read tryLock on a DIFFERENT thread and reports whether it succeeded. */
    private boolean tryReadFromAnotherThread(String objectId) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean got = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            Lock probe = fast.getReadLock("bedroom", objectId);
            if (probe.tryLock()) {
                got.set(true);
                probe.unlock();
            }
        }, "read-probe");
        t.start();
        t.join(5_000);
        return got.get();
    }

    @Test
    @DisplayName("許可リストに無い __…__ id は専用ロックにならない (リクエスト由来の無制限確保を防ぐ)")
    void aNonAllowlistedNamedLookingKeyStaysStriped() {
        // Named-lock status was once decided by a NAME PATTERN. Object ids come from clients and
        // are locked BEFORE existence validation, so any request could mint a permanent
        // dedicated-lock entry by inventing __anything__ ids. The allowlist closed that — and
        // this is the assertion that would have caught it: a key that merely LOOKS internal must
        // hash into the fixed stripes like every other id.
        Lock attacker = fast.getWriteLock("bedroom", "__not-allowlisted__");
        assertTrue(ThreadLockServiceImpl.stripeIdOf(attacker) < 4096,
                "a non-allowlisted __…__ key must be striped, not given a dedicated lock");

        List<Lock> viaSet = fast.orderedLocks("bedroom", List.of("__also-not-allowlisted__"), true);
        assertEquals(1, viaSet.size());
        assertTrue(ThreadLockServiceImpl.stripeIdOf(viaSet.get(0)) < 4096,
                "orderedLocks must apply the same allowlist as get(); a gap in either one"
                        + " reopens the unbounded allocation");

        // The one real named key still gets its dedicated identity.
        assertTrue(ThreadLockServiceImpl.stripeIdOf(
                        fast.getWriteLock("bedroom", "__folder-hierarchy__")) >= 4096,
                "the allowlisted key must still be dedicated");
    }

    private static List<Integer> stripesOf(List<Lock> locks) {
        List<Integer> out = new ArrayList<>(locks.size());
        for (Lock lock : locks) {
            out.add(ThreadLockServiceImpl.stripeIdOf(lock));
        }
        return out;
    }

    /** Two ids on distinct stripes, returned as {lower-stripe, higher-stripe}. */
    private static String[] lowerAndHigherStripeIds(ThreadLockServiceImpl svc, String a, String b) {
        for (int i = 0; i < 10_000; i++) {
            String candidate = b + i;
            int sa = svc.stripeOf("bedroom", a);
            int sb = svc.stripeOf("bedroom", candidate);
            if (sa == sb) {
                continue;
            }
            return sa < sb ? new String[] { a, candidate } : new String[] { candidate, a };
        }
        throw new IllegalStateException("could not find two ids on distinct stripes");
    }
}
