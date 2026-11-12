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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * A delete asks {@code CAN_DELETE_OBJECT} of every version it removes, before anything is deleted (R323, 3.4.2).
 *
 * <p>Unless {@code allVersions} is false, a delete removes the whole version series: {@code deleteDocument} deletes each
 * version {@code getAllVersions} answers the caller. Only the named object was asked, so a caller who may delete the
 * latest version removed versions it may neither delete nor read (measured on nb33 with a user who is no administrator).
 *
 * <p>What is locked, on the product's {@code ObjectServiceInternalImpl.deleteObjectInternal}: with {@code allVersions}
 * true or not given, every version the listing answers is asked, from whichever version the delete names; one refusal
 * deletes nothing — neither the series nor the parentChild child that the cascade would have deleted first; a listing
 * that throws, answers nothing or holds a row without an id deletes nothing; with {@code allVersions} false only the
 * named version is asked and the delete goes on although an older version may not be deleted.
 *
 * <p>Which version the caller may delete is a stand-in: the exception service refuses the ids in {@link #refused}.
 */
class ADeleteAsksEveryVersionItRemovesTest {

    private static final String REPO = "bedroom";
    private static final String SERIES = "series-of-Z";

    private final ContentService contentService = mock(ContentService.class);
    private final ExceptionService exceptionService = mock(ExceptionService.class);
    private final ObjectServiceInternalImpl deletes = new ObjectServiceInternalImpl();
    private final CallContext caller = mock(CallContext.class);
    /** The ids the caller may not delete. */
    private final Set<String> refused = new TreeSet<>();
    /** The ids CAN_DELETE_OBJECT was asked of, in order. */
    private final List<String> asked = new ArrayList<>();
    private final Document first = version("Z-1.0", false);
    private final Document second = version("Z-2.0", false);
    private final Document latest = version("Z-3.0", true);

    ADeleteAsksEveryVersionItRemovesTest() {
        when(caller.getUsername()).thenReturn("someone");
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(eq(REPO), anyString())).thenAnswer(call -> new ReentrantLock());
        doAnswer(call -> {
            Content content = call.getArgument(3);
            asked.add(content.getId());
            if (refused.contains(content.getId())) {
                throw new CmisPermissionDeniedException("Permission Denied!");
            }
            return null;
        }).when(exceptionService).permissionDenied(any(CallContext.class), eq(REPO),
                eq(PermissionMapping.CAN_DELETE_OBJECT), any(Content.class));
        when(contentService.getAllVersions(caller, REPO, SERIES)).thenReturn(Arrays.asList(first, second, latest));
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
        when(contentService.getAllVersions(caller, REPO, null)).thenReturn(List.of(child));
        deletes.setContentService(contentService);
        deletes.setExceptionService(exceptionService);
        deletes.setThreadLockService(locks);
        deletes.setNemakiCachePool(mock(NemakiCachePool.class, RETURNS_DEEP_STUBS));
    }

    private static Document version(String id, boolean latest) {
        Document document = new Document();
        document.setId(id);
        document.setName("Z");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setVersionSeriesId(SERIES);
        document.setLatestVersion(latest);
        return document;
    }

    private void assertNothingWasDeleted(String why) {
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        verify(contentService, never()).delete(any(), anyString(), anyString(), any());
        assertEquals(List.of(), deletedIds(), why);
    }

    private List<String> deletedIds() {
        List<String> ids = new ArrayList<>();
        org.mockito.Mockito.mockingDetails(contentService).getInvocations().forEach(call -> {
            String name = call.getMethod().getName();
            if (name.equals("deleteDocument") || name.equals("delete")) {
                ids.add(String.valueOf(call.getArguments()[2]));
            }
        });
        return ids;
    }

    @Test
    @DisplayName("a delete of every version that the caller may not delete one of deletes nothing — not even the parentChild child")
    void oneVersionTheCallerMayNotDeleteRefusesTheDelete() {
        refused.add(first.getId());
        assertThrows(CmisPermissionDeniedException.class,
                () -> deletes.deleteObjectInternal(caller, REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "deleting every version of Z, the first of which the caller may not delete");
        assertNothingWasDeleted("the objects deleted when one version of Z may not be deleted");
    }

    @Test
    @DisplayName("allVersions not given deletes every version, so every version is asked")
    void allVersionsNotGivenAsksEveryVersion() {
        refused.add(second.getId());
        assertThrows(CmisPermissionDeniedException.class,
                () -> deletes.deleteObjectInternal(caller, REPO, latest, null, Boolean.FALSE),
                "deleting Z without saying allVersions, the second version of which the caller may not delete");
        assertNothingWasDeleted("the objects deleted when allVersions is not given and one version may not be deleted");
    }

    @Test
    @DisplayName("a delete named by an older version asks every version too — the latest one included")
    void aDeleteNamedByAnOlderVersionAsksEveryVersion() {
        refused.add(latest.getId());
        assertThrows(CmisPermissionDeniedException.class,
                () -> deletes.deleteObjectInternal(caller, REPO, first, Boolean.TRUE, Boolean.FALSE),
                "deleting every version of Z named by its first version, the latest of which the caller may not delete");
        assertNothingWasDeleted("the objects deleted when the delete is named by an older version");
    }

    @Test
    @DisplayName("when the caller may delete every version, each is asked and the series is deleted")
    void everyVersionIsAskedAndTheSeriesIsDeleted() {
        assertDoesNotThrow(() -> deletes.deleteObjectInternal(caller, REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "deleting every version of Z, each of which the caller may delete");
        assertEquals(new TreeSet<>(List.of(first.getId(), second.getId(), latest.getId(), "CHILD")), new TreeSet<>(asked),
                "the objects CAN_DELETE_OBJECT was asked of");
        verify(contentService).deleteDocument(caller, REPO, latest.getId(), Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    @DisplayName("allVersions false deletes the named version only, so only it is asked — an older one the caller may not delete stays out of it")
    void allVersionsFalseAsksTheNamedVersionOnly() {
        refused.add(first.getId());
        assertDoesNotThrow(() -> deletes.deleteObjectInternal(caller, REPO, latest, Boolean.FALSE, Boolean.FALSE),
                "deleting the latest version of Z alone, while its first version may not be deleted");
        assertEquals(List.of("CHILD", latest.getId()).stream().sorted().toList(), asked.stream().sorted().toList(),
                "the objects CAN_DELETE_OBJECT was asked of when only the latest version is deleted");
        verify(contentService).deleteDocument(caller, REPO, latest.getId(), Boolean.FALSE, Boolean.FALSE);
    }

    @Test
    @DisplayName("a version listing that cannot be read deletes nothing")
    void aListingThatCannotBeReadDeletesNothing() {
        when(contentService.getAllVersions(caller, REPO, SERIES)).thenThrow(new IllegalStateException("the view did not answer"));
        assertThrows(IllegalStateException.class,
                () -> deletes.deleteObjectInternal(caller, REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "deleting every version of Z while its versions cannot be listed");
        assertNothingWasDeleted("the objects deleted when the versions cannot be listed");
    }

    @Test
    @DisplayName("a version listing that answers nothing, or a row without an id, deletes nothing")
    void aListingThatAnswersNothingDeletesNothing() {
        when(contentService.getAllVersions(caller, REPO, SERIES)).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> deletes.deleteObjectInternal(caller, REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "deleting every version of Z while the listing answers nothing");
        Document nameless = version(null, false);
        when(contentService.getAllVersions(caller, REPO, SERIES)).thenReturn(Arrays.asList(nameless, latest));
        assertThrows(IllegalStateException.class,
                () -> deletes.deleteObjectInternal(caller, REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "deleting every version of Z while the listing holds a row without an id");
        when(contentService.getAllVersions(caller, REPO, SERIES)).thenReturn(Arrays.asList(null, latest));
        assertThrows(IllegalStateException.class,
                () -> deletes.deleteObjectInternal(caller, REPO, latest, Boolean.TRUE, Boolean.FALSE),
                "deleting every version of Z while the listing holds a null row");
        assertNothingWasDeleted("the objects deleted when the listing answers nothing usable");
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), anyBoolean(), anyBoolean());
    }
}
