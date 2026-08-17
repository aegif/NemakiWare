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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §8-d executor and crash recovery (D-rest-3): reread-driven convergence, deterministic
 * compensation, collision handling, and the recovery matrix.
 */
public class LineageReplayServiceTest {

    private static final String TARGET = "atlas";

    private LineageReplayService service;
    private LineageJournalStore store; // implements transition + replay interfaces
    private LineageV2TransitionStore v2store;
    private LineageV2ReplayStore replayStore;
    private LineageDrestReadiness readiness;
    private LineageConfig config;
    private LineageMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        service = new LineageReplayService();
        store = mock(LineageJournalStore.class, withSettings()
                .extraInterfaces(LineageV2TransitionStore.class, LineageV2ReplayStore.class));
        v2store = (LineageV2TransitionStore) store;
        replayStore = (LineageV2ReplayStore) store;
        readiness = mock(LineageDrestReadiness.class);
        config = mock(LineageConfig.class);
        when(readiness.evaluate()).thenReturn(
                new LineageDrestReadiness.Readiness(true, List.of()));
        when(config.getTargets()).thenReturn(List.of(TARGET));
        metrics = mock(LineageMetrics.class);
        set("journalStore", store);
        set("readiness", readiness);
        set("lineageConfig", config);
        set("lineageMetrics", metrics);
    }

    private void set(String field, Object value) throws Exception {
        Field f = LineageReplayService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static LineageJournalRowV2 rowWith(LineageReplayRequest request) {
        LineageEventV2 event = new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt("2026-08-01T00:00:00Z")
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of(TARGET)))
                .addInput(LineageEndpoint.document("bedroom", "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L))
                .sequenceNumber(7L)
                .build();
        Map<String, LineageTargetLifecycle> lifecycles = Map.of(TARGET,
                new LineageTargetLifecycle(LineagePublishStatus.PUBLISHED, "tok", 1000L, null,
                        1500L, 0L, null));
        Map<String, LineageReplayRequest> requests = request == null ? Map.of()
                : Map.of(TARGET, request);
        return new LineageJournalRowV2(event, "3-abc",
                LineageJournalRowV2.SequencingState.SEQUENCED, 1L, "seq-tok", lifecycles,
                requests);
    }

    private static LineageReplayRequest request(LineageReplayRequest.State state) {
        return new LineageReplayRequest(state, 1L, "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", 1000L, 1500L,
                state == LineageReplayRequest.State.FAILED
                        ? new LineageTargetLifecycle.TerminalReason("R", "d", 1L) : null);
    }

    // ---------------------------------------------------------------- determinism

    @Test
    public void theCompensationIsAPureFunctionOfOriginalTargetAndGeneration() {
        LineageJournalRowV2 row = rowWith(null);
        LineageEventV2 a = LineageReplayService.compensationOf(row, TARGET, 1L);
        LineageEventV2 b = LineageReplayService.compensationOf(row, TARGET, 1L);
        assertEquals(a.deliveryId(), b.deliveryId(), "same inputs, same identity");
        assertEquals(a.creationPayloadDigest(), b.creationPayloadDigest(),
                "same inputs, same digest — crash retries converge");
        LineageEventV2 c = LineageReplayService.compensationOf(row, TARGET, 2L);
        assertNotEquals(a.deliveryId(), c.deliveryId(), "a new generation is a new identity");

        assertEquals(row.event().eventId(), a.eventId(), "audit event id is shared");
        assertEquals(row.event().occurredAt(), a.occurredAt(), "no clock read");
        assertEquals(Map.of(TARGET, LineagePublishStatus.PENDING), a.publishStatusByTarget(),
                "exactly the requested target, PENDING");
        assertEquals(0L, a.sequenceNumber(), "the fenced sequencer assigns the sequence");
        assertEquals(null, a.spoolRecordId(),
                "spoolRecordId is NOT copied — this delivery was not materialized from that"
                        + " fact");
    }

    // ---------------------------------------------------------------- execute paths

    @Test
    public void aRedGateRefusesAsDataBeforeTouchingAnything() {
        when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(false,
                List.of("lineage.drest.enabled is false")));
        var outcome = service.execute("rec", TARGET);
        assertEquals("NOT_READY", outcome.state());
        verify(replayStore, never()).requestReplay(anyString(), anyString());
    }

    @Test
    public void aRefusalFromTheStoreBecomesA409ShapedOutcome() {
        when(replayStore.requestReplay("rec", TARGET)).thenThrow(
                new LineageV2ReplayStore.ReplayRefusedException("live claim"));
        var outcome = service.execute("rec", TARGET);
        assertEquals("REFUSED", outcome.state());
        assertTrue(outcome.message().contains("live claim"));
    }

    @Test
    public void theHappyPathDrivesToObservedAckedThroughRereads() {
        LineageJournalRowV2 requested = rowWith(request(LineageReplayRequest.State.REQUESTED));
        LineageJournalRowV2 created = rowWith(request(LineageReplayRequest.State.CREATED));
        LineageJournalRowV2 acked = rowWith(request(LineageReplayRequest.State.ACKED));
        String recordId = requested.event().deliveryId();
        when(replayStore.requestReplay(recordId, TARGET)).thenReturn(
                new LineageV2ReplayStore.ReplayGrant(recordId, TARGET, 1L, "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"));
        when(v2store.findV2ByRecordId(recordId)).thenReturn(requested, created, acked);
        when(replayStore.advanceReplay(eq(recordId), eq(TARGET), eq("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"),
                eq(LineageReplayRequest.State.REQUESTED),
                eq(LineageReplayRequest.State.CREATED))).thenReturn(true);
        when(replayStore.advanceReplay(eq(recordId), eq(TARGET), eq("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"),
                eq(LineageReplayRequest.State.CREATED),
                eq(LineageReplayRequest.State.ACKED))).thenReturn(true);

        var outcome = service.execute(recordId, TARGET);
        assertEquals("ACKED", outcome.state(), "ACKED is reported only after being OBSERVED");
        assertEquals(1L, outcome.generation());
        // BOTH unacked encounters re-establish the compensation (step-3 resume rule):
        verify(store, org.mockito.Mockito.times(2)).appendV2(any());
        verify(metrics).recordReplayAcked(TARGET); // counted where OUR CAS landed the ACK
    }

    @Test
    public void aNewerFenceYieldsInsteadOfFighting() {
        LineageJournalRowV2 stolen = rowWith(new LineageReplayRequest(
                LineageReplayRequest.State.REQUESTED, 2L, "6c84fb90-12c4-11e1-840d-7b25c5ee775a", 2000L, 2000L, null));
        String recordId = stolen.event().deliveryId();
        when(replayStore.requestReplay(recordId, TARGET)).thenReturn(
                new LineageV2ReplayStore.ReplayGrant(recordId, TARGET, 1L, "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"));
        when(v2store.findV2ByRecordId(recordId)).thenReturn(stolen);
        var outcome = service.execute(recordId, TARGET);
        assertEquals("REFUSED", outcome.state());
        assertTrue(outcome.message().contains("newer request fence"));
        verify(store, never()).appendV2(any());
    }

    @Test
    public void aCollisionConvergesToObservedDurableFailedBeforeThe500Shape() {
        LineageJournalRowV2 requested = rowWith(request(LineageReplayRequest.State.REQUESTED));
        LineageJournalRowV2 failed = rowWith(request(LineageReplayRequest.State.FAILED));
        String recordId = requested.event().deliveryId();
        when(replayStore.requestReplay(recordId, TARGET)).thenReturn(
                new LineageV2ReplayStore.ReplayGrant(recordId, TARGET, 1L, "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"));
        when(v2store.findV2ByRecordId(recordId)).thenReturn(requested, failed);
        org.mockito.Mockito.doThrow(new LineageIntegrityException("doc-1", "stored-digest", "occupant digest differs"))
                .when(store).appendV2(any());
        when(replayStore.failReplay(eq(recordId), eq(TARGET), eq("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"), any()))
                .thenReturn(true);

        var outcome = service.execute(recordId, TARGET);
        assertEquals("FAILED", outcome.state());
        verify(replayStore).failReplay(eq(recordId), eq(TARGET), eq("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"), any());
    }

    @Test
    public void conflictBudgetExhaustionReportsIndeterminateNeverSuccess() {
        LineageJournalRowV2 created = rowWith(request(LineageReplayRequest.State.CREATED));
        String recordId = created.event().deliveryId();
        when(replayStore.requestReplay(recordId, TARGET)).thenReturn(
                new LineageV2ReplayStore.ReplayGrant(recordId, TARGET, 1L, "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"));
        when(v2store.findV2ByRecordId(recordId)).thenReturn(created);
        when(replayStore.advanceReplay(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(false); // CAS conflict forever

        var outcome = service.execute(recordId, TARGET);
        assertEquals("INDETERMINATE", outcome.state(),
                "success is never fabricated from intent");
    }

    // ---------------------------------------------------------------- recovery matrix

    @Test
    public void recoveryResumesEveryCrashPointToObservedAcked() {
        // Crash point 1: REQUESTED (compensation may or may not exist — appendV2 idempotent).
        LineageJournalRowV2 requested = rowWith(request(LineageReplayRequest.State.REQUESTED));
        LineageJournalRowV2 created = rowWith(request(LineageReplayRequest.State.CREATED));
        LineageJournalRowV2 acked = rowWith(request(LineageReplayRequest.State.ACKED));
        String recordId = requested.event().deliveryId();
        when(replayStore.findUnackedReplayRequests(51)).thenReturn(List.of(
                new LineageV2ReplayStore.ReplayRecovery(recordId, TARGET,
                        request(LineageReplayRequest.State.REQUESTED))));
        when(v2store.findV2ByRecordId(recordId)).thenReturn(requested, created, acked);
        when(replayStore.advanceReplay(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);

        assertEquals(1, service.recoverUnacked(50));
        verify(store, org.mockito.Mockito.times(2)).appendV2(any()); // both unacked states re-derive
    }

    /** Crash point CREATED: recovery re-establishes the compensation BEFORE acking. */
    @Test
    public void recoveryFromCreatedReEstablishesTheCompensationBeforeAcking() {
        LineageJournalRowV2 created = rowWith(request(LineageReplayRequest.State.CREATED));
        LineageJournalRowV2 acked = rowWith(request(LineageReplayRequest.State.ACKED));
        String recordId = created.event().deliveryId();
        when(replayStore.findUnackedReplayRequests(51)).thenReturn(List.of(
                new LineageV2ReplayStore.ReplayRecovery(recordId, TARGET,
                        request(LineageReplayRequest.State.CREATED))));
        when(v2store.findV2ByRecordId(recordId)).thenReturn(created, acked);
        when(replayStore.advanceReplay(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);

        assertEquals(1, service.recoverUnacked(50));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(store, replayStore);
        order.verify(store).appendV2(any());
        order.verify(replayStore).advanceReplay(eq(recordId), eq(TARGET), eq("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"),
                eq(LineageReplayRequest.State.CREATED), eq(LineageReplayRequest.State.ACKED));
    }

    /** Collision met DURING recovery converges the request to observed durable FAILED. */
    @Test
    public void aCollisionDuringRecoveryConvergesToDurableFailed() {
        LineageJournalRowV2 created = rowWith(request(LineageReplayRequest.State.CREATED));
        LineageJournalRowV2 failed = rowWith(request(LineageReplayRequest.State.FAILED));
        String recordId = created.event().deliveryId();
        when(replayStore.findUnackedReplayRequests(51)).thenReturn(List.of(
                new LineageV2ReplayStore.ReplayRecovery(recordId, TARGET,
                        request(LineageReplayRequest.State.CREATED))));
        when(v2store.findV2ByRecordId(recordId)).thenReturn(created, failed);
        org.mockito.Mockito.doThrow(new LineageIntegrityException("doc-1", "stored",
                "occupant digest differs")).when(store).appendV2(any());
        when(replayStore.failReplay(anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        assertEquals(0, service.recoverUnacked(50), "a collision is not a recovery");
        verify(replayStore).failReplay(eq(recordId), eq(TARGET), eq("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"), any());
    }

    /** A lost CAS is "reread and look again" — the next observation drives progress. */
    @Test
    public void aLostAdvanceCasRereadsAndProgresses() {
        LineageJournalRowV2 requested = rowWith(request(LineageReplayRequest.State.REQUESTED));
        LineageJournalRowV2 created = rowWith(request(LineageReplayRequest.State.CREATED));
        LineageJournalRowV2 acked = rowWith(request(LineageReplayRequest.State.ACKED));
        String recordId = requested.event().deliveryId();
        when(replayStore.requestReplay(recordId, TARGET)).thenReturn(
                new LineageV2ReplayStore.ReplayGrant(recordId, TARGET, 1L, "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"));
        // First advance loses its CAS; the reread sees CREATED anyway (someone's write
        // landed); the second advance succeeds.
        when(v2store.findV2ByRecordId(recordId)).thenReturn(requested, created, acked);
        when(replayStore.advanceReplay(eq(recordId), eq(TARGET), eq("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"),
                eq(LineageReplayRequest.State.REQUESTED),
                eq(LineageReplayRequest.State.CREATED))).thenReturn(false);
        when(replayStore.advanceReplay(eq(recordId), eq(TARGET), eq("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"),
                eq(LineageReplayRequest.State.CREATED),
                eq(LineageReplayRequest.State.ACKED))).thenReturn(true);

        assertEquals("ACKED", service.execute(recordId, TARGET).state());
    }

    /** A5: one original with two active targets is TWO recovery items. */
    @Test
    public void multiTargetRequestsRecoverIndependently() {
        LineageJournalRowV2 acked = rowWith(request(LineageReplayRequest.State.ACKED));
        String recordId = acked.event().deliveryId();
        when(config.getTargets()).thenReturn(List.of(TARGET, "dataplex"));
        when(replayStore.findUnackedReplayRequests(51)).thenReturn(List.of(
                new LineageV2ReplayStore.ReplayRecovery(recordId, TARGET,
                        request(LineageReplayRequest.State.REQUESTED)),
                new LineageV2ReplayStore.ReplayRecovery(recordId, "dataplex",
                        request(LineageReplayRequest.State.REQUESTED))));
        // Both drives observe ACKED immediately (converged elsewhere) — still two items.
        when(v2store.findV2ByRecordId(recordId)).thenReturn(acked);
        LineageJournalRowV2 ackedBoth = rowWithTwoTargets();
        when(v2store.findV2ByRecordId(recordId)).thenReturn(ackedBoth);

        assertEquals(2, service.recoverUnacked(50));
    }

    private static LineageJournalRowV2 rowWithTwoTargets() {
        LineageEventV2 event = new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt("2026-08-01T00:00:00Z")
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of(TARGET, "dataplex")))
                .addInput(LineageEndpoint.document("bedroom", "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L))
                .sequenceNumber(7L)
                .build();
        Map<String, LineageTargetLifecycle> lifecycles = Map.of(
                TARGET, new LineageTargetLifecycle(LineagePublishStatus.PUBLISHED, "tok",
                        1000L, null, 1500L, 0L, null),
                "dataplex", new LineageTargetLifecycle(LineagePublishStatus.PUBLISHED, "tok",
                        1000L, null, 1500L, 0L, null));
        Map<String, LineageReplayRequest> requests = Map.of(
                TARGET, request(LineageReplayRequest.State.ACKED),
                "dataplex", request(LineageReplayRequest.State.ACKED));
        return new LineageJournalRowV2(event, "3-abc",
                LineageJournalRowV2.SequencingState.SEQUENCED, 1L, "seq-tok", lifecycles,
                requests);
    }

    /** The limit+1 probe's boundary is exact: exactly-limit is NOT moreRemaining. */
    @Test
    public void moreRemainingBoundaryIsExact() {
        LineageJournalRowV2 acked = rowWith(request(LineageReplayRequest.State.ACKED));
        String recordId = acked.event().deliveryId();
        var item = new LineageV2ReplayStore.ReplayRecovery(recordId, TARGET,
                request(LineageReplayRequest.State.REQUESTED));
        when(v2store.findV2ByRecordId(recordId)).thenReturn(acked);

        when(replayStore.findUnackedReplayRequests(3)).thenReturn(List.of(item, item));
        var exact = service.recoverUnackedOutcome(2);
        assertEquals(false, exact.moreRemaining(), "exactly limit visible = nothing beyond");
        assertEquals(2, exact.recovered());

        when(replayStore.findUnackedReplayRequests(3)).thenReturn(List.of(item, item, item));
        var over = service.recoverUnackedOutcome(2);
        assertEquals(true, over.moreRemaining(), "limit+1 visible = more beyond");
        assertEquals(2, over.recovered(), "processing still caps at limit");
    }

    @Test
    public void recoveryIsFullyDormantUnderARedGate() {
        when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(false,
                List.of("lineage.drest.enabled is false")));
        assertEquals(0, service.recoverUnacked(50));
        verify(replayStore, never()).findUnackedReplayRequests(org.mockito.ArgumentMatchers
                .anyInt());
    }

    @Test
    public void aStrandedTargetIsRefusedLoudlyAndLeftRequested() {
        when(config.getTargets()).thenReturn(List.of()); // the only target was removed
        LineageJournalRowV2 requested = rowWith(request(LineageReplayRequest.State.REQUESTED));
        String recordId = requested.event().deliveryId();
        when(replayStore.findUnackedReplayRequests(51)).thenReturn(List.of(
                new LineageV2ReplayStore.ReplayRecovery(recordId, TARGET,
                        request(LineageReplayRequest.State.REQUESTED))));
        when(v2store.findV2ByRecordId(recordId)).thenReturn(requested);

        assertEquals(0, service.recoverUnacked(50), "not recovered — refused");
        verify(store, never()).appendV2(any());
        verify(replayStore, never()).failReplay(anyString(), anyString(), anyString(), any());
    }
}
