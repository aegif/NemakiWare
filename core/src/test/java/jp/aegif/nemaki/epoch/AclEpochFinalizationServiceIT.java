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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Attachment;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.IndexDefinition;
import com.ibm.cloud.cloudant.v1.model.IndexField;
import com.ibm.cloud.cloudant.v1.model.PostIndexOptions;
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
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Integration tests for {@link AclEpochFinalizationService} against a LIVE CouchDB. Each
 * test runs in its OWN throwaway content database (created WITH the {@code (aclEpochState)}
 * Mango index; dropped afterward) with its own seeded ACL-epoch counter. Mutation ids are
 * canonical UUIDs (the validator now rejects non-UUIDs). Gated like the other CouchDB ITs.
 */
public class AclEpochFinalizationServiceIT {

    private static Cloudant cloudant;
    private static boolean available;
    private static String baseUrl;
    private static String basicAuth;

    private String contentDb;
    private AclEpochFinalizationService svc;
    private AclEffectiveEpochService effectiveSvc;   // the walk that the quarantine blocks
    private jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconcileSvc;
    private CloudantClientPool pool;      // reused by tests that build a second service instance
    private AclEpochCounterService counter;

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
                    + "the ACL epoch finalization IT cannot run");
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(available, "nemaki_conf not reachable — skipping ACL epoch finalization IT");
        contentDb = "epoch-fin-it-" + UUID.randomUUID();
        cloudant.putDatabase(new PutDatabaseOptions.Builder().db(contentDb).build()).execute();
        cloudant.postIndex(new PostIndexOptions.Builder()
                .db(contentDb)
                .index(new IndexDefinition.Builder()
                        .fields(List.of(new IndexField.Builder().add(AclEpochState.FIELD_STATE, "asc").build()))
                        .build())
                .name("idx_aclEpochState").type(PostIndexOptions.Type.JSON).ddoc("acl-epoch-indexes")
                .build()).execute();
        cloudant.postIndex(new PostIndexOptions.Builder()
                .db(contentDb)
                .index(new IndexDefinition.Builder()
                        .fields(List.of(new IndexField.Builder()
                                .add(AclEpochState.FIELD_MUTATION_ID, "asc").build()))
                        .build())
                .name("idx_aclEpochMutationId").type(PostIndexOptions.Type.JSON).ddoc("acl-epoch-indexes")
                .build()).execute();

        ObjectMapper om = ObjectMapperFactory.createDefaultObjectMapper();
        CloudantClientWrapper confWrapper = new CloudantClientWrapper(cloudant, SystemConst.NEMAKI_CONF_DB, om);
        CloudantClientWrapper contentWrapper = new CloudantClientWrapper(cloudant, contentDb, om);
        pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(confWrapper);
        lenient().when(pool.getClient(contentDb)).thenReturn(contentWrapper);

        counter = new AclEpochCounterService();
        counter.setConnectorPool(pool);
        seedCounter(contentDb, 0L);

        svc = new AclEpochFinalizationService();
        svc.setConnectorPool(pool);
        svc.setCounterService(counter);
        // REQUIRED since increment 7b: the ACK turns a finalized epoch into a durable obligation.
        reconcileSvc = new jp.aegif.nemaki.reconcile.SearchIndexReconciliationService();
        reconcileSvc.setConnectorPool(pool);
        svc.setReconciliationService(reconcileSvc);

        // The walk under test for §5.1 (wiring gate 4).
        effectiveSvc = new AclEffectiveEpochService();
        effectiveSvc.setConnectorPool(pool);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo qinfo =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        lenient().when(qinfo.getRootFolderId()).thenReturn("q-root");
        lenient().when(qinfo.getPrincipalIdAnyone()).thenReturn("GROUP_EVERYONE");
        lenient().when(qinfo.getPrincipalIdAnonymous()).thenReturn("anonymous");
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap qmap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        lenient().when(qmap.get(contentDb)).thenReturn(qinfo);
        effectiveSvc.setRepositoryInfoMap(qmap);
    }

    @AfterEach
    void tearDown() {
        if (!available) return;
        try {
            cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(contentDb).build()).execute();
        } catch (Exception ignore) { /* best effort */ }
        deleteConf(AclEpochCounterService.counterDocId(contentDb));
        deleteEnqueuedReconcileTasks();
    }

    /**
     * The ACK path enqueues into the SHARED nemaki_conf queue, and dropping the per-test content DB
     * does not remove those. Left behind they accumulate — and once the total crosses
     * {@code SearchIndexReconciliationService.LIST_LIMIT_CAP} (1000) they push a sibling IT's own
     * tasks off the end of {@code list()}, failing it with a completely unrelated symptom. Found
     * exactly that way: this class had left 1034 entries.
     */
    private void deleteEnqueuedReconcileTasks() {
        try {
            var find = new com.ibm.cloud.cloudant.v1.model.PostFindOptions.Builder()
                    .db(SystemConst.NEMAKI_CONF_DB)
                    .selector(Map.of("type", "searchIndexAclReindexTask",
                            "repositoryId", contentDb))
                    .limit(1000L).build();
            for (var d : cloudant.postFind(find).execute().getResult().getDocs()) {
                try {
                    cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions
                            .Builder().db(SystemConst.NEMAKI_CONF_DB)
                            .docId(d.getId()).rev(d.getRev()).build()).execute();
                } catch (Exception ignore) { /* best effort */ }
            }
        } catch (Exception ignore) { /* best effort */ }
    }

    /**
     * An ACK-created task must be labelled for what it IS.
     *
     * <p>It used to be recorded as {@code INDEX_WRITE_FAILURE}, but the ACK runs for a mutation that
     * SUCCEEDED — it is the outbox making the obligation durable, not a failure of anything. Anyone
     * triaging the queue would have gone looking for a Solr problem that never happened. Observed on
     * the dev stack while driving a real scan through the new admin endpoint.
     */
    @Test
    void anAckCreatedTaskIsLabelledOUTBOX_ACK_notAFailure() {
        String m = AclEpochState.newMutationId();
        seedFinalized("ack-label", m, 5L);

        assertEquals(AclEpochFinalizationService.AckResult.ACKED,
                svc.ackFinalized(contentDb, getContent("ack-label")));

        var t = taskFor("ack-label");
        assertNotNull(t);
        assertEquals(jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.OUTBOX_ACK,
                t.getReason(), "the ACK is not an index-write failure");
        assertEquals(5L, t.getMinRequiredEpoch());
    }

    // ── finalize (Phase 2) ─────────────────────────────────────────

    @Test
    void finalizeAllocatesEpochAndPreservesOtherFields() {
        String m = AclEpochState.newMutationId();
        seedPending("d1", m);
        FinalizeOutcome o = svc.finalizePending(contentDb, "d1");
        assertEquals(FinalizeResult.FINALIZED, o.result);
        assertEquals(1L, o.epoch.longValue());

        Map<String, Object> p = props("d1");
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, p.get(AclEpochState.FIELD_STATE));
        assertEquals(1L, ((Number) p.get(AclEpochState.FIELD_SOURCE_EPOCH)).longValue());
        assertEquals(m, p.get(AclEpochState.FIELD_MUTATION_ID));
        assertEquals("keep-me", p.get("name"), "other content fields preserved");
    }

    @Test
    void finalizePreservesInlineAttachment() {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        Document d = new Document();
        d.setId("d-att");
        d.setProperties(p);
        Attachment att = new Attachment.Builder().contentType("text/plain").data("hello".getBytes()).build();
        Map<String, Attachment> atts = new LinkedHashMap<>();
        atts.put("note.txt", att);
        d.setAttachments(atts);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(contentDb).docId("d-att").document(d).build()).execute();

        Document hint = getContent("d-att");
        hint.setAttachments(null); // simulate a _find hint that lacks attachment stubs
        FinalizeOutcome o = svc.finalizePending(contentDb, hint);
        assertEquals(FinalizeResult.FINALIZED, o.result);

        Document after = getContent("d-att");
        assertTrue(after.getAttachments() != null && after.getAttachments().containsKey("note.txt"),
                "the inline attachment must survive finalize");
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, after.getProperties().get(AclEpochState.FIELD_STATE));
    }

    @Test
    void finalizeIsIdempotentOnAlreadyFinalizedNeverReallocates() {
        seedFinalized("d2", AclEpochState.newMutationId(), 7L);
        FinalizeOutcome o = svc.finalizePending(contentDb, "d2");
        assertEquals(FinalizeResult.SKIPPED_NOT_PENDING, o.result);
        assertNull(o.epoch);
        assertEquals(7L, ((Number) props("d2").get(AclEpochState.FIELD_SOURCE_EPOCH)).longValue());
    }

    @Test
    void finalizeSkipsStatelessDocument() {
        seedStateless("d3");
        FinalizeOutcome o = svc.finalizePending(contentDb, "d3");
        assertEquals(FinalizeResult.SKIPPED_NOT_PENDING, o.result);
        assertFalse(props("d3").containsKey(AclEpochState.FIELD_STATE));
    }

    @Test
    void finalizeRejectsRevlessDocumentBeforeAllocating() {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        Document ghost = new Document();
        ghost.setId("ghost-1"); // id set, no _rev, never persisted
        ghost.setProperties(p);

        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, ghost));
        assertNull(revOf(contentDb, "ghost-1"), "a rev-less finalize must not create a new document");
        assertEquals(0L, counterValue(contentDb), "no epoch allocated for a rejected rev-less finalize");
    }

    @Test
    void finalizeRejectsNonUuidMutationId() {
        // A PENDING doc whose mutation id is present but NOT a UUID → anomaly (review 2b
        // [P2]), no finalize, no epoch consumed.
        seedPending("nu", "not-a-uuid");
        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "nu"));
        assertEquals(AclEpochState.PENDING_EPOCH, props("nu").get(AclEpochState.FIELD_STATE));
        assertEquals(0L, counterValue(contentDb), "a non-UUID mutation id must not consume an epoch");
    }

    @Test
    void finalizeAbandonsWhenMutationIdChangedUnderneath() {
        String mA = AclEpochState.newMutationId();
        String mB = AclEpochState.newMutationId();
        seedPending("d4", mA);
        Document stale = getContent("d4"); // owns mA
        Document fresh = getContent("d4");
        Map<String, Object> fp = fresh.getProperties();
        fp.put(AclEpochState.FIELD_MUTATION_ID, mB);
        fresh.setProperties(fp);
        putContent(fresh);

        FinalizeOutcome o = svc.finalizePending(contentDb, stale);
        assertEquals(FinalizeResult.ABANDONED_SUPERSEDED, o.result);
        Map<String, Object> p = props("d4");
        assertEquals(AclEpochState.PENDING_EPOCH, p.get(AclEpochState.FIELD_STATE));
        assertEquals(mB, p.get(AclEpochState.FIELD_MUTATION_ID));
        assertFalse(p.containsKey(AclEpochState.FIELD_SOURCE_EPOCH));
    }

    @Test
    void finalizeThrowsAnomalyWhenReReadIsCorruptNotSuperseded() {
        seedPending("d6", AclEpochState.newMutationId());
        Document stale = getContent("d6");
        Document corrupt = getContent("d6");
        Map<String, Object> cp = corrupt.getProperties();
        cp.put(AclEpochState.FIELD_STATE, "WAT_UNKNOWN");
        corrupt.setProperties(cp);
        putContent(corrupt);

        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, stale));
    }

    @Test
    void finalizeThrowsAnomalyWhenStateVanishesFromExistingDocEpochNotConsumed() {
        // review 2b [P1]: a document that STILL EXISTS but has LOST its aclEpochState is
        // marker loss (corruption), NOT a delete race — it must be an ANOMALY, and the
        // pre-allocate re-read means NO epoch is consumed.
        seedPending("d7", AclEpochState.newMutationId());
        Document stale = getContent("d7");
        // Remove the epoch state from the live document (doc still exists).
        Map<String, Object> plain = baseFixture(); // no aclEpochState / mutationId
        putContentRaw("d7", plain);

        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, stale));
        assertEquals(0L, counterValue(contentDb), "marker loss detected before allocate → no epoch consumed");
    }

    @Test
    void concurrentFinalizeExactlyOneWins() throws Exception {
        seedPending("d5", AclEpochState.newMutationId());
        int threads = 6;
        AtomicInteger finalized = new AtomicInteger(0);
        List<Throwable> workerErrors = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    FinalizeOutcome o = svc.finalizePending(contentDb, getContent("d5"));
                    if (o.result == FinalizeResult.FINALIZED) finalized.incrementAndGet();
                } catch (Throwable t) {
                    workerErrors.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertTrue(workerErrors.isEmpty(), "no finalizer may throw (a loser abandons cleanly): " + workerErrors);
        assertEquals(1, finalized.get(), "exactly one concurrent finalizer commits the epoch (CAS, no JVM lock)");
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("d5").get(AclEpochState.FIELD_STATE));
    }

    // ── scan priority / independent budgets / anomaly visibility ───

    @Test
    void scanFinalizesPendingCountsFinalizedAndStopsAtFinalized() {
        seedPending("s-pending", AclEpochState.newMutationId());
        seedFinalized("s-final", AclEpochState.newMutationId(), 3L);
        seedStateless("s-plain");

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(1, sum.finalized, "only the PENDING doc is finalized this scan");
        // Since increment 7b the SAME scan also ACKs: both documents get a durable obligation and
        // advance to RECONCILE_ENQUEUED. `finalized` still counts the finalize step, which is what
        // this test is about; the terminal audit therefore sees nothing awaiting.
        assertEquals(2, sum.acked, "pre-existing FINALIZED + the one just finalized are both ACKed");
        assertEquals(0, sum.awaitingReconcile, "the ACK pass runs before the terminal audit");
        assertTrue(sum.errors.isEmpty());
        assertEquals(AclEpochState.RECONCILE_ENQUEUED, props("s-final").get(AclEpochState.FIELD_STATE));
        assertEquals(AclEpochState.RECONCILE_ENQUEUED, props("s-pending").get(AclEpochState.FIELD_STATE));
    }

    @Test
    void validPendingIsFinalizedDespiteAnAnomalousPendingBacklogOverTheCap() {
        // review 2b [P1] Mode B: > (per-pass cap) PENDING with MISSING mutation ids plus one
        // valid PENDING. The valid-PENDING selector excludes missing-mutation-id docs, so the
        // valid one is finalized regardless of how many anomalous PENDING precede it.
        int cap = 3;
        for (int i = 0; i < cap + 3; i++) {
            seedPendingNoMutationId("bad-p-" + i); // anomalous PENDING (missing mutation id)
        }
        seedPending("valid-pending", AclEpochState.newMutationId());

        ScanSummary sum = svc.scan(contentDb, cap);
        assertEquals(AclEpochState.RECONCILE_ENQUEUED,
                props("valid-pending").get(AclEpochState.FIELD_STATE),
                "the valid PENDING is finalized despite the anomalous-PENDING backlog");
        assertEquals(1, sum.finalized);
    }

    @Test
    void unknownStateIsReportedDespiteAFinalizedBacklogOverTheCap() {
        // review 2b [P1] Mode A: > (per-pass cap) FINALIZED plus one UNKNOWN-state doc. With
        // INDEPENDENT per-pass budgets the anomaly (audit) pass runs regardless of the
        // FINALIZED volume, so the UNKNOWN is reported.
        int cap = 3;
        for (int i = 0; i < cap + 3; i++) {
            seedFinalized("f-" + i, AclEpochState.newMutationId(), 10 + i);
        }
        Map<String, Object> u = baseFixture();
        u.put(AclEpochState.FIELD_STATE, "MYSTERY_STATE");
        putContentRaw("the-unknown", u);

        ScanSummary sum = svc.scan(contentDb, cap);
        assertTrue(sum.errors.stream().anyMatch(e -> "the-unknown".equals(e.get("docId"))),
                "the UNKNOWN state is reported even behind a FINALIZED backlog: " + sum.errors);
    }

    @Test
    void allPassesProgressUnderAMixedLoad() {
        // review 2b [P1]: each pass makes non-zero progress in one scan (independent budgets).
        seedPending("mix-p", AclEpochState.newMutationId());
        seedFinalized("mix-f", AclEpochState.newMutationId(), 9L);
        Map<String, Object> u = baseFixture();
        u.put(AclEpochState.FIELD_STATE, "MIX_UNKNOWN");
        putContentRaw("mix-u", u);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertTrue(sum.finalized >= 1, "PENDING pass progressed");
        assertTrue(sum.acked >= 1, "the ACK pass progressed (7b: FINALIZED no longer parks)");
        assertTrue(sum.errors.stream().anyMatch(e -> "mix-u".equals(e.get("docId"))), "audit pass progressed");
    }

    @Test
    void scanIgnoresStatelessContent() {
        seedStateless("plain-1");
        seedStateless("plain-2");
        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(0, sum.scanned, "state-less content must never be selected");
        assertFalse(props("plain-1").containsKey(AclEpochState.FIELD_STATE));
    }

    @Test
    void scanRecordsPendingWithoutMutationIdAsAnomalyAndRetains() {
        seedPendingNoMutationId("bad-pending");
        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(0, sum.finalized);
        assertTrue(sum.errors.stream().anyMatch(e -> "bad-pending".equals(e.get("docId"))));
        assertEquals(AclEpochState.PENDING_EPOCH, props("bad-pending").get(AclEpochState.FIELD_STATE),
                "an anomalous doc is left unprocessed, not finalized");
    }

    @Test
    void scanRecordsFinalizedWithoutMutationIdAsAnomaly() {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, 5L); // valid epoch, but no mutation id
        putContentRaw("bad-final-nomut", p);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(0, sum.awaitingReconcile, "a FINALIZED doc without a mutation id is not clean");
        assertTrue(sum.errors.stream().anyMatch(e -> "bad-final-nomut".equals(e.get("docId"))));
    }

    @Test
    void scanRecordsFinalizedWithInvalidEpochAsAnomaly() {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        p.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId()); // valid UUID
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, 1.5d);                          // invalid epoch
        putContentRaw("bad-final-epoch", p);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertTrue(sum.errors.stream().anyMatch(e -> "bad-final-epoch".equals(e.get("docId"))));
        assertEquals(0, sum.awaitingReconcile);
    }

    // ── every scan selector is index-served (not _all_docs) ────────

    @Test
    void allScanSelectorsUseTheAclEpochStateIndex() throws Exception {
        // The five selectors the service uses (all exclude a TRUE quarantine marker via the
        // $or of {$exists:false} and {$ne:true} — a malformed marker is NOT hidden).
        assertIndexServed(withNotQ(Map.of(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH,
                AclEpochState.FIELD_MUTATION_ID, Map.of("$exists", true))), "PENDING finalize");
        assertIndexServed(withNotQ(Map.of(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE,
                AclEpochState.FIELD_MUTATION_ID, Map.of("$exists", true))), "FINALIZED count");
        assertIndexServed(withNotQ(Map.of(AclEpochState.FIELD_STATE, Map.of("$in", List.of(
                        AclEpochState.PENDING_EPOCH, AclEpochState.FINALIZED_NEEDS_RECONCILE)),
                AclEpochState.FIELD_MUTATION_ID, Map.of("$exists", false))), "missing-mutation-id audit");
        assertIndexServed(withNotQ(Map.of(AclEpochState.FIELD_STATE, AclEpochState.RECONCILE_ENQUEUED)),
                "terminal audit");
        assertIndexServed(withNotQ(Map.of(AclEpochState.FIELD_STATE, Map.of("$exists", true, "$nin", List.of(
                        AclEpochState.PENDING_EPOCH, AclEpochState.FINALIZED_NEEDS_RECONCILE,
                        AclEpochState.RECONCILE_ENQUEUED)))), "unknown-state audit");
        // review 3b [P1]: served by the (aclEpochMutationId) index — the (aclEpochState) index
        // cannot serve an `aclEpochState $exists:false` condition (a JSON index only contains
        // documents that HAVE the indexed field).
        assertIndexServed(withNotQ(Map.of(
                        AclEpochState.FIELD_MUTATION_ID, Map.of("$exists", true),
                        AclEpochState.FIELD_STATE, Map.of("$exists", false))),
                "state-less-with-mutation-id audit", "idx_aclEpochMutationId");
    }

    private Map<String, Object> withNotQ(Map<String, Object> stateAndFields) {
        Map<String, Object> s = new LinkedHashMap<>(stateAndFields);
        s.put("$or", List.of(
                Map.of(AclEpochState.FIELD_QUARANTINED, Map.of("$exists", false)),
                Map.of(AclEpochState.FIELD_QUARANTINED, Map.of("$ne", true))));
        return s;
    }

    // ── review 2c: guaranteed FINITE-scan progression past ANY anomaly type ──

    @Test
    void validPendingFinalizedPastNonUuidPendingBacklogInFiniteScans() {
        int budget = 3;
        for (int i = 0; i < budget + 3; i++) {
            seedPending("nonuuid-" + i, "not-a-uuid-" + i); // present but non-UUID
        }
        seedPending("valid-nu", AclEpochState.newMutationId());

        int scans = scanUntil(() -> AclEpochState.RECONCILE_ENQUEUED
                .equals(props("valid-nu").get(AclEpochState.FIELD_STATE)), budget, 20);
        assertTrue(scans <= 20, "the valid PENDING must be finalized in a FINITE number of scans");
        // The anomalous ones are durably quarantined (excluded from the live selectors).
        assertTrue(Boolean.TRUE.equals(props("nonuuid-0").get(AclEpochState.FIELD_QUARANTINED)),
                "a non-UUID PENDING is moved to durable quarantine");
    }

    @Test
    void validPendingFinalizedPastNullNonStringBlankMutationIdBacklog() {
        int budget = 2;
        for (int i = 0; i < budget + 2; i++) seedPendingRawMutationId("mnull-" + i, null);   // JSON null
        for (int i = 0; i < budget + 2; i++) seedPendingRawMutationId("mnum-" + i, 12345);    // non-String
        for (int i = 0; i < budget + 2; i++) seedPendingRawMutationId("mblank-" + i, "  ");   // blank
        seedPending("valid-mixed", AclEpochState.newMutationId());

        int scans = scanUntil(() -> AclEpochState.RECONCILE_ENQUEUED
                .equals(props("valid-mixed").get(AclEpochState.FIELD_STATE)), budget, 40);
        assertTrue(scans <= 40, "the valid PENDING must be finalized past a null/non-String/blank backlog");
    }

    @Test
    void validFinalizedNotQuarantinedPastInvalidEpochBacklog() {
        int budget = 2;
        for (int i = 0; i < budget + 3; i++) {
            Map<String, Object> p = baseFixture();
            p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
            p.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId()); // valid UUID
            p.put(AclEpochState.FIELD_SOURCE_EPOCH, 1.5d);                          // invalid epoch
            putContentRaw("badep-" + i, p);
        }
        seedFinalized("valid-final", AclEpochState.newMutationId(), 5L);

        // Drive several scans; the invalid-epoch docs are quarantined, the valid FINALIZED is
        // neither quarantined nor altered.
        for (int i = 0; i < 20; i++) svc.scan(contentDb, budget);
        assertFalse(props("valid-final").containsKey(AclEpochState.FIELD_QUARANTINED),
                "a valid FINALIZED must never be quarantined");
        assertEquals(AclEpochState.RECONCILE_ENQUEUED, props("valid-final").get(AclEpochState.FIELD_STATE),
                "a valid FINALIZED is ACKed forward, not parked (7b)");
        assertTrue(Boolean.TRUE.equals(props("badep-0").get(AclEpochState.FIELD_QUARANTINED)),
                "an invalid-epoch FINALIZED is quarantined");
    }

    /** Run scan() repeatedly (per-pass budget = {@code budget}) until {@code done} or {@code maxScans}. */
    private int scanUntil(java.util.function.BooleanSupplier done, int budget, int maxScans) {
        for (int i = 1; i <= maxScans; i++) {
            svc.scan(contentDb, budget);
            if (done.getAsBoolean()) return i;
        }
        return maxScans + 1; // signal "did not converge"
    }

    private void assertIndexServed(Map<String, Object> selector, String label) throws Exception {
        assertIndexServed(selector, label, "idx_aclEpochState");
    }

    private void assertIndexServed(Map<String, Object> selector, String label, String expectedIndex)
            throws Exception {
        ObjectMapper om = ObjectMapperFactory.createDefaultObjectMapper();
        String body = om.writeValueAsString(Map.of("selector", selector));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + contentDb + "/_explain"))
                .header("Authorization", basicAuth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), label + " _explain failed: " + resp.body());
        JsonNode root = om.readTree(resp.body());
        String indexName = root.path("index").path("name").asText(null);
        assertEquals(expectedIndex, indexName,
                label + " selector must be served by " + expectedIndex + ", not _all_docs");
    }

    // ── review 2d: quarantine race / bypass / terminal / failure ───

    @Test
    void quarantineAbortsWhenTheDocumentIsAlreadyRepaired() {
        // review 2d [P1]: a stale anomaly detection must NOT quarantine a document that is
        // now valid (a concurrent normal Phase 1 repaired it). quarantine() re-validates.
        seedPending("repaired", AclEpochState.newMutationId());
        ScanSummary sum = new ScanSummary();
        svc.quarantine(contentDb, "repaired", "stale non-UUID anomaly", sum);
        assertEquals(0, sum.quarantined, "a repaired (valid) document must not be quarantined");
        assertFalse(props("repaired").containsKey(AclEpochState.FIELD_QUARANTINED));
    }

    @Test
    void quarantineProceedsWhenStillAnomalous() {
        seedPending("stillbad", "not-a-uuid");
        ScanSummary sum = new ScanSummary();
        svc.quarantine(contentDb, "stillbad", "non-UUID", sum);
        assertEquals(1, sum.quarantined);
        assertTrue(Boolean.TRUE.equals(props("stillbad").get(AclEpochState.FIELD_QUARANTINED)));
    }

    @Test
    void nonTrueQuarantineMarkerDoesNotHideAndIsNormalizedToTrue() {
        // review 2d [P1]: a false / "false" / numeric marker must NOT hide a document from
        // the scanner; it is surfaced and normalized to Boolean true.
        seedPendingWithMarker("q-false", false);
        seedPendingWithMarker("q-string", "false");
        seedPendingWithMarker("q-num", 0);
        for (int i = 0; i < 5; i++) svc.scan(contentDb, 100);
        assertEquals(Boolean.TRUE, props("q-false").get(AclEpochState.FIELD_QUARANTINED));
        assertEquals(Boolean.TRUE, props("q-string").get(AclEpochState.FIELD_QUARANTINED));
        assertEquals(Boolean.TRUE, props("q-num").get(AclEpochState.FIELD_QUARANTINED));
    }

    @Test
    void directFinalizeOnAQuarantinedDocumentIsRejected() {
        // review 2d [P1]: even a valid-looking PENDING that is quarantined must be refused by
        // a direct finalizer (no epoch consumed, state unchanged).
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        p.put(AclEpochState.FIELD_QUARANTINED, true);
        putContentRaw("q-direct", p);

        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "q-direct"));
        assertEquals(AclEpochState.PENDING_EPOCH, props("q-direct").get(AclEpochState.FIELD_STATE));
        assertEquals(0L, counterValue(contentDb), "a quarantined document must not consume an epoch");
    }

    @Test
    void quarantinePreservesInlineAttachment() {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, "not-a-uuid"); // anomalous -> will be quarantined
        Document d = new Document();
        d.setId("q-att");
        d.setProperties(p);
        Attachment att = new Attachment.Builder().contentType("text/plain").data("hi".getBytes()).build();
        Map<String, Attachment> atts = new LinkedHashMap<>();
        atts.put("keep.txt", att);
        d.setAttachments(atts);
        cloudant.putDocument(new PutDocumentOptions.Builder().db(contentDb).docId("q-att").document(d).build()).execute();

        svc.scan(contentDb, 100);
        Document after = getContent("q-att");
        assertEquals(Boolean.TRUE, after.getProperties().get(AclEpochState.FIELD_QUARANTINED));
        assertTrue(after.getAttachments() != null && after.getAttachments().containsKey("keep.txt"),
                "quarantine must preserve the inline attachment");
    }

    @Test
    void invalidReconcileEnqueuedIsQuarantinedValidIsCounted() {
        // review 2d [P2]: the terminal state is audited too.
        Map<String, Object> bad = baseFixture();
        bad.put(AclEpochState.FIELD_STATE, AclEpochState.RECONCILE_ENQUEUED);
        bad.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        bad.put(AclEpochState.FIELD_SOURCE_EPOCH, -3L); // invalid epoch
        putContentRaw("enq-bad", bad);

        Map<String, Object> good = baseFixture();
        good.put(AclEpochState.FIELD_STATE, AclEpochState.RECONCILE_ENQUEUED);
        good.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        good.put(AclEpochState.FIELD_SOURCE_EPOCH, 4L);
        putContentRaw("enq-good", good);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(Boolean.TRUE, props("enq-bad").get(AclEpochState.FIELD_QUARANTINED),
                "an invalid RECONCILE_ENQUEUED is quarantined");
        assertFalse(props("enq-good").containsKey(AclEpochState.FIELD_QUARANTINED),
                "a valid RECONCILE_ENQUEUED is not quarantined");
        assertTrue(sum.enqueued >= 1, "a valid RECONCILE_ENQUEUED is counted");
    }

    @Test
    void quarantineFailureIsReportedNotSwallowed() throws Exception {
        // review 2d [P2]: a quarantine that cannot durably persist must surface in the summary
        // (quarantineFailures + more), never appear as silent success. A concurrent writer that
        // keeps bumping the (still-anomalous) doc forces the CAS to lose. The invariant holds
        // whichever side wins: either it quarantines, or it records a failure.
        seedPending("qfail", "not-a-uuid");
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread bumper = new Thread(() -> {
            while (!stop.get()) {
                try {
                    Document d = getContent("qfail");
                    if (Boolean.TRUE.equals(d.getProperties().get(AclEpochState.FIELD_QUARANTINED))) break;
                    d.getProperties().put("bump", UUID.randomUUID().toString());
                    putContent(d);
                } catch (Exception ignore) { /* race with the quarantine PUT */ }
            }
        });
        bumper.start();
        ScanSummary sum = new ScanSummary();
        svc.quarantine(contentDb, "qfail", "non-UUID", sum);
        stop.set(true);
        bumper.join(5000);
        assertTrue(sum.quarantined == 1 || (sum.quarantineFailures >= 1 && sum.more),
                "quarantine must either succeed or record a failure (never silent): " + sum.quarantineFailures);
    }

    // ── review 2e: explicit-null marker (SDK stores JSON null as present) ──

    @Test
    void explicitNullQuarantineMarkerIsNotHiddenAndIsNormalizedToTrue() throws Exception {
        // The IBM Cloudant SDK stores an explicit JSON null as a PRESENT map entry, so a
        // {"aclEpochQuarantined": null} must be treated as a malformed marker (not absent):
        // the $ne:true branch of the selector matches it, validate() rejects it, and it is
        // normalized to true. Written via raw HTTP to guarantee an explicit JSON null.
        String m = AclEpochState.newMutationId();
        putRawJson("q-null", "{\"type\":\"epoch-it-fixture\",\"aclEpochState\":\"PENDING_EPOCH\","
                + "\"aclEpochMutationId\":\"" + m + "\",\"aclEpochQuarantined\":null}");
        for (int i = 0; i < 3; i++) svc.scan(contentDb, 100);
        assertEquals(Boolean.TRUE, props("q-null").get(AclEpochState.FIELD_QUARANTINED),
                "an explicit-null marker must be normalized to true, not treated as absent");
        assertEquals(AclEpochState.PENDING_EPOCH, props("q-null").get(AclEpochState.FIELD_STATE),
                "the null-marker doc must NOT be finalized (it is a marker anomaly)");
    }

    @Test
    void directFinalizeOnExplicitNullMarkerIsRejectedBeforeAllocate() throws Exception {
        String m = AclEpochState.newMutationId();
        putRawJson("q-null-fin", "{\"type\":\"epoch-it-fixture\",\"aclEpochState\":\"PENDING_EPOCH\","
                + "\"aclEpochMutationId\":\"" + m + "\",\"aclEpochQuarantined\":null}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "q-null-fin"));
        assertEquals(0L, counterValue(contentDb), "an explicit-null marker must not consume an epoch");
    }

    @Test
    void quarantineNormalizesAnExplicitNullMarkerOnReGet() throws Exception {
        String m = AclEpochState.newMutationId();
        putRawJson("q-null-q", "{\"type\":\"epoch-it-fixture\",\"aclEpochState\":\"PENDING_EPOCH\","
                + "\"aclEpochMutationId\":\"" + m + "\",\"aclEpochQuarantined\":null}");
        ScanSummary sum = new ScanSummary();
        svc.quarantine(contentDb, "q-null-q", "explicit null marker", sum);
        assertEquals(1, sum.quarantined, "an explicit-null marker present on re-GET must be quarantined");
        assertEquals(Boolean.TRUE, props("q-null-q").get(AclEpochState.FIELD_QUARANTINED));
    }

    @Test
    void quarantineAbortsWhenTheMarkerWasRemovedAndEpochIsValid() {
        // The marker was cleared (repaired) and the epoch fields are valid → quarantine aborts
        // (a repaired document must never be quarantined), even though an anomaly was detected
        // earlier.
        seedPending("q-cleared", AclEpochState.newMutationId()); // valid, no marker
        ScanSummary sum = new ScanSummary();
        svc.quarantine(contentDb, "q-cleared", "stale marker anomaly", sum);
        assertEquals(0, sum.quarantined);
        assertFalse(props("q-cleared").containsKey(AclEpochState.FIELD_QUARANTINED));
    }

    // ── review 2f: present-null state / contention / terminal cursor ──

    @Test
    void presentNullStateIsQuarantinedNotTreatedAsRepairedStateLess() throws Exception {
        // An explicit-null aclEpochState is PRESENT (SDK contract), so it is corruption, not
        // "repaired to state-less" — the unknown-state audit selects it and quarantine must
        // contain it (review 2f: the containsKey fix on the STATE field, not just the marker).
        putRawJson("nullstate", "{\"type\":\"epoch-it-fixture\",\"aclEpochState\":null}");
        for (int i = 0; i < 3; i++) svc.scan(contentDb, 100);
        assertEquals(Boolean.TRUE, props("nullstate").get(AclEpochState.FIELD_QUARANTINED),
                "a present-null aclEpochState must be quarantined, not skipped as state-less");
    }

    @Test
    void directFinalizeOnPresentNullStateIsAnomalyNotSilentSkip() throws Exception {
        putRawJson("nullstate-fin", "{\"type\":\"epoch-it-fixture\",\"aclEpochState\":null,"
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "nullstate-fin"));
    }

    @Test
    void contentionOnAValidPendingIsRecordedAndNeverQuarantined() throws Exception {
        // A finalize CAS livelock (a competing writer bumping a VALID PENDING) is CONTENTION,
        // not a data anomaly: it must be recorded (contended/more) and the doc NEVER quarantined
        // (review 2f [P3]). A bumper keeps the doc a valid PENDING so finalize keeps losing.
        seedPending("contend", AclEpochState.newMutationId());
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread bumper = new Thread(() -> {
            while (!stop.get()) {
                try {
                    Document d = getContent("contend");
                    Map<String, Object> p = d.getProperties();
                    if (!AclEpochState.PENDING_EPOCH.equals(p.get(AclEpochState.FIELD_STATE))) break; // finalized
                    p.put("bump", UUID.randomUUID().toString()); // keep state + mutation id (still valid PENDING)
                    d.setProperties(p);
                    putContent(d);
                } catch (Exception ignore) { /* race with the finalize CAS */ }
            }
        });
        bumper.start();
        ScanSummary last = null;
        for (int i = 0; i < 3; i++) {
            last = svc.scan(contentDb, 100);
            if (AclEpochState.FINALIZED_NEEDS_RECONCILE.equals(props("contend").get(AclEpochState.FIELD_STATE))) break;
        }
        stop.set(true);
        bumper.join(5000);
        assertFalse(props("contend").containsKey(AclEpochState.FIELD_QUARANTINED),
                "a valid contended PENDING must NEVER be quarantined");
    }

    @Test
    void corruptTerminalDocIsQuarantinedPastAValidBacklogInFiniteScans() {
        // review 2f [P1]: valid FINALIZED docs never leave the terminal selector (no ACK yet).
        // With a persistent cursor, a corrupt FINALIZED behind a >budget valid backlog is still
        // reached and quarantined within a FINITE number of scans.
        int budget = 3;
        for (int i = 0; i < budget + 4; i++) {
            seedFinalized("vf-" + i, AclEpochState.newMutationId(), 10 + i); // > budget valid FINALIZED
        }
        Map<String, Object> bad = baseFixture();
        bad.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        bad.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        bad.put(AclEpochState.FIELD_SOURCE_EPOCH, 0L); // invalid epoch (< 1)
        putContentRaw("vf-bad", bad);

        int scans = scanUntil(() -> Boolean.TRUE.equals(props("vf-bad").get(AclEpochState.FIELD_QUARANTINED)),
                budget, 30);
        assertTrue(scans <= 30, "the corrupt terminal doc must be quarantined in a FINITE number of scans "
                + "via the resume cursor (took " + scans + ")");
    }

    // ── review 3b [P1]: state lost, mutation id survived ───────────

    @Test
    void stateLessDocumentWithALeftoverMutationIdIsQuarantinedInFiniteScans() throws Exception {
        // Every OTHER selector keys on aclEpochState, so this shape is invisible to them and its
        // aclSourceEpoch would be consumed as "settled" forever. The dedicated pass must find it
        // AND the quarantine must actually stick — a quarantine that aborts (treating "state-less"
        // as repaired) would make the pass select the same document on every scan for ever.
        putRawJson("lost-marker", "{\"type\":\"epoch-it-fixture\",\"aclSourceEpoch\":900,"
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");

        int scans = scanUntil(() -> Boolean.TRUE.equals(props("lost-marker").get(AclEpochState.FIELD_QUARANTINED)),
                100, 5);
        assertTrue(scans <= 5, "a state-less doc with a leftover mutation id must be quarantined "
                + "in a FINITE number of scans (took " + scans + ")");
        assertEquals(900L, ((Number) props("lost-marker").get(AclEpochState.FIELD_SOURCE_EPOCH)).longValue(),
                "quarantine preserves the original fields for inspection / repair");
    }

    @Test
    void aFullyStateLessDocumentIsStillNeverSelected() {
        // The new pass must not widen the scanner's reach to ordinary content: only a LEFTOVER
        // mutation id qualifies.
        seedStateless("plain-nostate");
        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(0, sum.scanned, "ordinary state-less content is still never selected");
        assertFalse(props("plain-nostate").containsKey(AclEpochState.FIELD_QUARANTINED));
    }

    @Test
    void repairingByClearingBothFieldsStopsTheQuarantine() {
        // The repair contract: clearing state AND mutation id together makes the document ordinary
        // settled content, and quarantine must then ABORT rather than isolate a repaired document.
        seedStateless("repaired-both");
        ScanSummary sum = new ScanSummary();
        svc.quarantine(contentDb, "repaired-both", "stale leftover-mutation-id anomaly", sum);
        assertEquals(0, sum.quarantined, "a document with NEITHER field is repaired, not anomalous");
        assertFalse(props("repaired-both").containsKey(AclEpochState.FIELD_QUARANTINED));
    }

    // ── review 3c: direct finalizer + index pinning ────────────────

    @Test
    void directFinalizeOnStateLessWithLeftoverMutationIdIsAnomalyAndConsumesNoEpoch() throws Exception {
        // The direct public entry point must enforce what the scanner does: "state absent BUT
        // mutation id present" is corruption (the steady state clears both), NOT a clean skip.
        putRawJson("direct-lost", "{\"type\":\"epoch-it-fixture\",\"aclSourceEpoch\":900,"
                + "\"aclEpochMutationId\":\"" + AclEpochState.newMutationId() + "\"}");

        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "direct-lost"));
        assertEquals(0L, counterValue(contentDb), "a rejected finalize must not consume an epoch");
        assertEquals(900L, ((Number) props("direct-lost").get(AclEpochState.FIELD_SOURCE_EPOCH)).longValue(),
                "the document is left untouched");
    }

    @Test
    void directFinalizeOnGenuinelyStateLessContentIsStillACleanSkip() {
        // The other half of the contract: NEITHER field = ordinary content, not our business.
        seedStateless("direct-plain");
        FinalizeOutcome o = svc.finalizePending(contentDb, "direct-plain");
        assertEquals(FinalizeResult.SKIPPED_NOT_PENDING, o.result);
        assertNull(o.epoch);
        assertEquals(0L, counterValue(contentDb));
    }

    @Test
    void nullNumericAndBlankMutationIdsAreFoundViaTheRealIndexAndQuarantined() throws Exception {
        // All three malformed shapes must be reachable through the (aclEpochMutationId) index —
        // CouchDB indexes a null/numeric value, so $exists:true matches them.
        putRawJson("mid-null",  "{\"type\":\"epoch-it-fixture\",\"aclEpochMutationId\":null}");
        putRawJson("mid-num",   "{\"type\":\"epoch-it-fixture\",\"aclEpochMutationId\":12345}");
        putRawJson("mid-blank", "{\"type\":\"epoch-it-fixture\",\"aclEpochMutationId\":\"  \"}");

        for (int i = 0; i < 3; i++) svc.scan(contentDb, 100);
        assertEquals(Boolean.TRUE, props("mid-null").get(AclEpochState.FIELD_QUARANTINED));
        assertEquals(Boolean.TRUE, props("mid-num").get(AclEpochState.FIELD_QUARANTINED));
        assertEquals(Boolean.TRUE, props("mid-blank").get(AclEpochState.FIELD_QUARANTINED));
    }

    @Test
    void scanFailsRatherThanFullScanningWhenTheMutationIdIndexIsMissing() throws Exception {
        // CouchDB 3.3.x does NOT reject a use_index naming a missing index — it SILENTLY falls back
        // to _all_docs and returns 200 (only a `warning` field betrays it). Once the scanner is
        // auto-started that would full-scan a large content DB on every tick, so the scan must fail
        // loudly instead (review 3c [P2]).
        dropIndex("idx_aclEpochMutationId");
        putRawJson("needs-index", "{\"type\":\"epoch-it-fixture\",\"aclEpochMutationId\":\""
                + AclEpochState.newMutationId() + "\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.scan(contentDb, 100));
        assertTrue(e.getMessage().contains("idx_aclEpochMutationId"),
                "the failure must name the index to repair: " + e.getMessage());
        assertFalse(props("needs-index").containsKey(AclEpochState.FIELD_QUARANTINED),
                "nothing is processed from an unindexed scan");
    }

    @Test
    void scanFailsWhenTheStateIndexIsMissingToo() throws Exception {
        dropIndex("idx_aclEpochState");
        seedPending("needs-state-index", AclEpochState.newMutationId());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.scan(contentDb, 100));
        assertTrue(e.getMessage().contains("idx_aclEpochState"), e.getMessage());
    }

    // ── review 3d: no short-circuit ahead of the shared validator ──

    @Test
    void directFinalizeOnStateLessWithOnlyALeftoverQuarantineMarkerIsAnomaly() throws Exception {
        // An INCOMPLETE repair: state and mutation id were cleared but the quarantine marker was
        // forgotten. The pre-3d fast path ("neither field → ordinary content") reported this as a
        // clean skip; the marker must be surfaced instead.
        putRawJson("q-only", "{\"type\":\"epoch-it-fixture\",\"aclEpochQuarantined\":true}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "q-only"));

        putRawJson("q-only-false", "{\"type\":\"epoch-it-fixture\",\"aclEpochQuarantined\":false}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "q-only-false"));

        putRawJson("q-only-null", "{\"type\":\"epoch-it-fixture\",\"aclEpochQuarantined\":null}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "q-only-null"));
        assertEquals(0L, counterValue(contentDb), "no epoch is consumed by any of them");
    }

    @Test
    void directFinalizeOnStateLessWithOnlyACorruptEpochIsAnomaly() throws Exception {
        // Same class: no state, no mutation id, but a corrupt aclSourceEpoch. Previously a clean
        // skip; the corruption must be raised (a negative/fractional epoch is not a valid fence).
        putRawJson("e-neg",  "{\"type\":\"epoch-it-fixture\",\"aclSourceEpoch\":-1}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "e-neg"));

        putRawJson("e-null", "{\"type\":\"epoch-it-fixture\",\"aclSourceEpoch\":null}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "e-null"));

        putRawJson("e-frac", "{\"type\":\"epoch-it-fixture\",\"aclSourceEpoch\":1.5}");
        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, "e-frac"));
        assertEquals(0L, counterValue(contentDb));
    }

    @Test
    void directFinalizeOnSettledContentWithAValidEpochIsStillACleanSkip() throws Exception {
        // The positive control for the stricter order: the STEADY STATE (marker cleared, a valid
        // epoch retained, no state, no mutation id) must remain a clean skip.
        putRawJson("settled-ok", "{\"type\":\"epoch-it-fixture\",\"aclSourceEpoch\":12}");
        FinalizeOutcome o = svc.finalizePending(contentDb, "settled-ok");
        assertEquals(FinalizeResult.SKIPPED_NOT_PENDING, o.result);
        assertNull(o.epoch);
        assertEquals(0L, counterValue(contentDb));
    }

    // ── review 3d: the index pre-flight matches the DEFINITION, not just the name ──

    @Test
    void scanFailsWhenASameNamedIndexLivesInAnotherDesignDocument() throws Exception {
        dropIndex("idx_aclEpochMutationId");
        createJsonIndex("other-ddoc", "idx_aclEpochMutationId", AclEpochState.FIELD_MUTATION_ID, "asc");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.scan(contentDb, 100));
        assertTrue(e.getMessage().contains("idx_aclEpochMutationId"), e.getMessage());
    }

    @Test
    void scanFailsWhenASameNamedIndexCoversTheWrongField() throws Exception {
        dropIndex("idx_aclEpochMutationId");
        createJsonIndex("acl-epoch-indexes", "idx_aclEpochMutationId", "someOtherField", "asc");
        assertThrows(IllegalStateException.class, () -> svc.scan(contentDb, 100));
    }

    @Test
    void scanFailsWhenASameNamedIndexUsesTheWrongDirection() throws Exception {
        dropIndex("idx_aclEpochState");
        createJsonIndex("acl-epoch-indexes", "idx_aclEpochState", AclEpochState.FIELD_STATE, "desc");
        assertThrows(IllegalStateException.class, () -> svc.scan(contentDb, 100));
    }

    @Test
    void scanFailsWhenASameNamedIndexIsPartial() throws Exception {
        // A partial index silently OMITS documents, so it must never satisfy the pre-flight.
        dropIndex("idx_aclEpochState");
        createPartialJsonIndex("acl-epoch-indexes", "idx_aclEpochState", AclEpochState.FIELD_STATE);
        assertThrows(IllegalStateException.class, () -> svc.scan(contentDb, 100));
    }

    @Test
    void scanFailsWhenASameNamedIndexCoversExtraFields() throws Exception {
        dropIndex("idx_aclEpochState");
        createTwoFieldJsonIndex("acl-epoch-indexes", "idx_aclEpochState",
                AclEpochState.FIELD_STATE, AclEpochState.FIELD_MUTATION_ID);
        assertThrows(IllegalStateException.class, () -> svc.scan(contentDb, 100));
    }

    @Test
    void scanSucceedsWithTheCorrectlyDefinedIndexes() {
        // Positive control: the definitions this IT creates in setUp are exactly what is required,
        // so the strict pre-flight must NOT reject a healthy database.
        seedPending("healthy", AclEpochState.newMutationId());
        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(1, sum.finalized);
    }

    // ── review 2g: terminal-audit resume cursor robustness ─────────

    /** Deterministic id of the per-content-DB terminal-audit resume cursor. */
    private static final String CURSOR_ID = "acl-epoch-audit-cursor";

    @Test
    void invalidStoredBookmarkSelfHealsAndReachesRearTerminalAnomaly() {
        // review 2g [P1]: a stored bookmark that is invalid (garbage / expired) must NOT
        // permanently stall the terminal audit. On the invalid_bookmark 400 the cursor is
        // CAS-cleared and the pass retries from the top ONCE, so a corrupt terminal doc behind a
        // > budget valid backlog is still reached and quarantined in a FINITE number of scans.
        int budget = 2;
        for (int i = 0; i < 6; i++) seedFinalized("t-vf-" + i, AclEpochState.newMutationId(), 20 + i);
        Map<String, Object> bad = baseFixture();
        bad.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        bad.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        bad.put(AclEpochState.FIELD_SOURCE_EPOCH, 0L); // invalid epoch (< 1) → must be quarantined
        putContentRaw("t-zzz-bad", bad);               // sorts LAST → a true rear anomaly

        seedAuditCursor("garbage-not-a-real-bookmark"); // CouchDB returns invalid_bookmark for this

        int scans = scanUntil(() -> Boolean.TRUE.equals(props("t-zzz-bad").get(AclEpochState.FIELD_QUARANTINED)),
                budget, 30);
        assertTrue(scans <= 30, "an invalid stored bookmark must self-heal and the rear anomaly still reached");
        // The cursor doc self-healed into a valid cursor (type preserved, not a foreign doc).
        assertEquals("aclEpochAuditCursor", props(CURSOR_ID).get("type"));
    }

    @Test
    void reindexAndInvalidatedBookmarkSelfHealReachesRearAnomaly() throws Exception {
        // review 2g [P1]: after the cursor has advanced (a real bookmark persisted), an index
        // rebuild + an invalidated bookmark must self-heal and still reach a rear terminal anomaly.
        // NOTE: CouchDB 3.3.3 keeps bookmarks valid across an index rebuild, so the invalidity is
        // induced by corrupting the persisted bookmark — the self-heal code path is identical.
        int budget = 2;
        for (int i = 0; i < 6; i++) seedFinalized("r-vf-" + i, AclEpochState.newMutationId(), 30 + i);
        Map<String, Object> bad = baseFixture();
        bad.put(AclEpochState.FIELD_STATE, AclEpochState.RECONCILE_ENQUEUED);
        bad.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        bad.put(AclEpochState.FIELD_SOURCE_EPOCH, -1L); // invalid epoch
        putContentRaw("r-zzz-bad", bad);                // sorts LAST

        // One scan advances the sweep and persists a REAL resume bookmark (7 terminal > budget).
        svc.scan(contentDb, budget);
        assertTrue(props(CURSOR_ID).get("terminalBookmark") instanceof String,
                "a real bookmark is persisted after a partial terminal sweep");

        recreateAclEpochStateIndex();   // simulate an index rebuild
        corruptAuditCursorBookmark();   // the persisted bookmark has become invalid

        int scans = scanUntil(() -> Boolean.TRUE.equals(props("r-zzz-bad").get(AclEpochState.FIELD_QUARANTINED)),
                budget, 30);
        assertTrue(scans <= 30, "a corrupted resume bookmark self-heals and the rear anomaly is still reached");
    }

    @Test
    void foreignDocumentAtCursorIdIsLeftUntouchedAndAuditSkipped() {
        // review 2g [P1]: if a NON-cursor document occupies the cursor id, the terminal audit is
        // skipped fail-closed — the foreign document (and its attachment) is NEVER modified and a
        // cursorFailure is reported.
        Map<String, Object> foreign = new LinkedHashMap<>();
        foreign.put("type", "some-other-document");
        foreign.put("name", "not-a-cursor");
        Document d = new Document();
        d.setId(CURSOR_ID);
        d.setProperties(foreign);
        Attachment att = new Attachment.Builder().contentType("text/plain").data("keep".getBytes()).build();
        Map<String, Attachment> atts = new LinkedHashMap<>();
        atts.put("f.txt", att);
        d.setAttachments(atts);
        cloudant.putDocument(new PutDocumentOptions.Builder().db(contentDb).docId(CURSOR_ID).document(d).build()).execute();
        String revBefore = getContent(CURSOR_ID).getRev();

        seedFinalized("fa-ok", AclEpochState.newMutationId(), 5L); // a valid terminal doc that WOULD be audited

        ScanSummary sum = svc.scan(contentDb, 100);
        assertTrue(sum.cursorFailures >= 1, "a foreign cursor doc is reported as a cursor failure");
        assertTrue(sum.errors.stream().anyMatch(e -> CURSOR_ID.equals(e.get("docId"))));
        assertEquals(0, sum.awaitingReconcile, "the terminal audit is skipped when the cursor is unusable");

        Document after = getContent(CURSOR_ID);
        assertEquals(revBefore, after.getRev(), "the foreign document must NOT be modified");
        assertEquals("some-other-document", after.getProperties().get("type"), "foreign type preserved");
        assertTrue(after.getAttachments() != null && after.getAttachments().containsKey("f.txt"),
                "the foreign document's inline attachment must be untouched");
    }

    @Test
    void cursorSavePersistentConflictIsReportedNotSwallowed() {
        // review 2g [P1]: a cursor save that never converges (every CAS conflicts) must surface in
        // the summary (cursorFailures + more), never appear as silent success. Deterministic: a
        // subclass forces every PUT of the cursor id to 409.
        int budget = 2;
        for (int i = 0; i < 6; i++) seedFinalized("cs-vf-" + i, AclEpochState.newMutationId(), 40 + i); // > budget → not exhausted → save a bookmark
        AclEpochFinalizationService failing = new AclEpochFinalizationService() {
            @Override String putBack(String repositoryId, Document doc) {
                if (CURSOR_ID.equals(doc.getId())) return null; // every cursor CAS "conflicts"
                return super.putBack(repositoryId, doc);
            }
        };
        failing.setConnectorPool(pool);
        failing.setCounterService(counter);
        failing.setReconciliationService(reconcileSvc);

        ScanSummary sum = failing.scan(contentDb, budget);
        assertTrue(sum.cursorFailures >= 1, "a cursor save that never converges must be reported");
        assertTrue(sum.more, "an unsaved cursor sets more so a driver re-scans");
        assertTrue(sum.errors.stream().anyMatch(e -> CURSOR_ID.equals(e.get("docId"))),
                "the cursor-save failure names the cursor id: " + sum.errors);
    }

    @Test
    void terminalAuditProgressesEvenWhenServiceIsRecreatedEachScan() {
        // review 2g [P1]: the resume cursor is DURABLE (a CouchDB document), so a brand-new
        // service instance per scan still cycles through the terminal set to a rear anomaly. If the
        // cursor were in-memory each fresh instance would restart from the top and never reach a
        // last-sorted doc behind a > budget valid backlog.
        int budget = 2;
        for (int i = 0; i < 6; i++) seedFinalized("sr-vf-" + i, AclEpochState.newMutationId(), 50 + i);
        Map<String, Object> bad = baseFixture();
        bad.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        bad.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        bad.put(AclEpochState.FIELD_SOURCE_EPOCH, 0L); // invalid → must be reached + quarantined
        putContentRaw("sr-zzz-bad", bad);              // sorts LAST (7th of 7 terminal docs)

        int reached = -1;
        for (int i = 1; i <= 30 && reached < 0; i++) {
            AclEpochFinalizationService fresh = new AclEpochFinalizationService(); // NEW instance each scan
            fresh.setConnectorPool(pool);
            fresh.setCounterService(counter);
            fresh.setReconciliationService(reconcileSvc);
            fresh.scan(contentDb, budget);
            if (Boolean.TRUE.equals(props("sr-zzz-bad").get(AclEpochState.FIELD_QUARANTINED))) reached = i;
        }
        assertTrue(reached > 0 && reached <= 30,
                "a fresh service per scan still reaches the rear anomaly via the DURABLE cursor (took " + reached + ")");
        assertTrue(reached > 1, "the rear anomaly is only reached after the cursor advances across scans");
    }

    @Test
    void deterministicFinalizeContentionIsRecordedNotQuarantined() {
        // review 2g [P2]: force a finalize CAS livelock (8 conflicts) deterministically — a
        // subclass makes every PUT of the target doc conflict. The result is CONTENTION, not a
        // data anomaly: contended==1, more==true, the doc stays a valid PENDING, never quarantined.
        seedPending("cc-1", AclEpochState.newMutationId());
        AclEpochFinalizationService neverCommits = new AclEpochFinalizationService() {
            @Override String putBack(String repositoryId, Document doc) {
                if ("cc-1".equals(doc.getId())) return null; // every finalize CAS "conflicts" (8×)
                return super.putBack(repositoryId, doc);
            }
        };
        neverCommits.setConnectorPool(pool);
        neverCommits.setCounterService(counter);
        neverCommits.setReconciliationService(reconcileSvc);

        ScanSummary sum = neverCommits.scan(contentDb, 100);
        assertEquals(1, sum.contended, "the PENDING that never converges is counted as contended");
        assertTrue(sum.more, "contention sets more so the driver re-scans");
        assertFalse(props("cc-1").containsKey(AclEpochState.FIELD_QUARANTINED),
                "a contended VALID PENDING must NEVER be quarantined");
        assertEquals(AclEpochState.PENDING_EPOCH, props("cc-1").get(AclEpochState.FIELD_STATE),
                "the contended doc stays PENDING for a later scan");
    }

    // ── review 2h: strict cursor schemaVersion validation ──────────

    @Test
    void cursorWithoutSchemaVersionIsUnusableAndLeftUntouched() throws Exception {
        // A 2f-era cursor (type present, NO schemaVersion) is NOT adopted implicitly: it is
        // reported and left byte-for-byte untouched (review 2h [P2]).
        assertCursorUnusableAndUntouched(
                "{\"type\":\"aclEpochAuditCursor\",\"terminalBookmark\":\"some-bookmark\"}",
                "absent schemaVersion");
    }

    @Test
    void cursorWithExplicitNullSchemaVersionIsUnusableAndLeftUntouched() throws Exception {
        assertCursorUnusableAndUntouched(
                "{\"type\":\"aclEpochAuditCursor\",\"schemaVersion\":null,\"terminalBookmark\":\"b\"}",
                "explicit-null schemaVersion");
    }

    @Test
    void cursorWithFractionalSchemaVersionIsUnusableAndLeftUntouched() throws Exception {
        assertCursorUnusableAndUntouched(
                "{\"type\":\"aclEpochAuditCursor\",\"schemaVersion\":1.5,\"terminalBookmark\":\"b\"}",
                "non-integral schemaVersion 1.5");
    }

    @Test
    void cursorWithStringSchemaVersionIsUnusableAndLeftUntouched() throws Exception {
        assertCursorUnusableAndUntouched(
                "{\"type\":\"aclEpochAuditCursor\",\"schemaVersion\":\"1\",\"terminalBookmark\":\"b\"}",
                "string schemaVersion \"1\"");
    }

    @Test
    void cursorWithFutureSchemaVersionIsUnusableAndLeftUntouched() throws Exception {
        // A NEWER build's cursor must never be silently downgraded by this one.
        assertCursorUnusableAndUntouched(
                "{\"type\":\"aclEpochAuditCursor\",\"schemaVersion\":2,\"terminalBookmark\":\"b\"}",
                "future schemaVersion 2");
    }

    @Test
    void cursorWithCurrentSchemaVersionIsUsedNormally() {
        // The positive control: schemaVersion == 1 IS accepted (the strict check does not
        // fail-closed on a legitimate cursor), and the terminal audit runs.
        seedFinalized("okv-1", AclEpochState.newMutationId(), 61L);
        seedAuditCursor(null); // valid cursor doc, no bookmark yet

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(0, sum.cursorFailures, "a schemaVersion=1 cursor is usable: " + sum.errors);
        assertTrue(sum.acked + sum.awaitingReconcile >= 1,
                "the terminal audit runs with a valid cursor (the doc may already be ACKed)");
    }

    /**
     * Seed the cursor id with the given RAW JSON (plus an inline attachment), run a scan, and
     * assert: a cursorFailure is reported, the terminal audit is SKIPPED, and the document is
     * completely untouched (same _rev, same properties, attachment intact).
     */
    private void assertCursorUnusableAndUntouched(String cursorJson, String label) throws Exception {
        seedFinalized("cv-terminal", AclEpochState.newMutationId(), 60L); // would be audited if the pass ran
        putRawJson(CURSOR_ID, cursorJson);
        // Add an inline attachment so a clobber would be visible beyond the properties.
        Document withAtt = getContent(CURSOR_ID);
        Attachment att = new Attachment.Builder().contentType("text/plain").data("cursor-att".getBytes()).build();
        Map<String, Attachment> atts = new LinkedHashMap<>();
        atts.put("c.txt", att);
        withAtt.setAttachments(atts);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(contentDb).docId(CURSOR_ID).document(withAtt).build()).execute();

        Document before = getContent(CURSOR_ID);
        String revBefore = before.getRev();
        Map<String, Object> propsBefore = new LinkedHashMap<>(before.getProperties());

        ScanSummary sum = svc.scan(contentDb, 100);

        assertTrue(sum.cursorFailures >= 1, label + " must be reported as a cursor failure: " + sum.errors);
        assertTrue(sum.more, label + " must set more");
        assertTrue(sum.errors.stream().anyMatch(e -> CURSOR_ID.equals(e.get("docId"))),
                label + " must name the cursor id: " + sum.errors);
        assertEquals(0, sum.awaitingReconcile, label + " must SKIP the terminal audit");

        Document after = getContent(CURSOR_ID);
        assertEquals(revBefore, after.getRev(), label + ": the cursor document must NOT be modified");
        assertEquals(propsBefore, after.getProperties(), label + ": properties must be unchanged "
                + "(no implicit schemaVersion upgrade)");
        assertTrue(after.getAttachments() != null && after.getAttachments().containsKey("c.txt"),
                label + ": the inline attachment must be untouched");
    }

    // ── fixtures / helpers ─────────────────────────────────────────

    private Map<String, Object> baseFixture() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "epoch-it-fixture");
        p.put("name", "keep-me");
        return p;
    }

    // ── the outbox ACK (increment 7b — closes wiring gate 1) ───────

    private jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask taskFor(String docId) {
        return reconcileSvc.list(2000).stream()
                .filter(t -> contentDb.equals(t.getRepositoryId()) && docId.equals(t.getObjectId()))
                .findFirst().orElse(null);
    }

    /** The ACK's whole point: the obligation is DURABLE, and only then does the marker advance. */
    @Test
    void ackEstablishesTheObligationBEFOREAdvancingTheMarker() {
        seedFinalized("ack-ok", AclEpochState.newMutationId(), 42L);

        assertEquals(AclEpochFinalizationService.AckResult.ACKED,
                svc.ackFinalized(contentDb, getContent("ack-ok")));

        assertEquals(AclEpochState.RECONCILE_ENQUEUED, props("ack-ok").get(AclEpochState.FIELD_STATE));
        var t = taskFor("ack-ok");
        assertNotNull(t, "the ACK must leave a durable reconciliation task");
        assertTrue(t.getMinRequiredEpoch() >= 42L,
                "the task must carry the finalized epoch as its obligation, got " + t.getMinRequiredEpoch());
    }

    /**
     * If the obligation cannot be established the marker must NOT advance. Otherwise the outbox is
     * cleared for work nobody will do, and the scanner counts the document as enqueued for ever.
     */
    @Test
    void aFailedObligationLEAVESTheMarkerAtFINALIZED() {
        seedFinalized("ack-fail", AclEpochState.newMutationId(), 7L);

        AclEpochFinalizationService failing = new AclEpochFinalizationService();
        failing.setConnectorPool(pool);
        failing.setCounterService(counter);
        failing.setReconciliationService(new jp.aegif.nemaki.reconcile.SearchIndexReconciliationService() {
            @Override public void enqueueOrThrow(String r, String o, String reason, String op, long epoch) {
                throw new IllegalStateException("injected: queue unavailable");
            }
        });

        assertThrows(IllegalStateException.class, () -> failing.ackFinalized(contentDb, getContent("ack-fail")));
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("ack-fail").get(AclEpochState.FIELD_STATE),
                "the marker must be RETAINED so the next scan retries");
    }

    /** A document re-mutated since it was read is not ours to advance. */
    @Test
    void aSupersededDocumentIsABANDONED_notClobbered() {
        seedFinalized("ack-sup", AclEpochState.newMutationId(), 5L);
        Document stale = getContent("ack-sup");
        seedPending("ack-sup", AclEpochState.newMutationId());   // a NEW mutation lands

        assertEquals(AclEpochFinalizationService.AckResult.ABANDONED,
                svc.ackFinalized(contentDb, stale));
        assertEquals(AclEpochState.PENDING_EPOCH, props("ack-sup").get(AclEpochState.FIELD_STATE),
                "the new mutation's marker must survive");
    }

    /**
     * THE CRASH WINDOW. A crash between "obligation durable" and "marker advanced" must be
     * recoverable, and it must recover in the SAFE direction: the task is already there, so the next
     * scan re-enqueues idempotently (deterministic id + monotonic max) and re-attempts the CAS.
     *
     * <p>Injected deterministically by failing the marker CAS exactly once.
     */
    @Test
    void aCrashBetweenTheObligationAndTheMarkerRecoversOnTheNextScan() {
        seedFinalized("ack-crash", AclEpochState.newMutationId(), 11L);

        java.util.concurrent.atomic.AtomicBoolean crashed = new java.util.concurrent.atomic.AtomicBoolean(false);
        AclEpochFinalizationService crashing = new AclEpochFinalizationService() {
            @Override String putBack(String repositoryId, Document doc) {
                if (crashed.compareAndSet(false, true)) {
                    throw new IllegalStateException("injected crash after the obligation was durable");
                }
                return super.putBack(repositoryId, doc);
            }
        };
        crashing.setConnectorPool(pool);
        crashing.setCounterService(counter);
        crashing.setReconciliationService(reconcileSvc);

        assertThrows(IllegalStateException.class, () -> crashing.ackFinalized(contentDb, getContent("ack-crash")));

        // The obligation IS durable, and the marker did NOT advance — the safe direction.
        assertNotNull(taskFor("ack-crash"), "the obligation must survive the crash");
        assertEquals(11L, taskFor("ack-crash").getMinRequiredEpoch());
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("ack-crash").get(AclEpochState.FIELD_STATE));

        // The next scan completes it. Re-enqueue is idempotent: the obligation cannot go down.
        ScanSummary sum = svc.scan(contentDb, 100);
        assertTrue(sum.acked >= 1, "the next scan must complete the ACK");
        assertEquals(AclEpochState.RECONCILE_ENQUEUED, props("ack-crash").get(AclEpochState.FIELD_STATE));
        assertEquals(11L, taskFor("ack-crash").getMinRequiredEpoch(), "re-enqueue must not LOWER it");
    }

    /**
     * A mis-wired deployment must FAIL, not silently skip the ACK. Skipping would leave every
     * FINALIZED document parked while the scan reported success — the exact invisibility wiring
     * gate 1 exists to remove. (Added after a mutation showed that turning the guard into a skip
     * left the whole suite green.)
     */
    @Test
    void aMissingReconciliationServiceIsAWIRINGFAULT_notASilentSkip() {
        seedFinalized("ack-unwired", AclEpochState.newMutationId(), 2L);

        AclEpochFinalizationService unwired = new AclEpochFinalizationService();
        unwired.setConnectorPool(pool);
        unwired.setCounterService(counter);
        // deliberately no setReconciliationService

        assertThrows(AclEpochWiringException.class,
                () -> unwired.ackFinalized(contentDb, getContent("ack-unwired")));
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE,
                props("ack-unwired").get(AclEpochState.FIELD_STATE), "and the marker is untouched");
    }

    /**
     * The CAS-loop's own supersede check, reached only when the document changes AFTER the
     * obligation was established. The early check before the enqueue catches the common case, so a
     * mutation removing the in-loop check stayed green until this test existed.
     *
     * <p>The re-mutation is injected between the enqueue and the CAS by overriding the enqueue.
     */
    @Test
    void aReMutationBETWEENTheObligationAndTheCasIsABANDONED() {
        String mid = AclEpochState.newMutationId();
        seedFinalized("ack-race", mid, 8L);

        AclEpochFinalizationService racing = new AclEpochFinalizationService();
        racing.setConnectorPool(pool);
        racing.setCounterService(counter);
        racing.setReconciliationService(new jp.aegif.nemaki.reconcile.SearchIndexReconciliationService() {
            @Override public void enqueueOrThrow(String r, String o, String reason, String op, long epoch) {
                reconcileSvc.enqueueOrThrow(r, o, reason, op, epoch);   // do the real thing...
                seedPending("ack-race", AclEpochState.newMutationId()); // ...then race a new mutation in
            }
        });

        assertEquals(AclEpochFinalizationService.AckResult.ABANDONED,
                racing.ackFinalized(contentDb, getContent("ack-race")));
        assertEquals(AclEpochState.PENDING_EPOCH, props("ack-race").get(AclEpochState.FIELD_STATE),
                "the NEW mutation's marker must survive — the ACK belonged to the old one");
    }

    // ── §5.1 quarantine operational contract (wiring gate 4) ───────

    /**
     * The contract's core claim, end to end: quarantine → BLOCKED → repair → the SAME retained task
     * completes, with no manual re-enqueue (§5.1 items 1, 4 and 5).
     */
    @Test
    void quarantineBlocksThenRepairLetsTheSAMETaskComplete() {
        seedFolder("q-root", null, false, 4L);
        seedDocument("q-leaf", "q-root", true, 1L);
        quarantine("q-root");   // the ANCESTOR is the blocker

        // BLOCKED — and the failure names the ancestor, not just the object.
        AclEpochQuarantineBlockedException blocked = assertThrows(
                AclEpochQuarantineBlockedException.class, () -> effectiveSvc.snapshot(contentDb, "q-leaf"));
        assertEquals("q-root", blocked.getQuarantinedId(), "the BLOCKER must be identified");
        assertEquals("q-leaf", blocked.getBlockedObjectId());

        // REPAIR — one CAS.
        assertEquals(AclEpochFinalizationService.RepairResult.REPAIRED,
                svc.repairQuarantined(contentDb, "q-root"));

        // RESUMES on its own: the same walk now succeeds, so a retained task's next attempt does too.
        AclEffectiveEpochService.Snapshot snap = effectiveSvc.snapshot(contentDb, "q-leaf");
        assertNotNull(snap);
        assertEquals(4L, snap.effectiveEpoch, "the repaired ancestor's epoch is usable again");
    }

    /**
     * §5.1 item 4: the marker and the epoch fields move in ONE CAS. Clearing the marker first would
     * expose the still-anomalous document to a scanner pass, which would re-quarantine it
     * immediately — a repair that undoes itself.
     */
    @Test
    void repairClearsTheMarkerANDNormalizesTheFieldsTogether() {
        seedFinalized("q-one", AclEpochState.newMutationId(), 6L);
        quarantine("q-one");

        assertEquals(AclEpochFinalizationService.RepairResult.REPAIRED,
                svc.repairQuarantined(contentDb, "q-one"));

        Map<String, Object> p = props("q-one");
        assertFalse(p.containsKey(AclEpochState.FIELD_QUARANTINED), "marker gone");
        assertFalse(p.containsKey(AclEpochState.FIELD_STATE), "state gone in the SAME step");
        assertFalse(p.containsKey(AclEpochState.FIELD_MUTATION_ID), "mutation id gone in the SAME step");
        assertEquals(6L, ((Number) p.get(AclEpochState.FIELD_SOURCE_EPOCH)).longValue(),
                "a VALID epoch a mutation paid for is preserved, not discarded");

        // A scan now sees ordinary settled content — it must NOT re-quarantine it.
        ScanSummary sum = svc.scan(contentDb, 100);
        assertTrue(sum.errors.isEmpty(), "a repaired document must not look corrupt: " + sum.errors);
        assertFalse(props("q-one").containsKey(AclEpochState.FIELD_QUARANTINED));
    }

    /** A CORRUPT epoch is dropped rather than guessed at; absent reads as 0 (pre-migration). */
    @Test
    void repairDROPSACorruptEpochRatherThanInventingOne() {
        seedRaw("q-bad", "{\"type\":\"cmis:document\",\"aclInherited\":false,"
                + "\"aclSourceEpoch\":\"not-a-number\",\"aclEpochQuarantined\":true}");

        assertEquals(AclEpochFinalizationService.RepairResult.REPAIRED,
                svc.repairQuarantined(contentDb, "q-bad"));
        assertFalse(props("q-bad").containsKey(AclEpochState.FIELD_SOURCE_EPOCH),
                "a corrupt epoch is REMOVED — the walk then treats it as 0, which is safe; a guessed "
                        + "value could fence out a later correct writer");
    }

    /** §5.1 item 2: the blocking ancestor is counted and logged ONCE, not once per descendant. */
    @Test
    void theBlockingAncestorIsReportedOnceNotPerDescendant() {
        seedFolder("q-root2", null, false, 3L);
        for (int i = 0; i < 5; i++) {
            seedDocument("q-child" + i, "q-root2", true, 1L);
        }
        quarantine("q-root2");

        for (int i = 0; i < 5; i++) {
            final int n = i;
            assertThrows(AclEpochQuarantineBlockedException.class,
                    () -> effectiveSvc.snapshot(contentDb, "q-child" + n));
        }

        Map<String, Object> m = effectiveSvc.quarantineMetrics();
        assertEquals(5L, ((Number) m.get("quarantineBlockedTasks")).longValue(),
                "every blocked walk is COUNTED");
        assertEquals(List.of("q-root2"), m.get("quarantineBlockingIds"),
                "but the operator sees ONE id — the document whose repair unblocks all five");
    }

    @Test
    void repairingSomethingNotQuarantinedIsANoOp() {
        seedFinalized("q-clean", AclEpochState.newMutationId(), 2L);
        assertEquals(AclEpochFinalizationService.RepairResult.NOT_QUARANTINED,
                svc.repairQuarantined(contentDb, "q-clean"));
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("q-clean").get(AclEpochState.FIELD_STATE));
    }

    /** PUT an EXACT raw JSON body, so a malformed epoch survives the model layer. */
    private void seedRaw(String id, String json) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL",
                            "http://localhost:5984") + "/" + contentDb + "/" + id))
                    .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                            (cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin") + ":"
                                    + cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD",
                                    "password")).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build();
            var resp = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertTrue(resp.statusCode() < 300, "seedRaw " + id + " failed: " + resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("seedRaw failed for " + id, e);
        }
    }

    /** Mark a document quarantined, as the scanner's durable quarantine does. */
    private void quarantine(String id) {
        Document d = getContent(id);
        Map<String, Object> p = d.getProperties();
        p.put(AclEpochState.FIELD_QUARANTINED, Boolean.TRUE);
        d.setProperties(p);
        cloudant.putDocument(new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                .db(contentDb).docId(id).document(d).build()).execute();
    }

    // ── the outbox terminus (capability; NOT wired — gate 1 does not require it) ──

    @Test
    void theTerminusClearsBOTHMarkerFieldsInOneStep() {
        String mid = AclEpochState.newMutationId();
        seedFinalized("term-ok", mid, 3L);
        svc.ackFinalized(contentDb, getContent("term-ok"));

        assertEquals(AclEpochFinalizationService.ClearResult.CLEARED,
                svc.clearMarkerAfterReconcile(contentDb, "term-ok", mid));

        Map<String, Object> p = props("term-ok");
        assertFalse(p.containsKey(AclEpochState.FIELD_STATE), "state must be gone");
        assertFalse(p.containsKey(AclEpochState.FIELD_MUTATION_ID), "mutation id must be gone TOO — a "
                + "half-cleared document is an anomaly to the scanner");
        assertTrue(p.containsKey(AclEpochState.FIELD_SOURCE_EPOCH), "the settled epoch stays");

        // And a scan sees ordinary settled content: no pass matches it, nothing is quarantined.
        ScanSummary sum = svc.scan(contentDb, 100);
        assertTrue(sum.errors.isEmpty(), "a cleared document must not look corrupt: " + sum.errors);
        assertFalse(props("term-ok").containsKey(AclEpochState.FIELD_QUARANTINED));
    }

    /** The clear belongs to the obligation that completed, not to whatever marker is present now. */
    @Test
    void theTerminusRefusesWhenTheDocumentWasReMutated() {
        String oldMid = AclEpochState.newMutationId();
        seedFinalized("term-sup", oldMid, 3L);
        svc.ackFinalized(contentDb, getContent("term-sup"));
        seedPending("term-sup", AclEpochState.newMutationId());   // re-mutated

        assertEquals(AclEpochFinalizationService.ClearResult.ABANDONED,
                svc.clearMarkerAfterReconcile(contentDb, "term-sup", oldMid));
        assertEquals(AclEpochState.PENDING_EPOCH, props("term-sup").get(AclEpochState.FIELD_STATE));
    }

    private void seedPending(String id, String mutationId) {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, mutationId);
        putContentRaw(id, p);
    }

    private void seedPendingNoMutationId(String id) {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH); // deliberately no mutation id
        putContentRaw(id, p);
    }

    /** PENDING with an arbitrary raw mutation-id value (JSON null / non-String / blank / …). */
    private void seedPendingRawMutationId(String id, Object rawMutationId) {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, rawMutationId); // may be null (JSON null / omitted by the SDK)
        putContentRaw(id, p);
    }

    /** A valid PENDING (UUID mutation id) carrying a non-true quarantine marker value. */
    private void seedPendingWithMarker(String id, Object markerValue) {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, AclEpochState.newMutationId());
        p.put(AclEpochState.FIELD_QUARANTINED, markerValue); // false / "false" / 0 / …
        putContentRaw(id, p);
    }

    private void seedFinalized(String id, String mutationId, long epoch) {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        p.put(AclEpochState.FIELD_MUTATION_ID, mutationId);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        putContentRaw(id, p);
    }

    /** A folder for the §5.1 walk tests: settled content carrying only its epoch. */
    private void seedFolder(String id, String parentId, boolean inherits, long epoch) {
        Map<String, Object> p = baseFixture();
        p.put("type", "cmis:folder");
        p.put("name", id);
        if (parentId != null) p.put("parentId", parentId);
        p.put("aclInherited", inherits);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        p.remove(AclEpochState.FIELD_STATE);
        p.remove(AclEpochState.FIELD_MUTATION_ID);
        putContentRaw(id, p);
    }

    private void seedDocument(String id, String parentId, boolean inherits, long epoch) {
        Map<String, Object> p = baseFixture();
        p.put("type", "cmis:document");
        p.put("name", id);
        if (parentId != null) p.put("parentId", parentId);
        p.put("aclInherited", inherits);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, epoch);
        p.remove(AclEpochState.FIELD_STATE);
        p.remove(AclEpochState.FIELD_MUTATION_ID);
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

    private long counterValue(String repositoryId) {
        Document d = cloudant.getDocument(new GetDocumentOptions.Builder()
                .db(SystemConst.NEMAKI_CONF_DB).docId(AclEpochCounterService.counterDocId(repositoryId)).build())
                .execute().getResult();
        return ((Number) d.getProperties().get("value")).longValue();
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

    /** Seed the terminal-audit resume cursor with a given (possibly-garbage) bookmark. */
    private void seedAuditCursor(String bookmark) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "aclEpochAuditCursor");
        p.put("schemaVersion", 1);
        p.put("terminalBookmark", bookmark);
        putContentRaw(CURSOR_ID, p);
    }

    /** Overwrite the persisted resume bookmark with a garbage value (simulate an expired bookmark). */
    private void corruptAuditCursorBookmark() {
        Document c = getContent(CURSOR_ID);
        Map<String, Object> p = c.getProperties();
        p.put("terminalBookmark", "garbage-expired-bookmark");
        c.setProperties(p);
        putContent(c);
    }

    /**
     * Drop and recreate the epoch Mango indexes (simulate an index rebuild). Deleting the design
     * document removes BOTH indexes (they share {@code acl-epoch-indexes}), so both are recreated —
     * otherwise the scan's fail-closed index pre-flight would (correctly) refuse to run.
     */
    private void recreateAclEpochStateIndex() throws Exception {
        HttpResponse<String> g = rawRequest("GET", "/_design/acl-epoch-indexes", null);
        if (g.statusCode() == 200) {
            String rev = ObjectMapperFactory.createDefaultObjectMapper().readTree(g.body()).path("_rev").asText();
            rawRequest("DELETE", "/_design/acl-epoch-indexes?rev=" + rev, null);
        }
        cloudant.postIndex(new PostIndexOptions.Builder()
                .db(contentDb)
                .index(new IndexDefinition.Builder()
                        .fields(List.of(new IndexField.Builder().add(AclEpochState.FIELD_STATE, "asc").build()))
                        .build())
                .name("idx_aclEpochState").type(PostIndexOptions.Type.JSON).ddoc("acl-epoch-indexes")
                .build()).execute();
        cloudant.postIndex(new PostIndexOptions.Builder()
                .db(contentDb)
                .index(new IndexDefinition.Builder()
                        .fields(List.of(new IndexField.Builder()
                                .add(AclEpochState.FIELD_MUTATION_ID, "asc").build()))
                        .build())
                .name("idx_aclEpochMutationId").type(PostIndexOptions.Type.JSON).ddoc("acl-epoch-indexes")
                .build()).execute();
    }

    private HttpResponse<String> rawRequest(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + contentDb + path))
                .header("Authorization", basicAuth)
                .header("Content-Type", "application/json");
        if ("DELETE".equals(method)) b.DELETE();
        else if (body != null) b.method(method, HttpRequest.BodyPublishers.ofString(body));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Create a single-field JSON index with an explicit ddoc / name / field / direction. */
    private void createJsonIndex(String ddoc, String name, String field, String direction) throws Exception {
        postIndexRaw("{\"index\":{\"fields\":[{\"" + field + "\":\"" + direction + "\"}]},"
                + "\"name\":\"" + name + "\",\"type\":\"json\",\"ddoc\":\"" + ddoc + "\"}");
    }

    /** Create a PARTIAL JSON index (silently omits documents — must fail the pre-flight). */
    private void createPartialJsonIndex(String ddoc, String name, String field) throws Exception {
        postIndexRaw("{\"index\":{\"fields\":[{\"" + field + "\":\"asc\"}],"
                + "\"partial_filter_selector\":{\"" + field + "\":{\"$exists\":true}}},"
                + "\"name\":\"" + name + "\",\"type\":\"json\",\"ddoc\":\"" + ddoc + "\"}");
    }

    /** Create a TWO-field JSON index under the expected name (a different index than required). */
    private void createTwoFieldJsonIndex(String ddoc, String name, String f1, String f2) throws Exception {
        postIndexRaw("{\"index\":{\"fields\":[{\"" + f1 + "\":\"asc\"},{\"" + f2 + "\":\"asc\"}]},"
                + "\"name\":\"" + name + "\",\"type\":\"json\",\"ddoc\":\"" + ddoc + "\"}");
    }

    private void postIndexRaw(String body) throws Exception {
        HttpResponse<String> resp = rawRequest("POST", "/_index", body);
        if (resp.statusCode() >= 300) throw new IllegalStateException("create index failed: " + resp.body());
    }

    /** Delete one Mango index from the shared design doc (to prove the fail-closed pinning). */
    private void dropIndex(String indexName) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + contentDb + "/_index/_design/acl-epoch-indexes/json/" + indexName))
                .header("Authorization", basicAuth)
                .DELETE()
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) throw new IllegalStateException("drop index failed: " + resp.body());
    }

    /** PUT a document with an EXACT raw JSON body (to guarantee explicit JSON null / shape). */
    private void putRawJson(String id, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + contentDb + "/" + id))
                .header("Authorization", basicAuth)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) throw new IllegalStateException("raw put failed: " + resp.body());
    }

    private static String cfg(String sysProp, String envVar, String dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
