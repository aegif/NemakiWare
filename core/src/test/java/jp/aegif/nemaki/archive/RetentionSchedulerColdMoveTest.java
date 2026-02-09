package jp.aegif.nemaki.archive;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.GregorianCalendar;
import java.util.Map;

/**
 * Unit tests for RetentionScheduler.moveToCold() — COPY/MOVE mode separation.
 *
 * Verifies:
 * - MOVE mode: state → ARCHIVED_COLD, local content deleted
 * - COPY mode: state stays ARCHIVED_LOCAL, coldArchivedAt/contentRef recorded, local content kept
 * - Content-missing case: returns false, state reverted to ARCHIVED_LOCAL
 * - Exception case: state reverted to ARCHIVED_LOCAL, RuntimeException thrown
 */
public class RetentionSchedulerColdMoveTest {

    private RetentionScheduler scheduler;
    private ContentService contentService;
    private PropertyManager propertyManager;
    private InMemoryStorageAdapter adapter;

    @Before
    public void setUp() {
        scheduler = new RetentionScheduler();
        contentService = mock(ContentService.class);
        propertyManager = mock(PropertyManager.class);
        adapter = new InMemoryStorageAdapter();

        scheduler.setContentService(contentService);
        scheduler.setPropertyManager(propertyManager);
    }

    private Archive createTestArchive(String id, String originalId) {
        Archive archive = new Archive();
        archive.setId(id);
        archive.setOriginalId(originalId);
        archive.setName("test-doc.txt");
        archive.setMimeType("text/plain");
        archive.setArchiveState(Archive.STATE_ARCHIVED_LOCAL);
        archive.setArchivedAt(new GregorianCalendar());
        return archive;
    }

    private Method getMoveToColdMethod() throws NoSuchMethodException {
        Method method = RetentionScheduler.class.getDeclaredMethod(
                "moveToCold", String.class, Archive.class, LongTermStorageAdapter.class);
        method.setAccessible(true);
        return method;
    }

    // ===== MOVE mode tests =====

    @Test
    public void testMoveMode_stateTransitionsToArchivedCold() throws Exception {
        Archive archive = createTestArchive("arch-001", "orig-001");
        InputStream stream = new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8));

        when(contentService.getArchiveContentStream("bedroom", "arch-001")).thenReturn(stream);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY)).thenReturn(false);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");
        when(contentService.deleteArchiveContent("bedroom", "arch-001")).thenReturn(true);

        Method moveToCold = getMoveToColdMethod();
        boolean result = (boolean) moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        assertTrue("moveToCold should return true on success", result);

        // Verify state transitions: COLD_MOVING → ARCHIVED_COLD
        verify(contentService).updateArchiveState(eq("bedroom"), eq("arch-001"),
                eq(Archive.STATE_COLD_MOVING), isNull(), isNull());
        verify(contentService).updateArchiveState(eq("bedroom"), eq("arch-001"),
                eq(Archive.STATE_ARCHIVED_COLD), any(Map.class), any(GregorianCalendar.class));

        // Verify coldMoveMode recorded as MOVE
        verify(contentService).updateArchiveColdMoveMode("bedroom", "arch-001", "MOVE");

        // Verify local content deleted
        verify(contentService).deleteArchiveContent("bedroom", "arch-001");
    }

    @Test
    public void testMoveMode_contentStoredInAdapter() throws Exception {
        Archive archive = createTestArchive("arch-002", "orig-002");
        InputStream stream = new ByteArrayInputStream("move-content".getBytes(StandardCharsets.UTF_8));

        when(contentService.getArchiveContentStream("bedroom", "arch-002")).thenReturn(stream);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY)).thenReturn(false);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");
        when(contentService.deleteArchiveContent("bedroom", "arch-002")).thenReturn(true);

        Method moveToCold = getMoveToColdMethod();
        moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        // Verify content was written to adapter
        assertTrue("Content should exist in cold storage", adapter.exists("bedroom", "orig-002"));
    }

    // ===== COPY mode tests =====

    @Test
    public void testCopyMode_stateRemainsArchivedLocal() throws Exception {
        Archive archive = createTestArchive("arch-003", "orig-003");
        InputStream stream = new ByteArrayInputStream("copy-content".getBytes(StandardCharsets.UTF_8));

        when(contentService.getArchiveContentStream("bedroom", "arch-003")).thenReturn(stream);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY)).thenReturn(true);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");

        Method moveToCold = getMoveToColdMethod();
        boolean result = (boolean) moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        assertTrue("moveToCold should return true on success", result);

        // Verify state transitions: COLD_MOVING → ARCHIVED_LOCAL (NOT ARCHIVED_COLD)
        verify(contentService).updateArchiveState(eq("bedroom"), eq("arch-003"),
                eq(Archive.STATE_COLD_MOVING), isNull(), isNull());
        verify(contentService).updateArchiveState(eq("bedroom"), eq("arch-003"),
                eq(Archive.STATE_ARCHIVED_LOCAL), any(Map.class), any(GregorianCalendar.class));

        // Verify ARCHIVED_COLD is NEVER set in COPY mode
        verify(contentService, never()).updateArchiveState(eq("bedroom"), eq("arch-003"),
                eq(Archive.STATE_ARCHIVED_COLD), any(), any());

        // Verify coldMoveMode recorded as COPY
        verify(contentService).updateArchiveColdMoveMode("bedroom", "arch-003", "COPY");

        // Verify local content NOT deleted
        verify(contentService, never()).deleteArchiveContent(anyString(), anyString());
    }

    @Test
    public void testCopyMode_coldArchivedAtAndContentRefRecorded() throws Exception {
        Archive archive = createTestArchive("arch-004", "orig-004");
        InputStream stream = new ByteArrayInputStream("copy-content".getBytes(StandardCharsets.UTF_8));

        when(contentService.getArchiveContentStream("bedroom", "arch-004")).thenReturn(stream);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY)).thenReturn(true);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");

        Method moveToCold = getMoveToColdMethod();
        moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        // Verify updateArchiveState was called with non-null contentRef and coldArchivedAt
        verify(contentService).updateArchiveState(
                eq("bedroom"),
                eq("arch-004"),
                eq(Archive.STATE_ARCHIVED_LOCAL),
                argThat(contentRef -> contentRef != null && "s3".equals(contentRef.get("type"))),
                argThat(cal -> cal != null)
        );
    }

    @Test
    public void testCopyMode_contentStoredInAdapter() throws Exception {
        Archive archive = createTestArchive("arch-005", "orig-005");
        InputStream stream = new ByteArrayInputStream("copy-content-data".getBytes(StandardCharsets.UTF_8));

        when(contentService.getArchiveContentStream("bedroom", "arch-005")).thenReturn(stream);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY)).thenReturn(true);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");

        Method moveToCold = getMoveToColdMethod();
        moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        // Content should be in cold storage even in COPY mode
        assertTrue("Content should exist in cold storage", adapter.exists("bedroom", "orig-005"));
    }

    // ===== Edge case: no content stream =====

    @Test
    public void testNoContentStream_returnsFalseAndRevertsState() throws Exception {
        Archive archive = createTestArchive("arch-006", "orig-006");

        when(contentService.getArchiveContentStream("bedroom", "arch-006")).thenReturn(null);

        Method moveToCold = getMoveToColdMethod();
        boolean result = (boolean) moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        assertFalse("moveToCold should return false when content is missing", result);

        // Verify state set to COLD_MOVING then reverted to ARCHIVED_LOCAL
        verify(contentService).updateArchiveState(eq("bedroom"), eq("arch-006"),
                eq(Archive.STATE_COLD_MOVING), isNull(), isNull());
        verify(contentService).updateArchiveState(eq("bedroom"), eq("arch-006"),
                eq(Archive.STATE_ARCHIVED_LOCAL), isNull(), isNull());

        // Verify no cold move mode recorded
        verify(contentService, never()).updateArchiveColdMoveMode(anyString(), anyString(), anyString());

        // Verify nothing stored in adapter
        assertFalse("Nothing should be in cold storage", adapter.exists("bedroom", "orig-006"));
    }

    // ===== Edge case: adapter throws exception =====

    @Test
    public void testAdapterException_revertsStateAndThrows() throws Exception {
        Archive archive = createTestArchive("arch-007", "orig-007");
        InputStream stream = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));

        when(contentService.getArchiveContentStream("bedroom", "arch-007")).thenReturn(stream);

        // Use a failing adapter
        LongTermStorageAdapter failingAdapter = mock(LongTermStorageAdapter.class);
        when(failingAdapter.put(anyString(), anyString(), any(InputStream.class), any()))
                .thenThrow(new RuntimeException("S3 connection failed"));

        Method moveToCold = getMoveToColdMethod();

        try {
            moveToCold.invoke(scheduler, "bedroom", archive, failingAdapter);
            fail("Should have thrown an exception");
        } catch (Exception e) {
            // InvocationTargetException wraps RuntimeException
            assertTrue("Should wrap RuntimeException",
                    e.getCause() instanceof RuntimeException);
            assertTrue("Should contain original error message",
                    e.getCause().getMessage().contains("Cold move failed"));
        }

        // Verify state reverted to ARCHIVED_LOCAL
        verify(contentService, atLeastOnce()).updateArchiveState(eq("bedroom"), eq("arch-007"),
                eq(Archive.STATE_ARCHIVED_LOCAL), isNull(), isNull());
    }
}
