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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * §6-a's write seam (A-2 Slice 4a): where a version-free fact goes under each barrier state.
 *
 * <p>This is what makes 4b a flag flip. The routing ships now, with the flag saying 1, so the
 * only thing 4b changes is one document — and these tests pin that the v1 path is bit-for-bit
 * what it was, that v2 and an unreadable flag both spool, and that a missing spool never
 * becomes a v1 append.
 */
public class LineageBarrierEmitTest {

    @TempDir
    Path spoolDir;

    private LineageJournalStore store;
    private LineageConfig config;
    private LineageBarrierReader reader;
    private LineageSpoolMachinery machinery;
    private LineageMetrics metrics;
    private LineageFactSpool spool;

    @BeforeEach
    void setUp() {
        store = mock(LineageJournalStore.class);
        config = mock(LineageConfig.class);
        when(config.getTargets()).thenReturn(List.of("atlas"));
        reader = mock(LineageBarrierReader.class);
        metrics = new LineageMetrics();
        spool = new LineageFactSpool(spoolDir, metrics);
        machinery = mock(LineageSpoolMachinery.class);
        when(machinery.spool()).thenReturn(Optional.of(spool));
    }

    private static LineageFact fact() {
        return new LineageFact("bedroom", LineageProcessType.IMPORT_UPLOADED, "op-1",
                "2026-08-01T00:00:00Z",
                List.of(LineageEndpoint.importArtifact("bedroom", "op-1", "zip", Map.of())),
                List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                List.of("atlas"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                        List.of("upload://zip"), List.of("nemaki://bedroom/objects/doc-1"),
                        Map.of(), null));
    }

    private static LineageBarrierReader.BarrierView present(int writeVersion) {
        List<LineageWriteVersionBarrier.NodeRef> nodes =
                List.of(new LineageWriteVersionBarrier.NodeRef("node-a", "boot-1"));
        return new LineageBarrierReader.BarrierView.Present(new LineageWriteVersionBarrier(
                "1-x", LineageWriteVersionBarrier.State.ACTIVE, 3L, writeVersion,
                writeVersion, nodes, LineageWriteVersionBarrier.membershipDigestOf(nodes),
                java.util.Set.of(), java.util.Set.of(), Map.of()));
    }

    private long spooledFiles() throws java.io.IOException {
        try (var walk = Files.walk(spoolDir)) {
            return walk.filter(p -> p.getFileName().toString().startsWith("fact-")).count();
        }
    }

    /** No reader wired = the pre-4a construction: v1, unconditionally. */
    @Test
    public void anAbsentReaderKeepsTheLegacyV1Path() throws Exception {
        JournaledLineageEmitter emitter =
                new JournaledLineageEmitter(store, config, null, machinery, metrics);
        emitter.emit(fact());
        verify(store).append(any(LineageEvent.class));
        assertEquals(0L, spooledFiles());
    }

    /** A deployment that never created a barrier writes exactly what it wrote before 4a. */
    @Test
    public void aPristineBarrierProducesTheSameV1EventAsBefore() throws Exception {
        when(reader.view()).thenReturn(new LineageBarrierReader.BarrierView.Pristine());
        JournaledLineageEmitter with =
                new JournaledLineageEmitter(store, config, reader, machinery, metrics);
        LineageJournalStore legacyStore = mock(LineageJournalStore.class);
        JournaledLineageEmitter without = new JournaledLineageEmitter(legacyStore, config);

        LineageFact fact = fact();
        with.emit(fact);
        without.emit(fact);

        var withCaptor = org.mockito.ArgumentCaptor.forClass(LineageEvent.class);
        var withoutCaptor = org.mockito.ArgumentCaptor.forClass(LineageEvent.class);
        verify(store).append(withCaptor.capture());
        verify(legacyStore).append(withoutCaptor.capture());
        // eventId is allocated per call; everything the journal keys on must match exactly.
        assertEquals(withoutCaptor.getValue().eventKey(), withCaptor.getValue().eventKey());
        assertEquals(withoutCaptor.getValue().repositoryId(),
                withCaptor.getValue().repositoryId());
        assertEquals(withoutCaptor.getValue().processType(),
                withCaptor.getValue().processType());
        assertEquals(withoutCaptor.getValue().inputs(), withCaptor.getValue().inputs());
        assertEquals(withoutCaptor.getValue().outputs(), withCaptor.getValue().outputs());
        assertEquals(withoutCaptor.getValue().occurredAt(), withCaptor.getValue().occurredAt());
        assertEquals(0L, spooledFiles());
    }

    @Test
    public void aBarrierStillOnV1KeepsWritingV1() throws Exception {
        when(reader.view()).thenReturn(present(1));
        new JournaledLineageEmitter(store, config, reader, machinery, metrics).emit(fact());
        verify(store).append(any(LineageEvent.class));
        assertEquals(0L, spooledFiles());
    }

    /** The flip: v2 spools the FACT, and never appends an event from the emit path. */
    @Test
    public void aBarrierOnV2SpoolsTheFactInsteadOfAppending() throws Exception {
        when(reader.view()).thenReturn(present(2));
        new JournaledLineageEmitter(store, config, reader, machinery, metrics).emit(fact());
        verify(store, never()).append(any(LineageEvent.class));
        assertEquals(1L, spooledFiles(), "chunking and the decision live in the materializer");
    }

    /** #7: an unreadable flag cannot choose a version, so it stores the version-free fact. */
    @Test
    public void anUnreadableBarrierSpoolsTheFact() throws Exception {
        when(reader.view()).thenReturn(
                new LineageBarrierReader.BarrierView.Indeterminate("couch is down"));
        new JournaledLineageEmitter(store, config, reader, machinery, metrics).emit(fact());
        verify(store, never()).append(any(LineageEvent.class));
        assertEquals(1L, spooledFiles());
    }

    /** #7b: the spool failed too. The fact is lost, and it says so by name. */
    @Test
    public void aFailedSpoolWriteRaisesTheNamedDropCounter() {
        when(reader.view()).thenReturn(
                new LineageBarrierReader.BarrierView.Indeterminate("couch is down"));
        LineageFactSpool broken = mock(LineageFactSpool.class);
        when(broken.append(any())).thenThrow(new IllegalStateException("volume is read-only"));
        when(machinery.spool()).thenReturn(Optional.of(broken));

        new JournaledLineageEmitter(store, config, reader, machinery, metrics).emit(fact());

        verify(store, never()).append(any(LineageEvent.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> byReason =
                (Map<String, Object>) metrics.snapshot().get("emitDroppedByReason");
        assertEquals(1L, ((Number) byReason.get("flag_unreadable_and_spool_failed"))
                .longValue());
    }

    /**
     * A missing spool is NOT a licence to write v1: the barrier said this fact does not belong
     * on the v1 path, and being unable to spool it does not change that.
     */
    @Test
    public void aMissingSpoolDropsRatherThanCrossingTheFence() {
        when(machinery.spool()).thenReturn(Optional.empty());
        for (LineageBarrierReader.BarrierView view : List.of(present(2),
                new LineageBarrierReader.BarrierView.Indeterminate("couch is down"))) {
            when(reader.view()).thenReturn(view);
            new JournaledLineageEmitter(store, config, reader, machinery, metrics).emit(fact());
        }
        verify(store, never()).append(any(LineageEvent.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> byReason =
                (Map<String, Object>) metrics.snapshot().get("emitDroppedByReason");
        assertEquals(2L, ((Number) byReason.get("spool_machinery_absent")).longValue(),
                "both the v2 and the unreadable path drop — neither falls back to v1");
    }

    /** The drop must be visible even where no metrics bean exists. */
    @Test
    public void aDropWithoutMetricsStillRefusesToAppendV1() {
        when(reader.view()).thenReturn(present(2));
        when(machinery.spool()).thenReturn(Optional.empty());
        new JournaledLineageEmitter(store, config, reader, machinery, null).emit(fact());
        verify(store, never()).append(any(LineageEvent.class));
    }

    /** A spooled fact is a complete, version-free record the scanner can materialize later. */
    @Test
    public void theSpooledFactIsVerifiableAndVersionFree() throws Exception {
        when(reader.view()).thenReturn(present(2));
        LineageFact fact = fact();
        new JournaledLineageEmitter(store, config, reader, machinery, metrics).emit(fact);

        Path file;
        try (var walk = Files.walk(spoolDir)) {
            file = walk.filter(p -> p.getFileName().toString().startsWith("fact-"))
                    .findFirst().orElseThrow();
        }
        LineageSpoolPayloadV1 stored = spool.readVerified(file);
        assertNotNull(stored);
        assertEquals(LineageSpoolPayloadV1.of(fact).spoolRecordId(), stored.spoolRecordId());
        assertTrue(stored.selfVerifies());
    }
}
