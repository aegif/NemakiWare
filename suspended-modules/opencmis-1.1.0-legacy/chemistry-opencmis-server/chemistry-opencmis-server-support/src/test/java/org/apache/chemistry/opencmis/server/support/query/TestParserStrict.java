/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.server.support.query;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestParserStrict extends AbstractParserTest {

    @Before
    public void setUp() {
        super.setUp(CmisQlStrictLexer.class, CmisQlStrictParser.class, "CmisBaseGrammar", "CmisBaseLexer");
    }

    @Override
    @After
    public void tearDown() {
        super.tearDown();
    }

    // ----- Lexer tests -----

    // ID:
    @Test
    public void testID1() {
        testLexerOk("ID", "a");
    }

    // "toto" OK
    @Test
    public void testID2() {
        testLexerFail("ID", "!");
    }

    @Test
    public void testID3() {
        testLexerOk("ID", "toto");
    }

    // "toto123" OK
    @Test
    public void testID4() throws Exception {
        testLexerOk("ID", "toto123");
    }

    // "toto123_" OK
    @Test
    public void testID5() throws Exception {
        testLexerOk("ID", "toto123_");
    }

    // "_foo" OK
    @Test
    public void testID6() throws Exception {
        testLexerOk("ID", "_foo");
    }

    // "foo:bar" OK
    @Test
    public void testID7() throws Exception {
        testLexerOk("ID", "foo:bar");
    }

    // "123" FAIL
    @Test
    public void testID8() throws Exception {
        testLexerFail("ID", "123");
    }

    // "123abc" FAIL
    @Test
    public void testID9() throws Exception {
        testLexerFail("ID", "123abc");
    }

    // NUM_LIT:
    // "123" OK
    @Test
    public void testNUM_LIT1() throws Exception {
        testLexerOk("NUM_LIT", "123");
    }

    // "0" OK
    @Test
    public void testNUM_LIT2() throws Exception {
        testLexerOk("NUM_LIT", "0");
    }

    // "-0" OK
    @Test
    public void testNUM_LIT3() throws Exception {
        testLexerOk("NUM_LIT", "-0");
    }

    // "1" OK
    @Test
    public void testNUM_LIT4() throws Exception {
        testLexerOk("NUM_LIT", "1");
    }

    // "-1" OK
    @Test
    public void testNUM_LIT5() throws Exception {
        testLexerOk("NUM_LIT", "-1");
    }

    // "-123" OK
    @Test
    public void testNUM_LIT6() throws Exception {
        testLexerOk("NUM_LIT", "123");
    }

    // "0123" OK
    @Test
    public void testNUM_LIT7() throws Exception {
        testLexerOk("NUM_LIT", "0123");
    }

    // "-0123" OK
    @Test
    public void testNUM_LIT8() throws Exception {
        testLexerOk("NUM_LIT", "-0123");
    }

    // "123abc" FAIL
    @Test
    public void testNUM_LIT9() throws Exception {
        testLexerFail("NUM_LIT", "123abc");
    }

    // "123E" FAIL
    @Test
    public void testNUM_LIT10() throws Exception {
        testLexerFail("NUM_LIT", "123E");
    }

    // "123.456" OK
    @Test
    public void testNUM_LIT11() throws Exception {
        testLexerOk("NUM_LIT", "123.456");
    }

    // "+123.456" OK
    @Test
    public void testNUM_LIT12() throws Exception {
        testLexerOk("NUM_LIT", "+123.456");
    }

    // "-123.456" OK
    @Test
    public void testNUM_LIT13() throws Exception {
        testLexerOk("NUM_LIT", "-123.456");
    }

    // ".456" OK
    @Test
    public void testNUM_LIT14() throws Exception {
        testLexerOk("NUM_LIT", ".456");
    }

    // "+.456" OK
    @Test
    public void testNUM_LIT15() throws Exception {
        testLexerOk("NUM_LIT", "+.456");
    }

    // "-.456" OK
    @Test
    public void testNUM_LIT16() throws Exception {
        testLexerOk("NUM_LIT", "-.456");
    }

    // "123." OK
    @Test
    public void testNUM_LIT17() throws Exception {
        testLexerOk("NUM_LIT", "123.");
    }

    // "+123." OK
    @Test
    public void testNUM_LIT18() throws Exception {
        testLexerOk("NUM_LIT", "+123.");
    }

    // "-123." OK
    @Test
    public void testNUM_LIT19() throws Exception {
        testLexerOk("NUM_LIT", "-123.");
    }

    // "+123.456E78" OK
    @Test
    public void testNUM_LIT20() throws Exception {
        testLexerOk("NUM_LIT", "+123.456E78");
    }

    // "-123.456E-78" OK
    @Test
    public void testNUM_LIT21() throws Exception {
        testLexerOk("NUM_LIT", "-123.456E-78");
    }

    // ".456E78" OK
    @Test
    public void testNUM_LIT22() throws Exception {
        testLexerOk("NUM_LIT", ".456E78");
    }

    // "+123.E+78" OK
    @Test
    public void testNUM_LIT23() throws Exception {
        testLexerOk("NUM_LIT", "+123.E+78");
    }

    // STRING_LIT:
    // "'abc'" OK
    @Test
    public void testSTRING_LIT1() throws Exception {
        testLexerOk("STRING_LIT", "'abc'");
    }

    // "'a''bc'" OK
    @Test
    public void testSTRING_LIT2() throws Exception {
        testLexerOk("STRING_LIT", "'a''bc'");
    }

    // "'abc" FAIL
    @Test
    public void testSTRING_LIT3() throws Exception {
        testLexerFail("STRING_LIT", "'abc");
    }

    // "abc'" FAIL
    @Test
    public void testSTRING_LIT4() throws Exception {
        testLexerFail("STRING_LIT", "abc'");
    }

    // "'ab'c'" FAIL
    @Test
    public void testSTRING_LIT5() throws Exception {
        testLexerFail("STRING_LIT", "'ab'c'");
    }

    @Test
    public void testSTRING_LIT6() throws Exception {
        testLexerOk("STRING_LIT", "'That''s'");
        testLexerOk("STRING_LIT", "'Gus'''");
    }

    @Test
    public void testSTRING_LIT7() throws Exception {
        testLexerOk("STRING_LIT", "'That\\\'s'");
        testLexerOk("STRING_LIT", "'Gus\\\'\'");
    }

    @Test
    public void testSTRING_LIT8() throws Exception {
        testLexerOk("STRING_LIT", "'c:\\\\temp'");
    }

    @Test
    public void testSTRING_LIT9() throws Exception {
        testLexerOk("STRING_LIT", "'Like%String'");
    }

    @Test
    public void testSTRING_LIT10() throws Exception {
        testLexerOk("STRING_LIT", "'Like_String'");
    }

    @Test
    public void testSTRING_LIT11() throws Exception {
        testLexerOk("STRING_LIT", "'Like\\%String'");
    }

    @Test
    public void testSTRING_LIT12() throws Exception {
        testLexerOk("STRING_LIT", "'Like\\_String'");
    }

    // BOOL_LIT:
    // "TRUE" OK
    @Test
    public void testBOOL_LIT1() throws Exception {
        testLexerOk("BOOL_LIT", "TRUE");
    }

    // "true" OK
    @Test
    public void testSBOOL_LIT2() throws Exception {
        testLexerOk("BOOL_LIT", "true");
    }

    // "FALSE" OK
    @Test
    public void testBOOL_LIT3() throws Exception {
        testLexerOk("BOOL_LIT", "FALSE");
    }

    // "false" OK
    @Test
    public void testBOOL_LIT4() throws Exception {
        testLexerOk("BOOL_LIT", "false");
    }

    // TIME_LIT:
    // "TIMESTAMP '2010-01-01Z01:01:01.000Z'" OK
    @Test
    public void testTIME_LIT1() throws Exception {
        testLexerOk("TIME_LIT", "TIMESTAMP '2010-01-01Z01:01:01.000Z'");
    }

    // "timestamp   '123'" OK
    @Test
    public void testTIME_LIT2() throws Exception {
        testLexerOk("TIME_LIT", "timestamp   '123'");
    }

    // "TIMESTAMP 123" FAIL
    @Test
    public void testTIME_LIT3() throws Exception {
        testLexerFail("TIME_LIT", "TIMESTAMP 123");
    }

    // ----- Parser tests -----

    // literal:
    // "123" OK
    @Test
    public void testLiteral1() throws Exception {
        testParserOk("literal", "123");
    }

    // "-123" OK
    @Test
    public void testLiteral2() throws Exception {
        testParserOk("literal", "123");
    }

    // "0" OK
    @Test
    public void testLiteral3() throws Exception {
        testParserOk("literal", "0");
    }

    // "0123" OK
    @Test
    public void testLiteral4() throws Exception {
        testParserOk("literal", "0123");
    }

    // "abc123" OK
    // "123abc" FAIL
    @Test
    public void testLiteral5() throws Exception {
        testParserFail("literal", "123abc");
    }

    // "'abc'" OK
    @Test
    public void testLiteral6() throws Exception {
        testParserOk("literal", "'abc'");
    }

    // "123.345E78" OK
    @Test
    public void testLiteral7() throws Exception {
        testParserOk("literal", "123.345E78");
    }

    // order_by_clause:
    // "ORDER BY foo" -> (ORDER_BY (COL foo) ASC)
    @Test
    public void testOrderBy1() throws Exception {
        testParser("order_by_clause", "ORDER BY foo", "(ORDER_BY (COL foo) ASC)");
    }

    // "ORDER BY foo ASC" -> (ORDER_BY (COL foo) ASC)
    @Test
    public void testOrderBy2() throws Exception {
        testParser("order_by_clause", "ORDER BY foo ASC", "ORDER_BY (COL foo) ASC)");
    }

    // "ORDER BY foo DESC" -> (ORDER_BY (COL foo) DESC)
    @Test
    public void testOrderBy3() throws Exception {
        testParser("order_by_clause", "ORDER BY foo DESC", "(ORDER_BY (COL foo) DESC)");
    }

    // "ORDER BY t.foo, bar DESC" -> (ORDER_BY (COL t foo) ASC (COL bar) DESC)
    @Test
    public void testOrderBy4() throws Exception {
        testParser("order_by_clause", "ORDER BY t.foo, bar DESC", "(ORDER_BY (COL t foo) ASC (COL bar) DESC)");
    }

    // column_reference:
    // "foo" -> (COL foo)
    @Test
    public void test_column_reference1() throws Exception {
        testParser("column_reference", "foo", "(COL foo)");
    }

    // "bar.foo" -> (COL bar foo)
    @Test
    public void test_column_reference2() throws Exception {
        testParser("column_reference", "bar.foo", "(COL bar foo)");
    }

    // from_clause:
    // "FROM foo JOIN bar ON x = y" -> (FROM (TABLE foo) (JOIN INNER (TABLE bar)
    // (ON (COL x) = (COL y))))
    @Test
    public void testFrom() throws Exception {
        testParser("from_clause", "FROM foo JOIN bar ON x = y",
                "(FROM (TABLE foo) (JOIN INNER (TABLE bar) (ON (COL x) = (COL y))))");
    }

    // table_join:
    // "LEFT OUTER JOIN foo ON x = y" -> (JOIN LEFT (TABLE foo) (ON (COL x) =
    // (COL y)))
    @Test
    public void test_column_reference11() throws Exception {
        testParser("table_join", "LEFT OUTER JOIN foo ON x = y", "(JOIN LEFT (TABLE foo) (ON (COL x) = (COL y)))");
    }

    // "INNER JOIN foo" -> (JOIN INNER (TABLE foo))
    @Test
    public void test_column_reference12() throws Exception {
        testParser("table_join", "INNER JOIN foo", "(JOIN INNER (TABLE foo))");
    }

    // one_table:
    // "foo" -> (TABLE foo)
    @Test
    public void test_column_reference3() throws Exception {
        testParser("one_table", "foo", "(TABLE foo)");
    }

    // "foo bar" -> (TABLE foo bar)
    @Test
    public void test_column_reference4() throws Exception {
        testParser("one_table", "foo bar", "(TABLE foo bar)");
    }

    // "foo AS bar" -> (TABLE foo bar)
    @Test
    public void test_column_reference5() throws Exception {
        testParser("one_table", "foo AS bar", "(TABLE foo bar)");
    }

    // "(foo)" -> (TABLE foo)
    @Test
    public void test_column_reference6() throws Exception {
        testParser("one_table", "(foo)", "(TABLE foo)");
    }

    // in_predicate:
    // "foo IN ( 'a', 'b', 'c')" -> (IN (COL foo) (IN_LIST 'a' 'b' 'c'))
    @Test
    public void test_in_predicate1() throws Exception {
        testParser("in_predicate", "foo IN ( 'a', 'b', 'c')", "(IN (COL foo) (IN_LIST 'a' 'b' 'c'))");
    }

    // "foo NOT IN ( 1, 2, 3)" -> (NOT_IN (COL foo) (IN_LIST 1 2 3))
    @Test
    public void test_in_predicate2() throws Exception {
        testParser("in_predicate", "foo NOT IN ( 1, 2, 3)", "(NOT_IN (COL foo) (IN_LIST 1 2 3))");
    }

    // quantified_in_predicate:
    // "ANY foo IN ('a', 1)" -> (IN_ANY (COL foo) (IN_LIST 'a' 1))
    @Test
    public void tes_quantified_in_predicate() throws Exception {
        testParser("quantified_in_predicate", "ANY foo IN ('a', 1)", "(IN_ANY (COL foo) (IN_LIST 'a' 1))");
    }

    // comparison_predicate:
    // "foo = 1" -> (= (COL foo) 1)
    @Test
    public void test_comparison_predicate1() throws Exception {
        testParser("comparison_predicate", "foo = 1", "(= (COL foo) 1)");
    }

    // "foo <> 1" -> (<> (COL foo) 1)
    @Test
    public void test_comparison_predicate2() throws Exception {
        testParser("comparison_predicate", "foo <> 1", "(<> (COL foo) 1)");
    }

    //
    // predicate:
    // "foo = 1" -> (= (COL foo) 1)
    @Test
    public void test_predicate1() throws Exception {
        testParser("predicate", "foo = 1", "(= (COL foo) 1)");
    }

    // "foo IN ('bar')" -> (IN (COL foo) (IN_LIST 'bar'))
    @Test
    public void test_predicate2() throws Exception {
        testParser("predicate", "foo IN ('bar')", "(IN (COL foo) (IN_LIST 'bar'))");
    }

    // "foo IS NULL" -> (IS_NULL (COL foo))
    @Test
    public void test_predicate3() throws Exception {
        testParser("predicate", "foo IS NULL", "(IS_NULL (COL foo))");
    }

    // "foo IS NOT NULL" -> (IS_NOT_NULL (COL foo))
    @Test
    public void test_predicate4() throws Exception {
        testParser("predicate", "foo IS NOT NULL", "(IS_NOT_NULL (COL foo))");
    }

    // "1 = ANY foo" -> (EQ_ANY 1 (COL foo))
    @Test
    public void test_predicate5() throws Exception {
        testParser("predicate", "1 = ANY foo", "(EQ_ANY 1 (COL foo))");
    }

    // "SCORE() = 'bar'" -> (= SCORE 'bar')
    @Test
    public void test_predicate6() throws Exception {
        testParser("predicate", "SCORE() = 'bar'", "(= SCORE 'bar')");
    }

    // boolean_term:
    // "c >= 3 AND d <= 4" -> (AND (>= (COL c) 3) (<= (COL d) 4))
    @Test
    public void boolean_term1() throws Exception {
        testParser("boolean_term", "c >= 3 AND d <= 4", "(AND (>= (COL c) 3) (<= (COL d) 4))");
    }

    // "c >= 3 AND NOT d <= 4" -> (AND (>= (COL c) 3) (NOT (<= (COL d) 4)))
    @Test
    public void boolean_term2() throws Exception {
        testParser("boolean_term", "c >= 3 AND NOT d <= 4", "(AND (>= (COL c) 3) (NOT (<= (COL d) 4)))");
    }

    // folder_predicate:
    // "IN_FOLDER(foo,'ID123')" -> (IN_FOLDER foo 'ID123')
    @Test
    public void folder_predicate1() throws Exception {
        testParser("folder_predicate", "IN_FOLDER(foo,'ID123')", "(IN_FOLDER foo 'ID123')");
    }

    // "IN_FOLDER('ID123')" -> (IN_FOLDER 'ID123')
    @Test
    public void folder_predicate2() throws Exception {
        testParser("folder_predicate", "IN_FOLDER('ID123')", "(IN_FOLDER 'ID123')");
    }

    // "IN_TREE(foo,'ID123')" -> (IN_TREE foo 'ID123')
    @Test
    public void folder_predicate3() throws Exception {
        testParser("folder_predicate", "IN_TREE(foo,'ID123')", "(IN_FOLDER 'ID123')");
    }

    // "IN_TREE('ID123')" -> (IN_TREE 'ID123')
    @Test
    public void folder_predicate4() throws Exception {
        testParser("folder_predicate", "IN_TREE('ID123')", " (IN_TREE 'ID123')");
    }

    @Test
    public void folder_predicate() throws Exception {
        testParser("search_condition", "id456 LIKE 'Foo%' AND IN_FOLDER('abc')",
                "(AND (LIKE 'id456' 'Foo%') (IN_FOLDER 'abc')");
    }

    // text_search_predicate:
    // "CONTAINS('foo')" -> (CONTAINS 'foo')
    @Test
    public void text_search_predicate1() throws Exception {
        testParser("text_search_predicate", "CONTAINS('foo')", "(CONTAINS 'foo')");
    }

    // "CONTAINS(bar, 'foo')" -> (CONTAINS bar 'foo')
    @Test
    public void text_search_predicate2() throws Exception {
        testParser("text_search_predicate", "CONTAINS(bar, 'foo')", "(CONTAINS bar 'foo')");
    }

    // where_clause:
    // "WHERE foo = 1" -> (WHERE (= (COL foo) 1))
    @Test
    public void test_where_clause1() throws Exception {
        testParser("where_clause", "WHERE foo = 1", "(WHERE (= (COL foo) 1))");
    }

    // "WHERE a = 1 AND b <> 2 OR c >= 3" -> (WHERE (OR (AND (= (COL a) 1) (<>
    // (COL b) 2)) (>= (COL c) 3)))
    @Test
    public void test_where_clause2() throws Exception {
        testParser("where_clause", "WHERE a = 1 AND b <> 2 OR c >= 3",
                "(WHERE (OR (AND (= (COL a) 1) (<> (COL b) 2)) (>= (COL c) 3)))");
    }

    // "WHERE a = 1 AND b <> 2 OR c >= 3 AND d <= 4" -> (WHERE (OR (AND (= (COL
    // a) 1) (<> (COL b) 2)) (AND (>= (COL c) 3) (<= (COL d) 4))))
    @Test
    public void test_where_clause3() throws Exception {
        testParser("where_clause", "WHERE a = 1 AND b <> 2 OR c >= 3 AND d <= 4",
                "(WHERE (OR (AND (= (COL a) 1) (<> (COL b) 2)) (AND (>= (COL c) 3) (<= (COL d) 4))))");
    }

    // "WHERE a = 1 AND b <> 2 OR c >= 3 AND NOT d <= 4" -> (WHERE (OR (AND (=
    // (COL a) 1) (<> (COL b) 2)) (AND (>= (COL c) 3) (NOT (<= (COL d) 4)))))
    @Test
    public void test_where_clause4() throws Exception {
        testParser("where_clause", "WHERE a = 1 AND b <> 2 OR c >= 3 AND NOT d <= 4",
                "(WHERE (OR (AND (= (COL a) 1) (<> (COL b) 2)) (AND (>= (COL c) 3) (NOT (<= (COL d) 4)))))");
    }

    // query:
    // "SELECT * FROM Document" -> (SELECT * (FROM (TABLE Document)))
    @Test
    public void test_query1() throws Exception {
        testParser("query", "SELECT * FROM Document", "(SELECT * (FROM (TABLE Document)))");
    }

    // "SELECT a, b, c FROM Document" -> (SELECT (SEL_LIST (COL a) (COL b) (COL
    // c)) (FROM (TABLE Document)))
    @Test
    public void test_query2() throws Exception {
        testParser("query", "SELECT a, b, c FROM Document",
                "(SELECT (SEL_LIST (COL a) (COL b) (COL c)) (FROM (TABLE Document)))");
    }

    // "SELECT a, b FROM Document ORDER BY a, b" -> (SELECT (SEL_LIST (COL a)
    // (COL b)) (FROM (TABLE Document)) (ORDER_BY (COL a) ASC (COL b) ASC))
    @Test
    public void test_query3() throws Exception {
        testParser("query", "SELECT a, b FROM Document ORDER BY a, b",
                "(SELECT (SEL_LIST (COL a) (COL b)) (FROM (TABLE Document)) (ORDER_BY (COL a) ASC (COL b) ASC))");
    }

    // where_clause:
    // "WHERE IN_TREE('ID00093854763') AND ('SMITH' = ANY AUTHORS)" -> (WHERE
    // (AND (IN_TREE 'ID00093854763') (EQ_ANY 'SMITH' (COL AUTHORS))))
    @Test
    public void test_where_clause5() throws Exception {
        testParser("where_clause", "WHERE IN_TREE('ID00093854763') AND ('SMITH' = ANY AUTHORS)",
                "(WHERE (AND (IN_TREE 'ID00093854763') (EQ_ANY 'SMITH' (COL AUTHORS))))");
    }

    // query:
    //
    // <<
    // SELECT * FROM Document WHERE foo = 1
    // >> -> (SELECT * (FROM (TABLE Document)) (WHERE (= (COL foo) 1)))
    @Test
    public void query1() throws Exception {
        testParser("query", "SELECT * FROM Document WHERE foo = 1",
                "(SELECT * (FROM (TABLE Document)) (WHERE (= (COL foo) 1)))");
    }

    // Examples from older versions of the specs.

    // <<
    // SELECT * FROM WHITE_PAPER
    // >> -> (SELECT * (FROM (TABLE WHITE_PAPER)))
    @Test
    public void query2() throws Exception {
        testParser("query", "SELECT * FROM WHITE_PAPER", "(SELECT * (FROM (TABLE WHITE_PAPER)))");
    }

    // <<
    // SELECT TITLE, AUTHORS, DATE
    // FROM WHITE_PAPER
    // WHERE ( IN_TREE('ID00093854763') ) AND ( 'SMITH' = ANY AUTHORS )
    // >> -> (SELECT (SEL_LIST (COL TITLE) (COL AUTHORS) (COL DATE)) (FROM
    // (TABLE WHITE_PAPER)) (WHERE (AND (IN_TREE 'ID00093854763') (EQ_ANY
    // 'SMITH' (COL AUTHORS)))))
    @Test
    public void query3() throws Exception {
        testParser(
                "query",
                "SELECT TITLE, AUTHORS, DATE " + "FROM WHITE_PAPER "
                        + "WHERE ( IN_TREE('ID00093854763') ) AND ( 'SMITH' = ANY AUTHORS )",
                "(SELECT (SEL_LIST (COL TITLE) (COL AUTHORS) (COL DATE)) (FROM (TABLE WHITE_PAPER)) (WHERE (AND (IN_TREE 'ID00093854763') (EQ_ANY 'SMITH' (COL AUTHORS)))))");
    }

    // <<
    // SELECT Y.CLAIM_NUM, X.PROPERTY_ADDRESS, Y.DAMAGE_ESTIMATES
    // FROM POLICY AS X JOIN CLAIMS AS Y ON X.POLICY_NUM = Y.POLICY_NUM
    // WHERE ( 100000 = ANY Y.DAMAGE_ESTIMATES ) AND ( Y.CAUSE NOT LIKE
    // '%Katrina%' )
    // >> OK
    @Test
    public void query4() throws Exception {
        testParserOk("query", "SELECT Y.CLAIM_NUM, X.PROPERTY_ADDRESS, Y.DAMAGE_ESTIMATES "
                + "FROM POLICY AS X JOIN CLAIMS AS Y ON X.POLICY_NUM = Y.POLICY_NUM "
                + "WHERE ( 100000 = ANY Y.DAMAGE_ESTIMATES ) AND ( Y.CAUSE NOT LIKE '%Katrina%' )");
    }

    // <<
    // SELECT Y.CLAIM_NUM, X.PROPERTY_ADDRESS, Y.DAMAGE_ESTIMATES
    // FROM POLICY AS X JOIN CLAIMS AS Y ON X.POLICY_NUM = Y.POLICY_NUM
    // WHERE ( 100000 <= ANY Y.DAMAGE_ESTIMATES ) AND ( Y.CAUSE NOT LIKE
    // '%Katrina%' )
    // >> FAIL
    @Test
    public void query5() throws Exception {
        testParserFail("query", "SELECT Y.CLAIM_NUM, X.PROPERTY_ADDRESS, Y.DAMAGE_ESTIMATES "
                + "FROM POLICY AS X JOIN CLAIMS AS Y ON X.POLICY_NUM = Y.POLICY_NUM "
                + "    WHERE ( 100000 <= ANY Y.DAMAGE_ESTIMATES ) AND ( Y.CAUSE NOT LIKE '%Katrina%' )");
    }

    // <<
    // SELECT *
    // FROM CAR_REVIEW
    // WHERE ANY FEATURES IN ('NAVIGATION SYSTEM', 'SATELLITE RADIO', 'MP3' )
    // >> OK
    @Test
    public void query6() throws Exception {
        testParserOk("query",
                " SELECT * FROM CAR_REVIEW WHERE ANY FEATURES IN ('NAVIGATION SYSTEM', 'SATELLITE RADIO', 'MP3' )");
    }

    // <<
    // SELECT OBJECT_ID, SCORE() AS X, DESTINATION, DEPARTURE_DATES
    // FROM TRAVEL_BROCHURE
    // WHERE ( CONTAINS('CARIBBEAN CENTRAL AMERICA CRUISE TOUR') ) AND
    // ( '2010-1-1' = ANY DEPARTURE_DATES )
    // ORDER BY X DESC
    // >> OK
    @Test
    public void query7() throws Exception {
        testParserOk("query", "SELECT OBJECT_ID, SCORE() AS X, DESTINATION, DEPARTURE_DATES " + "FROM TRAVEL_BROCHURE "
                + "WHERE ( CONTAINS('CARIBBEAN CENTRAL AMERICA CRUISE TOUR') ) AND( '2010-1-1' = ANY DEPARTURE_DATES )");
    }

    @Test
    public void queryEsc1() throws Exception {
        testParserOk("query", "SELECT * FROM cmis:document " + "WHERE cmis:name LIKE 'abc\\%'");
    }

}
