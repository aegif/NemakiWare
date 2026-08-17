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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the order in which a thread takes object locks, and reports orders that can deadlock.
 *
 * <h2>Why this exists rather than a fix alone</h2>
 *
 * <p>On 2026-08-10 a live repository stopped answering: a thread dump showed seven threads parked
 * and none running. The dump gives the code lines but NOT the lock identities, so "these two
 * objects formed the cycle" was a hypothesis, not a finding — and the first hypothesis was wrong.
 * A fix aimed at the wrong pair would have left the repository just as capable of stopping.
 *
 * <p>Two things make guessing especially unsafe here. The locks are STRIPED (4096 stripes over
 * every object in every repository), so two objects with no relationship at all share a lock and
 * can form a cycle between them; an ordering rule expressed over the folder hierarchy would not
 * even address that. And {@code ReentrantReadWriteLock} cannot upgrade — a thread that holds a
 * read lock and asks for the write lock of the same stripe can never succeed, with no second
 * thread involved and no cycle to find in a dump.
 *
 * <p>So this records what actually happens. It reports two things:
 *
 * <ul>
 * <li><b>Upgrade</b>: the thread holds a stripe for reading — and NOT for writing — and asks to
 *     write it. The mode distinction matters: after a legal write→read downgrade the thread still
 *     owns the write lock, a further write request is reentrant and succeeds, and calling that an
 *     upgrade would be a false alarm. With the write hold absent the request is a certain
 *     self-deadlock, so {@link #beforeAcquire} returns {@code true} and the lock wrapper refuses
 *     it immediately instead of burning the acquisition bound while the queued writer blocks
 *     every later reader of the stripe.</li>
 * <li><b>Inversion</b>: some thread took stripe A then B, and some thread took B then A. Neither
 *     has to have deadlocked yet — this reports the possibility, which is the point. Waiting for
 *     the deadlock means waiting for a production outage to learn the same fact.</li>
 * </ul>
 *
 * <p>Edges are compared against the OLDEST held locks (the outer scopes), bounded at
 * {@link #MAX_TRACKED_DEPTH}: the live outage was precisely an outer hold against an inner set,
 * and the outer holds are the oldest entries. Acquisitions made while more than that many locks
 * are held are still upgrade-checked but only partially edge-checked; {@link #depthLimitedCount}
 * says how often that happened, so "zero inversions" can be read together with how much the
 * detector was allowed to see.
 *
 * <p>Findings are deduplicated by STRIPE pair, not by object pair — object pairs are unbounded
 * (a hot call site would mint a new finding per object combination and flood both the log and the
 * {@link #MAX_FINDINGS} cap), while stripe pairs are what the deadlock mechanics are actually
 * about. The first observed object pair is kept in the detail as an example. When the cap is
 * reached anyway, that fact is reported once at ERROR and exposed via {@link #findingsCapReached}
 * rather than silently swallowing later findings.
 *
 * <p>All state is per-JVM and since process start.
 */
public final class LockOrderMonitor {

    private static final Logger logger = LoggerFactory.getLogger(LockOrderMonitor.class);

    /**
     * How many of the oldest held locks a new acquisition is compared against.
     *
     * <p>Paged reads legitimately hold a lock per row; comparing a new acquisition against five
     * hundred inner-set holds is quadratic work for pairs that were all taken by one loop in one
     * order. The OLDEST holds are the outer scopes — the shape that took the repository down — so
     * the comparison window anchors there and truncation drops only inner-set-to-inner-set pairs.
     */
    private static final int MAX_TRACKED_DEPTH = 16;

    /** Cap on distinct reported findings, so a pathological workload cannot exhaust memory. */
    private static final int MAX_FINDINGS = 500;

    /** Cap on remembered edges. Beyond this, detection degrades rather than the JVM. */
    private static final int MAX_EDGES = 200_000;

    public static final String MODE_READ = "read";
    public static final String MODE_WRITE = "write";

    /** Per-thread: the held stack (order) plus per-stripe mode counts (O(1) reentrancy checks). */
    private static final class ThreadState {
        final Deque<Held> held = new ArrayDeque<>();
        /** stripe → {readHolds, writeHolds}. */
        final Map<Integer, int[]> modes = new HashMap<>();

        boolean isEmpty() {
            return held.isEmpty();
        }
    }

    private static final ThreadLocal<ThreadState> STATE = ThreadLocal.withInitial(ThreadState::new);

    /** Ordered stripe pairs already seen: "fromStripe>toStripe" → first example. */
    private static final Map<String, String> EDGES = new ConcurrentHashMap<>();

    /** Distinct findings keyed by kind + stripe pair, so each mechanism is reported once. */
    private static final Map<String, Finding> FINDINGS = new ConcurrentHashMap<>();

    private static final AtomicLong UPGRADES = new AtomicLong();
    private static final AtomicLong INVERSIONS = new AtomicLong();
    private static final AtomicLong DEPTH_LIMITED = new AtomicLong();
    private static final AtomicLong UNLOCKS_WITHOUT_HOLD = new AtomicLong();
    private static final AtomicBoolean CAP_REACHED = new AtomicBoolean();

    private LockOrderMonitor() {
    }

    /** One lock a thread currently holds. */
    private static final class Held {
        final int stripe;
        final String key;
        final String mode;

        Held(int stripe, String key, String mode) {
            this.stripe = stripe;
            this.key = key;
            this.mode = mode;
        }
    }

    /** A reportable ordering problem. */
    public static final class Finding {
        public final String kind;
        public final String first;
        public final String second;
        public final String detail;

        Finding(String kind, String first, String second, String detail) {
            this.kind = kind;
            this.first = first;
            this.second = second;
            this.detail = detail;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("first", first);
            m.put("second", second);
            m.put("detail", detail);
            return m;
        }
    }

    /**
     * Called just BEFORE a lock is acquired, so a finding is reported even when the acquisition
     * would never return.
     *
     * @return {@code true} when the acquisition is a CERTAIN self-deadlock — the thread holds the
     *         stripe for reading, does not hold it for writing, and is asking to write it. The
     *         caller must refuse the acquisition instead of attempting it: the attempt can only
     *         end at the timeout, and until then the queued writer stops every later reader of
     *         the stripe.
     */
    public static boolean beforeAcquire(int stripe, String key, String mode) {
        ThreadState st = STATE.get();
        if (st.isEmpty()) {
            return false;
        }
        int[] counts = st.modes.get(stripe);
        if (counts != null) {
            boolean readHeld = counts[0] > 0;
            boolean writeHeld = counts[1] > 0;
            if (MODE_WRITE.equals(mode) && readHeld && !writeHeld) {
                String heldKey = newestKeyOf(st, stripe);
                report("upgrade", heldKey, key, stripe, stripe,
                        "this thread holds stripe " + stripe + " for READING (" + heldKey
                                + ") and is asking to WRITE it (" + key + ")."
                                + " ReentrantReadWriteLock cannot upgrade, so the request can"
                                + " never succeed and is refused immediately"
                                + (heldKey != null && heldKey.equals(key) ? ""
                                        : " — note the two may be DIFFERENT objects that happen"
                                                + " to share a stripe"),
                        UPGRADES);
                return true;
            }
            // Reentrant on a held stripe: write-over-write is reentrant, and a read request from
            // a thread already holding the stripe (including the write→read downgrade) is always
            // grantable, so this acquisition cannot block — it cannot be the waiting end of a
            // cycle, and there is no edge worth recording.
            return false;
        }
        // A NEW stripe: compare against the OLDEST holds — the outer scopes. Bounded, and counted
        // when the bound truncates, so the coverage is honest.
        if (st.held.size() > MAX_TRACKED_DEPTH) {
            DEPTH_LIMITED.incrementAndGet();
        }
        int compared = 0;
        for (Iterator<Held> it = st.held.descendingIterator();
                it.hasNext() && compared < MAX_TRACKED_DEPTH; compared++) {
            Held h = it.next();
            String forward = h.stripe + ">" + stripe;
            String backward = stripe + ">" + h.stripe;
            String opposite = EDGES.get(backward);
            if (opposite != null) {
                report("inversion", h.key, key, h.stripe, stripe,
                        "stripe " + h.stripe + " then " + stripe + " here (" + h.key + " then "
                                + key + "), but " + stripe + " then " + h.stripe + " elsewhere ("
                                + opposite + "). Two threads interleaving these can wait on each"
                                + " other; the acquisition bounds turn that into a failed request"
                                + " rather than a stopped repository, but until the bound expires"
                                + " both stripes are degraded",
                        INVERSIONS);
            }
            if (EDGES.size() < MAX_EDGES) {
                EDGES.putIfAbsent(forward, h.key + " then " + key);
            }
        }
        return false;
    }

    private static String newestKeyOf(ThreadState st, int stripe) {
        for (Held h : st.held) {
            if (h.stripe == stripe) {
                return h.key;
            }
        }
        return null;
    }

    /** Called after a successful acquisition. */
    public static void acquired(int stripe, String key, String mode) {
        ThreadState st = STATE.get();
        st.held.push(new Held(stripe, key, mode));
        int[] counts = st.modes.computeIfAbsent(stripe, s -> new int[2]);
        counts[MODE_WRITE.equals(mode) ? 1 : 0]++;
    }

    /**
     * Called on release. Removes the newest entry matching BOTH stripe and mode.
     *
     * <p>The mode matters for the downgrade pattern: holding a stripe as write and read, a
     * mode-blind removal that happened to take the read entry would leave a phantom write hold in
     * the accounting — which is exactly the state that suppresses a real upgrade report later.
     */
    public static void released(int stripe, String mode) {
        ThreadState st = STATE.get();
        boolean removed = false;
        for (Iterator<Held> it = st.held.iterator(); it.hasNext();) {
            Held h = it.next();
            if (h.stripe == stripe && h.mode.equals(mode)) {
                it.remove();
                removed = true;
                break;
            }
        }
        if (removed) {
            int[] counts = st.modes.get(stripe);
            if (counts != null) {
                int idx = MODE_WRITE.equals(mode) ? 1 : 0;
                if (counts[idx] > 0) {
                    counts[idx]--;
                }
                if (counts[0] == 0 && counts[1] == 0) {
                    st.modes.remove(stripe);
                }
            }
        }
        if (st.isEmpty()) {
            // A request thread is pooled and reused; leaving an empty structure attached to it is
            // a slow leak across thousands of threads.
            STATE.remove();
        }
    }

    /**
     * Records an {@code unlock()} that had nothing to release.
     *
     * <p>Nonzero is NORMAL after failed acquisitions — every caller's finally-block runs whether
     * the lock was taken or not, and tolerating that is why acquisition failures keep their real
     * message. What this counter catches is the other cause: an unlock through a DIFFERENT
     * wrapper than the one that locked, which under the previous implementation released the lock
     * and now silently leaks it. Growth of this counter with no acquisition timeouts anywhere
     * near it is that bug's only visible symptom.
     */
    public static void unlockWithoutHold() {
        UNLOCKS_WITHOUT_HOLD.incrementAndGet();
    }

    private static void report(String kind, String first, String second, int stripeA, int stripeB,
            String detail, AtomicLong counter) {
        counter.incrementAndGet();
        // Keyed by stripe pair: object pairs are unbounded and would both flood the log (a hot
        // site mints a new pair per object combination) and eat the findings cap with duplicates
        // of one mechanism.
        String id = kind + "|" + Math.min(stripeA, stripeB) + "|" + Math.max(stripeA, stripeB);
        if (FINDINGS.size() >= MAX_FINDINGS) {
            if (CAP_REACHED.compareAndSet(false, true)) {
                logger.error("Lock-order findings cap ({}) reached — further distinct findings"
                        + " will be counted but not detailed. Read and address the existing ones;"
                        + " counters keep moving so convergence is still measurable.", MAX_FINDINGS);
            }
            return;
        }
        if (FINDINGS.putIfAbsent(id, new Finding(kind, first, second, detail)) != null) {
            return; // this stripe pair is already reported
        }
        // WARN, not ERROR: nothing has failed yet for an inversion, and the operator's action is
        // to report it rather than to page someone. The stack is what identifies the two call
        // sites, and no dump taken afterwards can recover it.
        logger.warn("LOCK ORDER {} — {}", kind, detail, new Throwable("lock order finding"));
    }

    public static long upgradeCount() {
        return UPGRADES.get();
    }

    public static long inversionCount() {
        return INVERSIONS.get();
    }

    /** How many acquisitions were only partially edge-checked because too much was held. */
    public static long depthLimitedCount() {
        return DEPTH_LIMITED.get();
    }

    public static long unlockWithoutHoldCount() {
        return UNLOCKS_WITHOUT_HOLD.get();
    }

    public static boolean findingsCapReached() {
        return CAP_REACHED.get();
    }

    public static List<Map<String, Object>> findings() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Finding f : FINDINGS.values()) {
            out.add(f.toMap());
        }
        return out;
    }

    /** Test hook. */
    public static void resetForTests() {
        EDGES.clear();
        FINDINGS.clear();
        UPGRADES.set(0);
        INVERSIONS.set(0);
        DEPTH_LIMITED.set(0);
        UNLOCKS_WITHOUT_HOLD.set(0);
        CAP_REACHED.set(false);
        STATE.remove();
    }
}
