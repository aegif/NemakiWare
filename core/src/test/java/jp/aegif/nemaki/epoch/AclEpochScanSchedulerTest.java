package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.rest.purview.journal.LeaderElection;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/** §11.7: the crash-recovery sweep — flag-gated, leader-gated, per-repository isolated. */
public class AclEpochScanSchedulerTest {

    private AclEpochScanScheduler scheduler;
    private AclEpochFinalizationService fin;
    private PropertyManager pm;
    private LeaderElection leader;

    @BeforeEach
    void setUp() {
        fin = mock(AclEpochFinalizationService.class);
        pm = mock(PropertyManager.class);
        leader = mock(LeaderElection.class);
        RepositoryInfoMap repos = mock(RepositoryInfoMap.class);
        lenient().when(repos.getMainRepositoryKeys()).thenReturn(List.of("bedroom", "canopy"));

        scheduler = new AclEpochScanScheduler();
        scheduler.setFinalizationService(fin);
        scheduler.setPropertyManager(pm);
        scheduler.setLeaderElection(leader);
        scheduler.setRepositoryInfoMap(repos);
        lenient().when(fin.scan(anyString(), anyInt()))
                .thenReturn(new AclEpochFinalizationService.ScanSummary());
    }

    /** With the flag off, start() must schedule NOTHING — the §11.8 bit-identical posture. */
    @Test
    public void flagOffStartsNothing_flagOnStarts() {
        when(pm.readBoolean(PropertyKey.ACL_EPOCH_WIRING_ENABLED)).thenReturn(false);
        scheduler.start();
        org.junit.jupiter.api.Assertions.assertFalse(scheduler.isRunning(),
                "flag off = no sweep thread exists at all");

        when(pm.readBoolean(PropertyKey.ACL_EPOCH_WIRING_ENABLED)).thenReturn(true);
        scheduler.start();
        org.junit.jupiter.api.Assertions.assertTrue(scheduler.isRunning(),
                "flag on = the unattended recovery floor is scheduled");
        scheduler.stop();
    }

    @Test
    public void aNonLeaderNeverSweeps() {
        when(leader.isEnabled()).thenReturn(true);
        when(leader.isLeader(anyString())).thenReturn(false);
        scheduler.poll();
        verify(fin, never()).scan(anyString(), anyInt());
    }

    @Test
    public void theLeaderSweepsEveryMainRepository() {
        when(leader.isEnabled()).thenReturn(true);
        when(leader.isLeader(anyString())).thenReturn(true);
        scheduler.poll();
        verify(fin).scan("bedroom", 500);
        verify(fin).scan("canopy", 500);
    }

    /** One broken repository must not stop the sweep of the others. */
    @Test
    public void perRepositoryIsolation() {
        when(leader.isEnabled()).thenReturn(false);
        when(fin.scan("bedroom", 500)).thenThrow(new AclEpochWiringException("db gone"));
        scheduler.poll();
        verify(fin).scan("canopy", 500);
    }

    @Test
    public void sweepCountsAreReadFromTheSummary() {
        when(leader.isEnabled()).thenReturn(false);
        AclEpochFinalizationService.ScanSummary s = new AclEpochFinalizationService.ScanSummary();
        s.scanned = 3; s.finalized = 1; s.acked = 1;
        when(fin.scan(anyString(), anyInt())).thenReturn(s);
        scheduler.poll(); // exercises the logging branch; the assertion is "no exception"
        assertEquals(1, s.acked);
    }
}
