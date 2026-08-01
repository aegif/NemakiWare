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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The {@code read:v2} evidence: a synthetic v2 row traverses codec → store row → projector claim
 * → sink publish → status mutation, with every store call carrying the <b>deliveryId</b>.
 *
 * <p>§6-a's capability is a statement about a working path, not about classes existing. Nothing
 * writes v2 yet, so the row here is synthesised through the real codec ({@code toMap} → {@code
 * decodeRow}) — the same bytes-to-entry path a stored document will take — and the loop under
 * test is the production loop with only its collaborators mocked.
 *
 * <p>The failure half matters as much: a v2 row that fails to publish must stay FAILED. The
 * dead-letter sink records v1 envelopes only, DISCARDED is terminal, and {@code purgeOlderThan}
 * deletes terminal rows — so DISCARD-without-dead-letter would eventually delete the only
 * lossless copy. That arithmetic is pinned here per failure path (publish, age, overflow).
 */
public class LineageV2ReadPathAcceptanceTest {

    private LineageProjectionLoop loop;
    private LineageJournalStore store;
    private LineageTargetSink sink;
    private LineageDeadLetterStore deadLetters;
    private LineageConfig config;

    @BeforeEach
    void setUp() throws Exception {
        loop = new LineageProjectionLoop();
        store = mock(LineageJournalStore.class);
        sink = mock(LineageTargetSink.class);
        deadLetters = mock(LineageDeadLetterStore.class);
        config = mock(LineageConfig.class);

        when(store.isActive()).thenReturn(true);
        when(config.getTargets()).thenReturn(List.of("purview"));
        when(config.getProjectionBatchSize()).thenReturn(50);
        when(config.getBacklogMaxRetryCount()).thenReturn(3);
        when(sink.targetName()).thenReturn("purview");
        when(sink.isAvailable()).thenReturn(true);
        when(store.findByTargetAndStatus(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        set("lineageConfig", config);
        set("journalStore", store);
        set("targetSinks", List.of(sink));
        LineageDeadLetterSink.setStore(deadLetters);
    }

    private void set(String field, Object value) throws Exception {
        Field f = LineageProjectionLoop.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(loop, value);
    }

    /** A v2 row as the store would yield it: through the real codec, not hand-assembled. */
    private static LineageJournalRow v2Row() {
        LineageEventV2 event = new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt("2026-08-01T00:00:00Z")
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document("bedroom", "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L))
                .build();
        return LineageEventCodec.decodeRow(CouchLineageEventV2.toMap(event));
    }

    private static LineageEventV2 eventOf(LineageJournalRow row) {
        return ((LineageJournalEntry.V2)
                ((LineageJournalRow.Decoded) row).entry().envelope()).event();
    }

    // ------------------------------------------------------------------ the working path

    @Test
    public void aV2RowIsClaimedPublishedAndCompletedByItsDeliveryId() throws Exception {
        LineageJournalRow row = v2Row();
        LineageEventV2 event = eventOf(row);
        assertNotEquals(event.eventId(), event.deliveryId(),
                "precondition: for v2 the record id is NOT the audit event id, which is what"
                        + " makes this test able to tell the two apart");

        when(store.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(row));
        when(store.updatePublishStatus(event.deliveryId(), "purview",
                LineagePublishStatus.PROJECTING)).thenReturn(1);
        when(sink.publish(any())).thenReturn(LineageTargetSinkResult.success(1, "OK"));

        loop.pollAndProject();

        // Claimed and completed under the deliveryId — never the audit eventId.
        verify(store).updatePublishStatus(event.deliveryId(), "purview",
                LineagePublishStatus.PROJECTING);
        verify(store).updatePublishStatus(event.deliveryId(), "purview",
                LineagePublishStatus.PUBLISHED);
        verify(store, never()).updatePublishStatus(eq(event.eventId()), anyString(), any());

        // The sink received the projection of this event, identity intact.
        ArgumentCaptor<LineageRecord> published = ArgumentCaptor.forClass(LineageRecord.class);
        verify(sink).publish(published.capture());
        assertEquals(event.processKey(), published.getValue().processIdentity());
        assertEquals(event.deliveryId(), published.getValue().recordId());
        assertEquals(2, published.getValue().schemaVersion());
    }

    // ------------------------------------------------------------------ the failure arithmetic

    @Test
    public void aV2PublishFailureStaysFailedAndTouchesNoDeadLetter() throws Exception {
        LineageJournalRow row = v2Row();
        LineageEventV2 event = eventOf(row);

        when(store.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(row));
        when(store.updatePublishStatus(event.deliveryId(), "purview",
                LineagePublishStatus.PROJECTING)).thenReturn(1);
        when(sink.publish(any())).thenReturn(LineageTargetSinkResult.failure("boom"));

        loop.pollAndProject();

        verify(store).updatePublishStatus(event.deliveryId(), "purview",
                LineagePublishStatus.FAILED);
        // No dead letter (it records v1 envelopes only), no retry-count check, and above all no
        // DISCARDED — terminal means purge-eligible, and this row's document is the only copy.
        verify(deadLetters, never()).record(any(), anyString());
        verify(store, never()).getRetryCount(anyString(), anyString());
        verify(store, never()).updatePublishStatus(anyString(), anyString(),
                eq(LineagePublishStatus.DISCARDED));
    }

    @Test
    public void anAgedV2RowIsNotAutoDiscarded() throws Exception {
        when(config.getBacklogMaxRetryAgeHours()).thenReturn(1);
        // occurredAt 2026-08-01 is far older than now-1h at any plausible test-run time.
        when(store.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of(v2Row()));

        loop.enforceBacklogThresholds("purview");

        verify(store, never()).discardEvent(anyString(), anyString());
        verify(deadLetters, never()).record(any(), anyString());
    }

    @Test
    public void theOverflowDrainSkipsV2Rows() throws Exception {
        when(config.getBacklogMaxDocs()).thenReturn(1);
        when(store.countNonTerminalByTarget("purview")).thenReturn(5L);
        when(store.findByTargetAndStatusOldestFirst(eq("purview"),
                eq(LineagePublishStatus.PENDING), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(v2Row()));
        when(store.findByTargetAndStatusOldestFirst(eq("purview"),
                eq(LineagePublishStatus.FAILED), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        loop.enforceBacklogThresholds("purview");

        verify(store, never()).discardEvent(anyString(), anyString());
    }

    /** The v1 discard machinery is unchanged — this is what proves the v2 branch is a branch. */
    @Test
    public void aV1PublishFailurePastMaxRetriesStillDiscardsWithADeadLetter() throws Exception {
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", "doc-1")
                .targets(List.of("purview"))
                .build();
        LineageJournalRow row = new LineageJournalRow.Decoded(LineageJournalEntry.ofV1(v1));

        when(store.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(row));
        when(store.updatePublishStatus(v1.eventId(), "purview", LineagePublishStatus.PROJECTING))
                .thenReturn(1);
        when(sink.publish(any())).thenReturn(LineageTargetSinkResult.failure("boom"));
        when(store.getRetryCount(v1.eventId(), "purview")).thenReturn(3);

        loop.pollAndProject();

        verify(deadLetters).record(eq(v1), org.mockito.ArgumentMatchers.startsWith(
                "auto-discard:max-retry-count:"));
        verify(store).updatePublishStatus(v1.eventId(), "purview",
                LineagePublishStatus.DISCARDED);
    }

    // ------------------------------------------------------------------ mixed batch

    /** v1 and v2 side by side in one poll — the shape of the world right after the write flip. */
    @Test
    public void aMixedBatchPublishesBothVersionsThroughOnePath() throws Exception {
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", "doc-1")
                .targets(List.of("purview"))
                .build();
        LineageJournalRow v1Row = new LineageJournalRow.Decoded(LineageJournalEntry.ofV1(v1));
        LineageJournalRow v2Row = v2Row();
        LineageEventV2 v2 = eventOf(v2Row);

        when(store.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(v1Row, v2Row));
        when(store.updatePublishStatus(anyString(), anyString(),
                eq(LineagePublishStatus.PROJECTING))).thenReturn(1);
        when(sink.publish(any())).thenReturn(LineageTargetSinkResult.success(1, "OK"));

        loop.pollAndProject();

        verify(store).updatePublishStatus(v1.eventId(), "purview", LineagePublishStatus.PUBLISHED);
        verify(store).updatePublishStatus(v2.deliveryId(), "purview",
                LineagePublishStatus.PUBLISHED);

        ArgumentCaptor<LineageRecord> published = ArgumentCaptor.forClass(LineageRecord.class);
        verify(sink, org.mockito.Mockito.times(2)).publish(published.capture());
        assertTrue(published.getAllValues().stream()
                        .anyMatch(r -> r.schemaVersion() == 1),
                "the v1 row went through the same path");
        assertTrue(published.getAllValues().stream()
                        .anyMatch(r -> r.schemaVersion() == 2),
                "the v2 row went through the same path");
    }
}
