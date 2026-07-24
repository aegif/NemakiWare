package jp.aegif.nemaki.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.epoch.AclEpochCounterService;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * Integration tests for {@link Patch_AclEpochCounter#ensureCounter} against a LIVE
 * CouchDB: create-if-absent, preserve-valid-existing, fail-closed on a corrupt existing
 * counter, and the dangerous tombstone-conflict path (a create {@code 409} where NO live
 * counter exists must NOT be recorded as success). Gated like the other CouchDB ITs.
 *
 * <pre>mvn -o test -Dtest=Patch_AclEpochCounterIT -f core/pom.xml -Pdevelopment
 *   -Dnemaki.test.couchdb.url=http://localhost:5984
 *   -Dnemaki.test.couchdb.user=admin -Dnemaki.test.couchdb.password=password</pre>
 */
public class Patch_AclEpochCounterIT {

    private static Cloudant cloudant;
    private static String db;
    private static boolean available;

    private String repo;

    @BeforeAll
    static void connect() {
        String url = cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL", "http://localhost:5984");
        String user = cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin");
        String pass = cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD", "password");
        db = SystemConst.NEMAKI_CONF_DB;
        try {
            BasicAuthenticator auth = new BasicAuthenticator.Builder().username(user).password(pass).build();
            cloudant = new Cloudant("cloudant-service", auth);
            cloudant.setServiceUrl(url);
            cloudant.getDatabaseInformation(
                    new com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions.Builder()
                            .db(db).build()).execute();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        if (!available && Boolean.parseBoolean(
                cfg("nemaki.test.couchdb.required", "NEMAKI_TEST_COUCHDB_REQUIRED", "false"))) {
            throw new IllegalStateException(
                    "nemaki.test.couchdb.required=true but nemaki_conf is not reachable — "
                    + "the ACL epoch counter patch IT cannot run");
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(available, "nemaki_conf not reachable — skipping ACL epoch counter patch IT");
        repo = "epoch-patch-it-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        if (available) deleteIfPresent(AclEpochCounterService.counterDocId(repo));
    }

    @Test
    void createsWhenAbsent() {
        Patch_AclEpochCounter.ensureCounter(cloudant, db, repo);
        assertEquals(0L, readValue(repo), "a fresh repository is seeded at value 0");
    }

    @Test
    void preservesValidExistingHighWatermark() {
        seed(repo, 42L);
        Patch_AclEpochCounter.ensureCounter(cloudant, db, repo);
        assertEquals(42L, readValue(repo), "an existing valid counter must NOT be reset to 0");
    }

    @Test
    void throwsOnCorruptExistingSoNoSuccessHistory() {
        seed(repo, 1.5d); // fractional = corruption
        // A throw from ensureCounter means AbstractNemakiPatch does not record PatchHistory,
        // so the corrupt counter is not silently accepted.
        assertThrows(IllegalStateException.class,
                () -> Patch_AclEpochCounter.ensureCounter(cloudant, db, repo));
    }

    @Test
    void tombstoneCreateNeverReportsSuccessWithoutALiveCounter() {
        // Create then delete → a tombstone remains. Whether CouchDB 409s the create over
        // a tombstone is version-dependent, so this asserts the version-INDEPENDENT
        // invariant: ensureCounter must EITHER fail closed (throw) OR leave a live, valid
        // counter — it must NEVER return success while no counter exists (the exact
        // "409 recorded as success" bug; the deterministic 409-branch logic is unit-tested
        // in Patch_AclEpochCounterTest).
        seed(repo, 0L);
        deleteIfPresent(AclEpochCounterService.counterDocId(repo)); // leaves a tombstone
        boolean threw = false;
        try {
            Patch_AclEpochCounter.ensureCounter(cloudant, db, repo);
        } catch (IllegalStateException e) {
            threw = true;
        }
        boolean liveValidCounter = currentRev(AclEpochCounterService.counterDocId(repo)) != null
                && readValue(repo) >= 0;
        assertTrue(threw || liveValidCounter,
                "ensureCounter over a tombstone must either fail closed or leave a valid live "
                + "counter — never report success with no counter");
    }

    // ── direct-CouchDB helpers ─────────────────────────────────────

    private void seed(String repositoryId, Object value) {
        String id = AclEpochCounterService.counterDocId(repositoryId);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", AclEpochCounterService.DOC_TYPE);
        props.put("value", value);
        Document doc = new Document();
        doc.setId(id);
        String rev = currentRev(id);
        if (rev != null) doc.setRev(rev);
        doc.setProperties(props);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(db).docId(id).document(doc).build()).execute();
    }

    private long readValue(String repositoryId) {
        Document d = cloudant.getDocument(new GetDocumentOptions.Builder()
                .db(db).docId(AclEpochCounterService.counterDocId(repositoryId)).build())
                .execute().getResult();
        // Use the public strict validator (also asserts type/_rev/finite-integral).
        return AclEpochCounterService.requireValidCounter(
                d.getProperties().get("type"), d.getProperties().get("value"), d.getRev());
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

    private void deleteIfPresent(String id) {
        String rev = currentRev(id);
        if (rev != null) {
            try {
                cloudant.deleteDocument(new DeleteDocumentOptions.Builder()
                        .db(db).docId(id).rev(rev).build()).execute();
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }

    private static String cfg(String sysProp, String envVar, String dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
