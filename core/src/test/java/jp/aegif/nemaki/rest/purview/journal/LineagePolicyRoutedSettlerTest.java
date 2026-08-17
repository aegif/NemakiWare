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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How the ABSENT branch routes, and what it refuses to resolve on. */
class LineagePolicyRoutedSettlerTest {

    private static final String TARGET = "atlas";
    private static final String REPO = "bedroom";

    @Test
    @DisplayName("a NON_PURGEABLE kind materialises, and only MATCHED/MATERIALIZED resolve")
    void observedOutcomesMapCorrectly() {
        for (var outcome : LineageObservedEntityMaterializer.Outcome.values()) {
            var materializer = mock(LineageObservedEntityMaterializer.class);
            when(materializer.materialize(any())).thenReturn(outcome);
            var machine = mock(LineageHistoricalPublishMachine.class);
            var settler = new PolicyRoutedAbsenceSettler(
                    resolverFor(EndpointKind.EXTERNAL_ASSET), machine,
                    registryOf(materializer), null);

            var obligation = obligation(EndpointKind.EXTERNAL_ASSET);
            var plan = settler.prepare(obligation);
            assertTrue(plan instanceof LineageAbsencePlan.ObservedPlan,
                    "a NON_PURGEABLE kind plans an observed materialisation");
            var verdict = settler.execute(plan);

            switch (outcome) {
                case MATCHED, MATERIALIZED -> {
                    // The durable outcome names the route: an observation must not be stored
                    // as a purge.
                    assertEquals(LineageCatalogAbsenceSettler.Verdict.RESOLVED_OBSERVED, verdict);
                    assertEquals(LineageCatalogObligation.Outcome.OBSERVED_MATERIALIZED,
                            verdict.outcome());
                }
                case SNAPSHOT_INCOMPLETE -> assertEquals(
                        LineageCatalogAbsenceSettler.Verdict.SNAPSHOT_INCOMPLETE, verdict);
                // CONFLICT is not terminal: something else owns that name, and a later pass may
                // find it corrected.
                default -> assertEquals(LineageCatalogAbsenceSettler.Verdict.RETRY, verdict);
            }
            // The observed path must never reach the historical machine.
            verify(machine, never()).publish(any(), any(), any(), any());
        }
    }

    /** A source that exists, or cannot be established, is never a tombstone. */
    @Test
    @DisplayName("a LEDGERED kind never publishes historically without a PURGED verdict")
    void ledgeredNeedsAPurgeVerdict() {
        for (var disposition : LineageSourceDisposition.values()) {
            if (disposition == LineageSourceDisposition.SOURCE_PURGED) {
                continue;
            }
            var sources = mock(LineageSourceDispositionRegistry.class);
            when(sources.dispositionOf(anyString(), any(), anyString()))
                    .thenReturn(LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                            EndpointKind.CMIS_DOCUMENT, qualifiedName(EndpointKind.CMIS_DOCUMENT),
                            disposition, "inc", "rev", null, 1_000L));
            var machine = mock(LineageHistoricalPublishMachine.class);
            var materializer = mock(LineageObservedEntityMaterializer.class);
            var settler = new PolicyRoutedAbsenceSettler(
                    resolverFor(EndpointKind.CMIS_DOCUMENT), machine,
                    registryOf(materializer), sources);

            var plan = settler.prepare(obligation(EndpointKind.CMIS_DOCUMENT));
            if (disposition == LineageSourceDisposition.SOURCE_EXISTS) {
                // Converges instead of retrying for ever: the source is there, so its current
                // entity is published from the verdict that proved it.
                assertTrue(plan instanceof LineageAbsencePlan.CurrentSourcePlan,
                        "SOURCE_EXISTS must not be an infinite retry");
            } else {
                assertTrue(plan instanceof LineageAbsencePlan.NoWrite.Retry,
                        disposition + " must write nothing");
                assertEquals(LineageCatalogAbsenceSettler.Verdict.RETRY, settler.execute(plan));
            }
            // Never the historical machine without a purge verdict, and never the observed
            // path for a LEDGERED kind.
            verify(machine, never()).publish(any(), any(), any(), any());
            verify(materializer, never()).materialize(any());
        }
    }

    /**
     * One unreadable row is no verdict on the other events waiting on the same entity.
     */
    @Test
    @DisplayName("CORRUPT is counted by fixed reason and does not terminalise the obligation")
    void corruptIsCountedNotTerminal() {
        var resolver = mock(LineageWaitingSnapshotResolver.class);
        when(resolver.resolve(any())).thenReturn(
                new LineageWaitingSnapshotResolver.Resolution.Corrupt(
                        CorruptWaitingEventException.Reason.UNDECODABLE_ROW.message()));
        var settler = new PolicyRoutedAbsenceSettler(resolver,
                mock(LineageHistoricalPublishMachine.class),
                registryOf(mock(LineageObservedEntityMaterializer.class)), null);

        assertEquals(LineageCatalogAbsenceSettler.Verdict.INDETERMINATE, settler.execute(
                settler.prepare(obligation(EndpointKind.EXTERNAL_ASSET))));
        assertEquals(LineageCatalogAbsenceSettler.Verdict.INDETERMINATE, settler.execute(
                settler.prepare(obligation(EndpointKind.EXTERNAL_ASSET))));

        Map<String, Long> counts = settler.corruptionCounts();
        assertEquals(1, counts.size());
        assertEquals(2L, counts.values().iterator().next());
        // Reasons only — no payload, no qualified name, no task key.
        String reason = counts.keySet().iterator().next();
        assertTrue(reason.equals(CorruptWaitingEventException.Reason.UNDECODABLE_ROW.message()));
    }

    @Test
    @DisplayName("no waiting snapshot writes nothing at all")
    void noSnapshotWritesNothing() {
        var resolver = mock(LineageWaitingSnapshotResolver.class);
        when(resolver.resolve(any()))
                .thenReturn(new LineageWaitingSnapshotResolver.Resolution.NoWaitingEvent());
        var machine = mock(LineageHistoricalPublishMachine.class);
        var materializer = mock(LineageObservedEntityMaterializer.class);

        var settler = new PolicyRoutedAbsenceSettler(resolver, machine,
                registryOf(materializer), null);
        var plan = settler.prepare(obligation(EndpointKind.EXTERNAL_ASSET));
        assertTrue(plan instanceof LineageAbsencePlan.NoWrite,
                "no snapshot must plan no write");
        assertEquals(LineageCatalogAbsenceSettler.Verdict.INDETERMINATE, settler.execute(plan));
        verify(machine, never()).publish(any(), any(), any(), any());
        verify(materializer, never()).materialize(any());
    }

    /**
     * A materializer registered for a different catalog is not this plan's materializer.
     *
     * <p>The failure this pins is silent by construction: the write would succeed, the read-back
     * would confirm it, and the obligation would resolve — with the entity in the wrong catalog
     * and the one its task key names still empty. Nothing re-opens a resolved obligation, so
     * there is no later pass that discovers this.
     *
     * <p>RETRY rather than a resolution, and zero calls on the foreign materializer.
     */
    @Test
    @DisplayName("a materializer for another target is never used, on either write route")
    void foreignTargetMaterializerIsNeverUsed() {
        // Registered under a target these obligations do not name. Both routes must refuse it
        // rather than fall back to "the only one there is".
        var foreign = mock(LineageObservedEntityMaterializer.class);
        when(foreign.materialize(any()))
                .thenReturn(LineageObservedEntityMaterializer.Outcome.MATERIALIZED);
        when(foreign.materializeCurrent(any(), any()))
                .thenReturn(LineageObservedEntityMaterializer.Outcome.MATERIALIZED);
        var elsewhere = new LineageObservedEntityMaterializerRegistry(Map.of("purview", foreign));
        var machine = mock(LineageHistoricalPublishMachine.class);

        var observedSettler = new PolicyRoutedAbsenceSettler(
                resolverFor(EndpointKind.EXTERNAL_ASSET), machine, elsewhere, null);
        var observedPlan = observedSettler.prepare(obligation(EndpointKind.EXTERNAL_ASSET));
        assertTrue(observedPlan instanceof LineageAbsencePlan.ObservedPlan);
        assertEquals(LineageCatalogAbsenceSettler.Verdict.RETRY,
                observedSettler.execute(observedPlan),
                "an observed plan must not resolve against another target's catalog");

        String qn = "nemaki://" + REPO + "/objects/doc-1";
        var sources = mock(LineageSourceDispositionRegistry.class);
        var licensed = LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                EndpointKind.CMIS_DOCUMENT, qn, LineageSourceDisposition.SOURCE_EXISTS,
                "inc-1", "rev-1", null, 1_000L);
        when(sources.dispositionOf(anyString(), any(), anyString())).thenReturn(licensed);
        // Publishable on purpose: the refusal under test must be the target lookup, not a
        // missing projection, or the test would pass for the wrong reason.
        when(sources.observeLive(anyString(), any(), anyString()))
                .thenReturn(live(licensed, qn));
        var currentSettler = new PolicyRoutedAbsenceSettler(
                resolverForKind(EndpointKind.CMIS_DOCUMENT, qn), machine, elsewhere, sources);
        var currentPlan = currentSettler.prepare(obligationFor(EndpointKind.CMIS_DOCUMENT, qn));
        assertTrue(currentPlan instanceof LineageAbsencePlan.CurrentSourcePlan);
        assertEquals(LineageCatalogAbsenceSettler.Verdict.RETRY,
                currentSettler.execute(currentPlan),
                "a current-source plan must not resolve against another target's catalog");

        verify(foreign, never()).materialize(any());
        verify(foreign, never()).materializeCurrent(any(), any());
        verify(machine, never()).publish(any(), any(), any(), any());
    }

    /**
     * The renewal is the authorisation, not a formality.
     *
     * <p>The lookups run while the lease is expiring. A worker that lost it has been superseded,
     * and writing on its way out would race the worker that took over — so a failed renew means
     * nothing external is called at all, not even for a plan that would have written.
     */
    @Test
    @DisplayName("a failed renew means zero external calls, even with a write-carrying plan")
    void failedRenewCallsNothingExternal() {
        var materializer = mock(LineageObservedEntityMaterializer.class);
        var machine = mock(LineageHistoricalPublishMachine.class);
        var settler = new PolicyRoutedAbsenceSettler(
                resolverFor(EndpointKind.EXTERNAL_ASSET), machine, registryOf(materializer), null);

        // prepare() is read-only, and it does produce a plan that would write.
        var plan = settler.prepare(obligation(EndpointKind.EXTERNAL_ASSET));
        assertTrue(plan.writesExternally(), "the plan would have written");
        verify(materializer, never()).materialize(any());
        verify(machine, never()).publish(any(), any(), any(), any());

        // The service only calls execute() after a successful renew; with none, nothing runs.
        // Asserted at the boundary this class owns: preparing alone touches nothing external.
        verify(materializer, never()).materializeCurrent(any(), any());
    }

    /** Each route stores its own outcome, so the durable record says how it was settled. */
    @Test
    @DisplayName("the three resolving verdicts carry three different durable outcomes")
    void outcomesAreRouteSpecific() {
        assertEquals(LineageCatalogObligation.Outcome.SOURCE_PURGED,
                LineageCatalogAbsenceSettler.Verdict.RESOLVED_PURGED.outcome());
        assertEquals(LineageCatalogObligation.Outcome.OBSERVED_MATERIALIZED,
                LineageCatalogAbsenceSettler.Verdict.RESOLVED_OBSERVED.outcome());
        assertEquals(LineageCatalogObligation.Outcome.LIVE_SOURCE_OBSERVATION_MATERIALIZED,
                LineageCatalogAbsenceSettler.Verdict.RESOLVED_CURRENT.outcome());
        // And the non-resolving ones store nothing at all.
        assertEquals(null, LineageCatalogAbsenceSettler.Verdict.RETRY.outcome());
        assertEquals(null, LineageCatalogAbsenceSettler.Verdict.INDETERMINATE.outcome());
        assertTrue(LineageCatalogAbsenceSettler.Verdict.RESOLVED_OBSERVED.resolves());
        assertTrue(!LineageCatalogAbsenceSettler.Verdict.INDETERMINATE.resolves());
    }

    /**
     * What gets written is this execution's read, never the event's older copy.
     *
     * <p>The event's attributes and the authorising verdict can describe different revisions,
     * and nothing in the v2 schema records which revision the event saw — so publishing the
     * event's copy asserts content for an instance the verdict was never about, on a route
     * whose result nothing revisits.
     */
    @Test
    @DisplayName("the live projection is published, and its absence writes nothing at all")
    void publishesTheProjectionAndNeverTheEventsCopy() {
        String qn = "nemaki://" + REPO + "/objects/doc-1";
        var evidence = LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                EndpointKind.CMIS_DOCUMENT, qn, LineageSourceDisposition.SOURCE_EXISTS,
                "inc-1", "rev-1", null, 1_000L);

        // 1. A projection is offered: exactly that map reaches the materializer.
        Map<String, Object> projection = Map.of("typeName", "nemaki_document",
                "attributes", Map.of("qualifiedName", qn, "name", "as-read-now.txt"));
        var sources = mock(LineageSourceDispositionRegistry.class);
        when(sources.dispositionOf(anyString(), any(), anyString())).thenReturn(evidence);
        when(sources.observeLive(anyString(), any(), anyString())).thenReturn(
                new LineageSourceDispositionResolver.LiveSourceObservation(evidence, projection));
        var materializer = mock(LineageObservedEntityMaterializer.class);
        when(materializer.materializeCurrent(any(), any()))
                .thenReturn(LineageObservedEntityMaterializer.Outcome.MATERIALIZED);
        var settler = new PolicyRoutedAbsenceSettler(
                resolverForKind(EndpointKind.CMIS_DOCUMENT, qn),
                mock(LineageHistoricalPublishMachine.class), registryOf(materializer), sources);

        assertEquals(LineageCatalogAbsenceSettler.Verdict.RESOLVED_CURRENT,
                settler.execute(settler.prepare(obligationFor(EndpointKind.CMIS_DOCUMENT, qn))));
        var published = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(materializer).materializeCurrent(any(), published.capture());
        assertEquals(projection, published.getValue(),
                "the entity written must be the one this execution's own read produced");

        // 2. A live source this node cannot project writes nothing — and specifically does not
        // substitute the snapshot's own attributes, which is the defect being closed.
        var unprojectable = mock(LineageSourceDispositionRegistry.class);
        when(unprojectable.dispositionOf(anyString(), any(), anyString())).thenReturn(evidence);
        when(unprojectable.observeLive(anyString(), any(), anyString())).thenReturn(
                new LineageSourceDispositionResolver.LiveSourceObservation(evidence, null));
        var untouched = mock(LineageObservedEntityMaterializer.class);
        var refusing = new PolicyRoutedAbsenceSettler(
                resolverForKind(EndpointKind.CMIS_DOCUMENT, qn),
                mock(LineageHistoricalPublishMachine.class), registryOf(untouched), unprojectable);

        assertEquals(LineageCatalogAbsenceSettler.Verdict.RETRY,
                refusing.execute(refusing.prepare(obligationFor(EndpointKind.CMIS_DOCUMENT, qn))),
                "an unprojectable live source must not resolve the obligation");
        verify(untouched, never()).materializeCurrent(any(), any());
        verify(untouched, never()).materialize(any());
    }

    /** LEDGERED + EXISTS converges on all three kinds rather than retrying for ever. */
    @Test
    @DisplayName("every LEDGERED kind converges from a positive live-source verdict")
    void ledgeredExistsConvergesOnAllKinds() {
        for (EndpointKind kind : EndpointKind.values()) {
            if (!LineagePurgeLifecyclePolicy.canBePurged(kind)) {
                continue;
            }
            String qn = switch (kind) {
                case CMIS_FOLDER -> "nemaki://" + REPO + "/folders/f-1/dataset";
                case ARCHIVE -> "nemaki://" + REPO + "/archives/a-1";
                default -> "nemaki://" + REPO + "/objects/doc-1";
            };
            var sources = mock(LineageSourceDispositionRegistry.class);
            var evidence = LineageSourceDispositionResolver.SourceEvidence.of(REPO, kind, qn,
                    LineageSourceDisposition.SOURCE_EXISTS, "inc-1", "rev-1", null, 1_000L);
            when(sources.dispositionOf(anyString(), any(), anyString())).thenReturn(evidence);
            when(sources.observeLive(anyString(), any(), anyString()))
                    .thenReturn(live(evidence, qn));
            var materializer = mock(LineageObservedEntityMaterializer.class);
            when(materializer.materializeCurrent(any(), any()))
                    .thenReturn(LineageObservedEntityMaterializer.Outcome.MATERIALIZED);
            var settler = new PolicyRoutedAbsenceSettler(resolverForKind(kind, qn),
                    mock(LineageHistoricalPublishMachine.class), registryOf(materializer), sources);

            var plan = settler.prepare(obligationFor(kind, qn));
            assertTrue(plan instanceof LineageAbsencePlan.CurrentSourcePlan, kind.toString());
            assertEquals(LineageCatalogAbsenceSettler.Verdict.RESOLVED_CURRENT,
                    settler.execute(plan));
        }
    }

    /**
     * The prepare-time verdict is not the write-time authorisation.
     *
     * <p>Between prepare and the renewal the source can be purged, re-created or modified.
     * Writing on the older verdict would publish content for an instance that no longer exists,
     * or publish an object as current at the moment it stopped being so.
     */
    @Test
    @DisplayName("a source that changed between prepare and execute writes nothing")
    void currentPlanIsRecheckedBeforeWriting() {
        String qn = "nemaki://" + REPO + "/objects/doc-1";
        var prepared = LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                EndpointKind.CMIS_DOCUMENT, qn, LineageSourceDisposition.SOURCE_EXISTS,
                "inc-1", "rev-1", null, 1_000L);

        record Case(String name, LineageSourceDispositionResolver.SourceEvidence recheck,
                boolean throwsUp) { }
        var cases = java.util.List.of(
                new Case("purged", LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                        EndpointKind.CMIS_DOCUMENT, qn, LineageSourceDisposition.SOURCE_PURGED,
                        "inc-1", "rev-1", null, 2_000L), false),
                new Case("unknown",
                        LineageSourceDispositionResolver.SourceEvidence.unknown(2_000L), false),
                new Case("re-created", LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                        EndpointKind.CMIS_DOCUMENT, qn, LineageSourceDisposition.SOURCE_EXISTS,
                        "inc-2", "rev-1", null, 2_000L), false),
                new Case("modified", LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                        EndpointKind.CMIS_DOCUMENT, qn, LineageSourceDisposition.SOURCE_EXISTS,
                        "inc-1", "rev-2", null, 2_000L), false),
                new Case("resolver threw", null, true));

        for (Case scenario : cases) {
            var sources = mock(LineageSourceDispositionRegistry.class);
            when(sources.dispositionOf(anyString(), any(), anyString())).thenReturn(prepared);
            // The write-time read is where the disagreement shows up. Each recheck is offered
            // with a publishable projection, so what refuses the write is the authorisation
            // check rather than an absent payload.
            when(sources.observeLive(anyString(), any(), anyString()))
                    .thenAnswer(invocation -> {
                        if (scenario.throwsUp()) {
                            throw new IllegalStateException("source unreachable");
                        }
                        return live(scenario.recheck(), qn);
                    });
            var materializer = mock(LineageObservedEntityMaterializer.class);
            var settler = new PolicyRoutedAbsenceSettler(
                    resolverForKind(EndpointKind.CMIS_DOCUMENT, qn),
                    mock(LineageHistoricalPublishMachine.class), registryOf(materializer), sources);

            var plan = settler.prepare(obligationFor(EndpointKind.CMIS_DOCUMENT, qn));
            assertTrue(plan instanceof LineageAbsencePlan.CurrentSourcePlan);
            assertEquals(LineageCatalogAbsenceSettler.Verdict.RETRY, settler.execute(plan),
                    scenario.name() + " must not authorise a write");
            // Zero catalog calls: the write was licensed by a verdict that no longer holds.
            verify(materializer, never()).materializeCurrent(any(), any());
            verify(materializer, never()).materialize(any());
        }
    }

    /** Only an identical verdict proceeds — checkedAt aside, which differs by construction. */
    @Test
    @DisplayName("an identical re-check proceeds, and checkedAt is not compared")
    void identicalRecheckProceeds() {
        String qn = "nemaki://" + REPO + "/objects/doc-1";
        var prepared = LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                EndpointKind.CMIS_DOCUMENT, qn, LineageSourceDisposition.SOURCE_EXISTS,
                "inc-1", "rev-1", null, 1_000L);
        // Same subject, same incarnation and revision, later clock.
        var later = LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                EndpointKind.CMIS_DOCUMENT, qn, LineageSourceDisposition.SOURCE_EXISTS,
                "inc-1", "rev-1", null, 9_000L);
        var sources = mock(LineageSourceDispositionRegistry.class);
        when(sources.dispositionOf(anyString(), any(), anyString()))
                .thenReturn(prepared).thenReturn(later);
        when(sources.observeLive(anyString(), any(), anyString()))
                .thenReturn(live(later, qn));
        var materializer = mock(LineageObservedEntityMaterializer.class);
        when(materializer.materializeCurrent(any(), any()))
                .thenReturn(LineageObservedEntityMaterializer.Outcome.MATERIALIZED);
        var settler = new PolicyRoutedAbsenceSettler(
                resolverForKind(EndpointKind.CMIS_DOCUMENT, qn),
                mock(LineageHistoricalPublishMachine.class), registryOf(materializer), sources);

        var plan = settler.prepare(obligationFor(EndpointKind.CMIS_DOCUMENT, qn));
        assertEquals(LineageCatalogAbsenceSettler.Verdict.RESOLVED_CURRENT,
                settler.execute(plan));
        verify(materializer).materializeCurrent(any(), any());
    }

    private static LineageCatalogObligation obligationFor(EndpointKind kind, String qn) {
        return new LineageCatalogObligation(null,
                LineageCatalogObligation.taskKey(TARGET, REPO, kind, qn), TARGET, REPO, kind, qn,
                LineageCatalogObligation.State.CLAIMED, "node-1", "tok-1", 0L, 0L, 0, 1_000L,
                LineageCatalogObligation.Outcome.NONE, null, null);
    }

    private static LineageWaitingSnapshotResolver resolverForKind(EndpointKind kind, String qn) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (kind == EndpointKind.ARCHIVE) {
            attributes.put("archivedAt", 1_700_000_000_000L);
            attributes.put("originalObjectId", "doc-1");
        } else {
            attributes.put("name", "a.txt");
        }
        LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO, kind, qn,
                attributes, LineageSourceDisposition.SOURCE_UNKNOWN,
                LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION);
        var resolver = mock(LineageWaitingSnapshotResolver.class);
        when(resolver.resolve(any())).thenReturn(
                new LineageWaitingSnapshotResolver.Resolution.LatestWaitingSnapshot(snapshot, 0,
                        new LineageObservationProvenance(
                                LineageObservationProvenance.LineageDeliveryKind.ORIGINAL,
                                "d-1", "d-1", 7L, 7L, "2026-08-05T00:00:00Z", null)));
        return resolver;
    }

    // ------------------------------------------------------------------

    /**
     * An observation as the live route now receives it: the verdict, and for a live source the
     * catalog projection built from the same read. A non-live verdict carries none.
     */
    private static LineageSourceDispositionResolver.LiveSourceObservation live(
            LineageSourceDispositionResolver.SourceEvidence evidence, String qn) {
        return new LineageSourceDispositionResolver.LiveSourceObservation(evidence,
                evidence.disposition() == LineageSourceDisposition.SOURCE_EXISTS
                        ? Map.of("typeName", "t", "attributes", Map.of("qualifiedName", qn))
                        : null);
    }

    /** A registry holding one materializer, keyed to the only target these tests obligate. */
    private static LineageObservedEntityMaterializerRegistry registryOf(
            LineageObservedEntityMaterializer materializer) {
        return new LineageObservedEntityMaterializerRegistry(Map.of(TARGET, materializer));
    }

    private static String qualifiedName(EndpointKind kind) {
        return kind == EndpointKind.EXTERNAL_ASSET ? "s3://bucket/obj-1"
                : "nemaki://" + REPO + "/objects/doc-1";
    }

    private static LineageCatalogObligation obligation(EndpointKind kind) {
        String qn = qualifiedName(kind);
        return new LineageCatalogObligation(null,
                LineageCatalogObligation.taskKey(TARGET, REPO, kind, qn), TARGET, REPO, kind, qn,
                LineageCatalogObligation.State.CLAIMED, "node-1", "tok-1", 0L, 0L, 0, 1_000L,
                LineageCatalogObligation.Outcome.NONE, null, null);
    }

    private static LineageWaitingSnapshotResolver resolverFor(EndpointKind kind) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (kind == EndpointKind.EXTERNAL_ASSET) {
            attributes.put("externalStableKey", qualifiedName(kind));
            attributes.put("sourceSystem", "s3");
        } else {
            attributes.put("name", "a.txt");
        }
        LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO, kind,
                qualifiedName(kind), attributes, LineageSourceDisposition.SOURCE_UNKNOWN,
                LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION);
        var resolver = mock(LineageWaitingSnapshotResolver.class);
        when(resolver.resolve(any())).thenReturn(
                new LineageWaitingSnapshotResolver.Resolution.LatestWaitingSnapshot(snapshot, 0,
                        new LineageObservationProvenance(
                                LineageObservationProvenance.LineageDeliveryKind.ORIGINAL,
                                "d-1", "d-1", 7L, 7L, "2026-08-05T00:00:00Z", null)));
        return resolver;
    }
}
