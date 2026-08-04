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

    private static final Set<String> TARGETS = Set.of("atlas", "purview");

    private LineageCatalogProbeRegistry probesFor(String... targets) {
        Map<String, LineageCatalogEntityProbe> byTarget = new java.util.LinkedHashMap<>();
        for (String target : targets) {
            byTarget.put(target, (t, r, k, qn) -> LineageCatalogEntityProbe.Presence.PRESENT);
        }
        return new LineageCatalogProbeRegistry(byTarget);
    }

    private Map<String, LineageHistoricalEntityPublisher> publishersFor(String... targets) {
        Map<String, LineageHistoricalEntityPublisher> byTarget = new java.util.LinkedHashMap<>();
        for (String target : targets) {
            byTarget.put(target, (t, r, k, qn, snapshot)
                    -> LineageHistoricalEntityPublisher.Outcome.PUBLISHED);
        }
        return byTarget;
    }

    private LineageObligationWiring complete() {
        return new LineageObligationWiring(
                mock(LineageCatalogObligationStore.class),
                probesFor("atlas", "purview"),
                publishersFor("atlas", "purview"),
                mock(LineageCatalogObligationService.class),
                new Object(), new Object());
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
                publishersFor("atlas", "purview"), null, new Object(), new Object());

        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("obligation service")));
    }

    @Test
    @DisplayName("a store with no probe is a violation")
    public void storeWithoutProbeIsRed() {
        LineageObligationWiring wiring = new LineageObligationWiring(
                mock(LineageCatalogObligationStore.class), null,
                publishersFor("atlas", "purview"),
                mock(LineageCatalogObligationService.class), new Object(), new Object());

        List<String> violations = wiring.violations(TARGETS);
        assertTrue(violations.stream().anyMatch(v -> v.contains("probe registry")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("'atlas'")));
    }

    /** Partial coverage is the dangerous case: one target works and the other silently cannot. */
    @Test
    @DisplayName("a probe for only some targets is a violation naming the missing one")
    public void partialProbeCoverageIsRed() {
        LineageObligationWiring wiring = new LineageObligationWiring(
                mock(LineageCatalogObligationStore.class), probesFor("atlas"),
                publishersFor("atlas", "purview"),
                mock(LineageCatalogObligationService.class), new Object(), new Object());

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
        LineageObligationWiring wiring = new LineageObligationWiring(
                mock(LineageCatalogObligationStore.class), probesFor("atlas", "purview"),
                publishersFor("atlas"),
                mock(LineageCatalogObligationService.class), new Object(), new Object());

        List<String> violations = wiring.violations(TARGETS);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("historical entity publisher"));
        assertTrue(violations.get(0).contains("purview"));
    }

    @Test
    @DisplayName("an unwired scanner is a violation")
    public void missingScannerIsRed() {
        LineageObligationWiring wiring = new LineageObligationWiring(
                mock(LineageCatalogObligationStore.class), probesFor("atlas", "purview"),
                publishersFor("atlas", "purview"),
                mock(LineageCatalogObligationService.class), null, new Object());

        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("scanner/reclaimer")));
    }

    @Test
    @DisplayName("an unwired projector collaborator is a violation")
    public void missingProjectorCollaboratorIsRed() {
        LineageObligationWiring wiring = new LineageObligationWiring(
                mock(LineageCatalogObligationStore.class), probesFor("atlas", "purview"),
                publishersFor("atlas", "purview"),
                mock(LineageCatalogObligationService.class), new Object(), null);

        assertTrue(wiring.violations(TARGETS).stream()
                .anyMatch(v -> v.contains("projector")));
    }

    @Test
    @DisplayName("a missing store is a violation")
    public void missingStoreIsRed() {
        LineageObligationWiring wiring = new LineageObligationWiring(
                null, probesFor("atlas", "purview"), publishersFor("atlas", "purview"),
                mock(LineageCatalogObligationService.class), new Object(), new Object());

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
        LineageCatalogObligationService service = mock(LineageCatalogObligationService.class);
        LineageObligationWiring wiring = new LineageObligationWiring(
                mock(LineageCatalogObligationStore.class), probesFor("atlas", "purview"),
                publishersFor("atlas", "purview"), service, new Object(), new Object());

        assertTrue(wiring.sharesService(service));
        assertFalse(wiring.sharesService(mock(LineageCatalogObligationService.class)));
        assertFalse(wiring.sharesService(null));
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
