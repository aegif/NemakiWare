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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.journal.LineageJournalRowV2.SequencingState;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseGrant;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseMissingException;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseView;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.SequenceCounterException;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.SequencerHealth;

/**
 * §8-a v2's fenced sequencer, driven through a scripted in-memory store — every race, latch
 * and crash window from the spec's tables, including the old-leader proof, as deterministic
 * series. The real-CouchDB integration test covers the same store contract against Couch;
 * this class covers the sequencer's decisions.
 */
public class LineageFencedSequencerTest {

    private static final String REPO = "bedroom";

    private static LineageEventV2 event(String operationId, String occurredAt) {
        return new LineageEventV2Builder()
                .eventId("evt-" + operationId)
                .occurredAt(occurredAt)
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId(operationId)
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document(REPO, "doc-" + operationId, "a.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "doc-" + operationId,
                        "doc-" + operationId, 1L))
                .build();
    }

    // ------------------------------------------------------------------ scripted store

    /**
     * In-memory {@link LineageSequencingStore}: real CAS semantics (rev counters, state
     * checks) plus script hooks that fail or steal at exact points, which is how each spec
     * series is reproduced deterministically.
     */
    static final class ScriptedStore implements LineageSequencingStore {

        record StoredRow(LineageEventV2 event, int rev, SequencingState state,
                         Long generation, String token, long sequence) {
        }

        final Map<String, StoredRow> rows = new LinkedHashMap<>();
        long leaseGeneration = 0;
        String leaseToken = null;
        String leaseOwner = null;
        Instant leaseExpiresAt = Instant.EPOCH;
        boolean leaseExists = true;
        Long counter = 0L;
        boolean counterMissing = false;
        long watermark = 0;

        // script hooks
        boolean acquireThrows = false;
        boolean failRenew = false;
        boolean claimStorageFails = false;
        String reportExpiresAt = null; // when set, readSequencerLease reports this expiry
        int stealLeaseAfterAllocations = -1;
        int allocations = 0;
        int writesAfterLatchCheck = 0;
        Runnable afterAllocation = null;

        void addUnsequenced(LineageEventV2 event) {
            rows.put(CouchLineageEventV2.documentId(event.deliveryId()),
                    new StoredRow(event, 1, SequencingState.UNSEQUENCED, null, null, 0));
        }

        void addSequencing(LineageEventV2 event, long generation, String token) {
            rows.put(CouchLineageEventV2.documentId(event.deliveryId()),
                    new StoredRow(event, 1, SequencingState.SEQUENCING, generation, token, 0));
        }

        private LineageJournalRowV2 toRow(StoredRow stored) {
            LineageEventV2 event = stored.sequence > 0
                    ? withSequence(stored.event, stored.sequence)
                    : stored.event;
            return new LineageJournalRowV2(event, String.valueOf(stored.rev), stored.state,
                    stored.generation, stored.token);
        }

        /** The canonical constructor with only the sequence changed — digest excludes it. */
        static LineageEventV2 withSequence(LineageEventV2 e, long sequence) {
            return new LineageEventV2(e.schemaVersion(), e.idempotencyKeyVersion(),
                    e.eventId(), e.processKey(), e.delivery(), e.deliveryId(),
                    e.repositoryId(), e.processType(), e.operationId(), e.occurredAt(),
                    e.inputs(), e.outputs(), e.chunkIndex(), e.chunkCount(), sequence,
                    e.correlationId(), e.spoolRecordId(), e.legacyEventKey(),
                    e.publishStatusByTarget(), e.creationPayloadDigest());
        }

        @Override
        public Optional<LeaseGrant> acquireSequencerLease(String repositoryId, String nodeId,
                Duration ttl) {
            if (acquireThrows) {
                throw new IllegalStateException("storage down");
            }
            if (!leaseExists) {
                throw new LeaseMissingException(repositoryId);
            }
            boolean free = leaseOwner == null || leaseExpiresAt.isBefore(Instant.now());
            if (!free) {
                return Optional.empty();
            }
            leaseGeneration++;
            leaseToken = "token-" + leaseGeneration;
            leaseOwner = nodeId;
            leaseExpiresAt = Instant.now().plus(ttl);
            leaseRev++;
            return Optional.of(new LeaseGrant(repositoryId, leaseGeneration, leaseToken,
                    nodeId, leaseExpiresAt.toString(), String.valueOf(leaseRev)));
        }

        @Override
        public Optional<LeaseGrant> renewSequencerLease(LeaseGrant grant, Duration ttl) {
            if (failRenew || !matches(grant)) {
                return Optional.empty();
            }
            leaseExpiresAt = Instant.now().plus(ttl);
            leaseRev++;
            return Optional.of(new LeaseGrant(grant.repositoryId(), grant.generation(),
                    grant.sequencerLeaseToken(), grant.owner(), leaseExpiresAt.toString(),
                    String.valueOf(leaseRev)));
        }

        int leaseRev = 1;

        @Override
        public void releaseSequencerLease(LeaseGrant grant) {
            // Models the frozen owner/generation/token/_rev release CAS.
            if (matches(grant) && String.valueOf(leaseRev).equals(grant.rev())) {
                leaseOwner = null;
                leaseExpiresAt = Instant.EPOCH;
            }
        }

        @Override
        public Optional<LeaseView> readSequencerLease(String repositoryId) {
            if (!leaseExists) {
                return Optional.empty();
            }
            return Optional.of(new LeaseView(leaseGeneration, leaseToken, leaseOwner,
                    reportExpiresAt != null ? reportExpiresAt : leaseExpiresAt.toString()));
        }

        private boolean matches(LeaseGrant grant) {
            return grant != null && leaseGeneration == grant.generation()
                    && grant.sequencerLeaseToken().equals(leaseToken)
                    && grant.owner().equals(leaseOwner);
        }

        @Override
        public List<LineageJournalRowV2> findUnsequencedV2(String repositoryId, int limit) {
            return byState(SequencingState.UNSEQUENCED, limit);
        }

        @Override
        public List<LineageJournalRowV2> findSequencingV2(String repositoryId, int limit) {
            return byState(SequencingState.SEQUENCING, limit);
        }

        private List<LineageJournalRowV2> byState(SequencingState state, int limit) {
            List<Map.Entry<String, StoredRow>> matched = new ArrayList<>();
            for (Map.Entry<String, StoredRow> entry : rows.entrySet()) {
                if (entry.getValue().state == state) {
                    matched.add(entry);
                }
            }
            matched.sort(Comparator
                    .comparing((Map.Entry<String, StoredRow> e) -> e.getValue().event
                            .occurredAt())
                    .thenComparing(Map.Entry::getKey));
            List<LineageJournalRowV2> result = new ArrayList<>();
            for (int i = 0; i < matched.size() && i < limit; i++) {
                result.add(toRow(matched.get(i).getValue()));
            }
            return result;
        }

        private boolean cas(LineageJournalRowV2 row, SequencingState expectedState,
                Long expectedGeneration, StoredRowMutator mutator) {
            StoredRow stored = rows.get(row.documentId());
            if (stored == null || stored.state != expectedState
                    || !String.valueOf(stored.rev).equals(row.rev())) {
                return false;
            }
            if (expectedGeneration != null && (stored.generation == null
                    || !expectedGeneration.equals(stored.generation))) {
                return false;
            }
            rows.put(row.documentId(), mutator.mutate(stored));
            return true;
        }

        interface StoredRowMutator {
            StoredRow mutate(StoredRow stored);
        }

        @Override
        public boolean claimForSequencing(LineageJournalRowV2 row, long generation,
                String token) {
            if (claimStorageFails) {
                throw new SequencingStorageException("storage down mid-claim",
                        new RuntimeException());
            }
            return cas(row, SequencingState.UNSEQUENCED, null, stored -> new StoredRow(
                    stored.event, stored.rev + 1, SequencingState.SEQUENCING, generation,
                    token, 0));
        }

        @Override
        public boolean reclaimForSequencing(LineageJournalRowV2 row, long staleGeneration,
                long generation, String token) {
            if (staleGeneration >= generation) {
                return false;
            }
            return cas(row, SequencingState.SEQUENCING, staleGeneration,
                    stored -> new StoredRow(stored.event, stored.rev + 1,
                            SequencingState.SEQUENCING, generation, token, 0));
        }

        @Override
        public boolean finalizeSequence(LineageJournalRowV2 row, long generation, String token,
                long sequence) {
            StoredRow stored = rows.get(row.documentId());
            if (stored == null || stored.state != SequencingState.SEQUENCING
                    || !String.valueOf(stored.rev).equals(row.rev())
                    || stored.generation == null || stored.generation != generation
                    || !token.equals(stored.token)) {
                return false;
            }
            rows.put(row.documentId(), new StoredRow(withSequence(stored.event, sequence),
                    stored.rev + 1, SequencingState.SEQUENCED, generation, token, sequence));
            return true;
        }

        @Override
        public long allocateSequenceFenced(String repositoryId) {
            if (counterMissing || counter == null) {
                throw new SequenceCounterException(SequencerHealth.COUNTER_MISSING,
                        "counter missing");
            }
            if (counter < watermark) {
                throw new SequenceCounterException(SequencerHealth.COUNTER_REWOUND,
                        "counter rewound");
            }
            counter = counter + 1;
            allocations++;
            if (afterAllocation != null) {
                afterAllocation.run();
            }
            if (stealLeaseAfterAllocations >= 0 && allocations > stealLeaseAfterAllocations) {
                // another node acquired: generation moves on, token changes
                leaseGeneration++;
                leaseToken = "stolen-" + leaseGeneration;
                leaseOwner = "other-node";
                leaseExpiresAt = Instant.now().plus(Duration.ofMinutes(5));
                stealLeaseAfterAllocations = -1;
            }
            return counter;
        }

        @Override
        public long sequenceHighWatermark(String repositoryId) {
            return watermark;
        }

        List<StoredRow> sequencedInOrder() {
            return rows.values().stream()
                    .filter(r -> r.state == SequencingState.SEQUENCED)
                    .sorted(Comparator.comparingLong(r -> r.sequence))
                    .toList();
        }
    }

    private static LineageFencedSequencer sequencer(ScriptedStore store) {
        return new LineageFencedSequencer(store, new LineageMetrics(), "node-a",
                Duration.ofMinutes(5), 10, 100);
    }

    // ------------------------------------------------------------------ series

    @Test
    public void unsequencedRowsAreFinalizedInOccurredAtOrder() {
        ScriptedStore store = new ScriptedStore();
        store.addUnsequenced(event("op-b", "2026-08-01T00:00:02Z"));
        store.addUnsequenced(event("op-a", "2026-08-01T00:00:01Z"));
        store.addUnsequenced(event("op-c", "2026-08-01T00:00:03Z"));

        LineageFencedSequencer.RunSummary summary = sequencer(store).runOnce(REPO);

        assertEquals(3, summary.finalized());
        assertEquals(SequencerHealth.FENCED_OK, summary.health());
        List<ScriptedStore.StoredRow> sequenced = store.sequencedInOrder();
        assertEquals(3, sequenced.size());
        assertEquals("op-a", sequenced.get(0).event().operationId());
        assertEquals("op-b", sequenced.get(1).event().operationId());
        assertEquals("op-c", sequenced.get(2).event().operationId());
        assertEquals(1, sequenced.get(0).sequence());
        assertEquals(3, sequenced.get(2).sequence());
        assertEquals(1L, sequenced.get(0).generation(), "fencing coordinates stay for audit");
        assertTrue(sequenced.get(0).token().startsWith("token-"));
        assertNull(store.leaseOwner, "the lease is released after a clean run");
    }

    /**
     * The spec's old-leader proof: G=5 stalls after allocating N; G=6 reclaims, allocates
     * N+1, finalizes; the old leader's later finalize must CAS-fail and N stays a gap.
     */
    @Test
    public void theOldLeaderProofHoldsAndTheGapIsTolerated() {
        ScriptedStore store = new ScriptedStore();
        store.leaseGeneration = 4; // next acquire = 5
        LineageEventV2 stalled = event("op-stall", "2026-08-01T00:00:01Z");
        store.addUnsequenced(stalled);

        // Old leader (G=5): the lease is stolen right after its allocation, before finalize.
        store.stealLeaseAfterAllocations = 0;
        LineageFencedSequencer.RunSummary oldLeader = sequencer(store).runOnce(REPO);
        assertEquals(0, oldLeader.finalized(), "the pre-finalize re-check fenced the write");
        assertTrue(oldLeader.lostLease());
        ScriptedStore.StoredRow midway = store.rows.values().iterator().next();
        assertEquals(SequencingState.SEQUENCING, midway.state(),
                "the stalled row stays SEQUENCING under the old generation");
        assertEquals(5L, midway.generation());
        long consumedByOldLeader = store.counter;

        // New leader path: free the stolen hold, acquire (G=7), reclaim, finalize.
        store.leaseOwner = null;
        LineageFencedSequencer.RunSummary newLeader = sequencer(store).runOnce(REPO);
        assertEquals(1, newLeader.finalized());
        assertEquals(1, newLeader.reclaimed());
        ScriptedStore.StoredRow finalized = store.rows.values().iterator().next();
        assertEquals(SequencingState.SEQUENCED, finalized.state());
        assertEquals(consumedByOldLeader + 1, finalized.sequence(),
                "the old leader's number is a gap, never reused and never blocking");
        assertTrue(finalized.generation() > 5L);
    }

    @Test
    public void reclaimTakesOnlyStrictlyOlderGenerations() {
        ScriptedStore store = new ScriptedStore();
        store.leaseGeneration = 9; // next acquire = 10
        store.addSequencing(event("op-old", "2026-08-01T00:00:01Z"), 3, "token-3");
        store.addSequencing(event("op-new", "2026-08-01T00:00:02Z"), 11, "token-11");

        LineageFencedSequencer.RunSummary summary = sequencer(store).runOnce(REPO);

        assertEquals(1, summary.reclaimed(), "only the older generation's row");
        assertEquals(1, summary.finalized());
        boolean newerUntouched = store.rows.values().stream().anyMatch(
                r -> r.generation() == 11L && r.state() == SequencingState.SEQUENCING);
        assertTrue(newerUntouched, "a newer generation's in-flight row is not ours to take");
    }

    @Test
    public void aFailedRenewalLatchesTheGeneration() {
        ScriptedStore store = new ScriptedStore();
        for (int i = 0; i < 3; i++) {
            store.addUnsequenced(event("op-" + i, "2026-08-01T00:00:0" + i + "Z"));
        }
        store.failRenew = true;
        // Deterministic renewal trigger: the observed lease reports an expiry inside the
        // half-TTL window, so the very first pre-write check must renew — and fail.
        store.reportExpiresAt = Instant.now().plus(Duration.ofSeconds(1)).toString();
        LineageFencedSequencer tight = new LineageFencedSequencer(store, null, "node-a",
                Duration.ofMinutes(5), 10, 100);

        LineageFencedSequencer.RunSummary summary = tight.runOnce(REPO);

        assertTrue(summary.lostLease());
        assertEquals(0, summary.finalized(), "no write lands after the latch drops");
        assertTrue(store.sequencedInOrder().isEmpty());
    }

    @Test
    public void counterFailuresStopTheRunFailClosed() {
        ScriptedStore store = new ScriptedStore();
        store.addUnsequenced(event("op-a", "2026-08-01T00:00:01Z"));
        store.counterMissing = true;

        LineageFencedSequencer.RunSummary summary = sequencer(store).runOnce(REPO);

        assertEquals(SequencerHealth.COUNTER_MISSING, summary.health());
        assertEquals(0, summary.finalized());
        ScriptedStore.StoredRow row = store.rows.values().iterator().next();
        assertEquals(SequencingState.SEQUENCING, row.state(),
                "the claimed row stays SEQUENCING — the next generation reclaims it");

        ScriptedStore rewound = new ScriptedStore();
        rewound.addUnsequenced(event("op-a", "2026-08-01T00:00:01Z"));
        rewound.counter = 5L;
        rewound.watermark = 10;
        assertEquals(SequencerHealth.COUNTER_REWOUND,
                sequencer(rewound).runOnce(REPO).health());
    }

    @Test
    public void aMissingLeaseDocumentIsFailClosedNeverCreated() {
        ScriptedStore store = new ScriptedStore();
        store.leaseExists = false;
        store.addUnsequenced(event("op-a", "2026-08-01T00:00:01Z"));

        LineageFencedSequencer.RunSummary summary = sequencer(store).runOnce(REPO);

        assertEquals(SequencerHealth.LEASE_MISSING, summary.health());
        assertEquals(0, summary.finalized());
        assertFalse(store.leaseExists, "operation never creates the lease document");
    }

    @Test
    public void constructorGuardsAreExact() {
        ScriptedStore store = new ScriptedStore();
        assertThrows(IllegalArgumentException.class, () -> new LineageFencedSequencer(
                store, null, "node-a", Duration.ofMinutes(5), 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new LineageFencedSequencer(
                store, null, "node-a", Duration.ofMinutes(5), 10, 0));
    }

    /** A backlog exactly at the cap is within it; one more alerts. */
    @Test
    public void theBacklogCapBoundaryIsExact() {
        ScriptedStore store = new ScriptedStore();
        for (int i = 0; i < 3; i++) {
            store.addUnsequenced(event("op-" + i, "2026-08-01T00:00:0" + i + "Z"));
        }
        LineageMetrics metrics = new LineageMetrics();
        // batch 1 finalizes one; the remaining 2 equal the cap — no alert.
        new LineageFencedSequencer(store, metrics, "node-a", Duration.ofMinutes(5), 1, 2)
                .runOnce(REPO);
        assertEquals(0, metrics.getSequencerBacklogAlerts(),
                "a backlog of exactly the cap is within it");
    }

    /** Backlog above the cap alerts but never stops processing — stopping never recovers. */
    @Test
    public void aBacklogAboveTheCapAlertsWithoutStopping() {
        ScriptedStore store = new ScriptedStore();
        for (int i = 0; i < 3; i++) {
            store.addUnsequenced(event("op-" + i, "2026-08-01T00:00:0" + i + "Z"));
        }
        LineageMetrics metrics = new LineageMetrics();
        LineageFencedSequencer tiny = new LineageFencedSequencer(store, metrics, "node-a",
                java.time.Duration.ofMinutes(5), 1, 1);

        LineageFencedSequencer.RunSummary summary = tiny.runOnce(REPO);

        assertEquals(1, summary.finalized(), "the batch cap bounds one pass");
        assertTrue(summary.backlog() > 1);
        assertEquals(1, metrics.getSequencerBacklogAlerts());
    }

    /**
     * An infrastructure failure mid-run is not a CAS loss: re-reading forever would spin
     * against an outage, and continuing would write blind. The run latches, STOPPED.
     */
    @Test
    public void aStorageOutageMidRunLatchesInsteadOfSpinning() {
        ScriptedStore store = new ScriptedStore();
        store.addUnsequenced(event("op-a", "2026-08-01T00:00:01Z"));
        store.claimStorageFails = true;

        LineageFencedSequencer.RunSummary summary = sequencer(store).runOnce(REPO);

        assertEquals(SequencerHealth.STOPPED, summary.health());
        assertTrue(summary.lostLease(), "the latch is down for this generation");
        assertEquals(0, summary.finalized());
    }

    @Test
    public void anUnexpectedAcquireFailureIsStoppedNotLeaseMissing() {
        ScriptedStore store = new ScriptedStore();
        store.acquireThrows = true;
        store.addUnsequenced(event("op-a", "2026-08-01T00:00:01Z"));

        LineageFencedSequencer.RunSummary summary = sequencer(store).runOnce(REPO);

        assertEquals(SequencerHealth.STOPPED, summary.health(),
                "a storage failure is not the lease's absence — the two heal differently");
        assertEquals(0, summary.finalized());
        assertEquals(SequencingState.UNSEQUENCED,
                store.rows.values().iterator().next().state());
    }

    /** A stale grant (renewed since) must not release the newer hold — _rev is in the CAS. */
    @Test
    public void aStaleGrantCannotReleaseTheRenewedLease() {
        ScriptedStore store = new ScriptedStore();
        var first = store.acquireSequencerLease(REPO, "node-a", Duration.ofMinutes(5))
                .orElseThrow();
        var renewed = store.renewSequencerLease(first, Duration.ofMinutes(5)).orElseThrow();
        store.releaseSequencerLease(first); // stale rev
        assertEquals("node-a", store.leaseOwner,
                "the stale grant's release must lose the _rev CAS");
        store.releaseSequencerLease(renewed);
        assertNull(store.leaseOwner, "the current grant releases normally");
    }

    @Test
    public void aHeldLeaseMeansNoWorkAndNoInterference() {
        ScriptedStore store = new ScriptedStore();
        store.leaseGeneration = 3;
        store.leaseToken = "token-3";
        store.leaseOwner = "other-node";
        store.leaseExpiresAt = Instant.now().plus(Duration.ofMinutes(5));
        store.addUnsequenced(event("op-a", "2026-08-01T00:00:01Z"));

        LineageFencedSequencer.RunSummary summary = sequencer(store).runOnce(REPO);

        assertEquals(0, summary.finalized());
        assertEquals(SequencerHealth.FENCED_OK, summary.health());
        assertEquals("other-node", store.leaseOwner);
        assertEquals(SequencingState.UNSEQUENCED,
                store.rows.values().iterator().next().state());
    }

    // ------------------------------------------------------------------ envelope strictness

    @Nested
    class EnvelopeStrictness {

        private Map<String, Object> rawRow() {
            Map<String, Object> doc = CouchLineageEventV2.toMap(
                    event("op-raw", "2026-08-01T00:00:01Z"), "1-abc");
            doc.put("state", "UNSEQUENCED");
            return doc;
        }

        @Test
        public void aWellFormedRowDecodesWithItsState() {
            LineageJournalRowV2 row = CouchLineageJournalRowV2.fromRaw(rawRow());
            assertEquals(SequencingState.UNSEQUENCED, row.state());
            assertEquals("1-abc", row.rev());
        }

        @Test
        public void missingOrUnknownStatesAreMalformedNotLegacy() {
            Map<String, Object> noState = rawRow();
            noState.remove("state");
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(noState));

            Map<String, Object> unknown = rawRow();
            unknown.put("state", "HALF_SEQUENCED");
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(unknown));
        }

        @Test
        public void mutableFieldTypesAreEnforcedNotSkipped() {
            Map<String, Object> garbageGeneration = rawRow();
            garbageGeneration.put("sequencerGeneration", "not-a-number");
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(garbageGeneration),
                    "a non-numeric generation is corruption, not absence");

            Map<String, Object> garbageToken = rawRow();
            garbageToken.put("sequencerLeaseToken", 12345);
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(garbageToken));

            Map<String, Object> blankToken = rawRow();
            blankToken.put("sequencerLeaseToken", "  ");
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(blankToken));
        }

        @Test
        public void stateDependentRequirementsAreEnforced() {
            Map<String, Object> sequencingWithoutFence = rawRow();
            sequencingWithoutFence.put("state", "SEQUENCING");
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(sequencingWithoutFence),
                    "SEQUENCING without fencing coordinates cannot exist as a value");

            Map<String, Object> unsequencedWithFence = rawRow();
            unsequencedWithFence.put("sequencerGeneration", 3L);
            unsequencedWithFence.put("sequencerLeaseToken", "token-3");
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(unsequencedWithFence));

            Map<String, Object> sequencedWithoutSequence = rawRow();
            sequencedWithoutSequence.put("state", "SEQUENCED");
            sequencedWithoutSequence.put("sequencerGeneration", 3L);
            sequencedWithoutSequence.put("sequencerLeaseToken", "token-3");
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(sequencedWithoutSequence),
                    "SEQUENCED with sequenceNumber 0 contradicts finalize's single CAS");
        }
    }
}
