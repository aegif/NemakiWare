package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Integration tests for {@link AclEpochCounterService} against a LIVE CouchDB — the
 * {@code _rev}-CAS allocation semantics that the pure unit test cannot cover. Gated:
 * skipped when {@code nemaki_conf} is unreachable ({@code assumeTrue}); a HARD failure
 * when {@code nemaki.test.couchdb.required=true} (CI). Each test isolates its counter
 * under a unique repository id and deletes it afterward.
 *
 * <pre>mvn -o test -Dtest=AclEpochCounterServiceIT -f core/pom.xml -Pdevelopment
 *   -Dnemaki.test.couchdb.url=http://localhost:5984
 *   -Dnemaki.test.couchdb.user=admin -Dnemaki.test.couchdb.password=password</pre>
 */
public class AclEpochCounterServiceIT {

    private static Cloudant cloudant;
    private static CloudantClientWrapper confWrapper;
    private static String db;
    private static boolean available;

    private AclEpochCounterService svc;
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
            db = confWrapper.getDatabaseName();
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
                    + "the ACL epoch counter IT cannot run (start CouchDB + Setup Wizard first)");
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(available, "nemaki_conf not reachable — skipping ACL epoch counter IT");
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(confWrapper);
        svc = new AclEpochCounterService();
        svc.setConnectorPool(pool);
        repo = "epoch-it-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        if (!available) return;
        deleteCounter(repo);
    }

    // ── monotonic allocation ───────────────────────────────────────

    @Test
    void allocateIsStrictlyMonotonicFromSeed() {
        seedCounter(repo, AclEpochCounterService.SEED_VALUE); // 0 → first allocate = 1
        assertEquals(1L, svc.allocate(repo));
        assertEquals(2L, svc.allocate(repo));
        assertEquals(3L, svc.allocate(repo));
        assertEquals(3L, svc.currentHighWatermark(repo), "high-watermark tracks the last allocation");
    }

    @Test
    void concurrentAllocateProducesDistinctGapFreeValues() throws Exception {
        seedCounter(repo, 0L);
        int threads = 8;
        Set<Long> issued = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    issued.add(svc.allocate(repo));
                } catch (Exception e) {
                    // a thread that fails simply contributes no value; the assertions below catch it
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "allocate threads did not finish");
        // A pure allocate never skips a value (a failed CAS persists nothing and retries),
        // so 8 concurrent allocations yield EXACTLY {1..8}: distinct (no double-issue) and
        // gap-free.
        assertEquals(8, issued.size(), "concurrent allocations must all be distinct (no double-issue)");
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), issued, "allocations must be gap-free 1..8");
        assertEquals(8L, svc.currentHighWatermark(repo));
    }

    // ── fail-closed ────────────────────────────────────────────────

    @Test
    void missingCounterFailsClosedAndDoesNotLazyRecreate() {
        // No seed → the counter is absent for this fresh repo id.
        assertFalse(counterExists(repo), "precondition: no counter yet");
        assertThrows(IllegalStateException.class, () -> svc.allocate(repo),
                "allocate on a missing counter must fail closed");
        assertFalse(counterExists(repo),
                "a failed allocate must NOT lazily recreate the counter (would roll back the high-watermark)");
        assertThrows(IllegalStateException.class, () -> svc.currentHighWatermark(repo));
    }

    @Test
    void corruptNegativeValueFailsClosed() {
        seedCounter(repo, -5L);
        assertThrows(IllegalStateException.class, () -> svc.allocate(repo));
        assertThrows(IllegalStateException.class, () -> svc.currentHighWatermark(repo));
    }

    // ── high-watermark read + no rollback ──────────────────────────

    @Test
    void currentHighWatermarkReadsWithoutIncrement() {
        seedCounter(repo, 42L);
        assertEquals(42L, svc.currentHighWatermark(repo));
        assertEquals(42L, svc.currentHighWatermark(repo), "reading must not increment");
        assertEquals(43L, svc.allocate(repo));
    }

    @Test
    void allocateAlwaysIncrementsFromStoredNeverRollsBack() {
        // Models "a restore must not roll the counter back": whatever the stored value,
        // allocate only ever moves it strictly forward.
        seedCounter(repo, 100L);
        assertEquals(101L, svc.allocate(repo));
        // Re-seed to a HIGHER value (as a recovery would) — allocate continues forward.
        seedCounter(repo, 500L);
        assertEquals(501L, svc.allocate(repo));
    }

    // ── direct-CouchDB helpers ─────────────────────────────────────

    private void seedCounter(String repositoryId, long value) {
        String id = AclEpochCounterService.counterDocId(repositoryId);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", AclEpochCounterService.DOC_TYPE);
        props.put("value", value);
        Document doc = new Document();
        doc.setId(id);
        String rev = currentRev(id);
        if (rev != null) {
            doc.setRev(rev);
        }
        doc.setProperties(props);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(db).docId(id).document(doc).build()).execute();
    }

    private String currentRev(String id) {
        try {
            Document d = cloudant.getDocument(new GetDocumentOptions.Builder()
                    .db(db).docId(id).build()).execute().getResult();
            return d == null ? null : d.getRev();
        } catch (NotFoundException e) {
            return null;
        }
    }

    private boolean counterExists(String repositoryId) {
        return currentRev(AclEpochCounterService.counterDocId(repositoryId)) != null;
    }

    private void deleteCounter(String repositoryId) {
        String id = AclEpochCounterService.counterDocId(repositoryId);
        String rev = currentRev(id);
        if (rev != null) {
            try {
                cloudant.deleteDocument(new DeleteDocumentOptions.Builder()
                        .db(db).docId(id).rev(rev).build()).execute();
            } catch (Exception ignore) {
                // best-effort cleanup
            }
        }
    }

    private static String cfg(String sysProp, String envVar, String dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
