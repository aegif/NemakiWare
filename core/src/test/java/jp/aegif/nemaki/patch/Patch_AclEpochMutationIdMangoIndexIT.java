package jp.aegif.nemaki.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.GetIndexesInformationOptions;
import com.ibm.cloud.cloudant.v1.model.IndexInformation;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Integration tests for {@link Patch_AclEpochMutationIdMangoIndex} against a LIVE CouchDB
 * (review 3c): the index is created, applying twice is idempotent, and a failure THROWS so
 * {@code AbstractNemakiPatch} does not record PatchHistory (otherwise the index would never be
 * created on a later, healthy startup).
 */
public class Patch_AclEpochMutationIdMangoIndexIT {

    private static Cloudant cloudant;
    private static boolean available;

    private String contentDb;

    @BeforeAll
    static void connect() {
        String url = cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL", "http://localhost:5984");
        String user = cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin");
        String pass = cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD", "password");
        // (kept for symmetry with the other epoch ITs; only the SDK client is used here)
        Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
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
            throw new IllegalStateException("nemaki.test.couchdb.required=true but nemaki_conf is not "
                    + "reachable — the ACL epoch mutation-id index patch IT cannot run");
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(available, "nemaki_conf not reachable — skipping");
        contentDb = "epoch-midx-it-" + UUID.randomUUID();
        cloudant.putDatabase(new PutDatabaseOptions.Builder().db(contentDb).build()).execute();
    }

    @AfterEach
    void tearDown() {
        if (!available) return;
        try {
            cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(contentDb).build()).execute();
        } catch (Exception ignore) { /* best effort */ }
    }

    @Test
    void createsTheIndexAndIsIdempotentOnReapply() {
        Patch_AclEpochMutationIdMangoIndex patch = patchWithPool();

        patch.applyPerRepositoryPatch(contentDb);
        assertTrue(hasIndex("idx_aclEpochMutationId"), "the index must exist after the first apply");

        // Re-applying must NOT throw (Cloudant returns result="exists").
        patch.applyPerRepositoryPatch(contentDb);
        assertTrue(hasIndex("idx_aclEpochMutationId"), "the index survives a second apply");
        assertEquals(1, countIndexes("idx_aclEpochMutationId"), "no duplicate index is created");
    }

    @Test
    void throwsWhenTheConnectorPoolIsUnavailableSoPatchHistoryIsNotRecorded() {
        // AbstractNemakiPatch records PatchHistory when the patch RETURNS; returning on a missing
        // pool would mean the index is never created on a later healthy startup.
        Patch_AclEpochMutationIdMangoIndex patch = new Patch_AclEpochMutationIdMangoIndex();
        patch.setPatchUtil(null);
        assertThrows(RuntimeException.class, () -> patch.applyPerRepositoryPatch(contentDb));
    }

    @Test
    void throwsWhenTheRepositoryHasNoClient() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient("no-such-repo")).thenReturn(null);
        PatchUtil util = mock(PatchUtil.class);
        when(util.getConnectorPool()).thenReturn(pool);
        Patch_AclEpochMutationIdMangoIndex patch = new Patch_AclEpochMutationIdMangoIndex();
        patch.setPatchUtil(util);
        assertThrows(RuntimeException.class, () -> patch.applyPerRepositoryPatch("no-such-repo"));
    }

    @Test
    void throwsWhenTheDatabaseDoesNotExist() {
        // A genuine registration failure must propagate (not be swallowed into a recorded success).
        CloudantClientWrapper wrapper =
                new CloudantClientWrapper(cloudant, "definitely-missing-db-" + UUID.randomUUID(),
                        ObjectMapperFactory.createDefaultObjectMapper());
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient("ghost")).thenReturn(wrapper);
        PatchUtil util = mock(PatchUtil.class);
        when(util.getConnectorPool()).thenReturn(pool);
        Patch_AclEpochMutationIdMangoIndex patch = new Patch_AclEpochMutationIdMangoIndex();
        patch.setPatchUtil(util);
        assertThrows(RuntimeException.class, () -> patch.applyPerRepositoryPatch("ghost"));
    }

    private Patch_AclEpochMutationIdMangoIndex patchWithPool() {
        CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudant, contentDb, ObjectMapperFactory.createDefaultObjectMapper());
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(contentDb)).thenReturn(wrapper);
        PatchUtil util = mock(PatchUtil.class);
        when(util.getConnectorPool()).thenReturn(pool);
        Patch_AclEpochMutationIdMangoIndex patch = new Patch_AclEpochMutationIdMangoIndex();
        patch.setPatchUtil(util);
        return patch;
    }

    private boolean hasIndex(String name) {
        return countIndexes(name) > 0;
    }

    private int countIndexes(String name) {
        List<IndexInformation> idx = cloudant.getIndexesInformation(
                new GetIndexesInformationOptions.Builder().db(contentDb).build())
                .execute().getResult().getIndexes();
        int n = 0;
        for (IndexInformation i : idx) {
            if (name.equals(i.getName())) n++;
        }
        return n;
    }

    private static String cfg(String sysProp, String envVar, String dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
