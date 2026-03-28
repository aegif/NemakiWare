package jp.aegif.nemaki.rest.purview.journal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LineageMetricsTest {

    private LineageMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new LineageMetrics();
    }

    @Test
    void recordPublish_incrementsCounters() {
        metrics.recordPublish("purview");
        metrics.recordPublish("purview");
        metrics.recordPublish("atlas");

        assertEquals(3, metrics.getEventsPublished());
    }

    @Test
    void recordFail_incrementsCounters() {
        metrics.recordFail("purview");
        assertEquals(1, metrics.getEventsFailed());
    }

    @Test
    void recordDiscard_incrementsCounter() {
        metrics.recordDiscard();
        metrics.recordDiscard();
        assertEquals(2, metrics.getEventsDiscarded());
    }

    @Test
    void recordDeadLetter_incrementsCounter() {
        metrics.recordDeadLetter();
        assertEquals(1, metrics.getDeadLetterCount());
    }

    @Test
    void recordPollComplete_updatesPollState() {
        assertNull(metrics.getLastPollTime());
        assertEquals(0, metrics.getLastPollEventCount());

        metrics.recordPollComplete(5);

        assertEquals(1, metrics.getPollCount());
        assertNotNull(metrics.getLastPollTime());
        assertEquals(5, metrics.getLastPollEventCount());
    }

    @SuppressWarnings("unchecked")
    @Test
    void snapshot_containsAllFields() {
        metrics.recordPublish("purview");
        metrics.recordPublish("purview");
        metrics.recordFail("purview");
        metrics.recordFail("atlas");
        metrics.recordDiscard();
        metrics.recordDeadLetter();
        metrics.recordPollComplete(3);

        Map<String, Object> snap = metrics.snapshot();

        assertEquals(2L, snap.get("eventsPublished"));
        assertEquals(2L, snap.get("eventsFailed"));
        assertEquals(1L, snap.get("eventsDiscarded"));
        assertEquals(1L, snap.get("deadLetterCount"));
        assertEquals(1L, snap.get("pollCount"));
        assertNotNull(snap.get("lastPollTime"));
        assertEquals(3, snap.get("lastPollEventCount"));

        Map<String, Object> byTarget = (Map<String, Object>) snap.get("byTarget");
        assertNotNull(byTarget);
        assertTrue(byTarget.containsKey("purview"));
        assertTrue(byTarget.containsKey("atlas"));

        Map<String, Object> purviewMetrics = (Map<String, Object>) byTarget.get("purview");
        assertEquals(2L, purviewMetrics.get("published"));
        assertEquals(1L, purviewMetrics.get("failed"));
    }

    @Test
    void snapshot_emptyByDefault() {
        Map<String, Object> snap = metrics.snapshot();
        assertEquals(0L, snap.get("eventsPublished"));
        assertEquals(0L, snap.get("eventsFailed"));
        assertNull(snap.get("lastPollTime"));
    }
}
