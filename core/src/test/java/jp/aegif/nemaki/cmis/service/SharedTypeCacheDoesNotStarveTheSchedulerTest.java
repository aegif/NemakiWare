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
package jp.aegif.nemaki.cmis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * R357 — a miss in the shared type caches must not stop the server.
 *
 * <h2>What happened</h2>
 *
 * <p>The three static caches of {@code RepositoryServiceImpl} filled themselves from inside
 * {@code ConcurrentHashMap.computeIfAbsent}, and the mapping function asked the type registry for
 * the answer. computeIfAbsent holds the bin's monitor for as long as it computes, and a virtual
 * thread with a monitor on its stack cannot be unmounted, whatever it then waits on. Eight
 * concurrent requests for a type the cache did not hold — which any signed-in user can send,
 * because the type is resolved while the request's properties are read, before the permission
 * checks — took every carrier and the whole server stopped answering, with the JVM never
 * restarted. Making the registry's own lock a parking lock (the first half of R357) did not help:
 * the monitor was the caller's, not the registry's.
 *
 * <h2>How this measures it</h2>
 *
 * <p>{@link SharedTypeCacheProbe} runs in a JVM of its own with four carriers and no
 * compensation. Its registry stand-in takes a lock of its own and the first answer waits inside
 * it for a sentinel virtual thread — the shape of the production wait. With the answer built
 * outside the map, the other requests park and give their carriers up, so the sentinel runs; with
 * the answer built inside computeIfAbsent they keep them and it never does.
 *
 * <p><b>Every one of the three caches is driven</b>, each in a JVM of its own, and the properties
 * are separate tests: the first version drove the type definitions only, and a review pointed out
 * that a regression in the children or the descendants cache would have left every test and every
 * control green. What the waiters queue on is read from the product's own lock, not inferred from
 * "parked on one and the same thing" — a sabotage that takes the cache lock away leaves them
 * queueing on the registry's lock, which that weaker count accepted.
 */
class SharedTypeCacheDoesNotStarveTheSchedulerTest {

    private static final long PROBE_BOUND_SECONDS = 180L;
    private static final Map<String, Map<String, String>> reports = new HashMap<>();
    private static final Map<String, String> outputs = new HashMap<>();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"definition", "children", "descendants"})
    @DisplayName("the requests behind the one being answered queue on the cache's own lock, so they give their carriers up")
    void theWaitersQueueOnTheCacheLock(String entry) {
        Map<String, String> r = storm(entry);
        assertEquals(String.valueOf(SharedTypeCacheProbe.WORKERS - 1),
                r.get("waitersOnTheCacheLock"),
                "the " + entry + " requests queued behind the one being answered were not all on"
                        + " the shared caches' lock (" + r.get("waitersParkedAnywhere")
                        + " were parked on something): a request blocked on a monitor keeps its"
                        + " carrier, which is R357");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"definition", "children", "descendants"})
    @DisplayName("a virtual thread the answer waits for still gets a carrier")
    void aVirtualThreadTheAnswerWaitsForStillRuns(String entry) {
        Map<String, String> r = storm(entry);
        assertEquals("true", r.get("sentinelInTime"),
                "the sentinel did not run within " + SharedTypeCacheProbe.DEADLINE_MS
                        + " ms while one " + entry + " miss was being answered and the others"
                        + " waited for it — every carrier was taken, the shape that stopped the"
                        + " server (R357)");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"definition", "children", "descendants"})
    @DisplayName("every request for the missing type finishes, and the next request answers")
    void everyRequestFinishesAndTheNextOneAnswers(String entry) {
        Map<String, String> r = storm(entry);
        assertEquals("true", r.get("holderEntered"),
                "no " + entry + " request reached the registry at all while eight of them asked"
                        + " for the same missing answer — with the computation inside the map the"
                        + " one that got in parks on the log appender's lock while it holds the"
                        + " bin's monitor, and the rest pin a carrier each behind it (R357)");
        assertEquals(String.valueOf(SharedTypeCacheProbe.WORKERS),
                r.get("workersFinishedInTime"),
                "not every concurrent " + entry + " request finished within "
                        + SharedTypeCacheProbe.DEADLINE_MS + " ms of the sentinel being started");
        assertEquals("true", r.get("unrelatedAnswered"),
                "a fresh virtual thread did not run right after the requests");
        assertEquals("true", r.get("cacheReadAnswered"),
                "a read of the " + entry + " cache did not answer right after the requests");
        assertEquals("0", r.get("workersAliveAtEnd"),
                "a request or the sentinel was still running after every bound had passed");
        assertEquals("0", r.get("workerFailures"),
                "a concurrent request failed: " + failures("storm:" + entry));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"definition", "children", "descendants"})
    @DisplayName("a request queued behind a miss is handed the instance the cache now holds")
    void theQueuedRequestGetsTheInstanceInTheCache(String entry) {
        Map<String, String> r = identity(entry);
        assertEquals("true", r.get("bothAnswered"),
                "one of the two " + entry + " requests got no answer at all: "
                        + failures("identity:" + entry));
        assertEquals("true", r.get("theTwoAnswersAreOneInstance"),
                "the " + entry + " request queued behind the miss was handed a different instance"
                        + " than the one that answered first — the callers of the type service"
                        + " compare type definitions by identity");
        assertEquals("true", r.get("theAnswerIsTheCachedInstance"),
                "an answer handed to a caller is not the instance the " + entry + " cache now"
                        + " holds, so a later reader of the same type gets a different object");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"definition", "children", "descendants"})
    @DisplayName("one miss is answered once, not once per request queued behind it")
    void oneMissIsAnsweredOnce(String entry) {
        Map<String, String> r = identity(entry);
        assertEquals("1", r.get("registryAnswersBuilt"),
                "the registry was asked more than once for one missing " + entry + ": the request"
                        + " that waited built a second answer instead of reading what the first"
                        + " put in the cache");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"definition", "children", "descendants"})
    @DisplayName("a type change that clears the caches is not undone by a miss already in flight")
    void aClearIsNotUndoneByAMissInFlight(String entry) {
        Map<String, String> r = clearDuringAMiss(entry);
        assertEquals("true", r.get("theClearSurvivedTheMiss"),
                "after an administrator's type change cleared the caches, the " + entry
                        + " answer that was already being built was written into the map"
                        + " afterwards, so every later request reads what the change removed"
                        + " (inside computeIfAbsent the map's bin lock held the two apart only"
                        + " for a bin the clear had not walked past yet)");
        // The clear's own answer is a claim about the product, not about whether the scenario
        // could be built: a regression that makes clearing throw must fire a lock, not be
        // reported as the harness being unable to look (review of the second round).
        assertEquals("true", r.get("clearAnswered"),
                "clearing the caches did not finish: " + r.get("clearAnswered"));
        assertEquals("true", r.get("clearWaitedForTheLock"),
                "the clear did not wait for the lock the miss was holding, so whether it is"
                        + " undone is a matter of timing");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The liveness report.
     *
     * <p>"A request reached the registry" is NOT a precondition here. It was, and a sabotage that
     * puts the computation back inside the map made it false — the thread computing inside the
     * bin's monitor parked on the log appender's lock, pinned, with the other requests pinned
     * behind it on that monitor, so nothing was left to run the owner of the appender's lock and
     * the registry was never reached at all. That is R357 reproducing in full, and the runner
     * rightly scored the round as the harness breaking rather than as the lock firing. It is a
     * liveness claim like the others, so it is asserted below.
     */
    private static Map<String, String> storm(String entry) {
        return probe("storm:" + entry);
    }

    /** The identity report, with the preconditions of the two requests checked. */
    private static Map<String, String> identity(String entry) {
        Map<String, String> r = probe("identity:" + entry);
        HarnessBroken.unless("true".equals(r.get("holderEntered")),
                "the first request did not reach the registry, so nothing was queued behind it");
        HarnessBroken.unless("true".equals(r.get("secondQueuedBehindTheFirst")),
                () -> "the second request did not queue on the shared caches' lock (it was "
                        + r.get("secondState") + "): with it blocked on a monitor instead, this"
                        + " scenario measures neither of the two answers");
        return r;
    }

    /** The clear report, with the precondition that a miss really was in flight. */
    private static Map<String, String> clearDuringAMiss(String entry) {
        Map<String, String> r = probe("clear:" + entry);
        HarnessBroken.unless("true".equals(r.get("holderEntered")),
                "no request was inside the registry when the clear ran");
        HarnessBroken.unless("true".equals(r.get("missAnswered")),
                "the miss that was in flight never answered, so what the clear left is not what"
                        + " this scenario is about");
        return r;
    }

    private static synchronized Map<String, String> probe(String scenario) {
        return reports.computeIfAbsent(scenario,
                SharedTypeCacheDoesNotStarveTheSchedulerTest::runTheProbe);
    }

    private static String failures(String scenario) {
        StringBuilder b = new StringBuilder();
        for (String line : outputs.getOrDefault(scenario, "").split("\n")) {
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
            log = Files.createTempFile("shared-type-cache-probe", ".log");
        } catch (Exception e) {
            throw new HarnessBroken("could not create the probe's log file", e);
        }
        List<String> command = List.of(java,
                "-XX:ActiveProcessorCount=" + SharedTypeCacheProbe.CARRIERS,
                "-Djdk.virtualThreadScheduler.parallelism=" + SharedTypeCacheProbe.CARRIERS,
                "-Djdk.virtualThreadScheduler.maxPoolSize=" + SharedTypeCacheProbe.CARRIERS,
                "-XX:+EnableDynamicAgentLoading",
                "-cp", classpath,
                SharedTypeCacheProbe.class.getName(), scenario);
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
                System.out.println(scenario + " " + line); // kept in the test's own output
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
        HarnessBroken.unless("true".equals(r.get("warmedUp")),
                () -> "the probe could not read the type once before the scenario: " + tail(seen));
        HarnessBroken.unless("true".equals(r.get("startsOnAMiss")),
                () -> "the cache still held the answer when the scenario started, so no miss was"
                        + " measured: " + tail(seen));
        HarnessBroken.unless("true".equals(r.get("finished")),
                () -> "the probe did not finish the " + scenario + " scenario: " + tail(seen));
        String four = String.valueOf(SharedTypeCacheProbe.CARRIERS);
        HarnessBroken.unless(four.equals(r.get("processors")) && four.equals(r.get("parallelism"))
                        && four.equals(r.get("maxPoolSize")),
                () -> "the probe JVM did not run with " + four + " carriers: " + r);
        // JEP 491 (JDK 24) stopped monitors from pinning, so on a later JDK computeIfAbsent would
        // pass this test as well and the test would stop telling the two apart. The image ships
        // JDK 21; on anything newer, say so instead of passing.
        HarnessBroken.unless(Integer.parseInt(r.get("feature")) < 24,
                () -> "JDK " + r.get("feature") + " does not pin carriers on monitors (JEP 491);"
                        + " this test only tells a monitor from a parking lock on JDK 21-23");
        return r;
    }

    private static String tail(String s) {
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }
}
