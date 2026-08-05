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
                    resolverFor(EndpointKind.EXTERNAL_ASSET), machine, materializer, null);

            var verdict = settler.settle(obligation(EndpointKind.EXTERNAL_ASSET));

            switch (outcome) {
                case MATCHED, MATERIALIZED -> assertEquals(
                        LineageCatalogAbsenceSettler.Verdict.RESOLVED, verdict);
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
                    resolverFor(EndpointKind.CMIS_DOCUMENT), machine, materializer, sources);

            assertEquals(LineageCatalogAbsenceSettler.Verdict.RETRY,
                    settler.settle(obligation(EndpointKind.CMIS_DOCUMENT)),
                    disposition + " must not license a tombstone");
            verify(machine, never()).publish(any(), any(), any(), any());
            // And a LEDGERED kind must never take the observed path.
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
                mock(LineageObservedEntityMaterializer.class), null);

        assertEquals(LineageCatalogAbsenceSettler.Verdict.INDETERMINATE,
                settler.settle(obligation(EndpointKind.EXTERNAL_ASSET)));
        assertEquals(LineageCatalogAbsenceSettler.Verdict.INDETERMINATE,
                settler.settle(obligation(EndpointKind.EXTERNAL_ASSET)));

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

        assertEquals(LineageCatalogAbsenceSettler.Verdict.INDETERMINATE,
                new PolicyRoutedAbsenceSettler(resolver, machine, materializer, null)
                        .settle(obligation(EndpointKind.EXTERNAL_ASSET)));
        verify(machine, never()).publish(any(), any(), any(), any());
        verify(materializer, never()).materialize(any());
    }

    // ------------------------------------------------------------------

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
