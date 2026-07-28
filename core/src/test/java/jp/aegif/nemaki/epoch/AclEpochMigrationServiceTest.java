package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;

/**
 * Unit tests for the repository-wide initial-epoch stamp (wiring gate 2, increment 10).
 *
 * <p>Solr and the writer are mocked so the parts that are easy to get quietly wrong — WHICH
 * documents are visited, which are skipped without a walk, and how each outcome is classified —
 * are pinned without a live stack. The live run is the IT.
 */
public class AclEpochMigrationServiceTest {

    private AclEpochMigrationService svc;
    private AclEpochIndexWriter writer;
    private SolrClient solrClient;
    /** Pages handed back in order, so a multi-page cursor walk is exercised. */
    private final ConcurrentLinkedQueue<SolrDocumentList> pages = new ConcurrentLinkedQueue<>();
    private final List<SolrQuery> issued = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        writer = mock(AclEpochIndexWriter.class);
        solrClient = mock(SolrClient.class);
        SolrUtil solrUtil = mock(SolrUtil.class);
        lenient().when(solrUtil.getSolrClient()).thenReturn(solrClient);

        svc = new AclEpochMigrationService();
        svc.setIndexWriter(writer);
        svc.setSolrUtil(solrUtil);
        svc.setPrincipalService(mock(PrincipalService.class));
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repos =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        lenient().when(repos.contains("bedroom")).thenReturn(true);
        lenient().when(repos.contains("canopy")).thenReturn(true);
        lenient().when(repos.keys()).thenReturn(java.util.Set.of("bedroom", "canopy"));
        svc.setRepositoryInfoMap(repos);

        lenient().when(solrClient.query(any(SolrQuery.class))).thenAnswer(inv -> {
            SolrQuery q = inv.getArgument(0);
            issued.add(q);
            SolrDocumentList docs = pages.poll();
            if (docs == null) docs = new SolrDocumentList();
            QueryResponse resp = mock(QueryResponse.class);
            lenient().when(resp.getResults()).thenReturn(docs);
            // Distinct marks while pages remain; repeating the input mark is Solr's own
            // end-of-iteration signal.
            String cur = q.get("cursorMark");
            lenient().when(resp.getNextCursorMark()).thenReturn(pages.isEmpty() ? cur : cur + "+");
            return resp;
        });
    }

    private static SolrDocument doc(String id, Long epoch) {
        SolrDocument d = new SolrDocument();
        d.setField("id", id);
        if (epoch != null) d.setField("effective_acl_epoch", epoch);
        return d;
    }

    private static SolrDocumentList page(SolrDocument... docs) {
        SolrDocumentList l = new SolrDocumentList();
        for (SolrDocument d : docs) l.add(d);
        return l;
    }

    private static AclEpochIndexWriter.WriteOutcome outcome(AclEpochIndexWriter.WriteResult r)
            throws Exception {
        // WriteOutcome's constructor is package-private to AclEpochIndexWriter's package, which this
        // test shares — construct it directly rather than mocking a value object.
        java.lang.reflect.Constructor<AclEpochIndexWriter.WriteOutcome> c =
                AclEpochIndexWriter.WriteOutcome.class.getDeclaredConstructor(
                        AclEpochIndexWriter.WriteResult.class, long.class, List.class, int.class);
        c.setAccessible(true);
        return c.newInstance(r, 1L, List.of("user:x"), 1);
    }

    private AclEpochMigrationService.Progress runToCompletion(String repo) throws Exception {
        assertTrue(svc.start(repo));
        AclEpochMigrationService.Progress p = svc.status(repo);
        for (int i = 0; i < 200 && "RUNNING".equals(p.status); i++) {
            Thread.sleep(25);
        }
        assertFalse("RUNNING".equals(p.status), "the run did not finish");
        return p;
    }

    /**
     * The RAG populations share the {@code nemaki} core and carry {@code readers}, but they are not
     * CMIS objects — their ids are not CouchDB object ids and their tokens belong to the RAG path.
     * Stamping them would have the epoch writer claim a field another writer rewrites. On the dev
     * stack they are the MAJORITY of the core (1147 chunks + 5 markers vs 305 CMIS objects), so
     * losing this filter is not a rounding error.
     */
    @Test
    public void theQueryVisitsCMISObjectsONLY_notTheRagPopulationsSharingTheCore() throws Exception {
        pages.add(page(doc("a", null)));
        when(writer.stampInitialEpoch(anyString(), anyString(), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));

        runToCompletion("bedroom");

        String fq = String.join(" ", issued.get(0).getFilterQueries());
        assertTrue(fq.contains("repository_id:\"bedroom\""), fq);
        assertTrue(fq.contains("-doc_type:[* TO *]"),
                "RAG chunks and rag: markers must be excluded by their OWN discriminator: " + fq);
    }

    /** A repository id is interpolated into a Solr query, so a quote must not break out of it. */
    @Test
    public void theRepositoryFilterQuotesTheId() {
        assertEquals("repository_id:\"a\\\"b\" AND -doc_type:[* TO *]",
                AclEpochMigrationService.cmisObjectFilter("a\"b"));
    }

    /**
     * An already-fenced document must be skipped from the QUERY's own field, without a walk. This is
     * what makes "just run it again" the entire recovery procedure: a re-run over a stamped
     * repository costs one Solr scan, not one authoritative ancestor walk per document.
     */
    @Test
    public void anAlreadyStampedDocumentIsSkippedWITHOUTAWalk() throws Exception {
        pages.add(page(doc("fresh", null), doc("done", 7L)));
        when(writer.stampInitialEpoch(anyString(), anyString(), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));

        AclEpochMigrationService.Progress p = runToCompletion("bedroom");

        assertEquals(2, p.scanned);
        assertEquals(1, p.stamped);
        assertEquals(1, p.alreadyStamped);
        verify(writer).stampInitialEpoch(eq("bedroom"), eq("fresh"), any(), any());
        verify(writer, never()).stampInitialEpoch(eq("bedroom"), eq("done"), any(), any());
    }

    /** cursorMark paging: every page is visited, and Solr's repeat-the-mark signal ends the loop. */
    @Test
    public void everyPageIsVisitedAndTheLoopTERMINATES() throws Exception {
        pages.add(page(doc("a", null), doc("b", null)));
        pages.add(page(doc("c", null)));
        when(writer.stampInitialEpoch(anyString(), anyString(), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));

        AclEpochMigrationService.Progress p = runToCompletion("bedroom");

        assertEquals(3, p.scanned);
        assertEquals(3, p.stamped);
        assertEquals("COMPLETED", p.status);
    }

    /**
     * Each outcome lands in its OWN counter. Collapsing them would be the difference between "310
     * documents are fenced" and "310 documents were looked at", and only the first licenses wiring.
     */
    @Test
    public void everyOutcomeIsCountedSEPARATELY() throws Exception {
        pages.add(page(doc("u", null), doc("d", null), doc("n", null), doc("i", null)));
        when(writer.stampInitialEpoch(anyString(), eq("u"), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));
        when(writer.stampInitialEpoch(anyString(), eq("d"), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.SKIPPED_DELETED));
        when(writer.stampInitialEpoch(anyString(), eq("n"), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.NOT_INDEXED));
        when(writer.stampInitialEpoch(anyString(), eq("i"), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.SKIPPED_IDEMPOTENT));

        AclEpochMigrationService.Progress p = runToCompletion("bedroom");

        assertEquals(1, p.stamped);
        assertEquals(1, p.skippedDeleted);
        assertEquals(1, p.notIndexed);
        assertEquals(1, p.alreadyStamped, "a concurrent stamp is 'already done', not a new category");
        assertEquals(0, p.failed);
    }

    /**
     * One bad document must not end the run — 300k documents behind it still need stamping — but it
     * must be COUNTED and its id RECORDED. A run that silently stepped over failures would report
     * COMPLETED while leaving documents unfenced, and the unfenced ones are exactly what makes
     * wiring the writer break every ACL update.
     */
    @Test
    public void aPerDocumentFailureIsCOUNTEDAndTheRunCONTINUES() throws Exception {
        pages.add(page(doc("bad", null), doc("good", null)));
        when(writer.stampInitialEpoch(anyString(), eq("bad"), any(), any()))
                .thenThrow(new RuntimeException("solr exploded"));
        when(writer.stampInitialEpoch(anyString(), eq("good"), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));

        AclEpochMigrationService.Progress p = runToCompletion("bedroom");

        assertEquals("COMPLETED", p.status);
        assertEquals(1, p.failed);
        assertEquals(1, p.stamped, "the document AFTER the failure must still be stamped");
        assertTrue(p.errors.stream().anyMatch(e -> e.startsWith("bad:")), p.errors.toString());
    }

    /**
     * A quarantine block gets its own counter AND the blocking id — one quarantined folder can
     * account for every failure in a run, so the actionable output is that handful of ids, not a
     * count. It is NOT a `failed`: retrying changes nothing until a human repairs the blocker.
     */
    @Test
    public void aQuarantineBlockRecordsTheBLOCKER_andIsNotCountedAsAFailure() throws Exception {
        pages.add(page(doc("x", null), doc("y", null)));
        when(writer.stampInitialEpoch(anyString(), anyString(), any(), any()))
                .thenThrow(new AclEpochQuarantineBlockedException("quarantined on anc", "anc-1"));

        AclEpochMigrationService.Progress p = runToCompletion("bedroom");

        assertEquals(2, p.quarantineBlocked);
        assertEquals(0, p.failed, "a quarantine block is not a failure — retrying cannot fix it");
        assertEquals(List.of("anc-1"), List.copyOf(p.quarantineBlockingIds),
                "two blocked documents, ONE id to repair");
    }

    /**
     * A missing bean is not a per-document problem: every remaining document would fail identically,
     * and burying that in a `failed` count would read as data corruption instead of a deployment
     * fault. It must end the run as FAILED.
     */
    @Test
    public void aWiringFaultFAILSTheRun_ratherThanCountingMillionsOfFailures() throws Exception {
        pages.add(page(doc("a", null), doc("b", null)));
        when(writer.stampInitialEpoch(anyString(), anyString(), any(), any()))
                .thenThrow(new AclEpochWiringException("effectiveEpochService not wired"));

        AclEpochMigrationService.Progress p = runToCompletion("bedroom");

        assertEquals("FAILED", p.status);
        assertEquals(0, p.failed);
        assertTrue(p.errorMessage.contains("not wired"), p.errorMessage);
    }

    /** A second start while one is in flight is refused: the counters are the point of the API. */
    @Test
    public void aSecondConcurrentRunIsREFUSED() throws Exception {
        pages.add(page(doc("a", null)));
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        when(writer.stampInitialEpoch(anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            hold.await();
            return outcome(AclEpochIndexWriter.WriteResult.UPDATED);
        });

        assertTrue(svc.start("bedroom"));
        for (int i = 0; i < 200 && svc.status("bedroom").scanned == 0; i++) Thread.sleep(10);
        assertFalse(svc.start("bedroom"), "a concurrent run would make the progress counters lie");
        assertTrue(svc.start("canopy"), "but a DIFFERENT repository is independent");
        hold.countDown();
    }

    /** Wiring is checked before anything is started, so the caller gets a 503 rather than a run. */
    @Test
    public void startRefusesWhenNotWired() {
        AclEpochMigrationService bare = new AclEpochMigrationService();
        assertThrows(AclEpochWiringException.class, () -> bare.start("bedroom"));
        assertThrows(IllegalArgumentException.class, () -> svc.start("  "));
    }

    /**
     * An UNKNOWN repository id must be refused, not quietly reported as a finished migration.
     *
     * <p>Without the guard the runner is a trap rather than a verification: a typo matches no Solr
     * document, so the run completes with {@code scanned: 0}, {@code remainingUnfenced} is 0, and the
     * endpoint answers {@code verdict: COMPLETE, fenced: true} for a repository it never touched.
     * Reproduced against the dev stack before the fix — an operator would have concluded gate 2 was
     * closed while the repository was entirely unfenced. This is the one failure direction the whole
     * endpoint exists to prevent.
     */
    @Test
    public void anUNKNOWNRepositoryIsRefused_notReportedAsAFinishedMigration() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.start("bedr00m-typo"));
        assertTrue(e.getMessage().contains("unknown repository"), e.getMessage());
        assertTrue(e.getMessage().contains("bedroom"), "and it must say what IS configured: " + e.getMessage());

        // The status path must refuse it too — otherwise a GET alone still answers "fenced".
        assertThrows(IllegalArgumentException.class, () -> svc.remainingUnfenced("bedr00m-typo"));
        assertNull(svc.status("bedr00m-typo"), "and no run may have been recorded for it");
    }

    /** Without the map the service must FAIL rather than accept every id as valid. */
    @Test
    public void aMissingRepositoryInfoMapIsAWiringFault_notAnOpenDoor() {
        AclEpochMigrationService noMap = new AclEpochMigrationService();
        noMap.setIndexWriter(writer);
        noMap.setSolrUtil(mock(SolrUtil.class));
        noMap.setPrincipalService(mock(PrincipalService.class));
        AclEpochWiringException e = assertThrows(AclEpochWiringException.class,
                () -> noMap.start("bedroom"));
        assertTrue(e.getMessage().contains("repositoryInfoMap"), e.getMessage());
    }

    /**
     * A rejected submission must not leave the entry RUNNING: {@code start} would then answer
     * "already running" for ever, so the endpoint would be dead until a JVM restart with no run to
     * observe — the worst of both (no work done, and no way to ask for it).
     */
    @Test
    public void aRejectedSubmissionDoesNotWEDGETheRepositoryAtRUNNING() {
        svc.stop(); // executor shut down; every submit is now rejected
        assertThrows(RuntimeException.class, () -> svc.start("bedroom"));

        AclEpochMigrationService.Progress p = svc.status("bedroom");
        assertNotNull(p);
        assertEquals("FAILED", p.status, "it must be observable as FAILED, not stuck RUNNING");
        assertTrue(p.errorMessage.contains("could not start"), p.errorMessage);
    }

    /**
     * The gate criterion is NOT "remainingUnfenced == 0". A Solr entry whose CouchDB content has
     * been DELETED can never be stamped, so that count never reaches zero on any index that has
     * ever lost a document — and reporting such a repository as never-migrated would block the
     * wiring decision on something that cannot block it: an orphan can never be the target of an
     * ACL write either. Found on the dev stack, where 35 of 304 bedroom documents were orphaned
     * relationships (CouchDB 404) and the endpoint reported `fenced: false` for ever.
     */
    @Test
    public void anOrphanedIndexEntryDoesNotMakeTheMigrationLookUNFINISHED() throws Exception {
        pages.add(page(doc("live", null), doc("orphan", null)));
        when(writer.stampInitialEpoch(anyString(), eq("live"), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));
        when(writer.stampInitialEpoch(anyString(), eq("orphan"), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.SKIPPED_DELETED));

        AclEpochMigrationService.Progress p = runToCompletion("bedroom");
        assertEquals(1, p.stamped);
        assertEquals(1, p.skippedDeleted);

        assertEquals(AclEpochMigrationService.Verdict.COMPLETE_EXCEPT_ORPHANS,
                svc.verdict("bedroom", 1, 10), "1 left, and exactly 1 was content-less: complete");
        assertEquals(AclEpochMigrationService.Verdict.COMPLETE,
                svc.verdict("bedroom", 0, 10));
        assertEquals(AclEpochMigrationService.Verdict.INCOMPLETE,
                svc.verdict("bedroom", 2, 10),
                "MORE left than were content-less means real work was missed — re-run");
    }

    /**
     * A run with failures or quarantine blocks is never "complete", however the residual count
     * happens to line up: the whole point of separating those counters is that they mean re-run and
     * repair-then-re-run, not done.
     */
    @Test
    public void failuresAndQuarantineBlocksKeepTheVerdictINCOMPLETE() throws Exception {
        pages.add(page(doc("a", null)));
        when(writer.stampInitialEpoch(anyString(), anyString(), any(), any()))
                .thenThrow(new AclEpochQuarantineBlockedException("q", "anc-1"));

        AclEpochMigrationService.Progress p = runToCompletion("bedroom");
        assertEquals(1, p.quarantineBlocked);
        assertEquals(AclEpochMigrationService.Verdict.INCOMPLETE, svc.verdict("bedroom", 1, 10));
    }

    /** Before any run there is nothing to conclude — not "complete because Solr looks empty". */
    @Test
    public void aRepositoryNeverRunIsNOT_RUN_evenAtZeroRemaining() {
        assertEquals(AclEpochMigrationService.Verdict.NOT_RUN, svc.verdict("never-touched", 0, 10));
    }

    /**
     * An EMPTY index must not read as COMPLETE — this is the documented order mistake wearing a
     * different hat.
     *
     * <p>"Run the stamp AFTER the full reindex" is the one ordering rule of this endpoint, because a
     * later reindex discards the stamp. Get it backwards and the run visits an empty index:
     * {@code scanned: 0}, nothing fails, {@code remainingUnfenced: 0} — which the first version of
     * this logic reported as {@code COMPLETE, fenced: true}. The operator records the gate closed,
     * then runs the reindex that unfences everything. Emptiness has to be reported as emptiness.
     */
    @Test
    public void anEMPTYIndexIsNotCOMPLETE_evenAfterASuccessfulRun() throws Exception {
        // A run over an index with no CMIS objects: completes cleanly, having done nothing.
        assertTrue(svc.start("bedroom"));
        AclEpochMigrationService.Progress p = svc.status("bedroom");
        for (int i = 0; i < 200 && "RUNNING".equals(p.status); i++) Thread.sleep(25);
        assertEquals("COMPLETED", p.status);
        assertEquals(0, p.scanned);

        assertEquals(AclEpochMigrationService.Verdict.EMPTY_INDEX, svc.verdict("bedroom", 0, 0),
                "zero remaining out of zero indexed says nothing about the migration");
        assertEquals(AclEpochMigrationService.Verdict.COMPLETE, svc.verdict("bedroom", 0, 10),
                "zero remaining out of ten indexed is the real thing");
    }

    // ── Increment 13: orphan verification is FAIL-CLOSED ───────────

    private com.ibm.cloud.cloudant.v1.Cloudant couchFor(String... goneIds) {
        var cloudant = mock(com.ibm.cloud.cloudant.v1.Cloudant.class,
                org.mockito.Answers.RETURNS_DEEP_STUBS);
        java.util.Set<String> gone = java.util.Set.of(goneIds);
        lenient().when(cloudant.getDocument(any(
                com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.class)))
            .thenAnswer(inv -> {
                com.ibm.cloud.cloudant.v1.model.GetDocumentOptions o = inv.getArgument(0);
                var call = mock(com.ibm.cloud.sdk.core.http.ServiceCall.class);
                if ("READ-ERROR".equals(o.docId())) {
                    when(call.execute()).thenThrow(new RuntimeException("couch view timeout"));
                } else if (gone.contains(o.docId())) {
                    when(call.execute()).thenThrow(new com.ibm.cloud.sdk.core.service.exception
                            .NotFoundException(mockResponse404()));
                } else {
                    when(call.execute()).thenReturn(mock(com.ibm.cloud.sdk.core.http.Response.class));
                }
                return call;
            });
        return cloudant;
    }

    private static okhttp3.Response mockResponse404() {
        return new okhttp3.Response.Builder()
                .request(new okhttp3.Request.Builder().url("http://x/").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(404).message("nf")
                .body(okhttp3.ResponseBody.create("{}", okhttp3.MediaType.parse("application/json")))
                .build();
    }

    private void wireCouch(com.ibm.cloud.cloudant.v1.Cloudant cloudant) {
        var wrapper = mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        lenient().when(wrapper.getClient()).thenReturn(cloudant);
        lenient().when(wrapper.getDatabaseName()).thenReturn("bedroom");
        var pool = mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
        lenient().when(pool.getClient("bedroom")).thenReturn(wrapper);
        svc.setConnectorPool(pool);
    }

    private static SolrDocument unfencedDoc(String id, long version) {
        SolrDocument d = new SolrDocument();
        d.setField("id", id);
        d.setField("_version_", version);
        return d;
    }

    /**
     * Only a DEFINITIVE CouchDB 404 qualifies an entry for deletion. A read ERROR is
     * "unverifiable" and a live document is "liveUnfenced" — neither may ever be deleted on that
     * evidence: deleting on an outage would purge the index of every object the moment CouchDB
     * blinked.
     */
    @Test
    public void orphanVerificationIsFAILCLOSED() {
        wireCouch(couchFor("gone-1"));
        pages.add(page(unfencedDoc("gone-1", 111L), unfencedDoc("live-1", 222L),
                unfencedDoc("READ-ERROR", 333L)));

        AclEpochMigrationService.OrphanScan scan = svc.scanOrphans("bedroom", 100);
        assertEquals(1, scan.verifiedOrphans.size());
        assertEquals("gone-1", scan.verifiedOrphans.get(0).getId());
        assertEquals(1, scan.unverifiable, "a read ERROR is NOT absence");
        assertEquals(1, scan.liveUnfenced, "existing content is NOT an orphan");
    }

    /** The scan only ever looks at UNFENCED entries — the fenced ones are not its business. */
    @Test
    public void theOrphanScanTargetsOnlyTheUnfencedPopulation() {
        wireCouch(couchFor());
        pages.add(page());
        svc.scanOrphans("bedroom", 100);
        String fq = String.join(" ", issued.get(0).getFilterQueries());
        assertTrue(fq.contains("-effective_acl_epoch:[* TO *]"), fq);
        assertTrue(fq.contains("-doc_type:[* TO *]"), "and never the RAG populations: " + fq);
    }

    /**
     * Every delete is CONDITIONAL on the {@code _version_} read at verification: an entry that
     * changed since (an archive RESTORE reusing the _id re-indexed it) must fail its CAS instead of
     * being deleted over fresh content.
     */
    @Test
    public void deletesCarryTheVerificationVersionAsACAS() throws Exception {
        wireCouch(couchFor("gone-2"));
        pages.add(page(unfencedDoc("gone-2", 777L)));
        java.util.List<org.apache.solr.client.solrj.request.UpdateRequest> sent = new ArrayList<>();
        when(solrClient.request(any(), any())).thenAnswer(inv -> {
            Object r = inv.getArgument(0);
            if (r instanceof org.apache.solr.client.solrj.request.UpdateRequest u) sent.add(u);
            return new org.apache.solr.common.util.NamedList<>();
        });

        AclEpochMigrationService.OrphanDeleteResult r = svc.deleteOrphans("bedroom", 100);
        assertEquals(1, r.deleted);
        assertEquals(1, sent.size());
        var byId = sent.get(0).getDeleteByIdMap();
        assertNotNull(byId);
        var params = byId.get("gone-2");
        assertNotNull(params, "the delete must address gone-2");
        assertEquals(777L, ((Number) params.get("ver")).longValue(),
                "the delete must be a _version_ CAS, not an unconditional delete");
    }

    /**
     * The remaining-unfenced count is an INDEPENDENT check read from Solr, not a restatement of the
     * run's counters — a run that reported COMPLETED while skipping documents must still show a
     * non-zero remainder.
     */
    @Test
    public void remainingUnfencedAsksSolr_notTheRunCounters() throws Exception {
        SolrDocumentList empty = new SolrDocumentList();
        empty.setNumFound(42L);
        pages.add(empty);

        assertEquals(42L, svc.remainingUnfenced("bedroom"));
        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put("fq", String.join(" ", issued.get(0).getFilterQueries()));
        assertTrue(seen.get("fq").toString().contains("-effective_acl_epoch:[* TO *]"), seen.toString());
        assertEquals(Integer.valueOf(0), issued.get(0).getRows(), "a count needs no rows");
    }
}
