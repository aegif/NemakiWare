package jp.aegif.nemaki.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * DETERMINISTIC integration test for the 5T principal tri-state, against a REAL CouchDB.
 *
 * <p>Fault INJECTION is deliberately avoided: the condition 5T exists for is "the query could not
 * be served", and the honest way to produce it is to actually DELETE the design document the views
 * live in — the same technique the epoch ITs use for a missing Mango index. A mock that returns
 * {@code null} would only prove that the mock was configured.
 *
 * <p>Each test uses a throwaway database, so nothing here can touch a real repository.
 */
public class PrincipalLookupTriStateIT {

    private static final String DDOC = "_design/_repo";
    private static final String REPO = "tri-state-it";

    private static Cloudant cloudant;
    private static boolean available;
    private static String baseUrl;
    private static String basicAuth;

    private String db;
    private jp.aegif.nemaki.dao.impl.couch.PrincipalDaoServiceImpl dao;

    private static String cfg(String sysProp, String env, String fallback) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    @BeforeAll
    static void connect() {
        String url = cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL", "http://localhost:5984");
        String user = cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin");
        String pass = cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD", "password");
        baseUrl = url.replaceAll("/+$", "");
        basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        try {
            BasicAuthenticator auth = new BasicAuthenticator.Builder().username(user).password(pass).build();
            cloudant = new Cloudant("cloudant-service", auth);
            cloudant.setServiceUrl(url);
            cloudant.getAllDbs().execute();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        if (!available && Boolean.parseBoolean(
                cfg("nemaki.test.couchdb.required", "NEMAKI_TEST_COUCHDB_REQUIRED", "false"))) {
            throw new IllegalStateException("nemaki.test.couchdb.required=true but CouchDB is not "
                    + "reachable — the principal tri-state IT cannot run");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(available, "CouchDB not reachable — skipping principal tri-state IT");
        db = "principal-tristate-it-" + UUID.randomUUID();
        cloudant.putDatabase(new PutDatabaseOptions.Builder().db(db).build()).execute();

        // The two views the DAO reads, defined exactly as it queries them (by id).
        put("/" + db + "/" + DDOC, "{\"views\":{"
                + "\"userItemsById\":{\"map\":\"function(doc){if(doc.type=='user'){emit(doc.userId,doc);}}\"},"
                + "\"groupItemsById\":{\"map\":\"function(doc){if(doc.type=='group'){emit(doc.groupId,doc);}}\"}"
                + "}}");
        put("/" + db + "/u-alice", "{\"type\":\"user\",\"userId\":\"alice\",\"name\":\"alice\"}");
        put("/" + db + "/g-team", "{\"type\":\"group\",\"groupId\":\"team\",\"name\":\"team\"}");

        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(REPO))
                .thenReturn(new CloudantClientWrapper(cloudant, db, new ObjectMapper()));
        dao = new jp.aegif.nemaki.dao.impl.couch.PrincipalDaoServiceImpl();
        dao.setConnectorPool(pool);
    }

    @AfterEach
    void tearDown() {
        if (db != null && available) {
            try {
                cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(db).build()).execute();
            } catch (Exception ignored) {
                // throwaway database; a failed cleanup must not mask a test result
            }
        }
    }

    // ── 3: positive control — a SERVED query must keep behaving exactly as before ──

    @Test
    public void aServedQueryDistinguishesPresentFromGenuinelyAbsent() {
        assertEquals(PrincipalLookup.FOUND, dao.lookupUserById(REPO, "alice"));
        assertEquals(PrincipalLookup.FOUND, dao.lookupGroupById(REPO, "team"));
        // The case that must stay an OMIT: served, and the principal really is not there.
        assertEquals(PrincipalLookup.NOT_FOUND, dao.lookupUserById(REPO, "no-such-user"));
        assertEquals(PrincipalLookup.NOT_FOUND, dao.lookupGroupById(REPO, "no-such-group"));
    }

    @Test
    public void aGenuinelyAbsentPrincipalIsDroppedFromTheTokensNotThrown() {
        java.util.List<jp.aegif.nemaki.model.Ace> aces = java.util.List.of(
                ace("alice", "cmis:read"), ace("deleted-user", "cmis:read"));
        java.util.List<String> tokens = AclSemantics.readerTokens(REPO, aces, resolver());
        assertTrue(tokens.contains("user:" + REPO + ":alice"), "the surviving principal must be kept");
        assertTrue(tokens.stream().noneMatch(t -> t.contains("deleted-user")),
                "a genuinely deleted principal must be DROPPED, exactly as before 5T");
    }

    // ── 1 + 2: the design document is gone, so the query cannot be served ──

    @Test
    public void anUnservableQueryIsUNAVAILABLE_notAbsence() throws Exception {
        deleteDesignDocument();
        assertEquals(PrincipalLookup.UNAVAILABLE, dao.lookupUserById(REPO, "alice"));
        assertEquals(PrincipalLookup.UNAVAILABLE, dao.lookupGroupById(REPO, "team"));
        // The pre-5T API still collapses it — which is exactly why the tri-state had to be added.
        org.junit.jupiter.api.Assertions.assertNull(dao.getUserById(REPO, "alice"),
                "getUserById cannot express this and still returns null — the tri-state is the fix");
    }

    @Test
    public void anUnservableQueryMakesTokenProjectionTHROW_soAStrictCallerCannotComplete()
            throws Exception {
        deleteDesignDocument();
        java.util.List<jp.aegif.nemaki.model.Ace> aces = java.util.List.of(ace("alice", "cmis:read"));

        PrincipalUnavailableException e = assertThrows(PrincipalUnavailableException.class,
                () -> AclSemantics.readerTokens(REPO, aces, resolver()));
        assertTrue(e.getMessage().contains("alice"), "the failing principal must be named");

        // The defect this closes, pinned POSITIVELY rather than by a sanity check: with the
        // design document gone every principal resolves to nothing, so WITHOUT the throw rule 2
        // would hand back the admin-only fallback — a plausible non-empty list that a strict
        // caller writes before deleting its reconciliation task as if it had reconciled. Show
        // that this exact value is what the pre-5T behaviour would have produced, and that the
        // production path refuses to produce it.
        java.util.List<String> whatTheOldBehaviourWouldHaveWritten =
                AclSemantics.readerTokens(REPO, aces, dropUnresolvable());
        assertEquals(AclSemantics.adminOnlyReaders(REPO), whatTheOldBehaviourWouldHaveWritten,
                "pre-5T semantics: an unservable lookup degrades to the admin-only fallback");
        assertTrue(!whatTheOldBehaviourWouldHaveWritten.contains("user:" + REPO + ":alice"),
                "and alice's genuine grant is silently gone — which is why this must throw");
    }

    // ── helpers ──

    private jp.aegif.nemaki.model.Ace ace(String principalId, String... permissions) {
        jp.aegif.nemaki.model.Ace a = new jp.aegif.nemaki.model.Ace();
        a.setPrincipalId(principalId);
        a.setPermissions(new java.util.ArrayList<>(java.util.Arrays.asList(permissions)));
        return a;
    }

    /** The PRODUCTION resolver shape: the tri-state DAO probes, user first then group. */
    private AclSemantics.PrincipalResolver resolver() {
        return new AclSemantics.PrincipalResolver() {
            @Override public PrincipalLookup lookupUser(String repo, String id) {
                return dao.lookupUserById(repo, id);
            }
            @Override public PrincipalLookup lookupGroup(String repo, String id) {
                return dao.lookupGroupById(repo, id);
            }
        };
    }

    /**
     * The PRE-5T resolver: it cannot tell "unservable" from "absent" and therefore drops both.
     * Present only so the IT can show what the production path now refuses to do.
     */
    private AclSemantics.PrincipalResolver dropUnresolvable() {
        return new AclSemantics.PrincipalResolver() {
            @Override public PrincipalLookup lookupUser(String repo, String id) {
                return dao.lookupUserById(repo, id) == PrincipalLookup.FOUND
                        ? PrincipalLookup.FOUND : PrincipalLookup.NOT_FOUND;
            }
            @Override public PrincipalLookup lookupGroup(String repo, String id) {
                return dao.lookupGroupById(repo, id) == PrincipalLookup.FOUND
                        ? PrincipalLookup.FOUND : PrincipalLookup.NOT_FOUND;
            }
        };
    }

    private void deleteDesignDocument() throws Exception {
        HttpResponse<String> head = send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + db + "/" + DDOC)).GET());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"_rev\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(head.body());
        assertTrue(m.find(), "could not read the design document _rev from: " + head.body());
        String rev = m.group(1);
        HttpResponse<String> del = send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + db + "/" + DDOC + "?rev=" + rev)).DELETE());
        assertTrue(del.statusCode() == 200 || del.statusCode() == 202,
                "the design document must really be gone: HTTP " + del.statusCode() + " " + del.body());
    }

    private void put(String path, String json) throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
        assertTrue(r.statusCode() / 100 == 2, "seed failed: HTTP " + r.statusCode() + " " + r.body());
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return HttpClient.newHttpClient().send(b.header("Authorization", basicAuth).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
