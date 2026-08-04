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

    /**
     * A publisher that does nothing. These tests are about wiring, not publishing — but the
     * interface has two methods now, so a lambda will not do.
     */
    private static LineageHistoricalEntityPublisher noOpPublisher() {
        return new LineageHistoricalEntityPublisher() {
            @Override
            public LineageHistoricalPublishReceipt publishHistorical(
                    HistoricalEntitySnapshot snapshot) {
                return null;
            }

            @Override
            public LineageHistoricalReadBack readBackHistorical(
                    HistoricalEntitySnapshot snapshot, String plannedOperationDigest) {
                return LineageHistoricalReadBack.UNKNOWN;
            }
        };
    }

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
        when(config.getMode()).thenReturn(LineageMode.DIRECT);
        when(config.getSequencerLeaseSeconds()).thenReturn(60);
        when(config.getSequencerBatchSize()).thenReturn(100);
        when(config.getSequencerBacklogCap()).thenReturn(1000);
        when(config.getEndpointMaxPerEvent()).thenReturn(1000);
        when(config.getEventMaxPayloadBytes()).thenReturn(1024L * 1024);
        when(config.getEventMaxDocumentBytes()).thenReturn(4L * 1024 * 1024);
        when(store.viewSignatureViolations()).thenReturn(List.of());
        when(sink.targetName()).thenReturn("atlas");
        when(sink.supportsVerification()).thenReturn(true);
        // 4a: readiness probes the SHARED spool, so the machinery must be wired — an absent
        // one is now a red verdict rather than a throwaway probe.
        set("spoolMachinery", new LineageSpoolMachinery(config, null, store));
        set("lineageConfig", config);
        set("journalStore", store);
        set("targetSinks", List.of(sink));
        // §2's obligation machine (v2.3.44): readiness now requires it to be ASSEMBLED, not
        // merely present in the binary. The tests below are about other conditions, so they
        // start from a wired one; aMachineThatIsNotWiredBlocks covers the absence.
        set("obligationWiring", wiredObligationMachine());
    }

    /** A complete obligation machine for the one configured target. */
    private static LineageObligationWiring wiredObligationMachine() {
        LineageCatalogObligationStore obligationStore =
                mock(LineageCatalogObligationStore.class);
        LineageCatalogObligationService service = new LineageCatalogObligationService(
                obligationStore, (t, r, k, qn) -> LineageCatalogEntityProbe.Presence.PRESENT,
                mock(LineageDrestReadiness.class), mock(LineageNodeIdentity.class), () -> 0L);
        return new LineageObligationWiring(
                obligationStore,
                new LineageCatalogProbeRegistry(java.util.Map.of("atlas",
                        (t, r, k, qn) -> LineageCatalogEntityProbe.Presence.PRESENT)),
                new LineageHistoricalPublisherRegistry(java.util.Map.of("atlas",
                        noOpPublisher())),
                service, new LineageObligationScannerImpl(service),
                new LineageObligationProjectorCollaboratorImpl(service),
                mock(LineageHistoricalPublishIntentStore.class),
                mock(LineageHistoricalCompensationStore.class),
                mock(LineageHistoricalPublishMachine.class), everyKindResolvable(),
                mock(LineageCurrentEntityRepublisher.class), FixedOperationBudgets.healthy(),
                availableLedger());
    }

    /** A source resolver for every kind, so the per-kind readiness check passes. */
    private static LineageSourceDispositionRegistry everyKindResolvable() {
        java.util.Map<EndpointKind, LineageSourceDispositionResolver> byKind =
                new java.util.EnumMap<>(EndpointKind.class);
        for (EndpointKind kind : EndpointKind.values()) {
            byKind.put(kind, (repositoryId, k, qn)
                    -> LineageSourceDispositionResolver.SourceEvidence.unknown(0L));
        }
        return new LineageSourceDispositionRegistry(byKind, () -> 0L);
    }

    private void set(String field, Object value) throws Exception {
        Field f = LineageDrestReadiness.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(readiness, value);
    }

    /**
     * The false-green {@code catalog:obligations} allowed until v2.3.44.
     *
     * <p>The capability says the code is in the binary. A node with the code and none of the
     * wiring passed condition 8 and a green gate, and would have found out with v2 writes
     * already open — which is exactly what 4b being a flag flip forbids.
     */
    @Test
    public void aMachineThatIsNotWiredBlocks() throws Exception {
        set("obligationWiring", null);

        LineageDrestReadiness.Readiness verdict = readiness.evaluate();

        assertFalse(verdict.ready());
        assertTrue(verdict.violations().stream()
                .anyMatch(v -> v.contains("obligation machine is not wired")),
                verdict.violations().toString());
    }

    /** A probe missing for a configured target is the same class of gap, per target. */
    @Test
    public void aTargetWithoutAProbeBlocks() throws Exception {
        LineageCatalogObligationStore obligationStore =
                mock(LineageCatalogObligationStore.class);
        LineageCatalogObligationService service = new LineageCatalogObligationService(
                obligationStore, (t, r, k, qn) -> LineageCatalogEntityProbe.Presence.PRESENT,
                mock(LineageDrestReadiness.class), mock(LineageNodeIdentity.class), () -> 0L);
        set("obligationWiring", new LineageObligationWiring(obligationStore,
                new LineageCatalogProbeRegistry(java.util.Map.of()),
                new LineageHistoricalPublisherRegistry(java.util.Map.of("atlas",
                        noOpPublisher())),
                service, new LineageObligationScannerImpl(service),
                new LineageObligationProjectorCollaboratorImpl(service),
                mock(LineageHistoricalPublishIntentStore.class),
                mock(LineageHistoricalCompensationStore.class),
                mock(LineageHistoricalPublishMachine.class), everyKindResolvable(),
                mock(LineageCurrentEntityRepublisher.class), FixedOperationBudgets.healthy(),
                availableLedger()));

        LineageDrestReadiness.Readiness verdict = readiness.evaluate();

        assertFalse(verdict.ready());
        assertTrue(verdict.violations().stream()
                .anyMatch(v -> v.contains("probe") && v.contains("atlas")),
                verdict.violations().toString());
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
        when(config.getEndpointMaxPerEvent()).thenReturn(1000);
        when(config.getEventMaxPayloadBytes()).thenReturn(1024L * 1024);
        when(config.getEventMaxDocumentBytes()).thenReturn(4L * 1024 * 1024);
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

    /** v2.3.22: the chunk limits are validated by the same gate. */
    @Test
    public void journaledModeValidatesTheChunkLimits(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path spoolDir) {
        when(config.getMode()).thenReturn(LineageMode.JOURNALED);
        when(config.getSpoolDir()).thenReturn(spoolDir.toString());
        when(config.getSpoolScanMaxFiles()).thenReturn(2000);
        when(config.getSpoolScanMaxMaterializations()).thenReturn(100);
        when(config.getSpoolScanMaxMillis()).thenReturn(5000L);
        assertTrue(readiness.evaluate().ready());

        when(config.getEndpointMaxPerEvent()).thenReturn(1);
        assertFalse(readiness.evaluate().ready(),
                "a limit of 1 cannot admit an anchor plus a payload endpoint");
        when(config.getEndpointMaxPerEvent()).thenReturn(1000);

        when(config.getEventMaxDocumentBytes()).thenReturn(9_000_000L);
        assertFalse(readiness.evaluate().ready(),
                "a ceiling above CouchDB's default max_document_size promises too much");
        when(config.getEventMaxDocumentBytes()).thenReturn(4L * 1024 * 1024);
        assertTrue(readiness.evaluate().ready());
    }

    /** D-rest-4 round-2 fix 4: journaled mode also validates the scan budgets. */
    @Test
    public void journaledModeValidatesSpoolAndScanBudgets(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path spoolDir) {
        when(config.getMode()).thenReturn(LineageMode.JOURNALED);
        when(config.getSpoolDir()).thenReturn(spoolDir.toString());
        when(config.getSpoolScanMaxFiles()).thenReturn(2000);
        when(config.getSpoolScanMaxMaterializations()).thenReturn(100);
        when(config.getSpoolScanMaxMillis()).thenReturn(5000L);
        assertTrue(readiness.evaluate().ready(), "a real writable dir + sane budgets pass");

        when(config.getSpoolScanMaxFiles()).thenReturn(0);
        assertFalse(readiness.evaluate().ready(),
                "a zero budget would throw every poll — the gate refuses it up front");
        when(config.getSpoolScanMaxFiles()).thenReturn(2000);
        when(config.getSpoolScanMaxMillis()).thenReturn(0L);
        assertFalse(readiness.evaluate().ready());
        when(config.getSpoolScanMaxMillis()).thenReturn(5000L);

        when(config.getSpoolDir()).thenReturn("");
        assertFalse(readiness.evaluate().ready(),
                "journaled mode without a spool dir is NOT_READY");
    }

    /**
     * v2.3.24 F3: each byte knob was validated alone, but never against the other. The
     * planner fits chunks against max-payload-bytes while the store's ceiling is
     * max-document-bytes — with the first above the second every plan is well-formed and
     * unstorable, so every fact would park.
     */
    @Test
    public void aPayloadLimitAboveTheDocumentCeilingBlocks(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path spoolDir) {
        when(config.getMode()).thenReturn(LineageMode.JOURNALED);
        when(config.getSpoolDir()).thenReturn(spoolDir.toString());
        when(config.getSpoolScanMaxFiles()).thenReturn(2000);
        when(config.getSpoolScanMaxMaterializations()).thenReturn(100);
        when(config.getSpoolScanMaxMillis()).thenReturn(5000L);
        when(config.getEventMaxPayloadBytes()).thenReturn(4L * 1024 * 1024);
        when(config.getEventMaxDocumentBytes()).thenReturn(2L * 1024 * 1024);
        LineageDrestReadiness.Readiness verdict = readiness.evaluate();
        assertFalse(verdict.ready());
        assertTrue(verdict.violations().stream()
                        .anyMatch(v -> v.contains("must not exceed")),
                "the relation between the two knobs is the violation, not either alone");

        when(config.getEventMaxDocumentBytes()).thenReturn(4L * 1024 * 1024);
        assertTrue(readiness.evaluate().ready(), "equal is allowed — the ceiling is inclusive");
    }

    /**
     * 4a: an unparseable reader-version declaration must NOT resolve to the default. The
     * default belongs to an absent setting; here the operator said something, and nothing
     * usable came of it, so this node declares that it reads nothing.
     */
    @Test
    public void anUnparseableReadSchemaVersionsDeclarationFailsClosed() throws Exception {
        LineageConfig real = new LineageConfig();
        Field field = LineageConfig.class.getDeclaredField("readSchemaVersions");
        field.setAccessible(true);
        field.set(real, "foo");
        assertTrue(real.getReadSchemaVersions().isEmpty(),
                "a malformed declaration must not become {1,2}");
        field.set(real, "");
        assertEquals(java.util.Set.of(1, 2), real.getReadSchemaVersions(),
                "an ABSENT declaration is the one that means both versions");
        field.set(real, "1");
        assertEquals(java.util.Set.of(1), real.getReadSchemaVersions());
    }

    @Test
    public void aTargetAddedLaterWithoutASinkBlocks() {
        when(config.getTargets()).thenReturn(List.of("atlas", "dataplex"));
        LineageDrestReadiness.Readiness verdict = readiness.evaluate();
        assertFalse(verdict.ready());
        assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("dataplex")));
    }

    /** A purge ledger that is present and usable — the ledger has its own tests. */
    private static LineagePurgeLedger availableLedger() {
        LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
        when(ledger.available()).thenReturn(true);
        return ledger;
    }
}
