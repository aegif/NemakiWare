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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every endpoint kind against every source verdict, checked for what must never happen.
 *
 * <h2>Why invariants rather than a table of expected answers</h2>
 *
 * <p>A written-out expectation per cell is a copy of the implementation: it passes whenever the
 * code and the table were changed together, which is exactly when a routing mistake is made. So
 * what is asserted here are the properties that hold for every cell and could not be restored by
 * editing one — a kind NemakiWare never destroys never gets a tombstone, a kind it does destroy
 * is never settled from the event's own copy, and no route ever stores another route's outcome.
 *
 * <p>The kinds come from {@code EndpointKind.values()} rather than a list, so a kind added later
 * is in the matrix before anyone remembers to add it.
 */
class LineageAbsenceMatrixTest {

    private static final String TARGET = "atlas";
    private static final String REPO = "bedroom";

    /** One valid endpoint per kind, built by the production factories. */
    private static LineageEndpoint endpointFor(EndpointKind kind) {
        return switch (kind) {
            case CMIS_DOCUMENT -> LineageEndpoint.document(REPO, "doc-1", "a.txt");
            case CMIS_FOLDER -> LineageEndpoint.folder(REPO, "f-1", "Folder");
            case ARCHIVE -> LineageEndpoint.archive(REPO, "arc-1", "doc-1", 1L);
            case EXTERNAL_ASSET -> LineageEndpoint.externalAsset(REPO, "s3://bucket/obj-1", "s3");
            case CLOUD_OBJECT -> LineageEndpoint.cloudObject(REPO, "gdrive", "file-1");
            case COLD_STORAGE -> LineageEndpoint.coldStorage(REPO, "cold-ref-1", "glacier");
            case IMPORT_ARTIFACT ->
                    LineageEndpoint.importArtifact(REPO, "op-1", "FULL", Map.of());
            case EXPORT_ARTIFACT ->
                    LineageEndpoint.exportArtifact(REPO, "op-1", "ZIP", "export.zip", 3L);
        };
    }

    private static LineageWaitingSnapshot snapshotFor(EndpointKind kind) {
        LineageEndpoint endpoint = endpointFor(kind);
        return LineageWaitingSnapshot.of(TARGET, REPO, kind, endpoint.catalogQualifiedName(),
                endpoint.attributes(), LineageSourceDisposition.SOURCE_UNKNOWN,
                LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION);
    }

    private static LineageCatalogObligation obligationFor(EndpointKind kind) {
        String qn = endpointFor(kind).catalogQualifiedName();
        return new LineageCatalogObligation(null,
                LineageCatalogObligation.taskKey(TARGET, REPO, kind, qn), TARGET, REPO, kind, qn,
                LineageCatalogObligation.State.CLAIMED, "node-1", "tok-1", 0L, 0L, 0, 1_000L,
                LineageCatalogObligation.Outcome.NONE, null, null);
    }

    private static LineageWaitingSnapshotResolver resolverFor(EndpointKind kind) {
        LineageWaitingSnapshotResolver resolver = mock(LineageWaitingSnapshotResolver.class);
        when(resolver.resolve(any())).thenReturn(
                new LineageWaitingSnapshotResolver.Resolution.LatestWaitingSnapshot(
                        snapshotFor(kind), 0,
                        new LineageObservationProvenance(
                                LineageObservationProvenance.LineageDeliveryKind.ORIGINAL,
                                "d-1", "d-1", 7L, 7L, "2026-08-05T00:00:00Z", null)));
        return resolver;
    }

    /** A source registry answering with one disposition, and a projection when it is live. */
    private static LineageSourceDispositionRegistry sourcesSaying(EndpointKind kind,
            LineageSourceDisposition disposition) {
        String qn = endpointFor(kind).catalogQualifiedName();
        LineageSourceDispositionResolver.SourceEvidence evidence =
                disposition == LineageSourceDisposition.SOURCE_UNKNOWN
                        ? LineageSourceDispositionResolver.SourceEvidence.unknown(1_000L)
                        : LineageSourceDispositionResolver.SourceEvidence.of(REPO, kind, qn,
                                disposition, "inc-1", "rev-1", null, 1_000L);
        Map<String, Object> projection = disposition == LineageSourceDisposition.SOURCE_EXISTS
                ? Map.of("typeName", kind.atlasTypeName(),
                        "attributes", Map.of("qualifiedName", qn))
                : null;
        LineageSourceDispositionRegistry sources = mock(LineageSourceDispositionRegistry.class);
        when(sources.dispositionOf(any(), any(), any())).thenReturn(evidence);
        when(sources.observeLive(any(), any(), any())).thenReturn(
                new LineageSourceDispositionResolver.LiveSourceObservation(evidence, projection));
        return sources;
    }

    private static LineageObservedEntityMaterializerRegistry materializerSaying(
            LineageObservedEntityMaterializer.Outcome outcome) {
        LineageObservedEntityMaterializer materializer =
                mock(LineageObservedEntityMaterializer.class);
        when(materializer.materialize(any())).thenReturn(outcome);
        when(materializer.materializeCurrent(any(), any())).thenReturn(outcome);
        return new LineageObservedEntityMaterializerRegistry(Map.of(TARGET, materializer));
    }

    // ------------------------------------------------------------------

    /**
     * A tombstone is only ever written for a source somebody attested was destroyed.
     *
     * <p>The other direction of the same rule matters just as much: a kind NemakiWare never
     * destroys must not reach the historical machine at all, because a purge mark for it could
     * only have come from a compensating cleanup — and acting on one would tombstone an object
     * that is sitting in the external system it belongs to.
     */
    @Test
    @DisplayName("no kind is ever routed to a tombstone it is not eligible for")
    void tombstonesOnlyForLedgeredAndProvenPurges() {
        for (EndpointKind kind : EndpointKind.values()) {
            for (LineageSourceDisposition disposition : LineageSourceDisposition.values()) {
                LineageHistoricalPublishMachine machine =
                        mock(LineageHistoricalPublishMachine.class);
                var settler = new PolicyRoutedAbsenceSettler(resolverFor(kind), machine,
                        materializerSaying(LineageObservedEntityMaterializer.Outcome.MATERIALIZED),
                        sourcesSaying(kind, disposition));

                LineageAbsencePlan plan = settler.prepare(obligationFor(kind));
                String cell = kind + "/" + disposition;

                boolean ledgered = LineagePurgeLifecyclePolicy.canBePurged(kind);
                if (plan instanceof LineageAbsencePlan.HistoricalPurgedPlan) {
                    assertTrue(ledgered, cell + ": only a LEDGERED kind may be tombstoned");
                    assertEquals(LineageSourceDisposition.SOURCE_PURGED, disposition,
                            cell + ": a tombstone needs an attested purge");
                }
                if (plan instanceof LineageAbsencePlan.ObservedPlan) {
                    assertFalse(ledgered, cell + ": a LEDGERED kind is never settled from the"
                            + " event's own copy — an absent entity may mean a purge");
                }
                if (!ledgered) {
                    settler.execute(plan);
                    verify(machine, never()).publish(any(), any(), any(), any());
                }
            }
        }
    }

    /**
     * The durable record says which route settled the obligation, and never another's.
     *
     * <p>A generic "resolved" would store the same thing for a tombstone and an observation, and
     * nothing later could tell them apart — including the operator deciding whether a catalog
     * entity may be trusted.
     */
    @Test
    @DisplayName("each route stores its own outcome, and no route stores another's")
    void outcomesFollowTheRoute() {
        for (EndpointKind kind : EndpointKind.values()) {
            for (LineageSourceDisposition disposition : LineageSourceDisposition.values()) {
                LineageHistoricalPublishMachine machine =
                        mock(LineageHistoricalPublishMachine.class);
                when(machine.publish(any(), any(), any(), any()))
                        .thenReturn(LineageHistoricalPublishMachine.Verdict.RESOLVED_PURGED);
                var settler = new PolicyRoutedAbsenceSettler(resolverFor(kind), machine,
                        materializerSaying(LineageObservedEntityMaterializer.Outcome.MATERIALIZED),
                        sourcesSaying(kind, disposition));

                LineageAbsencePlan plan = settler.prepare(obligationFor(kind));
                LineageCatalogAbsenceSettler.Verdict verdict = settler.execute(plan);
                String cell = kind + "/" + disposition;

                LineageCatalogObligation.Outcome expected = switch (plan) {
                    case LineageAbsencePlan.HistoricalPurgedPlan ignored ->
                            LineageCatalogObligation.Outcome.SOURCE_PURGED;
                    case LineageAbsencePlan.ObservedPlan ignored ->
                            LineageCatalogObligation.Outcome.OBSERVED_MATERIALIZED;
                    case LineageAbsencePlan.CurrentSourcePlan ignored ->
                            LineageCatalogObligation.Outcome
                                    .LIVE_SOURCE_OBSERVATION_MATERIALIZED;
                    default -> null;
                };
                if (verdict.resolves()) {
                    assertEquals(expected, verdict.outcome(), cell
                            + ": the durable outcome must name the route that settled it");
                } else {
                    // Nothing durable, or the one terminal failure — never another route's
                    // success recorded because this one did not finish.
                    assertTrue(verdict.outcome() == null
                                    || verdict == LineageCatalogAbsenceSettler.Verdict
                                            .SNAPSHOT_INCOMPLETE,
                            cell + ": an unfinished route must not store a success");
                }
            }
        }
    }

    /**
     * A retry after a crash reaches the same decision, not a different one.
     *
     * <p>The machine's whole recovery story is that a pass which died halfway is repeated. If a
     * repeat could route differently over unchanged state, the repeat would be a second opinion
     * rather than a resumption — and one of the two would be writing the wrong thing.
     */
    @Test
    @DisplayName("repeating a pass over unchanged state decides the same thing")
    void repeatedPassesAreStable() {
        for (EndpointKind kind : EndpointKind.values()) {
            for (LineageSourceDisposition disposition : LineageSourceDisposition.values()) {
                LineageHistoricalPublishMachine machine =
                        mock(LineageHistoricalPublishMachine.class);
                when(machine.publish(any(), any(), any(), any()))
                        .thenReturn(LineageHistoricalPublishMachine.Verdict.RESOLVED_PURGED);
                var settler = new PolicyRoutedAbsenceSettler(resolverFor(kind), machine,
                        materializerSaying(LineageObservedEntityMaterializer.Outcome.MATERIALIZED),
                        sourcesSaying(kind, disposition));
                String cell = kind + "/" + disposition;

                LineageAbsencePlan first = settler.prepare(obligationFor(kind));
                LineageCatalogAbsenceSettler.Verdict firstVerdict = settler.execute(first);
                LineageAbsencePlan second = settler.prepare(obligationFor(kind));
                LineageCatalogAbsenceSettler.Verdict secondVerdict = settler.execute(second);

                assertEquals(first.getClass(), second.getClass(),
                        cell + ": the route must not change over unchanged state");
                assertEquals(firstVerdict, secondVerdict,
                        cell + ": the verdict must not change over unchanged state");
            }
        }
    }

    /**
     * Nothing external is called for a plan that writes nothing.
     *
     * <p>{@code writesExternally()} is what the caller reads to decide whether the renewal is
     * needed at all. If a NoWrite plan could still reach the catalog, that decision would be
     * made on a false premise.
     */
    @Test
    @DisplayName("a NoWrite plan touches neither the catalog nor the machine")
    void noWritePlansAreInert() {
        for (EndpointKind kind : EndpointKind.values()) {
            for (LineageSourceDisposition disposition : LineageSourceDisposition.values()) {
                LineageHistoricalPublishMachine machine =
                        mock(LineageHistoricalPublishMachine.class);
                LineageObservedEntityMaterializer materializer =
                        mock(LineageObservedEntityMaterializer.class);
                var settler = new PolicyRoutedAbsenceSettler(resolverFor(kind), machine,
                        new LineageObservedEntityMaterializerRegistry(
                                Map.of(TARGET, materializer)),
                        sourcesSaying(kind, disposition));

                LineageAbsencePlan plan = settler.prepare(obligationFor(kind));
                if (plan.writesExternally()) {
                    continue;
                }
                LineageCatalogAbsenceSettler.Verdict verdict = settler.execute(plan);
                String cell = kind + "/" + disposition;
                verify(machine, never()).publish(any(), any(), any(), any());
                verify(materializer, never()).materialize(any());
                verify(materializer, never()).materializeCurrent(any(), any());
                // Nothing durable, with one deliberate exception: SnapshotIncomplete is the
                // NoWrite plan that IS terminal — it says the event can never rebuild the
                // entity, which is a fact about the data rather than about this attempt.
                if (plan instanceof LineageAbsencePlan.NoWrite.SnapshotIncomplete) {
                    assertEquals(LineageCatalogAbsenceSettler.Verdict.SNAPSHOT_INCOMPLETE,
                            verdict, cell);
                } else {
                    assertNull(verdict.outcome(),
                            cell + ": a plan that writes nothing must record nothing");
                }
            }
        }
    }

    /**
     * A complete snapshot is never terminalised because the entity was refused for another
     * reason.
     *
     * <p>{@code SNAPSHOT_INCOMPLETE} is the only verdict that cannot be walked back: it burns
     * the obligation and makes every event waiting on it permanently unprojectable. It must
     * therefore be reserved for the one thing it names — an event that structurally cannot
     * rebuild the entity — and never used as the fallback for "something else said no".
     *
     * <p>The question has to be asked of the entity the snapshot builds, not of the snapshot's
     * own attributes. Several mandatory names are derived by the factory: a CMIS document's
     * {@code repositoryId} and {@code objectId} come from its subject, and its attribute map
     * carries only {@code name}. Asking the raw snapshot made the answer "incomplete" for every
     * LEDGERED kind, always.
     */
    @Test
    @DisplayName("a snapshot that can rebuild its entity is never terminalised")
    void completeSnapshotsAreNeverTerminal() {
        for (EndpointKind kind : EndpointKind.values()) {
            if (!LineagePurgeLifecyclePolicy.canBePurged(kind)) {
                continue;
            }
            // The entity really is complete — the factory supplies what the snapshot does not.
            assertTrue(LineageHistoricalEntityFactory.missingMandatoryAttributes(
                            LineageHistoricalEntityFactory.observedEntityFrom(snapshotFor(kind)),
                            kind).isEmpty(),
                    kind + ": this fixture must be a complete snapshot for the test to mean"
                            + " anything");

            // A purge verdict the historical snapshot refuses — here because the snapshot was
            // taken while the source disposition was still unknown.
            var settler = new PolicyRoutedAbsenceSettler(resolverFor(kind),
                    mock(LineageHistoricalPublishMachine.class),
                    materializerSaying(LineageObservedEntityMaterializer.Outcome.MATERIALIZED),
                    sourcesSaying(kind, LineageSourceDisposition.SOURCE_PURGED));

            LineageAbsencePlan plan = settler.prepare(obligationFor(kind));
            assertFalse(plan instanceof LineageAbsencePlan.NoWrite.SnapshotIncomplete,
                    kind + ": a refusal that is not about the data must stay retryable");
            assertFalse(settler.execute(plan)
                            == LineageCatalogAbsenceSettler.Verdict.SNAPSHOT_INCOMPLETE,
                    kind + ": the obligation must not be burned");
        }
    }

    /** The matrix is derived, so a kind added later is covered without anyone remembering. */
    @Test
    @DisplayName("every kind has a lifecycle classification and a usable endpoint")
    void everyKindIsInTheMatrix() {
        for (EndpointKind kind : EndpointKind.values()) {
            assertTrue(LineagePurgeLifecyclePolicy.of(kind).isPresent(),
                    kind + " has no lifecycle classification, so no route is safe for it");
            LineageEndpoint endpoint = endpointFor(kind);
            assertEquals(kind, endpoint.kind());
            assertNotNull(snapshotFor(kind).evidenceDigest(), kind + " must produce a snapshot");
        }
        Map<Boolean, Integer> byPolicy = new LinkedHashMap<>();
        for (EndpointKind kind : EndpointKind.values()) {
            byPolicy.merge(LineagePurgeLifecyclePolicy.canBePurged(kind), 1, Integer::sum);
        }
        // Both sides are populated: a matrix where every kind fell on one side would exercise
        // only half the routing and still look complete.
        assertTrue(byPolicy.getOrDefault(true, 0) > 0);
        assertTrue(byPolicy.getOrDefault(false, 0) > 0);
    }
}
