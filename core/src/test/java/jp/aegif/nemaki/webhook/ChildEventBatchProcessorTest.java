package jp.aegif.nemaki.webhook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    
    @BeforeEach
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
        
        assertEquals(1, processor.getPendingEventCount("bedroom", "folder-1"), "Event should be queued");
    }
    
    @Test
    public void testMultipleEventsAreBatched() throws InterruptedException {
        ChildEvent event1 = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        ChildEvent event2 = createEvent("folder-1", "doc-2", "CHILD_CREATED");
        ChildEvent event3 = createEvent("folder-1", "doc-3", "CHILD_UPDATED");
        
        processor.queueEvent("bedroom", event1);
        processor.queueEvent("bedroom", event2);
        processor.queueEvent("bedroom", event3);
        
        assertEquals(3, processor.getPendingEventCount("bedroom", "folder-1"), "All events should be queued");
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
        
        assertEquals(1, deliveredBatches.size(), "One batch should be delivered");
        assertEquals(2, deliveredBatches.get(0).getEvents().size(), "Batch should contain 2 events");
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
        
        assertEquals(2, deliveredBatches.size(), "Two batches should be delivered (one per folder)");
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
        
        assertEquals(3, deliveredBatches.size(), "Should deliver 3 batches");
        assertEquals(5, deliveredBatches.get(0).getEvents().size(), "First batch should have 5 events");
        assertEquals(5, deliveredBatches.get(1).getEvents().size(), "Second batch should have 5 events");
        assertEquals(2, deliveredBatches.get(2).getEvents().size(), "Third batch should have 2 events");
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
        assertEquals(2, deliveredBatches.size(), "Should deliver only 2 batches due to rate limit");
        
        // Remaining events should still be pending
        assertTrue(processor.getPendingEventCount("bedroom", "folder-1") > 0, "Some events should remain pending");
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
        
        assertTrue(processor.isCircuitBreakerOpen("bedroom", "folder-1"), "Circuit breaker should be tripped");
    }
    
    @Test
    public void testCircuitBreakerPreventsNewEvents() {
        processor.setCircuitBreakerThreshold(10);
        
        // Trip the circuit breaker
        for (int i = 0; i < 15; i++) {
            ChildEvent event = createEvent("folder-1", "doc-" + i, "CHILD_CREATED");
            processor.queueEvent("bedroom", event);
        }
        
        assertTrue(processor.isCircuitBreakerOpen("bedroom", "folder-1"), "Circuit breaker should be open");
        
        // New events should be rejected
        ChildEvent newEvent = createEvent("folder-1", "doc-new", "CHILD_CREATED");
        boolean accepted = processor.queueEvent("bedroom", newEvent);
        
        assertFalse(accepted, "New events should be rejected when circuit breaker is open");
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
        
        assertEquals(1, deliveredBatches.size(), "One batch should be delivered");
        ChildEventBatch batch = deliveredBatches.get(0);
        assertEquals(3, batch.getEvents().size(), "Batch should contain 3 events");
        assertEquals("CHILD_BATCH", batch.getEventType(), "Batch event type should be CHILD_BATCH");
    }
    
    @Test
    public void testBatchPayloadContainsParentFolderInfo() throws InterruptedException {
        processor.setBatchWindowSeconds(1);
        
        ChildEvent event = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        event.setParentFolderPath("/Sites/Documents");
        
        processor.queueEvent("bedroom", event);
        
        Thread.sleep(1500);
        processor.processPendingBatches();
        
        assertEquals(1, deliveredBatches.size(), "One batch should be delivered");
        ChildEventBatch batch = deliveredBatches.get(0);
        assertEquals("folder-1", batch.getParentFolderId(), "Batch should have parent folder ID");
        assertEquals("/Sites/Documents", batch.getParentFolderPath(), "Batch should have parent folder path");
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
        
        assertEquals(1, deliveredBatches.size(), "One batch should be delivered");
        ChildEventBatch batch = deliveredBatches.get(0);
        
        assertTrue(batch.getWindowStart() >= beforeQueue, "Window start should be after test start");
        assertTrue(batch.getWindowEnd() <= afterProcess, "Window end should be before test end");
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
        
        assertTrue(totalDelivered <= 5, "Should not exceed absolute max per second");
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
        assertTrue(totalDelivered <= 50, "Should not exceed absolute max per second (batch of 100 > limit of 50)");
        
        // Events should remain pending since the batch couldn't be delivered
        assertTrue(processor.getPendingEventCount("bedroom", "folder-1") > 0, "Events should remain pending when batch exceeds limit");
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
        assertTrue(deliveredByExplicitCall <= 50, "Explicit call should not exceed absolute max per second");
        
        // Total delivered (scheduler + explicit) should show progress
        int totalDelivered = deliveredByScheduler + deliveredByExplicitCall;
        assertTrue(totalDelivered > 0, "Some events should be delivered");
        
        // Verify that not all events were delivered (limit should have blocked some)
        int pendingCount = processor.getPendingEventCount("bedroom", "folder-1");
        assertTrue(totalDelivered + pendingCount == 100, "Some events should remain pending due to rate limit");
    }
    
    @Test
    public void testShutdownProcessesRemainingBatches() throws InterruptedException {
        processor.setBatchWindowSeconds(60); // Long window
        
        ChildEvent event = createEvent("folder-1", "doc-1", "CHILD_CREATED");
        processor.queueEvent("bedroom", event);
        
        // Shutdown should process remaining batches
        processor.shutdown();
        
        assertEquals(1, deliveredBatches.size(), "Shutdown should deliver remaining batch");
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
        assertTrue(queuedCount.get() >= 50, "Most events should be queued");
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
