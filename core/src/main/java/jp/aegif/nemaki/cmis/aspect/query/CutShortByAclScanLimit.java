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
package jp.aegif.nemaki.cmis.aspect.query;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;

/**
 * Whether a search answer was cut short by the ACL scan limit (R258, R344, 3.4.2) — the one judgement every reader of a
 * CMIS query's answer asks.
 *
 * <p>While the ACL propagation has not converged, {@code SolrQueryProcessor} answers a large query with what its scan
 * window could confirm and marks the answer with the top-level extension {@code truncatedByAclScanLimit=true}
 * ({@code SolrQueryProcessor.markTruncated}): {@code numItems} is then a lower bound, {@code hasMoreItems} goes false at
 * the end of the window, and the order is the window's only. Such an answer can tell neither the whole set's count, its
 * order nor where it continues, so no reader may answer it as the whole. The mark is read as the producer writes it: a
 * TOP-LEVEL extension element named {@code truncatedByAclScanLimit} whose value is the string {@code true}. Anything else
 * — no mark, the value {@code false}, the name nested under another element — is not the mark.
 */
public final class CutShortByAclScanLimit {

    /** The name of the extension {@code SolrQueryProcessor.markTruncated} adds to the top level of the answer. */
    public static final String MARK = "truncatedByAclScanLimit";

    /**
     * What a reader answers in place of a cut-short answer (R344). It names no row, no id and no count — the lower bound is
     * not the count, so it is not repeated as one.
     */
    public static final String REFUSAL = "the search answer is cut short by the ACL scan limit, so neither its count, its"
            + " order nor where it continues is the whole set's — narrow the query";

    private CutShortByAclScanLimit() {
    }

    /** Whether {@code answer} carries the mark at its top level. A null answer, or one without extensions, does not. */
    public static boolean isCutShort(ExtensionsData answer) {
        if (answer == null || answer.getExtensions() == null) {
            return false;
        }
        for (CmisExtensionElement extension : answer.getExtensions()) {
            if (extension != null && MARK.equals(extension.getName()) && "true".equals(extension.getValue())) {
                return true;
            }
        }
        return false;
    }
}
