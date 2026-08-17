package jp.aegif.nemaki.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.config.ObjectMapperFactory;

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
            confWrapper = new CloudantClientWrapper(cloudant, SystemConst.NEMAKI_CONF_DB, ObjectMapperFactory.createDefaultObjectMapper());
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
        // A test that deliberately corrupts a stored obligation must not leave it behind. `list`
        // skips corrupt entries (gate 4 containment), so draining by taskId cannot reach them —
        // drain the healthy ones, then remove the corrupt ones through the escape hatch.
        for (SearchIndexAclReindexTask t : svc.list(2000)) {
            if (repo.equals(t.getRepositoryId())) {
                svc.forceDeleteByTaskId(t.getTaskId());
            }
        }
        for (SearchIndexReconciliationService.CorruptTaskRef c : svc.listCorrupt(2000)) {
            if (repo.equals(c.getRepositoryId())) {
                svc.deleteCorruptByDocId(c.getDocId());
            }
        }
        purgeCorruptTasks(); // belt and braces for a doc whose repositoryId itself is unreadable
    }

    /** Delete every IT task straight from CouchDB, bypassing the (throwing) deserializer. */
    private void purgeCorruptTasks() {
        try {
            var find = new com.ibm.cloud.cloudant.v1.model.PostFindOptions.Builder()
                    .db(confWrapper.getDatabaseName())
                    .selector(java.util.Map.of("type",
                            java.util.Map.of("$eq", SearchIndexAclReindexTask.DOC_TYPE)))
                    .limit(2000L).build();
            for (var d : confWrapper.getClient().postFind(find).execute().getResult().getDocs()) {
                // getId()/getRev(), NOT get("_id"): the SDK maps _id/_rev onto the typed fields, so
                // the dynamic accessor returns null and this loop silently deleted nothing.
                String id = d.getId();
                if (id == null || !id.contains("sir-it-")) continue;
                confWrapper.getClient().deleteDocument(
                        new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                                .db(confWrapper.getDatabaseName()).docId(id)
                                .rev(d.getRev()).build()).execute();
            }
        } catch (Exception ignore) { /* best effort */ }
    }

    // ── epoch obligation (increment 7a — the ACK primitive) ────────

    private SearchIndexAclReindexTask onlyTaskFor(String objectId) {
        List<SearchIndexAclReindexTask> found = svc.list(2000).stream()
                .filter(t -> repo.equals(t.getRepositoryId()) && objectId.equals(t.getObjectId()))
                .toList();
        assertEquals(1, found.size(), "expected exactly one task for " + objectId);
        return found.get(0);
    }

    /** A fresh enqueue records the obligation; a later, LOWER one must not lower it. */
    @Test
    void theEpochObligationMergesMONOTONICALLY() {
        svc.enqueueOrThrow(repo, "objE", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
                SearchIndexAclReindexTask.Operation.ACL_REINDEX, 7L);
        assertEquals(7L, onlyTaskFor("objE").getMinRequiredEpoch());

        svc.enqueueOrThrow(repo, "objE", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
                SearchIndexAclReindexTask.Operation.ACL_REINDEX, 3L);
        assertEquals(7L, onlyTaskFor("objE").getMinRequiredEpoch(),
                "a LOWER obligation must never replace a higher one — the ACK would then pass for "
                        + "an epoch nobody is going to reconcile");

        svc.enqueueOrThrow(repo, "objE", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
                SearchIndexAclReindexTask.Operation.ACL_REINDEX, 9L);
        assertEquals(9L, onlyTaskFor("objE").getMinRequiredEpoch(), "a HIGHER obligation raises it");
    }

    /** Best-effort refresh carries no obligation and must not disturb one already recorded. */
    @Test
    void aBestEffortEnqueueDoesNotLowerAnExistingObligation() {
        svc.enqueueOrThrow(repo, "objB", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
                SearchIndexAclReindexTask.Operation.ACL_REINDEX, 5L);
        svc.enqueue(repo, "objB", SearchIndexAclReindexTask.Reason.RELATIONSHIP_REFRESH_FAILURE);

        SearchIndexAclReindexTask t = onlyTaskFor("objB");
        assertEquals(5L, t.getMinRequiredEpoch(), "best-effort enqueue passes 0 and must merge as max");
        assertEquals(2, t.getGeneration(), "it is still a real enqueue event");
        assertEquals(SearchIndexAclReindexTask.Status.PENDING, t.getStatus());
    }

    /**
     * A v1 task (no field at all) reads as 0 — deliberately fail-closed for the ACK, because the
     * counter's first allocation is 1, so {@code 0 >= finalizedEpoch} is false for every real epoch
     * and the outbox marker survives until a fresh enqueue raises it.
     */
    @Test
    void aV1TaskWithoutTheFieldReadsAsZERO() {
        svc.enqueue(repo, "objV1", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        assertEquals(0L, onlyTaskFor("objV1").getMinRequiredEpoch());
    }

    /** RAG_PURGE is an unconditional deletion, not a point on the ACL timeline. */
    @Test
    void aPurgeMayNotCarryAnEpochObligation() {
        assertThrows(IllegalArgumentException.class, () ->
                svc.enqueueOrThrow(repo, "objP", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
                        SearchIndexAclReindexTask.Operation.RAG_PURGE, 4L));
    }

    /**
     * A PRESENT but corrupt obligation must be REJECTED and must SURFACE — not flattened to 0, and
     * not swallowed into "no such task".
     *
     * <p>Both halves were found by running mutations rather than by reasoning. Flattening makes a
     * damaged task indistinguishable from a legitimately old v1 one. Swallowing is worse: the task
     * vanishes from list / claim / metrics, so the obligation is neither honoured nor visible.
     */
    @Test
    void aCorruptObligationIsREJECTEDAndSURFACES_notReadAsZeroAndNotSwallowed() {
        svc.enqueue(repo, "objC", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        String docId = SearchIndexAclReindexTask.deterministicId(
                repo, "objC", SearchIndexAclReindexTask.Operation.ACL_REINDEX);

        for (Object corrupt : new Object[] { "3", 1.5d, -1 }) {
            forceStoredField(docId, corrupt);
            List<SearchIndexReconciliationService.CorruptTaskRef> reported = svc.listCorrupt(2000)
                    .stream().filter(c -> docId.equals(c.getDocId())).toList();
            assertEquals(1, reported.size(),
                    "a stored minRequiredEpoch of " + corrupt + " must be rejected, not read as 0");
            assertTrue(reported.get(0).getReason().contains("minRequiredEpoch"),
                    reported.get(0).getReason());
            assertTrue(svc.list(2000).stream().noneMatch(t -> "objC".equals(t.getObjectId())),
                    "and it must NOT be handed out as if it were a healthy task");
        }
        forceStoredField(docId, 2);   // repair so cleanUp() can drain the task
        assertEquals(2L, svc.list(2000).stream()
                .filter(x -> repo.equals(x.getRepositoryId())).findFirst().orElseThrow()
                .getMinRequiredEpoch());
    }

    /**
     * Gate 4 §5.1 item 1, against a REAL document — the half a mocked scheduler cannot see.
     *
     * <p>The scheduler's quarantine branch calls the service and the unit test verifies WHICH method,
     * but only the stored document says whether {@code attempts} moved. It matters: a subtree blocked
     * for a day would otherwise come out of the quarantine with {@code attempts} at the cap, so the
     * first ordinary failure afterwards marks it terminal-FAILED — the abandonment §5.1 item 1 exists
     * to prevent, deferred by exactly one step. (Written after the review found the first
     * implementation calling {@code retryLater}, which counts.)
     */
    @Test
    void aBlockedRetryDoesNotCONSUMEAnAttempt() {
        svc.enqueue(repo, "objBlocked", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        SearchIndexAclReindexTask claimed = svc.claimDue(50, "it-node", 60_000L).stream()
                .filter(t -> repo.equals(t.getRepositoryId())).findFirst().orElseThrow();
        int before = claimed.getAttempts();

        assertTrue(svc.retryLaterWithoutCountingAnAttempt(claimed, 3600_000L));

        SearchIndexAclReindexTask stored = onlyTaskFor("objBlocked");
        assertEquals(before, stored.getAttempts(),
                "a task that never got to run must not have the drive counted against it");
        assertEquals(SearchIndexAclReindexTask.Status.PENDING, stored.getStatus(),
                "and it must be re-opened, not left LEASED to a worker that gave up");
        assertNull(stored.getLeaseOwner(), "the lease is released so any replica can pick it up");
        assertTrue(stored.getNextAttemptAt() > System.currentTimeMillis() + 1_000_000L,
                "under the capped backoff, not the normal poll interval");

        // The contrast that gives the assertion its meaning: an ordinary retry DOES count.
        SearchIndexAclReindexTask again = svc.claimDue(50, "it-node", 60_000L).stream()
                .filter(t -> repo.equals(t.getRepositoryId())).findFirst().orElse(null);
        if (again != null) { // (only claimable if the backoff has elapsed — it has not, so skip)
            svc.retryLater(again, 0L);
            assertEquals(before + 1, onlyTaskFor("objBlocked").getAttempts());
        }
    }

    /**
     * §11.4 inline settle (approved D6): consume the own-node obligation ONLY when this write
     * actually covered it — a concurrent newer mutation may have merged a HIGHER obligation, and a
     * poller mid-re-drive owns its own terminus.
     */
    @Test
    void settleIfCoveredConsumesOnlyACoveredUnleasedObligation() {
        svc.enqueueOrThrow(repo, "objS", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
                SearchIndexAclReindexTask.Operation.ACL_REINDEX, 5L);

        assertFalse(svc.settleIfCovered(repo, "objS", 4L),
                "a HIGHER obligation (5) must not be consumed by a write that satisfied only 4");
        assertNotNull(onlyTaskFor("objS"), "and the task must still exist");

        // A poller holding a live lease owns the terminus — hands off.
        SearchIndexAclReindexTask claimed = svc.claimDue(50, "it-node", 60_000L).stream()
                .filter(t -> repo.equals(t.getRepositoryId())).findFirst().orElseThrow();
        assertFalse(svc.settleIfCovered(repo, "objS", 9L), "LEASED with a live lease → poller owns it");
        assertTrue(svc.retryLaterWithoutCountingAnAttempt(claimed, 0L)); // release the lease

        assertTrue(svc.settleIfCovered(repo, "objS", 5L), "covered + unleased → consumed");
        assertTrue(svc.list(2000).stream().noneMatch(t -> "objS".equals(t.getObjectId())));
        assertTrue(svc.settleIfCovered(repo, "objS", 5L), "nothing outstanding → trivially settled");
    }

    /**
     * Gate 4, absorbed 7a residual: ONE corrupt entry must not stall the queue for everything else.
     *
     * <p>Rejecting corruption (above) originally meant THROWING out of the shared deserializer — and
     * every read path goes through it, so a single damaged document broke {@code list},
     * {@code claimDue} and {@code metrics} for every other task, AND removed the operator's only way
     * to delete it (addressing by taskId requires deserializing the document that will not
     * deserialize). Unrecoverable through the API. Containment replaces propagation: skipped for
     * execution, still fully visible, and removable by {@code _id}.
     */
    @Test
    void oneCorruptEntryDoesNotStallTheQueueAndIsRemovableByDocId() {
        svc.enqueue(repo, "objHealthy", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        svc.enqueue(repo, "objBroken", SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
        String brokenId = SearchIndexAclReindexTask.deterministicId(
                repo, "objBroken", SearchIndexAclReindexTask.Operation.ACL_REINDEX);
        forceStoredField(brokenId, "not-a-number");

        // 1. The healthy task is still listed and still CLAIMABLE — the queue keeps draining.
        assertEquals(List.of("objHealthy"), svc.list(2000).stream()
                .filter(t -> repo.equals(t.getRepositoryId())).map(SearchIndexAclReindexTask::getObjectId)
                .toList(), "the corrupt entry must not take the healthy ones down with it");
        List<SearchIndexAclReindexTask> claimed = svc.claimDue(50, "it-node", 60_000L).stream()
                .filter(t -> repo.equals(t.getRepositoryId())).toList();
        assertEquals(List.of("objHealthy"),
                claimed.stream().map(SearchIndexAclReindexTask::getObjectId).toList(),
                "and a corrupt entry must never be CLAIMED: its obligation is unknown, so completing "
                        + "it would ACK an epoch it may never have reached");

        // 2. It is visible, with the context needed to act: which object, and why.
        SearchIndexReconciliationService.CorruptTaskRef ref = svc.listCorrupt(2000).stream()
                .filter(c -> brokenId.equals(c.getDocId())).findFirst()
                .orElseThrow(() -> new AssertionError("the corrupt entry VANISHED — containment must "
                        + "not become silence; that is the 7a failure mode this test exists for"));
        assertEquals("objBroken", ref.getObjectId(), "the operator needs the object id to re-index");
        assertEquals(repo, ref.getRepositoryId());
        assertTrue(svc.metrics().get("corrupt") instanceof Integer c && c >= 1,
                "and it must be countable without listing: " + svc.metrics().get("corrupt"));

        // 3. A healthy document is REFUSED by the escape hatch (it is a repair tool, not a second
        //    delete API — the taskId route has the LEASED protection this one cannot apply).
        String healthyId = SearchIndexAclReindexTask.deterministicId(
                repo, "objHealthy", SearchIndexAclReindexTask.Operation.ACL_REINDEX);
        assertFalse(svc.deleteCorruptByDocId(healthyId),
                "deleting a HEALTHY task by _id would silently drop a live obligation");
        assertEquals(1, svc.list(2000).stream()
                .filter(t -> repo.equals(t.getRepositoryId())).count(), "so it is still there");

        // 4. The corrupt one IS removable — the stall has an exit.
        assertTrue(svc.deleteCorruptByDocId(brokenId));
        assertTrue(svc.listCorrupt(2000).stream().noneMatch(c -> brokenId.equals(c.getDocId())));
        assertFalse(svc.deleteCorruptByDocId(brokenId), "and the delete is idempotent");
    }

    /** Overwrite ONE field of the stored task document, behind the service's back. */
    private void forceStoredField(String docId, Object minRequiredEpoch) {
        try {
            com.ibm.cloud.cloudant.v1.model.Document d = confWrapper.getClient()
                    .getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
                            .db(confWrapper.getDatabaseName()).docId(docId).build())
                    .execute().getResult();
            java.util.Map<String, Object> props = new java.util.HashMap<>(d.getProperties());
            props.put("minRequiredEpoch", minRequiredEpoch);
            com.ibm.cloud.cloudant.v1.model.Document upd = new com.ibm.cloud.cloudant.v1.model.Document();
            upd.setId(d.getId());
            upd.setRev(d.getRev());
            upd.setProperties(props);
            confWrapper.getClient().putDocument(new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions
                    .Builder().db(confWrapper.getDatabaseName()).docId(docId).document(upd).build())
                    .execute();
        } catch (Exception e) {
            throw new IllegalStateException("forceStoredField failed for " + docId, e);
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
