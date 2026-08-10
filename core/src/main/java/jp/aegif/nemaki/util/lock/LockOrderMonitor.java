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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the order in which a thread takes object locks, and reports orders that can deadlock.
 *
 * <h2>Why this exists rather than a fix alone</h2>
 *
 * <p>On 2026-08-10 a live repository stopped answering: a thread dump showed seven threads parked
 * and none running, with a {@code deleteObjectInternal} waiting for a write lock and a
 * {@code getFolderParent} waiting for a read lock. The dump gives the code lines but NOT the lock
 * identities, so "these two objects formed the cycle" was a hypothesis, not a finding — and a fix
 * aimed at the wrong pair would leave the repository just as capable of stopping.
 *
 * <p>Two things make guessing especially unsafe here. The locks are STRIPED (4096 stripes over
 * every object in every repository), so two objects with no relationship at all share a lock and
 * can form a cycle between them; an ordering rule expressed over the folder hierarchy would not
 * even address that. And {@code ReentrantReadWriteLock} cannot upgrade — a thread that holds a
 * read lock and asks for the write lock of the same stripe deadlocks against itself, with no
 * second thread involved and no cycle to find in a dump.
 *
 * <p>So this records what actually happens. It reports two things:
 *
 * <ul>
 * <li><b>Upgrade</b>: the same thread holds a stripe for reading and asks to write it. This is a
 *     certain, immediate, permanent self-deadlock; there is nothing probabilistic about it.</li>
 * <li><b>Inversion</b>: some thread took stripe A then B, and some thread took B then A. Neither
 *     has to have deadlocked yet — this reports the possibility, which is the point. Waiting for
 *     the deadlock means waiting for a production outage to learn the same fact.</li>
 * </ul>
 *
 * <p>Findings are reported once per distinct pair (a hot pair would otherwise flood the log) and
 * are readable from the admin metrics endpoint.
 */
public final class LockOrderMonitor {

    private static final Logger logger = LoggerFactory.getLogger(LockOrderMonitor.class);

    /**
     * Beyond this many locks held at once, edges stop being recorded.
     *
     * <p>Paged reads legitimately hold a lock per row, and recording every pair of a 100-row page
     * is 10,000 comparisons for information nobody wants: those are all taken in one loop, in one
     * order, and the interesting question is which ORDER that loop uses, which the first few
     * entries already answer. The upgrade check is NOT subject to this cap — it is cheap and its
     * finding is always real.
     */
    private static final int MAX_TRACKED_DEPTH = 16;

    /** Cap on distinct reported findings, so a pathological workload cannot exhaust memory. */
    private static final int MAX_FINDINGS = 500;

    /** Cap on remembered edges. Beyond this, detection degrades rather than the JVM. */
    private static final int MAX_EDGES = 200_000;

    public static final String MODE_READ = "read";
    public static final String MODE_WRITE = "write";

    private static final ThreadLocal<Deque<Held>> HELD = ThreadLocal.withInitial(ArrayDeque::new);

    /** Ordered pairs already seen: "fromStripe>toStripe". */
    private static final Map<String, String> EDGES = new ConcurrentHashMap<>();

    /** Distinct findings, newest last, keyed so each pair is reported once. */
    private static final Map<String, Finding> FINDINGS = new ConcurrentHashMap<>();

    private static final AtomicLong UPGRADES = new AtomicLong();
    private static final AtomicLong INVERSIONS = new AtomicLong();

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
     * never returns — which, for the upgrade case, is exactly what happens.
     */
    public static void beforeAcquire(int stripe, String key, String mode) {
        Deque<Held> held = HELD.get();
        if (held.isEmpty()) {
            return;
        }
        for (Held h : held) {
            if (h.stripe == stripe) {
                if (MODE_WRITE.equals(mode) && MODE_READ.equals(h.mode)) {
                    report("upgrade", h.key, key,
                            "this thread holds stripe " + stripe + " for READING (" + h.key
                                    + ") and is asking to WRITE it (" + key + ")."
                                    + " ReentrantReadWriteLock cannot upgrade, so this thread is"
                                    + " now parked forever and every later request for that stripe"
                                    + " queues behind it"
                                    + (h.key.equals(key) ? "" : " — note the two are DIFFERENT"
                                            + " objects that happen to share a stripe"),
                            UPGRADES);
                }
                // Same stripe, compatible modes: reentrant, and no edge to record.
                return;
            }
        }
        if (held.size() >= MAX_TRACKED_DEPTH) {
            return;
        }
        Held top = held.peek();
        if (top == null) {
            return;
        }
        String forward = top.stripe + ">" + stripe;
        String backward = stripe + ">" + top.stripe;
        String opposite = EDGES.get(backward);
        if (opposite != null) {
            report("inversion", top.key, key,
                    "stripe " + top.stripe + " then " + stripe + " here (" + top.key + " then "
                            + key + "), but " + stripe + " then " + top.stripe + " elsewhere ("
                            + opposite + "). Two threads interleaving these can wait on each other"
                            + " forever, and because a queued writer blocks later readers, every"
                            + " request touching either stripe stops as well",
                    INVERSIONS);
        }
        if (EDGES.size() < MAX_EDGES) {
            EDGES.putIfAbsent(forward, top.key + " then " + key);
        }
    }

    /** Called after a successful acquisition. */
    public static void acquired(int stripe, String key, String mode) {
        HELD.get().push(new Held(stripe, key, mode));
    }

    /** Called on release. Removes the most recent matching entry, so reentrant holds unwind. */
    public static void released(int stripe) {
        Deque<Held> held = HELD.get();
        for (java.util.Iterator<Held> it = held.iterator(); it.hasNext();) {
            if (it.next().stripe == stripe) {
                it.remove();
                break;
            }
        }
        if (held.isEmpty()) {
            // A request thread is pooled and reused; leaving an empty deque attached to it is a
            // slow leak across thousands of threads.
            HELD.remove();
        }
    }

    private static void report(String kind, String first, String second, String detail,
            AtomicLong counter) {
        counter.incrementAndGet();
        String id = kind + "|" + first + "|" + second;
        if (FINDINGS.size() >= MAX_FINDINGS || FINDINGS.putIfAbsent(id,
                new Finding(kind, first, second, detail)) != null) {
            return; // already reported, or the cap is reached
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
        HELD.remove();
    }
}
