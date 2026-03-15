package jp.aegif.nemaki.archive;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
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

    @BeforeEach
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

        assertTrue(result, "moveToCold should return true on success");

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
        assertTrue(adapter.exists("bedroom", "orig-002"), "Content should exist in cold storage");
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

        assertTrue(result, "moveToCold should return true on success");

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
        assertTrue(adapter.exists("bedroom", "orig-005"), "Content should exist in cold storage");
    }


    // ===== MOVE mode: deleteArchiveContent fails → cleanup cold storage =====

    @Test
    public void testMoveMode_deleteLocalFails_cleansUpColdStorageAndResetsMetadata() throws Exception {
        Archive archive = createTestArchive("arch-010", "orig-010");
        InputStream stream = new ByteArrayInputStream("move-content".getBytes(StandardCharsets.UTF_8));

        // Use mock adapter to verify removeProtection + version-specific delete
        LongTermStorageAdapter mockAdapter = mock(LongTermStorageAdapter.class);
        when(mockAdapter.put(eq("bedroom"), eq("orig-010"), any(InputStream.class), any()))
                .thenReturn("version-abc-123");

        when(contentService.getArchiveContentStream("bedroom", "arch-010")).thenReturn(stream);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY)).thenReturn(false);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");
        // Simulate local delete failure
        when(contentService.deleteArchiveContent("bedroom", "arch-010")).thenReturn(false);

        Method moveToCold = getMoveToColdMethod();
        boolean result = (boolean) moveToCold.invoke(scheduler, "bedroom", archive, mockAdapter);

        assertFalse(result, "moveToCold should return false when local delete fails");

        // Verify removeProtection was called before delete
        var inOrder = inOrder(mockAdapter, contentService);
        inOrder.verify(mockAdapter).put(eq("bedroom"), eq("orig-010"), any(InputStream.class), any());
        inOrder.verify(mockAdapter).enforceImmutability("bedroom", "orig-010");
        inOrder.verify(mockAdapter).removeProtection("bedroom", "orig-010");
        inOrder.verify(mockAdapter).delete("bedroom", "orig-010", "version-abc-123");
        inOrder.verify(contentService).resetColdMoveMetadata("bedroom", "arch-010");
    }

    // ===== Exception after put+enforceImmutability → cleanup with removeProtection =====

    @Test
    public void testExceptionAfterPut_cleansUpWithRemoveProtectionAndVersionDelete() throws Exception {
        Archive archive = createTestArchive("arch-011", "orig-011");
        InputStream stream = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));

        LongTermStorageAdapter mockAdapter = mock(LongTermStorageAdapter.class);
        when(mockAdapter.put(eq("bedroom"), eq("orig-011"), any(InputStream.class), any()))
                .thenReturn("version-xyz-789");
        // enforceImmutability succeeds, but readValue for LONGTERM_STORAGE_TYPE throws
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE))
                .thenThrow(new RuntimeException("Config read error"));

        when(contentService.getArchiveContentStream("bedroom", "arch-011")).thenReturn(stream);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY)).thenReturn(false);

        Method moveToCold = getMoveToColdMethod();

        try {
            moveToCold.invoke(scheduler, "bedroom", archive, mockAdapter);
            fail("Should have thrown an exception");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof RuntimeException);
        }

        // Verify cleanup: removeProtection + version-specific delete
        verify(mockAdapter).removeProtection("bedroom", "orig-011");
        verify(mockAdapter).delete("bedroom", "orig-011", "version-xyz-789");

        // Verify metadata reset for retry
        verify(contentService).resetColdMoveMetadata("bedroom", "arch-011");
    }

    // ===== Edge case: no content stream =====

    @Test
    public void testNoContentStream_returnsFalseAndRevertsState() throws Exception {
        Archive archive = createTestArchive("arch-006", "orig-006");

        when(contentService.getArchiveContentStream("bedroom", "arch-006")).thenReturn(null);

        Method moveToCold = getMoveToColdMethod();
        boolean result = (boolean) moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        assertFalse(result, "moveToCold should return false when content is missing");

        // Verify state set to COLD_MOVING then reverted to ARCHIVED_LOCAL
        verify(contentService).updateArchiveState(eq("bedroom"), eq("arch-006"),
                eq(Archive.STATE_COLD_MOVING), isNull(), isNull());
        verify(contentService).updateArchiveState(eq("bedroom"), eq("arch-006"),
                eq(Archive.STATE_ARCHIVED_LOCAL), isNull(), isNull());

        // Verify no cold move mode recorded
        verify(contentService, never()).updateArchiveColdMoveMode(anyString(), anyString(), anyString());

        // Verify nothing stored in adapter
        assertFalse(adapter.exists("bedroom", "orig-006"), "Nothing should be in cold storage");
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
            assertTrue(e.getCause() instanceof RuntimeException, "Should wrap RuntimeException");
            assertTrue(e.getCause().getMessage().contains("Cold move failed"), "Should contain original error message");
        }

        // Verify cold-move metadata reset for retry eligibility
        verify(contentService).resetColdMoveMetadata("bedroom", "arch-007");
    }

    // ===== enforceImmutability fails → removeProtection also fails → delete still attempted =====

    @Test
    public void testEnforceImmutabilityFails_deleteStillAttemptedEvenIfRemoveProtectionFails() throws Exception {
        Archive archive = createTestArchive("arch-012", "orig-012");
        InputStream stream = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));

        LongTermStorageAdapter mockAdapter = mock(LongTermStorageAdapter.class);
        when(mockAdapter.put(eq("bedroom"), eq("orig-012"), any(InputStream.class), any()))
                .thenReturn("version-nolh-001");
        // Simulate: bucket has no Object Lock — both enforceImmutability and removeProtection fail
        doThrow(new RuntimeException("Bucket has no Object Lock"))
                .when(mockAdapter).enforceImmutability("bedroom", "orig-012");
        doThrow(new RuntimeException("Bucket has no Object Lock"))
                .when(mockAdapter).removeProtection("bedroom", "orig-012");

        when(contentService.getArchiveContentStream("bedroom", "arch-012")).thenReturn(stream);

        Method moveToCold = getMoveToColdMethod();

        try {
            moveToCold.invoke(scheduler, "bedroom", archive, mockAdapter);
            fail("Should have thrown an exception");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof RuntimeException);
        }

        // Key assertion: delete is called even though removeProtection failed
        verify(mockAdapter).delete("bedroom", "orig-012", "version-nolh-001");

        // Verify metadata reset for retry
        verify(contentService).resetColdMoveMetadata("bedroom", "arch-012");
    }
}
