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
 * because the caches and their lock are process-wide. Every wait here is bounded and the main
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
 */
public final class SharedTypeCacheProbe {

    static final String REPO = "probe";
    static final String TYPE = "probe:custom";
    static final int CARRIERS = 4;
    static final int WORKERS = 8;
    /** How long anything is given that, with a working lock, takes milliseconds. */
    static final long DEADLINE_MS = 5_000L;
    /** The holder's own bound. Longer than every check made while it holds the registry. */
    static final long HOLDER_BOUND_MS = 30_000L;

    /** Which of the three caches a run drives. */
    enum Entry {
        definition("getTypeDefinition", "SHARED_TYPE_DEFINITIONS", REPO + ":" + TYPE),
        children("getTypesChildren", "SHARED_TYPE_CHILDREN", REPO + ":" + TYPE + ":true:null:null"),
        descendants("getTypesDescendants", "SHARED_TYPE_DESCENDANTS", REPO + ":" + TYPE + ":1:true");

        /** The registry method answering this cache's miss. */
        final String registryMethod;
        private final String field;
        private final String key;

        Entry(String registryMethod, String field, String key) {
            this.registryMethod = registryMethod;
            this.field = field;
            this.key = key;
        }

        /** The request, through the service's public door. */
        Object ask(RepositoryServiceImpl service) {
            switch (this) {
                case definition:
                    return service.getTypeDefinition(null, REPO, TYPE, null);
                case children:
                    return service.getTypeChildren(null, REPO, TYPE, Boolean.TRUE, null, null, null);
                default:
                    return service.getTypeDescendants(null, REPO, TYPE, BigInteger.ONE,
                            Boolean.TRUE, null);
            }
        }

        /** What the cache holds for that request, read straight out of the static map. */
        Object cached() {
            try {
                Field f = RepositoryServiceImpl.class.getDeclaredField(field);
                f.setAccessible(true);
                return ((Map<?, ?>) f.get(null)).get(key);
            } catch (Exception e) {
                throw new IllegalStateException("could not read " + field, e);
            }
        }
    }

    /**
     * The registry's own lock. TypeManagerImpl's stands here: answering a miss takes it, and the
     * first answer waits inside it for something only another virtual thread can give it.
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
            RepositoryServiceImpl service = service();
            // The classes of the answer path are loaded before anything contends, and the
            // warm-up's own entry is dropped again so that the scenario starts on a miss.
            report("warmedUp", entry.ask(service) != null);
            clearTheCaches();
            built.set(0);
            report("startsOnAMiss", entry.cached() == null);

            switch (scenario) {
                case "storm" -> storm(service, entry);
                case "identity" -> identity(service, entry);
                case "clear" -> clearDuringAMiss(service, entry);
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
                    answers.set(slot, entry.ask(service));
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

        // The others queue behind it ON THE CACHE'S OWN LOCK. Counting "parked on one and the
        // same blocker" was not enough: a sabotage that takes the cache lock away leaves every
        // miss queueing on the registry's lock instead, and seven threads parked on that sibling
        // would have satisfied the count (review of the first version). The lock itself is asked.
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        int queued = 0;
        do {
            queued = cacheLock().getQueueLength();
            if (queued == WORKERS - 1) {
                break;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < until);
        report("waitersOnTheCacheLock", queued);
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
                read.complete(entry.ask(service) != null);
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
                firstAnswer.set(entry.ask(service));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        report("holderEntered", holderEntered.await(DEADLINE_MS, TimeUnit.MILLISECONDS));

        Thread second = Thread.ofVirtual().name("probe-second").start(() -> {
            try {
                secondStarted.countDown();
                secondAnswer.set(entry.ask(service));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        secondStarted.await(DEADLINE_MS, TimeUnit.MILLISECONDS);

        // The second one is given a bounded moment to queue on the cache's lock.
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        boolean queued = false;
        do {
            queued = cacheLock().getQueueLength() == 1;
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
        Object inTheCache = entry.cached();
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
     */
    private static void clearDuringAMiss(RepositoryServiceImpl service, Entry entry)
            throws Exception {
        AtomicReference<Object> answer = new AtomicReference<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        storm.set(true);
        Thread miss = Thread.ofVirtual().name("probe-miss").start(() -> {
            try {
                answer.set(entry.ask(service));
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
        // A bounded moment for the clear either to queue on the lock or to run straight through.
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000L);
        while (System.nanoTime() < until && !cleared.isDone() && cacheLock().getQueueLength() == 0) {
            Thread.sleep(10L);
        }
        report("clearWaitedForTheLock", cacheLock().getQueueLength() == 1 && !cleared.isDone());

        released.countDown();
        miss.join(HOLDER_BOUND_MS);
        report("missAnswered", answer.get() != null);
        report("clearAnswered", answered(cleared));
        report("theClearSurvivedTheMiss", entry.cached() == null);
        report("workerFailures", failures.size());
        for (Throwable t : failures) {
            report("workerFailure", t.toString().replace('\n', ' '));
        }
        storm.set(false);
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

    private static int answersCollected(AtomicReferenceArray<Object> answers) {
        int n = 0;
        for (int i = 0; i < answers.length(); i++) {
            if (answers.get(i) != null) {
                n++;
            }
        }
        return n;
    }

    /** The product's own lock, so that what the waiters queue on is the thing under measurement. */
    private static ReentrantLock cacheLock() {
        try {
            Field f = RepositoryServiceImpl.class.getDeclaredField("SHARED_TYPE_CACHE_LOCK");
            f.setAccessible(true);
            return (ReentrantLock) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("could not read the shared caches' lock", e);
        }
    }

    private static void clearTheCaches() throws Exception {
        for (Entry e : Entry.values()) {
            Field f = RepositoryServiceImpl.class.getDeclaredField(e.field);
            f.setAccessible(true);
            ((Map<?, ?>) f.get(null)).clear();
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
     * Workers parked on anything at all, the holder apart — reported beside the lock's own queue
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
     * cache and this answer holds a monitor while the holder waits in it. It takes a lock of its
     * own, as the registry does, and the first answer waits inside that lock for a virtual
     * thread.
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
                    registryLock.lock();
                    try {
                        if (storm.get() && holderClaimed.compareAndSet(false, true)) {
                            holder.set(Thread.currentThread());
                            holderEntered.countDown();
                            released.await(HOLDER_BOUND_MS, TimeUnit.MILLISECONDS);
                        }
                        built.incrementAndGet();
                        switch (name) {
                            case "getTypesChildren":
                                TypeDefinitionListImpl list = new TypeDefinitionListImpl();
                                list.setList(new ArrayList<>(List.of(customType())));
                                list.setHasMoreItems(false);
                                list.setNumItems(BigInteger.ONE);
                                return list;
                            case "getTypesDescendants":
                                List<TypeDefinitionContainer> tree = new ArrayList<>();
                                tree.add(new TypeDefinitionContainerImpl(customType()));
                                return Collections.unmodifiableList(tree);
                            default:
                                return customType();
                        }
                    } finally {
                        registryLock.unlock();
                    }
                });
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
    private static TypeDefinition customType() {
        DocumentTypeDefinitionImpl td = new DocumentTypeDefinitionImpl();
        td.setId(TYPE);
        td.setLocalName(TYPE);
        td.setQueryName(TYPE);
        td.setDisplayName(TYPE);
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
