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
    public Instant getLastPollTime() { return lastPollTime; }
    public int getLastPollEventCount() { return lastPollEventCount; }
}
