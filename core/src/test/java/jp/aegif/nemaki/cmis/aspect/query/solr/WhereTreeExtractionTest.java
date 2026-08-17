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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;

import org.apache.chemistry.opencmis.server.support.query.CmisQlStrictLexer;
import org.apache.chemistry.opencmis.server.support.query.CmisTree;
import org.apache.chemistry.opencmis.server.support.query.QueryUtilStrict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The WHERE predicate is found in the tree the real parser produces.
 *
 * <h2>Why against the real parser</h2>
 *
 * <p>A dropped predicate does not fail: {@code SolrQueryProcessor} falls back to {@code *:*},
 * so the query runs, returns the whole repository, and nothing logs. That is what happened
 * when OpenCMIS moved to ANTLR4 — the root stopped being a nil node wrapping SELECT and became
 * the SELECT node itself, so an extraction that only looked one level down found nothing. Every
 * LIKE, IN_FOLDER and equality filter was silently discarded; the only signal was the CMIS TCK
 * reporting documents that should not have matched.
 *
 * <p>So this does not hand-build a tree — a hand-built tree encodes the same assumption the
 * code makes, and would have passed throughout. It parses real CMIS SQL with the shipped
 * parser and requires the predicate to come back.
 */
class WhereTreeExtractionTest {

    private static CmisTree extract(String statement) throws Exception {
        QueryUtilStrict util = new QueryUtilStrict(statement, null, null);
        CmisTree tree = util.parseStatement();
        assertNotNull(tree, "the parser returned no tree for: " + statement);

        Method extractWhereTree = SolrQueryProcessor.class
                .getDeclaredMethod("extractWhereTree", CmisTree.class);
        extractWhereTree.setAccessible(true);
        return (CmisTree) extractWhereTree.invoke(new SolrQueryProcessor(), tree);
    }

    @Test
    @DisplayName("LIKE の述語が取り出せる — 取り出せないと全件 (*:*) になる")
    void aLikePredicateIsFound() throws Exception {
        CmisTree where = extract("SELECT * FROM cmis:document WHERE cmis:name LIKE 'a%'");

        assertNotNull(where, "the WHERE predicate was dropped — the query would match everything");
        assertEquals(CmisQlStrictLexer.LIKE, where.getType(),
                "the node handed to the walker must be the predicate itself, not its parent");
    }

    @Test
    @DisplayName("等値・AND・IN_FOLDER も同じ経路で取り出せる")
    void otherPredicateShapesAreFound() throws Exception {
        assertEquals(CmisQlStrictLexer.EQ,
                extract("SELECT * FROM cmis:document WHERE cmis:name = 'x'").getType());
        assertEquals(CmisQlStrictLexer.AND,
                extract("SELECT * FROM cmis:document WHERE cmis:name = 'x' AND cmis:createdBy = 'y'")
                        .getType());
        assertNotNull(extract("SELECT * FROM cmis:document WHERE IN_FOLDER('abc')"),
                "IN_FOLDER is a predicate like any other; dropping it returns the whole repository");
    }

    /**
     * No WHERE clause still means no predicate — {@code *:*} is CORRECT here.
     *
     * <p>Without this the fix could have been "return something non-null", which would turn an
     * unfiltered query into a broken one instead of a correct one.
     */
    @Test
    @DisplayName("WHERE が無い文では null (この場合の *:* は正しい)")
    void aStatementWithoutWhereHasNoPredicate() throws Exception {
        assertNull(extract("SELECT * FROM cmis:document"));
    }
}
