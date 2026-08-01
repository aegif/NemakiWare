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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2c: the projector decides from the {@link LineageRecord} and keeps the envelope only for
 * the dead-letter path.
 *
 * <p>Two behaviours here are new rather than migrated, both from the review:
 * a stored row the read model rejects is quarantined without killing the batch (and without
 * breaking ordering), and the backlog age check compares parsed instants because the lexical
 * comparison it replaced mis-orders {@code Instant.toString()}'s variable-width fractional
 * seconds.
 */
class LineageProjectionRecordContractTest {

    private LineageProjectionLoop loop;
    private LineageConfig config;
    private LineageJournalStore store;
    private LineageDeadLetterStore deadLetters;
    private LineageTargetSink sink;

    @BeforeEach
    void setUp() throws Exception {
        loop = new LineageProjectionLoop();
        config = mock(LineageConfig.class);
        store = mock(LineageJournalStore.class);
        sink = mock(LineageTargetSink.class);
        deadLetters = mock(LineageDeadLetterStore.class);
        when(deadLetters.isActive()).thenReturn(true);
        LineageDeadLetterSink.setStore(deadLetters);

        when(config.getTargets()).thenReturn(List.of("purview"));
        when(config.getProjectionBatchSize()).thenReturn(50);
        when(config.getBacklogMaxRetryCount()).thenReturn(5);
        when(config.getBacklogMaxRetryAgeHours()).thenReturn(72);
        when(config.getBacklogMaxDocs()).thenReturn(10000);

        when(store.isActive()).thenReturn(true);
        when(store.findByTargetAndStatus(anyString(), any(), anyInt())).thenReturn(List.of());
        when(sink.targetName()).thenReturn("purview");
        when(sink.isAvailable()).thenReturn(true);

        set("lineageConfig", config);
        set("journalStore", store);
        set("targetSinks", List.of(sink));
    }

    @AfterEach
    void tearDown() {
        LineageDeadLetterSink.setStore(null);
    }

    private void set(String field, Object value) throws Exception {
        Field f = LineageProjectionLoop.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(loop, value);
    }

    private static LineageEvent event(String objectId) {
        return new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", objectId)
                .targets(List.of("purview"))
                .build();
    }

    /** eventId is blank, which {@link LineageRecord} rejects; everything else is fine. */
    private static LineageEvent unprojectable() {
        return new LineageEvent(1, "", "key", 0L, Instant.now().toString(), "bedroom",
                LineageProcessType.ARCHIVE_LOCAL, List.of(), List.of(), "", "", 1,
                Map.of(), Map.of("purview", LineagePublishStatus.PENDING));
    }

    // ------------------------------------------------------------------ quarantine, unordered

    /**
     * One bad row must cost one row. Before the guard, the projection threw inside the loop and
     * the exception handler treated it as a publish failure — burning a retry per poll on a row
     * that can never publish, forever.
     */
    @Test
    void aRowTheReadModelRejectsIsQuarantinedAndTheBatchContinues() throws Exception {
        LineageEvent bad = unprojectable();
        LineageEvent good = event("doc-1");
        when(store.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(bad, good));
        when(store.updatePublishStatus(good.eventId(), "purview", LineagePublishStatus.PROJECTING))
                .thenReturn(1);
        when(sink.publish(any())).thenReturn(LineageTargetSinkResult.success(1, "OK"));

        loop.pollAndProject();

        // The bad row: dead-lettered with the decode reason, discarded out of the poll set,
        // never claimed, never published.
        verify(deadLetters).record(eq(bad), org.mockito.ArgumentMatchers.startsWith(
                "projection-decode-failed:"));
        verify(store).updatePublishStatus(bad.eventId(), "purview",
                LineagePublishStatus.DISCARDED);
        verify(store, never()).updatePublishStatus(bad.eventId(), "purview",
                LineagePublishStatus.PROJECTING);

        // The good row still went out.
        verify(sink).publish(LineageRecord.fromV1(good));
        verify(store).updatePublishStatus(good.eventId(), "purview",
                LineagePublishStatus.PUBLISHED);
    }

    // ------------------------------------------------------------------ quarantine, ordered

    /**
     * The ordered path stops the repository for the cycle instead of continuing: publishing the
     * next event over a quarantined one would break the ordering the loop exists for. DISCARDED
     * is terminal, so the next poll advances the cursor over it and the repository resumes.
     */
    @Test
    void inOrderedProjectionAQuarantinedRowStopsTheRepositoryForThisCycle() throws Exception {
        // Ordered mode is selected by the presence of an active cursor store.
        ProjectionCursorStore cursorStore = mock(ProjectionCursorStore.class);
        set("cursorStore", cursorStore);
        when(cursorStore.isActive()).thenReturn(true);
        when(cursorStore.getAllCursors()).thenReturn(List.of());
        when(store.findDistinctNonTerminalRepositoryIds("purview")).thenReturn(List.of("bedroom"));
        when(cursorStore.getCursor("purview", "bedroom")).thenReturn(null);

        LineageEvent bad = unprojectable();
        LineageEvent next = event("doc-2");
        when(store.findByRepositoryAndSequenceRange("bedroom", 0L, 50))
                .thenReturn(List.of(bad, next));

        loop.pollAndProject();

        verify(deadLetters).record(eq(bad), org.mockito.ArgumentMatchers.startsWith(
                "projection-decode-failed:"));
        verify(store).updatePublishStatus(bad.eventId(), "purview",
                LineagePublishStatus.DISCARDED);
        // The row after it is NOT published this cycle — order is preserved.
        verify(sink, never()).publish(any());
        verify(store, never()).updatePublishStatus(eq(next.eventId()), anyString(), any());
    }

    // ------------------------------------------------------------------ age comparison

    /**
     * The bug the lexical comparison had: {@code ...:00.500Z} sorts before {@code ...:00Z}, so an
     * event from <em>half a second after</em> the cutoff was judged older than it. With a cutoff
     * of exactly now-72h, an event 1ms after the cutoff carrying a fractional second must
     * survive, and one clearly before it must not.
     */
    @Test
    void ageComparisonIsChronologicalNotLexical() throws Exception {
        Instant cutoffIsh = Instant.now().minus(72, ChronoUnit.HOURS);

        // Younger than the cutoff, but with a fractional second — lexically "smaller" than a
        // whole-second cutoff string, so the old code discarded it.
        LineageEvent youngWithMillis = new LineageEvent(1, "young", "k", 0L,
                cutoffIsh.plusSeconds(3600).plusMillis(500).toString(), "bedroom",
                LineageProcessType.ARCHIVE_LOCAL, List.of(), List.of(), "", "", 1,
                Map.of(), Map.of("purview", LineagePublishStatus.FAILED));
        LineageEvent genuinelyOld = new LineageEvent(1, "old", "k", 0L,
                cutoffIsh.minus(1, ChronoUnit.HOURS).toString(), "bedroom",
                LineageProcessType.ARCHIVE_LOCAL, List.of(), List.of(), "", "", 1,
                Map.of(), Map.of("purview", LineagePublishStatus.FAILED));

        when(store.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of(youngWithMillis, genuinelyOld));

        loop.pollAndProject();

        verify(store, never()).discardEvent("young", "purview");
        verify(store).discardEvent("old", "purview");
        verify(deadLetters).record(eq(genuinelyOld), org.mockito.ArgumentMatchers.contains(
                "retry-age-exceeded"));
    }

    /**
     * Age is the one thing we cannot know about an unparseable timestamp. Discarding on a guess
     * could throw away a young event; keeping it costs nothing, because the max-docs drain bounds
     * the backlog without reading timestamps.
     */
    @Test
    void anUnparseableOccurredAtIsNotAgeDiscarded() throws Exception {
        LineageEvent junk = new LineageEvent(1, "junk-ts", "k", 0L, "not-a-timestamp", "bedroom",
                LineageProcessType.ARCHIVE_LOCAL, List.of(), List.of(), "", "", 1,
                Map.of(), Map.of("purview", LineagePublishStatus.FAILED));
        when(store.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of(junk));

        loop.pollAndProject();

        verify(store, never()).discardEvent("junk-ts", "purview");
    }

    // ------------------------------------------------------------------ record drives the store

    /** For v1 the recordId is the eventId, so the store sees the same value as before. */
    @Test
    void storeMutationsReceiveTheRecordId() throws Exception {
        LineageEvent event = event("doc-1");
        when(store.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(event));
        when(store.updatePublishStatus(anyString(), anyString(),
                eq(LineagePublishStatus.PROJECTING))).thenReturn(1);
        when(sink.publish(any())).thenReturn(LineageTargetSinkResult.failure("boom"));
        when(store.getRetryCount(anyString(), anyString())).thenReturn(0);

        loop.pollAndProject();

        String recordId = LineageRecord.fromV1(event).recordId();
        assertTrue(recordId.equals(event.eventId()),
                "precondition: v1 recordId is the eventId");
        verify(store).updatePublishStatus(recordId, "purview", LineagePublishStatus.PROJECTING);
        verify(store).updatePublishStatus(recordId, "purview", LineagePublishStatus.FAILED);
        verify(store).getRetryCount(recordId, "purview");
        // The dead letter still receives the ENVELOPE — recovery cannot use the projection.
        verify(deadLetters).record(eq(event), org.mockito.ArgumentMatchers.startsWith(
                "publish-failed:"));
    }
}
