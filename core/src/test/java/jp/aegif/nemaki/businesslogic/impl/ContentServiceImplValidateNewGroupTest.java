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
 *     aegif - shared group-create validation tests (#14 REST consolidation)
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.GroupValidation;
import jp.aegif.nemaki.model.GroupItem;

/**
 * Tests for the consolidated {@code ContentService.validateNewGroup}, the single
 * source of truth shared by the legacy Jersey, Spring MVC and api/v1 group-create
 * bindings. A spy stubs {@code getGroupItemById} so we exercise only the
 * validation logic.
 */
public class ContentServiceImplValidateNewGroupTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = spy(new ContentServiceImpl());
    }

    @Test
    public void acceptsValidUniqueGroup() {
        doReturn(null).when(service).getGroupItemById(REPO, "newgroup");

        List<GroupValidation> reasons = service.validateNewGroup(REPO, "newgroup", "New Group");

        assertTrue(reasons.isEmpty());
    }

    @Test
    public void rejectsBlankGroupId() {
        List<GroupValidation> reasons = service.validateNewGroup(REPO, "  ", "New Group");

        assertEquals(List.of(GroupValidation.ID_REQUIRED), reasons);
        // uniqueness must not be probed when id is blank
        verify(service, never()).getGroupItemById(eq(REPO), anyString());
    }

    @Test
    public void rejectsBlankName() {
        doReturn(null).when(service).getGroupItemById(REPO, "newgroup");

        List<GroupValidation> reasons = service.validateNewGroup(REPO, "newgroup", "");

        assertEquals(List.of(GroupValidation.NAME_REQUIRED), reasons);
    }

    @Test
    public void rejectsDuplicateGroupId() {
        doReturn(mock(GroupItem.class)).when(service).getGroupItemById(REPO, "existing");

        List<GroupValidation> reasons = service.validateNewGroup(REPO, "existing", "Existing Group");

        assertEquals(List.of(GroupValidation.ALREADY_EXISTS), reasons);
    }

    @Test
    public void reportsAllReasonsForBlankIdAndName() {
        List<GroupValidation> reasons = service.validateNewGroup(REPO, "", "");

        // id+name both required; ordering is id-first then name (no uniqueness probe)
        assertEquals(List.of(GroupValidation.ID_REQUIRED, GroupValidation.NAME_REQUIRED), reasons);
    }
}
