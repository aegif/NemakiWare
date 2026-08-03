/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseGrant;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseMissingException;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.SequenceCounterException;

/**
 * The real-CouchDB half of increment D's gate: the same {@link LineageSequencingStore}
 * contract the scripted tests drive, exercised against actual CouchDB CAS semantics
 * (revisions, update conflicts, view ordering).
 *
 * <p>Enabled by {@code NEMAKI_LINEAGE_IT_COUCHDB_URL} (with
 * {@code NEMAKI_LINEAGE_IT_COUCHDB_USER} / {@code NEMAKI_LINEAGE_IT_COUCHDB_PASSWORD}) — set
 * locally against the dev stack, and set unconditionally by the dedicated CI job, where this
 * class MUST run: a skipped IT does not satisfy the gate. Each run provisions its own
 * database and deletes it afterwards.
 */
public class LineageSequencingCouchIT {

    private static final String REPO = "bedroom";

    private static Cloudant cloudant;
    private static String dbName;
    private static CouchLineageJournalStore store;

    @BeforeAll
    static void provision() {
        String url = System.getenv("NEMAKI_LINEAGE_IT_COUCHDB_URL");
        if (url == null || url.isBlank()) {
            // The dedicated CI job sets -Dlineage.it.required=true: there, a missing URL is a
            // FAILURE, never a silent skip — an @Enabled condition alone reports green with
            // zero tests executed on a misspelled variable. Locally (no flag) the class opts
            // out via an assumption.
            if (Boolean.getBoolean("lineage.it.required")) {
                throw new IllegalStateException("lineage.it.required=true but"
                        + " NEMAKI_LINEAGE_IT_COUCHDB_URL is not set — the CI gate must run");
            }
            org.junit.jupiter.api.Assumptions.abort(
                    "NEMAKI_LINEAGE_IT_COUCHDB_URL not set — real-CouchDB IT skipped locally");
        }
        String user = System.getenv("NEMAKI_LINEAGE_IT_COUCHDB_USER");
        String password = System.getenv("NEMAKI_LINEAGE_IT_COUCHDB_PASSWORD");
        if (user != null && !user.isBlank()) {
            cloudant = new Cloudant("lineage-it", new BasicAuthenticator.Builder()
                    .username(user).password(password).build());
        } else {
            cloudant = new Cloudant("lineage-it", null);
        }
        cloudant.setServiceUrl(url);
        dbName = "nemaki_lineage_it_" + UUID.randomUUID().toString().replace("-", "");
        store = CouchLineageJournalStore.forDirectClient(cloudant, dbName, new ObjectMapper());
    }

    @AfterAll
    static void dropDatabase() {
        if (cloudant != null && dbName != null) {
            try {
                cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(dbName).build())
                        .execute();
            } catch (Exception ignored) {
            }
        }
    }

    private static LineageEventV2 event(String repositoryId, String operationId,
            String occurredAt) {
        return new LineageEventV2Builder()
                .eventId("evt-" + operationId)
                .occurredAt(occurredAt)
                .repositoryId(repositoryId)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId(operationId)
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document(repositoryId, "doc-" + operationId,
                        "a.txt"))
                .addOutput(LineageEndpoint.archive(repositoryId, "doc-" + operationId,
                        "doc-" + operationId, 1L))
                .build();
    }

    /** The bootstrap patch's job, done directly: the lease document with generation 0. */
    private static void bootstrapLease(String repositoryId) {
        Map<String, Object> lease = new HashMap<>();
        lease.put("_id", "lineage_sequencer_lease:" + repositoryId);
        lease.put("type", "lineage_sequencer_lease");
        lease.put("generation", 0L);
        lease.put("owner", null);
        lease.put("expiresAt", java.time.Instant.EPOCH.toString());
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            lease.forEach(doc::put);
            doc.setId((String) lease.get("_id"));
            cloudant.postDocument(new com.ibm.cloud.cloudant.v1.model.PostDocumentOptions
                    .Builder().db(dbName).document(doc).build()).execute();
        } catch (Exception alreadyThere) {
            // idempotent for reruns within the class
        }
    }

    private static void bootstrapCounter(String repositoryId, long seq) {
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            doc.setId("lineage_seq:" + repositoryId);
            doc.put("type", "lineage_sequence");
            doc.put("repositoryId", repositoryId);
            doc.put("seq", seq);
            cloudant.postDocument(new com.ibm.cloud.cloudant.v1.model.PostDocumentOptions
                    .Builder().db(dbName).document(doc).build()).execute();
        } catch (Exception alreadyThere) {
        }
    }

    @Test
    public void theWholeFencedPathRunsAgainstRealCouch() {
        // Lease is bootstrap-only: before it exists, acquire must fail closed, never create.
        // A repository nothing bootstraps keeps this assertion order-independent.
        assertThrows(LeaseMissingException.class,
                () -> store.acquireSequencerLease("never-bootstrapped",
                        "node-a", Duration.ofMinutes(5)));

        bootstrapLease(REPO);
        bootstrapCounter(REPO, 0L);

        // Durable-first: three unsequenced rows land via the production appendV2.
        store.appendV2(event(REPO, "op-b", "2026-08-01T00:00:02Z"));
        store.appendV2(event(REPO, "op-a", "2026-08-01T00:00:01Z"));
        store.appendV2(event(REPO, "op-c", "2026-08-01T00:00:03Z"));

        List<LineageJournalRowV2> backlog = store.findUnsequencedV2(REPO, 10);
        assertEquals(3, backlog.size());
        assertEquals("op-a", backlog.get(0).event().operationId(),
                "the claim scan is occurredAt-ordered on the real view");

        LineageFencedSequencer sequencer = new LineageFencedSequencer(store,
                new LineageMetrics(), "node-a", Duration.ofMinutes(5), 10, 100);
        LineageFencedSequencer.RunSummary summary = sequencer.runOnce(REPO);

        assertEquals(3, summary.finalized());
        assertEquals(LineageSequencingStore.SequencerHealth.FENCED_OK, summary.health());
        assertTrue(store.findUnsequencedV2(REPO, 10).isEmpty());
        assertTrue(store.findSequencingV2(REPO, 10).isEmpty());
        assertEquals(3, store.sequenceHighWatermark(REPO),
                "the watermark view reduces to the max finalized sequence");

        // Idempotent re-run: nothing left, lease re-acquired and released cleanly.
        assertEquals(0, sequencer.runOnce(REPO).finalized());
    }

    @Test
    public void staleRevisionsLoseTheRealCas() {
        String casRepo = "cas-repo";
        bootstrapLease(casRepo);
        bootstrapCounter(casRepo, 100L);
        store.appendV2(event(casRepo, "op-cas", "2026-08-01T00:01:00Z"));

        LineageJournalRowV2 row = store.findUnsequencedV2(casRepo, 50).stream()
                .filter(r -> "op-cas".equals(r.event().operationId()))
                .findFirst().orElseThrow();

        Optional<LeaseGrant> grant = store.acquireSequencerLease(casRepo, "node-cas",
                Duration.ofMinutes(5));
        assertTrue(grant.isPresent());
        try {
            assertTrue(store.claimForSequencing(row, grant.get().generation(),
                    grant.get().sequencerLeaseToken()));
            assertFalse(store.claimForSequencing(row, grant.get().generation(),
                            grant.get().sequencerLeaseToken()),
                    "the same stale-rev row must lose the second CAS on real CouchDB");
        } finally {
            store.releaseSequencerLease(grant.get());
        }
    }

    /** A corrupt row at the queue head must surface as STOPPED, never as an empty backlog. */
    @Test
    public void aCorruptHeadRowStopsTheSequencerLoudly() throws Exception {
        String repo = "corrupt-head-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-head", "2026-08-01T00:00:01Z"));

        // Corrupt the stored row directly: garbage state makes the strict envelope reject it.
        var doc = cloudant.getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions
                .Builder().db(dbName)
                .docId(CouchLineageEventV2.documentId(
                        event(repo, "op-head", "2026-08-01T00:00:01Z").deliveryId()))
                .build()).execute().getResult();
        // Tamper an immutable field: the row STAYS in the backlog view (state unchanged)
        // but the strict decode's digest re-verification rejects it — the barrier case.
        doc.put("operationId", "op-TAMPERED");
        cloudant.postDocument(new com.ibm.cloud.cloudant.v1.model.PostDocumentOptions
                .Builder().db(dbName).document(doc).build()).execute();

        LineageFencedSequencer sequencer = new LineageFencedSequencer(store,
                new LineageMetrics(), "node-a", Duration.ofMinutes(5), 10, 100);
        LineageFencedSequencer.RunSummary summary = sequencer.runOnce(repo);
        assertEquals(LineageSequencingStore.SequencerHealth.STOPPED, summary.health(),
                "a blocked queue head is an outage, not an empty backlog");
    }

    /** The watermark covers v1 history too — a v1 sequence bounds the shared counter. */
    @Test
    public void theWatermarkSeesV1History() {
        String v1Repo = "v1-history-repo";
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId(v1Repo)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(v1Repo, "doc-1")
                .addOutput("nemaki://" + v1Repo + "/archives/doc-1")
                .targets(List.of("purview"))
                .build();
        store.append(v1); // the v1 path assigns its eager sequence (auto-seeded counter)
        assertTrue(store.sequenceHighWatermark(v1Repo) >= 1,
                "v1 sequences bound the shared counter's rewind check");
    }

    @Test
    public void theFencedAllocatorFailsClosedOnAMissingCounter() {
        String otherRepo = "canopy";
        assertThrows(SequenceCounterException.class,
                () -> store.allocateSequenceFenced(otherRepo),
                "no auto-seed: a missing counter is a bootstrap gap, not a zero");
    }

    // ================================================================ D-rest-2: the §8-b
    // v2 projection machine against real CouchDB CAS semantics.

    @Test
    public void theV2ClaimIsExclusiveAndTheFencedPipelinePublishes() {
        String repo = "v2-projection-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-p1", "2026-08-01T01:00:01Z"));
        LineageFencedSequencer sequencer = new LineageFencedSequencer(store, null,
                "it-node", Duration.ofMinutes(1), 10, 100);
        assertEquals(1, sequencer.runOnce(repo).finalized());

        List<LineageJournalRowV2> rows = store.findV2ByRepositoryAndSequenceRange(repo, 0, 10);
        assertEquals(1, rows.size());
        String recordId = rows.get(0).event().deliveryId();

        // Two claim attempts: real CouchDB revs make exactly one win.
        LineageV2TransitionStore.V2ClaimGrant first =
                store.claimForProjection(recordId, "atlas", Duration.ofMinutes(2));
        assertEquals(true, first != null);
        assertEquals(null, store.claimForProjection(recordId, "atlas", Duration.ofMinutes(2)),
                "a live unexpired claim is not reclaimable");

        // Fenced pipeline: PROJECTING -> VERIFYING -> PUBLISHED with the winning token.
        assertEquals(true, store.transitionV2(recordId, "atlas",
                LineagePublishStatus.PROJECTING, LineagePublishStatus.VERIFYING,
                first.claimToken(), null));
        assertEquals(false, store.transitionV2(recordId, "atlas",
                LineagePublishStatus.VERIFYING, LineagePublishStatus.PUBLISHED,
                "rotated-token", null), "a stale token loses the fence on the real store");
        assertEquals(true, store.transitionV2(recordId, "atlas",
                LineagePublishStatus.VERIFYING, LineagePublishStatus.PUBLISHED,
                first.claimToken(), null));

        LineageTargetLifecycle published = store.findV2ByRecordId(recordId)
                .targetLifecycles().get("atlas");
        assertEquals(LineagePublishStatus.PUBLISHED, published.status());
        assertEquals(first.claimToken(), published.claimToken(), "token kept for audit");
    }

    @Test
    public void theReaperTakesExactlyExpiredClaimsByToken() {
        String repo = "v2-reaper-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-r1", "2026-08-01T02:00:01Z"));
        store.appendV2(event(repo, "op-r2", "2026-08-01T02:00:02Z"));
        LineageFencedSequencer sequencer = new LineageFencedSequencer(store, null,
                "it-node", Duration.ofMinutes(1), 10, 100);
        assertEquals(2, sequencer.runOnce(repo).finalized());
        List<LineageJournalRowV2> rows = store.findV2ByRepositoryAndSequenceRange(repo, 0, 10);

        // One claim with a lease that is already effectively expired, one live.
        String expiredId = rows.get(0).event().deliveryId();
        String liveId = rows.get(1).event().deliveryId();
        LineageV2TransitionStore.V2ClaimGrant expired =
                store.claimForProjection(expiredId, "atlas", Duration.ofMillis(1));
        LineageV2TransitionStore.V2ClaimGrant live =
                store.claimForProjection(liveId, "atlas", Duration.ofMinutes(5));
        assertEquals(true, expired != null && live != null);
        try {
            Thread.sleep(5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int reaped = store.reapExpiredClaims("atlas", java.time.Instant.now());
        assertEquals(1, reaped, "exactly the expired claim reaps");
        assertEquals(LineagePublishStatus.FAILED,
                store.findV2ByRecordId(expiredId).targetLifecycles().get("atlas").status());
        LineageTargetLifecycle survivor =
                store.findV2ByRecordId(liveId).targetLifecycles().get("atlas");
        assertEquals(LineagePublishStatus.PROJECTING, survivor.status());
        assertEquals(0L, survivor.retryCount(), "reap consumed no retry anywhere");
        assertEquals(0L, store.findV2ByRecordId(expiredId).targetLifecycles().get("atlas")
                .retryCount(), "reap-FAILED consumed no retry either");

        // The reaped row is claimable again (FAILED -> PROJECTING re-claim).
        assertEquals(true,
                store.claimForProjection(expiredId, "atlas", Duration.ofMinutes(5)) != null);
    }

    @Test
    public void theMonotonicCursorRefusesRollbackOnRealRevisions() {
        CouchProjectionCursorStore cursors = new CouchProjectionCursorStore();
        try {
            java.lang.reflect.Field f =
                    CouchProjectionCursorStore.class.getDeclaredField("journalStore");
            f.setAccessible(true);
            f.set(cursors, store);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        String repo = "v2-cursor-repo";
        assertEquals(true, cursors.advanceCursorMonotonic(
                new ProjectionCursor("atlas", repo, 10L, java.time.Instant.now())));
        assertEquals(true, cursors.advanceCursorMonotonic(
                new ProjectionCursor("atlas", repo, 10L, java.time.Instant.now())),
                "equality is a no-op success");
        assertEquals(true, cursors.advanceCursorMonotonic(
                new ProjectionCursor("atlas", repo, 5L, java.time.Instant.now())),
                "a smaller incoming position is covered, not written");
        assertEquals(10L, cursors.getCursor("atlas", repo).lastProcessedSequence(),
                "the stored position never rolled back");
        assertEquals(true, cursors.advanceCursorMonotonic(
                new ProjectionCursor("atlas", repo, 12L, java.time.Instant.now())));
        assertEquals(12L, cursors.getCursor("atlas", repo).lastProcessedSequence());
    }

    /** F1: the strictly-after v2 query keeps full pages full when the cursor sits on a v2 row. */
    @Test
    public void theV2OrderedQueryIsStrictlyAfterAtTheQueryNotByPostFilter() {
        String repo = "v2-boundary-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-m1", "2026-08-01T03:00:01Z"));
        store.appendV2(event(repo, "op-m2", "2026-08-01T03:00:02Z"));
        store.appendV2(event(repo, "op-m3", "2026-08-01T03:00:03Z"));
        LineageFencedSequencer sequencer = new LineageFencedSequencer(store, null,
                "it-node", Duration.ofMinutes(1), 10, 100);
        assertEquals(3, sequencer.runOnce(repo).finalized());

        List<LineageJournalRowV2> all = store.findV2ByRepositoryAndSequenceRange(repo, 0, 10);
        assertEquals(3, all.size());
        long first = all.get(0).event().sequenceNumber();

        // Cursor ON the first v2 row, limit 2: a post-filter implementation would return a
        // shrunken page (1 row) and the merge window would misread coverage; strictly-after
        // returns a FULL page of the next two.
        List<LineageJournalRowV2> page =
                store.findV2ByRepositoryAndSequenceRange(repo, first, 2);
        assertEquals(2, page.size(), "full page stays full at the boundary");
        assertEquals(first + 1, page.get(0).event().sequenceNumber());
    }

    /** Round-2 fix 1: the read-only listings merge both schemas despite the view split. */
    @Test
    public void findAllAndDateRangeListBothSchemasInTimeOrder() {
        String repo = "v2-listing-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-l2", "2026-08-01T05:00:02Z"));
        // v1 events stamp occurredAt at append time (now) — later than the v2 row above.
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId(repo)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(repo, "doc-l1")
                .addOutput("nemaki://" + repo + "/archives/doc-l1")
                .targets(List.of("atlas"))
                .build();
        store.append(v1);

        List<LineageJournalRow> range = store.findByDateRange(
                "2026-08-01T05:00:00Z", "2099-01-01T00:00:00Z", 50, 0);
        // The class shares one DB — scope the assertions to this repo.
        List<LineageJournalRow.Decoded> mine = range.stream()
                .filter(r -> r instanceof LineageJournalRow.Decoded d
                        && repo.equals(d.entry().record().repositoryId()))
                .map(r -> (LineageJournalRow.Decoded) r)
                .toList();
        assertEquals(2, mine.size(), "both schemas appear in the ranged listing");
        assertEquals("2026-08-01T05:00:02Z", mine.get(0).entry().record().occurredAt(),
                "ascending merge order puts the older v2 row first");
        assertEquals(1, store.findByDateRange(
                "2026-08-01T05:00:00Z", "2099-01-01T00:00:00Z", 1, 0).size(),
                "limit applies to the MERGED order");

        // Nonzero offset walks the MERGED order: the row at offset 1 is the second element
        // of the full merged listing, whatever schema it came from.
        List<LineageJournalRow> all = store.findByDateRange(
                "2026-08-01T05:00:00Z", "2099-01-01T00:00:00Z", 10, 0);
        List<LineageJournalRow> offset1 = store.findByDateRange(
                "2026-08-01T05:00:00Z", "2099-01-01T00:00:00Z", 10, 1);
        assertEquals(all.size() - 1, offset1.size());
        assertEquals(((LineageJournalRow.Decoded) all.get(1)).entry().record().recordId(),
                ((LineageJournalRow.Decoded) offset1.get(0)).entry().record().recordId());

        // findAll (newest first) sees both schemas too.
        List<LineageJournalRow> newest = store.findAll(200, 0);
        boolean hasMineV2 = newest.stream().anyMatch(r -> r instanceof LineageJournalRow.Decoded d
                && repo.equals(d.entry().record().repositoryId())
                && "2026-08-01T05:00:02Z".equals(d.entry().record().occurredAt()));
        boolean hasMineV1 = newest.stream().anyMatch(r -> r instanceof LineageJournalRow.Decoded d
                && repo.equals(d.entry().record().repositoryId())
                && d.entry().envelope() instanceof LineageJournalEntry.V1);
        assertEquals(true, hasMineV2 && hasMineV1, "findAll merges both schemas");
    }

    /** F2: reaping across page boundaries — mutations must not skip surviving candidates. */
    @Test
    public void theReaperDrainsAcrossPageBoundariesDespiteMutations() {
        String repo = "v2-reaper-paging-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        int total = 120; // beyond the 100-row page size
        for (int i = 0; i < total; i++) {
            store.appendV2(event(repo, "op-page-" + String.format("%03d", i),
                    String.format("2026-08-01T04:%02d:%02dZ", i / 60, i % 60)));
        }
        LineageFencedSequencer sequencer = new LineageFencedSequencer(store, null,
                "it-node", Duration.ofMinutes(1), 200, 1000);
        assertEquals(total, sequencer.runOnce(repo).finalized());
        List<LineageJournalRowV2> rows =
                store.findV2ByRepositoryAndSequenceRange(repo, 0, total);
        assertEquals(total, rows.size());
        for (LineageJournalRowV2 row : rows) {
            assertEquals(true, store.claimForProjection(row.event().deliveryId(),
                    "page-target", Duration.ofMillis(1)) != null);
        }
        try {
            Thread.sleep(5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int reaped = store.reapExpiredClaims("page-target", java.time.Instant.now());
        assertEquals(total, reaped,
                "every expired claim across page boundaries reaps exactly once");
    }

    // ================================================================ D-rest-3: §8-d
    // replay machine against real CouchDB.

    @Test
    public void theReplayRequestRaceHasExactlyOneWinnerAndTheMachineConverges() {
        String repo = "v2-replay-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-rp1", "2026-08-01T06:00:01Z"));
        LineageFencedSequencer sequencer = new LineageFencedSequencer(store, null,
                "it-node", Duration.ofMinutes(1), 10, 100);
        assertEquals(1, sequencer.runOnce(repo).finalized());
        List<LineageJournalRowV2> rows = store.findV2ByRepositoryAndSequenceRange(repo, 0, 10);
        String recordId = rows.get(0).event().deliveryId();

        // Drive the publish lifecycle to PUBLISHED (terminal — the replayable set).
        LineageV2TransitionStore.V2ClaimGrant claim =
                store.claimForProjection(recordId, "atlas", Duration.ofMinutes(2));
        assertEquals(true, store.transitionV2(recordId, "atlas",
                LineagePublishStatus.PROJECTING, LineagePublishStatus.VERIFYING,
                claim.claimToken(), null));
        assertEquals(true, store.transitionV2(recordId, "atlas",
                LineagePublishStatus.VERIFYING, LineagePublishStatus.PUBLISHED,
                claim.claimToken(), null));

        // Race: two requests — real revisions make exactly one win.
        LineageV2ReplayStore.ReplayGrant g1 = store.requestReplay(recordId, "atlas");
        assertEquals(true, g1 != null);
        try {
            store.requestReplay(recordId, "atlas");
            throw new AssertionError("an in-progress request must refuse the second");
        } catch (LineageV2ReplayStore.ReplayRefusedException expected) {
            // in progress — the frozen expected set is {absent, ACKED}
        }

        // The deterministic compensation converges across two append attempts.
        LineageJournalRowV2 original = store.findV2ByRecordId(recordId);
        LineageEventV2 comp =
                LineageReplayService.compensationOf(original, "atlas", g1.generation());
        store.appendV2(comp);
        store.appendV2(comp); // idempotent — digest-exact 409 convergence
        assertEquals(true, store.advanceReplay(recordId, "atlas", g1.requestId(),
                LineageReplayRequest.State.REQUESTED, LineageReplayRequest.State.CREATED));
        assertEquals(true, store.advanceReplay(recordId, "atlas", g1.requestId(),
                LineageReplayRequest.State.CREATED, LineageReplayRequest.State.ACKED));

        // The compensation rides the normal pipeline: sequencer assigns a NEW sequence.
        assertEquals(1, sequencer.runOnce(repo).finalized());
        List<LineageJournalRowV2> after = store.findV2ByRepositoryAndSequenceRange(repo, 0, 10);
        assertEquals(2, after.size());
        assertEquals(true, after.get(1).event().sequenceNumber()
                > after.get(0).event().sequenceNumber());

        // ACKED admits generation+1.
        LineageV2ReplayStore.ReplayGrant g2 = store.requestReplay(recordId, "atlas");
        assertEquals(g1.generation() + 1, g2.generation());

        // The original's publish lifecycle is untouched audit fact.
        assertEquals(LineagePublishStatus.PUBLISHED,
                store.findV2ByRecordId(recordId).targetLifecycles().get("atlas").status());
    }

    @Test
    public void aLiveClaimRefusesReplayOnTheRealStore() {
        String repo = "v2-replay-live-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-rp2", "2026-08-01T06:10:01Z"));
        LineageFencedSequencer sequencer = new LineageFencedSequencer(store, null,
                "it-node", Duration.ofMinutes(1), 10, 100);
        sequencer.runOnce(repo);
        String recordId = store.findV2ByRepositoryAndSequenceRange(repo, 0, 10)
                .get(0).event().deliveryId();
        store.claimForProjection(recordId, "atlas", Duration.ofMinutes(5));
        try {
            store.requestReplay(recordId, "atlas");
            throw new AssertionError("a live PROJECTING claim must refuse replay");
        } catch (LineageV2ReplayStore.ReplayRefusedException expected) {
            // token-fenced claim is not stealable
        }
    }

    @Test
    public void unackedRequestsFencePurgeAndAppearInTheRecoveryScan() {
        String repo = "v2-replay-scan-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-rp3", "2020-01-01T00:00:01Z")); // old = purge window
        LineageFencedSequencer sequencer = new LineageFencedSequencer(store, null,
                "it-node", Duration.ofMinutes(1), 10, 100);
        sequencer.runOnce(repo);
        String recordId = store.findV2ByRepositoryAndSequenceRange(repo, 0, 10)
                .get(0).event().deliveryId();
        LineageV2TransitionStore.V2ClaimGrant claim =
                store.claimForProjection(recordId, "purview", Duration.ofMinutes(2));
        store.transitionV2(recordId, "purview", LineagePublishStatus.PROJECTING,
                LineagePublishStatus.VERIFYING, claim.claimToken(), null);
        store.transitionV2(recordId, "purview", LineagePublishStatus.VERIFYING,
                LineagePublishStatus.PUBLISHED, claim.claimToken(), null);
        LineageV2ReplayStore.ReplayGrant grant = store.requestReplay(recordId, "purview");

        List<LineageV2ReplayStore.ReplayRecovery> scan =
                store.findUnackedReplayRequests(50);
        assertEquals(true, scan.stream().anyMatch(r -> r.recordId().equals(recordId)
                && r.target().equals("purview")
                && r.request().requestId().equals(grant.requestId())),
                "the REQUESTED record is a recovery item");

        // ---- The purge fence, exercised against the REAL purge (round-1 finding 5) ----
        // The row's occurredAt (2020) is far inside the cutoff; readiness is stubbed green
        // through the ObjectProvider seam so the v2 half of purge actually runs.
        var readiness = org.mockito.Mockito.mock(LineageDrestReadiness.class);
        org.mockito.Mockito.when(readiness.evaluate()).thenReturn(
                new LineageDrestReadiness.Readiness(true, java.util.List.of()));
        org.springframework.beans.factory.ObjectProvider<LineageDrestReadiness> provider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public LineageDrestReadiness getObject(Object... args) {
                        return readiness;
                    }

                    @Override
                    public LineageDrestReadiness getIfAvailable() {
                        return readiness;
                    }

                    @Override
                    public LineageDrestReadiness getIfUnique() {
                        return readiness;
                    }

                    @Override
                    public LineageDrestReadiness getObject() {
                        return readiness;
                    }
                };
        try {
            java.lang.reflect.Field f =
                    CouchLineageJournalStore.class.getDeclaredField("drestReadinessProvider");
            f.setAccessible(true);
            f.set(store, provider);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        java.time.Instant cutoff = java.time.Instant.parse("2025-01-01T00:00:00Z");
        store.purgeOlderThan(cutoff);
        assertEquals(true, store.findV2ByRecordId(recordId) != null,
                "a REQUESTED replay request fences purge — the deterministic reconstruction"
                        + " needs this row");

        // Drive the request to ACKED; the fence lifts and the row purges.
        LineageJournalRowV2 original = store.findV2ByRecordId(recordId);
        LineageEventV2 comp = LineageReplayService.compensationOf(original, "purview",
                grant.generation());
        store.appendV2(comp);
        assertEquals(true, store.advanceReplay(recordId, "purview", grant.requestId(),
                LineageReplayRequest.State.REQUESTED, LineageReplayRequest.State.CREATED));
        assertEquals(true, store.advanceReplay(recordId, "purview", grant.requestId(),
                LineageReplayRequest.State.CREATED, LineageReplayRequest.State.ACKED));
        int purged = store.purgeOlderThan(cutoff);
        assertEquals(true, store.findV2ByRecordId(recordId) == null,
                "ACKED alone does not fence (purged=" + purged + ") — the compensation row"
                        + " is the durable artifact");
    }

    /** F3: a corrupt head row cannot pin the recovery scan, even at limit 1. */
    @Test
    public void aCorruptHeadRowCannotPinTheRecoveryScan() {
        String repo = "v2-replay-pin-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-pin1", "2026-08-01T07:00:01Z"));
        store.appendV2(event(repo, "op-pin2", "2026-08-01T07:00:02Z"));
        LineageFencedSequencer sequencer = new LineageFencedSequencer(store, null,
                "it-node", Duration.ofMinutes(1), 10, 100);
        assertEquals(2, sequencer.runOnce(repo).finalized());
        List<LineageJournalRowV2> rows = store.findV2ByRepositoryAndSequenceRange(repo, 0, 10);

        // Both rows get real requests (earlier updatedAtMs sorts first)...
        for (LineageJournalRowV2 row : rows) {
            String rid = row.event().deliveryId();
            LineageV2TransitionStore.V2ClaimGrant c =
                    store.claimForProjection(rid, "atlas", Duration.ofMinutes(2));
            store.transitionV2(rid, "atlas", LineagePublishStatus.PROJECTING,
                    LineagePublishStatus.VERIFYING, c.claimToken(), null);
            store.transitionV2(rid, "atlas", LineagePublishStatus.VERIFYING,
                    LineagePublishStatus.PUBLISHED, c.claimToken(), null);
            store.requestReplay(rid, "atlas");
        }
        // ...then the FIRST row's request is corrupted in place (a non-UUID fence).
        String headId = CouchLineageEventV2.documentId(rows.get(0).event().deliveryId());
        try {
            var doc = cloudant.getDocument(
                    new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
                            .db(dbName).docId(headId).build()).execute().getResult();
            @SuppressWarnings("unchecked")
            Map<String, Object> requests =
                    (Map<String, Object>) doc.getProperties().get("v2ReplayRequestsByTarget");
            @SuppressWarnings("unchecked")
            Map<String, Object> atlas = (Map<String, Object>) requests.get("atlas");
            atlas.put("requestId", "not-a-uuid");
            cloudant.putDocument(new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions
                    .Builder().db(dbName).docId(headId).document(doc).build()).execute();
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        List<LineageV2ReplayStore.ReplayRecovery> scan = store.findUnackedReplayRequests(1);
        assertEquals(1, scan.size(), "the corrupt head is skipped loudly, the healthy"
                + " request behind it is still found at limit 1");
        assertEquals(rows.get(1).event().deliveryId(), scan.get(0).recordId());
    }

    // ================================================================ D-rest-4: decisions
    // + materialized rows against real CouchDB.

    @Test
    public void theDecisionRaceHasOneWinnerAndV1RowsConvergeDigestExact() {
        String repo = "v2-mat-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        String eventId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d";
        String eventKey = LineageEvent.computeEventKey(repo,
                LineageProcessType.IMPORT_UPLOADED, List.of("upload://zip-upload"),
                List.of("nemaki://" + repo + "/objects/folder-1"));
        String digest = LineageSpoolIdentity.v1EventDigest(eventId, eventKey, repo,
                LineageProcessType.IMPORT_UPLOADED, List.of("upload://zip-upload"),
                List.of("nemaki://" + repo + "/objects/folder-1"), Map.of(),
                "2026-08-01T00:00:00Z", "");
        LineageMaterializationDecision mine = LineageMaterializationDecision.of(
                "a".repeat(64), "b".repeat(64), 1, 0L, eventId,
                List.of(new LineageMaterializationDecision.V1Entry(eventId, digest)), 1000L);
        assertEquals(mine.materializationPlanDigest(),
                store.createDecisionIfAbsent(mine).materializationPlanDigest());
        // Second creator with the SAME content converges on the stored decision...
        assertEquals(eventId, store.createDecisionIfAbsent(mine).allocatedEventId());
        // ...and a DIFFERENT fact under the same id is refused.
        LineageMaterializationDecision other = LineageMaterializationDecision.of(
                "a".repeat(64), "c".repeat(64), 1, 0L, eventId,
                List.of(new LineageMaterializationDecision.V1Entry(eventId, digest)), 1000L);
        try {
            store.createDecisionIfAbsent(other);
            throw new AssertionError("a different fact must not share the decision id");
        } catch (LineageIntegrityException expected) {
        }

        // The materialized v1 row: create-if-absent, digest-exact convergence, fenced
        // sequence.
        LineageEvent event = new LineageEvent(1, eventId, eventKey, 0L,
                "2026-08-01T00:00:00Z", repo, LineageProcessType.IMPORT_UPLOADED,
                List.of("upload://zip-upload"),
                List.of("nemaki://" + repo + "/objects/folder-1"), "", "", 1, Map.of(),
                Map.of("atlas", LineagePublishStatus.PENDING));
        store.createMaterializedV1RowIfAbsent(event, digest);
        store.createMaterializedV1RowIfAbsent(event, digest); // idempotent
        LineageMaterializationStore.MaterializedV1Row stored =
                store.readMaterializedV1RowStrict(eventId);
        assertEquals(true, stored.event().sequenceNumber() > 0,
                "the fenced allocator assigned a real sequence");
        assertEquals(eventKey, stored.event().eventKey());
        try {
            store.createMaterializedV1RowIfAbsent(event, "f".repeat(64));
            throw new AssertionError("an occupant with a different digest must refuse");
        } catch (LineageIntegrityException expected) {
        }
    }
}
