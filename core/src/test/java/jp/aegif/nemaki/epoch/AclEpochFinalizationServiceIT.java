package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.LinkedHashMap;
import java.util.Map;
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
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.epoch.AclEpochFinalizationService.FinalizeOutcome;
import jp.aegif.nemaki.epoch.AclEpochFinalizationService.FinalizeResult;
import jp.aegif.nemaki.epoch.AclEpochFinalizationService.ScanSummary;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * Integration tests for {@link AclEpochFinalizationService} against a LIVE CouchDB. Each
 * test runs in its OWN throwaway content database (dropped in {@code @AfterEach}) with its
 * own seeded ACL-epoch counter, so tests never see each other's documents and never touch
 * a real repository. Gated like the other CouchDB ITs.
 *
 * <pre>mvn -o test -Dtest=AclEpochFinalizationServiceIT -f core/pom.xml -Pdevelopment
 *   -Dnemaki.test.couchdb.url=http://localhost:5984
 *   -Dnemaki.test.couchdb.user=admin -Dnemaki.test.couchdb.password=password</pre>
 */
public class AclEpochFinalizationServiceIT {

    private static Cloudant cloudant;
    private static boolean available;

    private String contentDb;         // throwaway content DB (== the fixture repositoryId)
    private CloudantClientWrapper confWrapper;
    private CloudantClientWrapper contentWrapper;
    private AclEpochFinalizationService svc;

    @BeforeAll
    static void connect() {
        String url = cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL", "http://localhost:5984");
        String user = cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin");
        String pass = cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD", "password");
        try {
            BasicAuthenticator auth = new BasicAuthenticator.Builder().username(user).password(pass).build();
            cloudant = new Cloudant("cloudant-service", auth);
            cloudant.setServiceUrl(url);
            cloudant.getDatabaseInformation(
                    new com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions.Builder()
                            .db(SystemConst.NEMAKI_CONF_DB).build()).execute();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        if (!available && Boolean.parseBoolean(
                cfg("nemaki.test.couchdb.required", "NEMAKI_TEST_COUCHDB_REQUIRED", "false"))) {
            throw new IllegalStateException(
                    "nemaki.test.couchdb.required=true but nemaki_conf is not reachable — "
                    + "the ACL epoch finalization IT cannot run");
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(available, "nemaki_conf not reachable — skipping ACL epoch finalization IT");
        contentDb = "epoch-fin-it-" + UUID.randomUUID();
        cloudant.putDatabase(new PutDatabaseOptions.Builder().db(contentDb).build()).execute();

        ObjectMapper om = new ObjectMapper();
        confWrapper = new CloudantClientWrapper(cloudant, SystemConst.NEMAKI_CONF_DB, om);
        contentWrapper = new CloudantClientWrapper(cloudant, contentDb, om);

        // One mock pool serves BOTH the counter (nemaki_conf) and the content DB.
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(confWrapper);
        lenient().when(pool.getClient(contentDb)).thenReturn(contentWrapper);

        AclEpochCounterService counter = new AclEpochCounterService();
        counter.setConnectorPool(pool);
        seedCounter(contentDb, 0L); // so allocate() returns 1, 2, …

        svc = new AclEpochFinalizationService();
        svc.setConnectorPool(pool);
        svc.setCounterService(counter);
    }

    @AfterEach
    void tearDown() {
        if (!available) return;
        try {
            cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(contentDb).build()).execute();
        } catch (Exception ignore) { /* best effort */ }
        deleteConf(AclEpochCounterService.counterDocId(contentDb));
    }

    // ── finalize (Phase 2) ─────────────────────────────────────────

    @Test
    void finalizeAllocatesEpochAndPreservesOtherFields() {
        seedPending("d1", "m-1");
        FinalizeOutcome o = svc.finalizePending(contentDb, "d1");
        assertEquals(FinalizeResult.FINALIZED, o.result);
        assertEquals(1L, o.epoch.longValue(), "first allocation is 1");

        Map<String, Object> p = props("d1");
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, p.get(AclEpochState.FIELD_STATE));
        assertEquals(1L, ((Number) p.get(AclEpochState.FIELD_SOURCE_EPOCH)).longValue());
        assertEquals("m-1", p.get(AclEpochState.FIELD_MUTATION_ID), "mutation id preserved");
        assertEquals("keep-me", p.get("name"), "other content fields preserved");
    }

    @Test
    void finalizeIsIdempotentOnAlreadyFinalizedNeverReallocates() {
        seedFinalized("d2", "m-2", 7L);
        FinalizeOutcome o = svc.finalizePending(contentDb, "d2");
        assertEquals(FinalizeResult.SKIPPED_NOT_PENDING, o.result);
        assertNull(o.epoch);
        assertEquals(7L, ((Number) props("d2").get(AclEpochState.FIELD_SOURCE_EPOCH)).longValue(),
                "an already-finalized epoch must not be re-allocated or overwritten");
    }

    @Test
    void finalizeSkipsStatelessDocument() {
        seedStateless("d3");
        FinalizeOutcome o = svc.finalizePending(contentDb, "d3");
        assertEquals(FinalizeResult.SKIPPED_NOT_PENDING, o.result);
        assertFalse(props("d3").containsKey(AclEpochState.FIELD_STATE), "no epoch state added to a normal doc");
    }

    @Test
    void finalizeAbandonsWhenMutationIdChangedUnderneath() {
        // Seed PENDING with m-A, capture the (now stale) document, then a newer Phase-1
        // supersedes it with m-B. Finalizing the STALE doc must abandon (409 → re-read →
        // different mutation id), never overwriting the newer mutation's state.
        seedPending("d4", "m-A");
        Document stale = getContent("d4"); // rev R, mutation m-A (kept intact for the finalize)
        // Supersede: a newer Phase-1 bumps the doc to a new mutation id (still PENDING).
        Document fresh = getContent("d4");
        Map<String, Object> fp = fresh.getProperties();
        fp.put(AclEpochState.FIELD_MUTATION_ID, "m-B");
        fresh.setProperties(fp); // getProperties() may be a copy — re-set so the change persists
        putContent(fresh);

        FinalizeOutcome o = svc.finalizePending(contentDb, stale); // stale rev + m-A
        assertEquals(FinalizeResult.ABANDONED_SUPERSEDED, o.result);
        Map<String, Object> p = props("d4");
        assertEquals(AclEpochState.PENDING_EPOCH, p.get(AclEpochState.FIELD_STATE),
                "the superseded finalize must not finalize the newer mutation");
        assertEquals("m-B", p.get(AclEpochState.FIELD_MUTATION_ID));
        assertFalse(p.containsKey(AclEpochState.FIELD_SOURCE_EPOCH), "no epoch written by the abandoned finalize");
    }

    @Test
    void concurrentFinalizeExactlyOneWins() throws Exception {
        seedPending("d5", "m-5");
        int threads = 6;
        AtomicInteger finalized = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    // Each thread fetches its own snapshot and races the CAS.
                    FinalizeOutcome o = svc.finalizePending(contentDb, getContent("d5"));
                    if (o.result == FinalizeResult.FINALIZED) finalized.incrementAndGet();
                } catch (Exception ignore) {
                    // per-JVM-lock-free: a loser simply abandons/skips
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(1, finalized.get(), "exactly one concurrent finalizer commits the epoch (CAS, no JVM lock)");
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("d5").get(AclEpochState.FIELD_STATE));
    }

    // ── scan (crash recovery) ──────────────────────────────────────

    @Test
    void scanFinalizesPendingCountsFinalizedAndStopsAtFinalized() {
        seedPending("s-pending", "m-p");
        seedFinalized("s-final", "m-f", 3L);
        seedStateless("s-plain");

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(1, sum.finalized, "the PENDING doc is finalized");
        assertEquals(1, sum.awaitingReconcile, "the FINALIZED doc is counted but not advanced");
        assertEquals(2, sum.scanned, "only the two epoch-state docs are scanned (not the stateless one)");
        assertTrue(sum.errors.isEmpty());

        // The FINALIZED doc STAYS FINALIZED — increment 2 does NOT enqueue/ACK.
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("s-final").get(AclEpochState.FIELD_STATE));
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("s-pending").get(AclEpochState.FIELD_STATE));
    }

    @Test
    void scanIgnoresStatelessContent() {
        seedStateless("plain-1");
        seedStateless("plain-2");
        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(0, sum.scanned, "state-less content must never be selected by the scanner");
        assertFalse(props("plain-1").containsKey(AclEpochState.FIELD_STATE), "untouched");
    }

    @Test
    void scanRecordsPendingWithoutMutationIdAsAnomalyAndRetains() {
        // PENDING_EPOCH with NO mutation id → anomaly: recorded + left unprocessed.
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        // deliberately no aclEpochMutationId
        putContentRaw("bad-1", p);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(1, sum.scanned);
        assertEquals(0, sum.finalized);
        assertEquals(1, sum.errors.size(), "the anomaly is recorded");
        assertEquals("bad-1", sum.errors.get(0).get("docId"));
        assertEquals(AclEpochState.PENDING_EPOCH, props("bad-1").get(AclEpochState.FIELD_STATE),
                "an anomalous doc is left unprocessed (still PENDING), not silently skipped or finalized");
    }

    @Test
    void scanRecordsFinalizedWithInvalidEpochAsAnomaly() {
        // FINALIZED_NEEDS_RECONCILE with a fractional epoch → anomaly.
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        p.put(AclEpochState.FIELD_MUTATION_ID, "m-x");
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, 1.5d);
        putContentRaw("bad-2", p);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(1, sum.errors.size());
        assertEquals("bad-2", sum.errors.get(0).get("docId"));
        assertEquals(0, sum.awaitingReconcile);
    }

    // ── fixtures / helpers ─────────────────────────────────────────

    private Map<String, Object> baseFixture() {
        Map<String, Object> p = new LinkedHashMap<>();
        // A test-only type so the app's CMIS layer never treats a fixture as a real object;
        // the scanner selects by aclEpochState, so the type value is irrelevant to it.
        p.put("type", "epoch-it-fixture");
        p.put("name", "keep-me");
        return p;
    }

    private void seedPending(String id, String mutationId) {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, mutationId);
        putContentRaw(id, p);
    }

    private void seedFinalized(String id, String mutationId, long epoch) {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        p.put(AclEpochState.FIELD_MUTATION_ID, mutationId);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        putContentRaw(id, p);
    }

    private void seedStateless(String id) {
        putContentRaw(id, baseFixture());
    }

    private void putContentRaw(String id, Map<String, Object> props) {
        Document d = new Document();
        d.setId(id);
        String rev = revOf(contentDb, id);
        if (rev != null) d.setRev(rev);
        d.setProperties(props);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(contentDb).docId(id).document(d).build()).execute();
    }

    private Document getContent(String id) {
        return cloudant.getDocument(new GetDocumentOptions.Builder()
                .db(contentDb).docId(id).build()).execute().getResult();
    }

    private void putContent(Document d) {
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(contentDb).docId(d.getId()).document(d).build()).execute();
    }

    private Map<String, Object> props(String id) {
        return getContent(id).getProperties();
    }

    private String revOf(String db, String id) {
        try {
            Document d = cloudant.getDocument(new GetDocumentOptions.Builder().db(db).docId(id).build())
                    .execute().getResult();
            return d == null ? null : d.getRev();
        } catch (NotFoundException e) {
            return null;
        }
    }

    private void seedCounter(String repositoryId, long value) {
        String id = AclEpochCounterService.counterDocId(repositoryId);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", AclEpochCounterService.DOC_TYPE);
        props.put("value", value);
        Document doc = new Document();
        doc.setId(id);
        String rev = revOf(SystemConst.NEMAKI_CONF_DB, id);
        if (rev != null) doc.setRev(rev);
        doc.setProperties(props);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(SystemConst.NEMAKI_CONF_DB).docId(id).document(doc).build()).execute();
    }

    private void deleteConf(String id) {
        String rev = revOf(SystemConst.NEMAKI_CONF_DB, id);
        if (rev != null) {
            try {
                cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                        .db(SystemConst.NEMAKI_CONF_DB).docId(id).rev(rev).build()).execute();
            } catch (Exception ignore) { /* best effort */ }
        }
    }

    private static String cfg(String sysProp, String envVar, String dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
