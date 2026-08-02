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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The single aggregate D-rest gate (v2.3.18 ⑤ / v2.3.19 B2, C1): switch + config bounds +
 * deployed-view signatures + structural sink verification, evaluated as one verdict.
 */
public class LineageDrestReadinessTest {

    private LineageDrestReadiness readiness;
    private LineageConfig config;
    private CouchLineageJournalStore store;
    private LineageTargetSink sink;

    @BeforeEach
    void setUp() throws Exception {
        readiness = new LineageDrestReadiness();
        config = mock(LineageConfig.class);
        store = mock(CouchLineageJournalStore.class);
        sink = mock(LineageTargetSink.class);
        when(config.isDrestEnabled()).thenReturn(true);
        when(config.getProjectionClaimLeaseSeconds()).thenReturn(120);
        when(config.getVerifyTimeoutSeconds()).thenReturn(30);
        when(config.getVerifyIntervalSeconds()).thenReturn(2);
        when(config.getVerifyMaxAgeMinutes()).thenReturn(10);
        when(config.getTargets()).thenReturn(List.of("atlas"));
        when(config.getSequencerLeaseSeconds()).thenReturn(60);
        when(config.getSequencerBatchSize()).thenReturn(100);
        when(config.getSequencerBacklogCap()).thenReturn(1000);
        when(store.viewSignatureViolations()).thenReturn(List.of());
        when(sink.targetName()).thenReturn("atlas");
        when(sink.supportsVerification()).thenReturn(true);
        set("lineageConfig", config);
        set("journalStore", store);
        set("targetSinks", List.of(sink));
    }

    private void set(String field, Object value) throws Exception {
        Field f = LineageDrestReadiness.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(readiness, value);
    }

    @Test
    public void allGreenIsReady() {
        assertTrue(readiness.evaluate().ready());
    }

    @Test
    public void theSwitchOffAloneBlocks() {
        when(config.isDrestEnabled()).thenReturn(false);
        LineageDrestReadiness.Readiness verdict = readiness.evaluate();
        assertFalse(verdict.ready());
        assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("drest.enabled")));
    }

    @Test
    public void aLeaseThatFitsInsideOneVerifyEncounterBlocks() {
        // margin = max(2×interval, 10s) = 10s; lease must EXCEED timeout + margin = 40s.
        when(config.getProjectionClaimLeaseSeconds()).thenReturn(40);
        assertFalse(readiness.evaluate().ready(),
                "a lease expiring inside one verify encounter hands the reaper a live"
                        + " claimant");
        when(config.getProjectionClaimLeaseSeconds()).thenReturn(41);
        assertTrue(readiness.evaluate().ready());
    }

    @Test
    public void viewSignatureDriftBlocks() {
        when(store.viewSignatureViolations()).thenReturn(List.of(
                "view 'by_target_status' map source differs from this binary's definition"));
        assertFalse(readiness.evaluate().ready(),
                "an old binary redeployed its dual-schema views — activation must refuse");
    }

    @Test
    public void anUnverifiableConfiguredTargetBlocksAndJournalOnlyDoesNot() {
        when(sink.supportsVerification()).thenReturn(false);
        LineageDrestReadiness.Readiness verdict = readiness.evaluate();
        assertFalse(verdict.ready());
        assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("cannot verify")));

        // Journal-only: no targets, no ordered consumer to strand — the clause passes.
        when(config.getTargets()).thenReturn(List.of());
        assertTrue(readiness.evaluate().ready());
    }

    /** Round-2 fix 2: a green verdict must never reach the sequencer with unusable knobs. */
    @Test
    public void sequencerKnobsAreValidatedByTheSameGate() {
        when(config.getSequencerLeaseSeconds()).thenReturn(0);
        assertFalse(readiness.evaluate().ready());
        when(config.getSequencerLeaseSeconds()).thenReturn(60);
        when(config.getSequencerBacklogCap()).thenReturn(Integer.MAX_VALUE);
        assertFalse(readiness.evaluate().ready(),
                "an unbounded cap overflows the cap+1 probe");
        when(config.getSequencerBacklogCap()).thenReturn(1000);
        assertTrue(readiness.evaluate().ready());
    }

    /** Exact bound sweep: every knob's min and max are IN range; one past either is OUT. */
    @Test
    public void everyConfigBoundIsExact() {
        record Knob(java.util.function.IntConsumer set, int min, int max) {}
        // Baseline satisfies all cross-constraints at every probe (lease 3600 >> timeout+margin).
        when(config.getProjectionClaimLeaseSeconds()).thenReturn(3600);
        var knobs = java.util.List.of(
                new Knob(v -> when(config.getProjectionClaimLeaseSeconds()).thenReturn(v),
                        30 + 10 + 1, 3600), // min = timeout(30)+margin(10)+1: below it the
                                            // cross-constraint blocks, exactly as intended
                new Knob(v -> when(config.getVerifyTimeoutSeconds()).thenReturn(v), 1, 300),
                new Knob(v -> when(config.getVerifyIntervalSeconds()).thenReturn(v), 1, 60),
                new Knob(v -> when(config.getVerifyMaxAgeMinutes()).thenReturn(v), 1, 1440),
                new Knob(v -> when(config.getSequencerLeaseSeconds()).thenReturn(v), 10, 3600),
                new Knob(v -> when(config.getSequencerBatchSize()).thenReturn(v), 1, 10_000),
                new Knob(v -> when(config.getSequencerBacklogCap()).thenReturn(v), 1,
                        1_000_000));
        for (Knob knob : knobs) {
            knob.set().accept(knob.min());
            assertTrue(readiness.evaluate().ready(), "min " + knob.min() + " is in range");
            knob.set().accept(knob.max());
            assertTrue(readiness.evaluate().ready(), "max " + knob.max() + " is in range");
            knob.set().accept(knob.max() + 1);
            assertFalse(readiness.evaluate().ready(), "past max blocks");
            knob.set().accept(knob.min() - 1);
            assertFalse(readiness.evaluate().ready(), "below min blocks");
            knob.set().accept(knob.max()); // restore a valid value for the next knob
            // Re-restore baseline lease for cross-constraint stability.
            when(config.getProjectionClaimLeaseSeconds()).thenReturn(3600);
        }
        assertTrue(readiness.evaluate().ready());
    }

    /** The margin really is max(2×interval, 10s) — not a division, not a fixed 10. */
    @Test
    public void theLeaseMarginScalesWithTheVerifyInterval() {
        when(config.getVerifyIntervalSeconds()).thenReturn(6); // margin = max(12, 10) = 12
        when(config.getVerifyTimeoutSeconds()).thenReturn(30);
        when(config.getProjectionClaimLeaseSeconds()).thenReturn(42); // <= 30+12 → blocked
        assertFalse(readiness.evaluate().ready());
        when(config.getProjectionClaimLeaseSeconds()).thenReturn(43); // > 30+12 → ready
        assertTrue(readiness.evaluate().ready());
    }

    /** The projection lease's own [30,3600] bound is exact, independent of the margin rule. */
    @Test
    public void theProjectionLeaseLowerBoundIsExact() {
        when(config.getVerifyTimeoutSeconds()).thenReturn(1);
        when(config.getVerifyIntervalSeconds()).thenReturn(1); // margin 10 → cross needs > 11
        when(config.getProjectionClaimLeaseSeconds()).thenReturn(29);
        assertFalse(readiness.evaluate().ready(), "29 < 30 blocks on the range bound");
        when(config.getProjectionClaimLeaseSeconds()).thenReturn(30);
        assertTrue(readiness.evaluate().ready(), "30 is in range and past the margin");
    }

    @Test
    public void aTargetAddedLaterWithoutASinkBlocks() {
        when(config.getTargets()).thenReturn(List.of("atlas", "dataplex"));
        LineageDrestReadiness.Readiness verdict = readiness.evaluate();
        assertFalse(verdict.ready());
        assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("dataplex")));
    }
}
