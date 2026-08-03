package jp.aegif.nemaki.rest.purview.journal;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory metrics counters for the lineage journal subsystem.
 *
 * <p>All counters are thread-safe (AtomicLong). No external dependencies.
 * Counters reset on JVM restart — this is intentional for simplicity.
 */
@Component
public class LineageMetrics {

    private final AtomicLong eventsPublished = new AtomicLong();
    private final AtomicLong eventsFailed = new AtomicLong();
    private final AtomicLong eventsDiscarded = new AtomicLong();
    private final AtomicLong deadLetterCount = new AtomicLong();
    private final AtomicLong pollCount = new AtomicLong();
    private final AtomicLong spoolAppended = new AtomicLong();
    private final AtomicLong spoolIdempotent = new AtomicLong();
    private final AtomicLong spoolQuarantined = new AtomicLong();
    private final AtomicLong spoolWriteFailed = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> spoolQuarantinedByReason =
            new ConcurrentHashMap<>();
    private final AtomicLong sequencerFinalized = new AtomicLong();
    private final AtomicLong sequencerReclaimed = new AtomicLong();
    private final AtomicLong sequencerBacklogAlerts = new AtomicLong();
    private final AtomicLong v2Claimed = new AtomicLong();
    private final AtomicLong v2Published = new AtomicLong();
    private final AtomicLong v2VerifyRetries = new AtomicLong();
    private final AtomicLong v2Unprojectable = new AtomicLong();
    private final AtomicLong v2ClaimsReaped = new AtomicLong();
    private final AtomicLong v2RoutingHalts = new AtomicLong();
    private final AtomicLong replayRequested = new AtomicLong();
    private final AtomicLong replayAcked = new AtomicLong();
    private final AtomicLong replayFailed = new AtomicLong();
    private final AtomicLong replayRecovered = new AtomicLong();
    private final AtomicLong spoolMaterialized = new AtomicLong();
    private final AtomicLong spoolAckBroken = new AtomicLong();
    private final AtomicLong spoolAckVerified = new AtomicLong();
    private final AtomicLong spoolOversizeParked = new AtomicLong();
    private final AtomicLong partialRowsEscaped = new AtomicLong();
    private final AtomicLong emitDropped = new AtomicLong();
    private final Map<String, AtomicLong> emitDroppedByReason = new ConcurrentHashMap<>();
    private final AtomicLong decisionCollisions = new AtomicLong();
    private final AtomicLong unresolvedSkipped = new AtomicLong();
    private volatile Instant lastPollTime;
    private volatile int lastPollEventCount;

    private final ConcurrentHashMap<String, AtomicLong> publishedByTarget = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> failedByTarget = new ConcurrentHashMap<>();

    public void recordPublish(String target) {
        eventsPublished.incrementAndGet();
        publishedByTarget.computeIfAbsent(target, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordFail(String target) {
        eventsFailed.incrementAndGet();
        failedByTarget.computeIfAbsent(target, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordDiscard() {
        eventsDiscarded.incrementAndGet();
    }

    public void recordDeadLetter() {
        deadLetterCount.incrementAndGet();
    }

    public void recordSpoolAppended() {
        spoolAppended.incrementAndGet();
    }

    public void recordSpoolIdempotent() {
        spoolIdempotent.incrementAndGet();
    }

    /** @param reason {@code digest_mismatch} or {@code self_check_failed} (§6-a) */
    public void recordSpoolQuarantine(String reason) {
        spoolQuarantined.incrementAndGet();
        spoolQuarantinedByReason
                .computeIfAbsent(reason == null ? "unknown" : reason, r -> new AtomicLong())
                .incrementAndGet();
    }

    public void recordSpoolWriteFailed() {
        spoolWriteFailed.incrementAndGet();
    }

    public void recordSequencerFinalized() {
        sequencerFinalized.incrementAndGet();
    }

    public void recordSequencerReclaimed() {
        sequencerReclaimed.incrementAndGet();
    }

    public void recordSequencerBacklogAlert() {
        sequencerBacklogAlerts.incrementAndGet();
    }

    // §8-b v2 projection machine (D-rest-2)

    public void recordV2Claimed(String target) {
        v2Claimed.incrementAndGet();
    }

    public void recordV2Published(String target) {
        v2Published.incrementAndGet();
    }

    public void recordV2VerifyRetry(String target) {
        v2VerifyRetries.incrementAndGet();
    }

    public void recordV2Unprojectable(String target) {
        v2Unprojectable.incrementAndGet();
    }

    public void recordV2ClaimReaped(String target) {
        v2ClaimsReaped.incrementAndGet();
    }

    public void recordV2RoutingHalt(String reason) {
        v2RoutingHalts.incrementAndGet();
    }

    // §8-d replay machine (D-rest-3)

    public void recordReplayRequested(String target) {
        replayRequested.incrementAndGet();
    }

    public void recordReplayAcked(String target) {
        replayAcked.incrementAndGet();
    }

    public void recordReplayFailed(String target) {
        replayFailed.incrementAndGet();
    }

    public void recordReplayRecovered(String target) {
        replayRecovered.incrementAndGet();
    }

    // v2.3.21 materializer (D-rest-4)

    public void recordMaterialized() {
        spoolMaterialized.incrementAndGet();
    }

    public void recordAckBroken() {
        spoolAckBroken.incrementAndGet();
    }

    public void recordAckVerified() {
        spoolAckVerified.incrementAndGet();
    }

    public void recordOversizeParked() {
        spoolOversizeParked.incrementAndGet();
    }

    /**
     * A plan whose later chunk CouchDB refused, whose ALREADY-WRITTEN rows could not all be
     * made non-projectable. The fact is not parked (that would declare the work done), so
     * this counter marks the one condition where an incomplete fact may reach a sink.
     */
    public void recordPartialRowsEscaped() {
        partialRowsEscaped.incrementAndGet();
    }

    /**
     * A fact the emit path could not place anywhere (4a). The reason is the diagnosis: an
     * unreadable barrier with a failing spool, a v2 flag with no spool, or no spool wired.
     */
    public void recordEmitDropped(String reason) {
        emitDropped.incrementAndGet();
        emitDroppedByReason.computeIfAbsent(reason == null ? "unknown" : reason,
                r -> new AtomicLong()).incrementAndGet();
    }

    public void recordDecisionCollision() {
        decisionCollisions.incrementAndGet();
    }

    public void recordUnresolvedSkipped() {
        unresolvedSkipped.incrementAndGet();
    }

    public void recordPollComplete(int eventCount) {
        pollCount.incrementAndGet();
        lastPollTime = Instant.now();
        lastPollEventCount = eventCount;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventsPublished", eventsPublished.get());
        m.put("eventsFailed", eventsFailed.get());
        m.put("eventsDiscarded", eventsDiscarded.get());
        m.put("deadLetterCount", deadLetterCount.get());
        m.put("pollCount", pollCount.get());
        m.put("lastPollTime", lastPollTime != null ? lastPollTime.toString() : null);
        m.put("lastPollEventCount", lastPollEventCount);
        m.put("spoolAppended", spoolAppended.get());
        m.put("spoolIdempotent", spoolIdempotent.get());
        m.put("spoolQuarantined", spoolQuarantined.get());
        Map<String, Object> quarantineReasons = new LinkedHashMap<>();
        spoolQuarantinedByReason.forEach((reason, count) -> quarantineReasons.put(reason, count.get()));
        m.put("spoolQuarantinedByReason", quarantineReasons);
        m.put("spoolWriteFailed", spoolWriteFailed.get());
        m.put("sequencerFinalized", sequencerFinalized.get());
        m.put("sequencerReclaimed", sequencerReclaimed.get());
        m.put("sequencerBacklogAlerts", sequencerBacklogAlerts.get());
        m.put("v2Claimed", v2Claimed.get());
        m.put("v2Published", v2Published.get());
        m.put("v2VerifyRetries", v2VerifyRetries.get());
        m.put("v2Unprojectable", v2Unprojectable.get());
        m.put("v2ClaimsReaped", v2ClaimsReaped.get());
        m.put("v2RoutingHalts", v2RoutingHalts.get());
        m.put("replayRequested", replayRequested.get());
        m.put("replayAcked", replayAcked.get());
        m.put("replayFailed", replayFailed.get());
        m.put("replayRecovered", replayRecovered.get());
        m.put("spoolMaterialized", spoolMaterialized.get());
        m.put("spoolAckBroken", spoolAckBroken.get());
        m.put("spoolAckVerified", spoolAckVerified.get());
        m.put("spoolOversizeParked", spoolOversizeParked.get());
        m.put("partialRowsEscaped", partialRowsEscaped.get());
        m.put("emitDropped", emitDropped.get());
        Map<String, Object> emitDropReasons = new LinkedHashMap<>();
        emitDroppedByReason.forEach((reason, count) -> emitDropReasons.put(reason, count.get()));
        m.put("emitDroppedByReason", emitDropReasons);
        m.put("decisionCollisions", decisionCollisions.get());
        m.put("unresolvedSkipped", unresolvedSkipped.get());

        Map<String, Object> byTarget = new LinkedHashMap<>();
        for (String target : publishedByTarget.keySet()) {
            Map<String, Object> targetMetrics = new LinkedHashMap<>();
            targetMetrics.put("published", publishedByTarget.getOrDefault(target, new AtomicLong()).get());
            targetMetrics.put("failed", failedByTarget.getOrDefault(target, new AtomicLong()).get());
            byTarget.put(target, targetMetrics);
        }
        // Include targets that have failures but no publishes
        for (String target : failedByTarget.keySet()) {
            if (!byTarget.containsKey(target)) {
                Map<String, Object> targetMetrics = new LinkedHashMap<>();
                targetMetrics.put("published", 0L);
                targetMetrics.put("failed", failedByTarget.get(target).get());
                byTarget.put(target, targetMetrics);
            }
        }
        m.put("byTarget", byTarget);
        return m;
    }

    // Getters for testing
    public long getEventsPublished() { return eventsPublished.get(); }
    public long getEventsFailed() { return eventsFailed.get(); }
    public long getEventsDiscarded() { return eventsDiscarded.get(); }
    public long getDeadLetterCount() { return deadLetterCount.get(); }
    public long getPollCount() { return pollCount.get(); }
    public long getSpoolAppended() { return spoolAppended.get(); }
    public long getSpoolIdempotent() { return spoolIdempotent.get(); }
    public long getSpoolQuarantined() { return spoolQuarantined.get(); }
    public long getSpoolQuarantined(String reason) {
        AtomicLong count = spoolQuarantinedByReason.get(reason);
        return count == null ? 0L : count.get();
    }
    public long getSpoolWriteFailed() { return spoolWriteFailed.get(); }
    public long getSequencerFinalized() { return sequencerFinalized.get(); }
    public long getSequencerReclaimed() { return sequencerReclaimed.get(); }
    public long getSequencerBacklogAlerts() { return sequencerBacklogAlerts.get(); }
    public Instant getLastPollTime() { return lastPollTime; }
    public int getLastPollEventCount() { return lastPollEventCount; }
}
