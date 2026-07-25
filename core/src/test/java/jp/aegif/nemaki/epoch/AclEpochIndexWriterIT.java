package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
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
import jp.aegif.nemaki.epoch.AclEpochIndexWriter.WriteOutcome;
import jp.aegif.nemaki.epoch.AclEpochIndexWriter.WriteResult;
import jp.aegif.nemaki.util.constant.SystemConst;

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
    private AclEpochIndexWriter writer;
    private String solrId;

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

        CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudant, contentDb, new ObjectMapper());
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(contentDb)).thenReturn(wrapper);

        epochService = new AclEffectiveEpochService();
        epochService.setConnectorPool(pool);
        writer = new AclEpochIndexWriter();
        writer.setEffectiveEpochService(epochService);

        solrId = "epoch-writer-it-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (!available) return;
        try {
            cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(contentDb).build()).execute();
        } catch (Exception ignore) { /* best effort */ }
        try {
            solr.deleteById(solrId);
            solr.commit();
        } catch (Exception ignore) { /* best effort */ }
    }

    // ── the happy path + the atomicity requirement ─────────────────

    @Test
    void writesTheAclGroupAndLeavesEVERYOTHERFieldUntouched() throws Exception {
        seedFolder("root", null, false, 5L);
        seedDocument(solrId, "root", true, 2L);
        indexSolrDoc(solrId, "the original name", "/root/the original name", "the original body");

        WriteOutcome o = writer.write(contentDb, solrId, solr, snap -> List.of("group:g1", "user:u1"));
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

    @Test
    void notIndexedWhenTheSolrDocumentDoesNotExist() throws Exception {
        seedFolder("root", null, false, 1L);
        seedDocument(solrId, "root", true, 1L); // in CouchDB but never indexed
        WriteOutcome o = writer.write(contentDb, solrId, solr, snap -> List.of("user:u1"));
        assertEquals(WriteResult.NOT_INDEXED, o.result);
    }

    @Test
    void deletedObjectIsSkippedSoTheCallerCompletesRatherThanRetries() throws Exception {
        indexSolrDoc(solrId, "orphan", "/orphan", "body"); // indexed, but absent from CouchDB
        WriteOutcome o = writer.write(contentDb, solrId, solr, snap -> List.of("user:u1"));
        assertEquals(WriteResult.SKIPPED_DELETED, o.result);
    }

    // ── §4.3 fence decision ────────────────────────────────────────

    @Test
    void aStrictlyFresherStoredEpochIsNotOverwritten() throws Exception {
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:fresh"), 99L); // a fresher ACL already landed

        WriteOutcome o = writer.write(contentDb, solrId, solr, snap -> List.of("user:stale"));
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

        WriteOutcome o = writer.write(contentDb, solrId, solr, snap -> List.of("user:u1", "group:g1"));
        assertEquals(WriteResult.SKIPPED_IDEMPOTENT, o.result,
                "an order-only difference must be idempotent, not an endless divergence");
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
        WriteOutcome o = writer.write(contentDb, solrId, solr, snap -> {
            computeCalls.incrementAndGet();
            return List.of("user:authoritative");
        });
        assertEquals(WriteResult.UPDATED, o.result);
        assertTrue(computeCalls.get() >= 2, "the divergence forces an authoritative RECOMPUTE "
                + "(walk again), not a reuse of the first payload: " + computeCalls.get());
        assertEquals(List.of("user:authoritative"), readers(get(solrId)));
        assertEquals(7L, num(get(solrId), "effective_acl_epoch"), "the epoch is unchanged");
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
        AclEpochIndexWriter racing = new AclEpochIndexWriter() {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String id) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, id);
                if (d != null && injected.compareAndSet(false, true)) {
                    setAclGroup(id, List.of("user:competitor"), 4L); // bumps _version_
                }
                return d;
            }
        };
        racing.setEffectiveEpochService(epochService);

        WriteOutcome o = racing.write(contentDb, solrId, solr, snap -> {
            computeCalls.incrementAndGet();
            return List.of("user:mine");
        });
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
        AclEpochIndexWriter alwaysConflicts = new AclEpochIndexWriter() {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String id) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, id);
                if (d != null) {
                    setAclGroup(id, List.of("user:competitor-" + UUID.randomUUID()), 4L);
                }
                return d;
            }
        };
        alwaysConflicts.setEffectiveEpochService(epochService);
        alwaysConflicts.setMaxAttempts(3);

        assertThrows(AclEpochIndexWriter.AclEpochWriteContentionException.class,
                () -> alwaysConflicts.write(contentDb, solrId, solr, snap -> List.of("user:mine")));
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
        AclEpochIndexWriter racing = new AclEpochIndexWriter() {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String id) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, id);
                if (d != null && injected.compareAndSet(false, true)) {
                    bumpEpoch("root", 42L);
                }
                return d;
            }
        };
        racing.setEffectiveEpochService(epochService);

        WriteOutcome o = racing.write(contentDb, solrId, solr, snap -> List.of("user:u1"));
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
                () -> writer.write(contentDb, solrId, solr, snap -> List.of("user:mine")));

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
                () -> writer.write(contentDb, solrId, solr, snap -> List.of("user:mine")));
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
                () -> writer.write(contentDb, solrId, solr, snap -> List.of("user:u1")));
        SolrDocument after = get(solrId);
        assertNull(after.getFieldValue("effective_acl_epoch"), "the ACL group is untouched");
        assertTrue(after.getFieldValues("readers") == null || after.getFieldValues("readers").isEmpty());
    }

    @Test
    void aNullReadersComputationIsRefusedRatherThanWritingAnEmptyAclGroup() throws Exception {
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDoc(solrId, "n", "/n", "b");
        setAclGroup(solrId, List.of("user:before"), 1L);

        assertThrows(IllegalStateException.class,
                () -> writer.write(contentDb, solrId, solr, snap -> null));
        assertEquals(List.of("user:before"), readers(get(solrId)),
                "an empty readers list would make the object invisible — nothing is written");
    }

    @Test
    void aCrossRepositoryIdCollisionIsRefused() throws Exception {
        seedFolder("root", null, false, 3L);
        seedDocument(solrId, "root", true, 1L);
        indexSolrDocInRepository(solrId, "someone-elses-repository");
        assertThrows(IllegalStateException.class,
                () -> writer.write(contentDb, solrId, solr, snap -> List.of("user:u1")));
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
        AclEpochIndexWriter racing = new AclEpochIndexWriter() {
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
        racing.setEffectiveEpochService(epochService);

        WriteOutcome o = racing.write(contentDb, solrId, solr, snap -> List.of("user:authoritative"));
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
        AclEpochIndexWriter racing = new AclEpochIndexWriter() {
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
        racing.setEffectiveEpochService(epochService);

        WriteOutcome o = racing.write(contentDb, solrId, solr, snap -> List.of("user:authoritative"));
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

            AclEpochIndexWriter spoofed = new AclEpochIndexWriter() {
                @Override SolrDocument realtimeGet(SolrClient c, String repo, String oid) throws Exception {
                    SolrDocument d = super.realtimeGet(c, repo, oid);
                    d.setField("_version_", magic);
                    return d;
                }
            };
            spoofed.setEffectiveEpochService(epochService);

            assertThrows(IllegalStateException.class,
                    () -> spoofed.write(contentDb, id, solr, snap -> List.of("user:mine")),
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

        AclEpochIndexWriter spoofed = new AclEpochIndexWriter() {
            @Override SolrDocument realtimeGet(SolrClient c, String repo, String oid) throws Exception {
                SolrDocument d = super.realtimeGet(c, repo, oid);
                d.removeFields("repository_id"); // a restored / legacy / corrupt document
                return d;
            }
        };
        spoofed.setEffectiveEpochService(epochService);

        assertThrows(IllegalStateException.class,
                () -> spoofed.write(contentDb, solrId, solr, snap -> List.of("user:mine")));
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
                () -> writer.write(contentDb, solrId, solr, snap -> withNull));
        assertEquals(List.of("user:before"), readers(get(solrId)), "no update was sent (null token)");

        assertThrows(IllegalStateException.class,
                () -> writer.write(contentDb, solrId, solr, snap -> List.of("user:ok", "  ")));
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
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:folder");
        p.put("name", id);
        if (parentId != null) p.put("parentId", parentId);
        p.put("aclInherited", inherits);
        p.put("aclSourceEpoch", epoch);
        put(id, p);
    }

    private void seedDocument(String id, String parentId, boolean inherits, long epoch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "cmis:document");
        p.put("name", id);
        if (parentId != null) p.put("parentId", parentId);
        p.put("aclInherited", inherits);
        p.put("aclSourceEpoch", epoch);
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
