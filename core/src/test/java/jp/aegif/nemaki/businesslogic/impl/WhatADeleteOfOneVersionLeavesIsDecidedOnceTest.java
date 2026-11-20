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
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;

/**
 * What a delete of one version leaves of its series is decided once, by the rule deleteDocument deletes the series by
 * (R329, 3.4.2).
 *
 * <p>A delete by the holder of a working copy, with {@code allVersions} false, of the only checked-in version is refused
 * before anything is deleted — {@code ObjectServiceInternalImpl} asks {@code ContentService.committedVersionsLeftAfterDeleting}
 * whether a checked-in version is left. deleteDocument decides, in its own branch, whether to delete the series too. A
 * second rule that disagreed with it would refuse a delete that leaves the series, or let one through that deletes it.
 *
 * <p>What is locked, on the product's {@code ContentServiceImpl}: what is left is the series' versions read from the
 * store, without the private working copies and without the named version; a listing answered as nothing, or that
 * cannot be read, is thrown; and deleteDocument with {@code allVersions} false deletes the version series exactly when
 * that answer is empty, and promotes a version left otherwise. The DAO, the cache and the index are stand-ins.
 */
class WhatADeleteOfOneVersionLeavesIsDecidedOnceTest {

    private static final String REPO = "bedroom";
    private static final String SERIES = "vs-1";

    private static Document version(String id, String label, boolean latest, boolean workingCopy) {
        Document document = new Document();
        document.setId(id);
        document.setName("W");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setVersionSeriesId(SERIES);
        document.setVersionLabel(label);
        document.setLatestVersion(latest);
        document.setMajorVersion(true);
        document.setPrivateWorkingCopy(workingCopy);
        return document;
    }

    private static ContentServiceImpl serviceOver(ContentDaoService dao) {
        PropertyManager properties = mock(PropertyManager.class);
        when(properties.readValue(anyString())).thenReturn("false");
        ContentServiceImpl service = new ContentServiceImpl();
        service.setContentDaoService(dao);
        service.setPropertyManager(properties);
        service.setNemakiCachePool(mock(NemakiCachePool.class, RETURNS_DEEP_STUBS));
        service.setSolrUtil(mock(SolrUtil.class));
        return service;
    }

    private static ContentDaoService daoWith(Document named, List<Document> versions) {
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getContent(REPO, named.getId())).thenReturn(named);
        when(dao.getAllVersions(REPO, SERIES)).thenReturn(versions);
        when(dao.create(anyString(), any(jp.aegif.nemaki.model.Change.class))).thenAnswer(call -> call.getArgument(1));
        return dao;
    }

    @Test
    @DisplayName("what is left is the series' versions without the working copies and without the named one")
    void theCheckedInVersionsBesidesTheNamedOneAreWhatIsLeft() {
        Document older = version("W-1", "1.0", false, false);
        Document latest = version("W-2", "2.0", true, false);
        Document workingCopy = version("W-pwc", "pwc", false, true);
        assertEquals(List.of(older), serviceOver(daoWith(latest, List.of(older, latest, workingCopy)))
                .committedVersionsLeftAfterDeleting(REPO, latest), "what deleting the latest of two leaves");
        assertEquals(List.of(), serviceOver(daoWith(latest, List.of(latest, workingCopy)))
                .committedVersionsLeftAfterDeleting(REPO, latest), "what deleting the only checked-in version leaves");
        assertEquals(List.of(latest), serviceOver(daoWith(workingCopy, List.of(latest, workingCopy)))
                .committedVersionsLeftAfterDeleting(REPO, workingCopy), "what deleting the working copy leaves");
    }

    @Test
    @DisplayName("a listing answered as nothing, or that cannot be read, is thrown")
    void aListingThatIsNoAnswerIsThrown() {
        Document latest = version("W-2", "2.0", true, false);
        ContentDaoService nothing = daoWith(latest, null);
        assertThrows(IllegalStateException.class, () -> serviceOver(nothing).committedVersionsLeftAfterDeleting(REPO, latest),
                "a listing answered as nothing");
        ContentDaoService failing = daoWith(latest, List.of());
        when(failing.getAllVersions(REPO, SERIES)).thenThrow(new IllegalStateException("the view failed"));
        assertThrows(IllegalStateException.class, () -> serviceOver(failing).committedVersionsLeftAfterDeleting(REPO, latest),
                "a listing that cannot be read");
    }

    private static List<String> seriesDeleted(ContentDaoService dao) {
        List<String> deleted = new ArrayList<>();
        mockingDetails(dao).getInvocations().forEach(call -> {
            if (call.getMethod().getName().equals("delete") && SERIES.equals(call.getArguments()[1])) {
                deleted.add(SERIES);
            }
        });
        return deleted;
    }

    private static List<String> promoted(ContentDaoService dao) {
        List<String> updated = new ArrayList<>();
        mockingDetails(dao).getInvocations().forEach(call -> {
            if (call.getMethod().getName().equals("update") && call.getArguments()[1] instanceof Document d) {
                updated.add(d.getId());
            }
        });
        return updated;
    }

    private static void deleteAlone(ContentServiceImpl service, Document named) {
        try {
            service.deleteDocument(mock(CallContext.class), REPO, named.getId(), Boolean.FALSE, Boolean.FALSE);
        } catch (RuntimeException e) {
            // A collaborator that is not wired may fail the version's own delete. The promotion comes before that delete
            // and the series' delete after it: the case with nothing left asserts the series was deleted, so there the
            // delete ran through; the case with a version left asserts the promotion, which no failure after it undoes.
        }
    }

    @Test
    @DisplayName("deleteDocument with allVersions false deletes the series exactly when nothing is left, and promotes a version left otherwise")
    void deleteDocumentDeletesTheSeriesExactlyWhenNothingIsLeft() {
        Document latest = version("W-2", "2.0", true, false);
        Document workingCopy = version("W-pwc", "pwc", false, true);
        ContentDaoService alone = daoWith(latest, List.of(latest, workingCopy));
        assertEquals(List.of(), serviceOver(alone).committedVersionsLeftAfterDeleting(REPO, latest), "the fixture: nothing left");
        deleteAlone(serviceOver(alone), latest);
        assertEquals(List.of(SERIES), seriesDeleted(alone), "the series deleted when nothing is left");
        assertEquals(List.of(), promoted(alone), "the versions promoted when nothing is left");

        Document older = version("W-1", "1.0", false, false);
        ContentDaoService two = daoWith(latest, List.of(older, latest, workingCopy));
        assertEquals(List.of(older), serviceOver(two).committedVersionsLeftAfterDeleting(REPO, latest), "the fixture: one left");
        deleteAlone(serviceOver(two), latest);
        assertEquals(List.of(), seriesDeleted(two), "the series deleted when a version is left");
        assertEquals(List.of(older.getId()), promoted(two), "the version promoted when one is left");
    }
}
