package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
 * Mango index so the scanner selectors are index-served, not an {@code _all_docs} fallback;
 * dropped in {@code @AfterEach}) with its own seeded ACL-epoch counter. Gated like the
 * other CouchDB ITs.
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
        // Create the SAME (aclEpochState) index the production patch creates, so scan is
        // index-served rather than passing only because of an _all_docs fallback.
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
        seedPending("d1", "m-1");
        FinalizeOutcome o = svc.finalizePending(contentDb, "d1");
        assertEquals(FinalizeResult.FINALIZED, o.result);
        assertEquals(1L, o.epoch.longValue());

        Map<String, Object> p = props("d1");
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, p.get(AclEpochState.FIELD_STATE));
        assertEquals(1L, ((Number) p.get(AclEpochState.FIELD_SOURCE_EPOCH)).longValue());
        assertEquals("m-1", p.get(AclEpochState.FIELD_MUTATION_ID));
        assertEquals("keep-me", p.get("name"), "other content fields preserved");
    }

    @Test
    void finalizePreservesInlineAttachment() {
        // A committed content doc with a real inline attachment. Finalize (which re-reads
        // through getDoc so it has the _attachments stubs, even when driven by a _find hint)
        // must NOT drop the attachment.
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, "m-att");
        Document d = new Document();
        d.setId("d-att");
        d.setProperties(p);
        Attachment att = new Attachment.Builder()
                .contentType("text/plain")
                .data("aGVsbG8=".getBytes()) // base64 for "hello"
                .build();
        Map<String, Attachment> atts = new LinkedHashMap<>();
        atts.put("note.txt", att);
        d.setAttachments(atts);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(contentDb).docId("d-att").document(d).build()).execute();

        // Drive via the DOCUMENT overload with a _find-style hint (no _attachments) to prove
        // the finalize itself re-reads and preserves attachments.
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
        seedFinalized("d2", "m-2", 7L);
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
        // A hand-built PENDING document with NO _rev (never committed). Phase 2 must refuse
        // it BEFORE allocating an epoch — otherwise it could PUT itself as a NEW document.
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        p.put(AclEpochState.FIELD_MUTATION_ID, "m-ghost");
        Document ghost = new Document();
        ghost.setId("ghost-1"); // id set, but no _rev and never persisted
        ghost.setProperties(p);

        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, ghost));
        // It must NOT have created the document.
        assertNull(revOf(contentDb, "ghost-1"), "a rev-less finalize must not create a new document");
        // And it must NOT have consumed an epoch (counter still at 0).
        assertEquals(0L, counterValue(contentDb), "no epoch allocated for a rejected rev-less finalize");
    }

    @Test
    void finalizeAbandonsWhenMutationIdChangedUnderneath() {
        seedPending("d4", "m-A");
        Document stale = getContent("d4"); // owns m-A
        // A newer Phase-1 supersedes with m-B (still PENDING).
        Document fresh = getContent("d4");
        Map<String, Object> fp = fresh.getProperties();
        fp.put(AclEpochState.FIELD_MUTATION_ID, "m-B");
        fresh.setProperties(fp);
        putContent(fresh);

        FinalizeOutcome o = svc.finalizePending(contentDb, stale);
        assertEquals(FinalizeResult.ABANDONED_SUPERSEDED, o.result);
        Map<String, Object> p = props("d4");
        assertEquals(AclEpochState.PENDING_EPOCH, p.get(AclEpochState.FIELD_STATE));
        assertEquals("m-B", p.get(AclEpochState.FIELD_MUTATION_ID));
        assertFalse(p.containsKey(AclEpochState.FIELD_SOURCE_EPOCH));
    }

    @Test
    void finalizeThrowsAnomalyWhenReReadIsCorruptNotSuperseded() {
        // Owned hint says PENDING m-C; the live doc is corrupted to an UNKNOWN state before
        // finalize reads it. A corrupt live state must be an ANOMALY, not a silent
        // ABANDONED_SUPERSEDED (review 2a #4).
        seedPending("d6", "m-C");
        Document stale = getContent("d6");
        Document corrupt = getContent("d6");
        Map<String, Object> cp = corrupt.getProperties();
        cp.put(AclEpochState.FIELD_STATE, "WAT_UNKNOWN");
        corrupt.setProperties(cp);
        putContent(corrupt);

        assertThrows(AclEpochAnomalyException.class, () -> svc.finalizePending(contentDb, stale));
    }

    @Test
    void concurrentFinalizeExactlyOneWins() throws Exception {
        seedPending("d5", "m-5");
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
                    workerErrors.add(t); // do NOT swallow — a loser must ABANDON cleanly, not throw
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

    // ── scan priority / anomaly visibility ─────────────────────────

    @Test
    void scanFinalizesPendingCountsFinalizedAndStopsAtFinalized() {
        seedPending("s-pending", "m-p");
        seedFinalized("s-final", "m-f", 3L);
        seedStateless("s-plain");

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(1, sum.finalized, "only the PENDING doc is finalized this scan");
        // The PENDING-first pass finalizes s-pending → it is now FINALIZED too, so the
        // FINALIZED pass counts BOTH the pre-existing s-final and the just-finalized
        // s-pending. (awaitingReconcile is an informational count of FINALIZED docs.)
        assertEquals(2, sum.awaitingReconcile);
        assertTrue(sum.errors.isEmpty());
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("s-final").get(AclEpochState.FIELD_STATE));
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("s-pending").get(AclEpochState.FIELD_STATE));
    }

    @Test
    void pendingIsNotStarvedByAFinalizedBacklogOverTheCap() {
        // The review's real-CouchDB observation: FINALIZED sorts ahead of PENDING. With a
        // small cap and MORE finalized docs than the cap plus a single pending doc, the
        // PENDING-first pass must still finalize the pending one (never starved).
        int cap = 5;
        for (int i = 0; i < cap + 3; i++) {
            seedFinalized("f-" + i, "mf-" + i, 10 + i); // > cap finalized docs
        }
        seedPending("the-pending", "mp-1");

        ScanSummary sum = svc.scan(contentDb, cap);
        assertEquals(1, sum.finalized, "the single PENDING doc is finalized despite the FINALIZED backlog");
        assertEquals(AclEpochState.FINALIZED_NEEDS_RECONCILE, props("the-pending").get(AclEpochState.FIELD_STATE));
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
    void scanRecordsUnknownStateAsAnomaly() {
        // An UNKNOWN state is OUTSIDE the $in selector; the bounded audit pass must still
        // surface it (review 2a #2) rather than leaving it forever invisible.
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, "GARBAGE_STATE");
        p.put(AclEpochState.FIELD_MUTATION_ID, "m-u");
        putContentRaw("bad-unknown", p);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(1, sum.errors.size());
        assertEquals("bad-unknown", sum.errors.get(0).get("docId"));
    }

    @Test
    void scanRecordsPendingWithoutMutationIdAsAnomalyAndRetains() {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH); // no mutation id
        putContentRaw("bad-pending", p);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(0, sum.finalized);
        assertEquals(1, sum.errors.size());
        assertEquals("bad-pending", sum.errors.get(0).get("docId"));
        assertEquals(AclEpochState.PENDING_EPOCH, props("bad-pending").get(AclEpochState.FIELD_STATE),
                "an anomalous doc is left unprocessed, not finalized");
    }

    @Test
    void scanRecordsFinalizedWithoutMutationIdAsAnomaly() {
        // FINALIZED with an epoch but NO mutation id must be an anomaly, not a clean
        // awaitingReconcile (review 2a #2).
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, 5L); // valid epoch, but...
        // no aclEpochMutationId
        putContentRaw("bad-final-nomut", p);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(0, sum.awaitingReconcile, "a FINALIZED doc without a mutation id is not clean");
        assertEquals(1, sum.errors.size());
        assertEquals("bad-final-nomut", sum.errors.get(0).get("docId"));
    }

    @Test
    void scanRecordsFinalizedWithInvalidEpochAsAnomaly() {
        Map<String, Object> p = baseFixture();
        p.put(AclEpochState.FIELD_STATE, AclEpochState.FINALIZED_NEEDS_RECONCILE);
        p.put(AclEpochState.FIELD_MUTATION_ID, "m-x");
        p.put(AclEpochState.FIELD_SOURCE_EPOCH, 1.5d);
        putContentRaw("bad-final-epoch", p);

        ScanSummary sum = svc.scan(contentDb, 100);
        assertEquals(1, sum.errors.size());
        assertEquals("bad-final-epoch", sum.errors.get(0).get("docId"));
        assertEquals(0, sum.awaitingReconcile);
    }

    // ── index is actually used (not an _all_docs fallback) ─────────

    @Test
    void pendingSelectorUsesTheAclEpochStateIndex() throws Exception {
        // Raw _explain (the SDK's typed ExplainResult mis-deserializes opts.fields).
        ObjectMapper om = new ObjectMapper();
        String body = om.writeValueAsString(Map.of("selector",
                Map.of(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH)));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + contentDb + "/_explain"))
                .header("Authorization", basicAuth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "_explain call failed: " + resp.body());
        JsonNode root = om.readTree(resp.body());
        String indexName = root.path("index").path("name").asText(null);
        assertEquals("idx_aclEpochState", indexName,
                "the PENDING selector must be served by the (aclEpochState) index, not a full _all_docs scan");
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

    private static String cfg(String sysProp, String envVar, String dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
