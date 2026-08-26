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
package jp.aegif.nemaki.businesslogic.rendition.pdfa;

import org.jodconverter.document.DocumentFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The store properties that ASK LibreOffice for PDF/A (P3-2).
 *
 * <p>Asking is not getting — {@link VeraPdfAValidator} settles what the bytes are. What these
 * pin is that the request is well formed: the wrong filter name does not export the document at
 * all, and a version this build cannot name must not be sent as a guess.
 */
class PdfAExportRequestTest {

    @Test
    @DisplayName("the filter name follows the SOURCE document's family")
    void theFilterFollowsTheFamily() {
        // A spreadsheet exported with writer_pdf_Export is not exported at all.
        assertEquals("writer_pdf_Export", PdfAExportRequest.filterNameFor(DocumentFamily.TEXT));
        assertEquals("calc_pdf_Export",
                PdfAExportRequest.filterNameFor(DocumentFamily.SPREADSHEET));
        assertEquals("impress_pdf_Export",
                PdfAExportRequest.filterNameFor(DocumentFamily.PRESENTATION));
        assertEquals("draw_pdf_Export", PdfAExportRequest.filterNameFor(DocumentFamily.DRAWING));
        assertNull(PdfAExportRequest.filterNameFor(null));
    }

    @Test
    @DisplayName("SelectPdfVersion is inside FilterData, where LibreOffice reads it")
    void theVersionIsWhereTheFilterLooks() {
        Map<String, Object> store = PdfAExportRequest.storeProperties("writer_pdf_Export", 1);

        assertEquals("writer_pdf_Export", store.get("FilterName"));
        @SuppressWarnings("unchecked")
        Map<String, Object> filterData = (Map<String, Object>) store.get("FilterData");
        assertEquals(1, filterData.get("SelectPdfVersion"),
                "the version is not in FilterData, so the filter never sees it: " + store);
    }

    @Test
    @DisplayName("a version this build cannot name has no flavour to check against")
    void anUnknownVersionIsNotGuessed() {
        // Requesting a version whose output could not then be validated would produce a copy
        // that fails a check for a reason nobody chose.
        assertEquals("1b", PdfAExportRequest.flavourFor(1));
        assertEquals("2b", PdfAExportRequest.flavourFor(2));
        assertEquals("3b", PdfAExportRequest.flavourFor(3));
        assertNull(PdfAExportRequest.flavourFor(0));
        assertNull(PdfAExportRequest.flavourFor(9));
    }

    @Test
    @DisplayName("no filter name is refused, not defaulted")
    void aMissingFilterIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PdfAExportRequest.storeProperties("  ", 1));
    }
}
