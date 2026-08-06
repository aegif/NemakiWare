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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sequencer has a driver.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A v2 lineage fact is appended UNSEQUENCED, and the projector only ever looks at rows that
 * reached SEQUENCED. Finalizing that transition is the sequencer's job — and for a while the
 * sequencer's ONLY caller was the operator endpoint {@code POST /sequencer/{repo}/run}. On a
 * node that was green by every other measure (D-rest readiness green, reader ADMITTED, the
 * spool scan acking normally) the journal simply filled: 46 events at {@code sequenceNumber 0},
 * nothing claimed, nothing published, and no error anywhere — the pipeline was not broken, it
 * was never started. Driving it by hand finalized all 46 at once.
 *
 * <p>That is the same omission the obligation scanner had one stage later, and it is invisible
 * to any test that drives the sequencer directly. So what is asserted here is the WIRING: that
 * the poll loop runs a pass, and that it refuses to under the guards that make sequencing
 * DB-global work.
 */
class LineageSequencerDriverTest {

    private static LineageProjectionLoop loopWith(LineageSequencerAdminService admin,
                                                  LineageReaderAdmission admission,
                                                  LeaderElection leader,
                                                  LineageV2TransitionStore store,
                                                  LineageTargetSink sink) throws Exception {
        LineageProjectionLoop loop = new LineageProjectionLoop();
        set(loop, "sequencerAdmin", admin);
        set(loop, "readerAdmission", admission);
        set(loop, "leaderElection", leader);
        set(loop, "journalStore", store);
        set(loop, "targetSinks", sink == null ? null : List.of(sink));
        return loop;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** A store that is both the journal and the v2 transition surface, as production wires it. */
    private interface JournalAndV2 extends LineageJournalStore, LineageV2TransitionStore { }

    private static JournalAndV2 storeWithRepositories(String target, List<String> repositories) {
        JournalAndV2 store = mock(JournalAndV2.class);
        when(store.findV2NonTerminalRepositoryIds(target)).thenReturn(repositories);
        return store;
    }

    private static LineageTargetSink sinkNamed(String name) {
        LineageTargetSink sink = mock(LineageTargetSink.class);
        when(sink.targetName()).thenReturn(name);
        return sink;
    }

    private static LineageReaderAdmission admissionOf(LineageReaderAdmission.Decision decision) {
        LineageReaderAdmission admission = mock(LineageReaderAdmission.class);
        when(admission.evaluate()).thenReturn(
                new LineageReaderAdmission.Admission(decision, List.of(), null));
        return admission;
    }

    private static LineageSequencerAdminService adminThatFinalizes(int count) {
        LineageSequencerAdminService admin = mock(LineageSequencerAdminService.class);
        when(admin.run(anyString(), anyString())).thenReturn(
                new LineageSequencerAdminService.SequencerRunOutcome(true, List.of(),
                        new LineageFencedSequencer.RunSummary(
                                LineageSequencingStore.SequencerHealth.FENCED_OK,
                                count, 0, 0, false)));
        return admin;
    }

    @Test
    @DisplayName("poll ごとに sequencer pass が走る — これが無いと v2 は永久に UNSEQUENCED")
    void thePassRunsForEveryRepositoryWithWork() throws Exception {
        LineageSequencerAdminService admin = adminThatFinalizes(3);
        LineageProjectionLoop loop = loopWith(admin,
                admissionOf(LineageReaderAdmission.Decision.ADMITTED), null,
                storeWithRepositories("atlas", List.of("bedroom", "canopy")),
                sinkNamed("atlas"));

        loop.runSequencerPass();

        org.mockito.ArgumentCaptor<String> label = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(admin).run(eq("bedroom"), label.capture());
        verify(admin).run(eq("canopy"), anyString());
        assertEquals("loop", label.getValue(),
                "the lease owner must say which driver took it, so an operator can tell a"
                        + " scheduled pass from one they triggered by hand");
    }

    @Test
    @DisplayName("reader が ADMITTED でなければ sequence 番号を振らない")
    void arefusedReaderAssignsNothing() throws Exception {
        LineageSequencerAdminService admin = adminThatFinalizes(1);
        LineageProjectionLoop loop = loopWith(admin,
                admissionOf(LineageReaderAdmission.Decision.REFUSED), null,
                storeWithRepositories("atlas", List.of("bedroom")), sinkNamed("atlas"));

        loop.runSequencerPass();

        verify(admin, never()).run(anyString(), anyString());
    }

    @Test
    @DisplayName("leader でない node は lease を奪いに行かない")
    void aFollowerDoesNotRace() throws Exception {
        LineageSequencerAdminService admin = adminThatFinalizes(1);
        LeaderElection follower = mock(LeaderElection.class);
        when(follower.isEnabled()).thenReturn(true);
        when(follower.isLeader("projection")).thenReturn(false);

        LineageProjectionLoop loop = loopWith(admin,
                admissionOf(LineageReaderAdmission.Decision.ADMITTED), follower,
                storeWithRepositories("atlas", List.of("bedroom")), sinkNamed("atlas"));

        loop.runSequencerPass();

        verify(admin, never()).run(anyString(), anyString());
    }

    /**
     * The pass is actually scheduled, not merely implemented.
     *
     * <p>The defect was a method nobody called on a timer; a test that calls it directly would
     * have passed throughout. This reads the initialization source and requires the pass to be
     * handed to the scheduler beside the two that were already there.
     */
    @Test
    @DisplayName("sequencer pass が scheduler に登録されている (呼ばれない実装が本体の欠陥だった)")
    void thePassIsScheduled() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageProjectionLoop.java"));
        int schedulePoint = source.indexOf("scheduleWithFixedDelay(\n                this::runSequencerPass");
        assertTrue(schedulePoint > 0,
                "runSequencerPass is not handed to scheduleWithFixedDelay — an unsequenced"
                        + " journal never drains, and nothing reports an error while it does not");
    }

}
