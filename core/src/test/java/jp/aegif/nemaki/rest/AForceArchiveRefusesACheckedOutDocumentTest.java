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
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * Force-archive refuses a document someone has checked out (R331, 3.4.2).
 *
 * <p>Force-archive deletes every version by {@code ContentService.deleteDocument} as the system. The version list hides
 * every user's working copy from the system, so the versions and the series went and a checked-out document's working
 * copy stayed without them: its holder's reads and every non-administrator's checked-out listing answered 500 (measured
 * on nb33).
 *
 * <p>What is locked, on the product's {@code ArchiveResource.forceArchive}: a document {@code ContentService.isCheckedOut}
 * answers true for is answered 409 naming the reason, is not deleted nor removed from the cache, and its lock is let go; one it answers false for is archived as
 * before; a question it cannot answer deletes nothing and is answered as a failure, not a 409; the question is asked under
 * the document's write lock. The content service and the lock service are stand-ins.
 */
class AForceArchiveRefusesACheckedOutDocumentTest {

    private static final String REPO = "bedroom";
    private static final String DOC = "the-document";

    private final ContentService cs = mock(ContentService.class);
    private final ThreadLockService locks = mock(ThreadLockService.class);
    private final ReentrantLock documentsLock = new ReentrantLock();
    private final ArchiveResource resource = new ArchiveResource();
    private final NemakiCachePool cachePool = mock(NemakiCachePool.class, RETURNS_DEEP_STUBS);
    private final Document document = new Document();
    private final List<Boolean> askedUnderTheLock = new ArrayList<>();

    AForceArchiveRefusesACheckedOutDocumentTest() {
        when(locks.getWriteLock(REPO, DOC)).thenReturn(documentsLock);
        document.setId(DOC);
        document.setName("D.txt");
        document.setVersionSeriesId("series-of-D");
        when(cs.getDocument(REPO, DOC)).thenReturn(document);
        resource.setContentService(cs);
        resource.setThreadLockService(locks);
        resource.setNemakiCachePool(cachePool);
    }

    private void checkedOut(boolean answer) {
        when(cs.isCheckedOut(eq(REPO), any(Document.class))).thenAnswer(call -> {
            askedUnderTheLock.add(documentsLock.isHeldByCurrentThread());
            return answer;
        });
    }

    private static HttpServletRequest anAdministratorsRequest() {
        CallContext context = mock(CallContext.class);
        when(context.getUsername()).thenReturn("admin");
        when(context.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(anyString())).thenAnswer(call -> "CallContext".equals(call.getArgument(0)) ? context : null);
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        return request;
    }

    @Test
    @DisplayName("a document someone has checked out is answered 409 and not deleted, and its lock is let go")
    void aCheckedOutDocumentIsAnswered409() {
        checkedOut(true);
        WebApplicationException refusal = assertThrows(WebApplicationException.class,
                () -> resource.forceArchive(REPO, DOC, anAdministratorsRequest()), "force-archiving a checked-out document");
        assertEquals(409, refusal.getResponse().getStatus(), "the status of the refusal");
        assertTrue(String.valueOf(refusal.getResponse().getEntity()).contains("checkedOut"),
                "the refusal names its reason: " + refusal.getResponse().getEntity());
        verify(cs, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        verify(cachePool.get(REPO), never()).removeCmisCache(anyString());
        assertFalse(documentsLock.isLocked(), "the document's write lock is held after the refusal");
    }

    @Test
    @DisplayName("a document nobody has checked out is archived as before")
    void aDocumentNotCheckedOutIsArchivedAsBefore() {
        checkedOut(false);
        String answer = resource.forceArchive(REPO, DOC, anAdministratorsRequest());
        verify(cs).deleteDocument(any(), eq(REPO), eq(DOC), eq(true), eq(false));
        assertTrue(answer.contains("success"), "the answer: " + answer);
    }

    @Test
    @DisplayName("a question that cannot be answered deletes nothing and is answered as a failure, not as a check-out")
    void aStateThatCannotBeReadDeletesNothing() {
        when(cs.isCheckedOut(eq(REPO), any(Document.class))).thenThrow(new IllegalStateException("the version lookup failed"));
        String answer = resource.forceArchive(REPO, DOC, anAdministratorsRequest());
        verify(cs, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        assertTrue(answer.contains("failure") || answer.contains("false"), "the answer: " + answer);
    }

    @Test
    @DisplayName("the question is asked under the document's write lock")
    void theQuestionIsAskedUnderTheLock() {
        checkedOut(false);
        resource.forceArchive(REPO, DOC, anAdministratorsRequest());
        assertEquals(List.of(true), askedUnderTheLock, "whether the write lock was held when it was asked");
    }
}
