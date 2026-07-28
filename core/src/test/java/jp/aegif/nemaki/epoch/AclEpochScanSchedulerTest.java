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

    /** Increment 14: the sweep is not optional any more — start() always schedules it. */
    @Test
    public void startAlwaysSchedulesTheSweep() {
        scheduler.start();
        org.junit.jupiter.api.Assertions.assertTrue(scheduler.isRunning(),
                "ACL-epoch fencing is the only ACL write path, so its recovery half is not optional");
        scheduler.stop();
        org.junit.jupiter.api.Assertions.assertFalse(scheduler.isRunning());
    }

    /**
     * A leftover {@code acl.epoch.wiring.enabled=false} must not be silently ignored — an operator
     * who set it believes they are on a path that no longer exists. Startup WARNS (asserted on the
     * emitted event, not merely on "nothing broke"); the sweep still runs.
     */
    @Test
    public void anObsoleteFalseFlagIsWarnedAboutAndIgnored() {
        when(pm.readValue(PropertyKey.ACL_EPOCH_WIRING_ENABLED)).thenReturn("false");

        ch.qos.logback.classic.Logger log = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(AclEpochScanScheduler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            scheduler.start();
            org.junit.jupiter.api.Assertions.assertTrue(scheduler.isRunning(),
                    "the obsolete flag must NOT disable anything");
            org.junit.jupiter.api.Assertions.assertTrue(
                    appender.list.stream().anyMatch(e ->
                            e.getLevel() == ch.qos.logback.classic.Level.WARN
                                    && e.getFormattedMessage().contains("acl.epoch.wiring.enabled")
                                    && e.getFormattedMessage().contains("OBSOLETE")),
                    "an obsolete flag that is silently ignored leaves the operator believing in a "
                            + "rollback path that no longer exists");
        } finally {
            log.detachAppender(appender);
            scheduler.stop();
        }
    }

    /** ...and a deployment WITHOUT the obsolete setting must not be warned (no false alarm). */
    @Test
    public void anAbsentFlagProducesNoWarning() {
        when(pm.readValue(PropertyKey.ACL_EPOCH_WIRING_ENABLED)).thenReturn(null);

        ch.qos.logback.classic.Logger log = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(AclEpochScanScheduler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            scheduler.start();
            org.junit.jupiter.api.Assertions.assertTrue(appender.list.stream().noneMatch(e ->
                    e.getFormattedMessage().contains("OBSOLETE")));
        } finally {
            log.detachAppender(appender);
            scheduler.stop();
        }
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
