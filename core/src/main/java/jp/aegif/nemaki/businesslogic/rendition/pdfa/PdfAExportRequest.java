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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How to ASK LibreOffice for PDF/A, and why asking is not getting (P3-2).
 *
 * <h2>The store properties</h2>
 *
 * <p>LibreOffice's PDF export filter takes a {@code FilterData} map, and {@code SelectPdfVersion}
 * is the one that matters: {@code 0} is ordinary PDF, {@code 1} is PDF/A-1b, {@code 2} is
 * PDF/A-2b, {@code 3} is PDF/A-3b. The filter NAME differs per document family — a spreadsheet
 * exported with {@code writer_pdf_Export} is not exported at all — which is why this builds one
 * map per family rather than one map.
 *
 * <h2>Asking is not getting</h2>
 *
 * <p>LibreOffice accepts the property and produces what it produces. Transparency it cannot
 * flatten, a font it cannot embed, or a feature the profile forbids all yield a file that is
 * not PDF/A while the export reports success. So a request is never recorded as a fact:
 * {@link VeraPdfAValidator} checks the bytes, and the duplication's disclosure states what was
 * FOUND. A product that recorded the request would be telling every reader its convenience
 * copies are archival renditions on the strength of a flag it set itself.
 *
 * <h2>Off by default, deliberately</h2>
 *
 * <p>PDF/A changes what the copy looks like — fonts embedded, transparency flattened, colour
 * managed — and these copies are previews. Turning it on for every existing deployment would
 * change every preview and grow every file, to make a claim the deployment then has to verify.
 * It is a decision, so it is a setting.
 */
public final class PdfAExportRequest {

    private PdfAExportRequest() {
    }

    /** {@code SelectPdfVersion} for ordinary PDF. */
    public static final int NO_PROFILE = 0;

    /**
     * The flavour id {@link VeraPdfAValidator} should be asked for, given a version number.
     *
     * <p>Deliberately narrow: a version this build cannot name is one whose output it cannot
     * check, and validating against the wrong flavour is worse than not validating.
     */
    public static String flavourFor(int selectPdfVersion) {
        return switch (selectPdfVersion) {
            case 1 -> "1b";
            case 2 -> "2b";
            case 3 -> "3b";
            default -> null;
        };
    }

    /**
     * The store properties for one document family.
     *
     * @param filterName the family's PDF export filter, e.g. {@code writer_pdf_Export}
     * @param selectPdfVersion 0 for ordinary PDF; 1/2/3 for PDF/A-1b/2b/3b
     */
    public static Map<String, Object> storeProperties(String filterName, int selectPdfVersion) {
        if (filterName == null || filterName.isBlank()) {
            throw new IllegalArgumentException("a PDF export needs its family's filter name; "
                    + "the wrong one does not export the document at all");
        }
        Map<String, Object> filterData = new LinkedHashMap<>();
        filterData.put("SelectPdfVersion", selectPdfVersion);
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("FilterName", filterName);
        store.put("FilterData", filterData);
        return store;
    }

    /** The PDF export filter for a jodconverter document family. */
    public static String filterNameFor(org.jodconverter.document.DocumentFamily family) {
        if (family == null) {
            return null;
        }
        return switch (family) {
            case TEXT -> "writer_pdf_Export";
            case SPREADSHEET -> "calc_pdf_Export";
            case PRESENTATION -> "impress_pdf_Export";
            case DRAWING -> "draw_pdf_Export";
        };
    }
}
