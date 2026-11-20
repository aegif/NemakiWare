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
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * A parent folder is answered only to a caller who may read it (R311, 3.4.2).
 *
 * <p>{@code getObjectParents} and {@code getFolderParent} asked one thing of the caller — whether the caller may read
 * the OBJECT's parents, a permission on the object — and then compiled the parent folder. A caller who could read a
 * document, and was refused its parent folder by {@code getObject}, was answered that folder's id, name and other
 * properties by the Browser binding's {@code parents} and {@code parent}, AtomPub's, API v1's and OData's
 * {@code Documents('D')/parents} (measured on nb33 with a user who is no administrator).
 *
 * <p>What is locked: both readers ask the caller's permission to read the parent, of the parent, and a caller who is
 * refused it is refused the reading — the parent is not compiled for the answer. A caller who may read the parent is
 * answered as before.
 *
 * <p>The product's {@code NavigationServiceImpl} and {@code ExceptionServiceImpl} are driven; the content, the
 * permission decision, the compiling of an answer and the locks are stand-ins. The permission stand-in records what was
 * asked of which object, and refuses what a test tells it to.
 */
class AParentFolderIsAnsweredOnlyToACallerWhoMayReadItTest {

    private static final String REPO = "bedroom";
    private static final String READ = PermissionMapping.CAN_GET_PROPERTIES_OBJECT;

    private final ContentService contentService = mock(ContentService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final CompileService compileService = mock(CompileService.class);
    private final ThreadLockService locks = mock(ThreadLockService.class);
    private final CallContext callContext = mock(CallContext.class);
    private final ExceptionServiceImpl exceptionService = spy(new ExceptionServiceImpl());
    private final NavigationServiceImpl navigation = new NavigationServiceImpl();
    /** What the caller was asked for, as 「permission of id」, in order. */
    private final List<String> asked = new ArrayList<>();
    /** What the caller is refused, as 「permission of id」. */
    private final Set<String> refused = new HashSet<>();
    /** The ids of what was compiled for an answer, in order. */
    private final List<String> compiled = new ArrayList<>();

    AParentFolderIsAnsweredOnlyToACallerWhoMayReadItTest() {
        when(callContext.getUsername()).thenReturn("reader");
        UserItem reader = mock(UserItem.class);
        when(reader.isAdmin()).thenReturn(Boolean.FALSE);
        when(contentService.getUserItemById(REPO, "reader")).thenReturn(reader);
        when(contentService.calculateAcl(eq(REPO), any(Content.class))).thenReturn(new Acl());
        when(permissions.checkPermission(eq(callContext), eq(REPO), anyString(), any(), any(), any(Content.class)))
                .thenAnswer(call -> {
                    String what = call.getArgument(2) + " of " + ((Content) call.getArgument(5)).getId();
                    asked.add(what);
                    return !refused.contains(what);
                });
        when(permissions.checkPermissionAtTopLevel(any(), any(), any(), any())).thenReturn(true);
        exceptionService.setContentService(contentService);
        exceptionService.setPermissionService(permissions);
        // Reads the repository's capabilities; whether the parent exists is not what this lock is about.
        doNothing().when(exceptionService).objectNotFoundParentFolder(any(), any(), any());
        when(locks.orderedLocks(any(), any(), anyBoolean())).thenAnswer(call -> List.<Lock>of(new ReentrantLock()));
        when(compileService.compileObjectData(any(), eq(REPO), any(Content.class), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    String id = ((Content) call.getArgument(2)).getId();
                    compiled.add(id);
                    PropertiesImpl properties = new PropertiesImpl();
                    properties.addProperty(new PropertyIdImpl("cmis:objectId", id));
                    ObjectDataImpl data = new ObjectDataImpl();
                    data.setProperties(properties);
                    return data;
                });
        navigation.setContentService(contentService);
        navigation.setExceptionService(exceptionService);
        navigation.setCompileService(compileService);
        navigation.setThreadLockService(locks);

        Folder parent = new Folder();
        parent.setId("P");
        parent.setType("cmis:folder");
        parent.setObjectType("cmis:folder");
        Document document = new Document();
        document.setId("D");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        Folder folder = new Folder();
        folder.setId("S");
        folder.setType("cmis:folder");
        folder.setObjectType("cmis:folder");
        when(contentService.getContent(REPO, "D")).thenReturn(document);
        when(contentService.getContent(REPO, "S")).thenReturn(folder);
        when(contentService.getParent(REPO, "D")).thenReturn(parent);
        when(contentService.getParent(REPO, "S")).thenReturn(parent);
    }

    private List<ObjectParentData> parentsOf(String id) {
        return navigation.getObjectParents(callContext, REPO, id, "*", false, IncludeRelationships.NONE, null, true, null);
    }

    private ObjectData parentOf(String folderId) {
        return navigation.getFolderParent(callContext, REPO, folderId, "*", null);
    }

    /** How many lock sets were taken, and how many were released. */
    private long lockSets(String method) {
        return mockingDetails(locks).getInvocations().stream().filter(call -> call.getMethod().getName().equals(method)).count();
    }

    /** A reading that was refused holds no lock: every set that was taken was released. */
    private void assertTheLocksWereReleased(String reading) {
        assertTrue(lockSets("bulkLock") > 0, reading + " took no lock, so this cannot tell a lock released from none taken");
        assertEquals(lockSets("bulkLock"), lockSets("bulkUnlock"), reading + " was refused and left a lock set held");
    }

    @Test
    @DisplayName("getObjectParents: a caller who may not read the parent is refused, and the parent is not compiled for an answer")
    void theParentsOfAnObjectAreNotAnsweredToACallerWhoMayNotReadThem() {
        for (String id : List.of("D", "S")) {
            asked.clear();
            compiled.clear();
            refused.clear();
            refused.add(READ + " of P");
            CmisPermissionDeniedException denied = assertThrows(CmisPermissionDeniedException.class, () -> parentsOf(id),
                    "the parents of " + id + " were answered to a caller who may not read the parent");
            assertEquals(ExceptionServiceImpl.PERMISSION_DENIED_MESSAGE, denied.getMessage(), "the refusal names something");
            assertFalse(compiled.contains("P"), "the parent was compiled for the caller who may not read it: " + compiled);
            assertTrue(asked.contains(READ + " of P"), "the caller was not asked whether the parent may be read: " + asked);
            assertTheLocksWereReleased("the parents of " + id);
        }
    }

    @Test
    @DisplayName("getFolderParent: a caller who may not read the parent is refused, and the parent is not compiled for an answer")
    void theParentOfAFolderIsNotAnsweredToACallerWhoMayNotReadIt() {
        refused.add(READ + " of P");
        CmisPermissionDeniedException denied = assertThrows(CmisPermissionDeniedException.class, () -> parentOf("S"),
                "the parent of S was answered to a caller who may not read it");
        assertEquals(ExceptionServiceImpl.PERMISSION_DENIED_MESSAGE, denied.getMessage(), "the refusal names something");
        assertFalse(compiled.contains("P"), "the parent was compiled for the caller who may not read it: " + compiled);
        assertTrue(asked.contains(READ + " of P"), "the caller was not asked whether the parent may be read: " + asked);
        assertTheLocksWereReleased("the parent of S");
    }

    @Test
    @DisplayName("a caller who may read the parent is answered it, as before")
    void aCallerWhoMayReadTheParentIsAnsweredAsBefore() {
        for (String id : List.of("D", "S")) {
            List<ObjectParentData> parents = assertDoesNotThrow(() -> parentsOf(id), "the parents of " + id);
            assertEquals(1, parents.size(), "the parents of " + id);
            assertEquals("P", parents.get(0).getObject().getId(), "the parent of " + id);
        }
        ObjectData parent = assertDoesNotThrow(() -> parentOf("S"), "the parent of S");
        assertEquals("P", parent.getId(), "the parent of S");
    }

    @Test
    @DisplayName("what was asked of the object itself is asked as before, and refuses as before")
    void whatWasAskedOfTheObjectIsAskedAsBefore() {
        refused.add(PermissionMapping.CAN_GET_PARENTS_FOLDER + " of D");
        assertThrows(CmisPermissionDeniedException.class, () -> parentsOf("D"), "the parents of an object the caller may not ask about");
        assertEquals(List.of(PermissionMapping.CAN_GET_PARENTS_FOLDER + " of D"), asked,
                "the parent was asked about although the object's own permission refused");
        asked.clear();
        refused.clear();
        refused.add(PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT + " of S");
        assertThrows(CmisPermissionDeniedException.class, () -> parentOf("S"), "the parent of a folder the caller may not ask about");
        assertEquals(List.of(PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT + " of S"), asked,
                "the parent was asked about although the folder's own permission refused");
        assertEquals(List.of(), compiled, "something was compiled for a caller who was refused");
    }

    @Test
    @DisplayName("an object with no parent is answered no parents, and nothing is asked of a parent")
    void anObjectWithNoParentIsAnsweredNone() {
        when(contentService.getParent(REPO, "D")).thenReturn(null);
        List<ObjectParentData> parents = assertDoesNotThrow(() -> parentsOf("D"), "the parents of an object that has none");
        assertEquals(List.of(), parents, "an object with no parent");
        assertEquals(List.of(PermissionMapping.CAN_GET_PARENTS_FOLDER + " of D"), asked, "what was asked");
    }
}
