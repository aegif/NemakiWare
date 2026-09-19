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
package jp.aegif.nemaki.businesslogic.impl;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.PropertyManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A folder whose listing came back SHORT is not deleted over its invisible children.
 *
 * <h2>The orphan this prevents</h2>
 *
 * <p>{@code deleteTree} walks {@code getChildren}, deletes what it sees, then deletes the folder
 * itself. Rows the repository could not decode are not in that list — they are neither deleted
 * nor counted as failures — so deleting the parent leaves them hanging from a folder that no
 * longer exists: content that is real, reachable by id, and invisible to every tree walk. Unlike
 * the read paths this batch corrected, a deletion has no reconcile pass to catch it later.
 *
 * <p>The nested result was also DISCARDED: a subtree that could not be fully deleted reported
 * nothing to its parent, which then deleted itself over the survivors. Both arms are pinned here.
 */
class DeleteTreeKeepsTheParentOfUnreadableChildrenTest {

    private static final String REPO = "bedroom";

    @Test
    @DisplayName("a short listing keeps the folder, and the folder is reported as a failure")
    void aShortListingKeepsTheFolder() {
        ContentDaoService dao = mock(ContentDaoService.class);
        Folder folder = new Folder();
        folder.setId("f-1");
        when(dao.getFolder(REPO, "f-1")).thenReturn(folder);
        when(dao.getContent(REPO, "f-1")).thenReturn(folder);
        // The listing is EMPTY but one row could not be decoded — the shape a damaged child
        // row arrives in. Nothing here throws; the only signal is the counter.
        when(dao.getChildren(REPO, "f-1")).thenReturn(List.of());
        when(dao.lastUnreadableChildCount()).thenReturn(1);

        // Spied at the SERVICE boundary: deleteInternal needs change-event and archive wiring
        // this test does not carry, and stubbing all of it would measure the harness. What the
        // guard governs is whether delete() is CALLED for the folder.
        ContentServiceImpl service = spy(serviceWith(dao));
        doNothing().when(service).delete(any(), anyString(), anyString(), any());
        List<String> failed = service.deleteTree(null, REPO, "f-1", true, true, false);

        assertTrue(failed.contains("f-1"),
                "the folder is not in the failure list, so the caller believes the tree is "
                        + "gone: " + failed);
        verify(service, never()).delete(any(), eq(REPO), eq("f-1"), any());
    }

    @Test
    @DisplayName("a clean listing still deletes the folder — the control")
    void aCleanListingStillDeletes() {
        ContentDaoService dao = mock(ContentDaoService.class);
        Folder folder = new Folder();
        folder.setId("f-2");
        when(dao.getFolder(REPO, "f-2")).thenReturn(folder);
        when(dao.getContent(REPO, "f-2")).thenReturn(folder);
        when(dao.getChildren(REPO, "f-2")).thenReturn(List.of());
        when(dao.lastUnreadableChildCount()).thenReturn(0);

        ContentServiceImpl service = spy(serviceWith(dao));
        doNothing().when(service).delete(any(), anyString(), anyString(), any());
        List<String> failed = service.deleteTree(null, REPO, "f-2", true, true, false);

        assertTrue(failed.isEmpty(),
                "an ordinary empty folder was refused deletion, so the protection is an "
                        + "outage: " + failed);
        verify(service).delete(any(), eq(REPO), eq("f-2"), any());
    }

    @Test
    @DisplayName("a subtree's failures reach the top, and the top folder is kept")
    void nestedFailuresAreNotDiscarded() {
        ContentDaoService dao = mock(ContentDaoService.class);
        Folder top = new Folder();
        top.setId("top");
        Folder sub = new Folder();
        sub.setId("sub");
        sub.setType("cmis:folder");
        when(dao.getFolder(REPO, "top")).thenReturn(top);
        when(dao.getContent(REPO, "top")).thenReturn(top);
        when(dao.getFolder(REPO, "sub")).thenReturn(sub);
        when(dao.getContent(REPO, "sub")).thenReturn(sub);
        when(dao.getChildren(REPO, "top")).thenReturn(List.of((Content) sub));
        when(dao.getChildren(REPO, "sub")).thenReturn(List.of());
        // The top listing decoded fully; the SUB listing lost a row. Before the fix the nested
        // call's failure list was thrown away, so the top folder was deleted over a subtree
        // that still held an invisible child.
        when(dao.lastUnreadableChildCount()).thenReturn(0, 1);

        ContentServiceImpl service = spy(serviceWith(dao));
        doNothing().when(service).delete(any(), anyString(), anyString(), any());
        List<String> failed = service.deleteTree(null, REPO, "top", true, true, false);

        assertTrue(failed.contains("sub"),
                "the subtree's failure never reached the caller: " + failed);
        assertTrue(failed.contains("top"),
                "the top folder deleted itself over a subtree that still holds content: "
                        + failed);
        verify(service, never()).delete(any(), eq(REPO), eq("top"), any());
    }

    private static ContentServiceImpl serviceWith(ContentDaoService dao) {
        PropertyManager properties = mock(PropertyManager.class);
        when(properties.readValue(anyString())).thenReturn("false");

        ContentServiceImpl service = new ContentServiceImpl();
        service.setContentDaoService(dao);
        service.setPropertyManager(properties);
        return service;
    }
}
