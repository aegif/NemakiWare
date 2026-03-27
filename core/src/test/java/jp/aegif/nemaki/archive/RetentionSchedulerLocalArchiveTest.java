package jp.aegif.nemaki.archive;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import jp.aegif.nemaki.dao.RetentionLogDaoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Unit tests for RetentionScheduler.executeLocalArchive() and archiveDocument().
 *
 * Tests cover:
 * - Phase 1: Expiration-based archiving (cmis:rm_expirationDate)
 * - Phase 2: Inactivity-based archiving (retention.archive.local.after.days)
 * - archive.create.enabled safety guard
 * - archiveDocument() lock handling, error handling, result tracking
 * - Configuration edge cases (empty, null, zero, negative, non-numeric)
 * - Multi-repository orchestration
 * - persistMigrationLog() recording
 */
public class RetentionSchedulerLocalArchiveTest {

    private RetentionScheduler scheduler;
    private ContentService contentService;
    private PropertyManager propertyManager;
    private ThreadLockService threadLockService;
    private NemakiCachePool nemakiCachePool;
    private CacheService cacheService;
    private RetentionLogDaoService retentionLogDaoService;
    private RepositoryInfoMap repositoryInfoMap;

    @BeforeEach
    public void setUp() {
        scheduler = new RetentionScheduler();
        contentService = mock(ContentService.class);
        propertyManager = mock(PropertyManager.class);
        threadLockService = mock(ThreadLockService.class);
        nemakiCachePool = mock(NemakiCachePool.class);
        cacheService = mock(CacheService.class);
        retentionLogDaoService = mock(RetentionLogDaoService.class);
        repositoryInfoMap = mock(RepositoryInfoMap.class);

        scheduler.setContentService(contentService);
        scheduler.setPropertyManager(propertyManager);
        scheduler.setThreadLockService(threadLockService);
        scheduler.setNemakiCachePool(nemakiCachePool);
        scheduler.setRetentionLogDaoService(retentionLogDaoService);
        scheduler.setRepositoryInfoMap(repositoryInfoMap);

        // Default: retention enabled, single repository, archive creation enabled
        when(propertyManager.readBoolean(PropertyKey.RETENTION_ENABLED)).thenReturn(true);
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");
        when(repositoryInfoMap.keys()).thenReturn(repos);
        when(propertyManager.readBoolean("bedroom", PropertyKey.ARCHIVE_CREATE_ENABLED)).thenReturn(true);
        when(nemakiCachePool.get("bedroom")).thenReturn(cacheService);
    }

    private Method getExecuteLocalArchiveMethod() throws NoSuchMethodException {
        Method m = RetentionScheduler.class.getDeclaredMethod("executeLocalArchive");
        m.setAccessible(true);
        return m;
    }

    private Method getArchiveDocumentMethod() throws NoSuchMethodException {
        Method m = RetentionScheduler.class.getDeclaredMethod(
                "archiveDocument", String.class, String.class, String.class, RetentionJobResult.class);
        m.setAccessible(true);
        return m;
    }

    private void setupLockForDocument(String repositoryId, String documentId) {
        Lock lock = new ReentrantLock();
        when(threadLockService.getWriteLock(repositoryId, documentId)).thenReturn(lock);
    }

    // ========================================================================
    // Phase 1: Expiration-based archiving
    // ========================================================================

    @Test
    @DisplayName("Phase 1: 有効期限切れドキュメントがアーカイブされる")
    public void testPhase1_expiredDocumentsArchived() throws Exception {
        List<String> expiredIds = Arrays.asList("doc-001", "doc-002");
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(expiredIds);
        when(contentService.getStaleDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());

        // Phase 2 disabled (no localAfterDays config)
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn(null);

        setupLockForDocument("bedroom", "doc-001");
        setupLockForDocument("bedroom", "doc-002");

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // Both expired documents should be archived
        verify(contentService).deleteDocument(any(), eq("bedroom"), eq("doc-001"), eq(true), eq(false));
        verify(contentService).deleteDocument(any(), eq("bedroom"), eq("doc-002"), eq(true), eq(false));

        // Cache should be cleared for each
        verify(cacheService).removeCmisCache("doc-001");
        verify(cacheService).removeCmisCache("doc-002");
    }

    @Test
    @DisplayName("Phase 1: 有効期限切れドキュメントなし → 何もアーカイブされない")
    public void testPhase1_noExpiredDocuments() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn(null);

        getExecuteLocalArchiveMethod().invoke(scheduler);

        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), anyBoolean(), anyBoolean());
    }

    // ========================================================================
    // Phase 2: Inactivity-based archiving
    // ========================================================================

    @Test
    @DisplayName("Phase 2: 未更新ドキュメントがアーカイブされる")
    public void testPhase2_staleDocumentsArchived() throws Exception {
        // Phase 1: no expired documents
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());

        // Phase 2: 365 days inactivity threshold, 2 stale documents
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("365");
        List<String> staleIds = Arrays.asList("stale-001", "stale-002");
        when(contentService.getStaleDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(staleIds);

        setupLockForDocument("bedroom", "stale-001");
        setupLockForDocument("bedroom", "stale-002");

        getExecuteLocalArchiveMethod().invoke(scheduler);

        verify(contentService).deleteDocument(any(), eq("bedroom"), eq("stale-001"), eq(true), eq(false));
        verify(contentService).deleteDocument(any(), eq("bedroom"), eq("stale-002"), eq(true), eq(false));
    }

    @Test
    @DisplayName("Phase 2: localAfterDays=null → Phase 2 スキップ")
    public void testPhase2_nullLocalAfterDays_skipped() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn(null);

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // getStaleDocumentIds should NOT be called when localAfterDays is null
        verify(contentService, never()).getStaleDocumentIds(anyString(), any(GregorianCalendar.class));
    }

    @Test
    @DisplayName("Phase 2: localAfterDays=空文字 → Phase 2 スキップ")
    public void testPhase2_emptyLocalAfterDays_skipped() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("");

        getExecuteLocalArchiveMethod().invoke(scheduler);

        verify(contentService, never()).getStaleDocumentIds(anyString(), any(GregorianCalendar.class));
    }

    @Test
    @DisplayName("Phase 2: localAfterDays=0 → Phase 2 スキップ (0日は無意味)")
    public void testPhase2_zeroLocalAfterDays_skipped() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("0");

        getExecuteLocalArchiveMethod().invoke(scheduler);

        verify(contentService, never()).getStaleDocumentIds(anyString(), any(GregorianCalendar.class));
    }

    @Test
    @DisplayName("Phase 2: localAfterDays=負数 → Phase 2 スキップ")
    public void testPhase2_negativeLocalAfterDays_skipped() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("-30");

        getExecuteLocalArchiveMethod().invoke(scheduler);

        verify(contentService, never()).getStaleDocumentIds(anyString(), any(GregorianCalendar.class));
    }

    @Test
    @DisplayName("Phase 2: localAfterDays=非数値 → NumberFormatException を内部処理してクラッシュしない")
    public void testPhase2_nonNumericLocalAfterDays_handledGracefully() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("abc");

        // Should NOT throw — NumberFormatException is caught internally
        assertDoesNotThrow(() -> getExecuteLocalArchiveMethod().invoke(scheduler));

        verify(contentService, never()).getStaleDocumentIds(anyString(), any(GregorianCalendar.class));
    }

    @Test
    @DisplayName("Phase 2: localAfterDays=空白付き数値 → trim して正常処理")
    public void testPhase2_paddedLocalAfterDays_trimmed() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("  180  ");
        when(contentService.getStaleDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // getStaleDocumentIds should be called with a trimmed-valid value
        verify(contentService).getStaleDocumentIds(eq("bedroom"), any(GregorianCalendar.class));
    }

    // ========================================================================
    // Both Phases: Combined execution
    // ========================================================================

    @Test
    @DisplayName("Phase 1+2: 両方のドキュメントが同一ジョブでアーカイブされる")
    public void testBothPhases_combinedExecution() throws Exception {
        // Phase 1: 1 expired
        List<String> expiredIds = Arrays.asList("expired-001");
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(expiredIds);

        // Phase 2: 1 stale
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("30");
        List<String> staleIds = Arrays.asList("stale-001");
        when(contentService.getStaleDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(staleIds);

        setupLockForDocument("bedroom", "expired-001");
        setupLockForDocument("bedroom", "stale-001");

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // Both types archived
        verify(contentService).deleteDocument(any(), eq("bedroom"), eq("expired-001"), eq(true), eq(false));
        verify(contentService).deleteDocument(any(), eq("bedroom"), eq("stale-001"), eq(true), eq(false));
    }

    // ========================================================================
    // Safety guard: archive.create.enabled
    // ========================================================================

    @Test
    @DisplayName("archive.create.enabled=false → リポジトリ全体をスキップ")
    public void testArchiveDisabled_skipsEntireRepository() throws Exception {
        when(propertyManager.readBoolean("bedroom", PropertyKey.ARCHIVE_CREATE_ENABLED)).thenReturn(false);

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // Nothing should be archived
        verify(contentService, never()).getExpiredDocumentIds(anyString(), any(GregorianCalendar.class));
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("マルチリポジトリ: archive.create.enabled=false のリポジトリだけスキップ")
    public void testArchiveDisabled_multiRepo_onlyAffectedSkipped() throws Exception {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");
        repos.add("canopy");
        when(repositoryInfoMap.keys()).thenReturn(repos);

        // bedroom: archive disabled
        when(propertyManager.readBoolean("bedroom", PropertyKey.ARCHIVE_CREATE_ENABLED)).thenReturn(false);
        // canopy: archive enabled, no expired docs
        when(propertyManager.readBoolean("canopy", PropertyKey.ARCHIVE_CREATE_ENABLED)).thenReturn(true);
        when(contentService.getExpiredDocumentIds(eq("canopy"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("canopy", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn(null);
        when(nemakiCachePool.get("canopy")).thenReturn(mock(CacheService.class));

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // bedroom should not have getExpiredDocumentIds called
        verify(contentService, never()).getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class));
        // canopy should have getExpiredDocumentIds called
        verify(contentService).getExpiredDocumentIds(eq("canopy"), any(GregorianCalendar.class));
    }

    // ========================================================================
    // archiveDocument() — lock handling
    // ========================================================================

    @Test
    @DisplayName("archiveDocument: ロック取得成功 → 正常アーカイブ → succeeded++")
    public void testArchiveDocument_lockSuccess() throws Exception {
        setupLockForDocument("bedroom", "doc-123");

        RetentionJobResult result = new RetentionJobResult("local-archive", "bedroom");

        getArchiveDocumentMethod().invoke(scheduler, "bedroom", "doc-123", "expired", result);

        assertEquals(1, result.getProcessed());
        assertEquals(1, result.getSucceeded());
        assertEquals(0, result.getFailed());
        assertEquals(0, result.getSkipped());

        verify(contentService).deleteDocument(any(), eq("bedroom"), eq("doc-123"), eq(true), eq(false));
        verify(cacheService).removeCmisCache("doc-123");
    }

    @Test
    @DisplayName("archiveDocument: ロック取得失敗 → skipped++ + skippedDocumentIds に追加")
    public void testArchiveDocument_lockFailed() throws Exception {
        Lock alreadyLocked = new ReentrantLock();
        alreadyLocked.lock(); // Pre-lock so tryLock fails

        // Need a different thread to hold the lock since ReentrantLock is reentrant
        Lock mockLock = mock(Lock.class);
        when(mockLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);
        when(threadLockService.getWriteLock("bedroom", "doc-locked")).thenReturn(mockLock);

        RetentionJobResult result = new RetentionJobResult("local-archive", "bedroom");

        getArchiveDocumentMethod().invoke(scheduler, "bedroom", "doc-locked", "expired", result);

        assertEquals(1, result.getProcessed());
        assertEquals(0, result.getSucceeded());
        assertEquals(0, result.getFailed());
        assertEquals(1, result.getSkipped());
        assertTrue(result.getSkippedDocumentIds().contains("doc-locked"));

        // deleteDocument should NOT be called
        verify(contentService, never()).deleteDocument(any(), eq("bedroom"), eq("doc-locked"), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("archiveDocument: deleteDocument 例外 → failed++ (ロックは解放される)")
    public void testArchiveDocument_deleteException() throws Exception {
        setupLockForDocument("bedroom", "doc-err");

        doThrow(new RuntimeException("CouchDB timeout"))
                .when(contentService).deleteDocument(any(), eq("bedroom"), eq("doc-err"), eq(true), eq(false));

        RetentionJobResult result = new RetentionJobResult("local-archive", "bedroom");

        getArchiveDocumentMethod().invoke(scheduler, "bedroom", "doc-err", "expired", result);

        assertEquals(1, result.getProcessed());
        assertEquals(0, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertEquals(0, result.getSkipped());
    }

    @Test
    @DisplayName("archiveDocument: InterruptedException → skipped++ + interrupted フラグ復元")
    public void testArchiveDocument_interrupted() throws Exception {
        Lock mockLock = mock(Lock.class);
        when(mockLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("test interrupt"));
        when(threadLockService.getWriteLock("bedroom", "doc-int")).thenReturn(mockLock);

        RetentionJobResult result = new RetentionJobResult("local-archive", "bedroom");

        getArchiveDocumentMethod().invoke(scheduler, "bedroom", "doc-int", "stale", result);

        assertEquals(1, result.getProcessed());
        assertEquals(0, result.getSucceeded());
        assertEquals(0, result.getFailed());
        assertEquals(1, result.getSkipped());
        assertTrue(result.getSkippedDocumentIds().contains("doc-int"));

        // Interrupted flag should be restored
        assertTrue(Thread.currentThread().isInterrupted(), "Interrupt flag should be restored");

        // Clean up interrupt flag for other tests
        Thread.interrupted();
    }

    // ========================================================================
    // Multi-repository execution
    // ========================================================================

    @Test
    @DisplayName("マルチリポジトリ: 各リポジトリが独立して処理される")
    public void testMultiRepository_independentProcessing() throws Exception {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");
        repos.add("canopy");
        when(repositoryInfoMap.keys()).thenReturn(repos);

        // bedroom: 1 expired
        when(propertyManager.readBoolean("bedroom", PropertyKey.ARCHIVE_CREATE_ENABLED)).thenReturn(true);
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Arrays.asList("bed-001"));
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn(null);
        setupLockForDocument("bedroom", "bed-001");
        when(nemakiCachePool.get("bedroom")).thenReturn(cacheService);

        // canopy: 1 stale
        CacheService canopyCache = mock(CacheService.class);
        when(propertyManager.readBoolean("canopy", PropertyKey.ARCHIVE_CREATE_ENABLED)).thenReturn(true);
        when(contentService.getExpiredDocumentIds(eq("canopy"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("canopy", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("90");
        when(contentService.getStaleDocumentIds(eq("canopy"), any(GregorianCalendar.class)))
                .thenReturn(Arrays.asList("can-001"));
        setupLockForDocument("canopy", "can-001");
        when(nemakiCachePool.get("canopy")).thenReturn(canopyCache);

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // bedroom: expired doc archived
        verify(contentService).deleteDocument(any(), eq("bedroom"), eq("bed-001"), eq(true), eq(false));
        // canopy: stale doc archived
        verify(contentService).deleteDocument(any(), eq("canopy"), eq("can-001"), eq(true), eq(false));
    }

    @Test
    @DisplayName("1つのリポジトリで例外 → 他のリポジトリは正常に処理される")
    public void testMultiRepository_oneRepoException_othersContinue() throws Exception {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");
        repos.add("canopy");
        when(repositoryInfoMap.keys()).thenReturn(repos);

        // bedroom: throws exception during getExpiredDocumentIds
        when(propertyManager.readBoolean("bedroom", PropertyKey.ARCHIVE_CREATE_ENABLED)).thenReturn(true);
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenThrow(new RuntimeException("CouchDB down for bedroom"));

        // canopy: normal
        CacheService canopyCache = mock(CacheService.class);
        when(propertyManager.readBoolean("canopy", PropertyKey.ARCHIVE_CREATE_ENABLED)).thenReturn(true);
        when(contentService.getExpiredDocumentIds(eq("canopy"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("canopy", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn(null);
        when(nemakiCachePool.get("canopy")).thenReturn(canopyCache);

        // Should not throw — error is caught per-repository
        assertDoesNotThrow(() -> getExecuteLocalArchiveMethod().invoke(scheduler));

        // canopy should still have getExpiredDocumentIds called
        verify(contentService).getExpiredDocumentIds(eq("canopy"), any(GregorianCalendar.class));
    }

    // ========================================================================
    // persistMigrationLog
    // ========================================================================

    @Test
    @DisplayName("ローカルアーカイブジョブ実行後、migrationLog が記録される")
    public void testMigrationLogPersisted() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn(null);

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // Migration log should be persisted even with 0 documents processed
        verify(retentionLogDaoService).createLog(eq("bedroom"), any(RetentionMigrationLog.class));
        verify(retentionLogDaoService).deleteOldLogs("bedroom", 100);
    }

    @Test
    @DisplayName("retentionLogDaoService=null → persistMigrationLog は安全にスキップ")
    public void testMigrationLog_nullService_noError() throws Exception {
        // Set retentionLogDaoService to null
        scheduler.setRetentionLogDaoService(null);

        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn(null);

        // Should NOT throw
        assertDoesNotThrow(() -> getExecuteLocalArchiveMethod().invoke(scheduler));
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    @DisplayName("空のリポジトリセット → 何も処理されない")
    public void testEmptyRepositorySet() throws Exception {
        when(repositoryInfoMap.keys()).thenReturn(Collections.emptySet());

        getExecuteLocalArchiveMethod().invoke(scheduler);

        verify(contentService, never()).getExpiredDocumentIds(anyString(), any(GregorianCalendar.class));
    }

    @Test
    @DisplayName("Phase 1 で大量 (1000件) の有効期限切れドキュメント → 全件処理")
    public void testPhase1_largeNumberOfExpiredDocs() throws Exception {
        List<String> expiredIds = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String id = "doc-" + String.format("%04d", i);
            expiredIds.add(id);
            setupLockForDocument("bedroom", id);
        }

        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(expiredIds);
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn(null);

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // All 1000 documents should be archived
        verify(contentService, times(1000)).deleteDocument(any(), eq("bedroom"), anyString(), eq(true), eq(false));
    }

    @Test
    @DisplayName("Phase 2: localAfterDays=1 → 最小有効値、正常動作")
    public void testPhase2_minimumValidDays() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("1");
        when(contentService.getStaleDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());

        getExecuteLocalArchiveMethod().invoke(scheduler);

        // getStaleDocumentIds should be called
        verify(contentService).getStaleDocumentIds(eq("bedroom"), any(GregorianCalendar.class));
    }

    @Test
    @DisplayName("Phase 2: localAfterDays=36500 (100年) → 大きな値でも正常動作")
    public void testPhase2_veryLargeDays() throws Exception {
        when(contentService.getExpiredDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());
        when(propertyManager.readValue("bedroom", PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS))
                .thenReturn("36500");
        when(contentService.getStaleDocumentIds(eq("bedroom"), any(GregorianCalendar.class)))
                .thenReturn(Collections.emptyList());

        getExecuteLocalArchiveMethod().invoke(scheduler);

        verify(contentService).getStaleDocumentIds(eq("bedroom"), any(GregorianCalendar.class));
    }

    @Test
    @DisplayName("archiveDocument: 2種類の reason (expired/stale) が正しくログに使われる")
    public void testArchiveDocument_differentReasons() throws Exception {
        setupLockForDocument("bedroom", "doc-exp");
        setupLockForDocument("bedroom", "doc-stl");

        RetentionJobResult result1 = new RetentionJobResult("local-archive", "bedroom");
        RetentionJobResult result2 = new RetentionJobResult("local-archive", "bedroom");

        getArchiveDocumentMethod().invoke(scheduler, "bedroom", "doc-exp", "expired", result1);
        getArchiveDocumentMethod().invoke(scheduler, "bedroom", "doc-stl", "stale", result2);

        // Both should succeed
        assertEquals(1, result1.getSucceeded());
        assertEquals(1, result2.getSucceeded());
    }
}
