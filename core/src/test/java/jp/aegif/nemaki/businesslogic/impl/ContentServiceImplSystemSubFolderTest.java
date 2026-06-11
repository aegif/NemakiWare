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
 *     aegif - consolidated getOrCreateSystemSubFolder tests
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;

/**
 * Tests for the consolidated {@code ContentService.getOrCreateSystemSubFolder},
 * which replaced ~9 copy-pasted helpers across the REST resources, patches and
 * directory sync. A spy stubs the collaborating ContentServiceImpl methods so we
 * exercise only the consolidated control flow.
 */
public class ContentServiceImplSystemSubFolderTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = spy(new ContentServiceImpl());
    }

    private Folder folder(String id, String name) {
        Folder f = mock(Folder.class);
        when(f.getId()).thenReturn(id);
        when(f.getName()).thenReturn(name);
        return f;
    }

    @Test
    public void returnsExistingSubFolderWithoutCreating() {
        Folder system = folder("sys-1", ".system");
        Folder existing = folder("users-1", "users");
        doReturn(system).when(service).getSystemFolder(REPO);
        doReturn(List.<Content>of(existing)).when(service).getChildren(REPO, "sys-1");

        Folder result = service.getOrCreateSystemSubFolder(REPO, "users");

        assertSame(existing, result);
        verify(service, never()).createFolder(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void createsSubFolderWhenAbsent() {
        Folder system = folder("sys-1", ".system");
        Folder created = folder("groups-1", "groups");
        doReturn(system).when(service).getSystemFolder(REPO);
        doReturn(List.<Content>of()).when(service).getChildren(REPO, "sys-1");
        doReturn(created).when(service).createFolder(any(), eq(REPO), any(), eq(system),
                any(), any(), any(), any());

        Folder result = service.getOrCreateSystemSubFolder(REPO, "groups");

        assertSame(created, result);
    }

    @Test
    public void fallsBackToSystemPathWhenPropertyLookupReturnsNull() {
        Folder system = folder("sys-1", ".system");
        Folder created = folder("users-1", "users");
        doReturn(null).when(service).getSystemFolder(REPO);
        doReturn(system).when(service).getContentByPath(REPO, "/.system");
        doReturn(List.<Content>of()).when(service).getChildren(REPO, "sys-1");
        doReturn(created).when(service).createFolder(any(), eq(REPO), any(), eq(system),
                any(), any(), any(), any());

        Folder result = service.getOrCreateSystemSubFolder(REPO, "users");

        assertSame(created, result);
    }

    @Test
    public void throwsWhenSystemFolderUnavailable() {
        doReturn(null).when(service).getSystemFolder(REPO);
        doReturn(null).when(service).getContentByPath(REPO, "/.system");

        assertThrows(RuntimeException.class, () -> service.getOrCreateSystemSubFolder(REPO, "users"));
    }
}
