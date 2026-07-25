package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        ObjectMapper om = new ObjectMapper();
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
        assertEquals(2, sum.awaitingReconcile, "pre-existing FINALIZED + the one just finalized");
        assertTrue(sum.errors.isEmpty());
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("s-final").get(AclEpochState.FIELD_STATE));
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("s-pending").get(AclEpochState.FIELD_STATE));
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
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE,
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
        assertTrue(sum.awaitingReconcile >= 1, "FINALIZED pass progressed");
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

        int scans = scanUntil(() -> AclEpochState.FINALIZED_NEEDS_RECONCILE
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

        int scans = scanUntil(() -> AclEpochState.FINALIZED_NEEDS_RECONCILE
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
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("valid-final").get(AclEpochState.FIELD_STATE));
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
        ObjectMapper om = new ObjectMapper();
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
        assertTrue(sum.awaitingReconcile >= 1, "the terminal audit runs with a valid cursor");
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
            String rev = new ObjectMapper().readTree(g.body()).path("_rev").asText();
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
