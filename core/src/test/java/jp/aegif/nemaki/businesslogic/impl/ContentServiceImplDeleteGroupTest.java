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
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - shared canonical group delete tests (#14 REST consolidation)
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.GroupItem;

/**
 * Tests for the consolidated {@code ContentService.deleteGroup}, the canonical
 * delete shared by the three group REST stacks. It must (a) return false for a
 * missing group, (b) strip the group from every other group's nested-groups list
 * before deleting, and (c) only persist groups that actually referenced it.
 */
public class ContentServiceImplDeleteGroupTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = spy(new ContentServiceImpl());
        doNothing().when(service).delete(any(), any(), any(), any());
        doReturn(null).when(service).update(any(), any(), any());
    }

    private GroupItem group(String businessId, String docId, List<String> nested) {
        GroupItem g = mock(GroupItem.class);
        when(g.getGroupId()).thenReturn(businessId);
        when(g.getId()).thenReturn(docId);
        when(g.getGroups()).thenReturn(nested);
        return g;
    }

    @Test
    public void returnsFalseWhenGroupMissing() {
        doReturn(null).when(service).getGroupItemById(REPO, "ghost");

        assertFalse(service.deleteGroup(REPO, "ghost"));

        verify(service, never()).delete(any(), any(), any(), any());
        verify(service, never()).update(any(), any(), any());
    }

    @Test
    public void deletesAndStripsNestedReference() {
        GroupItem target = group("tgt", "tgt-doc", new ArrayList<>());
        GroupItem parent = group("parent", "parent-doc", new ArrayList<>(List.of("tgt", "keep")));
        GroupItem unrelated = group("unrelated", "unrelated-doc", new ArrayList<>(List.of("keep")));

        doReturn(target).when(service).getGroupItemById(REPO, "tgt");
        // nested cleanup re-fetches each referencing group FRESH (revision-bearing, and
        // immune to a stale cache from another replica)
        doReturn(parent).when(service).getGroupItemByIdFresh(REPO, "parent");
        // The cleanup asks the reverse-lookup view who nests "tgt" rather than listing every
        // group and scanning. "unrelated" is deliberately NOT in this answer — that is what the
        // view returns — and the assertions below still require it to be left alone.
        doReturn(List.of("parent")).when(service).getGroupIdsDirectlyContainingGroup(REPO, "tgt");

        assertTrue(service.deleteGroup(REPO, "tgt"));

        // parent had its nested-groups rewritten without "tgt"
        verify(parent).setGroups(List.of("keep"));
        verify(service).update(any(), eq(REPO), eq(parent));
        // unrelated never referenced tgt -> never re-persisted
        verify(unrelated, never()).setGroups(any());
        // and the target itself is deleted by its document id
        verify(service).delete(any(), eq(REPO), eq("tgt-doc"), eq(false));
    }

    @Test
    public void deletesWhenNoNestedReferencesExist() {
        GroupItem target = group("tgt", "tgt-doc", new ArrayList<>());
        GroupItem other = group("other", "other-doc", new ArrayList<>(List.of("keep")));

        doReturn(target).when(service).getGroupItemById(REPO, "tgt");
        doReturn(List.of()).when(service).getGroupIdsDirectlyContainingGroup(REPO, "tgt");

        assertTrue(service.deleteGroup(REPO, "tgt"));

        verify(service, never()).update(any(), any(), any());
        verify(service).delete(any(), eq(REPO), eq("tgt-doc"), eq(false));
    }
}
