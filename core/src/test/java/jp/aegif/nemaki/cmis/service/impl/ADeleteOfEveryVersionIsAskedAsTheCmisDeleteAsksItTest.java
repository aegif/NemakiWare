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
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.cache.NemakiCachePool;

/**
 * A delete of every version is asked, for a caller that deletes the document itself, as the CMIS delete asks it (R326,
 * 3.4.2).
 *
 * <p>The ZIP import's replace deletes the document of the same name by {@code ContentService.deleteDocument}, every
 * version. It asks first by {@code ObjectServiceInternal.refuseADeleteOfEveryVersion}, which makes the checks the CMIS
 * delete makes before anything is deleted, by the same methods.
 *
 * <p>What is locked, on the product's {@code ObjectServiceInternalImpl.refuseADeleteOfEveryVersion}: {@code CAN_DELETE_OBJECT}
 * is asked of the document and of every version {@code getAllVersions} answers the caller, and one refusal is thrown;
 * another user's check-out, or one with no holder named, is refused as a versioning conflict (409); the holder's own, and
 * a document that is not checked out, are not; a listing that cannot be read is thrown; and nothing is deleted — it only
 * asks. Whether the caller may delete is a stand-in that refuses the objects it is told to.
 */
class ADeleteOfEveryVersionIsAskedAsTheCmisDeleteAsksItTest {

    private static final String REPO = "bedroom";
    private static final String SERIES = "series-of-X";

    private final ContentService contentService = mock(ContentService.class);
    private final ExceptionService exceptionService = mock(ExceptionService.class);
    private final ObjectServiceInternalImpl deletes = new ObjectServiceInternalImpl();
    private final Set<String> refused = new HashSet<>();
    private final List<String> asked = new ArrayList<>();
    /** Whom the permission questions and the version listing were asked about. */
    private final List<CallContext> askedAs = new ArrayList<>();
    private final Document older = version("X-1.0", false);
    private final Document latest = version("X-2.0", true);

    ADeleteOfEveryVersionIsAskedAsTheCmisDeleteAsksItTest() {
        doAnswer(call -> {
            Content content = call.getArgument(3);
            asked.add(call.getArgument(2) + " " + content.getId());
            askedAs.add(call.getArgument(0));
            if (refused.contains(content.getId())) {
                throw new CmisPermissionDeniedException("permission denied");
            }
            return null;
        }).when(exceptionService).permissionDenied(any(CallContext.class), eq(REPO), anyString(), any(Content.class));
        when(contentService.getAllVersions(any(CallContext.class), eq(REPO), eq(SERIES))).thenAnswer(call -> {
            askedAs.add(call.getArgument(0));
            return List.of(older, latest);
        });
        deletes.setContentService(contentService);
        deletes.setExceptionService(exceptionService);
        deletes.setNemakiCachePool(mock(NemakiCachePool.class, RETURNS_DEEP_STUBS));
    }

    private static Document version(String id, boolean latest) {
        Document document = new Document();
        document.setId(id);
        document.setName("X");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setVersionSeriesId(SERIES);
        document.setLatestVersion(latest);
        document.setPrivateWorkingCopy(false);
        return document;
    }

    private void checkedOutBy(String holder) {
        VersionSeries series = new VersionSeries();
        series.setId(SERIES);
        series.setVersionSeriesCheckedOut(Boolean.TRUE);
        series.setVersionSeriesCheckedOutBy(holder);
        series.setVersionSeriesCheckedOutId("X-pwc");
        when(contentService.getVersionSeries(eq(REPO), any(Document.class))).thenReturn(series);
        // the working copy the series names is there, and no checked-in version is left besides the named one: a delete
        // of that version alone would leave the holder's working copy without its series — a delete of every version would not
        Document workingCopy = version("X-pwc", false);
        workingCopy.setPrivateWorkingCopy(true);
        when(contentService.getContent(REPO, "X-pwc")).thenReturn(workingCopy);
        when(contentService.committedVersionsLeftAfterDeleting(eq(REPO), any(Document.class))).thenReturn(List.of());
    }

    private static CallContext callerNamed(String name) {
        CallContext caller = mock(CallContext.class);
        when(caller.getUsername()).thenReturn(name);
        return caller;
    }

    private void assertNothingDeleted() {
        org.mockito.Mockito.mockingDetails(contentService).getInvocations().forEach(call -> assertTrue(
                !call.getMethod().getName().startsWith("delete"), "it deleted: " + call.getMethod().getName()));
    }

    @Test
    @DisplayName("the document itself is asked, and a refusal is thrown")
    void theDocumentItselfIsAsked() {
        refused.add(latest.getId());
        assertThrows(CmisPermissionDeniedException.class, () -> deletes.refuseADeleteOfEveryVersion(callerNamed("importer"), REPO, latest),
                "asking about a document the importer may not delete");
        assertEquals(List.of(PermissionMapping.CAN_DELETE_OBJECT + " " + latest.getId()), asked, "what was asked");
        assertNothingDeleted();
    }

    @Test
    @DisplayName("every version a delete of every version removes is asked, and one refusal is thrown")
    void everyVersionIsAsked() {
        refused.add(older.getId());
        assertThrows(CmisPermissionDeniedException.class, () -> deletes.refuseADeleteOfEveryVersion(callerNamed("importer"), REPO, latest),
                "asking about a document whose older version the importer may not delete");
        assertTrue(asked.contains(PermissionMapping.CAN_DELETE_OBJECT + " " + older.getId()), "the older version was asked: " + asked);
        assertNothingDeleted();
    }

    @Test
    @DisplayName("another user's check-out, or one with no holder named, is refused as a versioning conflict")
    void anotherUsersCheckOutIsRefused() {
        checkedOutBy("holder");
        CmisVersioningException refusal = assertThrows(CmisVersioningException.class,
                () -> deletes.refuseADeleteOfEveryVersion(callerNamed("importer"), REPO, latest), "asking about a document another user holds");
        assertEquals(409, refusal.getCode().intValue(), "the status the refusal carries");
        checkedOutBy(null);
        assertThrows(CmisVersioningException.class, () -> deletes.refuseADeleteOfEveryVersion(callerNamed("importer"), REPO, latest),
                "asking about a document checked out by no one named");
        assertNothingDeleted();
    }

    @Test
    @DisplayName("a delete that may be made returns — the holder's own delete of every version, though its only checked-in version deleted alone would be refused, or of a document not checked out — and deletes nothing")
    void aDeleteThatMayBeMadeReturns() {
        assertDoesNotThrow(() -> deletes.refuseADeleteOfEveryVersion(callerNamed("importer"), REPO, latest), "a document not checked out");
        checkedOutBy("importer");
        assertDoesNotThrow(() -> deletes.refuseADeleteOfEveryVersion(callerNamed("importer"), REPO, latest), "the holder's own");
        assertNothingDeleted();
    }

    @Test
    @DisplayName("the caller's own context is asked about everywhere — the document, every version, the listing, the check-out's holder")
    void theCallersOwnContextIsAskedAboutEverywhere() {
        CallContext importer = callerNamed("importer");
        checkedOutBy("importer");
        assertDoesNotThrow(() -> deletes.refuseADeleteOfEveryVersion(importer, REPO, latest), "the holder's own delete of every version");
        assertTrue(askedAs.size() >= 3, "what was asked: " + asked);
        assertTrue(askedAs.stream().allMatch(c -> c == importer), "something was asked about another caller than the importer: " + askedAs);
    }

    @Test
    @DisplayName("a version listing that cannot be read is thrown")
    void aListingThatCannotBeReadIsThrown() {
        when(contentService.getAllVersions(any(CallContext.class), eq(REPO), eq(SERIES))).thenThrow(new IllegalStateException("the view failed"));
        assertThrows(IllegalStateException.class, () -> deletes.refuseADeleteOfEveryVersion(callerNamed("importer"), REPO, latest),
                "asking while the versions cannot be read");
        assertNothingDeleted();
    }
}
