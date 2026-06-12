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
 *     aegif - shared archive restore-guard tests (#14 REST consolidation)
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ArchiveRestoreOutcome;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;

/**
 * Tests for the consolidated {@code ContentService.restoreArchiveGuarded}, the
 * authorization + cold-storage + parent-existence guard shared by the legacy and
 * api/v1 archive restore stacks.
 */
public class ContentServiceImplRestoreArchiveGuardedTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = spy(new ContentServiceImpl());
    }

    private Archive archive(String creator, String archivedBy, String state) {
        Archive a = mock(Archive.class);
        when(a.getCreator()).thenReturn(creator);
        when(a.getArchivedBy()).thenReturn(archivedBy);
        when(a.getEffectiveArchiveState()).thenReturn(state);
        return a;
    }

    @Test
    public void notFoundWhenMissing() throws Exception {
        doReturn(null).when(service).getArchive(REPO, "a1");
        assertEquals(ArchiveRestoreOutcome.NOT_FOUND,
                service.restoreArchiveGuarded(REPO, "a1", "alice", false));
        verify(service, never()).restoreArchive(any(), any());
    }

    @Test
    public void notFoundWhenNonAdminAndNotAccessible() throws Exception {
        doReturn(archive("owner", "deleter", "ARCHIVED")).when(service).getArchive(REPO, "a1");
        assertEquals(ArchiveRestoreOutcome.NOT_FOUND,
                service.restoreArchiveGuarded(REPO, "a1", "stranger", false));
        verify(service, never()).restoreArchive(any(), any());
    }

    @Test
    public void restoresWhenNonAdminIsCreator() throws Exception {
        doReturn(archive("alice", "deleter", "ARCHIVED")).when(service).getArchive(REPO, "a1");
        doNothing().when(service).restoreArchive(REPO, "a1");
        assertEquals(ArchiveRestoreOutcome.RESTORED,
                service.restoreArchiveGuarded(REPO, "a1", "alice", false));
        verify(service).restoreArchive(REPO, "a1");
    }

    @Test
    public void coldStorageRejectedEvenForAdmin() throws Exception {
        doReturn(archive("owner", "deleter", Archive.STATE_ARCHIVED_COLD)).when(service).getArchive(REPO, "a1");
        assertEquals(ArchiveRestoreOutcome.COLD_STORAGE,
                service.restoreArchiveGuarded(REPO, "a1", "admin", true));
        verify(service, never()).restoreArchive(any(), any());
    }

    @Test
    public void parentGoneMappedFromException() throws Exception {
        doReturn(archive("owner", "deleter", "ARCHIVED")).when(service).getArchive(REPO, "a1");
        doThrow(new ParentNoLongerExistException()).when(service).restoreArchive(REPO, "a1");
        assertEquals(ArchiveRestoreOutcome.PARENT_GONE,
                service.restoreArchiveGuarded(REPO, "a1", "admin", true));
    }

    @Test
    public void isArchiveAccessibleMatchesCreatorOrDeleter() {
        Archive a = archive("owner", "deleter", "ARCHIVED");
        assertTrue(service.isArchiveAccessible("owner", a));
        assertTrue(service.isArchiveAccessible("deleter", a));
        assertFalse(service.isArchiveAccessible("stranger", a));
        assertFalse(service.isArchiveAccessible(null, a));
    }
}
