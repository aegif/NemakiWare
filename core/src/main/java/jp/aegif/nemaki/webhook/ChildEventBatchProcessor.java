package jp.aegif.nemaki.webhook;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Processes CHILD_* events by batching them within a time window before delivery.
 * 
 * This processor aggregates multiple child events (CHILD_CREATED, CHILD_UPDATED,
 * CHILD_DELETED) that occur within a configurable time window for the same parent
 * folder, and delivers them as a single CHILD_BATCH webhook payload.
 * 
 * Features:
 * - Time-based batching (configurable window, default 5 seconds)
 * - Batch size limits (configurable, default 100 events)
 * - Rate limiting per folder (configurable, default 60 batches/minute)
 * - Circuit breaker for overload protection (configurable threshold)
 * - Absolute max events per second (configurable)
 * 
 * Thread-safe implementation using ConcurrentHashMap and atomic operations.
 */
public class ChildEventBatchProcessor {
    
    private static final Log log = LogFactory.getLog(ChildEventBatchProcessor.class);
    
    private static final int DEFAULT_BATCH_WINDOW_SECONDS = 5;
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;
    private static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 60;
    private static final int DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 500;
    private static final int DEFAULT_ABSOLUTE_MAX_PER_SECOND = 50;
    
    private int batchWindowSeconds = DEFAULT_BATCH_WINDOW_SECONDS;
    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private int rateLimitPerMinute = DEFAULT_RATE_LIMIT_PER_MINUTE;
    private int circuitBreakerThreshold = DEFAULT_CIRCUIT_BREAKER_THRESHOLD;
    private int absoluteMaxPerSecond = DEFAULT_ABSOLUTE_MAX_PER_SECOND;
    
    private final Consumer<ChildEventBatch> batchDeliveryHandler;
    private final Map<String, FolderEventQueue> folderQueues;
    private final Map<String, RateLimitState> rateLimitStates;
    private final Map<String, CircuitBreakerState> circuitBreakerStates;
    
    private ScheduledExecutorService scheduler;
    private volatile boolean shutdown = false;
    
    public ChildEventBatchProcessor(Consumer<ChildEventBatch> batchDeliveryHandler) {
        this.batchDeliveryHandler = batchDeliveryHandler;
        this.folderQueues = new ConcurrentHashMap<>();
        this.rateLimitStates = new ConcurrentHashMap<>();
        this.circuitBreakerStates = new ConcurrentHashMap<>();
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChildEventBatchProcessor-Scheduler");
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(this::processPendingBatches, 1, 1, TimeUnit.SECONDS);
        
        log.info("ChildEventBatchProcessor initialized with window=" + batchWindowSeconds + "s, " +
                 "maxBatchSize=" + maxBatchSize + ", rateLimit=" + rateLimitPerMinute + "/min");
    }
    
    public boolean queueEvent(String repositoryId, ChildEvent event) {
        if (shutdown) {
            log.warn("Cannot queue event: processor is shutting down");
            return false;
        }
        
        if (event == null || event.getParentFolderId() == null) {
            log.warn("Cannot queue event: event or parentFolderId is null");
            return false;
        }
        
        String queueKey = buildQueueKey(repositoryId, event.getParentFolderId());
        
        if (isCircuitBreakerOpen(repositoryId, event.getParentFolderId())) {
            log.warn("Circuit breaker is open for folder: " + event.getParentFolderId() + 
                     ". Event rejected: " + event.getObjectId());
            return false;
        }
        
        FolderEventQueue queue = folderQueues.computeIfAbsent(queueKey, 
            k -> new FolderEventQueue(repositoryId, event.getParentFolderId()));
        
        synchronized (queue) {
            queue.addEvent(event);
            
            if (queue.getEventCount() >= circuitBreakerThreshold) {
                tripCircuitBreaker(repositoryId, event.getParentFolderId());
            }
        }
        
        log.debug("Event queued: " + event.getEventType() + " for object " + event.getObjectId() + 
                  " in folder " + event.getParentFolderId());
        return true;
    }
    
    public void processPendingBatches() {
        long now = System.currentTimeMillis();
        long windowMillis = batchWindowSeconds * 1000L;
        
        for (Map.Entry<String, FolderEventQueue> entry : folderQueues.entrySet()) {
            FolderEventQueue queue = entry.getValue();
            
            synchronized (queue) {
                if (queue.isEmpty()) {
                    continue;
                }
                
                if (!shutdown && (now - queue.getWindowStart()) < windowMillis) {
                    continue;
                }
                
                String repositoryId = queue.getRepositoryId();
                String folderId = queue.getFolderId();
                
                if (!canDeliverBatch(repositoryId, folderId)) {
                    log.debug("Rate limit reached for folder: " + folderId);
                    continue;
                }
                
                List<ChildEventBatch> batches = createBatches(queue, now);
                
                int deliveredCount = 0;
                for (ChildEventBatch batch : batches) {
                    if (!canDeliverBatch(repositoryId, folderId)) {
                        log.debug("Rate limit reached during batch delivery for folder: " + folderId);
                        break;
                    }
                    
                    if (deliveredCount >= absoluteMaxPerSecond) {
                        log.debug("Absolute max per second reached for folder: " + folderId);
                        break;
                    }
                    
                    try {
                        batchDeliveryHandler.accept(batch);
                        recordBatchDelivery(repositoryId, folderId);
                        deliveredCount += batch.getEventCount();
                        
                        Iterator<ChildEvent> it = queue.getEvents().iterator();
                        int removed = 0;
                        while (it.hasNext() && removed < batch.getEventCount()) {
                            it.next();
                            it.remove();
                            removed++;
                        }
                        
                        log.debug("Delivered batch with " + batch.getEventCount() + 
                                  " events for folder: " + folderId);
                    } catch (Exception e) {
                        log.error("Failed to deliver batch for folder: " + folderId, e);
                    }
                }
                
                if (queue.isEmpty()) {
                    queue.resetWindow();
                }
            }
        }
    }
    
    private List<ChildEventBatch> createBatches(FolderEventQueue queue, long now) {
        List<ChildEventBatch> batches = new ArrayList<>();
        List<ChildEvent> events = new ArrayList<>(queue.getEvents());
        
        int batchCount = (int) Math.ceil((double) events.size() / maxBatchSize);
        
        for (int i = 0; i < batchCount; i++) {
            int start = i * maxBatchSize;
            int end = Math.min(start + maxBatchSize, events.size());
            
            ChildEventBatch batch = new ChildEventBatch(queue.getRepositoryId(), queue.getFolderId());
            batch.setWindowStart(queue.getWindowStart());
            batch.setWindowEnd(now);
            
            for (int j = start; j < end; j++) {
                batch.addEvent(events.get(j));
            }
            
            batches.add(batch);
        }
        
        return batches;
    }
    
    private boolean canDeliverBatch(String repositoryId, String folderId) {
        String key = buildQueueKey(repositoryId, folderId);
        RateLimitState state = rateLimitStates.computeIfAbsent(key, k -> new RateLimitState());
        
        long now = System.currentTimeMillis();
        long windowStart = now - 60000;
        
        synchronized (state) {
            state.cleanOldEntries(windowStart);
            return state.getDeliveryCount() < rateLimitPerMinute;
        }
    }
    
    private void recordBatchDelivery(String repositoryId, String folderId) {
        String key = buildQueueKey(repositoryId, folderId);
        RateLimitState state = rateLimitStates.computeIfAbsent(key, k -> new RateLimitState());
        
        synchronized (state) {
            state.recordDelivery(System.currentTimeMillis());
        }
    }
    
    public boolean isCircuitBreakerOpen(String repositoryId, String folderId) {
        String key = buildQueueKey(repositoryId, folderId);
        CircuitBreakerState state = circuitBreakerStates.get(key);
        
        if (state == null) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        if (state.isOpen() && (now - state.getOpenedAt()) > 60000) {
            state.halfOpen();
            log.info("Circuit breaker half-open for folder: " + folderId);
        }
        
        return state.isOpen();
    }
    
    private void tripCircuitBreaker(String repositoryId, String folderId) {
        String key = buildQueueKey(repositoryId, folderId);
        CircuitBreakerState state = circuitBreakerStates.computeIfAbsent(key, k -> new CircuitBreakerState());
        state.open();
        log.warn("Circuit breaker tripped for folder: " + folderId);
    }
    
    public int getPendingEventCount(String repositoryId, String folderId) {
        String key = buildQueueKey(repositoryId, folderId);
        FolderEventQueue queue = folderQueues.get(key);
        return queue != null ? queue.getEventCount() : 0;
    }
    
    private String buildQueueKey(String repositoryId, String folderId) {
        return repositoryId + ":" + folderId;
    }
    
    public void shutdown() {
        shutdown = true;
        log.info("Shutting down ChildEventBatchProcessor...");
        
        processPendingBatches();
        
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("ChildEventBatchProcessor shutdown complete");
    }
    
    public void setBatchWindowSeconds(int batchWindowSeconds) {
        this.batchWindowSeconds = batchWindowSeconds;
    }
    
    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }
    
    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }
    
    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) {
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }
    
    public void setAbsoluteMaxPerSecond(int absoluteMaxPerSecond) {
        this.absoluteMaxPerSecond = absoluteMaxPerSecond;
    }
    
    private static class FolderEventQueue {
        private final String repositoryId;
        private final String folderId;
        private final List<ChildEvent> events;
        private long windowStart;
        
        FolderEventQueue(String repositoryId, String folderId) {
            this.repositoryId = repositoryId;
            this.folderId = folderId;
            this.events = new ArrayList<>();
            this.windowStart = System.currentTimeMillis();
        }
        
        void addEvent(ChildEvent event) {
            events.add(event);
        }
        
        List<ChildEvent> getEvents() {
            return events;
        }
        
        int getEventCount() {
            return events.size();
        }
        
        boolean isEmpty() {
            return events.isEmpty();
        }
        
        long getWindowStart() {
            return windowStart;
        }
        
        void resetWindow() {
            windowStart = System.currentTimeMillis();
        }
        
        String getRepositoryId() {
            return repositoryId;
        }
        
        String getFolderId() {
            return folderId;
        }
    }
    
    private static class RateLimitState {
        private final List<Long> deliveryTimestamps = new ArrayList<>();
        
        void recordDelivery(long timestamp) {
            deliveryTimestamps.add(timestamp);
        }
        
        void cleanOldEntries(long cutoff) {
            deliveryTimestamps.removeIf(ts -> ts < cutoff);
        }
        
        int getDeliveryCount() {
            return deliveryTimestamps.size();
        }
    }
    
    private static class CircuitBreakerState {
        private volatile boolean open = false;
        private volatile long openedAt = 0;
        
        void open() {
            this.open = true;
            this.openedAt = System.currentTimeMillis();
        }
        
        void halfOpen() {
            this.open = false;
        }
        
        void close() {
            this.open = false;
            this.openedAt = 0;
        }
        
        boolean isOpen() {
            return open;
        }
        
        long getOpenedAt() {
            return openedAt;
        }
    }
}
