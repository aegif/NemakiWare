package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.antlr.runtime.tree.CommonTree;
import org.apache.chemistry.opencmis.server.support.query.TextSearchLexer;
import org.apache.lucene.search.Query;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for SolrPredicateWalker multilingual search with script-based
 * field selection:
 *   CJK only → text (Kuromoji),
 *   Latin only / mixed → text + text_en (both).
 *
 * text_en は title/description/keywords/content のみ copyField されるため、
 * author/content_type/resourcename/url のヒットを保証するには text フィールドも必須。
 */
public class SolrPredicateWalkerMultilingualTest {

    private SolrPredicateWalker walker;
    private Method walkTextWordMethod;
    private Method walkTextPhraseMethod;
    private Method detectScriptMethod;

    @Before
    public void setUp() throws Exception {
        walker = new SolrPredicateWalker(null, null, null, null);

        walkTextWordMethod = SolrPredicateWalker.class.getDeclaredMethod("walkTextWord", org.antlr.runtime.tree.Tree.class);
        walkTextWordMethod.setAccessible(true);

        walkTextPhraseMethod = SolrPredicateWalker.class.getDeclaredMethod("walkTextPhrase", org.antlr.runtime.tree.Tree.class);
        walkTextPhraseMethod.setAccessible(true);

        detectScriptMethod = SolrPredicateWalker.class.getDeclaredMethod("detectScript", String.class);
        detectScriptMethod.setAccessible(true);
    }

    private CommonTree createWordNode(String text) {
        CommonTree node = new CommonTree();
        node.token = new org.antlr.runtime.CommonToken(TextSearchLexer.TEXT_SEARCH_WORD_LIT, text);
        return node;
    }

    private CommonTree createPhraseNode(String text) {
        CommonTree node = new CommonTree();
        node.token = new org.antlr.runtime.CommonToken(TextSearchLexer.TEXT_SEARCH_PHRASE_STRING_LIT, text);
        return node;
    }

    // ========================================
    // detectScript tests
    // ========================================

    @Test
    public void testDetectScriptCjk() throws Exception {
        Object result = detectScriptMethod.invoke(null, "日本語");
        assertEquals("CJK_ONLY", result.toString());
    }

    @Test
    public void testDetectScriptLatin() throws Exception {
        Object result = detectScriptMethod.invoke(null, "english");
        assertEquals("LATIN_ONLY", result.toString());
    }

    @Test
    public void testDetectScriptMixed() throws Exception {
        Object result = detectScriptMethod.invoke(null, "hello世界");
        assertEquals("MIXED", result.toString());
    }

    @Test
    public void testDetectScriptSymbolsOnly() throws Exception {
        Object result = detectScriptMethod.invoke(null, "---");
        assertEquals("MIXED", result.toString());
    }

    /**
     * 補助平面CJK（拡張B: U+20000+）のサロゲートペア文字が正しくCJKと判定されること。
     * 𠀋 (U+2000B) は CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B に属する。
     */
    @Test
    public void testDetectScriptCjkExtensionB() throws Exception {
        // U+2000B = サロゲートペア \uD840\uDC0B
        String extB = "\uD840\uDC0B";
        Object result = detectScriptMethod.invoke(null, extB);
        assertEquals("Supplementary CJK (Extension B) should be CJK_ONLY",
            "CJK_ONLY", result.toString());
    }

    /**
     * 補助平面CJK + Latin の混在がMIXEDと判定されること。
     */
    @Test
    public void testDetectScriptCjkExtensionBMixed() throws Exception {
        String extBMixed = "abc\uD840\uDC0B";
        Object result = detectScriptMethod.invoke(null, extBMixed);
        assertEquals("Supplementary CJK + Latin should be MIXED",
            "MIXED", result.toString());
    }

    /**
     * CJK互換漢字補助 (U+2F800-U+2FA1F) が正しくCJKと判定されること。
     * 𠀀 (U+2F800) は CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT に属する。
     */
    @Test
    public void testDetectScriptCjkCompatibilitySupplement() throws Exception {
        // U+2F800 = サロゲートペア \uD87E\uDC00
        String compatSupp = "\uD87E\uDC00";
        Object result = detectScriptMethod.invoke(null, compatSupp);
        assertEquals("CJK Compatibility Supplement should be CJK_ONLY",
            "CJK_ONLY", result.toString());
    }

    // ========================================
    // walkTextWord tests — Latin only
    // Latin → text + text_en の両方を検索（author等はtextのみにcopyField）
    // ========================================

    @Test
    public void testWalkTextWordLatinOnly() throws Exception {
        CommonTree node = createWordNode("hello");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Latin-only word should use text_en field for stemming",
            queryStr.contains("text_en:\"hello\""));
        assertTrue("Latin-only word should also use text field for author/url/etc coverage",
            queryStr.contains("text:\"hello\""));
    }

    @Test
    public void testWalkTextWordLatinOnlyRunning() throws Exception {
        CommonTree node = createWordNode("running");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Latin-only word should use text_en field for stemming",
            queryStr.contains("text_en:\"running\""));
        assertTrue("Latin-only word should also use text field for author/url/etc coverage",
            queryStr.contains("text:\"running\""));
    }

    // ========================================
    // walkTextWord tests — CJK only
    // CJK → text (Kuromoji) のみ
    // ========================================

    @Test
    public void testWalkTextWordCjkOnly() throws Exception {
        CommonTree node = createWordNode("検索");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("CJK-only word should use text field",
            queryStr.contains("text:\"検索\""));
        assertFalse("CJK-only word should NOT use text_en field",
            queryStr.contains("text_en:"));
    }

    // ========================================
    // walkTextWord tests — Mixed
    // ========================================

    @Test
    public void testWalkTextWordMixed() throws Exception {
        CommonTree node = createWordNode("test検索");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Mixed word should search text field",
            queryStr.contains("text:\"test検索\""));
        assertTrue("Mixed word should search text_en field",
            queryStr.contains("text_en:\"test検索\""));
    }

    // ========================================
    // walkTextWord tests — Underscore (Latin)
    // ========================================

    @Test
    public void testWalkTextWordUnderscore() throws Exception {
        CommonTree node = createWordNode("TEST_KEYWORD_123");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Underscore term (Latin) should use text_en field",
            queryStr.contains("text_en:\"TEST_KEYWORD_123\""));
        assertTrue("Underscore term (Latin) should also use text field",
            queryStr.contains("text:\"TEST_KEYWORD_123\""));
    }

    // ========================================
    // walkTextPhrase tests — Latin only
    // ========================================

    @Test
    public void testWalkTextPhraseLatinOnly() throws Exception {
        CommonTree node = createPhraseNode("'hello world'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Latin-only phrase should use text_en field",
            queryStr.contains("text_en:\"hello world\""));
        assertTrue("Latin-only phrase should also use text field",
            queryStr.contains("text:\"hello world\""));
    }

    // ========================================
    // walkTextPhrase tests — CJK only
    // ========================================

    @Test
    public void testWalkTextPhraseCjkOnly() throws Exception {
        CommonTree node = createPhraseNode("'日本語フレーズ'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("CJK-only phrase should use text field",
            queryStr.contains("text:\"日本語フレーズ\""));
        assertFalse("CJK-only phrase should NOT use text_en field",
            queryStr.contains("text_en:"));
    }

    // ========================================
    // walkTextPhrase tests — Mixed
    // ========================================

    @Test
    public void testWalkTextPhraseMixed() throws Exception {
        CommonTree node = createPhraseNode("'test document テスト'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Mixed phrase should search text field",
            queryStr.contains("text:\"test document テスト\""));
        assertTrue("Mixed phrase should search text_en field",
            queryStr.contains("text_en:\"test document テスト\""));
    }

    // ========================================
    // Query structure tests
    // ========================================

    @Test
    public void testWalkTextWordLatinReturnsBooleanQueryWithBothFields() throws Exception {
        // "test" is Latin-only → BooleanQuery with text + text_en (SHOULD)
        CommonTree node = createWordNode("test");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Latin-only word should have text: clause",
            queryStr.contains("text:\"test\""));
        assertTrue("Latin-only word should have text_en: clause",
            queryStr.contains("text_en:\"test\""));
    }

    @Test
    public void testWalkTextWordCjkReturnsSingleTermQuery() throws Exception {
        // "検索" is CJK-only → single TermQuery (text only)
        CommonTree node = createWordNode("検索");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("CJK-only word should have text: clause",
            queryStr.contains("text:\"検索\""));
        assertFalse("CJK-only word should NOT have text_en: clause",
            queryStr.contains("text_en:"));
    }

    @Test
    public void testWalkTextPhraseLatinReturnsBooleanQueryWithBothFields() throws Exception {
        // "multi word phrase" is Latin-only → BooleanQuery with text + text_en
        CommonTree node = createPhraseNode("'multi word phrase'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Latin-only phrase should have text: clause",
            queryStr.contains("text:\"multi word phrase\""));
        assertTrue("Latin-only phrase should have text_en: clause",
            queryStr.contains("text_en:\"multi word phrase\""));
    }

    // ========================================
    // Schema coverage regression tests
    // text フィールドのみに copyField される author/content_type/resourcename/url が
    // Latin 検索でもヒット対象となることを query 構造で確認
    // ========================================

    @Test
    public void testLatinQueryCoversTextFieldForAuthorSearch() throws Exception {
        // author は text にのみ copyField される。
        // Latin-only クエリが text フィールドを含むことで author 検索が可能であることを確認。
        CommonTree node = createWordNode("smith");
        Query query = (Query) walkTextWordMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Latin query must include text field to cover author/content_type/resourcename/url",
            queryStr.contains("text:\"smith\""));
        assertTrue("Latin query should also include text_en for stemming",
            queryStr.contains("text_en:\"smith\""));
    }

    @Test
    public void testLatinPhraseCoversTextFieldForResourcenameSearch() throws Exception {
        // resourcename は text にのみ copyField される。
        CommonTree node = createPhraseNode("'annual report'");
        Query query = (Query) walkTextPhraseMethod.invoke(walker, node);
        String queryStr = query.toString();

        assertTrue("Latin phrase must include text field for resourcename coverage",
            queryStr.contains("text:\"annual report\""));
        assertTrue("Latin phrase should also include text_en for stemming",
            queryStr.contains("text_en:\"annual report\""));
    }
}
