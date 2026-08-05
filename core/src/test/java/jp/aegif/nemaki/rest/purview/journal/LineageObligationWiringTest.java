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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The false-green: code present, nothing assembled, and both gates green anyway.
 *
 * <p>{@code catalog:obligations} says the binary contains the machine. Until this check existed,
 * nothing said the machine was wired — so a node with no store, no probe for a configured
 * target and no historical publisher satisfied the barrier's condition 8 and a green D-rest
 * readiness, and would have discovered the gap with v2 writes already open. 4b is a flag flip;
 * that discovery has to happen before the flip, not after it.
 */
public class LineageObligationWiringTest {

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

    private static final Set<String> TARGETS = Set.of("atlas", "purview");

    private LineageCatalogProbeRegistry probesFor(String... targets) {
        Map<String, LineageCatalogEntityProbe> byTarget = new java.util.LinkedHashMap<>();
        for (String target : targets) {
            byTarget.put(target, (t, r, k, qn) -> LineageCatalogEntityProbe.Presence.PRESENT);
        }
        return new LineageCatalogProbeRegistry(byTarget);
    }

    private LineageHistoricalPublisherRegistry publishersFor(String... targets) {
        Map<String, LineageHistoricalEntityPublisher> byTarget = new java.util.LinkedHashMap<>();
        for (String target : targets) {
            byTarget.put(target, noOpPublisher());
        }
        return new LineageHistoricalPublisherRegistry(byTarget);
    }

    /**
     * A service over a known store, so the wiring's identity comparisons have something real
     * to compare. Not a mock: {@code storeRef()} has to return the store that was passed in.
     */
    private static LineageCatalogObligationService serviceOver(
            LineageCatalogObligationStore store) {
        return new LineageCatalogObligationService(store,
                (t, r, k, qn) -> LineageCatalogEntityProbe.Presence.PRESENT,
                mock(LineageDrestReadiness.class), mock(LineageNodeIdentity.class),
                () -> 0L);
    }

    /**
     * A complete machine, with named holes.
     *
     * <p>A builder rather than eleven positional arguments repeated per test: the point of each
     * case is which single collaborator is missing, and that has to be readable.
     */
    private final class Assembly {
        LineageCatalogObligationStore store = mock(LineageCatalogObligationStore.class);
        LineageCatalogProbeRegistry probes = probesFor("atlas", "purview");
        LineageHistoricalPublisherRegistry publishers = publishersFor("atlas", "purview");
        LineageCatalogObligationService service;
        boolean withScanner = true;
        boolean withProjector = true;
        LineageHistoricalPublishIntentStore intentStore =
                mock(LineageHistoricalPublishIntentStore.class);
        LineageHistoricalCompensationStore compensationStore =
                mock(LineageHistoricalCompensationStore.class);
        LineageHistoricalPublishMachine machine = mock(LineageHistoricalPublishMachine.class);
        LineageSourceDispositionRegistry sources = sourcesForEveryKind();
        LineageCurrentEntityRepublisher republisher = mock(LineageCurrentEntityRepublisher.class);
        /** Comfortably inside half the five-minute fence lease, for every target and kind. */
        LineageOperationBudgetProvider budgets = FixedOperationBudgets.healthy();
        LineagePurgeLedger purgeLedger = availableLedger();

        /** False to leave the ABSENT branch unwired, which is a violation by design. */
        boolean withSettler = true;

        /** A settler whose collaborators are the assembly's own instances. */
        LineageCatalogAbsenceSettler settlerFor(LineageHistoricalPublishMachine forMachine) {
            LineageCatalogAbsenceSettler settler = mock(LineageCatalogAbsenceSettler.class);
            when(settler.waitingSnapshotResolverRef())
                    .thenReturn(mock(LineageWaitingSnapshotResolver.class));
            when(settler.historicalMachineRef()).thenReturn(forMachine);
            when(settler.observedMaterializerRef())
                    .thenReturn(mock(LineageObservedEntityMaterializer.class));
            return settler;
        }

        LineageObligationWiring build() {
            LineageCatalogObligationService wired =
                    service != null ? service : serviceOver(store);
            if (wired != null && wired.settlerRef() == null && withSettler) {
                wired.setAbsenceSettler(settlerFor(machine));
            }
            return new LineageObligationWiring(store, probes, publishers, wired,
                    withScanner ? new LineageObligationScannerImpl(wired) : null,
                    withProjector ? new LineageObligationProjectorCollaboratorImpl(wired) : null,
                    intentStore, compensationStore, machine, sources, republisher, budgets,
                    purgeLedger, java.util.Set.of());
        }
    }

    /** An authoritative source resolver for every kind, so the per-kind check passes. */
    private static LineageSourceDispositionRegistry sourcesForEveryKind() {
        Map<EndpointKind, LineageSourceDispositionResolver> byKind =
                new java.util.EnumMap<>(EndpointKind.class);
        for (EndpointKind kind : EndpointKind.values()) {
            byKind.put(kind, (repositoryId, k, qn)
                    -> LineageSourceDispositionResolver.SourceEvidence.unknown(0L));
        }
        return new LineageSourceDispositionRegistry(byKind, () -> 0L);
    }

    private LineageObligationWiring complete() {
        return new Assembly().build();
    }

    /** Complete but for the two registries under test. */
    private LineageObligationWiring storeAnd(LineageCatalogProbeRegistry probes,
            LineageHistoricalPublisherRegistry publishers) {
        Assembly assembly = new Assembly();
        assembly.probes = probes;
        assembly.publishers = publishers;
        return assembly.build();
    }

    /** Complete but for whichever collaborator is switched off. */
    private LineageObligationWiring storeAndCollaborators(boolean withScanner,
            boolean withProjector) {
        Assembly assembly = new Assembly();
        assembly.withScanner = withScanner;
        assembly.withProjector = withProjector;
        return assembly.build();
    }

    @Test
    @DisplayName("a fully assembled machine has no violations")
    public void assembledIsClean() {
        assertEquals(List.of(), complete().violations(TARGETS));
    }

    /** The exact false-green: the capability is declared and there is nothing behind it. */
    @Test
    @DisplayName("no service bean is a violation, not a green gate")
    public void missingServiceIsRed() {
        Assembly assembly = new Assembly();
        assembly.service = null;
        assembly.withScanner = false;
        assembly.withProjector = false;
        LineageObligationWiring wiring = new LineageObligationWiring(assembly.store,
                assembly.probes, assembly.publishers, null, null, null, assembly.intentStore,
                assembly.compensationStore, assembly.machine, assembly.sources,
                assembly.republisher, assembly.budgets, assembly.purgeLedger, java.util.Set.of());

        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("obligation service")));
    }

    @Test
    @DisplayName("a store with no probe is a violation")
    public void storeWithoutProbeIsRed() {
        LineageObligationWiring wiring = storeAnd(null, publishersFor("atlas", "purview"));

        List<String> violations = wiring.violations(TARGETS);
        assertTrue(violations.stream().anyMatch(v -> v.contains("probe registry")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("'atlas'")));
    }

    /** Partial coverage is the dangerous case: one target works and the other silently cannot. */
    @Test
    @DisplayName("a probe for only some targets is a violation naming the missing one")
    public void partialProbeCoverageIsRed() {
        LineageObligationWiring wiring = storeAnd(probesFor("atlas"), publishersFor("atlas", "purview"));

        List<String> violations = wiring.violations(TARGETS);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("probe"));
        assertTrue(violations.get(0).contains("purview"));
    }

    /**
     * Without a historical publisher a purged source's obligation can never leave PENDING —
     * the consumer would retry a source that is never coming back.
     */
    @Test
    @DisplayName("a missing historical publisher is a violation naming the target")
    public void missingHistoricalPublisherIsRed() {
        LineageObligationWiring wiring = storeAnd(probesFor("atlas", "purview"), publishersFor("atlas"));

        List<String> violations = wiring.violations(TARGETS);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("historical entity publisher"));
        assertTrue(violations.get(0).contains("purview"));
    }

    @Test
    @DisplayName("an unwired scanner is a violation")
    public void missingScannerIsRed() {
        LineageObligationWiring wiring = storeAndCollaborators(false, true);

        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("scanner/reclaimer")));
    }

    @Test
    @DisplayName("an unwired projector collaborator is a violation")
    public void missingProjectorCollaboratorIsRed() {
        LineageObligationWiring wiring = storeAndCollaborators(true, false);

        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("projector")));
    }

    @Test
    @DisplayName("a missing store is a violation")
    public void missingStoreIsRed() {
        Assembly assembly = new Assembly();
        assembly.store = null;
        assembly.service = serviceOver(null);
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("store")));
    }

    /**
     * A node publishing lineage nowhere owes nothing. Asserted so the empty case is a decision
     * rather than a loop that happened not to run.
     */
    @Test
    @DisplayName("no configured targets means no per-target violations")
    public void noTargetsMeansNoPerTargetViolations() {
        assertEquals(List.of(), complete().violations(Set.of()));
        assertEquals(List.of(), complete().violations(null));
    }

    /**
     * The scanner and the projector must drive the SAME service, or one would resolve
     * obligations the other never sees.
     */
    @Test
    @DisplayName("sharing is identity, not merely having a service")
    public void serviceSharingIsIdentity() {
        LineageCatalogObligationStore store = mock(LineageCatalogObligationStore.class);
        LineageCatalogObligationService service = serviceOver(store);
        LineageObligationWiring wiring = new LineageObligationWiring(store,
                probesFor("atlas", "purview"), publishersFor("atlas", "purview"), service,
                new LineageObligationScannerImpl(service),
                new LineageObligationProjectorCollaboratorImpl(service),
                mock(LineageHistoricalPublishIntentStore.class),
                mock(LineageHistoricalCompensationStore.class),
                mock(LineageHistoricalPublishMachine.class), sourcesForEveryKind(),
                mock(LineageCurrentEntityRepublisher.class), FixedOperationBudgets.healthy(),
                availableLedger(), java.util.Set.of());

        assertTrue(wiring.sharesService(service));
        assertFalse(wiring.sharesService(serviceOver(store)));
        assertFalse(wiring.sharesService(null));
    }

    /**
     * The failure a presence check cannot see: nothing is null, and nothing works.
     *
     * <p>A scanner resolving obligations in one service while the projector waits on another
     * leaves both halves looking wired and neither able to see the other's state.
     */
    @Test
    @DisplayName("a scanner driving a different service instance is a violation")
    public void scannerOnAnotherServiceIsRed() {
        LineageCatalogObligationStore store = mock(LineageCatalogObligationStore.class);
        LineageCatalogObligationService registered = serviceOver(store);
        LineageCatalogObligationService other = serviceOver(store);

        LineageObligationWiring wiring = new LineageObligationWiring(store,
                probesFor("atlas", "purview"), publishersFor("atlas", "purview"), registered,
                new LineageObligationScannerImpl(other),
                new LineageObligationProjectorCollaboratorImpl(registered),
                mock(LineageHistoricalPublishIntentStore.class),
                mock(LineageHistoricalCompensationStore.class),
                mock(LineageHistoricalPublishMachine.class), sourcesForEveryKind(),
                mock(LineageCurrentEntityRepublisher.class), FixedOperationBudgets.healthy(),
                availableLedger(), java.util.Set.of());

        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("scanner drives a different service")),
                wiring.violations(TARGETS).toString());
    }

    @Test
    @DisplayName("a projector on a different service instance is a violation")
    public void projectorOnAnotherServiceIsRed() {
        LineageCatalogObligationStore store = mock(LineageCatalogObligationStore.class);
        LineageCatalogObligationService registered = serviceOver(store);

        LineageObligationWiring wiring = new LineageObligationWiring(store,
                probesFor("atlas", "purview"), publishersFor("atlas", "purview"), registered,
                new LineageObligationScannerImpl(registered),
                new LineageObligationProjectorCollaboratorImpl(serviceOver(store)),
                mock(LineageHistoricalPublishIntentStore.class),
                mock(LineageHistoricalCompensationStore.class),
                mock(LineageHistoricalPublishMachine.class), sourcesForEveryKind(),
                mock(LineageCurrentEntityRepublisher.class), FixedOperationBudgets.healthy(),
                availableLedger(), java.util.Set.of());

        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("projector's obligation collaborator")),
                wiring.violations(TARGETS).toString());
    }

    /** Two halves addressing different documents is not something a null check can find. */
    @Test
    @DisplayName("a service reading a different store is a violation")
    public void serviceOnAnotherStoreIsRed() {
        LineageCatalogObligationStore registered = mock(LineageCatalogObligationStore.class);
        LineageCatalogObligationService service =
                serviceOver(mock(LineageCatalogObligationStore.class));

        LineageObligationWiring wiring = new LineageObligationWiring(registered,
                probesFor("atlas", "purview"), publishersFor("atlas", "purview"), service,
                new LineageObligationScannerImpl(service),
                new LineageObligationProjectorCollaboratorImpl(service),
                mock(LineageHistoricalPublishIntentStore.class),
                mock(LineageHistoricalCompensationStore.class),
                mock(LineageHistoricalPublishMachine.class), sourcesForEveryKind(),
                mock(LineageCurrentEntityRepublisher.class), FixedOperationBudgets.healthy(),
                availableLedger(), java.util.Set.of());

        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("different store")),
                wiring.violations(TARGETS).toString());
    }

    /**
     * Two beans claiming one target is not a preference to resolve — it is a deployment where
     * nobody can say which catalog a historical entity was written to.
     */
    @Test
    @DisplayName("duplicate publisher targets are refused at construction")
    public void duplicatePublisherTargetsAreRefused() {
        LineageHistoricalEntityPublisher first = noOpPublisher();
        LineageHistoricalEntityPublisher second = noOpPublisher();

        Map<String, LineageHistoricalEntityPublisher> colliding =
                new java.util.LinkedHashMap<>();
        colliding.put("atlas", first);
        colliding.put("Atlas", second);

        IllegalStateException refusal = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new LineageHistoricalPublisherRegistry(colliding));
        assertTrue(refusal.getMessage().contains("claim target"));
    }

    @Test
    @DisplayName("a blank or null-valued publisher registration is refused")
    public void malformedPublisherRegistrationIsRefused() {
        Map<String, LineageHistoricalEntityPublisher> blank = new java.util.LinkedHashMap<>();
        blank.put("  ", noOpPublisher());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new LineageHistoricalPublisherRegistry(blank));

        Map<String, LineageHistoricalEntityPublisher> nullValued = new java.util.LinkedHashMap<>();
        nullValued.put("atlas", null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new LineageHistoricalPublisherRegistry(nullValued));
    }

    /** Lookup is exact, so a differently-cased registration never silently matches. */
    @Test
    @DisplayName("a publisher never answers for another target")
    public void publisherLookupIsExact() {
        LineageHistoricalPublisherRegistry registry = publishersFor("atlas");

        assertTrue(registry.canPublish("atlas"));
        assertFalse(registry.canPublish("purview"));
        assertFalse(registry.canPublish("Atlas"));
        assertFalse(registry.canPublish(null));
        org.junit.jupiter.api.Assertions.assertNull(registry.publisherFor("purview"));
    }

    /** Without it, a crash during publication leaves nothing to recover from. */
    @Test
    @DisplayName("a missing intent store is a violation")
    public void missingIntentStoreIsRed() {
        Assembly assembly = new Assembly();
        assembly.intentStore = null;
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("intent store")));
    }

    /** Without it, a wrong historical entity is never revisited. */
    @Test
    @DisplayName("a missing compensation store is a violation")
    public void missingCompensationStoreIsRed() {
        Assembly assembly = new Assembly();
        assembly.compensationStore = null;
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("compensation store")));
    }

    @Test
    @DisplayName("a missing historical publish machine is a violation")
    public void missingMachineIsRed() {
        Assembly assembly = new Assembly();
        assembly.machine = null;
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("historical publish machine")));
    }

    /** A compensation that cannot converge is a record of a problem, not a fix for one. */
    @Test
    @DisplayName("a missing current-entity republisher is a violation")
    public void missingRepublisherIsRed() {
        Assembly assembly = new Assembly();
        assembly.republisher = null;
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("republisher")));
    }

    /**
     * A kind with no authoritative resolver can never reach SOURCE_PURGED, so its obligations
     * retry for ever. Partial coverage is the dangerous case, so each is named.
     */
    @Test
    @DisplayName("a kind with no authoritative source resolver is a violation naming it")
    public void missingSourceResolverForOneKindIsRed() {
        Map<EndpointKind, LineageSourceDispositionResolver> byKind =
                new java.util.EnumMap<>(EndpointKind.class);
        for (EndpointKind kind : EndpointKind.values()) {
            if (kind != EndpointKind.CMIS_FOLDER) {
                byKind.put(kind, (repositoryId, k, qn)
                        -> LineageSourceDispositionResolver.SourceEvidence.unknown(0L));
            }
        }
        Assembly assembly = new Assembly();
        assembly.sources = new LineageSourceDispositionRegistry(byKind, () -> 0L);

        List<String> violations = assembly.build().violations(TARGETS);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("CMIS_FOLDER"));
    }

    @Test
    @DisplayName("no source disposition registry at all is a violation")
    public void missingSourceRegistryIsRed() {
        Assembly assembly = new Assembly();
        assembly.sources = null;
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("source disposition registry")));
    }

    /**
     * A catalog call that can outlast its fence is a call that may still be writing when
     * another intent takes the subject and writes it too.
     */
    @Test
    @DisplayName("a section that does not fit inside the fence lease is a violation")
    public void budgetMustFitInsideTheFenceLease() {
        Assembly tooLong = new Assembly();
        // The fence lease is five minutes; a five-minute request leaves no margin at all.
        tooLong.budgets = new FixedOperationBudgets(
                LineageHistoricalPublishMachine.INTENT_LEASE::toMillis, null);
        assertTrue(tooLong.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("does not fit inside the subject fence lease")),
                tooLong.build().violations(TARGETS).toString());
    }

    /**
     * The number that matters is the section's, not the largest request's.
     *
     * <p>This is the case the earlier single-read-timeout check passed: every individual call
     * fits comfortably, and the section they belong to does not.
     */
    @Test
    @DisplayName("retries and the second catalog call can push a fitting timeout over the lease")
    public void retriesCanPushAFittingTimeoutOver() {
        long lease = LineageHistoricalPublishMachine.INTENT_LEASE.toMillis();
        long margin = (long) (lease * LineageObligationWiring.FENCE_SAFETY_FACTOR);
        long readTimeout = 60_000L;
        // On its own the read timeout is inside the lease with room to spare.
        assertTrue(readTimeout + margin < lease);

        Assembly assembly = new Assembly();
        assembly.budgets = (target, kind) -> java.util.Optional.of(
                new LineageOperationBudget(target, kind, 2_000L, readTimeout, 3,
                        7_700L, 5_000L, 2_000L));
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("does not fit inside the subject fence lease")),
                "the whole section must be budgeted, not its largest single request");
    }

    /** Read timeout alone fits; publish plus read-back does not. */
    @Test
    @DisplayName("one call fits, two do not")
    public void secondCatalogCallIsCounted() {
        long lease = LineageHistoricalPublishMachine.INTENT_LEASE.toMillis();
        long margin = (long) (lease * LineageObligationWiring.FENCE_SAFETY_FACTOR);
        // Sized so that one call is inside the margin and two are not.
        long perCall = margin - 1_000L;
        Assembly assembly = new Assembly();
        assembly.budgets = (target, kind) -> java.util.Optional.of(
                new LineageOperationBudget(target, kind, 1L, perCall - 1L, 0, 0L, 0L, 1_000L));
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("does not fit inside the subject fence lease")));
    }

    /**
     * Targets are budgeted separately.
     *
     * <p>Atlas and Purview are configured independently. A check that read one number would
     * pass a node whose second target is configured far more slowly than its first.
     */
    @Test
    @DisplayName("each target is budgeted from its own configuration")
    public void targetsAreBudgetedIndependently() {
        Assembly assembly = new Assembly();
        long lease = LineageHistoricalPublishMachine.INTENT_LEASE.toMillis();
        assembly.budgets = (target, kind) -> java.util.Optional.of(
                "purview".equals(target)
                        // Purview is configured slowly enough to outlast the fence...
                        ? new LineageOperationBudget(target, kind, 1_000L, lease, 0, 0L, 0L,
                                1_000L)
                        // ...while Atlas is fine. A single shared number could not say both.
                        : new LineageOperationBudget(target, kind, 500L, 1_000L, 0, 0L, 0L,
                                500L));
        List<String> violations = assembly.build().violations(Set.of("atlas", "purview"));
        assertTrue(violations.stream().anyMatch(v -> v.contains("'purview'")), violations
                .toString());
        assertTrue(violations.stream().noneMatch(v -> v.contains("'atlas'")),
                "atlas fits and must not be dragged down by purview's configuration");
    }

    /** Unmeasured is not "probably fine". */
    @Test
    @DisplayName("an unresolvable budget is a violation, not an assumed default")
    public void unresolvableBudgetIsRed() {
        Assembly unknown = new Assembly();
        unknown.budgets = FixedOperationBudgets.unresolvable();
        assertTrue(unknown.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("no operation budget is resolvable")));

        Assembly missingProvider = new Assembly();
        missingProvider.budgets = null;
        assertTrue(missingProvider.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("no operation budget provider is wired")));
    }

    /** A configuration read that throws is not a small budget. */
    @Test
    @DisplayName("a provider that throws is red, and its message is not echoed")
    public void throwingProviderIsRed() {
        Assembly assembly = new Assembly();
        assembly.budgets = (target, kind) -> {
            throw new IllegalStateException("endpoint=https://secret.purview.azure.com");
        };
        List<String> violations = assembly.build().violations(TARGETS);
        assertTrue(violations.stream().anyMatch(v -> v.contains("no operation budget is"
                + " resolvable")));
        assertTrue(violations.stream().noneMatch(v -> v.contains("secret.purview.azure.com")),
                "a configuration error can carry endpoints and credentials");
    }

    /** Unbounded retries cannot be budgeted, however small the timeouts are. */
    @Test
    @DisplayName("unbounded retries are red even with tiny timeouts")
    public void unboundedRetriesAreRed() {
        Assembly assembly = new Assembly();
        assembly.budgets = (target, kind) -> java.util.Optional.of(
                new LineageOperationBudget(target, kind, 1L, 1L, -1, 0L, 0L, 1L));
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("is not bounded")));
    }

    /**
     * Configuration changed after startup must change the verdict.
     *
     * <p>A timeout captured when the context was built would leave the gate green on a
     * deployment that is no longer safe, until someone restarted it.
     */
    @Test
    @DisplayName("a timeout raised after startup turns a fresh evaluation red")
    public void configurationChangeIsSeenImmediately() {
        long[] readTimeoutMs = {1_000L};
        Assembly assembly = new Assembly();
        assembly.budgets = new FixedOperationBudgets(() -> readTimeoutMs[0], null);
        LineageObligationWiring wiring = assembly.build();
        assertEquals(List.of(), wiring.violations(TARGETS));

        // An administrator raises the read timeout past the fence lease.
        readTimeoutMs[0] = LineageHistoricalPublishMachine.INTENT_LEASE.toMillis();
        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("does not fit inside the subject fence lease")),
                "the same instance must re-read the configuration, not its startup snapshot");

        // And back again, so the gate is not one-way.
        readTimeoutMs[0] = 1_000L;
        assertEquals(List.of(), wiring.violations(TARGETS));
    }

    /** With nothing configured to publish to, there is no fenced section to budget. */
    @Test
    @DisplayName("no configured target means no budget violation")
    public void noTargetsNoBudget() {
        Assembly assembly = new Assembly();
        assembly.budgets = FixedOperationBudgets.unresolvable();
        assertEquals(List.of(), assembly.build().violations(Set.of()));
    }

    /**
     * The ABSENT branch is the only one that writes, so it may not be missing.
     *
     * <p>An unwired settler is safe — the consumer just releases and retries — but it is not a
     * working machine: an obligation whose authoritative publisher will never run would retry
     * for ever, and the events waiting on it would never move.
     */
    @Test
    @DisplayName("an unwired catalog-absence settler is a violation")
    public void unwiredSettlerIsRed() {
        Assembly assembly = new Assembly();
        assembly.withSettler = false;
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("no catalog-absence settler is wired")));
    }

    /**
     * Presence is not enough, for the same reason as the scanner and the projector.
     *
     * <p>A settler publishing through a different historical machine than the one wired here
     * writes intents that this node's recovery never looks at — nothing is null, and a crash
     * mid-publish is unrecoverable.
     */
    @Test
    @DisplayName("a settler driving a different historical machine is a violation")
    public void settlerMustShareTheMachine() {
        Assembly assembly = new Assembly();
        assembly.service = serviceOver(assembly.store);
        assembly.service.setAbsenceSettler(
                assembly.settlerFor(mock(LineageHistoricalPublishMachine.class)));
        assertTrue(assembly.build().violations(TARGETS).stream()
                .anyMatch(v -> v.contains("different historical publish machine")));
    }

    @Test
    @DisplayName("a settler missing either collaborator is a violation, named")
    public void settlerCollaboratorsAreNamed() {
        Assembly assembly = new Assembly();
        assembly.service = serviceOver(assembly.store);
        LineageCatalogAbsenceSettler partial = mock(LineageCatalogAbsenceSettler.class);
        when(partial.historicalMachineRef()).thenReturn(assembly.machine);
        assembly.service.setAbsenceSettler(partial);
        List<String> violations = assembly.build().violations(TARGETS);
        assertTrue(violations.stream().anyMatch(v -> v.contains("waiting-snapshot")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("observed-entity")));
    }

    /** The check must be meaningful while D-rest is off — that is when it is most useful. */
    @Test
    @DisplayName("the check reads no gate, so it answers with D-rest off")
    public void readsNoGate() {
        // No readiness, no config, no service.active() — construction and evaluation involve
        // nothing that could recurse back into the gate that consumes this.
        assertEquals(List.of(), complete().violations(TARGETS));
    }

    /** A purge ledger that is present and usable — the ledger has its own tests. */
    private static LineagePurgeLedger availableLedger() {
        LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
        when(ledger.available()).thenReturn(true);
        // Every kind covered: the coverage gate has its own test, and here it must not be the
        // thing under test.
        when(ledger.lifecycleCoveredKinds())
                .thenReturn(java.util.Set.of(EndpointKind.values()));
        return ledger;
    }
}
