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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

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
 * The list of checked-out documents answers only the working copies the caller may read (R314, 3.4.2).
 *
 * <p>{@code getCheckedOutDocs} read every working copy in the repository, or in the folder it was given, and compiled
 * them all into the answer. Nothing asked what the caller may read: a caller who is no administrator was answered every
 * working copy with all its properties — 90 of them on nb33, where {@code getObject} answers that caller one — and the
 * working copies in a folder the caller is refused.
 *
 * <p>What is locked: the working copies that are compiled for the answer are the ones the permission service keeps for
 * the caller, and a caller who may not read a folder's children is refused that folder's list before a working copy is
 * read. With no folder named, no folder is asked about. A blank folder id names no folder: the store is asked for the
 * working copies of no folder, not for those of a folder whose id is blank — it was, and answered none where there are
 * some. A folder id that names no folder is not found, whatever the repository says of multifiling.
 *
 * <p>The product's {@code NavigationServiceImpl} and {@code ExceptionServiceImpl} are driven; the working copies, the
 * permission decisions, the compiling and the locks are stand-ins. The stand-in store reads the folder id as the store
 * does: the working copies are filed in the folder F, and a folder id that is not null is matched as it is written.
 * Which working copy the caller may read is the
 * permission service's to say (a working copy is its owner's); this lock does not repeat that rule, it locks that the
 * list is put through it.
 */
class TheCheckedOutListAnswersOnlyWhatTheCallerMayReadTest {

    private static final String REPO = "bedroom";
    private static final String CHILDREN = PermissionMapping.CAN_GET_CHILDREN_FOLDER;

    private final ContentService contentService = mock(ContentService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final CompileService compileService = mock(CompileService.class);
    private final ThreadLockService locks = mock(ThreadLockService.class);
    private final CallContext callContext = mock(CallContext.class);
    private final ExceptionServiceImpl exceptionService = spy(new ExceptionServiceImpl());
    private final NavigationServiceImpl navigation = new NavigationServiceImpl();
    private final ObjectListImpl compiled = new ObjectListImpl();
    /** What the caller was asked for, as 「permission of id」, in order. */
    private final List<String> asked = new ArrayList<>();
    /** What the caller is refused, as 「permission of id」. */
    private final Set<String> refused = new HashSet<>();
    /** The ids of the working copies the caller may read. */
    private final Set<String> readable = new HashSet<>();

    TheCheckedOutListAnswersOnlyWhatTheCallerMayReadTest() {
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
        when(permissions.getFiltered(eq(callContext), eq(REPO), any())).thenAnswer(call -> {
            List<Document> kept = new ArrayList<>();
            for (Document copy : call.<List<Document>>getArgument(2)) {
                if (readable.contains(copy.getId())) {
                    kept.add(copy);
                }
            }
            return kept;
        });
        exceptionService.setContentService(contentService);
        exceptionService.setPermissionService(permissions);
        // Reads the repository's capabilities; whether the folder exists is not what this lock is about.
        doNothing().when(exceptionService).objectNotFoundParentFolder(any(), any(), any());
        doNothing().when(exceptionService).invalidArgumentOrderBy(any(), any());
        when(locks.readLocks(any(), any())).thenAnswer(call -> List.<Lock>of());
        when(compileService.compileObjectDataList(any(), eq(REPO), any(), any(), any(), any(), any(), anyBoolean(), any(), any(),
                anyBoolean(), any())).thenReturn(compiled);
        navigation.setContentService(contentService);
        navigation.setExceptionService(exceptionService);
        navigation.setCompileService(compileService);
        navigation.setThreadLockService(locks);
        navigation.setPermissionService(permissions);

        Folder folder = new Folder();
        folder.setId("F");
        folder.setType("cmis:folder");
        folder.setObjectType("cmis:folder");
        when(contentService.getFolder(REPO, "F")).thenReturn(folder);
        when(contentService.getCheckedOutDocs(eq(REPO), any(), any(), any())).thenAnswer(call -> {
            String filedIn = call.getArgument(1);
            // null is every folder; any other id is matched as it is written, a blank one too
            return filedIn == null || filedIn.equals("F")
                    ? List.of(workingCopy("MINE"), workingCopy("ANOTHERS"), workingCopy("ANOTHERS-2"))
                    : List.<Document>of();
        });
    }

    /** Folder ids that are blank: nothing, a space, a tab, an ideographic space. */
    private static final String[] BLANK = {"", " ", "\t", "\u3000"};

    private static Document workingCopy(String id) {
        Document copy = new Document();
        copy.setId(id);
        copy.setType("cmis:document");
        copy.setObjectType("cmis:document");
        copy.setPrivateWorkingCopy(Boolean.TRUE);
        return copy;
    }

    private ObjectList checkedOut(String folderId) {
        return navigation.getCheckedOutDocs(callContext, REPO, folderId, "*", null, Boolean.FALSE, IncludeRelationships.NONE,
                null, BigInteger.TEN, BigInteger.ZERO, null);
    }

    /** The ids of the working copies that were compiled for the answer; the call must have been made once. */
    private List<String> compiledForTheAnswer() {
        List<Invocation> calls = new ArrayList<>();
        for (Invocation call : mockingDetails(compileService).getInvocations()) {
            if (call.getMethod().getName().equals("compileObjectDataList")) {
                calls.add(call);
            }
        }
        assertEquals(1, calls.size(), "the answer was compiled " + calls.size() + " times");
        List<String> ids = new ArrayList<>();
        for (Content copy : calls.get(0).<List<Content>>getArgument(2)) {
            ids.add(copy.getId());
        }
        assertSame(callContext, calls.get(0).getArgument(0), "the answer was not compiled for the caller");
        return ids;
    }

    /** The folder ids the store was asked for the working copies of, in order. */
    private List<String> foldersTheStoreWasAskedFor() {
        List<String> folders = new ArrayList<>();
        for (Invocation call : mockingDetails(contentService).getInvocations()) {
            if (call.getMethod().getName().equals("getCheckedOutDocs")) {
                folders.add(call.getArgument(1));
            }
        }
        return folders;
    }

    private long calls(Object service, String method) {
        return mockingDetails(service).getInvocations().stream().filter(call -> call.getMethod().getName().equals(method)).count();
    }

    @Test
    @DisplayName("only the working copies the caller may read are compiled for the answer — with a folder named or none")
    void onlyTheWorkingCopiesTheCallerMayReadAreAnswered() {
        for (String folderId : new String[] {null, "F"}) {
            TheCheckedOutListAnswersOnlyWhatTheCallerMayReadTest fresh = new TheCheckedOutListAnswersOnlyWhatTheCallerMayReadTest();
            fresh.readable.add("MINE");
            ObjectList answered = assertDoesNotThrow(() -> fresh.checkedOut(folderId), "the list for the folder " + folderId);
            assertSame(fresh.compiled, answered, "the caller was not answered the compiled list");
            assertEquals(List.of("MINE"), fresh.compiledForTheAnswer(),
                    "working copies the caller may not read were compiled for the answer (folder " + folderId + ")");
        }
    }

    @Test
    @DisplayName("a blank folder id names no folder: the store is asked for the working copies of no folder, and no folder is asked about")
    void aBlankFolderIdNamesNoFolder() {
        for (String blank : BLANK) {
            TheCheckedOutListAnswersOnlyWhatTheCallerMayReadTest fresh = new TheCheckedOutListAnswersOnlyWhatTheCallerMayReadTest();
            fresh.readable.add("MINE");
            String shown = "the blank folder id of " + blank.length() + " character(s), U+" + (blank.isEmpty() ? "none"
                    : Integer.toHexString(blank.charAt(0)));
            assertDoesNotThrow(() -> fresh.checkedOut(blank), "the list for " + shown);
            List<String> folders = fresh.foldersTheStoreWasAskedFor();
            assertEquals(1, folders.size(), "the store was asked " + folders.size() + " times for " + shown);
            assertNull(folders.get(0), "the store was asked for the working copies of a folder whose id is blank (" + shown + ")");
            assertEquals(List.of("MINE"), fresh.compiledForTheAnswer(),
                    "the working copies the caller may read were not answered for " + shown);
            assertEquals(List.of(), fresh.asked, "a folder was asked about for " + shown);
            assertEquals(0, fresh.calls(fresh.contentService, "getFolder"), "a folder was read for " + shown);
        }
    }

    @Test
    @DisplayName("a folder id that names no folder is not found, though the multifiling check refuses nothing — nothing is asked or read")
    void aFolderThatIsNotThereIsNotFound() {
        readable.add("MINE");
        CmisObjectNotFoundException notFound = assertThrows(CmisObjectNotFoundException.class, () -> checkedOut("NOT-A-FOLDER"),
                "the list of a folder id that names no folder");
        assertEquals(BigInteger.valueOf(404), notFound.getCode(), "the refusal is not a 404");
        assertEquals(List.of(), asked, "the caller was asked about a folder id that names no folder");
        assertEquals(List.of(), foldersTheStoreWasAskedFor(), "the working copies were read for a folder id that names no folder");
        assertEquals(0, calls(compileService, "compileObjectDataList"), "an answer was compiled for a folder id that names no folder");
    }

    @Test
    @DisplayName("a caller who may read none of the working copies is answered none of them")
    void aCallerWhoMayReadNoneIsAnsweredNone() {
        assertDoesNotThrow(() -> checkedOut(null), "the list with no folder named");
        assertEquals(List.of(), compiledForTheAnswer(), "working copies the caller may not read were compiled for the answer");
    }

    @Test
    @DisplayName("a caller who may read every working copy is answered them all, as before")
    void aCallerWhoMayReadThemAllIsAnsweredAsBefore() {
        readable.addAll(List.of("MINE", "ANOTHERS", "ANOTHERS-2"));
        assertDoesNotThrow(() -> checkedOut(null), "the list with no folder named");
        assertEquals(List.of("MINE", "ANOTHERS", "ANOTHERS-2"), compiledForTheAnswer(), "the list was narrowed for a caller who may read it all");
    }

    @Test
    @DisplayName("the list of a folder is refused to a caller who may not read that folder's children, before a working copy is read")
    void theListOfAFolderTheCallerMayNotReadIsRefused() {
        readable.add("MINE");
        refused.add(CHILDREN + " of F");
        CmisPermissionDeniedException denied = assertThrows(CmisPermissionDeniedException.class, () -> checkedOut("F"),
                "the working copies in a folder were answered to a caller who may not read the folder's children");
        assertEquals(ExceptionServiceImpl.PERMISSION_DENIED_MESSAGE, denied.getMessage(), "the refusal names something");
        assertEquals(0, calls(contentService, "getCheckedOutDocs"), "the working copies were read for a caller who is refused the folder");
        assertEquals(0, calls(compileService, "compileObjectDataList"), "an answer was compiled for a caller who is refused the folder");
    }

    @Test
    @DisplayName("the folder is asked about only when one is named, and by the permission to read its children")
    void theFolderIsAskedAboutOnlyWhenOneIsNamed() {
        readable.add("MINE");
        assertDoesNotThrow(() -> checkedOut("F"), "the list of a folder the caller may read");
        assertEquals(List.of(CHILDREN + " of F"), asked, "what the caller was asked about the folder");
        asked.clear();
        assertDoesNotThrow(() -> checkedOut(null), "the list with no folder named");
        assertEquals(List.of(), asked, "a folder was asked about although none was named");
    }
}
