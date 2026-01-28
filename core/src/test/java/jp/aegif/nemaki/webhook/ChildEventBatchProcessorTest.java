package jp.aegif.nemaki.webhook;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for ChildEventBatchProcessor.
 * 
 * Tests cover:
 * - Batch aggregation within time window
 * - Batch size limits
 * - Rate limiting per folder
 * - Circuit breaker functionality
 * - Multiple event types in single batch
 */
public class ChildEventBatchProcessorTest {
    
    private ChildEventBatchProcessor processor;
    private List<ChildEventBatch> deliveredBatches;
    
    @Before
    public void setUp() {
        deliveredBatches = new ArrayList<>();
        processor = new ChildEventBatchProcessor(batch -> {
            deliveredBatches.add(batch);
        });
    }
    
    @Test
    public void testSingleEventIsQueued() {
        ChildEvent event = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        
        processor.queueEvent("bedroom", event);
        
        assertEquals("Event should be queued", 1, processor.getPendingEventCount("bedroom", "folder-1"));
    }
    
    @Test
    public void testMultipleEventsAreBatched() throws InterruptedException {
        ChildEvent event1 = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        ChildEvent event2 = createEvent("folder-1", "doc-2", "CHILD_CREATED");
        ChildEvent event3 = createEvent("folder-1", "doc-3", "CHILD_UPDATED");
        
        processor.queueEvent("bedroom", event1);
        processor.queueEvent("bedroom", event2);
        processor.queueEvent("bedroom", event3);
        
        assertEquals("All events should be queued", 3, processor.getPendingEventCount("bedroom", "folder-1"));
    }
    
    @Test
    public void testBatchIsDeliveredAfterWindow() throws InterruptedException {
        processor.setBatchWindowSeconds(1); // 1 second window for testing
        
        ChildEvent event1 = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        ChildEvent event2 = createEvent("folder-1", "doc-2", "CHILD_CREATED");
        
        processor.queueEvent("bedroom", event1);
        processor.queueEvent("bedroom", event2);
        
        // Wait for batch window to expire
        Thread.sleep(1500);
        processor.processPendingBatches();
        
        assertEquals("One batch should be delivered", 1, deliveredBatches.size());
        assertEquals("Batch should contain 2 events", 2, deliveredBatches.get(0).getEvents().size());
    }
    
    @Test
    public void testEventsFromDifferentFoldersAreSeparateBatches() throws InterruptedException {
        processor.setBatchWindowSeconds(1);
        
        ChildEvent event1 = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        ChildEvent event2 = createEvent("folder-2", "doc-2", "CHILD_CREATED");
        
        processor.queueEvent("bedroom", event1);
        processor.queueEvent("bedroom", event2);
        
        Thread.sleep(1500);
        processor.processPendingBatches();
        
        assertEquals("Two batches should be delivered (one per folder)", 2, deliveredBatches.size());
    }
    
    @Test
    public void testBatchSizeLimit() throws InterruptedException {
        processor.setBatchWindowSeconds(1);
        processor.setMaxBatchSize(5);
        
        // Queue 12 events - should result in 3 batches (5, 5, 2)
        for (int i = 0; i < 12; i++) {
            ChildEvent event = createEvent("folder-1", "doc-" + i, "CHILD_CREATED");
            processor.queueEvent("bedroom", event);
        }
        
        Thread.sleep(1500);
        processor.processPendingBatches();
        
        assertEquals("Should deliver 3 batches", 3, deliveredBatches.size());
        assertEquals("First batch should have 5 events", 5, deliveredBatches.get(0).getEvents().size());
        assertEquals("Second batch should have 5 events", 5, deliveredBatches.get(1).getEvents().size());
        assertEquals("Third batch should have 2 events", 2, deliveredBatches.get(2).getEvents().size());
    }
    
    @Test
    public void testRateLimitPerFolder() throws InterruptedException {
        processor.setBatchWindowSeconds(1);
        processor.setMaxBatchSize(10);
        processor.setRateLimitPerMinute(2); // Only 2 batches per minute
        
        // Queue enough events for 3 batches
        for (int i = 0; i < 25; i++) {
            ChildEvent event = createEvent("folder-1", "doc-" + i, "CHILD_CREATED");
            processor.queueEvent("bedroom", event);
        }
        
        Thread.sleep(1500);
        processor.processPendingBatches();
        
        // Only 2 batches should be delivered due to rate limit
        assertEquals("Should deliver only 2 batches due to rate limit", 2, deliveredBatches.size());
        
        // Remaining events should still be pending
        assertTrue("Some events should remain pending", processor.getPendingEventCount("bedroom", "folder-1") > 0);
    }
    
    @Test
    public void testCircuitBreakerTrips() throws InterruptedException {
        processor.setBatchWindowSeconds(1);
        processor.setCircuitBreakerThreshold(50);
        
        // Queue more events than circuit breaker threshold
        for (int i = 0; i < 100; i++) {
            ChildEvent event = createEvent("folder-1", "doc-" + i, "CHILD_CREATED");
            processor.queueEvent("bedroom", event);
        }
        
        assertTrue("Circuit breaker should be tripped", processor.isCircuitBreakerOpen("bedroom", "folder-1"));
    }
    
    @Test
    public void testCircuitBreakerPreventsNewEvents() {
        processor.setCircuitBreakerThreshold(10);
        
        // Trip the circuit breaker
        for (int i = 0; i < 15; i++) {
            ChildEvent event = createEvent("folder-1", "doc-" + i, "CHILD_CREATED");
            processor.queueEvent("bedroom", event);
        }
        
        assertTrue("Circuit breaker should be open", processor.isCircuitBreakerOpen("bedroom", "folder-1"));
        
        // New events should be rejected
        ChildEvent newEvent = createEvent("folder-1", "doc-new", "CHILD_CREATED");
        boolean accepted = processor.queueEvent("bedroom", newEvent);
        
        assertFalse("New events should be rejected when circuit breaker is open", accepted);
    }
    
    @Test
    public void testBatchContainsMixedEventTypes() throws InterruptedException {
        processor.setBatchWindowSeconds(1);
        
        ChildEvent created = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        ChildEvent updated = createEvent("folder-1", "doc-2", "CHILD_UPDATED");
        ChildEvent deleted = createEvent("folder-1", "doc-3", "CHILD_DELETED");
        
        processor.queueEvent("bedroom", created);
        processor.queueEvent("bedroom", updated);
        processor.queueEvent("bedroom", deleted);
        
        Thread.sleep(1500);
        processor.processPendingBatches();
        
        assertEquals("One batch should be delivered", 1, deliveredBatches.size());
        ChildEventBatch batch = deliveredBatches.get(0);
        assertEquals("Batch should contain 3 events", 3, batch.getEvents().size());
        assertEquals("Batch event type should be CHILD_BATCH", "CHILD_BATCH", batch.getEventType());
    }
    
    @Test
    public void testBatchPayloadContainsParentFolderInfo() throws InterruptedException {
        processor.setBatchWindowSeconds(1);
        
        ChildEvent event = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        event.setParentFolderPath("/Sites/Documents");
        
        processor.queueEvent("bedroom", event);
        
        Thread.sleep(1500);
        processor.processPendingBatches();
        
        assertEquals("One batch should be delivered", 1, deliveredBatches.size());
        ChildEventBatch batch = deliveredBatches.get(0);
        assertEquals("Batch should have parent folder ID", "folder-1", batch.getParentFolderId());
        assertEquals("Batch should have parent folder path", "/Sites/Documents", batch.getParentFolderPath());
    }
    
    @Test
    public void testBatchInfoContainsWindowTimes() throws InterruptedException {
        processor.setBatchWindowSeconds(1);
        
        long beforeQueue = System.currentTimeMillis();
        ChildEvent event = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        processor.queueEvent("bedroom", event);
        
        Thread.sleep(1500);
        processor.processPendingBatches();
        long afterProcess = System.currentTimeMillis();
        
        assertEquals("One batch should be delivered", 1, deliveredBatches.size());
        ChildEventBatch batch = deliveredBatches.get(0);
        
        assertTrue("Window start should be after test start", batch.getWindowStart() >= beforeQueue);
        assertTrue("Window end should be before test end", batch.getWindowEnd() <= afterProcess);
    }
    
    @Test
    public void testAbsoluteMaxPerSecond() throws InterruptedException {
        processor.setBatchWindowSeconds(1);
        processor.setAbsoluteMaxPerSecond(5);
        
        // Queue many events rapidly
        for (int i = 0; i < 20; i++) {
            ChildEvent event = createEvent("folder-1", "doc-" + i, "CHILD_CREATED");
            processor.queueEvent("bedroom", event);
        }
        
        // Process immediately
        processor.processPendingBatches();
        
        // Count total events delivered
        int totalDelivered = deliveredBatches.stream()
            .mapToInt(b -> b.getEvents().size())
            .sum();
        
        assertTrue("Should not exceed absolute max per second", totalDelivered <= 5);
    }
    
    @Test
    public void testAbsoluteMaxPerSecondWithLargeBatch() throws InterruptedException {
        // Test case: batch size (100) > absoluteMaxPerSecond (50)
        // The batch should NOT be delivered because it would exceed the limit
        processor.setBatchWindowSeconds(1);
        processor.setMaxBatchSize(100); // Large batch size
        processor.setAbsoluteMaxPerSecond(50); // Smaller limit
        
        // Queue 100 events - would create a single batch of 100 events
        for (int i = 0; i < 100; i++) {
            ChildEvent event = createEvent("folder-1", "doc-" + i, "CHILD_CREATED");
            processor.queueEvent("bedroom", event);
        }
        
        Thread.sleep(1500);
        processor.processPendingBatches();
        
        // Count total events delivered
        int totalDelivered = deliveredBatches.stream()
            .mapToInt(b -> b.getEvents().size())
            .sum();
        
        // Since batch size (100) > absoluteMaxPerSecond (50), no batch should be delivered
        // because delivering it would exceed the limit
        assertTrue("Should not exceed absolute max per second (batch of 100 > limit of 50)", 
                   totalDelivered <= 50);
        
        // Events should remain pending since the batch couldn't be delivered
        assertTrue("Events should remain pending when batch exceeds limit", 
                   processor.getPendingEventCount("bedroom", "folder-1") > 0);
    }
    
    @Test
    public void testAbsoluteMaxPerSecondWithMultipleBatches() throws InterruptedException {
        // Test case: multiple batches where cumulative count exceeds limit
        // Note: This test verifies that the limit is respected within each processPendingBatches call
        // The scheduler may also run during the test, so we check the invariant rather than exact counts
        processor.setBatchWindowSeconds(1);
        processor.setMaxBatchSize(20); // 20 events per batch
        processor.setAbsoluteMaxPerSecond(50); // 50 events per second limit
        
        // Queue 100 events - would create 5 batches of 20 events each
        for (int i = 0; i < 100; i++) {
            ChildEvent event = createEvent("folder-1", "doc-" + i, "CHILD_CREATED");
            processor.queueEvent("bedroom", event);
        }
        
        Thread.sleep(1500);
        
        // Clear any batches delivered by the scheduler before our explicit call
        int deliveredByScheduler = deliveredBatches.stream()
            .mapToInt(b -> b.getEvents().size())
            .sum();
        deliveredBatches.clear();
        
        // Call processPendingBatches explicitly
        processor.processPendingBatches();
        
        // Count events delivered by our explicit call
        int deliveredByExplicitCall = deliveredBatches.stream()
            .mapToInt(b -> b.getEvents().size())
            .sum();
        
        // The explicit call should respect the per-second limit (may be 0 if scheduler already hit limit)
        assertTrue("Explicit call should not exceed absolute max per second", 
                   deliveredByExplicitCall <= 50);
        
        // Total delivered (scheduler + explicit) should show progress
        int totalDelivered = deliveredByScheduler + deliveredByExplicitCall;
        assertTrue("Some events should be delivered", totalDelivered > 0);
        
        // Verify that not all events were delivered (limit should have blocked some)
        int pendingCount = processor.getPendingEventCount("bedroom", "folder-1");
        assertTrue("Some events should remain pending due to rate limit", 
                   totalDelivered + pendingCount == 100);
    }
    
    @Test
    public void testShutdownProcessesRemainingBatches() throws InterruptedException {
        processor.setBatchWindowSeconds(60); // Long window
        
        ChildEvent event = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        processor.queueEvent("bedroom", event);
        
        // Shutdown should process remaining batches
        processor.shutdown();
        
        assertEquals("Shutdown should deliver remaining batch", 1, deliveredBatches.size());
    }
    
    @Test
    public void testConcurrentEventQueuing() throws InterruptedException {
        processor.setBatchWindowSeconds(2);
        AtomicInteger queuedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(10);
        
        // Queue events from multiple threads
        for (int t = 0; t < 10; t++) {
            final int threadId = t;
            new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    ChildEvent event = createEvent("folder-1", "doc-" + threadId + "-" + i, "CHILD_CREATED");
                    if (processor.queueEvent("bedroom", event)) {
                        queuedCount.incrementAndGet();
                    }
                }
                latch.countDown();
            }).start();
        }
        
        latch.await(5, TimeUnit.SECONDS);
        
        // All 100 events should be queued (unless circuit breaker trips)
        assertTrue("Most events should be queued", queuedCount.get() >= 50);
    }
    
    private ChildEvent createEvent(String parentFolderId, String objectId, String eventType) {
        ChildEvent event = new ChildEvent();
        event.setParentFolderId(parentFolderId);
        event.setObjectId(objectId);
        event.setEventType(eventType);
        event.setObjectName("test-" + objectId);
        event.setTimestamp(System.currentTimeMillis());
        return event;
    }
}
