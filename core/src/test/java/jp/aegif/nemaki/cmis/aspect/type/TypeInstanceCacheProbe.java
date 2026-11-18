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
 * {@code ConcurrentHashMap.computeIfAbsent} — a review of the fix to the caches one layer out
 * found it, nothing had been measured there, and the rule that closed the measured stop was
 * applied to it rather than an argument made for why it was safe. This probe measures what that
 * rule buys: with the instance built under a lock, the request building it parks and the requests
 * behind it park too, so a virtual thread it waits for still gets a carrier; with the instance
 * built inside computeIfAbsent the builder holds the bin's monitor, cannot be unmounted, and the
 * others hold a monitor each behind it.
 *
 * <p><b>What makes the computation wait here is the registry's own type definition, not a log
 * line.</b> The first version held the builder on a {@code log.debug} line the computation writes
 * without a level guard, and raised the logger to DEBUG so that an appender would see it — a wait
 * production does not have, because {@code logback.xml} ships {@code jp.aegif.nemaki} at INFO and
 * drops every one of those lines before any appender (D35 Section 5). Measured in this fixture:
 * <b>the computation writes nothing at INFO or above</b>, reported every run as
 * {@code defaultLevelLinesInOneBuild}. So the lever is the object the computation reads — the
 * TypeDefinition this probe installs in the registry, whose {@code getPropertyDefinitions()}, the
 * first thing {@code ensureConsistentPropertyDefinitions} calls, holds the first caller until it
 * is released. What production can make the builder wait on there is the other map's bin monitors
 * inside {@code getSharedPropertyDefinition} and, on data whose inherited flag has to be
 * corrected, the warning that method writes; neither was measured stopping the server, and this
 * probe does not claim they were.
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

    /** The level the shipped configuration gives jp.aegif.nemaki. */
    private static final Level SHIPPED_LEVEL = Level.INFO;

    private static final AtomicBoolean arm = new AtomicBoolean(false);
    private static final AtomicBoolean holderClaimed = new AtomicBoolean(false);
    private static final AtomicReference<Thread> holder = new AtomicReference<>();
    private static final CountDownLatch holderEntered = new CountDownLatch(1);
    private static final CountDownLatch released = new CountDownLatch(1);
    private static final AtomicBoolean releasedBySentinel = new AtomicBoolean(false);

    /**
     * How many times the computation read the registry instance's properties — one read per
     * build, measured before the scenario. Identity alone cannot tell this cache working from
     * this cache being bypassed: ensureConsistentPropertyDefinitions returns its own argument on
     * every path, and that argument is the registry's one instance, so two callers get the same
     * object whether the cache answered or the instance was built again. A review of the first
     * version measured that. What does tell them apart is how often the computation ran.
     *
     * <p>Only the reads made INSIDE the computation are counted, and only such a read holds a
     * thread: one request reads these properties nine times, eight of them in the code around
     * the cache, and holding the first of those held a thread where no lock was taken yet — the
     * waiters then sailed past and the measurement said so (0 queued).
     */
    private static final AtomicInteger computationReads = new AtomicInteger();
    /** Every read, inside the computation or not, for the record. */
    private static final AtomicInteger propertyReads = new AtomicInteger();

    /** Lines the shipped configuration would hand an appender while a build runs. */
    private static final AtomicInteger defaultLevelLines = new AtomicInteger();
    private static final ConcurrentLinkedQueue<String> defaultLevelMessages =
            new ConcurrentLinkedQueue<>();

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
            // At the SHIPPED level, so that what it counts is what production's appender sees.
            installTheRecordingAppender();
            report("warmedUp", tm.getTypeDefinition(REPO, TYPE) != null);

            // From here the registry hands out a definition of this probe's own, which is what
            // makes the computation wait.
            installTheBlockingDefinition();
            report("theRegistryHoldsTheProbesDefinition", registryDefinition() instanceof Held);
            clearTheInstanceCache();
            propertyReads.set(0);
            defaultLevelLines.set(0);
            defaultLevelMessages.clear();
            report("buildAnswered", tm.getTypeDefinition(REPO, TYPE) != null);
            report("propertyReadsInOneBuild", propertyReads.get());
            report("computationReadsInOneBuild", computationReads.get());
            report("defaultLevelLinesInOneBuild", defaultLevelLines.get());
            report("defaultLevelLineSeen", defaultLevelMessages.peek());

            clearTheInstanceCache();
            report("startsOnAMiss", cached() == null);
            // The builds before the scenario are not the scenario's.
            propertyReads.set(0);
            computationReads.set(0);
            defaultLevelLines.set(0);

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
        report("propertyReads", propertyReads.get());
        report("computationReads", computationReads.get());
        report("defaultLevelLinesWhileHeld", defaultLevelLines.get());
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
        report("propertyReads", propertyReads.get());
        report("computationReads", computationReads.get());
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
     * The type definition the registry hands the computation, and the wait inside it. The
     * product's first move with it is to ask for its property definitions; the first caller to do
     * that while the scenario is armed is held there until it is released, which is the
     * production shape of a builder that waits inside the computation (the other map's bin
     * monitor, or the warning written when an inherited flag has to be corrected).
     */
    private static final class Held extends DocumentTypeDefinitionImpl {

        private static final long serialVersionUID = 1L;

        @Override
        public Map<String, PropertyDefinition<?>> getPropertyDefinitions() {
            propertyReads.incrementAndGet();
            if (!insideTheComputation()) {
                return super.getPropertyDefinitions();
            }
            computationReads.incrementAndGet();
            if (arm.get() && holderClaimed.compareAndSet(false, true)) {
                holder.set(Thread.currentThread());
                holderEntered.countDown();
                try {
                    released.await(HOLDER_BOUND_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.getPropertyDefinitions();
        }
    }

    /**
     * Whether this read is one the computation makes. The stack, not the product's lock: gating
     * on {@code sharedInstanceLock.isHeldByCurrentThread()} would read false under the very
     * sabotage these locks exist to catch — with the computation back inside
     * {@code computeIfAbsent} no such lock is taken — and the locks would then report the harness
     * as broken instead of firing. The computation is this method, in both shapes.
     */
    private static boolean insideTheComputation() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(
                f -> "ensureConsistentPropertyDefinitions".equals(f.getMethodName())));
    }

    /**
     * Counts what an appender would be handed while the computation runs, at the level the
     * container ships. Nothing is held here: the first version held a thread inside an appender,
     * and the lock caught what that cost (AppenderBase.doAppend is synchronized, so the waiters
     * came out BLOCKED and the harness was the thing being measured).
     */
    private static void installTheRecordingAppender() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(TypeManagerImpl.class);
        logger.setLevel(SHIPPED_LEVEL);
        logger.setAdditive(false); // the measurements stay readable
        UnsynchronizedAppenderBase<ILoggingEvent> recording =
                new UnsynchronizedAppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (!event.getLevel().isGreaterOrEqual(SHIPPED_LEVEL)) {
                    return;
                }
                defaultLevelLines.incrementAndGet();
                if (defaultLevelMessages.size() < 20) {
                    defaultLevelMessages.add(event.getLevel() + " | "
                            + String.valueOf(event.getFormattedMessage()).replace('\n', ' '));
                }
            }
        };
        recording.setName("r357-recording");
        recording.setContext(logger.getLoggerContext());
        recording.start();
        logger.addAppender(recording);
    }

    /** Puts this probe's definition in the registry, in place of the one it generated. */
    private static void installTheBlockingDefinition() {
        Held held = new Held();
        held.setId(TYPE);
        held.setLocalName(TYPE);
        held.setQueryName(TYPE);
        held.setDisplayName(TYPE);
        held.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        held.setParentTypeId(BaseTypeId.CMIS_DOCUMENT.value());
        held.setIsCreatable(true);
        held.setIsVersionable(false);
        held.setContentStreamAllowed(ContentStreamAllowed.ALLOWED);
        PropertyStringDefinitionImpl prop = new PropertyStringDefinitionImpl();
        prop.setId("probe:note");
        prop.setLocalName("probe:note");
        prop.setQueryName("probe:note");
        prop.setDisplayName("probe:note");
        prop.setPropertyType(PropertyType.STRING);
        prop.setCardinality(Cardinality.SINGLE);
        prop.setUpdatability(Updatability.READWRITE);
        prop.setIsInherited(false);
        held.addPropertyDefinition(prop);
        types().put(TYPE, new TypeDefinitionContainerImpl(held));
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

    @SuppressWarnings("unchecked")
    private static Map<String, TypeDefinitionContainer> types() {
        Map<String, Map<String, TypeDefinitionContainer>> all;
        try {
            Field f = TypeManagerImpl.class.getDeclaredField("TYPES");
            f.setAccessible(true);
            all = (Map<String, Map<String, TypeDefinitionContainer>>) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("could not read the registry's types", e);
        }
        Map<String, TypeDefinitionContainer> repo = all == null ? null : all.get(REPO);
        if (repo == null) {
            throw new IllegalStateException("the registry holds no types for " + REPO);
        }
        return repo;
    }

    private static TypeDefinition registryDefinition() {
        TypeDefinitionContainer container = types().get(TYPE);
        return container == null ? null : container.getTypeDefinition();
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
