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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
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
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.AclEpochPendingException;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.AclEpochUnavailableException;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.DependencyRole;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.Snapshot;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.config.ObjectMapperFactory;

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
                new CloudantClientWrapper(cloudant, contentDb, ObjectMapperFactory.createDefaultObjectMapper());
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(contentDb)).thenReturn(contentWrapper);

        svc = new AclEffectiveEpochService();
        svc.setConnectorPool(pool);

        // REQUIRED since review P1-1: the walk's inheritance-stop rule needs the root-folder id, the
        // same one the readers projection uses. The fixtures below use "root".
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        lenient().when(info.getRootFolderId()).thenReturn("root");
        lenient().when(info.getPrincipalIdAnyone()).thenReturn("GROUP_EVERYONE");
        lenient().when(info.getPrincipalIdAnonymous()).thenReturn("anonymous");
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap infoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        lenient().when(infoMap.get(contentDb)).thenReturn(info);
        svc.setRepositoryInfoMap(infoMap);
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

    /**
     * A root folder stored with an EXPLICIT-null {@code parentId} must walk, not throw.
     *
     * <p>The strict "present-null is corruption" rule (increment 2e) is right for the fields the
     * epoch machinery writes itself. {@code parentId} is CMIS topology written by the DAO, and "no
     * parent" is the normal state of a root — whether that lands as an absent key or an explicit
     * null is a serialization detail of whichever path created the repository.
     *
     * <p>Found by RUNNING the gate-2 migration on the dev stack, not by reasoning: {@code bedroom}'s
     * root has the key absent and stamped fine, while {@code canopy}'s root — created by the
     * different, init-time path — has it present-null and threw
     * {@code has a present-but-invalid parentId (null / non-String / blank): null}. Every walk
     * climbs to the root, so that ONE document would have failed EVERY ACL update in that repository
     * the moment the writer was wired. No existing test could see it: {@code seedFolder} OMITS the
     * key when the parent is null, and the model objects the unit tests build cannot express the
     * distinction at all.
     */
    @Test
    void aRootStoredWithAnEXPLICITNullParentIdWalksLikeAnAbsentOne() {
        seedFolderWithExplicitNullParent("root", false, 3L);
        seedFolder("mid", "root", true, 9L);

        Snapshot s = svc.snapshot(contentDb, "mid");
        assertNotNull(s, "an explicit-null parentId on the ROOT must not fail the whole walk");
        assertEquals(9L, s.effectiveEpoch, "max(mid=9, root=3) — the root still contributes");
        assertEquals(2, s.dependencies.size());

        // And the object itself is resolvable, which is what the migration needs.
        Snapshot self = svc.snapshot(contentDb, "root");
        assertNotNull(self);
        assertEquals(3L, self.effectiveEpoch);
    }

    /** Seed a folder whose parentId key is PRESENT with a null value, as canopy's root is. */
    private void seedFolderWithExplicitNullParent(String id, boolean inherits, long epoch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:folder");
        p.put("objectType", "cmis:folder");
        p.put("document", false);
        p.put("name", id);
        p.put(AclEffectiveEpochService.FIELD_PARENT_ID, null);   // the whole point
        p.put(AclEffectiveEpochService.FIELD_ACL_INHERITED, inherits);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        put(id, p);
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
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"parentId\":\"root\",\"aclSourceEpoch\":1}"); // no aclInherited

        Snapshot s = svc.snapshot(contentDb, "leaf");
        assertEquals(7L, s.effectiveEpoch, "an absent aclInherited must inherit (default TRUE)");
    }

    @Test
    void absentAclSourceEpochCountsAsZeroForPreMigrationContent() {
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false}");            // pre-migration: no epoch
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"parentId\":\"root\",\"aclInherited\":true}"); // pre-migration

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
        seedRaw("leaf", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochState\":\"PENDING_EPOCH\","
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
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochState\":\"PENDING_EPOCH\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");
        seedDocument("leaf", "root", true, 2L);

        AclEpochPendingException e = assertThrows(AclEpochPendingException.class,
                () -> svc.snapshot(contentDb, "leaf"));
        assertEquals("root", e.dependencyId, "the gate names the mid-mutation ANCESTOR");
    }

    @Test
    void finalizedNeedsReconcileAlsoGatesButReconcileEnqueuedDoesNot() {
        seedRaw("gated", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochState\":\"FINALIZED_NEEDS_RECONCILE\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\",\"aclSourceEpoch\":6}");
        assertThrows(AclEpochPendingException.class, () -> svc.snapshot(contentDb, "gated"),
                "FINALIZED_NEEDS_RECONCILE is mid-CAS-ambiguous and must gate");

        seedRaw("settled", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochState\":\"RECONCILE_ENQUEUED\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\",\"aclSourceEpoch\":6}");
        Snapshot s = svc.snapshot(contentDb, "settled");
        assertEquals(6L, s.effectiveEpoch, "RECONCILE_ENQUEUED is settled and must NOT gate");
    }

    // ── fail-closed data rules ─────────────────────────────────────

    @Test
    void quarantinedDependencyIsRefused() {
        seedRaw("q", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":4,\"aclEpochQuarantined\":true}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "q"),
                "a document the epoch machine quarantined must never feed a fence value");
    }

    @Test
    void nonIntegerAndNegativeEpochsAreRefused() throws Exception {
        seedRaw("frac", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":1.5}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "frac"));

        seedRaw("strv", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":\"3\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "strv"));

        seedRaw("nullv", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":null}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "nullv"),
                "an explicit-null epoch is PRESENT (SDK contract) = corruption, not 'absent'");

        seedRaw("neg", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":-2}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "neg"));
    }

    @Test
    void unknownEpochStateIsRefused() {
        seedRaw("weird", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochState\":\"MYSTERY\"}");
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
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochState\":\"PENDING_EPOCH\","
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
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":3,"
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

    // ── review 3a [P1] #1: dangling endpoints are recorded as NEGATIVE dependencies ──

    @Test
    void danglingEndpointIsRecordedSoItsRecreationInvalidatesTheSnapshot() {
        // THE bug this closes: the relationship document itself never changes when a missing
        // endpoint is (re)created under the same id, so without a recorded ABSENCE the snapshot
        // would still revalidate and we would CAS a fence value computed without that chain.
        seedFolder("root", null, false, 1L);
        seedDocument("tgt", "root", true, 8L);
        seedRelationship("rel", "gone", "tgt", 2L); // source does not exist

        Snapshot s = svc.snapshot(contentDb, "rel");
        assertEquals(8L, s.effectiveEpoch);
        assertTrue(s.dependencies.stream().anyMatch(d -> "gone".equals(d.id) && !d.exists),
                "the absence must be RECORDED as a negative dependency: " + s.dependencies);
        assertTrue(svc.revalidate(s), "still absent → still valid");

        seedDocument("gone", "root", true, 99L); // the endpoint is created under the same id
        assertFalse(svc.revalidate(s),
                "a recreated endpoint must invalidate the snapshot (the relationship doc is untouched)");

        Snapshot again = svc.snapshot(contentDb, "rel");
        assertEquals(99L, again.effectiveEpoch, "the recomputed epoch now includes the endpoint");
    }

    @Test
    void recordedAbsenceThatStaysAbsentDoesNotFalselyInvalidate() {
        seedFolder("root", null, false, 1L);
        seedDocument("src", "root", true, 3L);
        seedRelationship("rel", "src", "missing-target", 1L);

        Snapshot s = svc.snapshot(contentDb, "rel");
        assertTrue(s.dependencies.stream().anyMatch(d -> "missing-target".equals(d.id) && !d.exists));
        assertTrue(svc.revalidate(s), "an absence that is still an absence must revalidate");
    }

    // ── review 3a [P1] #2: quarantine PRESENCE (not just true) disqualifies ──

    @Test
    void falseQuarantineMarkerIsMalformedAndRefused() {
        // The state machine's contract: absent = usable, true = quarantined, anything else present
        // = malformed. Accepting `false` would let a corrupt document contribute a high epoch.
        seedRaw("qfalse", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":900,"
                + "\"aclEpochQuarantined\":false}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "qfalse"));
    }

    @Test
    void nullAndNonBooleanQuarantineMarkersAreRefused() {
        seedRaw("qnull", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochQuarantined\":null}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "qnull"));

        seedRaw("qstr", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochQuarantined\":\"no\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "qstr"));
    }

    @Test
    void aQuarantinedAncestorBlocksTheWholeSubtree() {
        // The accepted trade-off, pinned: a quarantined ancestor stops its subtree until repaired.
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":5,"
                + "\"aclEpochQuarantined\":true}");
        seedDocument("leaf", "root", true, 1L);
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    // ── review 3a [P1] #3: state invariants (ENQUEUED does not gate → must be validated) ──

    @Test
    void reconcileEnqueuedWithoutAnEpochIsRefused() {
        // ENQUEUED does NOT gate, so an unvalidated one becomes a fence value directly.
        seedRaw("enq-noepoch", "{\"type\":\"cmis:folder\",\"aclInherited\":false,"
                + "\"aclEpochState\":\"RECONCILE_ENQUEUED\",\"aclEpochMutationId\":\""
                + AclEpochState.newMutationId() + "\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "enq-noepoch"));
    }

    @Test
    void reconcileEnqueuedWithZeroOrNegativeEpochIsRefused() {
        String m = AclEpochState.newMutationId();
        seedRaw("enq-zero", "{\"type\":\"cmis:folder\",\"aclInherited\":false,"
                + "\"aclEpochState\":\"RECONCILE_ENQUEUED\",\"aclEpochMutationId\":\"" + m
                + "\",\"aclSourceEpoch\":0}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "enq-zero"));

        seedRaw("enq-neg", "{\"type\":\"cmis:folder\",\"aclInherited\":false,"
                + "\"aclEpochState\":\"RECONCILE_ENQUEUED\",\"aclEpochMutationId\":\"" + m
                + "\",\"aclSourceEpoch\":-4}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "enq-neg"));
    }

    @Test
    void reconcileEnqueuedWithMissingOrNonUuidMutationIdIsRefused() {
        seedRaw("enq-nomut", "{\"type\":\"cmis:folder\",\"aclInherited\":false,"
                + "\"aclEpochState\":\"RECONCILE_ENQUEUED\",\"aclSourceEpoch\":4}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "enq-nomut"));

        seedRaw("enq-badmut", "{\"type\":\"cmis:folder\",\"aclInherited\":false,"
                + "\"aclEpochState\":\"RECONCILE_ENQUEUED\",\"aclEpochMutationId\":\"not-a-uuid\","
                + "\"aclSourceEpoch\":4}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "enq-badmut"));
    }

    @Test
    void aValidReconcileEnqueuedAncestorStillContributesItsEpoch() {
        // The positive control: the added strictness must not break the settled path.
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,"
                + "\"aclEpochState\":\"RECONCILE_ENQUEUED\",\"aclEpochMutationId\":\""
                + AclEpochState.newMutationId() + "\",\"aclSourceEpoch\":11}");
        seedDocument("leaf", "root", true, 2L);
        assertEquals(11L, svc.snapshot(contentDb, "leaf").effectiveEpoch);
    }

    // ── review 3a [P1] #4: relationship detection + strict topology fields ──

    @Test
    void aSubtypedRelationshipIsStillDetectedByItsBaseType() {
        // Typed ingest relationships (nemaki:hasAttachment, …) keep type=cmis:relationship and
        // only differ in objectType — both endpoint chains must still be walked.
        seedFolder("root", null, false, 1L);
        seedDocument("src", "root", true, 21L);
        seedDocument("tgt", "root", true, 3L);
        seedRelationship("rel", "src", "tgt", 1L, "nemaki:hasAttachment");

        assertEquals(21L, svc.snapshot(contentDb, "rel").effectiveEpoch,
                "a SUBTYPED relationship must still walk both endpoint chains");
    }

    @Test
    void aRelationshipWithAMalformedEndpointFieldIsRefusedNotDemotedToContent() {
        // The pre-3a heuristic ("has both endpoint fields") silently demoted these to ordinary
        // content and dropped BOTH chains from the fence value.
        seedFolder("root", null, false, 1L);
        seedDocument("tgt", "root", true, 7L);

        seedRaw("rel-null", "{\"type\":\"cmis:relationship\",\"sourceId\":null,\"targetId\":\"tgt\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "rel-null"));

        seedRaw("rel-blank", "{\"type\":\"cmis:relationship\",\"sourceId\":\"  \",\"targetId\":\"tgt\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "rel-blank"));

        seedRaw("rel-num", "{\"type\":\"cmis:relationship\",\"sourceId\":42,\"targetId\":\"tgt\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "rel-num"));

        seedRaw("rel-missing", "{\"type\":\"cmis:relationship\",\"targetId\":\"tgt\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "rel-missing"));
    }

    @Test
    void relationshipWithBothEndpointFieldsMissingIsRefusedNotWalkedAsOrdinaryContent() {
        // The sharpest case for TYPE-based detection: with the old "has both endpoint fields"
        // heuristic this is not recognised as a relationship at all, so it is walked as ordinary
        // content and SILENTLY yields a fence value computed without either endpoint chain.
        seedRaw("rel-none", "{\"type\":\"cmis:relationship\",\"aclInherited\":false,\"aclSourceEpoch\":2}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "rel-none"),
                "a relationship with no endpoint ids cannot have an effective epoch computed");
    }

    @Test
    void aNonRelationshipCarryingEndpointFieldsIsRefused() {
        // We cannot tell whether those chains belong in the fence value → fail closed.
        seedRaw("odd", "{\"type\":\"cmis:document\",\"aclInherited\":false,"
                + "\"sourceId\":\"a\",\"targetId\":\"b\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "odd"));
    }

    /**
     * A MALFORMED parentId is still an anomaly; an explicit-NULL one is not.
     *
     * <p>This test used to require the null case to throw as well, on the reasoning that degrading a
     * malformed parentId to "no parent" would silently drop the inherited chain. That reasoning does
     * not survive contact with the CMIS side: {@code CouchContent} reads the field as
     * {@code (String) properties.get("parentId")}, which yields {@code null} for an ABSENT key and
     * for an EXPLICIT null alike, and {@code ContentServiceImpl} then tests {@code getParentId() ==
     * null}. The CMIS implementation is structurally incapable of telling the two apart — so making
     * the epoch side throw on one and walk on the other is precisely the two-implementations
     * divergence 5R/5S exists to eliminate.
     *
     * <p>It is not theoretical: {@code canopy}'s ROOT FOLDER is stored with an explicit-null
     * parentId (its creation path differs from {@code bedroom}'s, whose root omits the key), and
     * every walk climbs to the root — so this rule failed EVERY object in that repository. The
     * gate-2 migration run surfaced it; no unit test could, because model objects cannot express the
     * distinction at all.
     *
     * <p>Blank and non-String stay anomalies: those are values CMIS cannot use either (a blank id
     * resolves to no folder; a number throws {@code ClassCastException} in the DAO), so refusing
     * them keeps the two sides in agreement rather than breaking it.
     */
    @Test
    void aMalformedParentIdIsAnomaly_butAnExplicitNullIsJustNoParent() {
        seedRaw("pnull", "{\"type\":\"cmis:document\",\"aclInherited\":true,\"parentId\":null,"
                + "\"aclSourceEpoch\":4}");
        Snapshot s = svc.snapshot(contentDb, "pnull");
        assertNotNull(s, "explicit null == absent == no parent, exactly as CMIS reads it");
        assertEquals(4L, s.effectiveEpoch);

        seedRaw("pblank", "{\"type\":\"cmis:document\",\"aclInherited\":true,\"parentId\":\"  \"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "pblank"));

        seedRaw("pnum", "{\"type\":\"cmis:document\",\"aclInherited\":true,\"parentId\":7}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "pnum"));
    }

    @Test
    void contentWithoutATypeDiscriminatorIsRefusedNotGuessedAtRuntime() {
        // INVERTED in review 3b [P1]: the previous test pinned the UNSAFE behaviour (guessing
        // "ordinary content"). Guessing silently drops a relationship's endpoint chains, so a
        // document with no type/objectType is now an anomaly — pre-discriminator data needs an
        // explicit migration, not a runtime fallback.
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":6}");
        seedRaw("leaf", "{\"parentId\":\"root\",\"aclInherited\":true,\"aclSourceEpoch\":1}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    @Test
    void objectTypeAloneIsAcceptedAsTheDiscriminatorLikeTheContentDao() {
        // ContentDaoServiceImpl.getContent falls back to objectType when type is absent; the walk
        // must agree, or the two layers would disagree about what a document is.
        seedRaw("root", "{\"objectType\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":6}");
        seedRaw("leaf", "{\"objectType\":\"cmis:document\",\"parentId\":\"root\","
                + "\"aclInherited\":true,\"aclSourceEpoch\":1}");
        assertEquals(6L, svc.snapshot(contentDb, "leaf").effectiveEpoch);
    }

    @Test
    void legacyShortTypeFormsAreAcceptedLikeTheContentDao() {
        // The DAO accepts "folder"/"document" as well as the cmis: forms.
        seedRaw("root", "{\"type\":\"folder\",\"aclInherited\":false,\"aclSourceEpoch\":9}");
        seedRaw("leaf", "{\"type\":\"document\",\"parentId\":\"root\",\"aclInherited\":true}");
        assertEquals(9L, svc.snapshot(contentDb, "leaf").effectiveEpoch);
    }

    @Test
    void unrecognisedOrMalformedTypeIsRefused() {
        seedRaw("weirdtype", "{\"type\":\"nemaki:notABaseType\",\"aclInherited\":false}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "weirdtype"));

        seedRaw("nulltype", "{\"type\":null,\"aclInherited\":false}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "nulltype"));

        seedRaw("numtype", "{\"type\":5,\"aclInherited\":false}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "numtype"));
    }

    @Test
    void aNonFolderAncestorIsRefusedBecauseReadersResolveParentsViaGetFolder() {
        // The readers computation resolves parents with getFolder(), which returns null for a
        // non-folder and then fails closed under strict mode. If the epoch walk accepted a
        // document as an ancestor the two would see DIFFERENT dependency sets (review 3b [P1]).
        seedDocument("notAFolder", null, false, 50L); // a cmis:document acting as a "parent"
        seedDocument("child", "notAFolder", true, 1L);
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "child"));
    }

    @Test
    void anItemInheritsFromItsFolderAncestor() {
        // cmis:item (nemaki:user / nemaki:group live under .system) is legal content with an ACL.
        seedFolder("sys", null, false, 12L);
        seedRaw("anItem", "{\"type\":\"cmis:item\",\"objectType\":\"nemaki:user\","
                + "\"parentId\":\"sys\",\"aclInherited\":true,\"aclSourceEpoch\":2}");
        assertEquals(12L, svc.snapshot(contentDb, "anItem").effectiveEpoch);
    }

    // ── review 3b [P2]: the hop cap is an exact boundary ──

    @Test
    void aChainOfExactlyMaxAncestorHopsSucceeds() {
        // OFF-BY-ONE regression: the old loop bound rejected an exactly-at-the-limit chain,
        // permanently blocking a legitimately deep subtree from ever being re-indexed.
        int cap = 4;
        svc.setMaxAncestorHops(cap);
        seedFolder("n0", null, false, 7L);            // the non-inheriting top
        for (int i = 1; i <= cap; i++) {
            seedFolder("n" + i, "n" + (i - 1), true, i);
        }
        // n4 → n3 → n2 → n1 → n0 = EXACTLY cap ancestors above the leaf.
        assertEquals(7L, svc.snapshot(contentDb, "n" + cap).effectiveEpoch,
                "a chain of exactly maxAncestorHops ancestors must succeed");
    }

    @Test
    void aChainOfMaxAncestorHopsPlusOneFails() {
        int cap = 4;
        svc.setMaxAncestorHops(cap);
        seedFolder("m0", null, false, 7L);
        for (int i = 1; i <= cap + 1; i++) {
            seedFolder("m" + i, "m" + (i - 1), true, i);
        }
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "m" + (cap + 1)),
                "one hop past the cap must fail closed");
    }

    // ── review 3b [P1]: state lost but the mutation id survived ──

    @Test
    void mutationIdWithoutAStateIsRefusedAsSettledContent() {
        // The steady state clears BOTH. A leftover mutation id with no state means the marker was
        // lost (e.g. during a move), so the surviving aclSourceEpoch must NOT be used as settled —
        // otherwise a stale, possibly-high epoch fences out later correct writers forever.
        seedRaw("lost", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":900,"
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "lost"));
    }

    @Test
    void mutationIdWithoutAStateOnAnANCESTORIsAlsoRefused() {
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":900,"
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");
        seedDocument("leaf", "root", true, 1L);
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    // ── the persisted ACL → raw local ACEs (increment 5S step 2/3) ──
    //
    // Added because a self-review found this branch had NO coverage at all: the projection unit test
    // builds Dependencies directly, and every fixture above seeds documents WITHOUT an `acl` field,
    // so only the ABSENT branch was ever executed. The code that will feed production readers was
    // untested against a genuinely persisted ACL.

    @Test
    void parsesThePersistedAclIntoRawLocalAces() {
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"objectType\":\"cmis:document\","
                + "\"aclInherited\":false,\"aclSourceEpoch\":3,"
                + "\"acl\":{\"entries\":[{\"principal\":\"u1\",\"permissions\":[\"cmis:read\"]},"
                + "{\"principal\":\"g1\",\"permissions\":[\"cmis:read\",\"cmis:write\"]}]}}");

        List<jp.aegif.nemaki.model.Ace> aces = self("leaf").localAces;

        assertEquals(2, aces.size(), "both persisted entries");
        assertEquals("u1", aces.get(0).getPrincipalId());
        assertEquals(List.of("cmis:read"), aces.get(0).getPermissions());
        assertEquals("g1", aces.get(1).getPrincipalId());
        assertEquals(List.of("cmis:read", "cmis:write"), aces.get(1).getPermissions());
        assertTrue(aces.get(0).isDirect() && aces.get(1).isDirect(),
                "CouchAcl.convertToNemakiAcl marks every STORED entry direct=true; the epoch parser "
                        + "must agree or the merge would treat them as inherited");
    }

    @Test
    void anAbsentAclYieldsNoAces() {
        seedDocument("leaf", null, false, 1L);   // the helper writes no acl field at all
        assertTrue(self("leaf").localAces.isEmpty());
    }

    @Test
    void aPresentNullAclYieldsNoAces() {
        // convertToNemakiAcl returns null for a null ACL, which the CMIS side turns into no ACEs.
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,"
                + "\"aclSourceEpoch\":1,\"acl\":null}");
        assertTrue(self("leaf").localAces.isEmpty());
    }

    @Test
    void anAclWithoutEntriesYieldsNoAces() {
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,"
                + "\"aclSourceEpoch\":1,\"acl\":{}}");
        assertTrue(self("leaf").localAces.isEmpty());
    }

    @Test
    void aNonObjectAclIsAnAnomaly() {
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,"
                + "\"aclSourceEpoch\":1,\"acl\":\"not-an-object\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    @Test
    void nonListAclEntriesAreAnAnomaly() {
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,"
                + "\"aclSourceEpoch\":1,\"acl\":{\"entries\":\"nope\"}}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    @Test
    void aNullPrincipalIsAnAnomaly() {
        // CouchAcl NPEs on this, so it is not a form the CMIS layer tolerates either.
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,\"aclSourceEpoch\":1,"
                + "\"acl\":{\"entries\":[{\"principal\":null,\"permissions\":[\"cmis:read\"]}]}}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    /**
     * Review P2-5: a NON-STRING principal must be COERCED exactly as {@code CouchAcl} coerces it
     * ({@code .toString()}), not rejected. Rejecting it bought no safety — the id resolves to nothing
     * on either side — while permanently excluding from the index an object CMIS serves normally.
     */
    @Test
    void aNonStringPrincipalIsCoercedLikeCouchAclRatherThanRejected() {
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,\"aclSourceEpoch\":1,"
                + "\"acl\":{\"entries\":[{\"principal\":42,\"permissions\":[\"cmis:read\"]}]}}");

        List<jp.aegif.nemaki.model.Ace> aces = self("leaf").localAces;
        assertEquals(1, aces.size());
        assertEquals("42", aces.get(0).getPrincipalId(), "CouchAcl does principal.toString()");
    }

    /** Review P2-5: a BLANK principal is likewise kept, as CouchAcl keeps it. */
    @Test
    void aBlankPrincipalIsKeptLikeCouchAclRatherThanRejected() {
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,\"aclSourceEpoch\":1,"
                + "\"acl\":{\"entries\":[{\"principal\":\"  \",\"permissions\":[\"cmis:read\"]}]}}");

        List<jp.aegif.nemaki.model.Ace> aces = self("leaf").localAces;
        assertEquals(1, aces.size());
        assertEquals("  ", aces.get(0).getPrincipalId());
    }

    /** Review P3: a non-object ENTRY (the CMIS side blows up on the same cast). */
    @Test
    void aNonObjectAclEntryIsAnAnomaly() {
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,\"aclSourceEpoch\":1,"
                + "\"acl\":{\"entries\":[\"not-an-object\"]}}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    /** Review P3: non-list PERMISSIONS (distinct from the non-list `entries` case above). */
    @Test
    void nonListPermissionsAreAnAnomaly() {
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,\"aclSourceEpoch\":1,"
                + "\"acl\":{\"entries\":[{\"principal\":\"u1\",\"permissions\":\"cmis:read\"}]}}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    /**
     * Review P1-1: the walk and the readers projection must stop inheriting at the SAME node. A
     * corrupt ROOT — one carrying a parentId and an aclInherited that is not false — used to make the
     * walk climb PAST it (raising the effective epoch from a node the projection never reads) while
     * the projection stopped there. Both now use the shared predicate.
     */
    @Test
    void aCorruptRootStopsTheWALKToo_notOnlyTheProjection() {
        seedFolder("grandparent", null, false, 99L);      // must NOT contribute
        seedFolder("root", "grandparent", true, 5L);      // the root, corrupted: inherits + has a parent
        seedDocument("leaf", "root", true, 1L);

        AclEffectiveEpochService.Snapshot snap = svc.snapshot(contentDb, "leaf");

        assertEquals(5L, snap.effectiveEpoch, "the walk must stop AT the root, as the projection does; "
                + "climbing past it would pick up the grandparent's epoch 99");
        assertTrue(snap.dependencies.stream().noneMatch(d -> "grandparent".equals(d.id)),
                "a node the projection never reads must not be a recorded dependency either");
    }

    @Test
    void aNonStringPermissionIsAnAnomaly() {
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"aclInherited\":false,\"aclSourceEpoch\":1,"
                + "\"acl\":{\"entries\":[{\"principal\":\"u1\",\"permissions\":[42]}]}}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.snapshot(contentDb, "leaf"));
    }

    /** The SELF dependency of a snapshot (recorded first by the walk). */
    private AclEffectiveEpochService.Dependency self(String id) {
        AclEffectiveEpochService.Snapshot snap = svc.snapshot(contentDb, id);
        assertNotNull(snap, "the object must exist: " + id);
        return snap.dependencies.get(0);
    }

    // ── fixtures / helpers ─────────────────────────────────────────


    // ── A3: per-traversal ancestor memo (design §4.6) ──────────────────────────────────────

    /** Every field a later step consumes, rendered so a mismatch names itself. */
    private static String fingerprint(Snapshot s) {
        // Every field a later step consumes — review found the first version omitted state,
        // kind, the relationship endpoints and, worst, localAces: the readers projection is
        // computed FROM those, so a memo that returned the right topology with the wrong ACL
        // would have produced identical fingerprints and a different answer.
        StringBuilder b = new StringBuilder("epoch=").append(s.effectiveEpoch).append(" deps=[");
        for (AclEffectiveEpochService.Dependency d : s.dependencies) {
            b.append(d.id).append('/').append(d.role).append('/').append(d.rev)
             .append('/').append(d.sourceEpoch).append('/').append(d.exists)
             .append('/').append(d.parentId).append('/').append(d.aclInherited)
             .append('/').append(d.state).append('/').append(d.kind)
             .append('/').append(d.sourceId).append('/').append(d.targetId)
             .append('/').append(aces(d)).append(' ');
        }
        return b.append(']').toString();
    }

    /** The dependency's own ACL, rendered stably. */
    private static String aces(AclEffectiveEpochService.Dependency d) {
        if (d.localAces == null) {
            return "-";
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (jp.aegif.nemaki.model.Ace a : d.localAces) {
            out.add(a.getPrincipalId() + ":" + a.getPermissions());
        }
        java.util.Collections.sort(out);
        return out.toString();
    }

    /**
     * A memoised traversal must produce the SAME snapshot as an un-memoised one.
     *
     * <p>Compared on the whole dependency set — id, role, {@code _rev}, source epoch, existence,
     * parent and inheritance flag — not just the effective epoch. The epoch is a max over the
     * chain, so it survives a walk that recorded the wrong ancestors; the dependency list is what
     * revalidation and the readers projection actually consume, and it is where a memo that
     * returned a document under the wrong role or skipped a link would show up.
     */
    @Test
    void aMemoisedTraversalProducesTheSameSnapshot() {
        seedFolder("root", null, false, 3L);
        seedFolder("mid", "root", true, 9L);
        seedDocument("a", "mid", true, 4L);
        seedDocument("b", "mid", true, 5L);

        AclEffectiveEpochService.TraversalMemo memo = new AclEffectiveEpochService.TraversalMemo();
        // 'a' first populates the memo with mid + root; 'b' should then be served from it.
        Snapshot cachedA = svc.snapshot(contentDb, "a", memo);
        Snapshot cachedB = svc.snapshot(contentDb, "b", memo);

        assertEquals(fingerprint(svc.snapshot(contentDb, "a")), fingerprint(cachedA));
        assertEquals(fingerprint(svc.snapshot(contentDb, "b")), fingerprint(cachedB));
        assertTrue(memo.hits() > 0,
                "the second walk must actually have been served from the memo, or this test"
                        + " proves only that two uncached walks agree");
    }

    /**
     * The SELF read is never memoised, so the map stays ancestor-sized.
     *
     * <p>Memoising the target would make the map O(subtree): the re-drive snapshots every
     * descendant. The claim "it holds the ancestor set, not the descendants" is what makes this
     * safe to enable on a 100k-descendant propagation, so it is asserted rather than assumed.
     */
    @Test
    void theMemoHoldsAncestorsNotTargets() {
        seedFolder("root", null, false, 1L);
        seedFolder("mid", "root", true, 1L);
        for (int i = 0; i < 10; i++) {
            seedDocument("leaf-" + i, "mid", true, 1L);
        }

        AclEffectiveEpochService.TraversalMemo memo = new AclEffectiveEpochService.TraversalMemo();
        for (int i = 0; i < 10; i++) {
            svc.snapshot(contentDb, "leaf-" + i, memo);
        }

        assertEquals(2, memo.size(),
                "only mid and root belong in the memo; ten targets would mean it grows with the"
                        + " subtree instead of the ancestor chain");
    }

    /**
     * A folder-heavy tree must not let the memo grow without bound.
     *
     * <p>The narrow test above uses ten leaves under ONE chain, and review pointed out that such
     * a shape cannot reveal the real growth: every visited FOLDER becomes an ancestor the moment
     * one of its children is snapshotted. A root holding N folders with a document each therefore
     * retains ~N raw documents — the very case a 100k-node propagation hits, where an
     * OutOfMemoryError would abandon the traversal rather than merely slow it.
     *
     * <p>The cap is set low here so the bound is exercised without seeding 50,000 folders; what
     * is being asserted is that a bound EXISTS and holds, not the production number.
     */
    @Test
    void aBranchedTreeCannotGrowTheMemoWithoutBound() {
        seedFolder("root", null, false, 1L);
        int branches = 40;
        int cap = 8;
        for (int i = 0; i < branches; i++) {
            seedFolder("br-" + i, "root", true, 1L);
            seedDocument("doc-" + i, "br-" + i, true, 1L);
        }

        AclEffectiveEpochService.TraversalMemo memo = new AclEffectiveEpochService.TraversalMemo(cap);
        for (int i = 0; i < branches; i++) {
            svc.snapshot(contentDb, "doc-" + i, memo);
        }

        assertTrue(memo.size() <= cap,
                "every branch folder becomes an ancestor, so without a bound this would hold "
                        + branches + " entries; it holds " + memo.size());
        assertTrue(memo.evictions() > 0, "and the bound must actually have been exercised");
        // The shared root is what everything reuses, so the optimisation still pays off.
        assertTrue(memo.hits() > 0,
                "an LRU must still serve the hot shared ancestor; a cache that only evicts is"
                        + " just overhead");
    }

    /**
     * A mid-mutation ancestor must NOT be memoised.
     *
     * <p>The pending gate throws on {@code PENDING_EPOCH}, and the finalizer is actively moving
     * that document — memoising it caches a value already known to be about to change. The cost
     * is not a wrong answer but a stalled traversal: every later descendant under that ancestor
     * would keep hitting the gate for the rest of the traversal, even after CouchDB settled it.
     * Without the memo those descendants re-read and made progress, so caching it would be a
     * convergence regression introduced by an optimisation.
     */
    @Test
    void aPendingAncestorIsNotMemoisedSoTheTraversalCanRecover() {
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochState\":\"PENDING_EPOCH\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");
        seedDocument("leaf", "root", true, 4L);
        seedDocument("leaf2", "root", true, 4L);

        AclEffectiveEpochService.TraversalMemo memo = new AclEffectiveEpochService.TraversalMemo();
        assertThrows(AclEpochPendingException.class, () -> svc.snapshot(contentDb, "leaf", memo));
        assertEquals(0, memo.size(),
                "the pending ancestor must not be in the memo: it is the one document guaranteed"
                        + " to change, and keeping it stalls the rest of the traversal");

        // The finalizer settles it, exactly as it would mid-traversal.
        seedFolder("root", null, false, 7L);

        Snapshot s = svc.snapshot(contentDb, "leaf2", memo);
        assertEquals(7L, s.effectiveEpoch,
                "the next descendant in the SAME traversal must see the settled ancestor; a"
                        + " memoised PENDING would have thrown here instead");
    }

    /**
     * Revalidation must still refuse a snapshot whose ancestor changed — memo or no memo.
     *
     * <p>This is the test that matters. The memo's whole safety argument is that step 4 re-reads
     * the authoritative source before each CAS, so a stale reused ancestor is caught there. If
     * revalidation is ever changed to consult the memo, the fence loses its only detection
     * mechanism and every other test in this file still passes.
     */
    @Test
    void revalidationStillDetectsAnAncestorChangedMidTraversal() {
        seedFolder("root", null, false, 3L);
        seedFolder("mid", "root", true, 9L);
        seedDocument("leaf", "mid", true, 4L);

        seedDocument("leaf2", "mid", true, 4L);

        AclEffectiveEpochService.TraversalMemo memo = new AclEffectiveEpochService.TraversalMemo();
        svc.snapshot(contentDb, "leaf", memo);              // populates the memo
        long before = memo.hits();
        Snapshot withMemo = svc.snapshot(contentDb, "leaf2", memo); // built FROM the memo
        assertTrue(memo.hits() > before,
                "this snapshot has to be the one assembled from cached ancestors, or the test"
                        + " below proves nothing about memo-built snapshots");
        Snapshot withoutMemo = svc.snapshot(contentDb, "leaf2");
        assertTrue(svc.revalidate(withMemo), "unchanged sources revalidate");

        // An ancestor moves under us, exactly as a concurrent applyAcl would do.
        seedFolder("mid", "root", true, 11L);

        assertFalse(svc.revalidate(withMemo),
                "revalidation reads CouchDB directly; a snapshot built from a memo must be"
                        + " refused just the same, or the memo has silently disabled the fence");
        assertFalse(svc.revalidate(withoutMemo),
                "and the un-memoised snapshot is refused identically — the detection does not"
                        + " depend on how the snapshot was built");
    }

    /**
     * After a refused revalidation, the traversal must be able to make progress.
     *
     * <p>Detection alone is not enough. The writer's response to a refusal is to restart the
     * walk, and a memo that still holds the rejected ancestor hands the restart the very value
     * that was just refused — the same snapshot, the same refusal, for ever. Invalidation is
     * what turns "we noticed" into "we converged", so it is part of the contract, not an
     * implementation detail.
     */
    @Test
    void afterInvalidationTheRestartSeesTheNewAncestor() {
        seedFolder("root", null, false, 3L);
        seedFolder("mid", "root", true, 9L);
        seedDocument("leaf", "mid", true, 4L);

        AclEffectiveEpochService.TraversalMemo memo = new AclEffectiveEpochService.TraversalMemo();
        Snapshot first = svc.snapshot(contentDb, "leaf", memo);
        assertEquals(9L, first.effectiveEpoch);

        seedFolder("mid", "root", true, 11L);
        assertFalse(svc.revalidate(first), "the change is detected");

        // Without this the next line would return 9 for ever.
        memo.invalidateAll();

        Snapshot second = svc.snapshot(contentDb, "leaf", memo);
        assertEquals(11L, second.effectiveEpoch,
                "the restart must see the NEW ancestor epoch; a memo that survived the refusal"
                        + " would keep serving 9 and the writer could never finish");
        assertTrue(svc.revalidate(second), "and the fresh snapshot now revalidates");
    }

    /**
     * A memo that is NOT invalidated reproduces the non-convergence, so the test above is known
     * to be testing something.
     */
    @Test
    void withoutInvalidationTheRestartWouldSeeTheStaleAncestor() {
        seedFolder("root", null, false, 3L);
        seedFolder("mid", "root", true, 9L);
        seedDocument("leaf", "mid", true, 4L);

        AclEffectiveEpochService.TraversalMemo memo = new AclEffectiveEpochService.TraversalMemo();
        svc.snapshot(contentDb, "leaf", memo);
        seedFolder("mid", "root", true, 11L);

        Snapshot again = svc.snapshot(contentDb, "leaf", memo); // deliberately NOT invalidated
        assertEquals(9L, again.effectiveEpoch,
                "this is the loop the invalidation exists to break: the walk keeps producing the"
                        + " snapshot revalidation just rejected");
        assertFalse(svc.revalidate(again), "and it would be rejected again, for ever");
    }

    private void seedFolder(String id, String parentId, boolean inherits, long epoch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:folder");   // the real persisted base-type discriminator
        p.put("objectType", "cmis:folder");
        p.put("document", false);
        p.put("name", id);
        if (parentId != null) p.put(AclEffectiveEpochService.FIELD_PARENT_ID, parentId);
        p.put(AclEffectiveEpochService.FIELD_ACL_INHERITED, inherits);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        put(id, p);
    }

    private void seedDocument(String id, String parentId, boolean inherits, long epoch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:document");
        p.put("objectType", "cmis:document");
        p.put("document", true);
        p.put("name", id);
        if (parentId != null) p.put(AclEffectiveEpochService.FIELD_PARENT_ID, parentId);
        p.put(AclEffectiveEpochService.FIELD_ACL_INHERITED, inherits);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        put(id, p);
    }

    private void seedRelationship(String id, String sourceId, String targetId, long epoch) {
        seedRelationship(id, sourceId, targetId, epoch, "cmis:relationship");
    }

    /** A relationship whose SUBTYPE differs (objectType), as the ingest typed relationships do. */
    private void seedRelationship(String id, String sourceId, String targetId, long epoch,
                                  String objectType) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:relationship");   // base type — set by Relationship's constructor
        p.put("objectType", objectType);
        p.put("relationship", true);
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
