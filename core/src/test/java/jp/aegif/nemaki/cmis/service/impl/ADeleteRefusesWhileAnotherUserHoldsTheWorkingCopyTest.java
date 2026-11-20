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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * A document whose version series another user has checked out is not deleted (R325, 3.4.2).
 *
 * <p>Deleting every version of a document that another user had checked out deleted the versions and the version
 * series, and left that user's private working copy without its series: its holder's reads and checked-out listing then
 * answered 500 (measured on nb33). A delete of the only version removes the series too, whatever {@code allVersions}
 * says.
 *
 * <p>What is locked, on the product's {@code ObjectServiceInternalImpl.deleteObjectInternal}: when the series is checked
 * out by a user other than the caller — or by no one named — the delete is refused as a versioning conflict (409 — the
 * Browser binding answers a constraint 400) before anything
 * is deleted, the parentChild child included, with {@code allVersions} true, false or not given, for an administrator
 * too, and when the other user's working copy is named itself (the versions deleted are listed without it); it goes on
 * when the caller holds the working copy — naming a version or the working copy — when the series is not checked out,
 * and when the document has no series; a series that cannot be read deletes nothing; a parentChild child another user
 * has checked out is skipped, as a refused child is, and stays while its parent is deleted.
 *
 * <p>Whether the caller may delete is a stand-in that allows every delete; the constraint stand-in throws as the
 * product's does, and records what it was told — no refusal here may come from it.
 */
class ADeleteRefusesWhileAnotherUserHoldsTheWorkingCopyTest {

    private static final String REPO = "bedroom";
    private static final String SERIES = "series-of-W";
    private static final String REFUSAL = "checked out by another user";

    private final ContentService contentService = mock(ContentService.class);
    private final ExceptionService exceptionService = mock(ExceptionService.class);
    private final ObjectServiceInternalImpl deletes = new ObjectServiceInternalImpl();
    /** What the constraint stand-in was told, in order. */
    private final List<String> constraints = new ArrayList<>();
    private final Document latest = version("W-1.0", true, false);
    private final Document workingCopy = version("W-pwc", false, true);

    ADeleteRefusesWhileAnotherUserHoldsTheWorkingCopyTest() {
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(eq(REPO), anyString())).thenAnswer(call -> new ReentrantLock());
        doAnswer(call -> {
            String objectId = call.getArgument(0);
            String message = call.getArgument(1);
            constraints.add(objectId + ": " + message);
            throw new CmisConstraintException(message);
        }).when(exceptionService).constraint(anyString(), anyString());
        // the named document has one parentChild child, which the cascade deletes before the document itself
        Document child = new Document();
        child.setId("CHILD");
        child.setName("CHILD");
        child.setType("cmis:document");
        child.setObjectType("cmis:document");
        child.setLatestVersion(true);
        when(contentService.getParentChildChildIds(eq(REPO), anyString())).thenReturn(List.of());
        when(contentService.getParentChildChildIds(REPO, latest.getId())).thenReturn(List.of("CHILD"));
        when(contentService.getContent(REPO, "CHILD")).thenReturn(child);
        when(contentService.getAllVersions(any(CallContext.class), eq(REPO), eq(SERIES))).thenReturn(List.of(latest));
        when(contentService.getAllVersions(any(CallContext.class), eq(REPO), eq((String) null))).thenReturn(List.of(child));
        UserItem administrator = mock(UserItem.class);
        when(administrator.isAdmin()).thenReturn(Boolean.TRUE);
        when(contentService.getUserItemById(REPO, "admin")).thenReturn(administrator);
        deletes.setContentService(contentService);
        deletes.setExceptionService(exceptionService);
        deletes.setThreadLockService(locks);
        deletes.setNemakiCachePool(mock(NemakiCachePool.class, RETURNS_DEEP_STUBS));
    }

    private static Document version(String id, boolean latest, boolean workingCopy) {
        Document document = new Document();
        document.setId(id);
        document.setName("W");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setVersionSeriesId(SERIES);
        document.setLatestVersion(latest);
        document.setPrivateWorkingCopy(workingCopy);
        return document;
    }

    private void checkedOutBy(String holder) {
        VersionSeries series = new VersionSeries();
        series.setId(SERIES);
        series.setVersionSeriesCheckedOut(Boolean.TRUE);
        series.setVersionSeriesCheckedOutBy(holder);
        series.setVersionSeriesCheckedOutId(workingCopy.getId());
        when(contentService.getVersionSeries(eq(REPO), any(Document.class))).thenReturn(series);
        when(contentService.getVersionSeries(REPO, SERIES)).thenReturn(series);
    }

    private static CallContext callerNamed(String name) {
        CallContext caller = mock(CallContext.class);
        when(caller.getUsername()).thenReturn(name);
        return caller;
    }

    private List<String> deletedIds() {
        List<String> ids = new ArrayList<>();
        org.mockito.Mockito.mockingDetails(contentService).getInvocations().forEach(call -> {
            String name = call.getMethod().getName();
            if (name.equals("deleteDocument") || name.equals("delete") || name.equals("deleteUserItem")) {
                ids.add(String.valueOf(call.getArguments().length > 2 ? call.getArguments()[2] : call.getArguments()[0]));
            }
        });
        return ids;
    }

    private void assertRefusedAndNothingDeleted(String caller, Boolean allVersions, String why) {
        CmisVersioningException refusal = assertThrows(CmisVersioningException.class,
                () -> deletes.deleteObjectInternal(callerNamed(caller), REPO, latest, allVersions, Boolean.FALSE), why);
        assertRefusal(refusal, why);
        assertEquals(List.of(), deletedIds(), "the objects deleted when " + why);
    }

    private void assertRefusal(CmisVersioningException refusal, String why) {
        assertTrue(refusal.getMessage().contains(REFUSAL), "the refusal of " + why + " — another refusal answered: "
                + refusal.getMessage());
        assertEquals(409, refusal.getCode().intValue(), "the status the refusal of " + why + " carries");
        assertEquals(List.of(), constraints, "the constraints " + why + " met");
    }

    @Test
    @DisplayName("deleting every version of a document another user has checked out is refused, and nothing is deleted — not even the parentChild child")
    void anotherUsersWorkingCopyRefusesTheDeleteOfEveryVersion() {
        checkedOutBy("holder");
        assertRefusedAndNothingDeleted("someone", Boolean.TRUE, "deleting every version of W, which another user has checked out");
    }

    @Test
    @DisplayName("with allVersions false or not given, it is refused too — a delete of the only version removes the series")
    void anotherUsersWorkingCopyRefusesWhateverAllVersionsSays() {
        checkedOutBy("holder");
        assertRefusedAndNothingDeleted("someone", Boolean.FALSE, "deleting the only version of W, which another user has checked out");
        assertRefusedAndNothingDeleted("someone", null, "deleting W without saying allVersions, which another user has checked out");
    }

    @Test
    @DisplayName("an administrator is refused too")
    void anAdministratorIsRefusedToo() {
        checkedOutBy("holder");
        assertRefusedAndNothingDeleted("admin", Boolean.TRUE, "an administrator deleting W, which another user has checked out");
    }

    @Test
    @DisplayName("a series checked out with no holder named is refused")
    void aCheckOutWithNoHolderIsRefused() {
        checkedOutBy(null);
        assertRefusedAndNothingDeleted("someone", Boolean.TRUE, "deleting W, checked out by no one named");
    }

    @Test
    @DisplayName("the caller who holds the working copy deletes as before")
    void theHolderDeletesAsBefore() {
        checkedOutBy("holder");
        assertDoesNotThrow(() -> deletes.deleteObjectInternal(callerNamed("holder"), REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "the holder deleting every version of W");
        verify(contentService).deleteDocument(any(CallContext.class), eq(REPO), eq(latest.getId()), eq(Boolean.TRUE), eq(Boolean.FALSE));
        assertEquals(List.of(), constraints, "the constraints the holder's delete met");
    }

    @Test
    @DisplayName("a series that is not checked out, or a document without one, deletes as before")
    void aDocumentNotCheckedOutDeletesAsBefore() {
        VersionSeries series = new VersionSeries();
        series.setId(SERIES);
        series.setVersionSeriesCheckedOut(Boolean.FALSE);
        when(contentService.getVersionSeries(eq(REPO), any(Document.class))).thenReturn(series);
        assertDoesNotThrow(() -> deletes.deleteObjectInternal(callerNamed("someone"), REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "deleting every version of W, which is not checked out");
        when(contentService.getVersionSeries(eq(REPO), any(Document.class))).thenReturn(null);
        assertDoesNotThrow(() -> deletes.deleteObjectInternal(callerNamed("someone"), REPO, latest, Boolean.FALSE, Boolean.FALSE),
                "deleting W, whose series is not there");
        assertEquals(List.of(), constraints, "the constraints these deletes met");
        assertEquals(List.of(latest.getId(), latest.getId()), deletedIds().stream().filter(id -> !id.equals("CHILD")).toList(),
                "the documents deleted");
    }

    @Test
    @DisplayName("another user's working copy named itself is refused too — the versions deleted are listed without it")
    void anotherUsersWorkingCopyNamedItselfIsRefused() {
        checkedOutBy("holder");
        CmisVersioningException refusal = assertThrows(CmisVersioningException.class,
                () -> deletes.deleteObjectInternal(callerNamed("admin"), REPO, workingCopy, Boolean.TRUE, Boolean.FALSE),
                "an administrator deleting the working copy another user holds of W");
        assertRefusal(refusal, "an administrator deleting the working copy another user holds of W");
        assertEquals(List.of(), deletedIds(), "the objects deleted when another user's working copy is named");
    }

    @Test
    @DisplayName("the holder naming its own working copy deletes as before")
    void theHolderNamingItsWorkingCopyDeletesAsBefore() {
        checkedOutBy("holder");
        assertDoesNotThrow(() -> deletes.deleteObjectInternal(callerNamed("holder"), REPO, workingCopy, Boolean.TRUE, Boolean.FALSE),
                "the holder deleting its working copy of W");
        assertEquals(List.of(), constraints, "the constraints deleting the working copy met");
        verify(contentService).deleteDocument(any(CallContext.class), eq(REPO), eq(workingCopy.getId()), eq(Boolean.TRUE), eq(Boolean.FALSE));
    }

    @Test
    @DisplayName("a parentChild child another user has checked out is skipped and stays; its parent is deleted")
    void aChildAnotherUserHoldsIsSkipped() {
        Document child = (Document) contentService.getContent(REPO, "CHILD");
        child.setVersionSeriesId("series-of-CHILD");
        VersionSeries heldChild = new VersionSeries();
        heldChild.setId("series-of-CHILD");
        heldChild.setVersionSeriesCheckedOut(Boolean.TRUE);
        heldChild.setVersionSeriesCheckedOutBy("holder");
        org.mockito.Mockito.doAnswer(call -> "series-of-CHILD".equals(((Document) call.getArgument(1)).getVersionSeriesId())
                ? heldChild : null).when(contentService).getVersionSeries(eq(REPO), any(Document.class));
        when(contentService.getAllVersions(any(CallContext.class), eq(REPO), eq("series-of-CHILD"))).thenReturn(List.of(child));
        assertDoesNotThrow(() -> deletes.deleteObjectInternal(callerNamed("someone"), REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "deleting W, whose parentChild child another user has checked out");
        assertEquals(List.of(latest.getId()), deletedIds(), "the objects deleted — W, and not its child");
    }

    @Test
    @DisplayName("a version series that cannot be read deletes nothing")
    void aSeriesThatCannotBeReadDeletesNothing() {
        when(contentService.getVersionSeries(eq(REPO), any(Document.class)))
                .thenThrow(new IllegalStateException("the version lookup failed"));
        assertThrows(IllegalStateException.class,
                () -> deletes.deleteObjectInternal(callerNamed("someone"), REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "deleting W while its version series cannot be read");
        assertEquals(List.of(), deletedIds(), "the objects deleted when the version series cannot be read");
    }
}
