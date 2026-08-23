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

import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.util.PropertyManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweeper: what it does by default, and what it refuses to do.
 *
 * <p>The two properties that matter are opposites. It must run without being configured, because
 * an unresolved listing that is always empty is indistinguishable from nothing having gone wrong.
 * And it must delete nothing without being configured, because these rows are evidence.
 */
class CaptureIntentSweeperTest {

    private static CaptureIntentSweeper sweeper(CaptureMaintenanceStore store, PropertyManager pm) {
        CaptureIntentSweeper s = new CaptureIntentSweeper();
        s.setMaintenanceStore(store);
        s.setPropertyManager(pm);
        LineageJournalStore journal = mock(LineageJournalStore.class);
        when(journal.isActive()).thenReturn(true);
        s.setJournalStore(journal);
        return s;
    }

    // ── Runs by default ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sweeping happens with no configuration at all")
    void sweepsWithoutConfiguration() {
        // The purge scheduler defaults its cron to empty and so never runs until configured.
        // Copying that here would mean UNRESOLVED never appears and the operator listing is
        // always empty — which reads as "nothing went wrong".
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);

        sweeper(store, null).runOnce();

        verify(store).sweepExpiredIntents(anyLong(), anyInt());
    }

    @Test
    @DisplayName("the defaults are the documented ones — stale is floored by the fetch timeout")
    void defaultsAreAsDocumented() {
        CaptureIntentSweeper s = sweeper(mock(CaptureMaintenanceStore.class), null);

        // 15 configured, but the effective threshold is max(15, fetchTimeout 30 + 5): the old
        // defaults let a fetch's second half be judged dead mid-write (M5, P1-1(e) §4).
        assertEquals(35, s.staleMinutes(),
                "the default stale threshold no longer undercuts the default fetch timeout");
        assertEquals(5, s.intervalMinutes());
        assertEquals(200, s.batchLimit(), "bounded: every replica walks the same batch");
    }

    @Test
    @DisplayName("the floor follows the SAME fetch-timeout config the scheduler reads")
    void staleFloorFollowsFetchTimeout() {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("ingest.scheduler.fetchTimeoutMinutes")).thenReturn("50");
        CaptureIntentSweeper s = sweeper(mock(CaptureMaintenanceStore.class), pm);

        assertEquals(55, s.staleMinutes(),
                "raising the fetch timeout without touching the sweeper reopened the "
                        + "judged-dead-while-fetching window");

        // An operator setting stale ABOVE the floor is still honored.
        when(pm.readValue(CaptureIntentSweeper.KEY_STALE_MINUTES)).thenReturn("120");
        assertEquals(120, s.staleMinutes());
    }

    @Test
    @DisplayName("staleness and interval are separate settings")
    void stalenessAndIntervalAreSeparate() {
        // How often to look and how long to wait are different questions; one key for both would
        // force an operator to make the sweep frequent in order to make the deadline short.
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(CaptureIntentSweeper.KEY_STALE_MINUTES)).thenReturn("60");
        when(pm.readValue(CaptureIntentSweeper.KEY_INTERVAL_MINUTES)).thenReturn("2");
        CaptureIntentSweeper s = sweeper(mock(CaptureMaintenanceStore.class), pm);

        assertEquals(60, s.staleMinutes());
        assertEquals(2, s.intervalMinutes());
    }

    // ── Deletes nothing by default ───────────────────────────────────────────────────────

    @Test
    @DisplayName("no retention is configured, so nothing is deleted")
    void deletesNothingByDefault() {
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);

        sweeper(store, null).runOnce();

        verify(store, never()).purgeUnresolvedOlderThan(anyLong(), anyInt());
        verify(store, never()).purgeCapturedOlderThan(anyLong(), anyInt());
    }

    @Test
    @DisplayName("an unreadable retention setting keeps everything")
    void unreadableRetentionKeepsEverything() {
        // Failing towards deletion would let a configuration outage destroy evidence.
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(anyString())).thenThrow(new RuntimeException("couchdb down"));
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);

        sweeper(store, pm).runOnce();

        verify(store, never()).purgeUnresolvedOlderThan(anyLong(), anyInt());
        verify(store, never()).purgeCapturedOlderThan(anyLong(), anyInt());
    }

    @Test
    @DisplayName("zero and negative both mean keep forever")
    void zeroAndNegativeMeanForever() {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(CaptureIntentSweeper.KEY_RETENTION_CAPTURED)).thenReturn("0");
        when(pm.readValue(CaptureIntentSweeper.KEY_RETENTION_UNRESOLVED)).thenReturn("-5");
        CaptureIntentSweeper s = sweeper(mock(CaptureMaintenanceStore.class), pm);

        assertEquals(0, s.retentionDays(CaptureIntentSweeper.KEY_RETENTION_CAPTURED));
        assertEquals(0, s.retentionDays(CaptureIntentSweeper.KEY_RETENTION_UNRESOLVED));
    }

    @Test
    @DisplayName("a configured retention does delete — the control for the four tests above")
    void configuredRetentionDeletes() {
        // Without this, hard-coding retentionDays to 0 would pass every test above while making
        // the setting do nothing at all.
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(CaptureIntentSweeper.KEY_RETENTION_CAPTURED)).thenReturn("90");
        when(pm.readValue(CaptureIntentSweeper.KEY_RETENTION_UNRESOLVED)).thenReturn("30");
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);

        sweeper(store, pm).runOnce();

        verify(store).purgeCapturedOlderThan(anyLong(), anyInt());
        verify(store).purgeUnresolvedOlderThan(anyLong(), anyInt());
    }

    @Test
    @DisplayName("the two retentions are independent settings")
    void retentionsAreIndependent() {
        // The captured rows are the evidence of successful ingests and the unresolved ones are
        // the evidence of failures; an operator may well want to keep one and not the other.
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(CaptureIntentSweeper.KEY_RETENTION_UNRESOLVED)).thenReturn("30");
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);

        sweeper(store, pm).runOnce();

        verify(store).purgeUnresolvedOlderThan(anyLong(), anyInt());
        verify(store, never()).purgeCapturedOlderThan(anyLong(), anyInt());
    }

    @Test
    @DisplayName("the configuration keys are the documented ones, by literal")
    void keysArePinnedByLiteral() {
        // Every other test here stubs the PropertyManager through the KEY_ constants, and javac
        // inlines a static final String — so a renamed key can leave stale test bytecode passing
        // against the old value. Pinning the literals makes a rename a visible change, and keeps
        // these in step with nemakiware.properties.
        //
        // The prefix is capture-boundary, not capture: lineage.capture.version-events already
        // exists and means something else entirely.
        assertEquals("lineage.capture-boundary.stale.minutes",
                CaptureIntentSweeper.KEY_STALE_MINUTES);
        assertEquals("lineage.capture-boundary.sweep.interval.minutes",
                CaptureIntentSweeper.KEY_INTERVAL_MINUTES);
        assertEquals("lineage.capture-boundary.sweep.batch",
                CaptureIntentSweeper.KEY_BATCH_LIMIT);
        assertEquals("lineage.capture-boundary.retention.unresolved.days",
                CaptureIntentSweeper.KEY_RETENTION_UNRESOLVED);
        assertEquals("lineage.capture-boundary.retention.captured.days",
                CaptureIntentSweeper.KEY_RETENTION_CAPTURED);
    }

    // ── Robustness ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no lineage database means no work and no probing of its own")
    void inactiveJournalStopsTheWork() {
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        CaptureIntentSweeper s = sweeper(store, null);
        LineageJournalStore journal = mock(LineageJournalStore.class);
        when(journal.isActive()).thenReturn(false);
        s.setJournalStore(journal);

        s.runOnce();

        verify(store, never()).sweepExpiredIntents(anyLong(), anyInt());
    }

    @Test
    @DisplayName("a pass that throws does not kill the scheduled task")
    void throwingPassIsContained() {
        // scheduleWithFixedDelay stops rescheduling after an uncaught exception, and nothing
        // reports that it stopped — the sweeper would go quiet and the listing would go empty.
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.sweepExpiredIntents(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("lineage database unreachable"));

        sweeper(store, null).runOnce();   // must not throw

        CaptureIntentSweeper again = sweeper(store, null);
        again.runOnce();                  // and must still be callable
    }

    @Test
    @DisplayName("the deadline passed to the store is in the past by the staleness threshold")
    void deadlineIsComputedFromStaleness() {
        PropertyManager pm = mock(PropertyManager.class);
        // Above the fetch-timeout floor, so the configured value is the effective one.
        when(pm.readValue(CaptureIntentSweeper.KEY_STALE_MINUTES)).thenReturn("60");
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        long before = System.currentTimeMillis();

        sweeper(store, pm).runOnce();

        org.mockito.ArgumentCaptor<Long> cutoff = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(store).sweepExpiredIntents(cutoff.capture(), anyInt());
        long expected = before - 60 * 60_000L;
        assertTrue(cutoff.getValue() <= expected + 5_000 && cutoff.getValue() >= expected - 5_000,
                "cutoff " + cutoff.getValue() + " should be about 60 minutes before " + before);
    }

    @Test
    @DisplayName("with no maintenance store the sweeper simply does not start")
    void noStoreMeansNoSweeper() {
        CaptureIntentSweeper s = new CaptureIntentSweeper();

        s.init();      // must not throw
        s.shutdown();  // nor this
    }

    /** Keeps the unused import honest: the listing type is part of the seam under test. */
    @Test
    @DisplayName("the maintenance seam exposes the listing the admin endpoint needs")
    void seamExposesListing() {
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.listUnresolved(null, 10, 0)).thenReturn(
                new CaptureMaintenanceStore.UnresolvedPage(List.of(Map.of("intentId", "i-1")),
                        10, 0, false));

        assertEquals(1, store.listUnresolved(null, 10, 0).entries().size());
    }
}
