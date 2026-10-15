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
package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.apache.chemistry.opencmis.server.support.query.CmisCommonTree;
import org.apache.chemistry.opencmis.server.support.query.CmisQlStrictLexer;
import org.apache.chemistry.opencmis.server.support.query.CmisTree;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code IS NULL} and {@code IS NOT NULL} name the Solr field of the column (3.4.1, external review C1).
 *
 * <p>Both operators read the column with {@code safeWalkExprToString}, which walks to {@code walkCol} — and
 * that returns {@code null} for every column. The field name was always null, so {@code TermRangeQuery} threw
 * {@code "field must not be null"}: neither operator could be used at all, and the CMIS column name was never
 * translated to its Solr name. Measured on the product before the fix: a {@code NullPointerException}, not an
 * empty result.
 *
 * <p>The translation is asserted through a {@code SolrUtil} that answers a different Solr name than the CMIS
 * name, so a fix that merely stopped the exception — passing the CMIS name straight through — does not satisfy
 * these locks.
 */
class IsNullNamesTheSolrFieldTest {

    private static final String REPO = "bedroom";
    /** Deliberately unlike the CMIS name, so "it did not throw" cannot pass for "it translated". */
    private static final String CMIS_COLUMN = "cmis:description";
    private static final String SOLR_FIELD = "description_for_search";

    private SolrPredicateWalker walker;

    @BeforeEach
    void setUp() {
        SolrUtil solrUtil = mock(SolrUtil.class);
        when(solrUtil.convertToString(any(CmisTree.class))).thenReturn(CMIS_COLUMN);
        when(solrUtil.getPropertyNameInSolr(eq(REPO), eq(CMIS_COLUMN))).thenReturn(SOLR_FIELD);
        walker = new SolrPredicateWalker(REPO, null, solrUtil, null);
    }

    /** The column node the parser hands these operators: a COL with the property name under it. */
    private static CmisTree column() {
        CmisCommonTree col = new CmisCommonTree(CmisQlStrictLexer.COL, "COL");
        col.addChild(new CmisCommonTree(CmisQlStrictLexer.ID, CMIS_COLUMN));
        return col;
    }

    private String queryFor(String method) {
        Query query = assertDoesNotThrow(() -> {
            Method m = SolrPredicateWalker.class.getDeclaredMethod(method, CmisTree.class);
            m.setAccessible(true);
            try {
                return (Query) m.invoke(walker, column());
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
            }
        }, method + " threw instead of building a query — this is the defect, not a harness problem");
        return query.toString();
    }

    @Test
    @DisplayName("IS NOT NULL asks for any value of the column's Solr field")
    void isNotNullAsksForAnyValueOfTheSolrField() {
        String query = queryFor("walkIsNotNull");

        assertEquals(SOLR_FIELD + ":[* TO *]", query,
                "IS NOT NULL did not become an open range over the column's Solr field");
    }

    @Test
    @DisplayName("IS NULL excludes any value of the column's Solr field, against all documents")
    void isNullExcludesAnyValueOfTheSolrField() {
        String query = queryFor("walkIsNull");

        // A BooleanQuery whose only clause is MUST_NOT matches nothing, so the absence has to be
        // taken against all documents. Without the positive clause this query returns no rows for
        // every repository, which reads as "no document has that property empty".
        assertEquals("+*:* -" + SOLR_FIELD + ":[* TO *]", query,
                "IS NULL did not become an exclusion of the column's Solr field against all documents");
    }

    @Test
    @DisplayName("neither operator leaves the CMIS column name in the query — the Solr name is a different field")
    void neitherOperatorUsesTheCmisName() {
        for (String method : new String[] {"walkIsNull", "walkIsNotNull"}) {
            String query = queryFor(method);
            assertTrue(query.contains(SOLR_FIELD), method + " did not name the Solr field: " + query);
            assertTrue(!query.contains(CMIS_COLUMN),
                    method + " left the untranslated CMIS column name in the query: " + query);
        }
    }
}
