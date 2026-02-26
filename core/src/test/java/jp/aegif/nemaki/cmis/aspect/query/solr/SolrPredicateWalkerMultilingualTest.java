package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.antlr.runtime.tree.CommonTree;
import org.apache.chemistry.opencmis.server.support.query.TextSearchLexer;
import org.apache.lucene.search.Query;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for SolrPredicateWalker multilingual search (text + text_en dual-index).
 *
 * Verifies that walkTextWord and walkTextPhrase produce Query objects whose
 * toString() output contains both "text:" and "text_en:" field queries.
 *
 * The Query.toString() output is what gets sent to Solr via SolrQuery.setQuery(),
 * where Solr's query parser re-parses it and applies each field's configured
 * analyzer (text_ja for "text", English stemmer for "text_en").
 */
public class SolrPredicateWalkerMultilingualTest {

    private SolrPredicateWalker walker;
    private Method walkTextWordMethod;
    private Method walkTextPhraseMethod;

    @Before
    public void setUp() throws Exception {
        // Create walker with null dependencies (only walkTextWord/walkTextPhrase are tested,
        // which don't use repositoryId, queryObject, solrUtil, or contentService)
        walker = new SolrPredicateWalker(null, null, null, null);

        // Access private methods via reflection
        walkTextWordMethod = SolrPredicateWalker.class.getDeclaredMethod("walkTextWord", org.antlr.runtime.tree.Tree.class);
        walkTextWordMethod.setAccessible(true);

        walkTextPhraseMethod = SolrPredicateWalker.class.getDeclaredMethod("walkTextPhrase", org.antlr.runtime.tree.Tree.class);
        walkTextPhraseMethod.setAccessible(true);
    }

    /**
     * Create a mock Tree node with TEXT_SEARCH_WORD_LIT type.
     */
    private CommonTree createWordNode(String text) {
        CommonTree node = new CommonTree();
        node.token = new org.antlr.runtime.CommonToken(TextSearchLexer.TEXT_SEARCH_WORD_LIT, text);
        return node;
    }

    /**
     * Create a mock Tree node with TEXT_SEARCH_PHRASE_STRING_LIT type.
     */
    private CommonTree createPhraseNode(String text) {
        CommonTree node = new CommonTree();
        node.token = new org.antlr.runtime.CommonToken(TextSearchLexer.TEXT_SEARCH_PHRASE_STRING_LIT, text);
        return node;
    }

    // ========================================
    // walkTextWord tests
    // ========================================

    @Test
    public void testWalkTextWordProducesDualFieldQuery() throws Exception {
        CommonTree node = createWordNode("hello");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Query should contain text: field", queryStr.contains("text:"));
        assertTrue("Query should contain text_en: field", queryStr.contains("text_en:"));
    }

    @Test
    public void testWalkTextWordContainsSearchTerm() throws Exception {
        CommonTree node = createWordNode("running");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        // Both fields should contain the search term in quotes
        assertTrue("text field should contain the term",
            queryStr.contains("text:\"running\""));
        assertTrue("text_en field should contain the term",
            queryStr.contains("text_en:\"running\""));
    }

    @Test
    public void testWalkTextWordJapaneseText() throws Exception {
        CommonTree node = createWordNode("検索");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Query should contain text: with Japanese term",
            queryStr.contains("text:\"検索\""));
        assertTrue("Query should contain text_en: with Japanese term",
            queryStr.contains("text_en:\"検索\""));
    }

    @Test
    public void testWalkTextWordMixedLanguage() throws Exception {
        CommonTree node = createWordNode("test検索");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Query should search both fields for mixed text",
            queryStr.contains("text:\"test検索\""));
        assertTrue("Query should search both fields for mixed text",
            queryStr.contains("text_en:\"test検索\""));
    }

    @Test
    public void testWalkTextWordUnderscoreTerm() throws Exception {
        // Regression: underscores should be preserved in phrase search
        CommonTree node = createWordNode("TEST_KEYWORD_123");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Underscore term should be in text: field",
            queryStr.contains("text:\"TEST_KEYWORD_123\""));
        assertTrue("Underscore term should be in text_en: field",
            queryStr.contains("text_en:\"TEST_KEYWORD_123\""));
    }

    // ========================================
    // walkTextPhrase tests
    // ========================================

    @Test
    public void testWalkTextPhraseProducesDualFieldQuery() throws Exception {
        CommonTree node = createPhraseNode("'hello world'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Phrase query should contain text: field", queryStr.contains("text:"));
        assertTrue("Phrase query should contain text_en: field", queryStr.contains("text_en:"));
    }

    @Test
    public void testWalkTextPhraseStripsQuotesAndWraps() throws Exception {
        CommonTree node = createPhraseNode("'hello world'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);
        String queryStr = query.toString();

        // Single quotes should be stripped, double quotes should wrap
        assertTrue("text field should contain double-quoted phrase",
            queryStr.contains("text:\"hello world\""));
        assertTrue("text_en field should contain double-quoted phrase",
            queryStr.contains("text_en:\"hello world\""));
    }

    @Test
    public void testWalkTextPhraseJapanese() throws Exception {
        CommonTree node = createPhraseNode("'日本語フレーズ'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Japanese phrase should be in text: field",
            queryStr.contains("text:\"日本語フレーズ\""));
        assertTrue("Japanese phrase should be in text_en: field",
            queryStr.contains("text_en:\"日本語フレーズ\""));
    }

    @Test
    public void testWalkTextPhraseMixedLanguage() throws Exception {
        CommonTree node = createPhraseNode("'test document テスト'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Mixed phrase should be in text: field",
            queryStr.contains("text:\"test document テスト\""));
        assertTrue("Mixed phrase should be in text_en: field",
            queryStr.contains("text_en:\"test document テスト\""));
    }

    // ========================================
    // Query structure tests
    // ========================================

    @Test
    public void testWalkTextWordReturnsBooleanQueryWithShouldClauses() throws Exception {
        CommonTree node = createWordNode("test");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);

        // BooleanQuery with SHOULD clauses produces space-separated format
        String queryStr = query.toString();
        // Should be: text:"test" text_en:"test"
        // (two SHOULD clauses separated by space = OR semantics in Solr)
        String[] parts = queryStr.split("\\s+");
        assertEquals("Should have exactly 2 clauses", 2, parts.length);
    }

    @Test
    public void testWalkTextPhraseReturnsBooleanQueryWithShouldClauses() throws Exception {
        CommonTree node = createPhraseNode("'multi word phrase'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);

        String queryStr = query.toString();
        // Both text: and text_en: should appear
        int textCount = queryStr.split("text:").length - 1;
        int textEnCount = queryStr.split("text_en:").length - 1;
        assertTrue("Should have at least one text: clause", textCount >= 1);
        assertEquals("Should have exactly one text_en: clause", 1, textEnCount);
    }
}
