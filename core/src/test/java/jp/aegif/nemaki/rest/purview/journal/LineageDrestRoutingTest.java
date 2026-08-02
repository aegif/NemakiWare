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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * D-rest-2 routing (v2.3.19): readiness OFF keeps the byte-identical legacy walk on a v1-only
 * stream; readiness ON runs the merged walk with the token-fenced v2 machine, uniform
 * zero-means-stop, and the monotonic cursor.
 */
public class LineageDrestRoutingTest {

    private static final String TARGET = "purview";
    private static final String REPO = "bedroom";

    private LineageProjectionLoop loop;
    private LineageConfig config;
    private LineageJournalStore store; // mock implementing BOTH interfaces
    private LineageV2TransitionStore v2store;
    private LineageTargetSink sink;
    private ProjectionCursorStore cursorStore;
    private LineageDrestReadiness readiness;
    private LineageReplayService replayService;

    @BeforeEach
    void setUp() throws Exception {
        loop = new LineageProjectionLoop();
        config = mock(LineageConfig.class);
        store = mock(LineageJournalStore.class, withSettings()
                .extraInterfaces(LineageV2TransitionStore.class));
        v2store = (LineageV2TransitionStore) store;
        sink = mock(LineageTargetSink.class);
        cursorStore = mock(ProjectionCursorStore.class);
        readiness = mock(LineageDrestReadiness.class);
        replayService = mock(LineageReplayService.class);

        when(config.getTargets()).thenReturn(List.of(TARGET));
        when(config.getProjectionBatchSize()).thenReturn(50);
        when(config.getProjectionPollIntervalSeconds()).thenReturn(10);
        when(config.getProjectionStaleThresholdMinutes()).thenReturn(5);
        when(config.getBacklogMaxRetryAgeHours()).thenReturn(0);
        when(config.getBacklogMaxDocs()).thenReturn(0);
        when(config.getBacklogMaxSizeMb()).thenReturn(0);
        when(config.getProjectionClaimLeaseSeconds()).thenReturn(120);
        when(config.getVerifyTimeoutSeconds()).thenReturn(30);
        when(config.getVerifyIntervalSeconds()).thenReturn(1);
        when(config.getVerifyMaxAgeMinutes()).thenReturn(10);

        when(store.isActive()).thenReturn(true);
        when(sink.targetName()).thenReturn(TARGET);
        when(sink.isAvailable()).thenReturn(true);
        when(cursorStore.isActive()).thenReturn(true);
        when(cursorStore.getAllCursors()).thenReturn(List.of());
        when(cursorStore.getCursor(anyString(), anyString())).thenReturn(null);
        when(store.findDistinctNonTerminalRepositoryIds(TARGET)).thenReturn(List.of(REPO));

        setField(loop, "lineageConfig", config);
        setField(loop, "journalStore", store);
        setField(loop, "targetSinks", List.of(sink));
        setField(loop, "cursorStore", cursorStore);
        setField(loop, "drestReadiness", readiness);
        setField(loop, "replayService", replayService);
    }

    private static void setField(Object target, String fieldName, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void readinessIs(boolean ready) {
        when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(ready,
                ready ? List.of() : List.of("lineage.drest.enabled is false")));
    }

    private static LineageJournalRowV2 sequencedRow(long seq, String status,
                                                    Map<String, Object> claim) {
        LineageEventV2 event = new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-55555555555" + (seq % 10))
                .occurredAt("2026-08-01T00:00:0" + (seq % 10) + "Z")
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-" + seq)
                .delivery(new LineageDelivery.Original(List.of(TARGET)))
                .addInput(LineageEndpoint.document(REPO, "doc-" + seq, "a.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "arc-" + seq, "doc-" + seq, 1L))
                .sequenceNumber(seq)
                .build();
        Map<String, Object> doc = new java.util.LinkedHashMap<>(CouchLineageEventV2.toMap(event));
        doc.put("_rev", "3-abc");
        doc.put("state", "SEQUENCED");
        doc.put("sequencerGeneration", 1L);
        doc.put("sequencerLeaseToken", "seq-tok");
        if (status != null) {
            doc.put("publishStatusByTarget", Map.of(TARGET, status));
        }
        if (claim != null) {
            doc.put("v2ClaimByTarget", Map.of(TARGET, claim));
        }
        return CouchLineageJournalRowV2.fromRaw(doc);
    }

    /**
     * B1 (v2.3.20): recovery runs once per poll BEFORE the empty-target early return — a
     * stranded request whose only target was removed is still visited every poll. The
     * service itself is readiness-gated, so a red gate stays fully dormant inside it.
     */
    @Test
    public void replayRecoveryRunsEvenWhenNoTargetsAreConfigured() {
        readinessIs(false);
        when(config.getTargets()).thenReturn(List.of()); // early-return path
        loop.pollAndProject();
        verify(replayService).recoverUnacked(org.mockito.ArgumentMatchers.anyInt());
    }

    // ---------------------------------------------------------------- readiness OFF

    @Test
    public void readinessOffRunsTheLegacyWalkAndNeverTouchesTheV2Surface() {
        readinessIs(false);
        when(store.findByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of());

        loop.pollAndProject();

        verify(v2store, never()).findV2ByRepositoryAndSequenceRange(anyString(), anyLong(),
                anyInt());
        verify(v2store, never()).reapExpiredClaims(anyString(), any());
        verify(v2store, never()).claimForProjection(anyString(), anyString(), any());
        verify(cursorStore, never()).advanceCursorMonotonic(any());
    }

    // ---------------------------------------------------------------- readiness ON

    @Test
    public void theHappyPathClaimsVerifiesPublishesAndAdvancesTheMonotonicCursor()
            throws Exception {
        readinessIs(true);
        when(v2store.reapExpiredClaims(eq(TARGET), any())).thenReturn(0);
        when(v2store.findV2NonTerminalRepositoryIds(TARGET)).thenReturn(List.of(REPO));
        when(store.findByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of());
        LineageJournalRowV2 row = sequencedRow(7L, "PENDING", null);
        when(v2store.findV2ByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of(row));
        String recordId = row.event().deliveryId();
        when(v2store.claimForProjection(eq(recordId), eq(TARGET), any()))
                .thenReturn(new LineageV2TransitionStore.V2ClaimGrant(recordId, TARGET,
                        "tok-9", java.time.Instant.now().plus(Duration.ofSeconds(120))));
        when(sink.publish(any())).thenReturn(new LineageTargetSinkResult(true, 2, "ok"));
        when(v2store.transitionV2(eq(recordId), eq(TARGET),
                eq(LineagePublishStatus.PROJECTING), eq(LineagePublishStatus.VERIFYING),
                eq("tok-9"), any())).thenReturn(true);
        when(v2store.renewClaim(eq(recordId), eq(TARGET), eq("tok-9"), any()))
                .thenReturn(true);
        // The reread the verify loop does before each attempt: a live owned VERIFYING row.
        when(v2store.findV2ByRecordId(recordId)).thenReturn(sequencedRow(7L, "VERIFYING",
                Map.of("token", "tok-9", "claimedAtMs", 1000L,
                        "leaseExpiresAtMs", System.currentTimeMillis() + 60_000L,
                        "verifyingSinceMs", System.currentTimeMillis(), "retryCount", 0L)));
        when(sink.verify(any(), any())).thenReturn(LineageTargetSink.VerifyResult.VERIFIED);
        when(v2store.transitionV2(eq(recordId), eq(TARGET),
                eq(LineagePublishStatus.VERIFYING), eq(LineagePublishStatus.PUBLISHED),
                eq("tok-9"), any())).thenReturn(true);
        when(cursorStore.advanceCursorMonotonic(any())).thenReturn(true);

        loop.pollAndProject();

        ArgumentCaptor<ProjectionCursor> cursor = ArgumentCaptor.forClass(ProjectionCursor.class);
        verify(cursorStore).advanceCursorMonotonic(cursor.capture());
        assertEquals(7L, cursor.getValue().lastProcessedSequence());
        verify(store, never()).updatePublishStatus(anyString(), anyString(), any());
    }

    @Test
    public void aFailedTransitionMeansNoCursorAdvance() throws Exception {
        readinessIs(true);
        when(v2store.reapExpiredClaims(eq(TARGET), any())).thenReturn(0);
        when(v2store.findV2NonTerminalRepositoryIds(TARGET)).thenReturn(List.of(REPO));
        when(store.findByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of());
        LineageJournalRowV2 row = sequencedRow(7L, "PENDING", null);
        when(v2store.findV2ByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of(row));
        String recordId = row.event().deliveryId();
        when(v2store.claimForProjection(eq(recordId), eq(TARGET), any()))
                .thenReturn(new LineageV2TransitionStore.V2ClaimGrant(recordId, TARGET,
                        "tok-9", java.time.Instant.now().plus(Duration.ofSeconds(120))));
        when(sink.publish(any())).thenReturn(new LineageTargetSinkResult(true, 2, "ok"));
        when(v2store.transitionV2(eq(recordId), eq(TARGET),
                eq(LineagePublishStatus.PROJECTING), eq(LineagePublishStatus.VERIFYING),
                eq("tok-9"), any())).thenReturn(false); // claim died under us

        loop.pollAndProject();

        verify(cursorStore, never()).advanceCursorMonotonic(any());
    }

    @Test
    public void aForeignLiveClaimHaltsTheRepositoryWithoutTouchingIt() {
        readinessIs(true);
        when(v2store.reapExpiredClaims(eq(TARGET), any())).thenReturn(0);
        when(v2store.findV2NonTerminalRepositoryIds(TARGET)).thenReturn(List.of(REPO));
        when(store.findByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of());
        // A live foreign claim (token not in this loop's registry).
        LineageJournalRowV2 row = sequencedRow(7L, "PROJECTING",
                Map.of("token", "foreign-tok", "claimedAtMs", 1000L,
                        "leaseExpiresAtMs", System.currentTimeMillis() + 60_000L,
                        "retryCount", 0L));
        when(v2store.findV2ByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of(row));

        loop.pollAndProject();

        verify(v2store, never()).transitionV2(anyString(), anyString(), any(), any(),
                anyString(), any());
        verify(v2store, never()).claimForProjection(anyString(), anyString(), any());
        verify(cursorStore, never()).advanceCursorMonotonic(any());
    }

    @Test
    public void nothingPastTheHaltRowIsProcessed() throws Exception {
        readinessIs(true);
        when(v2store.reapExpiredClaims(eq(TARGET), any())).thenReturn(0);
        when(v2store.findV2NonTerminalRepositoryIds(TARGET)).thenReturn(List.of(REPO));
        // v2 row at seq 7 (foreign live claim = halt), v2 row at seq 9 PENDING behind it.
        LineageJournalRowV2 halted = sequencedRow(7L, "PROJECTING",
                Map.of("token", "foreign-tok", "claimedAtMs", 1000L,
                        "leaseExpiresAtMs", System.currentTimeMillis() + 60_000L,
                        "retryCount", 0L));
        LineageJournalRowV2 behind = sequencedRow(9L, "PENDING", null);
        when(v2store.findV2ByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of(halted, behind));
        when(store.findByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of());

        loop.pollAndProject();

        // The PENDING row at the greater sequence was never claimed: round-1 (d) made exact.
        verify(v2store, never()).claimForProjection(anyString(), anyString(), any());
    }

    @Test
    public void theV1PublishedPersistIsConfirmedBeforeTheCursorMovesUnderTheSwitch()
            throws Exception {
        readinessIs(true);
        when(v2store.reapExpiredClaims(eq(TARGET), any())).thenReturn(0);
        when(v2store.findV2NonTerminalRepositoryIds(TARGET)).thenReturn(List.of());
        when(v2store.findV2ByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of());

        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .addOutput("nemaki://bedroom/archives/doc-1")
                .targets(List.of(TARGET))
                .build();
        LineageJournalRow row = new LineageJournalRow.Decoded(LineageJournalEntry.ofV1(event));
        when(store.findByRepositoryAndSequenceRange(eq(REPO), anyLong(), anyInt()))
                .thenReturn(List.of(row));
        when(store.updatePublishStatus(anyString(), eq(TARGET),
                eq(LineagePublishStatus.PROJECTING))).thenReturn(1);
        when(sink.publish(any())).thenReturn(new LineageTargetSinkResult(true, 1, "ok"));
        // B4: the PUBLISHED persist reports 0 — the cursor must NOT advance.
        when(store.updatePublishStatus(anyString(), eq(TARGET),
                eq(LineagePublishStatus.PUBLISHED))).thenReturn(0);

        loop.pollAndProject();

        verify(cursorStore, never()).advanceCursorMonotonic(any());
        verify(cursorStore, never()).updateCursor(any());
    }
}
