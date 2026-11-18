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
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * R357 — a miss in the shared type caches must not stop the server, and must not queue behind the
 * misses of unrelated keys.
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
 * <p>Building the answer under ONE lock for all three caches (the second half) stopped that, and
 * a review measured what it cost: a miss for a type the registry does not know makes the registry
 * rebuild itself inside the lock, and absence is not cached, so every miss in the server queued
 * behind an unbounded line of whole-registry rebuilds that one signed-in user could keep full.
 * The lock is now one stripe per cache KEY, so a miss excludes the misses asking the same
 * question and no others.
 *
 * <h2>How this measures it</h2>
 *
 * <p>{@link SharedTypeCacheProbe} runs in a JVM of its own with four carriers and no
 * compensation. Its registry stand-in takes a lock of its own for the held type and the first
 * answer waits inside it for a sentinel virtual thread — the shape of the production wait. With
 * the answer built outside the map, the other requests park and give their carriers up, so the
 * sentinel runs; with the answer built inside computeIfAbsent they keep them and it never does.
 *
 * <p><b>Every one of the three caches is driven, and each has tests of its own</b> — not one
 * parameterised test over three — so that the runner's verdict names the cache whose protection a
 * sabotage removed (D35 Section 5). The first version drove the type definitions only, and a
 * review pointed out that a regression in the children or the descendants cache would have left
 * every test and every control green. What the waiters queue on is read from the product's own
 * stripe function, not inferred from "parked on one and the same thing" — a sabotage that takes
 * the cache lock away leaves them queueing on the registry's lock, which that weaker count
 * accepted.
 *
 * <p><b>What is not measured here:</b> whether a miss that needs no rebuild still waits for a
 * rebuild inside the real registry. The registry stand-in answers other keys without its lock, so
 * these tests measure the separation the stripes give and nothing about the registry's own
 * serialisation; that is measured in the real environment (D35 Section 4).
 */
class SharedTypeCacheDoesNotStarveTheSchedulerTest {

    private static final long PROBE_BOUND_SECONDS = 180L;
    private static final Map<String, Map<String, String>> reports = new HashMap<>();
    private static final Map<String, String> outputs = new HashMap<>();

    // ---------------------------------------------------------------- the type definitions cache

    @Test
    @DisplayName("the requests behind the type definition being answered queue on the stripe of its key, so they give their carriers up")
    void theWaitersForATypeDefinitionQueueOnItsStripe() {
        theWaitersQueueOnTheStripe("definition");
    }

    @Test
    @DisplayName("a virtual thread the type definition answer waits for still gets a carrier")
    void aVirtualThreadTheTypeDefinitionAnswerWaitsForStillRuns() {
        theSentinelStillRuns("definition");
    }

    @Test
    @DisplayName("every request for the missing type definition finishes, and the next request answers")
    void everyTypeDefinitionRequestFinishesAndTheNextOneAnswers() {
        everyRequestFinishes("definition");
    }

    @Test
    @DisplayName("a request queued behind a type definition miss is handed the instance the cache now holds")
    void theQueuedTypeDefinitionRequestGetsTheInstanceInTheCache() {
        theQueuedRequestGetsTheCachedInstance("definition");
    }

    @Test
    @DisplayName("one type definition miss is answered once, not once per request queued behind it")
    void oneTypeDefinitionMissIsAnsweredOnce() {
        oneMissIsAnsweredOnce("definition");
    }

    @Test
    @DisplayName("a type change that clears the caches is not undone by a type definition miss already in flight")
    void aClearIsNotUndoneByATypeDefinitionMissInFlight() {
        theClearIsNotUndone("definition");
    }

    @Test
    @DisplayName("a type definition miss for another key is answered while one miss is held")
    void aTypeDefinitionMissOnAnotherStripeAnswersWhileOneIsHeld() {
        aMissOnAnotherStripeAnswers("definition");
    }

    @Test
    @DisplayName("a type definition miss for a key on the same stripe waits for the one being answered")
    void aTypeDefinitionMissOnTheSameStripeWaitsForTheOneBeingAnswered() {
        aMissOnTheSameStripeWaits("definition");
    }

    // ------------------------------------------------------------------------ the children cache

    @Test
    @DisplayName("the requests behind the type children being answered queue on the stripe of its key, so they give their carriers up")
    void theWaitersForTheChildrenQueueOnItsStripe() {
        theWaitersQueueOnTheStripe("children");
    }

    @Test
    @DisplayName("a virtual thread the type children answer waits for still gets a carrier")
    void aVirtualThreadTheChildrenAnswerWaitsForStillRuns() {
        theSentinelStillRuns("children");
    }

    @Test
    @DisplayName("every request for the missing type children finishes, and the next request answers")
    void everyChildrenRequestFinishesAndTheNextOneAnswers() {
        everyRequestFinishes("children");
    }

    @Test
    @DisplayName("a request queued behind a type children miss is handed the instance the cache now holds")
    void theQueuedChildrenRequestGetsTheInstanceInTheCache() {
        theQueuedRequestGetsTheCachedInstance("children");
    }

    @Test
    @DisplayName("one type children miss is answered once, not once per request queued behind it")
    void oneChildrenMissIsAnsweredOnce() {
        oneMissIsAnsweredOnce("children");
    }

    @Test
    @DisplayName("a type change that clears the caches is not undone by a type children miss already in flight")
    void aClearIsNotUndoneByAChildrenMissInFlight() {
        theClearIsNotUndone("children");
    }

    @Test
    @DisplayName("a type children miss for another key is answered while one miss is held")
    void aChildrenMissOnAnotherStripeAnswersWhileOneIsHeld() {
        aMissOnAnotherStripeAnswers("children");
    }

    @Test
    @DisplayName("a type children miss for a key on the same stripe waits for the one being answered")
    void aChildrenMissOnTheSameStripeWaitsForTheOneBeingAnswered() {
        aMissOnTheSameStripeWaits("children");
    }

    // --------------------------------------------------------------------- the descendants cache

    @Test
    @DisplayName("the requests behind the type descendants being answered queue on the stripe of its key, so they give their carriers up")
    void theWaitersForTheDescendantsQueueOnItsStripe() {
        theWaitersQueueOnTheStripe("descendants");
    }

    @Test
    @DisplayName("a virtual thread the type descendants answer waits for still gets a carrier")
    void aVirtualThreadTheDescendantsAnswerWaitsForStillRuns() {
        theSentinelStillRuns("descendants");
    }

    @Test
    @DisplayName("every request for the missing type descendants finishes, and the next request answers")
    void everyDescendantsRequestFinishesAndTheNextOneAnswers() {
        everyRequestFinishes("descendants");
    }

    @Test
    @DisplayName("a request queued behind a type descendants miss is handed the instance the cache now holds")
    void theQueuedDescendantsRequestGetsTheInstanceInTheCache() {
        theQueuedRequestGetsTheCachedInstance("descendants");
    }

    @Test
    @DisplayName("one type descendants miss is answered once, not once per request queued behind it")
    void oneDescendantsMissIsAnsweredOnce() {
        oneMissIsAnsweredOnce("descendants");
    }

    @Test
    @DisplayName("a type change that clears the caches is not undone by a type descendants miss already in flight")
    void aClearIsNotUndoneByADescendantsMissInFlight() {
        theClearIsNotUndone("descendants");
    }

    @Test
    @DisplayName("a type descendants miss for another key is answered while one miss is held")
    void aDescendantsMissOnAnotherStripeAnswersWhileOneIsHeld() {
        aMissOnAnotherStripeAnswers("descendants");
    }

    @Test
    @DisplayName("a type descendants miss for a key on the same stripe waits for the one being answered")
    void aDescendantsMissOnTheSameStripeWaitsForTheOneBeingAnswered() {
        aMissOnTheSameStripeWaits("descendants");
    }

    // --------------------------------------------------- the stale entry, in the definitions only

    /**
     * The 0-property arm no lock reached until D35 Section 5. A cached type definition with no
     * properties is dropped as stale, but only if it is still the stale one: a request that read
     * it stale can be overtaken by a request that has already replaced it and handed its answer
     * out, and an unconditional remove drops that answer out of the cache. The first request then
     * builds a second instance of one type, and the callers of the type service compare type
     * definitions by identity.
     */
    @Test
    @DisplayName("a stale entry already replaced by another request is not dropped by the request that read it")
    void aStaleEntryAlreadyReplacedIsNotDroppedByTheRequestThatReadIt() {
        Map<String, String> r = stale();
        assertEquals("1", r.get("registryAnswersBuilt"),
                "the registry was asked " + r.get("registryAnswersBuilt") + " times for one type:"
                        + " the request that found the entry stale dropped the fresh entry another"
                        + " request had already put there and built a second instance of the same"
                        + " type");
        assertEquals("true", r.get("theTwoAnswersAreOneInstance"),
                "the two requests for one type were handed different instances — the callers of"
                        + " the type service compare type definitions by identity");
        assertEquals("true", r.get("theMapHoldsTheAnswerHandedOut"),
                "the cache no longer holds the instance that was handed to the callers, so the"
                        + " next reader of this type gets a different object again");
    }

    // ------------------------------------------------------------------------------------

    private static void theWaitersQueueOnTheStripe(String entry) {
        Map<String, String> r = storm(entry);
        assertEquals(String.valueOf(SharedTypeCacheProbe.WORKERS - 1),
                r.get("waitersOnTheStripeOfTheKey"),
                "the " + entry + " requests queued behind the one being answered were not all on"
                        + " the stripe of that key (" + r.get("waitersParkedAnywhere")
                        + " were parked on something): a request blocked on a monitor keeps its"
                        + " carrier, which is R357");
    }

    private static void theSentinelStillRuns(String entry) {
        Map<String, String> r = storm(entry);
        assertEquals("true", r.get("sentinelInTime"),
                "the sentinel did not run within " + SharedTypeCacheProbe.DEADLINE_MS
                        + " ms while one " + entry + " miss was being answered and the others"
                        + " waited for it — every carrier was taken, the shape that stopped the"
                        + " server (R357)");
    }

    private static void everyRequestFinishes(String entry) {
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

    private static void theQueuedRequestGetsTheCachedInstance(String entry) {
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

    private static void oneMissIsAnsweredOnce(String entry) {
        Map<String, String> r = identity(entry);
        assertEquals("1", r.get("registryAnswersBuilt"),
                "the registry was asked more than once for one missing " + entry + ": the request"
                        + " that waited built a second answer instead of reading what the first"
                        + " put in the cache");
    }

    private static void theClearIsNotUndone(String entry) {
        Map<String, String> r = clearDuringAMiss(entry);
        // Read by IDENTITY out of the map's VALUES, and with the size of all three maps beside
        // it: the first version read the one key the probe writes out for itself, which a key the
        // product never writes would have made true by itself (review of the second round).
        assertEquals("false", r.get("theMissAnswerIsInTheMapAfterTheClear"),
                "after an administrator's type change cleared the caches, the " + entry
                        + " answer that was already being built was written into the map"
                        + " afterwards, so every later request reads what the change removed"
                        + " (inside computeIfAbsent the map's bin lock held the two apart only"
                        + " for a bin the clear had not walked past yet)");
        assertEquals("0", r.get("entriesLeftInTheThreeMapsAfterTheClear"),
                "the three shared caches were not empty after a type change cleared them while a "
                        + entry + " miss was in flight");
        // The clear's own answer is a claim about the product, not about whether the scenario
        // could be built: a regression that makes clearing throw must fire a lock, not be
        // reported as the harness being unable to look (review of the second round).
        assertEquals("true", r.get("clearAnswered"),
                "clearing the caches did not finish: " + r.get("clearAnswered"));
        assertEquals("true", r.get("clearWaitedForTheLock"),
                "the clear did not wait for the stripe the miss was holding, so whether it is"
                        + " undone is a matter of timing");
    }

    private static void aMissOnAnotherStripeAnswers(String entry) {
        Map<String, String> r = stripes(entry);
        assertEquals("true", r.get("otherStripeAnsweredWhileOneIsHeld"),
                "a " + entry + " miss for a key on another stripe did not answer within "
                        + SharedTypeCacheProbe.DEADLINE_MS + " ms while one miss was held inside"
                        + " the registry (it took " + r.get("otherStripeMs") + " ms): with one"
                        + " lock for every key, a miss for a type the registry does not know"
                        + " rebuilds the whole registry inside that lock, and every other miss"
                        + " in the server has to take that same lock to be answered");
    }

    private static void aMissOnTheSameStripeWaits(String entry) {
        Map<String, String> r = stripes(entry);
        assertEquals("true", r.get("sameStripeQueuedOnTheHoldersStripe"),
                "a " + entry + " miss for another key on the SAME stripe did not wait for the one"
                        + " being answered (it was " + r.get("sameStripeState") + ", answered="
                        + r.get("sameStripeAnsweredWhileOneIsHeld") + "): two misses that share a"
                        + " stripe must still exclude each other, or one answer is built twice");
        assertEquals("true", r.get("sameStripeAnsweredAfterwards"),
                "the " + entry + " miss that waited for the stripe never answered after it was"
                        + " released");
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
     * liveness claim like the others, so it is asserted above.
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
                () -> "the second request did not queue on the stripe of that key (it was "
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

    /**
     * The stripe report. What the scenario needs is two more keys, one on the held stripe and one
     * off it, and it SEARCHES for them through the product's own stripe function — so the only
     * preconditions are that the search found them and that the first miss really is held.
     */
    private static Map<String, String> stripes(String entry) {
        Map<String, String> r = probe("stripes:" + entry);
        HarnessBroken.unless("true".equals(r.get("theSameStripeTypeIsOnTheHoldersStripe")),
                () -> "no second key on the held stripe (" + r.get("holderStripe") + ") was found"
                        + " among the ten thousand tried, so nothing here measures two keys"
                        + " sharing a stripe");
        // NOT a precondition. "No key lands off the held stripe" is exactly the state this
        // scenario is about — one lock for every key — and refusing it there made the sabotage
        // that restores that state break the harness instead of firing the lock below.
        HarnessBroken.unless("true".equals(r.get("holderEntered")),
                "the first request did not reach the registry, so no stripe was held while the"
                        + " other two asked");
        HarnessBroken.unless("true".equals(r.get("heldMissAnswered")),
                "the miss that was held never answered, so the stripe it held was never released"
                        + " in a way this scenario can read");
        return r;
    }

    /**
     * The stale-entry report. The preconditions are the two the scenario cannot work without: the
     * entry was stale when the first request read it, and the first request really was held
     * inside the window between reading it and dropping it.
     */
    private static Map<String, String> stale() {
        Map<String, String> r = probe("stale:definition");
        HarnessBroken.unless("true".equals(r.get("startsOnAStaleEntry")),
                "the stale entry this scenario needs was not in the cache when it started");
        HarnessBroken.unless("true".equals(r.get("windowEntered")),
                "the first request was not held between reading the stale entry and dropping it,"
                        + " so no other request could replace it in the meantime");
        HarnessBroken.unless("true".equals(r.get("theOtherRequestAnswered")),
                "the other request never answered, so nothing replaced the stale entry");
        HarnessBroken.unless("true".equals(r.get("theOtherRequestsAnswerIsInTheMap")),
                "the other request's answer was not in the cache before the first request went"
                        + " on, so there was nothing there for it to drop");
        HarnessBroken.unless("true".equals(r.get("theRequestThatWaitedAnswered")),
                "the request that waited never answered");
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
        // The key the probe reads the caches under has to be the key the product writes, or every
        // "the cache holds nothing" reading below is true whatever the product does. Measured
        // once, on the warm-up's own answer, rather than assumed (review of the second round).
        HarnessBroken.unless("true".equals(r.get("warmUpVisibleUnderThatKey")),
                () -> "the warm-up's answer could not be read back under the key this probe"
                        + " believes the product caches it under, so nothing it reads out of"
                        + " those maps means anything: " + tail(seen));
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
