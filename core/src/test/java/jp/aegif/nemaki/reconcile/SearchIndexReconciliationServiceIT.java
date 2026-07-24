package jp.aegif.nemaki.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * Integration tests for {@link SearchIndexReconciliationService} against a LIVE
 * CouchDB (the concurrency / durability guarantees the Mockito scheduler tests
 * cannot cover). Gated: if {@code nemaki_conf} is not reachable the whole class is
 * skipped ({@code assumeTrue}), so it is safe in an offline build. Run against the
 * dev stack with:
 *
 * <pre>mvn -o test -Dtest=SearchIndexReconciliationServiceIT -f core/pom.xml -Pdevelopment
 *   -Dnemaki.test.couchdb.url=http://localhost:5984
 *   -Dnemaki.test.couchdb.user=admin -Dnemaki.test.couchdb.password=password</pre>
 *
 * Every test isolates its documents under a unique {@code repositoryId} prefix and
 * cleans them up, so it never touches real reconciliation entries.
 */
public class SearchIndexReconciliationServiceIT {

    private static Cloudant cloudant;
    private static CloudantClientWrapper confWrapper;
    private static boolean available;

    private SearchIndexReconciliationService svc;
    private String repo;

    @BeforeAll
    static void connect() {
        String url = cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL", "http://localhost:5984");
        String user = cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin");
        String pass = cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD", "password");
        try {
            BasicAuthenticator auth = new BasicAuthenticator.Builder().username(user).password(pass).build();
            cloudant = new Cloudant("cloudant-service", auth);
            cloudant.setServiceUrl(url);
            confWrapper = new CloudantClientWrapper(cloudant, SystemConst.NEMAKI_CONF_DB, new ObjectMapper());
            // reachability probe: nemaki_conf must exist
            cloudant.getDatabaseInformation(
                    new com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions.Builder()
                            .db(SystemConst.NEMAKI_CONF_DB).build()).execute();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        // In CI (nemaki.test.couchdb.required=true) an unreachable nemaki_conf is a
        // HARD FAILURE, not a silent skip — a dedicated CI job spins up CouchDB/Solr,
        // so a skip there would falsely green-light the re-drive queue's concurrency
        // guarantees. Locally the flag defaults false, so the class still skips when
        // there is no dev stack.
        if (!available && Boolean.parseBoolean(
                cfg("nemaki.test.couchdb.required", "NEMAKI_TEST_COUCHDB_REQUIRED", "false"))) {
            throw new IllegalStateException(
                    "nemaki.test.couchdb.required=true but nemaki_conf is not reachable — "
                    + "the reconciliation IT cannot run (start CouchDB/Solr + Setup Wizard first)");
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(available, "nemaki_conf not reachable — skipping reconciliation IT");
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(confWrapper);
        svc = new SearchIndexReconciliationService();
        svc.setConnectorPool(pool);
        repo = "sir-it-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        if (svc == null || !available) return;
        for (SearchIndexAclReindexTask t : svc.list(2000)) {
            if (repo.equals(t.getRepositoryId())) {
                svc.forceDeleteByTaskId(t.getTaskId());
            }
        }
    }

    // ── dedupe ─────────────────────────────────────────────────────

    @Test
    void enqueueIsIdempotentPerObjectAndBumpsGeneration() {
        svc.enqueue(repo, "objA", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        svc.enqueue(repo, "objA", SearchIndexAclReindexTask.Reason.RELATIONSHIP_REFRESH_FAILURE);

        List<SearchIndexAclReindexTask> forRepo = svc.list(2000).stream()
                .filter(t -> repo.equals(t.getRepositoryId())).toList();
        assertEquals(1, forRepo.size(), "same (repo,object) must collapse to one document");
        assertEquals(2, forRepo.get(0).getGeneration(), "a second enqueue bumps the generation");
    }

    @Test
    void concurrentEnqueueCollapsesToOneDocument() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    svc.enqueue(repo, "objB", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "enqueue threads did not finish");

        long count = svc.list(2000).stream().filter(t -> repo.equals(t.getRepositoryId())).count();
        assertEquals(1, count, "concurrent enqueues for one object must not create duplicates");
    }

    @Test
    void aclAndPurgeAreIndependentTasksNotOneMergedDoc() {
        // ACL_REINDEX and RAG_PURGE for the SAME object are separate documents
        // (separate deterministic-id namespaces): a completed purge must not delete
        // the unfinished ACL obligation, and vice versa. Enqueue both, in either
        // order, and observe TWO distinct tasks both surviving.
        svc.enqueue(repo, "objOp1", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        svc.enqueue(repo, "objOp1", SearchIndexAclReindexTask.Reason.PWC_PURGE_FAILURE,
                SearchIndexAclReindexTask.Operation.RAG_PURGE);

        List<SearchIndexAclReindexTask> forObj = svc.list(2000).stream()
                .filter(t -> repo.equals(t.getRepositoryId()) && "objOp1".equals(t.getObjectId()))
                .toList();
        assertEquals(2, forObj.size(), "ACL and PURGE for one object must be two independent tasks");
        assertTrue(forObj.stream().anyMatch(t ->
                SearchIndexAclReindexTask.Operation.ACL_REINDEX.equals(t.getEffectiveOperation())));
        assertTrue(forObj.stream().anyMatch(t ->
                SearchIndexAclReindexTask.Operation.RAG_PURGE.equals(t.getEffectiveOperation())));
    }

    @Test
    void enqueueOrThrowSucceedsWhenDurable() {
        // The durable variant for security obligations returns (no throw) when the
        // task is persisted, and the task exists afterwards.
        svc.enqueueOrThrow(repo, "objOr1", SearchIndexAclReindexTask.Reason.PWC_PURGE_FAILURE,
                SearchIndexAclReindexTask.Operation.RAG_PURGE);
        long n = svc.list(2000).stream()
                .filter(t -> repo.equals(t.getRepositoryId()) && "objOr1".equals(t.getObjectId())
                        && SearchIndexAclReindexTask.Operation.RAG_PURGE.equals(t.getEffectiveOperation()))
                .count();
        assertEquals(1, n, "enqueueOrThrow must durably persist the purge task");
    }

    // ── CAS claim exclusivity ──────────────────────────────────────

    @Test
    void claimIsExclusive() {
        svc.enqueue(repo, "objC", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        String taskId = taskIdFor("objC");

        SearchIndexAclReindexTask first = svc.claimForManualRetry(taskId, "node-1", 60_000L);
        SearchIndexAclReindexTask second = svc.claimForManualRetry(taskId, "node-2", 60_000L);

        assertNotNull(first, "the first claim must succeed");
        assertNull(second, "a second claim on an actively-leased task must fail");
        assertEquals(SearchIndexAclReindexTask.Status.LEASED, first.getStatus());
    }

    @Test
    void concurrentClaimOnlyOneWins() throws Exception {
        svc.enqueue(repo, "objC2", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        String taskId = taskIdFor("objC2");
        int threads = 6;
        AtomicInteger winners = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final String node = "node-" + i;
            new Thread(() -> {
                try {
                    start.await();
                    if (svc.claimForManualRetry(taskId, node, 60_000L) != null) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(1, winners.get(), "exactly one concurrent claimer must win the CAS lease");
    }

    // ── lease renewal / loss detection ─────────────────────────────

    @Test
    void renewDetectsLeaseLoss() {
        svc.enqueue(repo, "objD", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        String taskId = taskIdFor("objD");
        SearchIndexAclReindexTask mine = svc.claimForManualRetry(taskId, "node-A", 60_000L);
        assertNotNull(mine);

        // Another worker modifies the same doc (bumps its _rev), invalidating my rev.
        SearchIndexAclReindexTask other = svc.getByTaskId(taskId);
        assertTrue(svc.retryLater(other, 0L), "other worker's CAS write should succeed");

        // Force a renewal (lease window shorter than remaining -> a real CAS write) and
        // observe the loss: my stale rev now conflicts.
        assertFalse(svc.renewLeaseIfNeeded(mine, 1_000_000_000L),
                "renewal on a stale rev must report the lease as lost");
    }

    @Test
    void renewRestoresLocalExpiryOnCasFailure() {
        svc.enqueue(repo, "objR", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        String taskId = taskIdFor("objR");
        SearchIndexAclReindexTask mine = svc.claimForManualRetry(taskId, "node-A", 60_000L);
        assertNotNull(mine);

        // Another worker reclaims the lease (bumps the rev), so my rev is stale.
        SearchIndexAclReindexTask other = svc.getByTaskId(taskId);
        assertTrue(svc.retryLater(other, 0L));

        long before = mine.getLeaseExpiresAt();
        // Huge lease forces a real CAS (half > remaining); the CAS fails on the stale rev.
        assertFalse(svc.renewLeaseIfNeeded(mine, 1_000_000_000L),
                "a renewal on a reclaimed lease must report the lease lost");
        assertEquals(before, mine.getLeaseExpiresAt(),
                "a failed renewal must NOT leave the local expiry in the future — otherwise the "
                + "next checkpoint would see 'plenty of time left', skip the CAS, and wrongly "
                + "report the lease as still held (defeating cooperative fencing).");
    }

    @Test
    void fenceGuardLatchesFalsePermanentlyOnceLost() {
        svc.enqueue(repo, "objG2", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        String taskId = taskIdFor("objG2");
        SearchIndexAclReindexTask mine = svc.claimForManualRetry(taskId, "node-A", 60_000L);
        assertNotNull(mine);
        java.util.function.BooleanSupplier guard = svc.fenceGuard(mine, 60_000L);

        // Another worker reclaims the lease, invalidating my rev.
        SearchIndexAclReindexTask other = svc.getByTaskId(taskId);
        assertTrue(svc.retryLater(other, 0L));

        // Force the heartbeat to fire (expiry in the past) so the guard actually CASes,
        // then observe the loss.
        mine.setLeaseExpiresAt(0L);
        assertFalse(guard.getAsBoolean(), "the checkpoint after a reclaim must report the lease lost");

        // Even if the local expiry is (wrongly) reset far into the future, the guard must
        // STAY false forever — a worker that lost its lease must never resume writing.
        mine.setLeaseExpiresAt(Long.MAX_VALUE);
        assertFalse(guard.getAsBoolean(), "the guard must latch false permanently once the lease is lost");
    }

    // ── complete CAS vs a concurrent new event ─────────────────────

    @Test
    void completeCasFailsAfterConcurrentEnqueue() {
        svc.enqueue(repo, "objE", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        String taskId = taskIdFor("objE");
        SearchIndexAclReindexTask claimed = svc.claimForManualRetry(taskId, "node-P", 60_000L);
        assertNotNull(claimed);

        // A NEW failure event arrives mid-flight (bumps generation, changes the rev).
        svc.enqueue(repo, "objE", SearchIndexAclReindexTask.Reason.RELATIONSHIP_REFRESH_FAILURE);

        // The poller's CAS delete on the now-stale claim rev must fail, and the fresh
        // PENDING entry must survive.
        assertFalse(svc.complete(claimed), "complete on a stale rev must not delete a newer event");
        SearchIndexAclReindexTask survivor = svc.getByTaskId(taskId);
        assertNotNull(survivor, "the fresh failure event must survive");
        assertEquals(SearchIndexAclReindexTask.Status.PENDING, survivor.getStatus());
    }

    // ── admin list status filter (Mango selector, not post-limit) ──

    @Test
    void listStatusFilterUsesSelector() {
        svc.enqueue(repo, "objF", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        svc.enqueue(repo, "objG", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        SearchIndexAclReindexTask f = svc.claimForManualRetry(taskIdFor("objF"), "n", 60_000L);
        svc.markFailed(f, "boom");

        List<SearchIndexAclReindexTask> failed = svc.list("FAILED", 2000).stream()
                .filter(t -> repo.equals(t.getRepositoryId())).toList();
        assertEquals(1, failed.size());
        assertEquals("objF", failed.get(0).getObjectId());
    }

    // ── metrics ────────────────────────────────────────────────────

    @Test
    void metricsReportsCountsAndAvailability() {
        svc.enqueue(repo, "objH", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        var m = svc.metrics();
        assertEquals(Boolean.TRUE, m.get("queueMetricsAvailable"));
        assertTrue(((Number) m.get("pending")).intValue() >= 1);
        assertTrue(m.containsKey("oldestPendingCreatedAgeMs"));
        assertTrue(m.containsKey("mostOverduePendingMs"));
        assertTrue(m.containsKey("enqueueFailureCount"));
    }

    // ── helpers ────────────────────────────────────────────────────

    private String taskIdFor(String objectId) {
        return svc.list(2000).stream()
                .filter(t -> repo.equals(t.getRepositoryId()) && objectId.equals(t.getObjectId()))
                .map(SearchIndexAclReindexTask::getTaskId)
                .findFirst().orElse(null);
    }

    private static String cfg(String sysProp, String envVar, String dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
