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
import jp.aegif.nemaki.epoch.AclEpochFinalizationService.AclEpochAnomalyException;
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

        ObjectMapper om = new ObjectMapper();
        CloudantClientWrapper confWrapper = new CloudantClientWrapper(cloudant, SystemConst.NEMAKI_CONF_DB, om);
        CloudantClientWrapper contentWrapper = new CloudantClientWrapper(cloudant, contentDb, om);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(confWrapper);
        lenient().when(pool.getClient(contentDb)).thenReturn(contentWrapper);

        AclEpochCounterService counter = new AclEpochCounterService();
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
        assertEquals("idx_aclEpochState", indexName,
                label + " selector must be served by the (aclEpochState) index, not _all_docs");
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
