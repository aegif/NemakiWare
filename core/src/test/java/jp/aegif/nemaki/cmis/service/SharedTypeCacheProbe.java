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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionListImpl;

import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.impl.RepositoryServiceImpl;

/**
 * The child JVM of {@link SharedTypeCacheDoesNotStarveTheSchedulerTest} (R357). Not a test.
 *
 * <p>That test starts this class in a JVM with four carriers and no compensation, once per
 * scenario and per cache, and reads the {@code PROBE key=value} lines it prints. One JVM each,
 * because the caches and their locks are process-wide. Every wait here is bounded and the main
 * thread is a platform thread, so the measurements are printed even when the virtual threads are
 * stuck; {@code System.exit} then takes them down with the JVM.
 *
 * <p>The shape is the one that stopped the server. A request asks for something the shared cache
 * does not hold, and answering the miss goes to the type registry, which takes a lock of its own
 * and, inside it, waits for something only another virtual thread can give it (in production the
 * log appender's lock; here the sentinel). The registry here is a stand-in with exactly that
 * shape, because what is measured is the caller: whether a request that waits for the registry
 * keeps its carrier. It keeps it if it holds a monitor, and filling the cache from inside
 * {@code ConcurrentHashMap.computeIfAbsent} means holding one. With as many waiting requests as
 * there are carriers, nothing is left to run the thread the holder waits for.
 *
 * <p><b>All three caches are driven.</b> The first version drove only the type definitions, and
 * a review pointed out that a regression in the children or the descendants cache — two of the
 * three paths the fix changed — would have left every lock and every control green.
 *
 * <p><b>The registry stand-in takes its lock for the held type only.</b> One lock for every
 * answer was right while one lock guarded all three caches, and it is wrong now: with it, a miss
 * for ANY key would wait for the held one inside the registry, and the separation the stripes
 * give could not be told from its absence. The real registry rebuilds itself for a type it does
 * not know and serialises on its own lock while it does — <b>whether a miss that needs no rebuild
 * still waits for that rebuild is NOT measured here</b>; it is measured in the real environment,
 * where the registry is the real one (D35 Section 4).
 */
public final class SharedTypeCacheProbe {

    static final String REPO = "probe";
    /** The type whose miss is held inside the registry stand-in. */
    static final String TYPE = "probe:custom";
    static final int CARRIERS = 4;
    static final int WORKERS = 8;
    /** How long anything is given that, with a working lock, takes milliseconds. */
    static final long DEADLINE_MS = 5_000L;
    /** The holder's own bound. Longer than every check made while it holds the registry. */
    static final long HOLDER_BOUND_MS = 30_000L;

    /** Which of the three caches a run drives. */
    enum Entry {
        definition("getTypeDefinition", "SHARED_TYPE_DEFINITIONS"),
        children("getTypesChildren", "SHARED_TYPE_CHILDREN"),
        descendants("getTypesDescendants", "SHARED_TYPE_DESCENDANTS");

        /** The registry method answering this cache's miss. */
        final String registryMethod;
        private final String field;

        Entry(String registryMethod, String field) {
            this.registryMethod = registryMethod;
            this.field = field;
        }

        /** The request, through the service's public door. */
        Object ask(RepositoryServiceImpl service, String typeId) {
            switch (this) {
                case definition:
                    return service.getTypeDefinition(null, REPO, typeId, null);
                case children:
                    return service.getTypeChildren(null, REPO, typeId, Boolean.TRUE, null, null,
                            null);
                default:
                    return service.getTypeDescendants(null, REPO, typeId, BigInteger.ONE,
                            Boolean.TRUE, null);
            }
        }

        /**
         * The key this cache answers that request under — written out here as the product writes
         * it. That the two agree is not assumed: the warm-up's answer is read back under this key
         * before any scenario starts, and a probe that cannot find it says so (a review of the
         * first version found the clear scenario reading a key of the probe's own making, where a
         * key the product never writes would have made "the clear was not undone" true by
         * itself).
         */
        String keyFor(String typeId) {
            switch (this) {
                case definition:
                    return REPO + ":" + typeId;
                case children:
                    return REPO + ":" + typeId + ":true:null:null";
                default:
                    return REPO + ":" + typeId + ":1:true";
            }
        }

        /** What the cache holds for that request, read straight out of the static map. */
        Object cached(String typeId) {
            return map(this).get(keyFor(typeId));
        }
    }

    /**
     * The registry's own lock. TypeManagerImpl's stands here: answering a miss for the held type
     * takes it, and the first such answer waits inside it for something only another virtual
     * thread can give it.
     */
    private static final ReentrantLock registryLock = new ReentrantLock();

    private static final AtomicBoolean storm = new AtomicBoolean(false);
    private static final AtomicBoolean holderClaimed = new AtomicBoolean(false);
    private static final AtomicReference<Thread> holder = new AtomicReference<>();
    private static final CountDownLatch holderEntered = new CountDownLatch(1);
    private static final CountDownLatch released = new CountDownLatch(1);
    private static final AtomicBoolean releasedBySentinel = new AtomicBoolean(false);
    /** Answers the registry stand-in built, that is, misses not served from the map. */
    private static final AtomicInteger built = new AtomicInteger();

    private SharedTypeCacheProbe() {
    }

    static void report(String key, Object value) {
        System.out.println("PROBE " + key + "=" + value);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        String[] parts = (args.length == 1 ? args[0] : "").split(":", 2);
        String scenario = parts[0];
        report("started", true);
        report("scenario", args.length == 1 ? args[0] : "");
        report("feature", Runtime.version().feature());
        report("processors", Runtime.getRuntime().availableProcessors());
        report("parallelism", System.getProperty("jdk.virtualThreadScheduler.parallelism"));
        report("maxPoolSize", System.getProperty("jdk.virtualThreadScheduler.maxPoolSize"));
        try {
            Entry entry = Entry.valueOf(parts.length == 2 ? parts[1] : "definition");
            report("entry", entry);
            report("stripes", stripeCount());
            RepositoryServiceImpl service = service();
            // The classes of the answer path are loaded before anything contends, and the
            // warm-up's own entry is dropped again so that the scenario starts on a miss.
            report("warmedUp", entry.ask(service, TYPE) != null);
            // The product wrote the warm-up's answer under a key of its own. If this probe
            // cannot read it back under the key it believes in, every later "the cache holds
            // nothing" reading would be true whatever the product did.
            report("warmUpVisibleUnderThatKey", entry.cached(TYPE) != null);
            clearTheCaches();
            built.set(0);
            report("startsOnAMiss", entry.cached(TYPE) == null);

            switch (scenario) {
                case "storm" -> storm(service, entry);
                case "identity" -> identity(service, entry);
                case "clear" -> clearDuringAMiss(service, entry);
                case "stripes" -> stripes(service, entry);
                case "stale" -> staleEntryReplacedWhileItIsRead(service, entry);
                default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
            }
            report("finished", true);
        } catch (Throwable t) {
            report("probeError", t.toString().replace('\n', ' '));
            t.printStackTrace(System.out);
        }
        System.out.flush();
        System.exit(0);
    }

    /** Eight requests for one thing the cache does not hold, released at once. */
    private static void storm(RepositoryServiceImpl service, Entry entry) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicReferenceArray<Object> answers = new AtomicReferenceArray<>(WORKERS);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            final int slot = i;
            workers.add(Thread.ofVirtual().name("probe-worker-" + i).unstarted(() -> {
                try {
                    go.await();
                    answers.set(slot, entry.ask(service, TYPE));
                    finished.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        storm.set(true);
        for (Thread w : workers) {
            w.start();
        }
        go.countDown(); // all eight are released at once

        boolean entered = holderEntered.await(DEADLINE_MS, TimeUnit.MILLISECONDS);
        report("holderEntered", entered);

        // The others queue behind it ON THE STRIPE OF THEIR OWN KEY. Counting "parked on one and
        // the same blocker" was not enough: a sabotage that takes the cache lock away leaves
        // every miss queueing on the registry's lock instead, and seven threads parked on that
        // sibling would have satisfied the count (review of the first version). The product's own
        // lock for this key is asked, through the product's own stripe function.
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        int queued = 0;
        do {
            queued = stripeOf(entry.keyFor(TYPE)).getQueueLength();
            if (queued == WORKERS - 1) {
                break;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < until);
        report("waitersOnTheStripeOfTheKey", queued);
        report("waitersParkedAnywhere", parkedAnywhere(workers));

        // The sentinel: a virtual thread the holder is waiting for.
        CountDownLatch sentinelRan = new CountDownLatch(1);
        long sentinelStart = System.nanoTime();
        Thread sentinel = Thread.ofVirtual().name("probe-sentinel").start(() -> {
            sentinelRan.countDown();
            releasedBySentinel.set(true);
            released.countDown();
        });
        boolean sentinelInTime = sentinelRan.await(DEADLINE_MS, TimeUnit.MILLISECONDS);
        report("sentinelInTime", sentinelInTime);
        report("sentinelMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentinelStart));

        joinByOneDeadline(workers, DEADLINE_MS);
        report("workersFinishedInTime", finished.get());

        // An unrelated request right after: a fresh virtual thread, and a read of the cache.
        CompletableFuture<Boolean> unrelated = new CompletableFuture<>();
        Thread.ofVirtual().name("probe-unrelated").start(() -> unrelated.complete(true));
        report("unrelatedAnswered", answered(unrelated));
        CompletableFuture<Boolean> read = new CompletableFuture<>();
        Thread.ofVirtual().name("probe-read").start(() -> {
            try {
                read.complete(entry.ask(service, TYPE) != null);
            } catch (Throwable t) {
                read.completeExceptionally(t);
            }
        });
        report("cacheReadAnswered", answered(read));

        // Rescue, so that nothing is left running: the holder is let go whether or not the
        // sentinel ever ran, and every worker is waited for — under ONE deadline, not one each,
        // or eight stuck workers could outlast the bound the parent gives this JVM and the
        // measurement would be reported as the harness breaking (review of the first version).
        released.countDown();
        joinByOneDeadline(workers, HOLDER_BOUND_MS);
        sentinel.join(DEADLINE_MS);
        int alive = 0;
        for (Thread w : workers) {
            if (w.isAlive()) {
                alive++;
            }
        }
        report("workersAliveAtEnd", alive + (sentinel.isAlive() ? 1 : 0));
        report("releasedBySentinel", releasedBySentinel.get());
        report("workerFailures", failures.size());
        for (Throwable t : failures) {
            report("workerFailure", t.toString().replace('\n', ' '));
        }
        report("registryAnswersBuilt", built.get());
        report("answersCollected", answersCollected(answers));
        storm.set(false);
    }

    /**
     * Two requests for the same missing thing, the second queued behind the first. Whoever
     * answers second must hand back what the first put in the map, not a second answer built
     * while it waited: the callers of the type service compare type definitions by identity.
     */
    private static void identity(RepositoryServiceImpl service, Entry entry) throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicReference<Object> firstAnswer = new AtomicReference<>();
        AtomicReference<Object> secondAnswer = new AtomicReference<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        storm.set(true);
        Thread first = Thread.ofVirtual().name("probe-first").start(() -> {
            try {
                firstAnswer.set(entry.ask(service, TYPE));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        report("holderEntered", holderEntered.await(DEADLINE_MS, TimeUnit.MILLISECONDS));

        Thread second = Thread.ofVirtual().name("probe-second").start(() -> {
            try {
                secondStarted.countDown();
                secondAnswer.set(entry.ask(service, TYPE));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        secondStarted.await(DEADLINE_MS, TimeUnit.MILLISECONDS);

        // The second one is given a bounded moment to queue on the stripe of that key.
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        boolean queued = false;
        do {
            queued = stripeOf(entry.keyFor(TYPE)).getQueueLength() == 1;
            if (queued) {
                break;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < until);
        report("secondQueuedBehindTheFirst", queued);
        report("secondState", second.getState());

        released.countDown();
        first.join(HOLDER_BOUND_MS);
        second.join(HOLDER_BOUND_MS);
        Object inTheCache = entry.cached(TYPE);
        report("bothAnswered", firstAnswer.get() != null && secondAnswer.get() != null);
        report("registryAnswersBuilt", built.get());
        report("theTwoAnswersAreOneInstance", firstAnswer.get() == secondAnswer.get());
        report("theAnswerIsTheCachedInstance", inTheCache != null
                && firstAnswer.get() == inTheCache && secondAnswer.get() == inTheCache);
        report("workerFailures", failures.size());
        for (Throwable t : failures) {
            report("workerFailure", t.toString().replace('\n', ' '));
        }
        storm.set(false);
    }

    /**
     * A type is changed while a miss is in flight. The clear must not be undone by the answer
     * that was already being built. With the computation inside computeIfAbsent the map's own bin
     * lock held the two apart only for a bin the clear had not walked past yet — clear() goes
     * through the table in index order — so the window existed before as well; moving the
     * computation out of the map removed the bin lock from the picture entirely (review of the
     * first version).
     *
     * <p>What is read afterwards is the map's VALUES, by identity, and the size of all three
     * maps: the first version read the one key this probe writes out for itself, and a key the
     * product never writes would have made the reading true by itself (review of the second
     * round). The agreement of the two keys is measured as well, in main(), where the warm-up's
     * answer is read back under it.
     */
    private static void clearDuringAMiss(RepositoryServiceImpl service, Entry entry)
            throws Exception {
        AtomicReference<Object> answer = new AtomicReference<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        storm.set(true);
        Thread miss = Thread.ofVirtual().name("probe-miss").start(() -> {
            try {
                answer.set(entry.ask(service, TYPE));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        report("holderEntered", holderEntered.await(DEADLINE_MS, TimeUnit.MILLISECONDS));

        // The administrator's clear, on a virtual thread of its own, while the miss is inside.
        CountDownLatch clearStarted = new CountDownLatch(1);
        CompletableFuture<Boolean> cleared = new CompletableFuture<>();
        Thread.ofVirtual().name("probe-clear").start(() -> {
            try {
                clearStarted.countDown();
                Method m = RepositoryServiceImpl.class.getDeclaredMethod("clearSharedTypeCaches");
                m.setAccessible(true);
                m.invoke(service);
                cleared.complete(true);
            } catch (Throwable t) {
                cleared.completeExceptionally(t);
            }
        });
        clearStarted.await(DEADLINE_MS, TimeUnit.MILLISECONDS);
        // A bounded moment for the clear either to queue on the stripe the miss holds or to run
        // straight through it.
        ReentrantLock held = stripeOf(entry.keyFor(TYPE));
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000L);
        while (System.nanoTime() < until && !cleared.isDone() && held.getQueueLength() == 0) {
            Thread.sleep(10L);
        }
        report("clearWaitedForTheLock", held.getQueueLength() == 1 && !cleared.isDone());

        released.countDown();
        miss.join(HOLDER_BOUND_MS);
        report("missAnswered", answer.get() != null);
        report("clearAnswered", answered(cleared));
        report("theMissAnswerIsInTheMapAfterTheClear", isAValueOf(entry, answer.get()));
        report("entriesLeftInTheThreeMapsAfterTheClear", entriesInAllThreeMaps());
        report("workerFailures", failures.size());
        for (Throwable t : failures) {
            report("workerFailure", t.toString().replace('\n', ' '));
        }
        storm.set(false);
    }

    /**
     * What the stripes are for: a miss for one key must not wait for a miss for another.
     *
     * <p>One request is held inside the registry, holding the stripe of its key. A second request
     * asks for a key that lands on THAT stripe, and must wait — two requests for the same stripe
     * still exclude each other, which is what keeps one answer from being built twice. A third
     * asks for a key that lands on another stripe, and must be answered while the first is still
     * held: with one lock for all three caches, a miss for a type the registry does not know
     * rebuilt the whole registry inside that lock, so every miss in the server queued behind it.
     *
     * <p>The two extra type ids are SEARCHED FOR through the product's own stripe function, not
     * written out here: the stripe of a key is an implementation detail, and a hard-coded pair
     * would stop being on the same (or another) stripe the moment the function changed, with the
     * scenario still reporting whatever it then measured. The search asks the product and the
     * answer is reported.
     */
    private static void stripes(RepositoryServiceImpl service, Entry entry) throws Exception {
        String holderKey = entry.keyFor(TYPE);
        int holderStripe = stripeIndexOf(holderKey);
        String sameStripeType = typeOnStripe(entry, holderStripe, true);
        String otherStripeType = typeOnStripe(entry, holderStripe, false);
        report("holderStripe", holderStripe);
        report("sameStripeType", sameStripeType);
        report("otherStripeType", otherStripeType);
        report("theSameStripeTypeIsOnTheHoldersStripe", sameStripeType != null
                && stripeIndexOf(entry.keyFor(sameStripeType)) == holderStripe);
        report("theOtherStripeTypeIsNotOnTheHoldersStripe", otherStripeType != null
                && stripeIndexOf(entry.keyFor(otherStripeType)) != holderStripe);
        // A key OFF the held stripe may not exist — that is what "one lock for every key"
        // looks like from here, and it is the thing this scenario measures, so it is REPORTED
        // (as a value that is not "true") rather than refused. Refusing it made the sabotage
        // that returns one stripe for every key break the harness instead of firing the lock.
        if (sameStripeType == null) {
            return; // without two keys on one stripe there is no exclusion to measure
        }

        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        storm.set(true);
        AtomicReference<Object> heldAnswer = new AtomicReference<>();
        Thread heldMiss = Thread.ofVirtual().name("probe-held").start(() -> {
            try {
                heldAnswer.set(entry.ask(service, TYPE));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        report("holderEntered", holderEntered.await(DEADLINE_MS, TimeUnit.MILLISECONDS));

        // A miss on another stripe, while the first one is held. It must answer.
        if (otherStripeType == null) {
            report("otherStripeAnsweredWhileOneIsHeld", "thereIsOneStripeForEveryKey");
            report("otherStripeMs", -1);
        } else {
            long otherStart = System.nanoTime();
            CompletableFuture<Boolean> other = new CompletableFuture<>();
            Thread.ofVirtual().name("probe-other-stripe").start(() -> {
                try {
                    other.complete(entry.ask(service, otherStripeType) != null);
                } catch (Throwable t) {
                    other.completeExceptionally(t);
                }
            });
            report("otherStripeAnsweredWhileOneIsHeld", answered(other));
            report("otherStripeMs",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - otherStart));
        }

        // A miss on the holder's own stripe, while the first one is held. It must wait.
        CountDownLatch sameStarted = new CountDownLatch(1);
        CompletableFuture<Boolean> same = new CompletableFuture<>();
        Thread sameThread = Thread.ofVirtual().name("probe-same-stripe").start(() -> {
            try {
                sameStarted.countDown();
                same.complete(entry.ask(service, sameStripeType) != null);
            } catch (Throwable t) {
                same.completeExceptionally(t);
            }
        });
        sameStarted.await(DEADLINE_MS, TimeUnit.MILLISECONDS);
        ReentrantLock stripe = stripeOf(holderKey);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        boolean queued = false;
        do {
            queued = stripe.getQueueLength() >= 1;
            if (queued) {
                break;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < until);
        report("sameStripeQueuedOnTheHoldersStripe", queued && !same.isDone());
        report("sameStripeAnsweredWhileOneIsHeld", same.isDone());
        report("sameStripeState", sameThread.getState());

        released.countDown();
        heldMiss.join(HOLDER_BOUND_MS);
        sameThread.join(HOLDER_BOUND_MS);
        report("heldMissAnswered", heldAnswer.get() != null);
        report("sameStripeAnsweredAfterwards", answered(same));
        report("registryAnswersBuilt", built.get());
        report("workerFailures", failures.size());
        for (Throwable t : failures) {
            report("workerFailure", t.toString().replace('\n', ' '));
        }
        storm.set(false);
    }

    /**
     * The one arm of this path that no lock reached (D35 Section 5). A cached type definition
     * with no properties is dropped as stale — but only if it is still the stale one, because a
     * request that found it stale may be overtaken by a request that has already replaced it and
     * handed its answer out. An unconditional {@code remove} drops that fresh entry, the first
     * request then builds a SECOND instance of one type, and the callers of the type service
     * compare type definitions by identity.
     *
     * <p>Driven through the product's door twice. The stale entry is this probe's, and it is the
     * stale entry itself that holds the first request inside the window: the product asks it for
     * its property definitions, twice, and the second answer comes only after the other request
     * has replaced the entry. No log level and no appender is involved — the lever is the object
     * the product is reading.
     */
    private static void staleEntryReplacedWhileItIsRead(RepositoryServiceImpl service, Entry entry)
            throws Exception {
        if (entry != Entry.definition) {
            report("notThisCache", true);
            return;
        }
        CountDownLatch windowEntered = new CountDownLatch(1);
        CountDownLatch replaced = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean claimed = new AtomicBoolean(false);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        // A type definition with no properties at all: what the product reads as a stale entry.
        DocumentTypeDefinitionImpl stale = new DocumentTypeDefinitionImpl() {
            @Override
            public Map<String, PropertyDefinition<?>> getPropertyDefinitions() {
                // The product asks twice: `== null`, then `isEmpty()`. The second answer is
                // given only after the other request has put a fresh entry in the map, so the
                // entry this one is about to drop is no longer the stale one.
                if (reads.incrementAndGet() == 2 && claimed.compareAndSet(false, true)) {
                    windowEntered.countDown();
                    try {
                        replaced.await(HOLDER_BOUND_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Collections.emptyMap();
            }
        };
        stale.setId(TYPE);
        putInTheMap(entry, entry.keyFor(TYPE), stale);
        report("startsOnAStaleEntry", entry.cached(TYPE) == stale);

        AtomicReference<Object> firstAnswer = new AtomicReference<>();
        Thread first = Thread.ofVirtual().name("probe-stale-reader").start(() -> {
            try {
                firstAnswer.set(entry.ask(service, TYPE));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        report("windowEntered", windowEntered.await(DEADLINE_MS, TimeUnit.MILLISECONDS));

        // The other request: the same door, the same key. It finds the entry still stale, drops
        // it, builds the fresh answer and hands it out.
        AtomicReference<Object> secondAnswer = new AtomicReference<>();
        Thread second = Thread.ofVirtual().name("probe-stale-replacer").start(() -> {
            try {
                secondAnswer.set(entry.ask(service, TYPE));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        second.join(DEADLINE_MS);
        report("theOtherRequestAnswered", secondAnswer.get() != null);
        report("theOtherRequestsAnswerIsInTheMap", entry.cached(TYPE) == secondAnswer.get());
        replaced.countDown();
        first.join(HOLDER_BOUND_MS);

        report("theRequestThatWaitedAnswered", firstAnswer.get() != null);
        report("registryAnswersBuilt", built.get());
        report("theTwoAnswersAreOneInstance", firstAnswer.get() == secondAnswer.get());
        report("theMapHoldsTheAnswerHandedOut", entry.cached(TYPE) == secondAnswer.get()
                && entry.cached(TYPE) == firstAnswer.get());
        report("workerFailures", failures.size());
        for (Throwable t : failures) {
            report("workerFailure", t.toString().replace('\n', ' '));
        }
    }

    // ------------------------------------------------------------------------------------

    private static void joinByOneDeadline(List<Thread> threads, long budgetMs)
            throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        for (Thread t : threads) {
            long left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
            if (left > 0) {
                t.join(left);
            }
        }
    }

    private static int answersCollected(AtomicReferenceArray<Object> answers) {
        int n = 0;
        for (int i = 0; i < answers.length(); i++) {
            if (answers.get(i) != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * The product's own stripe for a key, through the product's own function — so that what the
     * waiters queue on is the thing under measurement, and a change to how the stripe is chosen
     * cannot leave this probe watching a lock nobody takes.
     */
    private static ReentrantLock stripeOf(String cacheKey) {
        try {
            Method m = RepositoryServiceImpl.class.getDeclaredMethod("stripeOf", String.class);
            m.setAccessible(true);
            return (ReentrantLock) m.invoke(null, cacheKey);
        } catch (Exception e) {
            throw new IllegalStateException("could not ask the product for the stripe of a key", e);
        }
    }

    private static ReentrantLock[] stripeArray() {
        try {
            Field f = RepositoryServiceImpl.class.getDeclaredField("SHARED_TYPE_CACHE_LOCKS");
            f.setAccessible(true);
            return (ReentrantLock[]) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("could not read the shared caches' locks", e);
        }
    }

    private static int stripeCount() {
        return stripeArray().length;
    }

    /** Which stripe of the product's array a key is answered under, by identity. */
    private static int stripeIndexOf(String cacheKey) {
        ReentrantLock lock = stripeOf(cacheKey);
        ReentrantLock[] stripes = stripeArray();
        for (int i = 0; i < stripes.length; i++) {
            if (stripes[i] == lock) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A type id, other than {@link #TYPE}, whose key for this cache lands on (or off) the given
     * stripe. Searched, not written out: see {@link #stripes}.
     */
    private static String typeOnStripe(Entry entry, int stripe, boolean onIt) {
        for (int i = 0; i < 10_000; i++) {
            String candidate = "probe:other" + i;
            int index = stripeIndexOf(entry.keyFor(candidate));
            if (index < 0) {
                return null;
            }
            if ((index == stripe) == onIt) {
                return candidate;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Entry entry) {
        try {
            Field f = RepositoryServiceImpl.class.getDeclaredField(entry.field);
            f.setAccessible(true);
            return (Map<String, Object>) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + entry.field, e);
        }
    }

    private static void putInTheMap(Entry entry, String key, Object value) {
        map(entry).put(key, value);
    }

    /** Whether that very object is one of the cache's values — identity, not equality. */
    private static boolean isAValueOf(Entry entry, Object answer) {
        if (answer == null) {
            return false;
        }
        for (Object value : map(entry).values()) {
            if (value == answer) {
                return true;
            }
        }
        return false;
    }

    private static int entriesInAllThreeMaps() {
        int n = 0;
        for (Entry e : Entry.values()) {
            n += map(e).size();
        }
        return n;
    }

    private static void clearTheCaches() {
        for (Entry e : Entry.values()) {
            map(e).clear();
        }
    }

    private static String answered(CompletableFuture<Boolean> f) {
        try {
            return String.valueOf(f.get(DEADLINE_MS, TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException e) {
            return "timeout";
        } catch (Exception e) {
            return "error:" + e.getCause();
        }
    }

    /**
     * Workers parked on anything at all, the holder apart — reported beside the stripe's own queue
     * length, never asserted on. The two differing says the waiters are parked somewhere else,
     * which is what the queue length refuses to accept as queueing here.
     */
    private static int parkedAnywhere(List<Thread> workers) {
        int n = 0;
        for (Thread w : workers) {
            if (w == holder.get() || w.getState() != Thread.State.WAITING) {
                continue;
            }
            if (LockSupport.getBlocker(w) != null) {
                n++;
            }
        }
        return n;
    }

    private static RepositoryServiceImpl service() {
        RepositoryServiceImpl service = new RepositoryServiceImpl();
        service.setTypeManager(registry());
        service.setExceptionService(silentExceptions());
        return service;
    }

    /**
     * The type registry. A plain proxy rather than a mock, so that nothing between the shared
     * cache and this answer holds a monitor while the holder waits in it.
     *
     * <p>It takes a lock of its own for {@link #TYPE} — the type whose miss is held — as the
     * registry does while it rebuilds itself, and the first such answer waits inside that lock
     * for a virtual thread. Every OTHER type id is answered without that lock, as the registry
     * answers a type it already holds. Taking it for every answer was right while one lock
     * guarded all three caches; with the stripes it would hide the separation they give behind a
     * lock of the harness's own.
     */
    private static TypeManager registry() {
        return (TypeManager) Proxy.newProxyInstance(TypeManager.class.getClassLoader(),
                new Class<?>[] {TypeManager.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (!name.equals("getTypeDefinition") && !name.equals("getTypesChildren")
                            && !name.equals("getTypesDescendants")) {
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        return method.getReturnType().isPrimitive() ? 0 : null;
                    }
                    String typeId = typeIdOf(name, args);
                    boolean theHeldOne = TYPE.equals(typeId);
                    if (theHeldOne) {
                        registryLock.lock();
                    }
                    try {
                        if (theHeldOne && storm.get()
                                && holderClaimed.compareAndSet(false, true)) {
                            holder.set(Thread.currentThread());
                            holderEntered.countDown();
                            released.await(HOLDER_BOUND_MS, TimeUnit.MILLISECONDS);
                        }
                        built.incrementAndGet();
                        switch (name) {
                            case "getTypesChildren":
                                TypeDefinitionListImpl list = new TypeDefinitionListImpl();
                                list.setList(new ArrayList<>(List.of(customType(typeId))));
                                list.setHasMoreItems(false);
                                list.setNumItems(BigInteger.ONE);
                                return list;
                            case "getTypesDescendants":
                                List<TypeDefinitionContainer> tree = new ArrayList<>();
                                tree.add(new TypeDefinitionContainerImpl(customType(typeId)));
                                return Collections.unmodifiableList(tree);
                            default:
                                return customType(typeId);
                        }
                    } finally {
                        if (theHeldOne) {
                            registryLock.unlock();
                        }
                    }
                });
    }

    /**
     * The type id the registry was asked for. The three methods do not take it in the same
     * position — getTypesChildren takes the call context first — so it is read by signature
     * rather than by index alone.
     */
    private static String typeIdOf(String method, Object[] args) {
        int at = method.equals("getTypesChildren") ? 2 : 1;
        Object value = args != null && args.length > at ? args[at] : null;
        return value instanceof String ? (String) value : null;
    }

    /** The checks the service makes on its way out. None of them is what is measured here. */
    private static ExceptionService silentExceptions() {
        return (ExceptionService) Proxy.newProxyInstance(ExceptionService.class.getClassLoader(),
                new Class<?>[] {ExceptionService.class}, (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                });
    }

    /**
     * A type with a property of its own. Without one the service would read it back as a stale
     * entry and drop it again, and no two callers would ever see one instance.
     */
    private static TypeDefinition customType(String typeId) {
        DocumentTypeDefinitionImpl td = new DocumentTypeDefinitionImpl();
        td.setId(typeId);
        td.setLocalName(typeId);
        td.setQueryName(typeId);
        td.setDisplayName(typeId);
        td.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        td.setParentTypeId(BaseTypeId.CMIS_DOCUMENT.value());
        td.setIsCreatable(true);
        td.setIsVersionable(false);
        td.setContentStreamAllowed(ContentStreamAllowed.ALLOWED);
        PropertyStringDefinitionImpl prop = new PropertyStringDefinitionImpl();
        prop.setId("probe:note");
        prop.setLocalName("probe:note");
        prop.setQueryName("probe:note");
        prop.setDisplayName("probe:note");
        prop.setPropertyType(PropertyType.STRING);
        prop.setCardinality(Cardinality.SINGLE);
        prop.setUpdatability(Updatability.READWRITE);
        prop.setIsInherited(false);
        td.addPropertyDefinition(prop);
        return td;
    }
}
