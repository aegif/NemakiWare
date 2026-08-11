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
package jp.aegif.nemaki.util.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.lock.impl.ThreadLockServiceImpl;

// (LockAcquisitionTimeoutException is in this package)

/**
 * Finding an unsafe lock order before it stops the repository.
 *
 * <h2>What went wrong, and why a detector rather than a guess</h2>
 *
 * <p>A live repository stopped answering on 2026-08-10: seven threads parked, none running, one
 * waiting for a write lock inside a delete and one waiting for a read lock inside a navigation
 * call. A thread dump names the LINES but not the OBJECTS, and these locks are STRIPED — 4096 of
 * them for every object in every repository — so the two ends of a cycle need not be related in
 * any way a reader of the code could predict. Choosing a fix from the code alone means fixing a
 * hypothesis, and the first hypothesis here turned out to be wrong.
 *
 * <p>The failures below are what the detector has to catch, and what the lock service has to
 * survive. An upgrade never succeeds: {@code ReentrantReadWriteLock} refuses to turn a read hold
 * into a write hold. An inversion is a possibility rather than an event — which is exactly why it
 * is worth reporting, since the alternative is waiting for the outage that proves it.
 */
class LockOrderMonitorTest {

    private ThreadLockServiceImpl service;

    @BeforeEach
    void setUp() {
        LockOrderMonitor.resetForTests();
        service = new ThreadLockServiceImpl();
    }

    @Test
    @DisplayName("read を持ったまま write を取ろうとしたら即時に拒否される (自己デッドロック)")
    void aReadHoldFollowedByAWriteRequestIsRefusedImmediately() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong elapsedMs = new java.util.concurrent.atomic.AtomicLong(-1);

        // Both acquisitions on ONE thread: that is what an upgrade is. Two threads contending for
        // the same stripe is ordinary and resolves; one thread asking to write what it already
        // reads never succeeds, because ReentrantReadWriteLock has no upgrade path. Waiting out
        // the bound would stall every later reader of the stripe behind the queued writer for the
        // whole bound — for an outcome known before the wait began. So it must fail NOW.
        Thread t = new Thread(() -> {
            Lock read = service.getReadLock("bedroom", "obj-a");
            Lock write = service.getWriteLock("bedroom", "obj-a");
            read.lock();
            long start = System.nanoTime();
            try {
                write.lock();
            } catch (Throwable thrown) {
                outcome.set(thrown);
            } finally {
                elapsedMs.set((System.nanoTime() - start) / 1_000_000L);
                read.unlock();
            }
        }, "upgrade-attempt");
        t.setDaemon(true); // must not pin the suite for the full bound if fast-fail regresses
        t.start();
        t.join(10_000);
        assertFalse(t.isAlive(),
                "the refusal is immediate; a thread still alive here means the acquisition is"
                        + " WAITING, i.e. fast-fail has regressed to waiting out the bound");

        assertTrue(outcome.get() instanceof LockAcquisitionTimeoutException,
                "the certain self-deadlock must be refused with the retryable lock exception,"
                        + " got: " + outcome.get());
        assertTrue(elapsedMs.get() >= 0 && elapsedMs.get() < 5_000,
                "refused IMMEDIATELY — burning the acquisition bound on an outcome known in"
                        + " advance stalls the whole stripe: " + elapsedMs.get() + "ms");
        assertEquals(1, LockOrderMonitor.upgradeCount(), "and reported as an upgrade");
    }

    @Test
    @DisplayName("write→read→write の合法な降格パターンは upgrade と誤報しない")
    void aLegalDowngradePatternIsNotReportedAsAnUpgrade() {
        // After WRITE then READ of the same stripe (the documented RRWL downgrade idiom), the
        // thread still owns the write lock, so a further write request is reentrant and MUST
        // succeed. A monitor that only looked at the newest same-stripe entry would see the read
        // and cry upgrade — and with the refusal above, that false alarm would turn a legal
        // acquisition into a spurious failure.
        Lock write = service.getWriteLock("bedroom", "obj-a");
        Lock read = service.getReadLock("bedroom", "obj-a");
        Lock writeAgain = service.getWriteLock("bedroom", "obj-a");
        write.lock();
        read.lock();
        writeAgain.lock();
        writeAgain.unlock();
        read.unlock();
        write.unlock();

        assertEquals(0, LockOrderMonitor.upgradeCount(),
                "a reentrant write under an existing write hold is legal, whatever reads sit"
                        + " between them");
    }

    @Test
    @DisplayName("mode を無視した解放で write の会計が残らない (非 LIFO 解放)")
    void releasingTheWriteHoldFirstKeepsTheAccountingRight() throws Exception {
        // Hold WRITE and READ of one stripe, release the WRITE first (non-LIFO). A mode-blind
        // removal would take the read entry and leave a phantom write hold — which then hides a
        // REAL upgrade: with only the read actually held, a write request truly can never
        // succeed, and the monitor must still say so.
        java.util.concurrent.atomic.AtomicReference<Throwable> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            Lock write = service.getWriteLock("bedroom", "obj-a");
            Lock read = service.getReadLock("bedroom", "obj-a");
            write.lock();
            read.lock();
            write.unlock(); // non-LIFO: the write goes first, the read stays
            Lock writeAgain = service.getWriteLock("bedroom", "obj-a");
            try {
                writeAgain.lock();
            } catch (Throwable thrown) {
                outcome.set(thrown);
            } finally {
                read.unlock();
            }
        }, "downgrade-then-upgrade");
        t.setDaemon(true);
        t.start();
        t.join(10_000);
        assertFalse(t.isAlive(), "the refusal is immediate — see the upgrade test");

        assertTrue(outcome.get() instanceof LockAcquisitionTimeoutException,
                "with only the read left held this IS a certain self-deadlock and must be"
                        + " refused — a phantom write entry from mode-blind release accounting"
                        + " would have suppressed it: " + outcome.get());
        assertEquals(1, LockOrderMonitor.upgradeCount());
    }

    @Test
    @DisplayName("別スレッドが逆順で取ったら (AB-BA) 検出する")
    void twoThreadsTakingTheSamePairInOppositeOrdersAreReported() throws Exception {
        // Ids chosen at runtime so the two really are different stripes; a collision would make
        // the acquisitions reentrant and there would be nothing to invert.
        String a = "obj-a";
        String b = distinctStripeId(a);

        CountDownLatch done = new CountDownLatch(1);
        Thread forward = new Thread(() -> {
            Lock la = service.getReadLock("bedroom", a);
            Lock lb = service.getReadLock("bedroom", b);
            la.lock();
            lb.lock();
            lb.unlock();
            la.unlock();
            done.countDown();
        }, "a-then-b");
        forward.setDaemon(true);
        forward.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "the forward order should not block");

        // The same pair, the other way round. Nothing deadlocks here — read locks are shared — but
        // the ORDER is what makes a deadlock possible once a writer queues between them.
        Lock lb = service.getReadLock("bedroom", b);
        Lock la = service.getReadLock("bedroom", a);
        lb.lock();
        la.lock();
        la.unlock();
        lb.unlock();

        assertEquals(1, LockOrderMonitor.inversionCount(),
                "taking the same two stripes in opposite orders must be reported even though"
                        + " neither thread has hung yet: the report IS the early warning");
        assertTrue(LockOrderMonitor.findings().stream()
                        .anyMatch(f -> "inversion".equals(f.get("kind"))),
                "the finding must name the pair, since a dump taken later cannot recover it");
    }

    @Test
    @DisplayName("非隣接のロック順 (A→B→C を保持した状態の A→C) も検出する")
    void anEdgeBetweenNonAdjacentHeldLocksIsDetected() {
        String a = "obj-a";
        String b = distinctStripeId(a);
        String c = thirdStripeId(a, b);

        // Establish C-before-A somewhere.
        Lock lc = service.getReadLock("bedroom", c);
        Lock la0 = service.getReadLock("bedroom", a);
        lc.lock();
        la0.lock();
        la0.unlock();
        lc.unlock();

        long before = LockOrderMonitor.inversionCount();

        // Now A, then B, then C on one thread. A model that compared only the most recently held
        // lock would see B→C and record nothing about A→C — and A→C is the pair that inverts.
        // That shape, an outer lock held across an inner ordered set, is what the live outage was.
        Lock la = service.getReadLock("bedroom", a);
        Lock lb = service.getReadLock("bedroom", b);
        Lock lc2 = service.getReadLock("bedroom", c);
        la.lock();
        lb.lock();
        lc2.lock();
        lc2.unlock();
        lb.unlock();
        la.unlock();

        assertTrue(LockOrderMonitor.inversionCount() > before,
                "a head-only edge model cannot see this, so it could not be used to claim which"
                        + " sites exist — only which sites it happens to be able to notice");
    }

    @Test
    @DisplayName("同一スレッドの素直な入れ子は誤検出しない")
    void anOrdinaryNestedHoldIsNotReported() {
        String a = "obj-a";
        String b = distinctStripeId(a);
        Lock la = service.getWriteLock("bedroom", a);
        Lock lb = service.getWriteLock("bedroom", b);
        la.lock();
        lb.lock();
        lb.unlock();
        la.unlock();

        assertEquals(0, LockOrderMonitor.inversionCount(), "one consistent order is not a finding");
        assertEquals(0, LockOrderMonitor.upgradeCount());
    }

    @Test
    @DisplayName("orderedLocks は同一ストライプを 1 つに畳み、入力順に依存しない")
    void orderedLocksCollapseDuplicatesAndIgnoreInputOrder() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add("obj-" + i);
        }
        int distinctStripes = (int) stripes(ids).stream().distinct().count();

        List<Lock> locks = service.orderedLocks("bedroom", ids, false);
        assertEquals(distinctStripes, locks.size(),
                "one lock per STRIPE, not per id: two ids sharing a stripe are one lock, and"
                        + " taking it twice would leave a hold that bulkUnlock never releases");

        List<String> reversed = new ArrayList<>(ids);
        Collections.reverse(reversed);
        assertEquals(locks.size(), service.orderedLocks("bedroom", reversed, false).size(),
                "the same set must produce the same locks whatever order it arrives in");

        List<String> withDuplicates = new ArrayList<>(ids);
        withDuplicates.addAll(ids);
        assertEquals(locks.size(), service.orderedLocks("bedroom", withDuplicates, false).size(),
                "repeated ids must collapse too");
    }

    @Test
    @DisplayName("ページ読みを逆順で走らせても検出が出ない (orderedLocks が揃えるため)")
    void twoPagesReadInOppositeOrdersNoLongerInvert() {
        List<String> page = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            page.add("row-" + i);
        }
        List<String> reversed = new ArrayList<>(page);
        Collections.reverse(reversed);

        runLocked(service.orderedLocks("bedroom", page, false));
        runLocked(service.orderedLocks("bedroom", reversed, false));

        assertEquals(0, LockOrderMonitor.inversionCount(),
                "this is the whole reason ordering happens inside the lock service rather than at"
                        + " the thirty-odd call sites: the callers pass whatever order their data"
                        + " arrived in, and Solr does not promise one");
    }

    @Test
    @DisplayName("取得に失敗した後の finally の unlock が本当の失敗を握り潰さない")
    void aFinallyBlockAfterAFailedAcquisitionDoesNotMaskTheRealFailure() {
        // Callers universally write lock(); try { ... } finally { unlock(); }. When the
        // acquisition fails, that finally still runs. Unlocking something never taken throws
        // IllegalMonitorStateException, which would replace the timeout with a meaningless error
        // and lose the reason — the diagnosis this whole area exists to produce.
        Lock never = service.getReadLock("bedroom", "obj-never-taken");
        never.unlock();
        never.unlock();

        List<Lock> set = service.orderedLocks("bedroom", List.of("x", "y"), false);
        service.bulkUnlock(set);

        // Reaching here at all is the assertion: nothing threw.
        assertEquals(0, LockOrderMonitor.upgradeCount());
    }

    private void runLocked(List<Lock> locks) {
        service.bulkLock(locks);
        service.bulkUnlock(locks);
    }

    private List<Integer> stripes(List<String> ids) {
        List<Integer> out = new ArrayList<>();
        for (String id : ids) {
            out.add(service.stripeOf("bedroom", id));
        }
        return out;
    }

    /** An id whose stripe differs from {@code other}'s, so the two are genuinely separate locks. */
    private String distinctStripeId(String other) {
        int target = service.stripeOf("bedroom", other);
        for (int i = 0; i < 10_000; i++) {
            String candidate = "obj-b" + i;
            if (service.stripeOf("bedroom", candidate) != target) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an id on a different stripe");
    }

    private String thirdStripeId(String x, String y) {
        int sx = service.stripeOf("bedroom", x);
        int sy = service.stripeOf("bedroom", y);
        for (int i = 0; i < 10_000; i++) {
            String candidate = "obj-c" + i;
            int s = service.stripeOf("bedroom", candidate);
            if (s != sx && s != sy) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find a third stripe");
    }
}
