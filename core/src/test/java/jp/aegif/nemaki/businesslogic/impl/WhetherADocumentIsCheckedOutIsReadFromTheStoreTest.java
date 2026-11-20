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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.VersionSeries;

/**
 * Whether a document is checked out is read from the store (R331, R341, 3.4.2) — the question force-archive and the
 * retention job ask before they delete every version as the system.
 *
 * <p>R341: the first form read the series' mark alone, through the cached read. Another replica's check-out does not reach
 * this replica's cache, and a series restored with its mark cleared, or a check-out that failed between writing its working
 * copy and marking the series, leaves a working copy the mark does not name — force-archive then deleted every version and
 * the working copy lost its series again (measured on nb33 by clearing only the store's mark).
 *
 * <p>What is locked, on the product's {@code ContentServiceImpl.isCheckedOut}: true when the store's series is marked
 * checked out, and when the store's version list of the series holds a working copy whatever the mark says (also when the
 * series is not there); false when neither; the cached read is not the answer either way; a document without a series is
 * not checked out and the store is not asked; a series or a version list that cannot be read is thrown, not answered false.
 * The DAO is a stand-in.
 */
class WhetherADocumentIsCheckedOutIsReadFromTheStoreTest {

    private static final String REPO = "bedroom";

    private final ContentDaoService dao = mock(ContentDaoService.class);
    private final ContentServiceImpl service = new ContentServiceImpl();

    WhetherADocumentIsCheckedOutIsReadFromTheStoreTest() {
        service.setContentDaoService(dao);
    }

    private static Document document(String series) {
        Document document = new Document();
        document.setId("D");
        document.setVersionSeriesId(series);
        return document;
    }

    private static Document workingCopy() {
        Document pwc = document("S");
        pwc.setId("D-pwc");
        pwc.setPrivateWorkingCopy(Boolean.TRUE);
        return pwc;
    }

    private static VersionSeries series(Boolean checkedOut) {
        VersionSeries series = new VersionSeries();
        series.setId("S");
        series.setVersionSeriesCheckedOut(checkedOut);
        return series;
    }

    private void store(VersionSeries series, Document... versions) {
        when(dao.getVersionSeriesFresh(REPO, "S")).thenReturn(series);
        when(dao.getAllVersions(REPO, "S")).thenReturn(Arrays.asList(versions));
    }

    @Test
    @DisplayName("the store's mark answers: checked out is true; not checked out, or no answer, with no working copy, false")
    void theStoresMarkAnswers() {
        store(series(Boolean.TRUE), document("S"));
        assertTrue(service.isCheckedOut(REPO, document("S")), "a series the store marks checked out");
        store(series(Boolean.FALSE), document("S"));
        assertFalse(service.isCheckedOut(REPO, document("S")), "a series the store does not mark, with no working copy");
        store(series(null), document("S"));
        assertFalse(service.isCheckedOut(REPO, document("S")), "a series that says nothing, with no working copy");
        store(null, document("S"));
        assertFalse(service.isCheckedOut(REPO, document("S")), "a series that is not there, with no working copy");
    }

    @Test
    @DisplayName("a working copy in the store's version list answers true whatever the mark says (R341)")
    void aWorkingCopyInTheStoreAnswersWhateverTheMarkSays() {
        store(series(Boolean.FALSE), document("S"), workingCopy());
        assertTrue(service.isCheckedOut(REPO, document("S")), "a working copy the store's mark does not name");
        store(series(null), workingCopy());
        assertTrue(service.isCheckedOut(REPO, document("S")), "a working copy of a series that says nothing");
        store(null, document("S"), workingCopy());
        assertTrue(service.isCheckedOut(REPO, document("S")), "a working copy of a series that is not there");
    }

    @Test
    @DisplayName("the cached read is not the answer, either way (R341)")
    void theCachedReadIsNotTheAnswer() {
        when(dao.getVersionSeries(REPO, "S")).thenReturn(series(Boolean.FALSE));
        store(series(Boolean.TRUE), document("S"));
        assertTrue(service.isCheckedOut(REPO, document("S")), "the cache says not checked out, the store says checked out");
        when(dao.getVersionSeries(REPO, "S")).thenReturn(series(Boolean.TRUE));
        store(series(Boolean.FALSE), document("S"));
        assertFalse(service.isCheckedOut(REPO, document("S")), "the cache says checked out, the store says it is not and holds no working copy");
    }

    @Test
    @DisplayName("a document without a series is not checked out, and the store is not asked")
    void noSeriesIsNotCheckedOut() {
        assertFalse(service.isCheckedOut(REPO, document(null)), "a document without a series");
        verifyNoInteractions(dao);
    }

    @Test
    @DisplayName("a series or a version list that cannot be read is thrown, not answered false")
    void aStoreThatCannotBeReadIsThrown() {
        when(dao.getVersionSeriesFresh(REPO, "S")).thenThrow(new IllegalStateException("the version lookup failed"));
        assertThrows(IllegalStateException.class, () -> service.isCheckedOut(REPO, document("S")), "a series that cannot be read");

        org.mockito.Mockito.reset(dao);
        when(dao.getVersionSeriesFresh(REPO, "S")).thenReturn(series(Boolean.FALSE));
        when(dao.getAllVersions(REPO, "S")).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.isCheckedOut(REPO, document("S")), "a version list answered as nothing");

        org.mockito.Mockito.reset(dao);
        when(dao.getVersionSeriesFresh(REPO, "S")).thenReturn(series(Boolean.FALSE));
        when(dao.getAllVersions(REPO, "S")).thenThrow(new IllegalStateException("the version list could not be read"));
        assertThrows(IllegalStateException.class, () -> service.isCheckedOut(REPO, document("S")), "a version list that cannot be read");

        List<Document> withAHole = Arrays.asList(document("S"), null);
        org.mockito.Mockito.reset(dao);
        when(dao.getVersionSeriesFresh(REPO, "S")).thenReturn(series(Boolean.FALSE));
        when(dao.getAllVersions(REPO, "S")).thenReturn(withAHole);
        assertThrows(IllegalStateException.class, () -> service.isCheckedOut(REPO, document("S")), "a version answered as nothing");

        org.mockito.Mockito.reset(dao);
        store(series(Boolean.FALSE), document("S"));
        assertDoesNotThrow(() -> service.isCheckedOut(REPO, document("S")), "a store that answers (the positive control)");
    }
}
