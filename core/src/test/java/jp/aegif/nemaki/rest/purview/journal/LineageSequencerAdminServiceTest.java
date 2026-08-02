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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The disabled-by-default sequencer admin entry (F7): refusal-as-data under a red gate,
 * lease-missing vs infrastructure distinction, and the diagnostics payload.
 */
public class LineageSequencerAdminServiceTest {

    private LineageSequencerAdminService service;
    private LineageJournalStore store;
    private LineageSequencingStore sequencing;
    private LineageDrestReadiness readiness;
    private LineageConfig config;

    @BeforeEach
    void setUp() throws Exception {
        service = new LineageSequencerAdminService();
        store = mock(LineageJournalStore.class,
                withSettings().extraInterfaces(LineageSequencingStore.class));
        sequencing = (LineageSequencingStore) store;
        readiness = mock(LineageDrestReadiness.class);
        config = mock(LineageConfig.class);
        when(config.getSequencerLeaseSeconds()).thenReturn(60);
        when(config.getSequencerBatchSize()).thenReturn(100);
        when(config.getSequencerBacklogCap()).thenReturn(1000);
        when(config.getTargets()).thenReturn(List.of());
        set("journalStore", store);
        set("readiness", readiness);
        set("lineageConfig", config);
    }

    private void set(String field, Object value) throws Exception {
        Field f = LineageSequencerAdminService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    @Test
    public void aRedGateRefusesTheRunWithItsViolationsAndTouchesNothing() {
        when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(false,
                List.of("lineage.drest.enabled is false")));
        var outcome = service.run("bedroom");
        assertFalse(outcome.ran());
        assertEquals(List.of("lineage.drest.enabled is false"), outcome.violations());
        verify(sequencing, never()).findUnsequencedV2(anyString(), anyInt());
    }

    @Test
    public void statusDistinguishesLeaseMissingFromInfrastructureFailure() {
        when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(false,
                List.of("lineage.drest.enabled is false")));
        when(sequencing.readSequencerLease("bedroom")).thenReturn(Optional.empty());
        when(sequencing.findUnsequencedV2(anyString(), anyInt())).thenReturn(List.of());

        Map<String, Object> status = service.status("bedroom");
        assertEquals(false, status.get("enabled"));
        assertEquals(false, status.get("leasePresent"));
        assertTrue(((String) status.get("hint")).contains("Bootstrap"),
                "lease-missing points at the bootstrap patch, not at a 503");

        when(sequencing.readSequencerLease("bedroom")).thenThrow(
                new LineageSequencingStore.SequencingStorageException("couch down", null));
        assertThrows(IllegalStateException.class, () -> service.status("bedroom"),
                "infrastructure failure is 503 territory, never 'lease missing'");
    }

    /** Round-3 fix 2: diagnostics survive a misconfigured cap — clamped, never overflowed. */
    @Test
    public void statusClampsAnInsaneBacklogCapInsteadOfOverflowing() {
        when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(false,
                List.of("lineage.sequencer.backlog-cap must be in [1, 1000000], got"
                        + " 2147483647")));
        when(config.getSequencerBacklogCap()).thenReturn(Integer.MAX_VALUE);
        when(sequencing.readSequencerLease("bedroom")).thenReturn(Optional.empty());
        when(sequencing.findUnsequencedV2("bedroom", 1_000_001)).thenReturn(List.of());
        Map<String, Object> status = service.status("bedroom");
        assertEquals(0, status.get("unsequencedBacklog"));
        verify(sequencing).findUnsequencedV2("bedroom", 1_000_001);
    }

    /** A green gate actually runs the fenced sequencer and returns its summary. */
    @Test
    public void aGreenGateRunsAndReturnsTheRealSummary() {
        when(readiness.evaluate()).thenReturn(
                new LineageDrestReadiness.Readiness(true, List.of()));
        // A held foreign lease makes runOnce return immediately with lostLease semantics —
        // enough to cover the success-path plumbing without a full sequencing fixture.
        when(sequencing.acquireSequencerLease(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        var outcome = service.run("bedroom");
        assertTrue(outcome.ran());
        assertTrue(outcome.violations().isEmpty());
        assertTrue(outcome.summary() != null);
    }

    /** probe == cap is NOT at-cap: the boundary is exact. */
    @Test
    public void backlogAtCapBoundaryIsExact() {
        when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(false,
                List.of("lineage.drest.enabled is false")));
        when(config.getSequencerBacklogCap()).thenReturn(2);
        when(sequencing.readSequencerLease("bedroom")).thenReturn(Optional.empty());
        when(sequencing.findUnsequencedV2("bedroom", 3)).thenReturn(List.of(
                mock(LineageJournalRowV2.class), mock(LineageJournalRowV2.class)));
        Map<String, Object> status = service.status("bedroom");
        assertEquals(2, status.get("unsequencedBacklog"));
        assertEquals(false, status.get("unsequencedBacklogAtCap"),
                "exactly cap visible means not over cap");
    }

    @Test
    public void statusReportsTheBacklogProbeAtCapPlusOne() {
        when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(false,
                List.of("lineage.drest.enabled is false")));
        when(config.getSequencerBacklogCap()).thenReturn(2);
        when(sequencing.readSequencerLease("bedroom")).thenReturn(Optional.of(
                new LineageSequencingStore.LeaseView(3L, "tok", "node-a",
                        "2026-08-01T00:00:00Z")));
        when(sequencing.findUnsequencedV2("bedroom", 3)).thenReturn(List.of(
                mock(LineageJournalRowV2.class), mock(LineageJournalRowV2.class),
                mock(LineageJournalRowV2.class)));

        Map<String, Object> status = service.status("bedroom");
        assertEquals(2, status.get("unsequencedBacklog"));
        assertEquals(true, status.get("unsequencedBacklogAtCap"),
                "a probe bounded by its own limit cannot see past itself — cap+1 can");
    }
}
