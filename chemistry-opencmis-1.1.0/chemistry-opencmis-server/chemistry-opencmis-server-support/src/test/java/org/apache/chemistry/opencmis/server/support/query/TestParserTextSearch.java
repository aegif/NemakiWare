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

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestParserTextSearch extends AbstractParserTest{

    //private static final Logger log = LoggerFactory.getLogger(TestParserTextSearch.class);

    @Before
    public void setUp() {
        super.setUp(TextSearchLexer.class, TextSearchParser.class, null, "CmisBaseLexer");
    }

    @Override
    @After
    public void tearDown() {
        super.tearDown();
    }

    // full text search parser
    //    OR:
    //    <<
    //    OR
    //    >> OK
    @Test
    public void testOR1() {
      testLexerOk("OR", "OR");
    }
    
    //
    //    TEXT_SEARCH_WORD_LIT:
    //    <<
    //    abc
    //    >> OK
    @Test
    public void testTEXT_SEARCH_WORD_LIT2() {
      testLexerOk("TEXT_SEARCH_WORD_LIT", "abc");
    }
    
    //
    //    <<
    //    ab c
    //    >> FAIL
    @Test
    public void testTEXT_SEARCH_WORD_LIT3() {
        testLexerFail("TEXT_SEARCH_WORD_LIT", "ab c");
    }
    
    //
    //    <<
    //    'abc' 
    //    >> FAIL
    @Test
    public void testTEXT_SEARCH_WORD_LIT4() {
        testLexerFail("TEXT_SEARCH_WORD_LIT", "\'abc\'");
    }
    
    //
    //    <<
    //    ab\'c
    //    >> OK
    @Test
    public void testWordLiteralWithSingleQuote() {
        testLexerOk("TEXT_SEARCH_WORD_LIT", "ab\\'c");
        testLexerFail("TEXT_SEARCH_WORD_LIT", "ab'c");
    }
    
    //
    //    <<
    //    "ab\\c"
    //    >> OK
    @Test
    public void testPhraseWithBackslash() {
      testLexerOk("TEXT_SEARCH_PHRASE_STRING_LIT", "\"ab\\\\c\"");
    }
    
    //
    //    phrase:
    //
    //    <<
    //    \'abc\'
    //    >> FAIL
    @Test
    public void testPhrase1() {
        testParserFail("phrase", "\\'abc\\'");
    }
    
    //
    //    <<
    //    "abc"
    //    >> OK
    @Test
    public void testPhrase2() {
        testParserOk("phrase", "\"abc\"");
    }
    
    //
    //    <<
    //    "'abc'"
    //    >> OK
    @Test
    public void testPhrase3() {
      testParserFail("phrase", "'abc'");
      testParserOk("phrase", "\"\\'abc\\'\"");
    }
    
    //
    //    <<
    //    "abc def"
    //    >> OK
    @Test
    public void testPhrase4() {
      testParserOk("phrase", "\"abc def\"");
    }
    
    //
    //    <<
    //    "ab\\c"
    //    >> OK
    @Test
    public void testPhrase6() {
      testParserOk("phrase", "\"ab\\\\c\"");
    }
    
    //
    //    <<
    //    "ab\c"
    //    >> FAIL
    @Test
    public void testPhraseEscapedBackslash() {
        testParserFail("phrase", "\"ab\\c\"");
        testParserOk("phrase", "\"ab\\\\c\"");
    }
    
    //
    //    <<
    //    "ab\\\c"
    //    >> FAIL
    @Test
    public void testPhrase8() {
        testParserFail("phrase", "\"ab\\\\\\c\"");
    }
    
    //
    //    <<
    //    "ab\'c"
    //    >> OK
    @Test
    public void testPhraseSingleQuote() {
        assertEquals("ab'c", "ab\'c");
        testParserOk("phrase", "\"ab\\'c\"");
        testParserFail("phrase", "\"ab'c\""); //!!
    }
    
    //
    //    <<
    //    "ab\-c"
    //    >> OK
    @Test
    public void testPhraseHyphen() {
        testParserOk("phrase", "\"ab\\-c\"");
        testParserFail("phrase", "\"ab-c\"");     // !!!
    }
    
    //
    //    << 
    //    "abc*"
    //    "abc\*"
    //    "abc?"
    //    "abc\?"
    //    >> OK
    @Test
    public void testPhraseWildcards() {
      testParserOk("phrase", "\"abc*\"");
      testParserOk("phrase", "\"abc\\*\"");
      testParserOk("phrase", "\"abc?\"");
      testParserOk("phrase", "\"abc\\?\"");
    }

    //
    //    <<
    //    "abc def"
    //    >> OK
    @Test
    public void testPhrase10() {
      testParserOk("phrase", "\"abc def\"");
    }
    
    //
    //    <<
    //    "\'abc\'"
    //    >> OK
    @Test
    public void testPhrase11() {
      testParserOk("phrase", "\"\\'abc\\'\"");
    }
    
    //
    //    <<
    //    "abc AND def"
    //    >> OK
    @Test
    public void testPhrase12() {
      testParserOk("phrase", "\"abc AND def\"");
    }
    
    //
    //    << 
    //    "AND"
    //    >> OK
    @Test
    public void testPhrase13() {
      testParserOk("phrase", "\"AND\"");
    }
    
    //
    //    word:
    //
    //    << 
    //    abc
    //    >> OK
    @Test
    public void testWord1() {
      testParserOk("word", "abc");
    }
    
    //
    //    << 
    //    312#+!&abc
    //    >> OK
    @Test
    public void testWord2() {
      testParserOk("word", "312#+!&abc");
    }
    
    //
    //    << 
    //    \'abc\'
    //    >> OK
    @Test
    public void testWord3() {
      testParserOk("word", "\\'abc\\'");
    }
    
    //
    //    << 
    //    'abc'
    //    >> FAIL
    @Test
    public void testWord4() {
        testParserFail("word", "'abc'");
    }    
    
    //
    //    <<
    //    ab\-c
    //    >> OK
    @Test
    public void testWord6() {
        testParserOk("word", "ab\\-c");
    }
    
    //
    //    <<
    //    ab\\c
    //    >> OK
    @Test
    public void testWord7() {
        testParserOk("word", "ab\\\\c");
    }
    
    //
    //    <<
    //    ab\'c
    //    >> OK
    @Test
    public void testWord8() {
        testParserOk("word", "ab\\'c");
    }
    
    //
    //    <<
    //    OR
    //    >> FAIL
    @Test
    public void testWord9() {
        testParserFail("word", "OR");
    }
    
    //
    //    <<
    //    AND
    //    >> FAIL
    @Test
    public void testWord10() {
        testParserFail("word", "AND");
    }
    
    //
    //
    //    term:
    //
    //    <<
    //    -abc
    //    >> OK
    @Test
    public void testWord11() {
      testParserOk("term", "-abc");
    }
    
    //
    //    <<
    //    abc
    //    >> OK
    @Test
    public void testWord12() {
      testParserOk("term", "abc");
    }
    
    //
    //    <<
    //    'abc def'
    //    >> OK
    @Test
    public void testWord13() {
      testParserOk("term", "\"abc def\"");
    }
    
    //
    //    <<
    //    -'abc def'
    //    >> OK
    @Test
    public void testWord14() {
      testParserOk("term", "-\"abc def\"");
    }
    
    //
    //    conjunct:
    //
    //    <<
    //    abc def
    //    >> OK
    @Test
    public void testConjunct1() {
      testParserOk("conjunct", "abc def");
    }
    
    //
    //    <<
    //    abc AND def
    //    >> OK
    @Test
    public void testConjunct2() {
      testParserOk("conjunct", "abc AND def");
    }
    
    //
    //    <<
    //    abc AND def ghi John\'s
    //    >> OK
    @Test
    public void testConjunct3() {
      testParserOk("conjunct", "abc AND def ghi John\\'s");
    }
    
    //
    //    text_search_expression:
    //
    //    <<
    //    cat mouse dog
    //    >> OK
    @Test
    public void testTextSearchExpression1() {
      testParserOk("text_search_expression", "cat mouse dog");
    }
    
    //
    //    <<
    //    cat AND mouse AND dog
    //    >> OK
    @Test
    public void testTextSearchExpression2() {
      testParserOk("text_search_expression", "cat AND mouse AND dog");
    }
    
    //
    //    <<
    //    cat OR mouse OR dog
    //    >> OK
    @Test
    public void testTextSearchExpression3() {
      testParserOk("text_search_expression", "cat OR mouse OR dog");
    }
    
    //
    //    <<
    //    cat mouse OR dog
    //    >> OK
    @Test
    public void testTextSearchExpression4() {
      testParserOk("text_search_expression", "cat mouse OR dog");
    }
    
    //
    //    <<
    //    cat AND mouse OR dog AND John\'s
    //    >> OK
    @Test
    public void testTextSearchExpression5() {
      testParserOk("text_search_expression", "cat AND mouse OR dog AND John\\'s");
    }
    
    //
    //    <<
    //    "cat AND mouse"
    //    >> OK
    @Test
    public void testTextSearchExpression6() {
      testParserOk("text_search_expression", "\"cat AND mouse\"");
    }
    
    //
    //    <<
    //    "text search expression"
    //    >> OK
    @Test
    public void testTextSearchExpression7() {
      testParserOk("text_search_expression", "\"text search expression\"");
    }
    
    //
    //    <<
    //    "John\'s presentation"
    //    >> OK
    @Test
    public void testTextSearchExpression8() {
      testParserOk("text_search_expression", "\"John\\'s presentation\"");
    }
    
    //
    //    <<
    //    "John\\'s presentation"
    //    >> FAIL
    @Test
    public void testTextSearchExpression9() {
        testParserFail("text_search_expression", "\"John\\\\'s presentation\"");
    }
    
    //
    //    <<
    //    A\-1
    //    >> OK
    @Test
    public void testTextSearchExpression10() {
      testParserOk("text_search_expression", "A\\-1");
    }
    
    //
    //    <<
    //    "c:\\My Documents"
    //    >> OK
    @Test
    public void testTextSearchExpression11() {
      testParserOk("text_search_expression", "\"c:\\\\My Documents\"");
    }
    
    //
    //    <<
    //    "c:\\\My Documents"
    //    >> FAIL
    @Test
    public void testTextSearchExpression13() {
        testParserFail("text_search_expression", "\"c:\\\\\\My Documents\"");
    }
    
    //
    //    <<
    //    "c:\My Documents"
    //    >> FAIL
    @Test
    public void testTextSearchExpression14() {
      testParserFail("text_search_expression", "\"c:\\My Documents\"");
    }
    
    //
    //    <<
    //    c:\My Documents
    //    >> FAIL
    @Test
    public void testTextSearchExpression15() {
      testParserFail("text_search_expression", "c:\\My Documents");
    }
    
    @Test
    public void testTextSearchException() {
    	testParserFail("text_search_expression", "AND OR");
    }
}
