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
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * The holder's delete of the only checked-in version alone is refused while its working copy is there (R329, 3.4.2).
 *
 * <p>The holder of a working copy deleted, with {@code allVersions} false, the only checked-in version of its series:
 * deleteDocument then deleted the version series too, and the holder's working copy, which it does not delete, stayed
 * without it — its reads, and every non-administrator's checked-out listing, answered 500 until an administrator
 * cancelled the check-out (measured on nb33).
 *
 * <p>What is locked, on the product's {@code ObjectServiceInternalImpl.deleteObjectInternal}: when the caller holds the
 * series, {@code allVersions} is false, no checked-in version is left besides the named one and the working copy the
 * series names is there — or it names none — the delete is refused as a versioning conflict (409) before anything is
 * deleted, the parentChild child included; it goes on as before when a checked-in version is left, when every version
 * is deleted ({@code allVersions} true or not given), when the series is not checked out, and when the working copy it
 * names is not there; the holder's own working copy named with {@code allVersions} false meets the refusal of a version
 * that is not the latest, as before; another user's check-out is refused as R325 refuses it, a checked-in version left
 * or not; a listing that cannot be read deletes nothing; a parentChild child the caller holds this way is skipped and
 * stays while its parent is deleted.
 *
 * <p>Whether the caller may delete is a stand-in that allows every delete; what a delete of one version leaves is a
 * stand-in ({@code ContentService.committedVersionsLeftAfterDeleting} — its own lock is
 * {@code WhatADeleteOfOneVersionLeavesIsDecidedOnceTest}); the constraint stand-in throws as the product's does and
 * records what it was told.
 */
class ADeleteRefusesToLeaveTheHoldersOwnWorkingCopyWithoutItsSeriesTest {

    private static final String REPO = "bedroom";
    private static final String SERIES = "series-of-W";
    private static final String REFUSAL = "its only checked-in version";
    private static final String ANOTHER_USERS = "checked out by another user";

    private final ContentService contentService = mock(ContentService.class);
    private final ExceptionService exceptionService = mock(ExceptionService.class);
    private final ObjectServiceInternalImpl deletes = new ObjectServiceInternalImpl();
    private final List<String> constraints = new ArrayList<>();
    private final Document older = version("W-1.0", false, false);
    private final Document latest = version("W-2.0", true, false);
    private final Document workingCopy = version("W-pwc", false, true);

    ADeleteRefusesToLeaveTheHoldersOwnWorkingCopyWithoutItsSeriesTest() {
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(eq(REPO), anyString())).thenAnswer(call -> new ReentrantLock());
        doAnswer(call -> {
            String objectId = call.getArgument(0);
            String message = call.getArgument(1);
            constraints.add(objectId + ": " + message);
            throw new CmisConstraintException(message);
        }).when(exceptionService).constraint(anyString(), anyString());
        Document child = new Document();
        child.setId("CHILD");
        child.setName("CHILD");
        child.setType("cmis:document");
        child.setObjectType("cmis:document");
        child.setLatestVersion(true);
        when(contentService.getParentChildChildIds(eq(REPO), anyString())).thenReturn(List.of());
        when(contentService.getParentChildChildIds(REPO, latest.getId())).thenReturn(List.of("CHILD"));
        when(contentService.getContent(REPO, "CHILD")).thenReturn(child);
        when(contentService.getContent(REPO, workingCopy.getId())).thenReturn(workingCopy);
        when(contentService.getAllVersions(any(CallContext.class), eq(REPO), eq(SERIES))).thenReturn(List.of(latest, workingCopy));
        when(contentService.getAllVersions(any(CallContext.class), eq(REPO), eq((String) null))).thenReturn(List.of(child));
        when(contentService.committedVersionsLeftAfterDeleting(eq(REPO), any(Document.class))).thenReturn(List.of(older));
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

    private void checkedOutBy(String holder, String workingCopyId) {
        VersionSeries series = new VersionSeries();
        series.setId(SERIES);
        series.setVersionSeriesCheckedOut(Boolean.TRUE);
        series.setVersionSeriesCheckedOutBy(holder);
        series.setVersionSeriesCheckedOutId(workingCopyId);
        when(contentService.getVersionSeries(eq(REPO), any(Document.class))).thenReturn(series);
    }

    /** The named version is the only checked-in one: deleting it alone leaves none. */
    private void theOnlyCheckedInVersion() {
        when(contentService.committedVersionsLeftAfterDeleting(REPO, latest)).thenReturn(List.of());
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

    private void assertRefused(String caller, Document named, Boolean allVersions, String expected, String why) {
        CmisVersioningException refusal = assertThrows(CmisVersioningException.class,
                () -> deletes.deleteObjectInternal(callerNamed(caller), REPO, named, allVersions, Boolean.FALSE), why);
        assertTrue(refusal.getMessage().contains(expected), "the refusal of " + why + " — another refusal answered: "
                + refusal.getMessage());
        assertEquals(409, refusal.getCode().intValue(), "the status the refusal of " + why + " carries");
        assertEquals(List.of(), constraints, "the constraints " + why + " met");
        assertEquals(List.of(), deletedIds(), "the objects deleted when " + why);
    }

    private void assertDeletedAsBefore(String caller, Document named, Boolean allVersions, String why) {
        assertDoesNotThrow(() -> deletes.deleteObjectInternal(callerNamed(caller), REPO, named, allVersions, Boolean.FALSE), why);
        verify(contentService).deleteDocument(any(CallContext.class), eq(REPO), eq(named.getId()), eq(allVersions), eq(Boolean.FALSE));
        assertEquals(List.of(), constraints, "the constraints " + why + " met");
    }

    @Test
    @DisplayName("the holder deleting the only checked-in version alone is refused, and nothing is deleted — not even the parentChild child")
    void theOnlyCheckedInVersionDeletedAloneIsRefused() {
        checkedOutBy("holder", workingCopy.getId());
        theOnlyCheckedInVersion();
        assertRefused("holder", latest, Boolean.FALSE, REFUSAL, "the holder deleting W's only checked-in version alone");
    }

    @Test
    @DisplayName("a series that names no working copy is taken to have one, and refused")
    void aSeriesNamingNoWorkingCopyIsTakenToHaveOne() {
        checkedOutBy("holder", null);
        theOnlyCheckedInVersion();
        assertRefused("holder", latest, Boolean.FALSE, REFUSAL, "the holder deleting W's only checked-in version, its series naming no working copy");
    }

    @Test
    @DisplayName("with a checked-in version left, the holder's delete of the latest alone goes on as before")
    void aCheckedInVersionLeftDeletesAsBefore() {
        checkedOutBy("holder", workingCopy.getId());
        assertDeletedAsBefore("holder", latest, Boolean.FALSE, "the holder deleting W's latest version alone, an older one left");
        assertEquals(List.of("CHILD", latest.getId()), deletedIds(), "the objects deleted — the child and W's latest version");
    }

    @Test
    @DisplayName("the holder's delete of every version goes on as before — its working copy goes with them")
    void everyVersionDeletesAsBefore() {
        checkedOutBy("holder", workingCopy.getId());
        theOnlyCheckedInVersion();
        assertDeletedAsBefore("holder", latest, Boolean.TRUE, "the holder deleting every version of W");
    }

    @Test
    @DisplayName("allVersions not given deletes every version, and goes on as before")
    void allVersionsNotGivenDeletesAsBefore() {
        checkedOutBy("holder", workingCopy.getId());
        theOnlyCheckedInVersion();
        assertDeletedAsBefore("holder", latest, null, "the holder deleting W without saying allVersions");
    }

    @Test
    @DisplayName("a series that is not checked out deletes the only version alone as before")
    void aSeriesNotCheckedOutDeletesAsBefore() {
        VersionSeries series = new VersionSeries();
        series.setId(SERIES);
        series.setVersionSeriesCheckedOut(Boolean.FALSE);
        when(contentService.getVersionSeries(eq(REPO), any(Document.class))).thenReturn(series);
        theOnlyCheckedInVersion();
        assertDeletedAsBefore("someone", latest, Boolean.FALSE, "deleting W's only version alone, not checked out");
    }

    @Test
    @DisplayName("a working copy the series names that is not there is not kept: the delete goes on as before")
    void aWorkingCopyThatIsNotThereIsNotKept() {
        checkedOutBy("holder", "W-pwc-gone");
        theOnlyCheckedInVersion();
        assertDeletedAsBefore("holder", latest, Boolean.FALSE, "the holder deleting W's only checked-in version, its working copy gone");
    }

    @Test
    @DisplayName("the holder naming its own working copy with allVersions false meets the refusal of a version that is not the latest, as before")
    void theHoldersOwnWorkingCopyNamedIsAsBefore() {
        checkedOutBy("holder", workingCopy.getId());
        when(contentService.committedVersionsLeftAfterDeleting(REPO, workingCopy)).thenReturn(List.of(latest));
        assertThrows(CmisConstraintException.class,
                () -> deletes.deleteObjectInternal(callerNamed("holder"), REPO, workingCopy, Boolean.FALSE, Boolean.FALSE),
                "the holder deleting its working copy alone");
        assertEquals(1, constraints.size(), "the constraints met: " + constraints);
        assertTrue(constraints.get(0).contains("non-latest"), "the constraint met: " + constraints);
    }

    @Test
    @DisplayName("another user's check-out is refused as before, a checked-in version left or not")
    void anotherUsersCheckOutIsRefusedAsBefore() {
        checkedOutBy("holder", workingCopy.getId());
        assertRefused("someone", latest, Boolean.FALSE, ANOTHER_USERS, "another user deleting W's latest version alone, an older one left");
        theOnlyCheckedInVersion();
        assertRefused("someone", latest, Boolean.FALSE, ANOTHER_USERS, "another user deleting W's only checked-in version alone");
    }

    @Test
    @DisplayName("what a delete of one version leaves that cannot be read deletes nothing")
    void aListingThatCannotBeReadDeletesNothing() {
        checkedOutBy("holder", workingCopy.getId());
        when(contentService.committedVersionsLeftAfterDeleting(REPO, latest)).thenThrow(new IllegalStateException("the versions could not be read"));
        assertThrows(IllegalStateException.class,
                () -> deletes.deleteObjectInternal(callerNamed("holder"), REPO, latest, Boolean.FALSE, Boolean.FALSE),
                "the holder deleting W's latest version alone while its versions cannot be read");
        assertEquals(List.of(), deletedIds(), "the objects deleted when the versions cannot be read");
    }

    @Test
    @DisplayName("a parentChild child the caller holds this way is skipped and stays; its parent is deleted")
    void aChildTheCallerHoldsIsSkipped() {
        Document child = (Document) contentService.getContent(REPO, "CHILD");
        child.setVersionSeriesId("series-of-CHILD");
        VersionSeries heldChild = new VersionSeries();
        heldChild.setId("series-of-CHILD");
        heldChild.setVersionSeriesCheckedOut(Boolean.TRUE);
        heldChild.setVersionSeriesCheckedOutBy("holder");
        doAnswer(call -> "series-of-CHILD".equals(((Document) call.getArgument(1)).getVersionSeriesId())
                ? heldChild : null).when(contentService).getVersionSeries(eq(REPO), any(Document.class));
        when(contentService.committedVersionsLeftAfterDeleting(REPO, child)).thenReturn(List.of());
        assertDoesNotThrow(() -> deletes.deleteObjectInternal(callerNamed("holder"), REPO, latest, Boolean.FALSE, Boolean.FALSE),
                "deleting W's latest version, whose parentChild child the caller holds as its only version");
        assertEquals(List.of(latest.getId()), deletedIds(), "the objects deleted — W, and not its child");
    }
}
