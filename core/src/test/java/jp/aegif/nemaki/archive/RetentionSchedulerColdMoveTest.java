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
    private jp.aegif.nemaki.evidence.DispositionRecorder dispositionRecorder;

    @BeforeEach
    public void setUp() {
        scheduler = new RetentionScheduler();
        contentService = mock(ContentService.class);
        propertyManager = mock(PropertyManager.class);
        adapter = new InMemoryStorageAdapter();

        scheduler.setContentService(contentService);
        scheduler.setPropertyManager(propertyManager);
        // MOVE mode deletes content, and P3-3 refuses to delete anything it cannot record.
        // Granting by default here keeps these tests about COPY/MOVE; the refusal is its own
        // test below. Note what happened when this line did not exist: both MOVE-mode tests
        // failed, which is the wiring being real rather than decorative.
        dispositionRecorder = mock(jp.aegif.nemaki.evidence.DispositionRecorder.class);
        when(dispositionRecorder.authoriseDisposition(anyString(), any(), anyString(), any(),
                anyString())).thenReturn(
                new jp.aegif.nemaki.evidence.DispositionRecorder.Authorisation(true, null));
        scheduler.setDispositionRecorder(dispositionRecorder);
    }

    @Test
    public void testMoveMode_unrecordableDisposition_doesNotDeleteLocalContent() throws Exception {
        // The rule that inverts the capture boundary: a capture that cannot be chained still
        // happened and must not be undone; a deletion that cannot be recorded has NOT happened
        // and must not be allowed to. Deleting here would leave content gone with nothing
        // recording that it went, and no object left for anyone to notice.
        Archive archive = createTestArchive("arch-refused", "doc-refused");
        InputStream content = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));
        when(contentService.getArchiveContentStream("bedroom", "arch-refused"))
                .thenReturn(content);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY))
                .thenReturn(false);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");
        when(dispositionRecorder.authoriseDisposition(anyString(), any(), anyString(), any(),
                anyString())).thenReturn(
                new jp.aegif.nemaki.evidence.DispositionRecorder.Authorisation(false,
                        "this disposition could not be recorded, so it did not happen. The "
                                + "content is untouched and the next run will try again."));

        Method moveToCold = getMoveToColdMethod();
        boolean result = (boolean) moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        assertFalse(result, "an unrecordable disposition was reported as a completed cold move");
        verify(contentService, never()).deleteArchiveContent(anyString(), anyString());
        // And the archive goes BACK IN THE POOL.
        //
        // The first version relabelled it COPY and returned, which quietly took it out of the
        // pool for ever: updateArchiveState stamps coldArchivedAt, and the candidate filter
        // skips anything that has one. Every statement this product makes about a refusal —
        // the design document, the javadoc, and the sentence an operator reads in the log
        // ("the next run will try again") — was false, and a test asserted the message that
        // said so. resetColdMoveMetadata is what actually makes it true; it is the same undo
        // the delete-failure path beside it already performs.
        verify(contentService).resetColdMoveMetadata("bedroom", "arch-refused");
    }

    @Test
    public void testMoveMode_refusalIsCountedOnce_notAlsoAsASkip() throws Exception {
        // A refusal IS a skip — the caller counts it as one when moveToCold returns false —
        // and `refused` exists to say WHY that skip happened. Bumping skipped a second time
        // here made the two totals disagree with `processed`, so an operator reconciling the
        // run sees more outcomes than there were documents and concludes the job double-ran.
        Archive archive = createTestArchive("arch-count", "doc-count");
        when(contentService.getArchiveContentStream("bedroom", "arch-count"))
                .thenReturn(new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)));
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY))
                .thenReturn(false);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");
        when(dispositionRecorder.authoriseDisposition(anyString(), any(), anyString(), any(),
                anyString())).thenReturn(
                new jp.aegif.nemaki.evidence.DispositionRecorder.Authorisation(false, "no"));
        RetentionJobResult result = new RetentionJobResult("cold-move", "bedroom");

        Method moveToCold = RetentionScheduler.class.getDeclaredMethod("moveToCold",
                String.class, Archive.class, LongTermStorageAdapter.class,
                RetentionJobResult.class);
        moveToCold.setAccessible(true);

        // What the caller does around it, so the totals are the ones an operator would read.
        result.incrementProcessed();
        boolean moved = (boolean) moveToCold.invoke(scheduler, "bedroom", archive, adapter,
                result);
        if (!moved) {
            result.incrementSkipped();
            result.addSkippedDocumentId(archive.getId());
        }

        assertEquals(1, result.getRefused(), "the refusal was not counted");
        assertEquals(1, result.getSkipped(),
                "one refused document was counted as two skips, so processed and skipped "
                        + "no longer reconcile");
        assertEquals(result.getProcessed(),
                result.getSucceeded() + result.getFailed() + result.getSkipped(),
                "the outcome counts do not add up to the number of documents processed");
        // And the summary line an operator actually reads has to show it. A run that refused
        // every disposition otherwise prints as a run that skipped them, and "skipped" reads
        // as "there was nothing to do".
        assertTrue(result.toString().contains("refused=1"),
                "the refusal is counted but not printed: " + result);
    }

    @Test
    public void testMoveMode_ruleRecordsTheThresholdTheJobActuallyApplied() throws Exception {
        // The entry has to commit to the rule the run ACTED UNDER. executeColdMoveInternal
        // falls back to 90 when the property will not parse, so recording the raw string would
        // say the run acted under "abc" when it acted under 90 — a record of a rule that was
        // never applied, in the one field the whole digest exists to carry.
        Archive archive = createTestArchive("arch-rule", "doc-rule");
        InputStream content = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));
        when(contentService.getArchiveContentStream("bedroom", "arch-rule")).thenReturn(content);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY))
                .thenReturn(false);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");
        when(propertyManager.readValue(PropertyKey.RETENTION_ARCHIVE_COLD_AFTER_DAYS))
                .thenReturn("not-a-number");
        when(contentService.deleteArchiveContent("bedroom", "arch-rule")).thenReturn(true);

        Method moveToCold = getMoveToColdMethod();
        moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, String>> rule =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(dispositionRecorder).authoriseDisposition(anyString(), any(), anyString(),
                rule.capture(), anyString());

        assertEquals("90", rule.getValue().get("retention.archive.cold.after.days"),
                "the entry records the threshold as written rather than as applied: "
                        + rule.getValue());
        assertEquals("false", rule.getValue().get("retention.cold.keep.local.copy"),
                rule.getValue().toString());
    }

    @Test
    public void testMoveMode_dispositionIsRecordedBeforeTheDelete() throws Exception {
        // Order is the whole design. Recording afterwards would leave, on a crash in between,
        // content deleted that nothing records disposing of.
        Archive archive = createTestArchive("arch-order", "doc-order");
        InputStream content = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));
        when(contentService.getArchiveContentStream("bedroom", "arch-order")).thenReturn(content);
        when(propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY))
                .thenReturn(false);
        when(propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE)).thenReturn("s3");
        when(contentService.deleteArchiveContent("bedroom", "arch-order")).thenReturn(true);

        Method moveToCold = getMoveToColdMethod();
        moveToCold.invoke(scheduler, "bedroom", archive, adapter);

        org.mockito.InOrder order = inOrder(dispositionRecorder, contentService);
        order.verify(dispositionRecorder).authoriseDisposition(eq("bedroom"), any(),
                eq("doc-order"), any(), anyString());
        order.verify(contentService).deleteArchiveContent("bedroom", "arch-order");
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
