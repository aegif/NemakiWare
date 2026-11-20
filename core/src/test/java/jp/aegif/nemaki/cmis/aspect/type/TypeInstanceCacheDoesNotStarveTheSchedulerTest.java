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
 * R357 — the registry's own instance-sharing cache must not stop the server either.
 *
 * <p>The caches one layer out, in {@code RepositoryServiceImpl}, were measured stopping the
 * server: requests pinned inside {@code ConcurrentHashMap.computeIfAbsent}, waiting for the
 * registry's lock or holding it and parked on something only another virtual thread could
 * release, with no carrier left to run that thread. A review of that fix found the registry's
 * own instance cache filling itself the same way.
 * Nothing had been measured there, so rather than arguing that the site is safe the same rule was
 * applied to it: the instance is built outside the map, under a lock whose waiters park.
 *
 * <p>{@link TypeInstanceCacheProbe} drives it in a JVM with
 * {@link TypeInstanceCacheProbe#CARRIERS} carriers. The wait inside the
 * computation is the registry's own type definition, whose property definitions the computation
 * reads first — <b>not</b> a log line: the first version held the builder on a {@code log.debug}
 * line and raised the logger to DEBUG to make an appender see it, which is a wait production does
 * not have, because the shipped level for {@code jp.aegif.nemaki} is INFO (D35 Section 5, which
 * asked for a line the shipped level lets through; there is one — the inherited-flag correction
 * warning — but this probe's fixture cannot reach it, so the lever is the object instead and the
 * deviation is recorded rather than hidden). The probe reports how many lines at INFO or above
 * one build writes, which is 0 <b>in this fixture</b>; nothing asserts it, and it is not a claim
 * about every build. With the lock, the other requests queue on it and a sentinel virtual thread
 * still runs; with computeIfAbsent they hold a monitor each and it does not.
 */
class TypeInstanceCacheDoesNotStarveTheSchedulerTest {

    private static final long PROBE_BOUND_SECONDS = 180L;
    private static final Map<String, Map<String, String>> reports = new HashMap<>();
    private static final Map<String, String> outputs = new HashMap<>();

    @Test
    @DisplayName("the requests behind the one building the shared instance queue on its lock")
    void theWaitersQueueOnTheInstanceLock() {
        Map<String, String> r = storm();
        assertEquals(String.valueOf(TypeInstanceCacheProbe.WORKERS - 1),
                r.get("waitersOnTheInstanceLock"),
                "the requests for the same type were not all queued on the instance cache's lock"
                        + " — a request blocked on the map's bin monitor keeps its carrier, which"
                        + " is R357");
    }

    @Test
    @DisplayName("a virtual thread the builder waits for still gets a carrier")
    void aVirtualThreadTheBuilderWaitsForStillRuns() {
        Map<String, String> r = storm();
        assertEquals("true", r.get("sentinelInTime"),
                "the sentinel did not run within " + TypeInstanceCacheProbe.DEADLINE_MS
                        + " ms while the shared instance was being built and the other requests"
                        + " waited for it — every carrier was taken, the shape that stopped the"
                        + " server (R357)");
    }

    @Test
    @DisplayName("every request for the shared instance finishes, and an unrelated one answers")
    void everyRequestFinishesAndAnUnrelatedOneAnswers() {
        Map<String, String> r = storm();
        assertEquals(String.valueOf(TypeInstanceCacheProbe.WORKERS),
                r.get("workersFinishedInTime"),
                "not every concurrent request for the same type finished within "
                        + TypeInstanceCacheProbe.DEADLINE_MS + " ms of the sentinel being started");
        assertEquals("true", r.get("unrelatedAnswered"),
                "a fresh virtual thread did not run right after the requests");
        assertEquals("0", r.get("workersAliveAtEnd"),
                "a request or the sentinel was still running after every bound had passed");
        assertEquals("0", r.get("workerFailures"),
                "a concurrent request failed: " + failures("storm"));
        assertEquals(String.valueOf(TypeInstanceCacheProbe.WORKERS), r.get("answersCollected"),
                "a request came back with no shared instance at all");
    }

    @Test
    @DisplayName("the request that waited reads the cache instead of building the instance again")
    void theQueuedRequestReadsWhatTheFirstOnePutInTheCache() {
        Map<String, String> r = identity();
        assertEquals("true", r.get("bothAnswered"),
                "one of the two requests got no answer at all: " + failures("identity"));
        // The identity of the two answers cannot tell this cache working from this cache being
        // bypassed: ensureConsistentPropertyDefinitions returns its own argument on every path,
        // and that argument is the registry's one instance, so both callers hold the same object
        // either way. A review of the first version measured that and the assertion on it was
        // withdrawn. How often the instance was built does tell them apart, and one build reads
        // the registry instance's properties exactly once (checked before the scenario).
        assertEquals("1", r.get("computationReads"),
                "the shared instance was built " + r.get("computationReads") + " times for one"
                        + " type: the request that waited built its own instead of reading what"
                        + " the first one put in the cache");
        assertEquals("true", r.get("theAnswerIsTheCachedInstance"),
                "an answer handed to a caller is not the instance the cache now holds");
    }

    // ------------------------------------------------------------------------------------

    private static Map<String, String> storm() {
        return probe("storm");
    }

    private static Map<String, String> identity() {
        Map<String, String> r = probe("identity");
        HarnessBroken.unless("true".equals(r.get("secondQueuedBehindTheFirst")),
                () -> "the second request did not queue on the instance cache's lock (it was "
                        + r.get("secondState") + "): with it blocked on a monitor instead, this"
                        + " scenario measures neither of the two answers");
        return r;
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

    private static synchronized Map<String, String> probe(String scenario) {
        return reports.computeIfAbsent(scenario,
                TypeInstanceCacheDoesNotStarveTheSchedulerTest::runTheProbe);
    }

    private static Map<String, String> runTheProbe(String scenario) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Path log;
        try {
            log = Files.createTempFile("type-instance-cache-probe", ".log");
        } catch (Exception e) {
            throw new HarnessBroken("could not create the probe's log file", e);
        }
        List<String> command = List.of(java,
                "-XX:ActiveProcessorCount=" + TypeInstanceCacheProbe.CARRIERS,
                "-Djdk.virtualThreadScheduler.parallelism=" + TypeInstanceCacheProbe.CARRIERS,
                "-Djdk.virtualThreadScheduler.maxPoolSize=" + TypeInstanceCacheProbe.CARRIERS,
                "-XX:+EnableDynamicAgentLoading",
                "-cp", classpath,
                TypeInstanceCacheProbe.class.getName(), scenario);
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
                System.out.println(scenario + " " + line);
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
        // The lever: the registry hands the computation a definition of the probe's own, and one
        // build reads its properties exactly once — which is what makes the count of those reads
        // a count of builds.
        HarnessBroken.unless("true".equals(r.get("theRegistryHoldsTheProbesDefinition")),
                () -> "the registry did not hand out the definition this probe installed, so"
                        + " nothing holds a request inside the computation: " + tail(seen));
        HarnessBroken.unless("true".equals(r.get("buildAnswered")),
                () -> "a request did not answer at all before the scenario: " + tail(seen));
        HarnessBroken.unless("1".equals(r.get("computationReadsInOneBuild")),
                () -> "one build read the registry instance's properties from inside the"
                        + " computation " + r.get("computationReadsInOneBuild") + " times, not"
                        + " once (" + r.get("propertyReadsInOneBuild") + " reads in all), so a"
                        + " count of those reads is not a count of builds: " + tail(seen));
        HarnessBroken.unless("true".equals(r.get("startsOnAMiss")),
                () -> "the cache still held the instance when the scenario started: " + tail(seen));
        HarnessBroken.unless("true".equals(r.get("holderEntered")),
                () -> "no request was held inside the computation: " + tail(seen));
        HarnessBroken.unless("true".equals(r.get("finished")),
                () -> "the probe did not finish the " + scenario + " scenario: " + tail(seen));
        String four = String.valueOf(TypeInstanceCacheProbe.CARRIERS);
        HarnessBroken.unless(four.equals(r.get("processors")) && four.equals(r.get("parallelism"))
                        && four.equals(r.get("maxPoolSize")),
                () -> "the probe JVM did not run with " + four + " carriers: " + r);
        // JEP 491 (JDK 24) stopped monitors from pinning, so on a later JDK computeIfAbsent would
        // pass this test as well and the test would stop telling the two apart.
        HarnessBroken.unless(Integer.parseInt(r.get("feature")) < 24,
                () -> "JDK " + r.get("feature") + " does not pin carriers on monitors (JEP 491);"
                        + " this test only tells a monitor from a parking lock on JDK 21-23");
        return r;
    }

    private static String tail(String s) {
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }
}
