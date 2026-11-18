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
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.impl.TypeManagerImpl;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * The child JVM of {@link TypeRegistryLockDoesNotStarveTheSchedulerTest} (R357). Not a test.
 *
 * <p>That test starts this class in a JVM with {@link #CARRIERS} carriers and no compensation,
 * once per scenario ({@code storm}, {@code nested}, {@code failure}), and reads the
 * {@code PROBE key=value} lines it prints. One JVM per scenario because the lock is
 * process-wide: a scenario that leaves it held would otherwise decide the next one. Every wait here is bounded, and the main thread
 * is a platform thread, so the measurements are printed even when the virtual threads are
 * stuck; {@code System.exit} then takes the stuck ones down with the JVM.
 *
 * <p>The shape is the one that stopped the server. One refresh holds the registry lock and,
 * inside it, waits for something only another virtual thread can give it (in production a
 * process-wide lock of its own, whose next owner had no carrier; here the sentinel). The other
 * workers queue on the same lock. A monitor pins a carrier per waiter, so the waiters can leave
 * the scheduler with nothing to run the sentinel on, and the holder waits for ever.
 */
public final class TypeRegistryLockProbe {

    static final String REPO = "probe";
    static final int CARRIERS = 4;
    static final int WORKERS = 8;
    /** How long anything is given that, with a working lock, takes milliseconds. */
    static final long DEADLINE_MS = 5_000L;
    /** The holder's own bound. Longer than every check made while it holds the lock. */
    static final long HOLDER_BOUND_MS = 30_000L;

    private static final AtomicBoolean storm = new AtomicBoolean(false);
    private static final AtomicBoolean holderClaimed = new AtomicBoolean(false);
    private static final AtomicReference<Thread> holder = new AtomicReference<>();
    private static final CountDownLatch holderEntered = new CountDownLatch(1);
    private static final CountDownLatch released = new CountDownLatch(1);
    private static final AtomicBoolean releasedBySentinel = new AtomicBoolean(false);
    private static final AtomicInteger inside = new AtomicInteger();
    private static final AtomicInteger maxInside = new AtomicInteger();
    private static final AtomicBoolean failKeys = new AtomicBoolean(false);
    /** Workers that have called refreshTypes(), the holder included. */
    private static final AtomicInteger calling = new AtomicInteger();
    /** Other workers that had called refreshTypes() by the time the holder left. */
    private static final AtomicInteger callersWhileHeld = new AtomicInteger(-1);

    private TypeRegistryLockProbe() {
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
            tm.refreshTypes(); // the refresh path's classes are loaded before anything contends
            Set<String> expected = typeIds();
            report("expectedTypes", expected.size());

            switch (scenario) {
                case "storm" -> storm(tm, expected);
                case "nested" -> nestedInit(tm);
                case "failure" -> failureReleasesTheLock(tm);
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

    /** {@link #WORKERS} refreshes released at once, one of them held inside the lock. */
    private static void storm(TypeManagerImpl tm, Set<String> expected) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            workers.add(Thread.ofVirtual().name("probe-worker-" + i).unstarted(() -> {
                try {
                    go.await();
                    calling.incrementAndGet();
                    tm.refreshTypes();
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

        // The others queue on the lock: every one of them parked, on one and the same blocker.
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        int parked = 0;
        do {
            parked = parkedOnOneBlocker(workers);
            if (parked == WORKERS - 1) {
                break;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < until);
        report("waitersParked", parked);

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

        long workersUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        for (Thread w : workers) {
            long left = TimeUnit.NANOSECONDS.toMillis(workersUntil - System.nanoTime());
            if (left > 0) {
                w.join(left);
            }
        }
        report("workersFinishedInTime", finished.get());

        // An unrelated request right after: a fresh virtual thread, and a read of the registry.
        CompletableFuture<Boolean> unrelated = new CompletableFuture<>();
        Thread.ofVirtual().name("probe-unrelated").start(() -> unrelated.complete(true));
        report("unrelatedAnswered", answered(unrelated));
        CompletableFuture<Boolean> read = new CompletableFuture<>();
        Thread.ofVirtual().name("probe-read").start(() -> {
            try {
                read.complete(tm.getTypeById(REPO, "cmis:document") != null);
            } catch (Throwable t) {
                read.completeExceptionally(t);
            }
        });
        report("registryReadAnswered", answered(read));

        // Rescue, so that nothing is left running: the holder is let go whether or not the
        // sentinel ever ran, and every worker is waited for.
        released.countDown();
        for (Thread w : workers) {
            w.join(HOLDER_BOUND_MS);
        }
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
        report("callersWhileHeld", callersWhileHeld.get());
        report("maxInside", maxInside.get());
        Set<String> after = typeIds();
        report("cacheWhole", after.equals(expected));
        report("cacheTypes", after.size());
        storm.set(false);
    }

    /** ensureInitialized() takes the lock and calls init(), which takes it again. */
    private static void nestedInit(TypeManagerImpl tm) throws Exception {
        setStatic("initialized", false);
        CompletableFuture<Boolean> nested = new CompletableFuture<>();
        Thread.ofVirtual().name("probe-nested").start(() -> {
            try {
                nested.complete(tm.getTypeById(REPO, "cmis:document") != null);
            } catch (Throwable t) {
                nested.completeExceptionally(t);
            }
        });
        report("nestedInitAnswered", answered(nested));
    }

    /** A refresh that throws inside the lock, then a refresh from another thread. */
    private static void failureReleasesTheLock(TypeManagerImpl tm) throws Exception {
        failKeys.set(true);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread failer = Thread.ofVirtual().name("probe-failer").start(() -> {
            try {
                tm.refreshTypes();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        failer.join(DEADLINE_MS);
        Throwable t = thrown.get();
        report("failureEscaped", t != null && String.valueOf(t.getMessage()).contains("No repositories found"));

        CompletableFuture<Boolean> next = new CompletableFuture<>();
        Thread.ofVirtual().name("probe-after-failure").start(() -> {
            try {
                tm.refreshTypes();
                next.complete(tm.getTypeById(REPO, "cmis:document") != null);
            } catch (Throwable e) {
                next.completeExceptionally(e);
            }
        });
        report("refreshAfterFailureAnswered", answered(next));
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

    /** Workers other than the holder that are parked, all on one blocker object. */
    private static int parkedOnOneBlocker(List<Thread> workers) {
        Object blocker = null;
        int n = 0;
        for (Thread w : workers) {
            if (w == holder.get()) {
                continue;
            }
            if (w.getState() != Thread.State.WAITING) {
                continue;
            }
            Object b = LockSupport.getBlocker(w);
            if (b == null) {
                continue;
            }
            if (blocker == null) {
                blocker = b;
            }
            if (b == blocker) {
                n++;
            }
        }
        return n;
    }

    private static Set<String> typeIds() throws Exception {
        Field f = TypeManagerImpl.class.getDeclaredField("TYPES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, TypeDefinitionContainer>> types =
                (Map<String, Map<String, TypeDefinitionContainer>>) f.get(null);
        Map<String, TypeDefinitionContainer> repo = types.get(REPO);
        return repo == null ? Collections.emptySet() : new TreeSet<>(repo.keySet());
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field f = TypeManagerImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static TypeManagerImpl registry() {
        TypeManagerImpl tm = new TypeManagerImpl();
        tm.setTypeService(typeService());
        tm.setRepositoryInfoMap(oneRepository());
        tm.setPropertyManager(readableConfiguration());
        return tm;
    }

    /**
     * The store. A plain proxy rather than a mock, so that nothing between the registry and
     * this answer holds a monitor while the holder waits in it.
     */
    private static TypeService typeService() {
        return (TypeService) Proxy.newProxyInstance(TypeService.class.getClassLoader(),
                new Class<?>[] {TypeService.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getTypeDefinitions")) {
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        return method.getReturnType().isPrimitive() ? 0 : null;
                    }
                    int now = inside.incrementAndGet();
                    maxInside.accumulateAndGet(now, Math::max);
                    try {
                        if (storm.get() && holderClaimed.compareAndSet(false, true)) {
                            holder.set(Thread.currentThread());
                            holderEntered.countDown();
                            released.await(HOLDER_BOUND_MS, TimeUnit.MILLISECONDS);
                            callersWhileHeld.set(calling.get() - 1);
                        }
                        return Collections.singletonList(customType());
                    } finally {
                        inside.decrementAndGet();
                    }
                });
    }

    private static RepositoryInfoMap oneRepository() {
        RepositoryInfoMap map = mock(RepositoryInfoMap.class, withSettings().stubOnly());
        Set<String> keys = new LinkedHashSet<>(List.of(REPO));
        when(map.keys()).thenAnswer(inv -> failKeys.getAndSet(false) ? Collections.emptySet() : keys);
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
        td.setTypeId("probe:custom");
        td.setId("probe:custom");
        td.setParentId("cmis:document");
        td.setBaseId(BaseTypeId.CMIS_DOCUMENT);
        return td;
    }
}
