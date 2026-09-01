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

import jp.aegif.nemaki.util.test.HarnessBroken;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.service.ObjectServiceInternal;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * THE PRODUCTION deleteTree walk keeps a folder whose listing came back short.
 *
 * <h2>Why this test names the private walk and not the service method</h2>
 *
 * <p>The first version of this protection was written into
 * {@code ContentServiceImpl.deleteTree} — a method with NO production caller. Every binding
 * (AtomPub, Browser, REST v1) funnels into {@code ObjectServiceImpl.deleteTreeDFS}, which kept
 * deleting parents over children a decode-shortened listing had hidden. A guard on the wrong
 * sibling protects nothing, and the review that found it put it plainly: the batch's oldest
 * lesson, applied to its own newest fix. So this test drives the walk that production drives,
 * by reflection because it is private — a seam that breaks loudly (AssertionError below) if the
 * method is renamed.
 */
class DeleteTreeDfsKeepsFoldersOverInvisibleChildrenTest {

    private static final String REPO = "bedroom";

    @Test
    @DisplayName("a short listing keeps the folder and reports it in failedIds")
    void aShortListingKeepsTheFolder() throws Exception {
        ContentService contentService = mock(ContentService.class);
        ObjectServiceInternal internal = mock(ObjectServiceInternal.class);
        Folder folder = new Folder();
        folder.setId("f-1");
        when(contentService.getChildren(REPO, "f-1")).thenReturn(List.of());
        when(contentService.lastUnreadableChildCount()).thenReturn(1);

        List<String> failedIds = new ArrayList<>();
        invokeDfs(serviceWith(contentService, internal), folder, failedIds);

        assertTrue(failedIds.contains("f-1"),
                "the folder is not reported, so the caller believes the tree is gone: "
                        + failedIds);
        verify(internal, never()).deleteObjectInternal(any(), anyString(), any(Content.class),
                any(), any());
    }

    @Test
    @DisplayName("a clean listing still deletes — the control")
    void aCleanListingStillDeletes() throws Exception {
        ContentService contentService = mock(ContentService.class);
        ObjectServiceInternal internal = mock(ObjectServiceInternal.class);
        Folder folder = new Folder();
        folder.setId("f-2");
        when(contentService.getChildren(REPO, "f-2")).thenReturn(List.of());
        when(contentService.lastUnreadableChildCount()).thenReturn(0);

        List<String> failedIds = new ArrayList<>();
        invokeDfs(serviceWith(contentService, internal), folder, failedIds);

        assertTrue(failedIds.isEmpty(),
                "an ordinary empty folder was refused deletion, so the protection is an "
                        + "outage: " + failedIds);
        verify(internal).deleteObjectInternal(any(), anyString(), any(Content.class),
                any(), any());
    }

    @Test
    @DisplayName("a failed descendant keeps every ancestor, not only the direct parent")
    void aFailedDescendantKeepsTheAncestors() throws Exception {
        ContentService contentService = mock(ContentService.class);
        ObjectServiceInternal internal = mock(ObjectServiceInternal.class);
        Folder top = new Folder();
        top.setId("top");
        Folder sub = new Folder();
        sub.setId("sub");
        when(contentService.getChildren(REPO, "top"))
                .thenReturn(List.of((Content) sub));
        when(contentService.getChildren(REPO, "sub")).thenReturn(List.of());
        // top's listing decodes fully; sub's lost a row.
        when(contentService.lastUnreadableChildCount()).thenReturn(0, 1);

        List<String> failedIds = new ArrayList<>();
        invokeDfs(serviceWith(contentService, internal), top, failedIds);

        assertTrue(failedIds.contains("sub"), String.valueOf(failedIds));
        assertTrue(failedIds.contains("top"),
                "the ancestor deleted itself over a subtree that still holds content: "
                        + failedIds);
        verify(internal, never()).deleteObjectInternal(any(), anyString(), any(Content.class),
                any(), any());
        assertFalse(failedIds.isEmpty());
    }

    private static ObjectServiceImpl serviceWith(ContentService contentService,
            ObjectServiceInternal internal) {
        ObjectServiceImpl service = new ObjectServiceImpl();
        service.setContentService(contentService);
        service.setObjectServiceInternal(internal);
        service.setExceptionService(mock(ExceptionService.class));
        return service;
    }

    private static void invokeDfs(ObjectServiceImpl service, Folder node, List<String> failedIds)
            throws Exception {
        try {
            Method dfs = ObjectServiceImpl.class.getDeclaredMethod("deleteTreeDFS",
                    org.apache.chemistry.opencmis.commons.server.CallContext.class, String.class,
                    Content.class, Boolean.class, Boolean.class, List.class);
            dfs.setAccessible(true);
            dfs.invoke(service, null, REPO, node, Boolean.TRUE, Boolean.TRUE, failedIds);
        } catch (NoSuchMethodException e) {
            throw new HarnessBroken("deleteTreeDFS was renamed or reshaped — update this test "
                    + "to keep driving the walk production drives; without it the guard is "
                    + "pinned only by a presence check", e);
        }
    }

    @Test
    @DisplayName("a RETAINED folder is not removed from the search index")
    void aRetainedFolderStaysFindable() throws Exception {
        // The public method's postlude called solrUtil.deleteDocument for the requested folder
        // unconditionally — so every guard that RETAINS the folder left it in CouchDB while
        // erasing it from every search result: kept for safety, unfindable because of it. The
        // public method needs live exception/permission wiring this harness does not carry, so
        // the condition is pinned in source: driving it would test the harness, and the shape
        // that failed here twice was a guard attached to something production does not run.
        String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceImpl.java"));
        int solrDelete = source.indexOf("solrUtil.deleteDocument(repositoryId, folder.getId())");
        assertTrue(solrDelete >= 0,
                "fixture check: the postlude moved; update this lock with it");
        String guard = source.substring(Math.max(0, solrDelete - 200), solrDelete);
        assertTrue(guard.contains("!failedIds.contains(folder.getId())"),
                "the Solr removal no longer asks whether the folder was actually deleted, so a "
                        + "retained folder vanishes from search while still existing");
    }
}
