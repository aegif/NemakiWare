/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * The retention job does not archive a document someone has checked out (R331, 3.4.2).
 *
 * <p>The job archives an expired or stale document by {@code ContentService.deleteDocument} as the system, every version.
 * The version list hides every user's working copy from the system, so a checked-out document's working copy was left
 * without its series, as force-archive left it (measured on nb33 through force-archive; the job is not measured there —
 * enabling it would archive every expired document of the repository).
 *
 * <p>What is locked, on the product's {@code RetentionScheduler.archiveDocument}: a document
 * {@code ContentService.isCheckedOut} answers true for is not deleted nor removed from the cache, is counted skipped, not archived, and is named with
 * the reason so that it is asked again on the next run; one it answers false for is archived as before; a question it
 * cannot answer deletes nothing and is counted failed; the question is asked under the document's write lock, which is let
 * go after a skip. The content service and the lock service are stand-ins.
 */
class ARetentionArchiveSkipsACheckedOutDocumentTest {

    private static final String REPO = "bedroom";
    private static final String DOC = "doc-1";

    private final RetentionScheduler scheduler = new RetentionScheduler();
    private final ContentService cs = mock(ContentService.class);
    private final ReentrantLock documentsLock = new ReentrantLock();
    private final List<Boolean> askedUnderTheLock = new ArrayList<>();
    private final CacheService repositoryCache = mock(CacheService.class);

    ARetentionArchiveSkipsACheckedOutDocumentTest() {
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(REPO, DOC)).thenReturn(documentsLock);
        NemakiCachePool pool = mock(NemakiCachePool.class);
        when(pool.get(REPO)).thenReturn(repositoryCache);
        Document document = new Document();
        document.setId(DOC);
        document.setName("D.txt");
        document.setVersionSeriesId("series-of-D");
        when(cs.getDocument(REPO, DOC)).thenReturn(document);
        scheduler.setContentService(cs);
        scheduler.setThreadLockService(locks);
        scheduler.setNemakiCachePool(pool);
    }

    private RetentionJobResult archive() throws Exception {
        Method m = RetentionScheduler.class.getDeclaredMethod("archiveDocument", String.class, String.class, String.class,
                RetentionJobResult.class);
        m.setAccessible(true);
        RetentionJobResult result = new RetentionJobResult("local-archive", REPO);
        m.invoke(scheduler, REPO, DOC, "expired", result);
        return result;
    }

    private void checkedOut(boolean answer) {
        when(cs.isCheckedOut(eq(REPO), any(Document.class))).thenAnswer(call -> {
            askedUnderTheLock.add(documentsLock.isHeldByCurrentThread());
            return answer;
        });
    }

    @Test
    @DisplayName("a document someone has checked out is not deleted, counted skipped and named with the reason, and its lock is let go")
    void aCheckedOutDocumentIsSkipped() throws Exception {
        checkedOut(true);
        RetentionJobResult result = archive();
        verify(cs, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        assertEquals(1, result.getSkipped(), "skipped");
        assertEquals(0, result.getSucceeded(), "archived");
        assertEquals(0, result.getFailed(), "failed");
        assertEquals(List.of(DOC + " (checked out)"), result.getSkippedDocumentIds(), "the documents named as skipped");
        verify(repositoryCache, never()).removeCmisCache(anyString());
        assertFalse(documentsLock.isLocked(), "the document's write lock is held after the skip");
    }

    @Test
    @DisplayName("a document nobody has checked out is archived as before")
    void aDocumentNotCheckedOutIsArchivedAsBefore() throws Exception {
        checkedOut(false);
        RetentionJobResult result = archive();
        verify(cs).deleteDocument(any(), eq(REPO), eq(DOC), eq(true), eq(false));
        assertEquals(1, result.getSucceeded(), "archived");
    }

    @Test
    @DisplayName("a question that cannot be answered deletes nothing and is counted failed")
    void aStateThatCannotBeReadDeletesNothing() throws Exception {
        when(cs.isCheckedOut(eq(REPO), any(Document.class))).thenThrow(new IllegalStateException("the version lookup failed"));
        RetentionJobResult result = archive();
        verify(cs, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        assertEquals(1, result.getFailed(), "failed");
        assertEquals(0, result.getSucceeded(), "archived");
    }

    @Test
    @DisplayName("the question is asked under the document's write lock")
    void theQuestionIsAskedUnderTheLock() throws Exception {
        checkedOut(false);
        archive();
        assertEquals(List.of(true), askedUnderTheLock, "whether the write lock was held when it was asked");
        assertTrue(!documentsLock.isLocked(), "the lock is held after the archive");
    }
}
