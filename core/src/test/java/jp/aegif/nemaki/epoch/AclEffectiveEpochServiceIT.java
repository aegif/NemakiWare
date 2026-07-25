package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.AclEpochAnomalyException;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.AclEpochPendingException;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.AclEpochUnavailableException;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.DependencyRole;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.Snapshot;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * Integration tests for {@link AclEffectiveEpochService} against a LIVE CouchDB (design §4.1 /
 * §4.2 steps 1, 2, 4 — increment 3). Each test runs in its OWN throwaway content database.
 *
 * <p>The fixtures are raw CouchDB content documents ({@code parentId} / {@code aclInherited} /
 * {@code sourceId} / {@code targetId} — exactly what {@code CouchContent} and
 * {@code CouchRelationship} persist), so the walk is exercised against the real persisted shape.
 */
public class AclEffectiveEpochServiceIT {

    private static Cloudant cloudant;
    private static boolean available;
    private static String baseUrl;
    private static String basicAuth;

    private String contentDb;
    private AclEffectiveEpochService svc;

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
                    + "the ACL effective-epoch IT cannot run");
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(available, "nemaki_conf not reachable — skipping ACL effective-epoch IT");
        contentDb = "epoch-eff-it-" + UUID.randomUUID();
        cloudant.putDatabase(new PutDatabaseOptions.Builder().db(contentDb).build()).execute();

        CloudantClientWrapper contentWrapper =
                new CloudantClientWrapper(cloudant, contentDb, new ObjectMapper());
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(contentDb)).thenReturn(contentWrapper);

        svc = new AclEffectiveEpochService();
        svc.setConnectorPool(pool);
    }

    @AfterEach
    void tearDown() {
        if (!available) return;
        try {
            cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(contentDb).build()).execute();
        } catch (Exception ignore) { /* best effort */ }
    }

    // ── §4.1 effective epoch over the inheriting chain ─────────────

    @Test
    void effectiveEpochIsTheMaxOverSelfAndInheritingAncestors() {
        seedFolder("root", null, false, 3L);   // root: does not inherit
        seedFolder("mid", "root", true, 9L);   // inherits from root
        seedDocument("leaf", "mid", true, 4L); // inherits from mid

        Snapshot s = svc.snapshot(contentDb, "leaf");
        assertNotNull(s);
        assertEquals(9L, s.effectiveEpoch, "max(leaf=4, mid=9, root=3)");
        assertEquals(3, s.dependencies.size(), "self + both inheriting ancestors are recorded");
        assertEquals("leaf", s.dependencies.get(0).id);
        assertEquals(DependencyRole.SELF, s.dependencies.get(0).role);
    }

    @Test
    void walkStopsAtANonInheritingNodeSoHigherAncestorsDoNotCount() {
        seedFolder("root", null, false, 100L);  // a HIGH epoch that must NOT be counted
        seedFolder("cut", "root", false, 5L);   // breaks inheritance
        seedDocument("leaf", "cut", true, 2L);

        Snapshot s = svc.snapshot(contentDb, "leaf");
        assertEquals(5L, s.effectiveEpoch, "the walk stops AT the non-inheriting node (root is excluded)");
        assertEquals(2, s.dependencies.size(), "root is not a dependency");
        assertTrue(s.dependencies.stream().noneMatch(d -> "root".equals(d.id)));
    }

    @Test
    void absentAclInheritedDefaultsToInheriting() {
        // calculateAcl's getAclInheritedWithDefault: a null aclInherited means TRUE.
        seedFolder("root", null, false, 7L);
        seedRaw("leaf", "{\"parentId\":\"root\",\"aclSourceEpoch\":1}"); // no aclInherited at all

        Snapshot s = svc.snapshot(contentDb, "leaf");
        assertEquals(7L, s.effectiveEpoch, "an absent aclInherited must inherit (default TRUE)");
    }

    @Test
    void absentAclSourceEpochCountsAsZeroForPreMigrationContent() {
        seedRaw("root", "{\"aclInherited\":false}");                    // pre-migration: no epoch
        seedRaw("leaf", "{\"parentId\":\"root\",\"aclInherited\":true}"); // pre-migration: no epoch

        Snapshot s = svc.snapshot(contentDb, "leaf");
        assertEquals(0L, s.effectiveEpoch, "absent aclSourceEpoch = 0 (§4.1 pre-migration)");
    }

    @Test
    void deletedTargetReturnsNullRatherThanThrowing() {
        assertNull(svc.snapshot(contentDb, "no-such-object"),
                "a genuinely deleted target returns null so the caller completes instead of retrying");
    }

    // ── §4.2 step 1: the pending gate ──────────────────────────────

    @Test
    void pendingSelfBlocksTheWalk() {
        seedRaw("leaf", "{\"aclInherited\":false,\"aclEpochState\":\"PENDING_EPOCH\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");
        AclEpochPendingException e = assertThrows(AclEpochPendingException.class,
                () -> svc.snapshot(contentDb, "leaf"));
        assertEquals("leaf", e.dependencyId);
        assertEquals(AclEpochState.PENDING_EPOCH, e.state);
    }

    @Test
    void pendingANCESTORBlocksTheWalk() {
        // The read-skew case the gate exists for: the target looks settled but an ancestor is
        // mid-mutation, so its epoch is not yet assigned.
        seedRaw("root", "{\"aclInherited\":false,\"aclEpochState\":\"PENDING_EPOCH\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");
        seedDocument("leaf", "root", true, 2L);

        AclEpochPendingException e = assertThrows(AclEpochPendingException.class,
                () -> svc.snapshot(contentDb, "leaf"));
        assertEquals("root", e.dependencyId, "the gate names the mid-mutation ANCESTOR");
    }

    @Test
    void finalizedNeedsReconcileAlsoGatesButReconcileEnqueuedDoesNot() {
        seedRaw("gated", "{\"aclInherited\":false,\"aclEpochState\":\"FINALIZED_NEEDS_RECONCILE\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\",\"aclSourceEpoch\":6}");
        assertThrows(AclEpochPendingException.class, () -> svc.snapshot(contentDb, "gated"),
                "FINALIZED_NEEDS_RECONCILE is mid-CAS-ambiguous and must gate");

        seedRaw("settled", "{\"aclInherited\":false,\"aclEpochState\":\"RECONCILE_ENQUEUED\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\",\"aclSourceEpoch\":6}");
        Snapshot s = svc.snapshot(contentDb, "settled");
        assertEquals(6L, s.effectiveEpoch, "RECONCILE_ENQUEUED is settled and must NOT gate");
    }

    // ── fail-closed data rules ─────────────────────────────────────

    @Test
    void quarantinedDependencyIsRefused() {
        seedRaw("q", "{\"aclInherited\":false,\"aclSourceEpoch\":4,\"aclEpochQuarantined\":true}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "q"),
                "a document the epoch machine quarantined must never feed a fence value");
    }

    @Test
    void nonIntegerAndNegativeEpochsAreRefused() throws Exception {
        seedRaw("frac", "{\"aclInherited\":false,\"aclSourceEpoch\":1.5}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "frac"));

        seedRaw("strv", "{\"aclInherited\":false,\"aclSourceEpoch\":\"3\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "strv"));

        seedRaw("nullv", "{\"aclInherited\":false,\"aclSourceEpoch\":null}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "nullv"),
                "an explicit-null epoch is PRESENT (SDK contract) = corruption, not 'absent'");

        seedRaw("neg", "{\"aclInherited\":false,\"aclSourceEpoch\":-2}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "neg"));
    }

    @Test
    void unknownEpochStateIsRefused() {
        seedRaw("weird", "{\"aclInherited\":false,\"aclEpochState\":\"MYSTERY\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "weird"));
    }

    @Test
    void unreadableParentOfAnInheritingObjectIsRetryableNotSilentlyDropped() {
        // A dangling parent must NOT quietly degrade to "local ACEs only" — that would compute an
        // under-visible fence value (strict calculateAcl contract).
        seedDocument("leaf", "vanished-parent", true, 1L);
        assertThrows(AclEpochUnavailableException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    @Test
    void inheritanceCycleFailsClosedInsteadOfLooping() {
        seedFolder("a", "b", true, 1L);
        seedFolder("b", "a", true, 2L); // a → b → a
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "a"),
                "a cycle must fail closed, never loop");
    }

    @Test
    void chainLongerThanTheHopCapFailsClosed() {
        svc.setMaxAncestorHops(3);
        seedFolder("n0", null, false, 1L);
        for (int i = 1; i <= 6; i++) {
            seedFolder("n" + i, "n" + (i - 1), true, i);
        }
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "n6"));
    }

    @Test
    void invalidHopCapFallsBackToTheDefault() {
        svc.setMaxAncestorHops(0);
        assertEquals(AclEffectiveEpochService.DEFAULT_MAX_ANCESTOR_HOPS, svc.getMaxAncestorHops());
        svc.setMaxAncestorHops(-5);
        assertEquals(AclEffectiveEpochService.DEFAULT_MAX_ANCESTOR_HOPS, svc.getMaxAncestorHops());
    }

    // ── §4.1 relationships: max over BOTH endpoint chains ──────────

    @Test
    void relationshipEpochIsTheMaxOverBothEndpointChainsAndItself() {
        seedFolder("root", null, false, 1L);
        seedFolder("srcParent", "root", true, 12L);
        seedDocument("src", "srcParent", true, 2L);   // chain max 12
        seedDocument("tgt", "root", true, 5L);        // chain max 5
        seedRelationship("rel", "src", "tgt", 3L);    // own epoch 3

        Snapshot s = svc.snapshot(contentDb, "rel");
        assertEquals(12L, s.effectiveEpoch, "max(self=3, source chain=12, target chain=5)");
        assertTrue(s.dependencies.stream().anyMatch(d -> d.role == DependencyRole.RELATIONSHIP_SOURCE));
        assertTrue(s.dependencies.stream().anyMatch(d -> d.role == DependencyRole.RELATIONSHIP_TARGET));
    }

    @Test
    void danglingRelationshipEndpointContributesNothingRatherThanFailing() {
        seedFolder("root", null, false, 1L);
        seedDocument("tgt", "root", true, 8L);
        seedRelationship("rel", "gone", "tgt", 2L); // source no longer exists

        Snapshot s = svc.snapshot(contentDb, "rel");
        assertEquals(8L, s.effectiveEpoch,
                "a dangling endpoint contributes nothing (SolrUtil.relationshipReaders precedent)");
    }

    @Test
    void relationshipWithASharedAncestorDoesNotFalselyDetectACycle() {
        seedFolder("root", null, false, 4L);
        seedDocument("src", "root", true, 1L);
        seedDocument("tgt", "root", true, 2L); // BOTH endpoints inherit from the same root
        seedRelationship("rel", "src", "tgt", 0L);

        Snapshot s = svc.snapshot(contentDb, "rel");
        assertEquals(4L, s.effectiveEpoch, "a shared ancestor is legitimate, not a cycle");
    }

    @Test
    void pendingRelationshipEndpointAncestorGatesToo() {
        seedRaw("root", "{\"aclInherited\":false,\"aclEpochState\":\"PENDING_EPOCH\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");
        seedDocument("src", "root", true, 1L);
        seedDocument("tgt", null, false, 1L);
        seedRelationship("rel", "src", "tgt", 1L);

        AclEpochPendingException e = assertThrows(AclEpochPendingException.class,
                () -> svc.snapshot(contentDb, "rel"));
        assertEquals("root", e.dependencyId);
    }

    // ── §4.2 step 4: revalidation ──────────────────────────────────

    @Test
    void revalidateIsTrueWhenNothingChanged() {
        seedFolder("root", null, false, 3L);
        seedDocument("leaf", "root", true, 1L);
        Snapshot s = svc.snapshot(contentDb, "leaf");
        assertTrue(svc.revalidate(s), "an untouched snapshot revalidates");
    }

    @Test
    void revalidateDetectsAnAncestorEpochBump() {
        seedFolder("root", null, false, 3L);
        seedDocument("leaf", "root", true, 1L);
        Snapshot s = svc.snapshot(contentDb, "leaf");

        bumpEpoch("root", 10L); // a concurrent ACL mutation finalized on the ANCESTOR
        assertFalse(svc.revalidate(s), "an ancestor epoch bump must force a restart");
    }

    @Test
    void revalidateDetectsAMoveOfTheTargetItself() {
        seedFolder("root", null, false, 3L);
        seedFolder("other", null, false, 4L);
        seedDocument("leaf", "root", true, 1L);
        Snapshot s = svc.snapshot(contentDb, "leaf");

        reparent("leaf", "other"); // a move rewrites the child (parentId + _rev)
        assertFalse(svc.revalidate(s), "a move must force a restart (topology change)");
    }

    @Test
    void revalidateDetectsAnInheritanceFlip() {
        seedFolder("root", null, false, 3L);
        seedDocument("leaf", "root", true, 1L);
        Snapshot s = svc.snapshot(contentDb, "leaf");

        setAclInherited("leaf", false); // the leaf stops inheriting → the chain changed
        assertFalse(svc.revalidate(s));
    }

    @Test
    void revalidateDetectsADeletedDependency() {
        seedFolder("root", null, false, 3L);
        seedDocument("leaf", "root", true, 1L);
        Snapshot s = svc.snapshot(contentDb, "leaf");

        deleteDoc("root");
        assertFalse(svc.revalidate(s), "a vanished dependency must force a restart");
    }

    @Test
    void revalidateDetectsATouchThatDoesNotChangeAnyEpoch() {
        // Even a change that leaves every epoch equal invalidates the snapshot: the _rev moved, so
        // the recorded reading is no longer provably the one the readers were computed from.
        seedFolder("root", null, false, 3L);
        seedDocument("leaf", "root", true, 1L);
        Snapshot s = svc.snapshot(contentDb, "leaf");

        Document d = get("root");
        Map<String, Object> p = d.getProperties();
        p.put("name", "renamed");
        d.setProperties(p);
        put(d);

        assertFalse(svc.revalidate(s), "any _rev change forces a restart");
    }

    @Test
    void revalidateAppliesThePendingGateToTheCurrentState() {
        seedFolder("root", null, false, 3L);
        seedDocument("leaf", "root", true, 1L);
        Snapshot s = svc.snapshot(contentDb, "leaf");

        // A new ACL mutation commits its Phase-1 marker on the ancestor AFTER our walk.
        seedRaw("root", "{\"aclInherited\":false,\"aclSourceEpoch\":3,"
                + "\"aclEpochState\":\"PENDING_EPOCH\",\"aclEpochMutationId\":\""
                + AclEpochState.newMutationId() + "\"}");

        assertThrows(AclEpochPendingException.class, () -> svc.revalidate(s),
                "revalidation re-applies the pending gate to the CURRENT document");
    }

    @Test
    void revalidateRejectsANullSnapshot() {
        assertThrows(IllegalArgumentException.class, () -> svc.revalidate(null));
    }

    @Test
    void snapshotRejectsBlankArguments() {
        assertThrows(IllegalArgumentException.class, () -> svc.snapshot(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> svc.snapshot(contentDb, " "));
    }

    // ── fixtures / helpers ─────────────────────────────────────────

    private void seedFolder(String id, String parentId, boolean inherits, long epoch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("document", false);
        p.put("name", id);
        if (parentId != null) p.put(AclEffectiveEpochService.FIELD_PARENT_ID, parentId);
        p.put(AclEffectiveEpochService.FIELD_ACL_INHERITED, inherits);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        put(id, p);
    }

    private void seedDocument(String id, String parentId, boolean inherits, long epoch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("document", true);
        p.put("name", id);
        if (parentId != null) p.put(AclEffectiveEpochService.FIELD_PARENT_ID, parentId);
        p.put(AclEffectiveEpochService.FIELD_ACL_INHERITED, inherits);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        put(id, p);
    }

    private void seedRelationship(String id, String sourceId, String targetId, long epoch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", id);
        p.put(AclEffectiveEpochService.FIELD_SOURCE_ID, sourceId);
        p.put(AclEffectiveEpochService.FIELD_TARGET_ID, targetId);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        put(id, p);
    }

    /** PUT an EXACT raw JSON body (guarantees explicit nulls / absent fields). */
    private void seedRaw(String id, String json) {
        try {
            String rev = revOf(id);
            String body = rev == null ? json
                    : json.replaceFirst("^\\{", "{\"_rev\":\"" + rev + "\",");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + contentDb + "/" + id))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) throw new IllegalStateException("raw put failed: " + resp.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void bumpEpoch(String id, long epoch) {
        Document d = get(id);
        Map<String, Object> p = d.getProperties();
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        d.setProperties(p);
        put(d);
    }

    private void reparent(String id, String newParentId) {
        Document d = get(id);
        Map<String, Object> p = d.getProperties();
        p.put(AclEffectiveEpochService.FIELD_PARENT_ID, newParentId);
        d.setProperties(p);
        put(d);
    }

    private void setAclInherited(String id, boolean inherited) {
        Document d = get(id);
        Map<String, Object> p = d.getProperties();
        p.put(AclEffectiveEpochService.FIELD_ACL_INHERITED, inherited);
        d.setProperties(p);
        put(d);
    }

    private void deleteDoc(String id) {
        String rev = revOf(id);
        if (rev != null) {
            cloudant.deleteDocument(new DeleteDocumentOptions.Builder()
                    .db(contentDb).docId(id).rev(rev).build()).execute();
        }
    }

    private Document get(String id) {
        return cloudant.getDocument(new GetDocumentOptions.Builder()
                .db(contentDb).docId(id).build()).execute().getResult();
    }

    private void put(Document d) {
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(contentDb).docId(d.getId()).document(d).build()).execute();
    }

    private void put(String id, Map<String, Object> props) {
        Document d = new Document();
        d.setId(id);
        String rev = revOf(id);
        if (rev != null) d.setRev(rev);
        d.setProperties(props);
        put(d);
    }

    private String revOf(String id) {
        try {
            Document d = cloudant.getDocument(new GetDocumentOptions.Builder()
                    .db(contentDb).docId(id).build()).execute().getResult();
            return d == null ? null : d.getRev();
        } catch (NotFoundException e) {
            return null;
        }
    }

    private static String cfg(String sysProp, String envVar, String dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
