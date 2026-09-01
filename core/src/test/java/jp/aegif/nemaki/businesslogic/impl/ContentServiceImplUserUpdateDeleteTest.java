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
 *     aegif - shared user update/delete + password hashing tests (#14)
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
import org.mindrot.jbcrypt.BCrypt;

import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;

/**
 * Tests for the consolidated user tails on ContentService: {@code hashPassword}
 * (BCrypt), {@code applyUserUpdate} (signature + persist) and {@code deleteUser}
 * (canonical delete that strips group memberships before removing the user).
 */
public class ContentServiceImplUserUpdateDeleteTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = spy(new ContentServiceImpl());
        doNothing().when(service).delete(any(), any(), any(), any());
        doReturn(null).when(service).update(any(), any(), any());
    }

    @Test
    public void hashPasswordIsBcryptVerifiable() {
        String hash = service.hashPassword("s3cret");
        assertNotEquals("s3cret", hash);
        assertTrue(BCrypt.checkpw("s3cret", hash));
        assertFalse(BCrypt.checkpw("wrong", hash));
    }

    @Test
    public void applyUserUpdateStampsModifierAndPersists() {
        UserItem user = mock(UserItem.class);

        service.applyUserUpdate(REPO, user, "admin");

        verify(user).setModifier("admin");
        verify(user).setModified(any());
        verify(service).update(any(), eq(REPO), eq(user));
    }

    @Test
    public void deleteUserReturnsFalseWhenMissing() {
        doReturn(null).when(service).getUserItemById(REPO, "ghost");

        assertFalse(service.deleteUser(REPO, "ghost"));

        verify(service, never()).delete(any(), any(), any(), any());
        verify(service, never()).update(any(), any(), any());
    }

    @Test
    public void deleteUserStripsGroupMembershipThenDeletes() {
        UserItem user = mock(UserItem.class);
        when(user.getId()).thenReturn("u1-doc");

        GroupItem g1 = mock(GroupItem.class);
        when(g1.getGroupId()).thenReturn("g1");
        when(g1.getUsers()).thenReturn(new ArrayList<>(List.of("u1", "keep")));
        GroupItem g2 = mock(GroupItem.class);
        when(g2.getGroupId()).thenReturn("g2");
        when(g2.getUsers()).thenReturn(new ArrayList<>(List.of("keep")));

        doReturn(user).when(service).getUserItemById(REPO, "u1");
        // The cleanup asks the reverse-lookup view which groups list "u1", rather than fetching
        // every group in the repository and scanning its member list. g2 is deliberately absent
        // from that answer, and the assertions below still require it to be left untouched.
        doReturn(List.of("g1")).when(service).getGroupIdsDirectlyContainingUser(REPO, "u1");
        // membership cleanup re-fetches each referencing group FRESH (revision-bearing,
        // and immune to a stale cache from another replica)
        doReturn(g1).when(service).getGroupItemByIdFresh(REPO, "g1");

        assertTrue(service.deleteUser(REPO, "u1"));

        verify(g1).setUsers(List.of("keep"));
        verify(service).update(any(), eq(REPO), eq(g1));
        verify(g2, never()).setUsers(any());
        verify(service).delete(any(), eq(REPO), eq("u1-doc"), eq(false));
    }
}
