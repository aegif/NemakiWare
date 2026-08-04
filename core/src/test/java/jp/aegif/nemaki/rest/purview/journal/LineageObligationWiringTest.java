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
            public LineageCatalogEntityProbe.Presence readBackHistorical(
                    HistoricalEntitySnapshot snapshot) {
                return LineageCatalogEntityProbe.Presence.UNKNOWN;
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

    private LineageObligationWiring complete() {
        LineageCatalogObligationStore store = mock(LineageCatalogObligationStore.class);
        LineageCatalogObligationService service = serviceOver(store);
        return new LineageObligationWiring(store, probesFor("atlas", "purview"),
                publishersFor("atlas", "purview"), service,
                new LineageObligationScannerImpl(service),
                new LineageObligationProjectorCollaboratorImpl(service));
    }

    /** Complete but for the two registries under test. */
    private LineageObligationWiring storeAnd(LineageCatalogProbeRegistry probes,
            LineageHistoricalPublisherRegistry publishers) {
        LineageCatalogObligationStore store = mock(LineageCatalogObligationStore.class);
        LineageCatalogObligationService service = serviceOver(store);
        return new LineageObligationWiring(store, probes, publishers, service,
                new LineageObligationScannerImpl(service),
                new LineageObligationProjectorCollaboratorImpl(service));
    }

    /** Complete but for whichever collaborator is switched off. */
    private LineageObligationWiring storeAndCollaborators(boolean withScanner,
            boolean withProjector) {
        LineageCatalogObligationStore store = mock(LineageCatalogObligationStore.class);
        LineageCatalogObligationService service = serviceOver(store);
        return new LineageObligationWiring(store, probesFor("atlas", "purview"),
                publishersFor("atlas", "purview"), service,
                withScanner ? new LineageObligationScannerImpl(service) : null,
                withProjector ? new LineageObligationProjectorCollaboratorImpl(service) : null);
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
        LineageObligationWiring wiring = new LineageObligationWiring(
                mock(LineageCatalogObligationStore.class), probesFor("atlas", "purview"),
                publishersFor("atlas", "purview"), null, null, null);

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
        LineageObligationWiring wiring = new LineageObligationWiring(
                null, probesFor("atlas", "purview"), publishersFor("atlas", "purview"),
                serviceOver(null), null, null);

        assertTrue(wiring.violations(TARGETS).stream().anyMatch(v -> v.contains("store")));
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
                new LineageObligationProjectorCollaboratorImpl(service));

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
                new LineageObligationProjectorCollaboratorImpl(registered));

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
                new LineageObligationProjectorCollaboratorImpl(serviceOver(store)));

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
                new LineageObligationProjectorCollaboratorImpl(service));

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

    /** The check must be meaningful while D-rest is off — that is when it is most useful. */
    @Test
    @DisplayName("the check reads no gate, so it answers with D-rest off")
    public void readsNoGate() {
        // No readiness, no config, no service.active() — construction and evaluation involve
        // nothing that could recurse back into the gate that consumes this.
        assertEquals(List.of(), complete().violations(TARGETS));
    }
}
