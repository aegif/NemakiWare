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
 *     aegif - shared user build+persist tests (#14 REST consolidation)
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;

/**
 * Tests for the consolidated {@code ContentService.buildAndCreateUser}, the
 * single build+persist tail shared by the four group/user REST create paths. It
 * must BCrypt-hash the password, place the user under the system {@code users}
 * folder, attach only the supplied sub-type properties, stamp the creation
 * signature, and persist via {@code createUserItem}.
 */
public class ContentServiceImplBuildUserTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;
    private Folder usersFolder;

    @BeforeEach
    public void setUp() {
        service = spy(new ContentServiceImpl());
        usersFolder = mock(Folder.class);
        when(usersFolder.getId()).thenReturn("users-folder-id");
        doReturn(usersFolder).when(service).getOrCreateSystemSubFolder(REPO, "users");
        doReturn(null).when(service).createUserItem(any(), any(), any(UserItem.class));
    }

    private Map<String, Object> subTypeMap(UserItem user) {
        Map<String, Object> m = new HashMap<>();
        if (user.getSubTypeProperties() != null) {
            for (Property p : user.getSubTypeProperties()) {
                m.put(p.getKey(), p.getValue());
            }
        }
        return m;
    }

    @Test
    public void hashesPasswordAndPersistsUnderUsersFolder() {
        UserItem returned = service.buildAndCreateUser(REPO, "alice", "Alice",
                "s3cret-pw", "Ali", "Ce", "alice@example.com", "admin");

        ArgumentCaptor<UserItem> captor = ArgumentCaptor.forClass(UserItem.class);
        verify(service).createUserItem(any(), eq(REPO), captor.capture());
        UserItem persisted = captor.getValue();

        assertSame(returned, persisted);
        assertEquals("alice", persisted.getUserId());
        assertEquals("users-folder-id", persisted.getParentId());
        // password is BCrypt-hashed, not stored verbatim
        assertNotEquals("s3cret-pw", persisted.getPassword());
        assertTrue(BCrypt.checkpw("s3cret-pw", persisted.getPassword()));
        // signature stamped with the actor
        assertEquals("admin", persisted.getCreator());
        assertEquals("admin", persisted.getModifier());
        // sub-type properties carry the optional fields
        Map<String, Object> props = subTypeMap(persisted);
        assertEquals("Ali", props.get("nemaki:firstName"));
        assertEquals("Ce", props.get("nemaki:lastName"));
        assertEquals("alice@example.com", props.get("nemaki:email"));
    }

    @Test
    public void omitsNullSubTypeProperties() {
        UserItem user = service.buildAndCreateUser(REPO, "bob", "Bob",
                "pw", null, null, null, "admin");

        Map<String, Object> props = subTypeMap(user);
        assertTrue(props.isEmpty(), "no sub-type properties expected when first/last/email are null");
        assertTrue(BCrypt.checkpw("pw", user.getPassword()));
    }
}
