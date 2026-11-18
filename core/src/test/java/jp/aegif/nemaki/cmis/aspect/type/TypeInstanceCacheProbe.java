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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.impl.TypeManagerImpl;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * The child JVM of {@link TypeInstanceCacheDoesNotStarveTheSchedulerTest} (R357). Not a test.
 *
 * <p>The registry's own instance-sharing cache used to be filled from inside
 * {@code ConcurrentHashMap.computeIfAbsent}, and that computation writes the log — a review of
 * the fix to the caches one layer out found it. Here the wait inside the computation is the real
 * one: a logback appender of this probe's own blocks the first thread that writes the line the
 * computation writes, which is what the production appender did when the container's log pipe
 * stalled. A thread that holds a monitor cannot be unmounted, so before the fix the holder pins
 * its carrier there and the requests for the same type pin one each on the bin's monitor behind
 * it; with the lock they all park and give their carriers up.
 *
 * <p>Every wait is bounded, the main thread is a platform thread, and {@code System.exit} takes
 * whatever is stuck down with the JVM.
 */
public final class TypeInstanceCacheProbe {

    static final String REPO = "probe";
    static final String TYPE = "probe:custom";
    static final int CARRIERS = 4;
    static final int WORKERS = 8;
    static final long DEADLINE_MS = 5_000L;
    static final long HOLDER_BOUND_MS = 30_000L;

    /** The line the computation writes, unguarded, after it has built the shared instance. */
    private static final String INSIDE_THE_COMPUTATION =
            "OBJECT_IDENTITY: After ensureConsistentPropertyDefinitions";

    private static final AtomicBoolean arm = new AtomicBoolean(false);
    private static final AtomicBoolean holderClaimed = new AtomicBoolean(false);
    private static final AtomicReference<Thread> holder = new AtomicReference<>();
    private static final CountDownLatch holderEntered = new CountDownLatch(1);
    private static final CountDownLatch released = new CountDownLatch(1);
    private static final AtomicBoolean releasedBySentinel = new AtomicBoolean(false);

    private TypeInstanceCacheProbe() {
    }

    static void report(String key, Object value) {
        System.out.println("PROBE " + key + "=" + value);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        String scenario = args.length == 1 ? args[0] : "";
        report("started", true);
        report("scenario", scenario);
        report("feature", Runtime.version().feature());
        report("processors", Runtime.getRuntime().availableProcessors());
        report("parallelism", System.getProperty("jdk.virtualThreadScheduler.parallelism"));
        report("maxPoolSize", System.getProperty("jdk.virtualThreadScheduler.maxPoolSize"));
        try {
            TypeManagerImpl tm = registry();
            tm.init();
            tm.refreshTypes();
            // The appender goes on BEFORE the warm-up, unarmed: it is also what measures that
            // the computation writes the line at all, and it cannot see a line written before
            // it was installed.
            installTheBlockingAppender();
            report("warmedUp", tm.getTypeDefinition(REPO, TYPE) != null);
            report("theComputationWritesThatLine", computationRuns.get() > 0);
            clearTheInstanceCache();
            report("startsOnAMiss", cached() == null);
            computationRuns.set(0); // the warm-up's own run is not the scenario's

            switch (scenario) {
                case "storm" -> storm(tm);
                case "identity" -> identity(tm);
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

    /** Eight requests for one shared instance the cache does not hold, released at once. */
    private static void storm(TypeManagerImpl tm) throws Exception {
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
                    answers.set(slot, tm.getTypeDefinition(REPO, TYPE));
                    finished.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        arm.set(true);
        for (Thread w : workers) {
            w.start();
        }
        go.countDown(); // all eight are released at once

        report("holderEntered", holderEntered.await(DEADLINE_MS, TimeUnit.MILLISECONDS));

        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        int queued = 0;
        do {
            queued = instanceLock().getQueueLength();
            if (queued == WORKERS - 1) {
                break;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < until);
        report("waitersOnTheInstanceLock", queued);

        CountDownLatch sentinelRan = new CountDownLatch(1);
        long sentinelStart = System.nanoTime();
        Thread sentinel = Thread.ofVirtual().name("probe-sentinel").start(() -> {
            sentinelRan.countDown();
            releasedBySentinel.set(true);
            released.countDown();
        });
        report("sentinelInTime", sentinelRan.await(DEADLINE_MS, TimeUnit.MILLISECONDS));
        report("sentinelMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentinelStart));

        joinByOneDeadline(workers, DEADLINE_MS);
        report("workersFinishedInTime", finished.get());

        CompletableFuture<Boolean> unrelated = new CompletableFuture<>();
        Thread.ofVirtual().name("probe-unrelated").start(() -> unrelated.complete(true));
        report("unrelatedAnswered", answered(unrelated));

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
        int answered = 0;
        for (int i = 0; i < WORKERS; i++) {
            if (answers.get(i) != null) {
                answered++;
            }
        }
        report("answersCollected", answered);
        report("computationRuns", computationRuns.get());
        arm.set(false);
    }

    /** The request that waited must be handed the instance the first one put in the map. */
    private static void identity(TypeManagerImpl tm) throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicReference<Object> firstAnswer = new AtomicReference<>();
        AtomicReference<Object> secondAnswer = new AtomicReference<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        arm.set(true);
        Thread first = Thread.ofVirtual().name("probe-first").start(() -> {
            try {
                firstAnswer.set(tm.getTypeDefinition(REPO, TYPE));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        report("holderEntered", holderEntered.await(DEADLINE_MS, TimeUnit.MILLISECONDS));

        Thread second = Thread.ofVirtual().name("probe-second").start(() -> {
            try {
                secondStarted.countDown();
                secondAnswer.set(tm.getTypeDefinition(REPO, TYPE));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        secondStarted.await(DEADLINE_MS, TimeUnit.MILLISECONDS);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        boolean queued = false;
        do {
            queued = instanceLock().getQueueLength() == 1;
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
        Object inTheCache = cached();
        report("bothAnswered", firstAnswer.get() != null && secondAnswer.get() != null);
        report("computationRuns", computationRuns.get());
        report("theAnswerIsTheCachedInstance", inTheCache != null
                && firstAnswer.get() == inTheCache && secondAnswer.get() == inTheCache);
        report("workerFailures", failures.size());
        for (Throwable t : failures) {
            report("workerFailure", t.toString().replace('\n', ' '));
        }
        arm.set(false);
    }

    // ------------------------------------------------------------------------------------

    /**
     * How many times the computation ran. Identity alone cannot tell this cache
     * working from this cache being bypassed: ensureConsistentPropertyDefinitions
     * returns its own argument on every path, and that argument is the registry's one
     * instance, so two callers get the same object whether the cache answered or the
     * instance was built again. A review of the first version measured that. What does
     * tell them apart is how often the computation ran.
     */
    private static final AtomicInteger computationRuns = new AtomicInteger();

    /**
     * The wait inside the computation, through the real logging path: commons-logging to SLF4J to
     * logback, the way production reaches the appender whose lock pinned the carrier. The first
     * thread writing the computation's own line is held; everything else passes straight through,
     * so this appender itself never becomes the thing under measurement.
     */
    private static void installTheBlockingAppender() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(TypeManagerImpl.class);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false); // the measurements stay readable
        // UnsynchronizedAppenderBase, not AppenderBase: the latter's doAppend is
        // synchronized, so holding one thread inside append() blocks every other
        // thread that logs anything on that monitor and pins its carrier - the
        // harness would then be the thing being measured. Production's appender is
        // an UnsynchronizedAppenderBase too (the thread dump of the stop shows it).
        // The lock caught this: the waiters came out BLOCKED instead of queued.
        UnsynchronizedAppenderBase<ILoggingEvent> blocking =
                new UnsynchronizedAppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (!String.valueOf(event.getFormattedMessage()).contains(INSIDE_THE_COMPUTATION)) {
                    return;
                }
                computationRuns.incrementAndGet();
                if (!arm.get() || !holderClaimed.compareAndSet(false, true)) {
                    return;
                }
                holder.set(Thread.currentThread());
                holderEntered.countDown();
                try {
                    released.await(HOLDER_BOUND_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        blocking.setName("r357-blocking");
        blocking.setContext(logger.getLoggerContext());
        blocking.start();
        logger.addAppender(blocking);
    }

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

    private static String answered(CompletableFuture<Boolean> f) {
        try {
            return String.valueOf(f.get(DEADLINE_MS, TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException e) {
            return "timeout";
        } catch (Exception e) {
            return "error:" + e.getCause();
        }
    }

    private static ReentrantLock instanceLock() {
        try {
            Field f = TypeManagerImpl.class.getDeclaredField("sharedInstanceLock");
            f.setAccessible(true);
            return (ReentrantLock) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("could not read the instance cache's lock", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, TypeDefinition>> instanceCache() {
        try {
            Field f = TypeManagerImpl.class.getDeclaredField("SHARED_TYPE_DEFINITIONS");
            f.setAccessible(true);
            return (Map<String, Map<String, TypeDefinition>>) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("could not read the instance cache", e);
        }
    }

    private static Object cached() {
        Map<String, TypeDefinition> repo = instanceCache().get(REPO);
        return repo == null ? null : repo.get(REPO + ":" + TYPE);
    }

    private static void clearTheInstanceCache() {
        instanceCache().clear();
    }

    private static TypeManagerImpl registry() {
        TypeManagerImpl tm = new TypeManagerImpl();
        tm.setTypeService(typeService());
        tm.setRepositoryInfoMap(oneRepository());
        tm.setPropertyManager(readableConfiguration());
        return tm;
    }

    /** The store. A plain proxy, so nothing between the registry and this answer holds a lock. */
    private static TypeService typeService() {
        return (TypeService) Proxy.newProxyInstance(TypeService.class.getClassLoader(),
                new Class<?>[] {TypeService.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getTypeDefinitions")) {
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        return method.getReturnType().isPrimitive() ? 0 : null;
                    }
                    return Collections.singletonList(customType());
                });
    }

    private static RepositoryInfoMap oneRepository() {
        RepositoryInfoMap map = mock(RepositoryInfoMap.class, withSettings().stubOnly());
        Set<String> keys = new LinkedHashSet<>(List.of(REPO));
        when(map.keys()).thenReturn(keys);
        RepositoryInfo info = mock(RepositoryInfo.class, withSettings().stubOnly());
        when(info.getId()).thenReturn(REPO);
        when(map.get(REPO)).thenReturn(info);
        return map;
    }

    private static PropertyManager readableConfiguration() {
        PropertyManager pm = mock(PropertyManager.class, withSettings().stubOnly());
        when(pm.readValue(anyString())).thenReturn(Updatability.READWRITE.value());
        when(pm.readValue(PropertyKey.BASETYPE_DOCUMENT_CONTENT_STREAM_ALLOWED))
                .thenReturn(ContentStreamAllowed.ALLOWED.value());
        return pm;
    }

    private static NemakiTypeDefinition customType() {
        NemakiTypeDefinition td = new NemakiTypeDefinition();
        td.setTypeId(TYPE);
        td.setId(TYPE);
        td.setParentId("cmis:document");
        td.setBaseId(BaseTypeId.CMIS_DOCUMENT);
        return td;
    }
}
