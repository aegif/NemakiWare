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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.acl.AclSemantics;
import jp.aegif.nemaki.acl.PrincipalLookup;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.epoch.AclEpochIndexWriter.WriteOutcome;
import jp.aegif.nemaki.epoch.AclEpochIndexWriter.WriteResult;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Deterministic concurrency tests for {@link AclEpochIndexWriter} against a LIVE CouchDB AND a LIVE
 * Solr (design §4.2 steps 3/5/6 + §4.3 — increment 4).
 *
 * <p>The reviewer's pre-wiring requirement is that the same-epoch collision, the 409, the
 * dependency change and the pending/quarantine paths are pinned DETERMINISTICALLY rather than by
 * timing. That is achieved by overriding the package-private {@code realtimeGet}, which is exactly
 * the point between step 3 (RTG) and step 5 (CAS): a subclass injects a competing Solr write or a
 * CouchDB dependency mutation there, so the race always happens.
 */
public class AclEpochIndexWriterIT {

    private static Cloudant cloudant;
    private static SolrClient solr;
    private static boolean available;

    private String contentDb;
    private AclEffectiveEpochService epochService;
    private String solrId;

    /**
     * Resolves an explicit set of principals — "u1"/"u2" as users, "g1"/"g2" and the configured
     * anyone group as groups. Everything else is genuinely absent.
     *
     * <p>Deliberately NOT prefix magic: an earlier {@code startsWith("g")} version failed to resolve
     * {@code GROUP_EVERYONE} (capital G), the token was dropped, and the fail-closed fallback turned
     * the whole set admin-only — the very stale-DENY these tests exist to catch, produced by the
     * fixture rather than by the code under test.
     *
     * <p>Only the REAL-PATH tests consult it; the protocol tests inject their readers directly.
     */
    private static final AclSemantics.PrincipalResolver RESOLVER = new AclSemantics.PrincipalResolver() {
        @Override public PrincipalLookup lookupUser(String repo, String id) {
            return List.of("u1", "u2").contains(id) ? PrincipalLookup.FOUND : PrincipalLookup.NOT_FOUND;
        }
        @Override public PrincipalLookup lookupGroup(String repo, String id) {
            return List.of("g1", "g2", "GROUP_EVERYONE").contains(id)
                    ? PrincipalLookup.FOUND : PrincipalLookup.NOT_FOUND;
        }
    };

    /**
     * A writer whose readers are CHOSEN rather than projected (increment 5S step 3).
     *
     * <p>Most tests here pin the WRITE PROTOCOL — the fence decision, the 409 restart, the
     * repository boundary — and those need a specific readers value at a specific moment, not one
     * that happens to fall out of a CouchDB fixture. Overriding the package-private
     * {@code computeReaders} is the same technique already used for {@code realtimeGet}.
     *
     * <p>The projection ITSELF is covered separately: {@code AclEffectiveEpochReadersProjectionTest}
     * pins its semantics, and {@link #realPathProjectsTheReadersFromTheSeededAcl} +
     * {@link #realPathInheritedSystemPrincipalSurvivesTheProjection} drive the UNOVERRIDDEN path so
     * the seam cannot silently diverge from what production runs.
     */
    private class TestWriter extends AclEpochIndexWriter {
        private final Function<AclEffectiveEpochService.Snapshot, List<String>> fn;

        TestWriter(Function<AclEffectiveEpochService.Snapshot, List<String>> fn) {
            this.fn = fn;
            setEffectiveEpochService(epochService);
        }

        @Override List<String> computeReaders(AclEffectiveEpochService.Snapshot snapshot,
                                              AclSemantics.PrincipalResolver resolver) {
            return fn == null ? super.computeReaders(snapshot, resolver) : fn.apply(snapshot);
        }
    }

    /** A wired writer that always returns {@code readers}. */
    private TestWriter writerReturning(List<String> readers) {
        return new TestWriter(snapshot -> readers);
    }

    /** A wired writer whose readers come from {@code fn} (for counting / null cases). */
    private TestWriter writerComputing(Function<AclEffectiveEpochService.Snapshot, List<String>> fn) {
        return new TestWriter(fn);
    }

    /** A wired writer that runs the REAL projection. */
    private TestWriter realWriter() {
        return new TestWriter(null);
    }

    @BeforeAll
    static void connect() {
        String url = cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL", "http://localhost:5984");
        String user = cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin");
        String pass = cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD", "password");
        String solrUrl = cfg("nemaki.test.solr.url", "NEMAKI_TEST_SOLR_URL", "http://localhost:8983/solr/nemaki");
        try {
            BasicAuthenticator auth = new BasicAuthenticator.Builder().username(user).password(pass).build();
            cloudant = new Cloudant("cloudant-service", auth);
            cloudant.setServiceUrl(url);
            cloudant.getDatabaseInformation(
                    new com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions.Builder()
                            .db(SystemConst.NEMAKI_CONF_DB).build()).execute();
            solr = new HttpJdkSolrClient.Builder(solrUrl).useHttp1_1(true).build();
            solr.ping();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        if (!available && Boolean.parseBoolean(
                cfg("nemaki.test.couchdb.required", "NEMAKI_TEST_COUCHDB_REQUIRED", "false"))) {
            throw new IllegalStateException("nemaki.test.couchdb.required=true but CouchDB and/or Solr "
                    + "are not reachable — the ACL epoch index-writer IT cannot run");
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(available, "CouchDB and/or Solr not reachable — skipping ACL epoch writer IT");
        contentDb = "epoch-writer-it-" + UUID.randomUUID();
        cloudant.putDatabase(new PutDatabaseOptions.Builder().db(contentDb).build()).execute();

        CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudant, contentDb, ObjectMapperFactory.createDefaultObjectMapper());
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(contentDb)).thenReturn(wrapper);

        epochService = new AclEffectiveEpochService();
        epochService.setConnectorPool(pool);

        // The REAL readers projection needs the root-folder id and the configured system principal
        // ids; the fixtures below use "root" as the repository root.
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        lenient().when(info.getRootFolderId()).thenReturn("root");
        lenient().when(info.getPrincipalIdAnyone()).thenReturn("GROUP_EVERYONE");
        lenient().when(info.getPrincipalIdAnonymous()).thenReturn("anonymous");
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap infoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        lenient().when(infoMap.get(contentDb)).thenReturn(info);
        epochService.setRepositoryInfoMap(infoMap);

        solrId = "epoch-writer-it-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (!available) return;
        try {
            cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(contentDb).build()).execute();
        } catch (Exception ignore) { /* best effort */ }
        try {
            // deleteByQuery on the per-test repository id, NOT deleteById(solrId): several tests
            // index documents under OTHER ids (the CAS-magic doc, extra endpoints), and those were
            // being left behind in the shared live core — one was still there carrying an
            // effective_acl_epoch, which is exactly the field the gate-2 migration counts. The
            // repository id is a per-test UUID, so this can never reach real data.
            solr.deleteByQuery("repository_id:\"" + contentDb + "\"");
            solr.commit();
        } catch (Exception ignore) { /* best effort */ }
    }

    // ── the happy path + the atomicity requirement ─────────────────

    @Test
    void writesTheAclGroupAndLeavesEVERYOTHERFieldUntouched() throws Exception {
        seedFolder("root", null, false, 5L);
        seedDocument(solrId, "root", true, 2L);
        indexSolrDoc(solrId, "the original name", "/root/the original name", "the original body");

        WriteOutcome o = writerReturning(List.of("group:g1", "user:u1"))
                .write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.UPDATED, o.result);
        assertEquals(5L, o.epoch, "max(self=2, root=5)");

        SolrDocument after = get(solrId);
        assertEquals(List.of("group:g1", "user:u1"), readers(after), "readers written canonically (sorted)");
        assertEquals(5L, num(after, "effective_acl_epoch"));
        // §4.4: the ACL group is written ALONE — a transient failure elsewhere must never clobber content.
        assertEquals("the original name", after.getFieldValue("name"));
        assertEquals("/root/the original name", after.getFieldValue("path"));
        assertEquals(List.of("the original body"), after.getFieldValues("content"),
                "the (multiValued) content field survives the ACL-group write");
        assertEquals(4242L, ((Number) after.getFieldValue("content_length")).longValue());
        assertEquals(contentDb, after.getFieldValue("repository_id"));
    }

    // ── A3: the writer owns the traversal memo's invalidation ──────

    /**
     * A refused revalidation must leave the writer able to finish.
     *
     * <p>This is the contract the IT for {@code AclEffectiveEpochService} cannot pin: that test
     * calls {@code memo.invalidateAll()} itself, so it passes whether or not production does.
     * The invalidation lives in {@link AclEpochIndexWriter}, and if it were dropped the writer
     * would loop — snapshot serves the stale ancestor from the memo, revalidate reads CouchDB and
     * correctly refuses, restart gets the same stale ancestor — until the attempt budget runs out.
     *
     * <p>The sequence needs no hooks. Priming the memo with one successful write and then moving
     * the ancestor in CouchDB puts the second call in exactly that position: its first attempt
     * computes from the memo's stale ancestor, revalidation refuses, and only invalidation lets
     * the restart see the new epoch.
     */
    @Test
    void aRefusedRevalidationInvalidatesTheMemoSoTheWriterConverges() throws Exception {
        seedFolder("root", null, false, 5L);
        seedDocument(solrId, "root", true, 2L);
        indexSolrDoc(solrId, "n", "/root/n", "b");

        AclEffectiveEpochService.TraversalMemo memo = new AclEffectiveEpochService.TraversalMemo();
        WriteOutcome first = writerReturning(List.of("group:g1"))
                .writeAllowingBootstrap(contentDb, solrId, solr, RESOLVER, memo);
        assertEquals(WriteResult.UPDATED, first.result);
        assertEquals(5L, first.epoch, "max(self=2, root=5)");
        assertTrue(memo.size() > 0, "the memo must now hold the ancestor, or nothing is primed");

        // The ancestor moves, exactly as a concurrent applyAcl would move it.
        seedFolder("root", null, false, 9L);

        WriteOutcome second = writerReturning(List.of("group:g1"))
                .writeAllowingBootstrap(contentDb, solrId, solr, RESOLVER, memo);
        assertEquals(WriteResult.UPDATED, second.result);
        assertEquals(9L, second.epoch,
                "the restart must compute from the NEW ancestor; 5 here would mean the memo"
                        + " survived the refusal and the writer wrote what revalidation rejected");
        assertTrue(second.attempts >= 2,
                "and it must have taken a restart to get there — one attempt would mean the"
                        + " stale ancestor was never served, so this test proved nothing");
        assertEquals(9L, num(get(solrId), "effective_acl_epoch"), "and that is what Solr holds");
    }

    @Test
    void notIndexedWhenTheSolrDocumentDoesNotExist() throws Exception {
        seedFolder("root", null, false, 1L);
        seedDocument(solrId, "root", true, 1L); // in CouchDB but never indexed
        WriteOutcome o = writerReturning(List.of("user:u1")).write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.NOT_INDEXED, o.result);
    }

    @Test
    void deletedObjectIsSkippedSoTheCallerCompletesRatherThanRetries() throws Exception {
        indexSolrDoc(solrId, "orphan", "/orphan", "body"); // indexed, but absent from CouchDB
        WriteOutcome o = writerReturning(List.of("user:u1")).write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.SKIPPED_DELETED, o.result);
    }

    // ── §4.3 fence decision ────────────────────────────────────────

    @Test
    void aStrictlyFresherStoredEpochIsNotOverwritten() throws Exception {
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:fresh"), 99L); // a fresher ACL already landed

        WriteOutcome o = writerReturning(List.of("user:stale")).write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.SKIPPED_FRESHER, o.result);
        assertEquals(List.of("user:fresh"), readers(get(solrId)), "the fresher readers survive");
        assertEquals(99L, num(get(solrId), "effective_acl_epoch"));
    }

    @Test
    void equalEpochWithIdenticalReadersIsTrueIdempotence() throws Exception {
        seedFolder("root", null, false, 7L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("group:g1", "user:u1"), 7L);

        WriteOutcome o = writerReturning(List.of("user:u1", "group:g1"))
                .write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.SKIPPED_IDEMPOTENT, o.result,
                "an order-only difference must be idempotent, not an endless divergence");
    }

    /**
     * The equal-epoch recompute must be a recompute, not a replay of the same cached ancestors.
     *
     * <p>That branch exists so conflicting writers converge on one authoritatively recomputed
     * answer. With a traversal memo and no invalidation the second walk is handed the same
     * ancestors and produces the same readers — the "recompute" resolves nothing and the
     * convergence mechanism becomes a loop that writes the payload it was supposed to re-derive.
     * The counter is what distinguishes the two, since both end in a successful write.
     */
    @Test
    void theEqualEpochRecomputeDropsTheMemoSoItIsNotAReplay() throws Exception {
        seedFolder("root", null, false, 7L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:stored-only"), 7L);

        AclEffectiveEpochService.TraversalMemo memo = new AclEffectiveEpochService.TraversalMemo();
        AtomicInteger computeCalls = new AtomicInteger();
        WriteOutcome o = writerComputing(snapshot -> {
            computeCalls.incrementAndGet();
            return List.of("user:authoritative");
        }).writeAllowingBootstrap(contentDb, solrId, solr, RESOLVER, memo);

        assertEquals(WriteResult.UPDATED, o.result);
        assertTrue(computeCalls.get() >= 2, "the divergence still forces a second walk");
        assertTrue(memo.invalidations() > 0,
                "and that walk must start from a cleared memo — otherwise it re-reads the same"
                        + " cached ancestors, derives the same readers, and the authoritative"
                        + " recompute is a replay of the value it was meant to re-derive");
    }

    @Test
    void equalEpochWithDivergentReadersRecomputesAuthoritativelyAndWrites() throws Exception {
        // The read-skew case: same epoch, different readers. The rule is NEVER "my payload wins" —
        // the writer recomputes from the authoritative sources and CASes THAT.
        seedFolder("root", null, false, 7L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:stored-only"), 7L);

        AtomicInteger computeCalls = new AtomicInteger();
        WriteOutcome o = writerComputing(snapshot -> {
            computeCalls.incrementAndGet();
            return List.of("user:authoritative");
        }).write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.UPDATED, o.result);
        assertTrue(computeCalls.get() >= 2, "the divergence forces an authoritative RECOMPUTE "
                + "(walk again), not a reuse of the first payload: " + computeCalls.get());
        assertEquals(List.of("user:authoritative"), readers(get(solrId)));
        assertEquals(7L, num(get(solrId), "effective_acl_epoch"), "the epoch is unchanged");
    }

    // ── the REAL projection (no override) ──────────────────────────

    /**
     * Drives {@link AclEpochIndexWriter#write} with the projection UNOVERRIDDEN, so the seam the
     * protocol tests above rely on cannot silently diverge from what production runs: the readers
     * that land in Solr are the ones {@code Snapshot.readers} computes from the seeded ACLs.
     */
    @Test
    void realPathProjectsTheReadersFromTheSeededAcl() throws Exception {
        seedFolder("root", null, false, 3L, List.of(ace("g1", "cmis:read")));
        seedDocument(solrId, "root", true, 1L, List.of(ace("u1", "cmis:read")));
        indexSolrDoc(solrId, "n", "/n", "b");

        WriteOutcome o = realWriter().write(contentDb, solrId, solr, RESOLVER);

        assertEquals(WriteResult.UPDATED, o.result);
        assertEquals(3L, o.epoch, "max(self=1, root=3)");
        assertEquals(List.of(AclSemantics.formatGroupReader(contentDb, "g1"),
                        AclSemantics.formatUserReader(contentDb, "u1")),
                readers(get(solrId)),
                "the object's own grant AND the inherited one, projected from the SAME snapshot");
    }

    /**
     * The asymmetry found in 5S step 2a, pinned through the WRITER: an INHERITED system principal
     * survives only because the projection applies the final conversion. Without it the id stays
     * {@code CMIS_ANYONE}, matches neither the {@code "cmis:anyone"} literal nor any user/group, is
     * dropped, and the whole set collapses to admin-only — a silent stale-DENY reaching Solr.
     */
    @Test
    void realPathInheritedSystemPrincipalSurvivesTheProjection() throws Exception {
        seedFolder("root", null, false, 4L,
                List.of(ace(jp.aegif.nemaki.util.constant.PrincipalId.ANYONE_IN_DB, "cmis:read")));
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");

        WriteOutcome o = realWriter().write(contentDb, solrId, solr, RESOLVER);

        assertEquals(WriteResult.UPDATED, o.result);
        List<String> written = readers(get(solrId));
        assertEquals(List.of(AclSemantics.formatGroupReader(contentDb, "GROUP_EVERYONE")), written,
                "expected the CONVERTED anyone group token, got " + written);
        assertTrue(!written.equals(AclSemantics.adminOnlyReaders(contentDb)),
                "collapsed to ADMIN-ONLY — the silent stale-DENY this test exists to catch");
    }

    /**
     * The one legitimate empty ACL group. A relationship's readers are the UNION of its endpoints'
     * and never its own ACL, so when both endpoints are gone the authoritative answer IS empty —
     * exactly what production persists ({@code SolrUtil.unionReaders}). Refusing it unconditionally
     * (as the writer does for ordinary content, pinned above) would leave such a relationship's
     * reconcile task failing and retrying for ever.
     */
    @Test
    void aRelationshipWithBothEndpointsGoneWritesAnAuthoritativeEmptyAclGroup() throws Exception {
        seedRelationship(solrId, "vanished-source", "vanished-target", 6L);
        indexSolrDoc(solrId, "rel", "/rel", "b");
        setAclGroup(solrId, List.of("user:stale-from-before"), 5L);

        WriteOutcome o = realWriter().write(contentDb, solrId, solr, RESOLVER);

        assertEquals(WriteResult.UPDATED, o.result);
        assertEquals(6L, o.epoch);
        assertEquals(List.of(), readers(get(solrId)),
                "the stale one-sided readers must be REPLACED by the authoritative empty set");
    }

    // ── migration: stamping the initial epoch (wiring gate 2) ──────

    /**
     * The gate-2 contract: an UNFENCED document is refused by the ordinary write and stamped by the
     * migration, with the value {@code snapshot().effectiveEpoch}.
     *
     * <p>The refusal half matters as much as the stamp: it is why the migration must run BEFORE the
     * writer is wired. Without it every ACL update on pre-migration content would fail closed.
     */
    @Test
    void anUnfencedDocumentIsREFUSEDByTheOrdinaryWriteAndSTAMPEDByTheMigration() throws Exception {
        seedFolder("root", null, false, 3L, List.of(ace("g1", "cmis:read")));
        seedDocument(solrId, "root", true, 1L, List.of(ace("u1", "cmis:read")));
        indexSolrDocUnfenced(solrId, "n", "/n", "b");   // no effective_acl_epoch at all

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> realWriter().write(contentDb, solrId, solr, RESOLVER));
        assertTrue(refused.getMessage().contains("never been fenced"), refused.getMessage());

        WriteOutcome o = realWriter().stampInitialEpoch(contentDb, solrId, solr, RESOLVER);

        assertEquals(WriteResult.UPDATED, o.result);
        assertEquals(3L, o.epoch, "the stamp is snapshot().effectiveEpoch = max(self=1, root=3)");
        assertEquals(3L, num(get(solrId), "effective_acl_epoch"));
        assertEquals(List.of(AclSemantics.formatGroupReader(contentDb, "g1"),
                        AclSemantics.formatUserReader(contentDb, "u1")),
                readers(get(solrId)), "the readers are stamped from the SAME snapshot");

        // ...and the ordinary write now works, which is the whole point of the gate.
        assertEquals(WriteResult.SKIPPED_IDEMPOTENT,
                realWriter().write(contentDb, solrId, solr, RESOLVER).result);
    }

    /**
     * Pre-migration content has every {@code aclSourceEpoch} absent, so the stamp is 0 — and the
     * field must still be CREATED. An earlier draft short-circuited on "readers already match" and
     * would have reported success while leaving the document unfenced, so the writer would have
     * refused every later ACL update on it.
     */
    @Test
    void aZeroEpochStampIsStillWRITTEN_evenWhenTheReadersAlreadyMatch() throws Exception {
        seedFolderNoEpoch("root", null, false);
        seedDocumentNoEpoch(solrId, "root", true, List.of(ace("u1", "cmis:read")));
        indexSolrDocUnfenced(solrId, "n", "/n", "b");
        // Pre-set exactly the readers the projection will compute.
        setReadersOnly(solrId, List.of(AclSemantics.formatUserReader(contentDb, "u1")));

        WriteOutcome o = realWriter().stampInitialEpoch(contentDb, solrId, solr, RESOLVER);

        assertEquals(WriteResult.UPDATED, o.result, "an absent epoch field must be created, not skipped");
        assertEquals(0L, o.epoch, "pre-migration content: every aclSourceEpoch absent → 0");
        assertEquals(0L, num(get(solrId), "effective_acl_epoch"));
    }

    /** Re-running the migration is a clean no-op: it must be resumable after an interruption. */
    @Test
    void stampingTwiceIsIdempotent() throws Exception {
        seedFolder("root", null, false, 2L, List.of(ace("g1", "cmis:read")));
        seedDocument(solrId, "root", true, 1L);
        indexSolrDocUnfenced(solrId, "n", "/n", "b");

        assertEquals(WriteResult.UPDATED,
                realWriter().stampInitialEpoch(contentDb, solrId, solr, RESOLVER).result);
        assertEquals(WriteResult.SKIPPED_IDEMPOTENT,
                realWriter().stampInitialEpoch(contentDb, solrId, solr, RESOLVER).result);
    }

    /**
     * The migration must NOT touch CouchDB. Allocating {@code aclSourceEpoch} is the post-commit
     * two-phase mutation's job (§2.2); pre-seeding it here would manufacture an epoch no mutation
     * ever paid for, and the counter would no longer bound what has been issued.
     */
    @Test
    void stampingWritesSOLRONLY_neverTheCouchDBAclSourceEpoch() throws Exception {
        seedFolderNoEpoch("root", null, false);
        seedDocumentNoEpoch(solrId, "root", true, List.of(ace("u1", "cmis:read")));
        indexSolrDocUnfenced(solrId, "n", "/n", "b");

        realWriter().stampInitialEpoch(contentDb, solrId, solr, RESOLVER);

        for (String id : List.of("root", solrId)) {
            Document d = cloudant.getDocument(new GetDocumentOptions.Builder()
                    .db(contentDb).docId(id).build()).execute().getResult();
            assertFalse(d.getProperties().containsKey("aclSourceEpoch"),
                    "the migration wrote aclSourceEpoch onto " + id + " — that is the mutation's job");
        }
    }

    // ── step 6: 409 → FULL restart, no payload reuse ───────────────

    @Test
    void aCasConflictRestartsFromTheWalkAndDoesNotReuseThePayload() throws Exception {
        seedFolder("root", null, false, 4L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");

        AtomicBoolean injected = new AtomicBoolean(false);
        AtomicInteger computeCalls = new AtomicInteger();
        // Between step 3 (RTG) and step 5 (CAS) a competing writer bumps _version_ ONCE, so the
        // first CAS is guaranteed to 409.
        AclEpochIndexWriter racing = new TestWriter(snapshot -> {
            computeCalls.incrementAndGet();
            return List.of("user:mine");
        }) {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String id) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, id);
                if (d != null && injected.compareAndSet(false, true)) {
                    setAclGroup(id, List.of("user:competitor"), 4L); // bumps _version_
                }
                return d;
            }
        };

        WriteOutcome o = racing.write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.UPDATED, o.result);
        assertTrue(o.attempts >= 2, "the 409 must cause a restart: attempts=" + o.attempts);
        assertTrue(computeCalls.get() >= 2, "the restart RE-WALKS and RE-COMPUTES; the conflicted "
                + "payload is discarded (calls=" + computeCalls.get() + ")");
        assertEquals(List.of("user:mine"), readers(get(solrId)), "the recomputed value converges");
    }

    @Test
    void persistentConflictIsReportedAsRetryableContentionNotSilentSuccess() throws Exception {
        seedFolder("root", null, false, 4L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");

        // EVERY attempt conflicts → the budget is exhausted → a retryable contention exception, so
        // the caller RETAINS its task (never a silent success).
        AclEpochIndexWriter alwaysConflicts = new TestWriter(snapshot -> List.of("user:mine")) {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String id) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, id);
                if (d != null) {
                    setAclGroup(id, List.of("user:competitor-" + UUID.randomUUID()), 4L);
                }
                return d;
            }
        };
        alwaysConflicts.setMaxAttempts(3);

        assertThrows(AclEpochIndexWriter.AclEpochWriteContentionException.class,
                () -> alwaysConflicts.write(contentDb, solrId, solr, RESOLVER));
    }

    // ── step 4: a dependency change between RTG and CAS restarts ───

    @Test
    void aDependencyChangedAfterTheWalkRestartsAndUsesTheNewEpoch() throws Exception {
        seedFolder("root", null, false, 4L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");

        AtomicBoolean injected = new AtomicBoolean(false);
        // The ANCESTOR's epoch is bumped after the snapshot was taken: revalidation must catch it
        // and the final write must carry the NEW effective epoch, never the stale one.
        AclEpochIndexWriter racing = new TestWriter(snapshot -> List.of("user:u1")) {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String id) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, id);
                if (d != null && injected.compareAndSet(false, true)) {
                    bumpEpoch("root", 42L);
                }
                return d;
            }
        };

        WriteOutcome o = racing.write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.UPDATED, o.result);
        assertEquals(42L, o.epoch, "the restart recomputed the effective epoch from the NEW ancestor");
        assertEquals(42L, num(get(solrId), "effective_acl_epoch"));
        assertTrue(o.attempts >= 2, "attempts=" + o.attempts);
    }

    // ── pending / quarantine: NOTHING is written, the caller retains ──

    @Test
    void aPendingDependencyWritesNothingAtAll() throws Exception {
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclEpochState\":\"PENDING_EPOCH\","
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:before"), 3L);

        assertThrows(AclEffectiveEpochService.AclEpochPendingException.class,
                () -> writerReturning(List.of("user:mine")).write(contentDb, solrId, solr, RESOLVER));

        SolrDocument after = get(solrId);
        assertEquals(List.of("user:before"), readers(after), "the pending gate wrote NOTHING");
        assertEquals(3L, num(after, "effective_acl_epoch"));
    }

    @Test
    void aQuarantinedDependencyWritesNothingAtAll() throws Exception {
        seedRaw("root", "{\"type\":\"cmis:folder\",\"aclInherited\":false,\"aclSourceEpoch\":9,"
                + "\"aclEpochQuarantined\":true}");
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:before"), 3L);

        assertThrows(AclEpochAnomalyException.class,
                () -> writerReturning(List.of("user:mine")).write(contentDb, solrId, solr, RESOLVER));
        assertEquals(List.of("user:before"), readers(get(solrId)), "a quarantined ancestor wrote NOTHING");
    }

    // ── fail-closed reads ──────────────────────────────────────────

    @Test
    void anUnfencedDocumentFailsClosedInsteadOfBeingBootstrapped() throws Exception {
        // §4.3: an ABSENT stored epoch means the document has never been fenced. A normal
        // ACL-UPDATE must NOT bootstrap it — "no fence yet, so any writer wins" is exactly the
        // property the fence removes. (The previous test asserted the OPPOSITE while being named
        // "fails closed"; review 4a [P1] #3.)
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDocUnfenced(solrId, "n", "/n", "b"); // NO effective_acl_epoch

        assertThrows(IllegalStateException.class,
                () -> writerReturning(List.of("user:u1")).write(contentDb, solrId, solr, RESOLVER));
        SolrDocument after = get(solrId);
        assertNull(after.getFieldValue("effective_acl_epoch"), "the ACL group is untouched");
        assertTrue(after.getFieldValues("readers") == null || after.getFieldValues("readers").isEmpty());
    }

    @Test
    void anEmptyReadersComputationIsRefusedSoNoUpdateIsSent() throws Exception {
        // review 4b: the SPI already promised "never empty"; the writer now ENFORCES it. An
        // authoritative expansion always emits at least the admin role token, so an empty result
        // means the computation failed — persisting it would make the object invisible to every
        // non-admin search.
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:before"), 2L);

        assertThrows(IllegalStateException.class,
                () -> writerReturning(List.of()).write(contentDb, solrId, solr, RESOLVER));
        assertEquals(List.of("user:before"), readers(get(solrId)), "no update was sent");
        assertEquals(2L, num(get(solrId), "effective_acl_epoch"));
    }

    @Test
    void aNullReadersComputationIsRefusedRatherThanWritingAnEmptyAclGroup() throws Exception {
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:before"), 1L);

        assertThrows(IllegalStateException.class,
                () -> writerComputing(snapshot -> null).write(contentDb, solrId, solr, RESOLVER));
        assertEquals(List.of("user:before"), readers(get(solrId)),
                "an empty readers list would make the object invisible — nothing is written");
    }

    @Test
    void aCrossRepositoryIdCollisionIsRefused() throws Exception {
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDocInRepository(solrId, "someone-elses-repository");
        assertThrows(IllegalStateException.class,
                () -> writerReturning(List.of("user:u1")).write(contentDb, solrId, solr, RESOLVER));
    }

    // ── review 4a: the fence is evaluated on EVERY attempt ─────────

    @Test
    void aNewerEpochLandingDuringTheRecomputeIsNotOverwritten() throws Exception {
        // THE regression this closes: the recompute flag must not bypass `stored > mine`. Another
        // writer lands epoch 9 between the divergence observation and the recompute's RTG; the
        // recompute reads THAT document's _version_, so a bypassing CAS would succeed and roll the
        // index back from 9 to 7.
        seedFolder("root", null, false, 7L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:stored-only"), 7L); // equal epoch, divergent readers

        AtomicInteger rtgCalls = new AtomicInteger();
        AclEpochIndexWriter racing = new TestWriter(snapshot -> List.of("user:authoritative")) {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String id) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, id);
                if (rtgCalls.incrementAndGet() == 1) {
                    // AFTER attempt 1's RTG (which observes epoch 7 + divergent readers and arms the
                    // recompute), a competing writer lands epoch 9. Attempt 2 must SKIP, not CAS 7.
                    setAclGroup(id, List.of("user:newer"), 9L);
                }
                return d;
            }
        };

        WriteOutcome o = racing.write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.SKIPPED_FRESHER, o.result,
                "a strictly newer stored epoch must win even during an equal-epoch recompute");
        SolrDocument after = get(solrId);
        assertEquals(9L, num(after, "effective_acl_epoch"), "epoch 9 must NOT be rolled back to 7");
        assertEquals(List.of("user:newer"), readers(after));
    }

    @Test
    void aRecomputeThatAgreesWithTheStoredValueIsIdempotent() throws Exception {
        // The equal-epoch identity check must ALSO be re-evaluated on the recompute attempt: if the
        // authoritative recompute simply agrees with what is stored, that is idempotence, not a
        // reason to write.
        seedFolder("root", null, false, 7L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:stored-only"), 7L);

        AtomicInteger rtgCalls = new AtomicInteger();
        AclEpochIndexWriter racing = new TestWriter(snapshot -> List.of("user:authoritative")) {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String id) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, id);
                if (rtgCalls.incrementAndGet() == 1) {
                    // AFTER attempt 1 observes the divergence, the stored readers become exactly
                    // what the recompute will produce — attempt 2 must find it idempotent.
                    setAclGroup(id, List.of("user:authoritative"), 7L);
                }
                return d;
            }
        };

        WriteOutcome o = racing.write(contentDb, solrId, solr, RESOLVER);
        assertEquals(WriteResult.SKIPPED_IDEMPOTENT, o.result);
    }

    // ── review 4a: strict _version_, stored epoch, repository_id, readers ──

    @Test
    void solrMagicVersionValuesAreRefusedSoNoUnconditionalWriteIsSent() throws Exception {
        // _version_ 0 = no concurrency check, 1 = "any existing version", negative = must-not-exist,
        // 1.5 = not a version at all. None of them is a real compare-and-set.
        for (Object magic : new Object[] { 1L, 0L, -1L, 1.5d }) {
            String id = "magic-" + UUID.randomUUID();
            seedFolder("root-" + id, null, false, 3L);
            seedDocument(id, "root-" + id, true, 1L);
            indexSolrDoc(id, "n", "/n", "b");
            setAclGroup(id, List.of("user:before"), 2L);

            AclEpochIndexWriter spoofed = new TestWriter(snapshot -> List.of("user:mine")) {
                @Override SolrDocument realtimeGet(SolrClient c, String repo, String oid) throws Exception {
                    SolrDocument d = super.realtimeGet(c, repo, oid);
                    d.setField("_version_", magic);
                    return d;
                }
            };

            assertThrows(IllegalStateException.class,
                    () -> spoofed.write(contentDb, id, solr, RESOLVER),
                    "_version_=" + magic + " must be refused");
            assertEquals(List.of("user:before"), readers(get(id)), "no update was sent for " + magic);
            assertEquals(2L, num(get(id), "effective_acl_epoch"));
            solr.deleteById(id);
            solr.commit();
        }
    }

    @Test
    void aMissingRepositoryIdIsRefusedSoNoUpdateIsSent() throws Exception {
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:before"), 2L);

        AclEpochIndexWriter spoofed = new TestWriter(snapshot -> List.of("user:mine")) {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String oid) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, oid);
                d.removeFields("repository_id"); // a restored / legacy / corrupt document
                return d;
            }
        };

        assertThrows(IllegalStateException.class,
                () -> spoofed.write(contentDb, solrId, solr, RESOLVER));
        assertEquals(List.of("user:before"), readers(get(solrId)), "no update was sent");
    }

    @Test
    void nullOrBlankIncomingReaderTokensAreRefusedSoNoUpdateIsSent() throws Exception {
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:before"), 2L);

        // A partial SPI failure must NOT be silently written as a SHORTER (under-granting) set.
        List<String> withNull = new java.util.ArrayList<>();
        withNull.add("user:ok");
        withNull.add(null);
        assertThrows(IllegalStateException.class,
                () -> writerReturning(withNull).write(contentDb, solrId, solr, RESOLVER));
        assertEquals(List.of("user:before"), readers(get(solrId)), "no update was sent (null token)");

        assertThrows(IllegalStateException.class,
                () -> writerReturning(List.of("user:ok", "  ")).write(contentDb, solrId, solr, RESOLVER));
        assertEquals(List.of("user:before"), readers(get(solrId)), "no update was sent (blank token)");
    }

    // ── fixtures / helpers ─────────────────────────────────────────

    /** Index a document that has ALREADY been fenced (epoch 0 = the migration baseline). */
    private void indexSolrDoc(String id, String name, String path, String body) throws Exception {
        indexSolrDoc(id, name, path, body, contentDb);
        setAclGroup(id, List.of(), 0L); // a normal ACL-UPDATE never bootstraps the fence itself
    }

    /** Index a document that has NEVER been fenced (no effective_acl_epoch). */
    private void indexSolrDocUnfenced(String id, String name, String path, String body) throws Exception {
        indexSolrDoc(id, name, path, body, contentDb);
    }

    private void indexSolrDocInRepository(String id, String repositoryId) throws Exception {
        indexSolrDoc(id, "n", "/n", "b", repositoryId);
    }

    private void indexSolrDoc(String id, String name, String path, String body, String repositoryId)
            throws Exception {
        SolrInputDocument d = new SolrInputDocument();
        d.addField("id", id);
        d.addField("repository_id", repositoryId);
        d.addField("object_id", id);
        d.addField("name", name);
        d.addField("path", path);
        d.addField("content", body);
        d.addField("content_length", 4242L);
        UpdateRequest req = new UpdateRequest();
        req.add(d);
        req.process(solr);
        solr.commit();
    }

    /** Directly set the ACL group (simulating another writer) — also bumps {@code _version_}. */
    private void setAclGroup(String id, List<String> readers, long epoch) throws Exception {
        SolrInputDocument d = new SolrInputDocument();
        d.addField("id", id);
        d.setField("readers", Collections.singletonMap("set", readers));
        d.setField("effective_acl_epoch", Collections.singletonMap("set", epoch));
        UpdateRequest req = new UpdateRequest();
        req.add(d);
        req.process(solr);
        solr.commit();
    }

    private SolrDocument get(String id) throws Exception {
        SolrDocument d = solr.getById(id);
        assertNotNull(d, "the Solr document must exist: " + id);
        return d;
    }

    private List<String> readers(SolrDocument d) {
        java.util.Collection<Object> vs = d.getFieldValues("readers");
        return AclEpochIndexWriter.canonical(vs == null ? List.of()
                : vs.stream().map(String::valueOf).collect(java.util.stream.Collectors.toList()));
    }

    private long num(SolrDocument d, String field) {
        Object v = d.getFieldValue(field);
        assertNotNull(v, field + " must be present");
        return ((Number) v).longValue();
    }

    private void seedFolder(String id, String parentId, boolean inherits, long epoch) {
        seedFolder(id, parentId, inherits, epoch, null);
    }

    /** As above, plus the persisted ACL in the real {@code CouchAcl} shape. */
    private void seedFolder(String id, String parentId, boolean inherits, long epoch,
                            List<Map<String, Object>> aclEntries) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:folder");
        p.put("name", id);
        if (parentId != null) p.put("parentId", parentId);
        p.put("aclInherited", inherits);
        p.put("aclSourceEpoch", epoch);
        if (aclEntries != null) p.put("acl", Map.of("entries", aclEntries));
        put(id, p);
    }

    /** Seed a folder with NO aclSourceEpoch — the pre-migration shape. */
    private void seedFolderNoEpoch(String id, String parentId, boolean inherits) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:folder");
        p.put("name", id);
        if (parentId != null) p.put("parentId", parentId);
        p.put("aclInherited", inherits);
        put(id, p);
    }

    private void seedDocumentNoEpoch(String id, String parentId, boolean inherits,
                                     List<Map<String, Object>> aclEntries) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:document");
        p.put("name", id);
        if (parentId != null) p.put("parentId", parentId);
        p.put("aclInherited", inherits);
        if (aclEntries != null) p.put("acl", Map.of("entries", aclEntries));
        put(id, p);
    }

    /** Set readers WITHOUT creating effective_acl_epoch (the pre-migration index shape). */
    private void setReadersOnly(String id, List<String> readers) throws Exception {
        SolrInputDocument d = new SolrInputDocument();
        d.addField("id", id);
        d.setField("readers", Collections.singletonMap("set", readers));
        UpdateRequest req = new UpdateRequest();
        req.add(d);
        req.process(solr);
        solr.commit();
    }

    /** A persisted ACE: {@code {"principal": …, "permissions": [...]}}. */
    private static Map<String, Object> ace(String principal, String... permissions) {
        return Map.of("principal", principal, "permissions", List.of(permissions));
    }

    /** A relationship whose endpoints may or may not exist. */
    private void seedRelationship(String id, String sourceId, String targetId, long epoch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:relationship");
        p.put("name", id);
        p.put("sourceId", sourceId);
        p.put("targetId", targetId);
        p.put("aclInherited", false);
        p.put("aclSourceEpoch", epoch);
        put(id, p);
    }

    private void seedDocument(String id, String parentId, boolean inherits, long epoch) {
        seedDocument(id, parentId, inherits, epoch, null);
    }

    private void seedDocument(String id, String parentId, boolean inherits, long epoch,
                              List<Map<String, Object>> aclEntries) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:document");
        p.put("name", id);
        if (parentId != null) p.put("parentId", parentId);
        p.put("aclInherited", inherits);
        p.put("aclSourceEpoch", epoch);
        if (aclEntries != null) p.put("acl", Map.of("entries", aclEntries));
        put(id, p);
    }

    private void seedRaw(String id, String json) {
        try {
            java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:5984/" + contentDb + "/" + id))
                    .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                            .encodeToString("admin:password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json));
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
                    .send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) throw new IllegalStateException("raw put failed: " + resp.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void bumpEpoch(String id, long epoch) {
        Document d = cloudant.getDocument(new GetDocumentOptions.Builder()
                .db(contentDb).docId(id).build()).execute().getResult();
        Map<String, Object> p = d.getProperties();
        p.put("aclSourceEpoch", epoch);
        d.setProperties(p);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(contentDb).docId(id).document(d).build()).execute();
    }

    private void put(String id, Map<String, Object> props) {
        Document d = new Document();
        d.setId(id);
        String rev = revOf(id);
        if (rev != null) d.setRev(rev);
        d.setProperties(props);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(contentDb).docId(id).document(d).build()).execute();
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
