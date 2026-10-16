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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.chemistry.opencmis.server.support.query.CmisCommonTree;
import org.apache.chemistry.opencmis.server.support.query.CmisQlStrictLexer;
import org.apache.chemistry.opencmis.server.support.query.ColumnReference;
import org.apache.chemistry.opencmis.server.support.query.QueryObject;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code IS NULL} and {@code IS NOT NULL} name the Solr field of the column, once (3.4.1, external review C1).
 *
 * <p>Both operators read the column with {@code safeWalkExprToString}, which walks to {@code walkCol} — and that
 * returns {@code null} for every column, so {@code TermRangeQuery} threw {@code "field must not be null"}. From
 * the entry point that exception was caught in {@code SolrQueryProcessor} and the statement fell back to the
 * manual parser, which answered these operators correctly for properties outside {@code cmis:}: the operators
 * were broken in this walker, not unusable everywhere.
 *
 * <p>These locks drive {@code walkPredicate} — the public entry {@code SolrQueryProcessor} calls — and they use
 * the names the real {@code SolrUtil} produces, including the already-escaped colon of a custom property.
 * Escaping that a second time names a field nothing is indexed under, which for {@code IS NULL} matches EVERY
 * document the type and ACL filters leave: the direction this tree least wants a query to break in.
 */
class IsNullNamesTheSolrFieldTest {

    private static final String REPO = "bedroom";

    private SolrUtil solrUtil;
    private QueryObject queryObject;
    private SolrPredicateWalker walker;

    @BeforeEach
    void setUp() {
        solrUtil = mock(SolrUtil.class);
        queryObject = mock(QueryObject.class);
        walker = new SolrPredicateWalker(REPO, queryObject, solrUtil, null);
    }

    /**
     * The column as the query object resolves it, and the Solr name the real SolrUtil returns for it — plus
     * what the real {@code convertToString} and the real property map would answer for the QUALIFIED form,
     * so that reading the column the wrong way produces a wrong FIELD rather than a null from an unstubbed
     * mock. Without this the control for that regression fired on the mock gap, not on the defect it
     * declared (round-4 review, P2).
     */
    private void column(String qualifier, String propertyQueryName, String solrField) {
        when(queryObject.getColumnReference(anyInt()))
                .thenReturn(new ColumnReference(qualifier, propertyQueryName));
        when(solrUtil.getPropertyNameInSolr(eq(REPO), eq(propertyQueryName))).thenReturn(solrField);

        if (qualifier != null) {
            String joined = qualifier + "." + propertyQueryName;
            when(solrUtil.convertToString(any())).thenReturn(joined);
            // What the real map does with a name it does not know: a custom field no document carries.
            // Only stubbed when there IS a qualifier — with none, "joined" IS the property name and this
            // would overwrite the answer above (measured: it did).
            when(solrUtil.getPropertyNameInSolr(eq(REPO), eq(joined)))
                    .thenReturn("dynamic.property." + joined.replace(":", "\\:"));
        } else {
            when(solrUtil.convertToString(any())).thenReturn(propertyQueryName);
        }
    }

    /** {@code walkPredicate} — the entry {@code SolrQueryProcessor} calls — on IS NULL / IS NOT NULL. */
    private String predicate(int token) {
        String name = token == CmisQlStrictLexer.IS_NULL ? "IS_NULL" : "IS_NOT_NULL";
        CmisCommonTree node = new CmisCommonTree(token, name);
        CmisCommonTree col = new CmisCommonTree(CmisQlStrictLexer.COL, "COL");
        col.addChild(new CmisCommonTree(CmisQlStrictLexer.ID, "the-query-object-resolves-this"));
        node.addChild(col);
        Query query = assertDoesNotThrow(() -> walker.walkPredicate(node),
                "walkPredicate threw instead of building a query — this is the defect, not a harness problem");
        return query.toString();
    }

    private String isNull() {
        return predicate(CmisQlStrictLexer.IS_NULL);
    }

    private String isNotNull() {
        return predicate(CmisQlStrictLexer.IS_NOT_NULL);
    }

    @Test
    @DisplayName("IS NOT NULL asks for any value of the column's Solr field")
    void isNotNullAsksForAnyValueOfTheSolrField() {
        column(null, "cmis:name", "name");

        assertEquals("name:[* TO *]", isNotNull(),
                "IS NOT NULL did not become an open range over the column's Solr field");
    }

    @Test
    @DisplayName("IS NULL excludes any value of the column's Solr field, against all documents")
    void isNullExcludesAnyValueOfTheSolrField() {
        column(null, "cmis:name", "name");

        // The positive clause keeps the meaning unambiguous however this query is nested: a negation on its
        // own is a shape that has to be repaired downstream, and this walker's output is handed over as a string.
        assertEquals("+*:* -name:[* TO *]", isNull(),
                "IS NULL did not become an exclusion of the column's Solr field against all documents");
    }

    @Test
    @DisplayName("a custom property's field is not escaped a second time — getPropertyNameInSolr already escaped its colon")
    void aCustomPropertyIsNotEscapedTwice() {
        // Exactly what the real SolrUtil returns for a custom property: the colon already escaped, while the
        // document is indexed under the raw name. Escaping again escapes the backslash as well.
        String asSolrUtilReturnsIt = "dynamic.property.nemaki\\:tag";
        column(null, "nemaki:tag", asSolrUtilReturnsIt);

        assertEquals(asSolrUtilReturnsIt + ":[* TO *]", isNotNull(),
                "the field was escaped again, naming a field nothing is indexed under");
        assertEquals("+*:* -" + asSolrUtilReturnsIt + ":[* TO *]", isNull(),
                "the field was escaped again, so IS NULL matched every document instead of the ones without it");
        assertFalse(isNull().contains("\\\\"),
                "the field name carries a doubled backslash, so it names a field nothing is indexed under");
    }

    @Test
    @DisplayName("a custom DATETIME property's colon is escaped — that arm of the property map does not escape it itself")
    void aCustomDateTimePropertyIsEscapedOnce() {
        // The DATETIME arm returns "dynamicDate.property.<id>" with a RAW colon, where the STRING arm
        // escapes. Passing it through unchanged puts a bare colon in the query, which Solr rejects as a
        // syntax error (400) — the opposite break from escaping twice, in the same line of code.
        column(null, "nemaki:dueDate", "dynamicDate.property.nemaki:dueDate");

        assertEquals("dynamicDate.property.nemaki\\:dueDate:[* TO *]", isNotNull(),
                "the DATETIME field's colon was left raw, so Solr rejects the query");
        assertEquals("+*:* -dynamicDate.property.nemaki\\:dueDate:[* TO *]", isNull(),
                "the DATETIME field's colon was left raw, so Solr rejects the query");
        assertFalse(isNull().contains("\\\\"),
                "the DATETIME field's colon was escaped twice");
    }

    @Test
    @DisplayName("a table-qualified column resolves to its property — the qualifier is not part of the field name")
    void aQualifiedColumnResolvesToItsProperty() {
        // WHERE d.cmis:name IS NULL. Joining the node's children with a dot asks for "d.cmis:name", which is in
        // no property map: the field becomes a custom one no document carries, so IS NULL matched every document
        // the type and ACL filters left, and IS NOT NULL matched none.
        column("d", "cmis:name", "name");

        assertEquals("+*:* -name:[* TO *]", isNull(), "the table qualifier was read as part of the field name");
        assertEquals("name:[* TO *]", isNotNull(), "the table qualifier was read as part of the field name");
        // Naming the failure mode, so reading the column the wrong way cannot look like a pass: the field
        // a joined "d.cmis:name" resolves to is a custom one no document has, and IS NULL over it matches
        // every document the type and ACL filters leave.
        assertFalse(isNull().contains("dynamic.property.d."),
                "IS NULL named the qualified column as a custom field, so it matches every document");
    }
}
