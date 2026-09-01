/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * A page of children that lost rows to a failed decode is refused, not served short.
 *
 * <h2>The approximation that was not one</h2>
 *
 * <p>Rows the store cannot decode are consumed on the wire and absent from the batch. The
 * cursor arithmetic accounts for them — that was fixed — but the ANSWER did not: the client
 * received a page missing those children with nothing to distinguish it from a complete one.
 * It was carried for several rounds as "the same layer as the existing numItems imprecision",
 * and that is the wrong comparison: {@code numItems} is a count that may be approximate, while
 * this is a statement about WHICH OBJECTS EXIST in a folder.
 *
 * <p>The same method already refuses when the first probe comes back empty for the same
 * reason, so this is the established rule applied to the page rather than a new one.
 */
class ChildrenPageIsNotSilentlyShortTest {

    private static final String REPO = "bedroom";
    private static final String FOLDER = "folder-1";

    private static Document child(String id) {
        Document d = new Document();
        d.setId(id);
        d.setName(id);
        d.setType("cmis:document");
        d.setObjectType("cmis:document");
        d.setParentId(FOLDER);
        return d;
    }

    /**
     * Wires the service with a store that returns {@code children} and reports
     * {@code unreadable} rows dropped from the same read.
     */
    private static NavigationServiceImpl serviceReturning(List<Content> children, int unreadable) {
        ContentService contentService = mock(ContentService.class);
        when(contentService.getChildrenPaged(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(children));
        when(contentService.lastUnreadableChildCount()).thenReturn(unreadable);
        // 0 sends the service down the probe path — the branch a folder of this size takes
        // in production, and the one whose listing IS the probe.
        when(contentService.getChildrenCount(anyString(), anyString())).thenReturn(0L);

        Folder folder = new Folder();
        folder.setId(FOLDER);
        folder.setName("records");
        folder.setType("cmis:folder");
        folder.setObjectType("cmis:folder");
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(folder);
        when(contentService.getContent(REPO, FOLDER)).thenReturn(folder);

        NavigationServiceImpl service = new NavigationServiceImpl();
        service.setContentService(contentService);
        service.setExceptionService(mock(ExceptionService.class));
        service.setCompileService(mock(CompileService.class));
        PermissionService permission = mock(PermissionService.class);
        when(permission.checkPermissionWithGivenList(any(), anyString(), anyString(), any(),
                anyString(), any(), anyString(), any())).thenReturn(true);
        service.setPermissionService(permission);
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.orderedLocks(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.of());
        when(locks.getReadLock(anyString(), anyString()))
                .thenReturn(new java.util.concurrent.locks.ReentrantLock());
        service.setThreadLockService(locks);
        service.setPropertyManager(mock(jp.aegif.nemaki.util.PropertyManager.class));
        return service;
    }

    private static CallContext adminContext() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.get(CallContext.USERNAME)).thenReturn("admin");
        return ctx;
    }

    @Test
    @DisplayName("a page short by an undecodable row refuses rather than answering short")
    void aPageShortByADecodeFailureRefuses() {
        NavigationServiceImpl service = serviceReturning(
                List.of(child("d1"), child("d2")), 1);

        CmisRuntimeException refused = assertThrows(CmisRuntimeException.class,
                () -> service.getChildren(adminContext(), REPO, FOLDER, null, null, false,
                        null, null, false, BigInteger.valueOf(10), BigInteger.ZERO,
                        new org.apache.chemistry.opencmis.commons.spi.Holder<>(), null),
                "a listing missing a child the folder holds was served as the folder's "
                        + "contents, and nothing in the response said so");
        assertTrue(refused.getMessage().contains("could not be decoded")
                        || refused.getMessage().contains("short by"),
                "refused for some other reason: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("1 row"),
                "the refusal does not say how much is missing: " + refused.getMessage());
    }
}
