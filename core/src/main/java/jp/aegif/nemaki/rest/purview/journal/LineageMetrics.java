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
    public Instant getLastPollTime() { return lastPollTime; }
    public int getLastPollEventCount() { return lastPollEventCount; }
}
