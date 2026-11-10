/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * {@code getAllVersions} with both an objectId and a versionSeriesId answers only the series of the document the objectId names
 * (R307 — 3.4.2). The versionSeriesId was read and the objectId was not, so AtomPub's
 * {@code versions?id=<D>&versionSeries=<E's series>} answered E's versions, for a folder's id and an id that names nothing too
 * (measured on nb33). The series is now answered only when it is the named document's own; another document's series and any
 * series with the id of an object that is not a document are refused with a 400, and an id that names no CMIS object is not
 * found — before a version is read. Without a versionSeriesId, or with a blank one, the named document's versions are answered as
 * before, and a versionSeriesId without an objectId is not changed.
 *
 * <p>The service is the product's {@code VersioningServiceImpl} with the product's {@code ExceptionServiceImpl}; only the content
 * service, the compile and the locks are stood in for.
 */
class AllVersionsAnswerOnlyTheSeriesOfTheDocumentObjectIdNamesTest {

    private static final String REPO = "bedroom";

    private final VersioningServiceImpl service = new VersioningServiceImpl();
    private final ContentService contentService = mock(ContentService.class);
    private final CompileService compileService = mock(CompileService.class);
    private final CallContext callContext = mock(CallContext.class);

    /** What the stubbed compile answers: the id of the content it was given. */
    private static final class Compiled extends ObjectDataImpl {
        private static final long serialVersionUID = 1L;
        private final String compiledFrom;

        Compiled(String compiledFrom) {
            this.compiledFrom = compiledFrom;
        }
    }

    AllVersionsAnswerOnlyTheSeriesOfTheDocumentObjectIdNamesTest() {
        when(compileService.compileObjectData(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new Compiled(((Content) invocation.getArgument(2)).getId()));
        service.setContentService(contentService);
        service.setCompileService(compileService);
        service.setExceptionService(spy(new ExceptionServiceImpl()));
        service.setThreadLockService(mock(ThreadLockService.class));
        // D is a document of the series DS, with the versions D-1.0 and D; E's series ES has E-1.0 and E; F is a folder.
        stored("D", "cmis:document", "DS");
        stored("F", "cmis:folder", null);
        Content series = new Content();
        series.setId("VS-ROW");
        series.setType("versionSeries");
        series.setObjectType("versionSeries");
        when(contentService.getContent(REPO, "VS-ROW")).thenReturn(series);
        when(contentService.getAllVersions(any(), eq(REPO), eq("DS"))).thenAnswer(invocation -> versions("D-1.0", "D"));
        when(contentService.getAllVersions(any(), eq(REPO), eq("ES"))).thenAnswer(invocation -> versions("E-1.0", "E"));
    }

    private void stored(String id, String type, String series) {
        Content content = new Content();
        content.setId(id);
        content.setType(type);
        content.setObjectType(type);
        when(contentService.getContent(REPO, id)).thenReturn(content);
        if (series != null) {
            Document document = new Document();
            document.setId(id);
            document.setType(type);
            document.setObjectType(type);
            document.setVersionSeriesId(series);
            when(contentService.getDocument(REPO, id)).thenReturn(document);
        }
    }

    private static List<Document> versions(String older, String latest) {
        List<Document> versions = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String id : new String[] { older, latest }) {
            Document version = new Document();
            version.setId(id);
            version.setType("cmis:document");
            GregorianCalendar created = new GregorianCalendar();
            created.setTimeInMillis(now += 1000);
            version.setCreated(created);
            versions.add(version);
        }
        return versions;
    }

    private List<String> answered(String objectId, String versionSeriesId) {
        List<String> ids = new ArrayList<>();
        for (ObjectData version : service.getAllVersions(callContext, REPO, objectId, versionSeriesId, null, false, null)) {
            assertTrue(version instanceof Compiled, "a version was answered by something other than the compile: " + version);
            ids.add(((Compiled) version).compiledFrom);
        }
        return ids;
    }

    private void noVersionWasRead() {
        verify(contentService, never()).getAllVersions(any(), anyString(), anyString());
        verify(compileService, never()).compileObjectData(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anotherDocumentsSeriesForTheDocumentTheObjectIdNamesIsRefusedBeforeAVersionIsRead() {
        CmisInvalidArgumentException refused = assertThrows(CmisInvalidArgumentException.class, () -> answered("D", "ES"),
                "the versions of another document's series were answered for D");
        assertEquals(VersioningServiceImpl.VERSION_SERIES_OF_ANOTHER_DOCUMENT_REFUSED, refused.getMessage());
        noVersionWasRead();
    }

    @Test
    void theDocumentsOwnSeriesOrNoneOrABlankOneIsAnsweredAsBefore() {
        for (String series : new String[] { "DS", null, "", " " }) {
            List<String> versions = assertDoesNotThrow(() -> answered("D", series), "D with the series " + series + " was refused");
            assertEquals(List.of("D", "D-1.0"), versions, "D with the series " + series);
        }
        verify(contentService, never()).getAllVersions(any(), anyString(), eq("ES"));
    }

    @Test
    void aSeriesWithTheIdOfAFolderOrOfNothingIsNotAnsweredWithThatSeries() {
        CmisInvalidArgumentException folder = assertThrows(CmisInvalidArgumentException.class, () -> answered("F", "ES"),
                "a folder's id was answered with another series' versions");
        assertEquals(VersioningServiceImpl.VERSIONS_OF_AN_OBJECT_THAT_IS_NOT_A_DOCUMENT_REFUSED, folder.getMessage());
        for (String id : new String[] { "GONE", "VS-ROW" }) {
            assertThrows(CmisObjectNotFoundException.class, () -> answered(id, "ES"), id + " was answered with another series' versions");
        }
        noVersionWasRead();
    }

    @Test
    void aSeriesWithoutAnObjectIdIsAnsweredAsBefore() {
        List<String> versions = assertDoesNotThrow(() -> answered(null, "ES"), "a series without an objectId was refused");
        assertEquals(List.of("E", "E-1.0"), versions);
    }
}
