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
package jp.aegif.nemaki.cmis.aspect.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * R357 — concurrent refreshes of the type registry must not stop the server.
 *
 * <h2>What happened</h2>
 *
 * <p>On the Playwright gate a test that creates types concurrently stopped the server for
 * good, and it was reproduced in an isolated image. The thread dump showed the carriers
 * pinned: virtual threads blocked entering {@code synchronized (initLock)} in
 * {@code refreshTypes()}, and the one holding it parked on something only another virtual
 * thread could release, with no carrier left to run that thread. Not two monitors waiting on
 * each other: the scheduler starved itself of carriers, which is why only a JVM whose carriers
 * can all be taken shows it — the environment the probe below builds on purpose.
 *
 * <h2>How this measures it</h2>
 *
 * <p>{@link TypeRegistryLockProbe} runs in a JVM of its own with
 * {@link TypeRegistryLockProbe#CARRIERS} carriers and no compensation (the scheduler's
 * parallelism and its maximum pool size are set from that same constant).
 * {@link TypeRegistryLockProbe#WORKERS} refreshes are released at once; the store answer of
 * the first one waits, inside the lock,
 * for a sentinel virtual thread to let it go — the shape of the production wait. With a lock
 * that parks its waiters the sentinel runs at once; with the monitor it never gets a carrier.
 * Every wait in the probe is bounded, so it always reports, and the test JVM starts no worker
 * of its own.
 *
 * <p>Each property is its own test, so that a negative control names the one it removes.
 * The probe runs once per scenario, each in a JVM of its own: the lock is process-wide, and a
 * scenario that leaves it held must not decide the next one.
 */
class TypeRegistryLockDoesNotStarveTheSchedulerTest {

    private static final long PROBE_BOUND_SECONDS = 180L;
    private static final Map<String, Map<String, String>> reports = new HashMap<>();
    private static final Map<String, String> outputs = new HashMap<>();

    @Test
    @DisplayName("while one refresh holds the lock, the others really have called refreshTypes() behind it")
    void theOtherRefreshesReallyQueueBehindTheHolder() {
        Map<String, String> r = storm();
        int callers = Integer.parseInt(r.get("callersWhileHeld"));
        assertTrue(callers >= TypeRegistryLockProbe.CARRIERS - 1,
                "only " + callers + " other refresh(es) had been called while the holder was"
                        + " inside the lock — with fewer than one per remaining carrier the"
                        + " scheduler cannot be starved, so nothing here measures R357");
    }

    @Test
    @DisplayName("the waiters park on one and the same lock, so they give their carriers up")
    void theWaitersParkOnOneLock() {
        Map<String, String> r = storm();
        assertEquals(String.valueOf(TypeRegistryLockProbe.WORKERS - 1), r.get("waitersParked"),
                "the refreshes behind the holder were not all parked on one lock — a waiter"
                        + " blocked on a monitor keeps its carrier, which is R357");
    }

    @Test
    @DisplayName("a virtual thread the holder is waiting for still gets a carrier")
    void aVirtualThreadTheHolderWaitsForStillRuns() {
        Map<String, String> r = storm();
        assertEquals("true", r.get("sentinelInTime"),
                "the sentinel did not run within " + TypeRegistryLockProbe.DEADLINE_MS
                        + " ms while one refresh held the lock and the others waited for it —"
                        + " every carrier was taken, the shape that stopped the server (R357)");
    }

    @Test
    @DisplayName("every refresh finishes in time, and an unrelated request answers right after")
    void everyRefreshFinishesAndTheNextRequestAnswers() {
        Map<String, String> r = storm();
        assertEquals(String.valueOf(TypeRegistryLockProbe.WORKERS), r.get("workersFinishedInTime"),
                "not every concurrent refresh finished within " + TypeRegistryLockProbe.DEADLINE_MS
                        + " ms of the sentinel being started");
        assertEquals("true", r.get("unrelatedAnswered"),
                "a fresh virtual thread did not run right after the refreshes");
        assertEquals("true", r.get("registryReadAnswered"),
                "a read of the type registry did not answer right after the refreshes");
        assertEquals("0", r.get("workersAliveAtEnd"),
                "a refresh or the sentinel was still running after every bound had passed");
    }

    @Test
    @DisplayName("no two refreshes are inside the lock at once, and the registry is whole afterwards")
    void noTwoRefreshesAreInsideAtOnceAndTheRegistryIsWhole() {
        Map<String, String> r = storm();
        assertEquals("1", r.get("maxInside"),
                "two refreshes read the store inside the critical section at the same time —"
                        + " the lock no longer excludes them");
        assertEquals("0", r.get("workerFailures"),
                "a concurrent refresh failed: " + failures());
        assertEquals("true", r.get("cacheWhole"),
                "after the concurrent refreshes the registry did not hold the types it held"
                        + " before (" + r.get("cacheTypes") + " vs " + r.get("expectedTypes") + ")");
    }

    @Test
    @DisplayName("a refresh that throws inside the lock lets the next refresh, on another thread, in")
    void aRefreshThatThrowsLetsTheNextOneIn() {
        Map<String, String> r = probe("failure");
        HarnessBroken.unless("true".equals(r.get("failureEscaped")),
                "the refresh meant to fail inside the lock did not throw out of it — the"
                        + " release on the exceptional path was not exercised");
        assertEquals("true", r.get("refreshAfterFailureAnswered"),
                "after a refresh threw inside the lock, the next refresh from another thread"
                        + " did not get in — the lock was left held on the exceptional path");
    }

    @Test
    @DisplayName("ensureInitialized() → init() takes the lock twice on one thread without deadlocking")
    void aNestedInitDoesNotDeadlockOnItsOwnLock() {
        Map<String, String> r = probe("nested");
        assertEquals("true", r.get("nestedInitAnswered"),
                "ensureInitialized() holds the lock when it calls init(), which takes it again;"
                        + " a lock that is not reentrant stops that thread for good");
    }

    // ------------------------------------------------------------------------------------

    /** The report, with the preconditions of the storm checked. */
    private static Map<String, String> storm() {
        Map<String, String> r = probe("storm");
        HarnessBroken.unless("true".equals(r.get("holderEntered")),
                "no refresh reached the store inside the lock — the storm did not start");
        return r;
    }

    private static synchronized Map<String, String> probe(String scenario) {
        return reports.computeIfAbsent(scenario, TypeRegistryLockDoesNotStarveTheSchedulerTest::runTheProbe);
    }

    private static String failures() {
        StringBuilder b = new StringBuilder();
        for (String line : outputs.get("storm").split("\n")) {
            if (line.startsWith("PROBE workerFailure=")) {
                b.append(line.substring("PROBE workerFailure=".length())).append("; ");
            }
        }
        return b.toString();
    }

    private static Map<String, String> runTheProbe(String scenario) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Path log;
        try {
            log = Files.createTempFile("type-registry-lock-probe", ".log");
        } catch (Exception e) {
            throw new HarnessBroken("could not create the probe's log file", e);
        }
        List<String> command = List.of(java,
                "-XX:ActiveProcessorCount=" + TypeRegistryLockProbe.CARRIERS,
                "-Djdk.virtualThreadScheduler.parallelism=" + TypeRegistryLockProbe.CARRIERS,
                "-Djdk.virtualThreadScheduler.maxPoolSize=" + TypeRegistryLockProbe.CARRIERS,
                "-XX:+EnableDynamicAgentLoading",
                "-cp", classpath,
                TypeRegistryLockProbe.class.getName(), scenario);
        Process process;
        String output;
        try {
            // To a file, not a pipe: a pipe nobody drains fills up, and the probe's next write
            // would then block inside the log appender — a stall of the harness's own making.
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
        } catch (Exception e) {
            throw new HarnessBroken("could not start the probe JVM: " + command, e);
        }
        try {
            boolean exited = process.waitFor(PROBE_BOUND_SECONDS, TimeUnit.SECONDS);
            HarnessBroken.unless(exited, "the probe JVM did not exit within "
                    + PROBE_BOUND_SECONDS + " s although every wait in it is bounded");
            output = Files.readString(log, StandardCharsets.UTF_8);
            outputs.put(scenario, output);
        } catch (HarnessBroken e) {
            throw e;
        } catch (Exception e) {
            throw new HarnessBroken("could not read the probe's report", e);
        } finally {
            process.destroyForcibly();
            new File(log.toString()).delete();
        }
        Map<String, String> r = new HashMap<>();
        for (String line : output.split("\n")) {
            if (line.startsWith("PROBE ")) {
                System.out.println(line); // the measurements, kept in the test's own output
                int eq = line.indexOf('=');
                if (eq > 0) {
                    r.put(line.substring("PROBE ".length(), eq), line.substring(eq + 1).trim());
                }
            }
        }
        String seen = output;
        HarnessBroken.unless("true".equals(r.get("started")) && scenario.equals(r.get("scenario")),
                () -> "the probe JVM did not start the " + scenario + " scenario: " + tail(seen));
        HarnessBroken.unless(!r.containsKey("probeError"),
                () -> "the probe failed outside what it measures: " + r.get("probeError"));
        HarnessBroken.unless("true".equals(r.get("finished")),
                () -> "the probe did not finish the " + scenario + " scenario: " + tail(seen));
        String four = String.valueOf(TypeRegistryLockProbe.CARRIERS);
        HarnessBroken.unless(four.equals(r.get("processors")) && four.equals(r.get("parallelism"))
                        && four.equals(r.get("maxPoolSize")),
                () -> "the probe JVM did not run with " + four + " carriers: " + r);
        // JEP 491 (JDK 24) stopped monitors from pinning, so on a later JDK the monitor would
        // pass this test as well and the test would stop telling the two apart. The image
        // ships JDK 21; on anything newer, say so instead of passing.
        HarnessBroken.unless(Integer.parseInt(r.get("feature")) < 24,
                () -> "JDK " + r.get("feature") + " does not pin carriers on monitors (JEP 491);"
                        + " this test only tells a monitor from a parking lock on JDK 21-23");
        return r;
    }

    private static String tail(String s) {
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }
}
