package jp.aegif.nemaki.rag.util;

import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Unit tests for SolrQuerySanitizer.
 *
 * Tests escaping of Solr special characters to prevent query injection attacks.
 * Solr special characters: + - && || ! ( ) { } [ ] ^ " ~ * ? : \ /
 */
public class SolrQuerySanitizerTest {

    // ========== escape() tests ==========

    @Test
    public void testEscapeNull() {
        assertEquals("", SolrQuerySanitizer.escape(null));
    }

    @Test
    public void testEscapeEmpty() {
        assertEquals("", SolrQuerySanitizer.escape(""));
    }

    @Test
    public void testEscapeNoSpecialChars() {
        assertEquals("hello world", SolrQuerySanitizer.escape("hello world"));
    }

    @Test
    public void testEscapePlus() {
        assertEquals("foo\\+bar", SolrQuerySanitizer.escape("foo+bar"));
    }

    @Test
    public void testEscapeMinus() {
        assertEquals("foo\\-bar", SolrQuerySanitizer.escape("foo-bar"));
    }

    @Test
    public void testEscapeDoubleAmpersand() {
        // && is two characters, each should be escaped
        assertEquals("foo\\&\\&bar", SolrQuerySanitizer.escape("foo&&bar"));
    }

    @Test
    public void testEscapeDoublePipe() {
        // || is two characters, each should be escaped
        assertEquals("foo\\|\\|bar", SolrQuerySanitizer.escape("foo||bar"));
    }

    @Test
    public void testEscapeSingleAmpersand() {
        // Single & should also be escaped (conservative approach)
        assertEquals("foo\\&bar", SolrQuerySanitizer.escape("foo&bar"));
    }

    @Test
    public void testEscapeSinglePipe() {
        // Single | should also be escaped (conservative approach)
        assertEquals("foo\\|bar", SolrQuerySanitizer.escape("foo|bar"));
    }

    @Test
    public void testEscapeExclamation() {
        assertEquals("\\!important", SolrQuerySanitizer.escape("!important"));
    }

    @Test
    public void testEscapeParentheses() {
        assertEquals("\\(test\\)", SolrQuerySanitizer.escape("(test)"));
    }

    @Test
    public void testEscapeBraces() {
        assertEquals("\\{range\\}", SolrQuerySanitizer.escape("{range}"));
    }

    @Test
    public void testEscapeBrackets() {
        assertEquals("\\[array\\]", SolrQuerySanitizer.escape("[array]"));
    }

    @Test
    public void testEscapeCaret() {
        assertEquals("boost\\^2", SolrQuerySanitizer.escape("boost^2"));
    }

    @Test
    public void testEscapeDoubleQuote() {
        assertEquals("say \\\"hello\\\"", SolrQuerySanitizer.escape("say \"hello\""));
    }

    @Test
    public void testEscapeTilde() {
        assertEquals("fuzzy\\~2", SolrQuerySanitizer.escape("fuzzy~2"));
    }

    @Test
    public void testEscapeAsterisk() {
        assertEquals("wild\\*card", SolrQuerySanitizer.escape("wild*card"));
    }

    @Test
    public void testEscapeQuestionMark() {
        assertEquals("single\\?char", SolrQuerySanitizer.escape("single?char"));
    }

    @Test
    public void testEscapeColon() {
        assertEquals("field\\:value", SolrQuerySanitizer.escape("field:value"));
    }

    @Test
    public void testEscapeBackslash() {
        assertEquals("path\\\\to\\\\file", SolrQuerySanitizer.escape("path\\to\\file"));
    }

    @Test
    public void testEscapeForwardSlash() {
        assertEquals("path\\/to\\/file", SolrQuerySanitizer.escape("path/to/file"));
    }

    @Test
    public void testEscapeMultipleSpecialChars() {
        assertEquals("\\(test\\) \\&\\& \\[value\\]", SolrQuerySanitizer.escape("(test) && [value]"));
    }

    @Test
    public void testEscapeSolrInjectionAttempt() {
        // Attempt to break out of a query and add OR clause
        // Note: '=' is NOT a Solr special character, so it's not escaped
        String malicious = "\") OR 1=1 OR (\"";
        String escaped = SolrQuerySanitizer.escape(malicious);
        assertEquals("\\\"\\) OR 1=1 OR \\(\\\"", escaped);
    }

    @Test
    public void testEscapeAllSpecialChars() {
        // Test all special characters at once
        String allSpecial = "+-&&||!(){}[]^\"~*?:\\/";
        String escaped = SolrQuerySanitizer.escape(allSpecial);
        assertEquals("\\+\\-\\&\\&\\|\\|\\!\\(\\)\\{\\}\\[\\]\\^\\\"\\~\\*\\?\\:\\\\\\/", escaped);
    }

    // ========== escapeAndQuote() tests ==========

    @Test
    public void testEscapeAndQuoteNull() {
        assertEquals("\"\"", SolrQuerySanitizer.escapeAndQuote(null));
    }

    @Test
    public void testEscapeAndQuoteSimple() {
        assertEquals("\"hello world\"", SolrQuerySanitizer.escapeAndQuote("hello world"));
    }

    @Test
    public void testEscapeAndQuoteWithSpecialChars() {
        assertEquals("\"test\\+value\"", SolrQuerySanitizer.escapeAndQuote("test+value"));
    }

    @Test
    public void testEscapeAndQuoteWithInternalQuotes() {
        assertEquals("\"say \\\"hello\\\"\"", SolrQuerySanitizer.escapeAndQuote("say \"hello\""));
    }

    // ========== sanitizeId() tests ==========

    @Test(expected = IllegalArgumentException.class)
    public void testSanitizeIdNull() {
        SolrQuerySanitizer.sanitizeId(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSanitizeIdEmpty() {
        SolrQuerySanitizer.sanitizeId("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSanitizeIdWhitespaceOnly() {
        SolrQuerySanitizer.sanitizeId("   ");
    }

    @Test
    public void testSanitizeIdSimple() {
        assertEquals("abc123", SolrQuerySanitizer.sanitizeId("abc123"));
    }

    @Test
    public void testSanitizeIdWithSpecialChars() {
        assertEquals("doc\\-123\\:v1", SolrQuerySanitizer.sanitizeId("doc-123:v1"));
    }

    @Test
    public void testSanitizeIdTrimsWhitespace() {
        assertEquals("abc123", SolrQuerySanitizer.sanitizeId("  abc123  "));
    }

    // ========== containsSpecialChars() tests ==========

    @Test
    public void testContainsSpecialCharsNull() {
        assertFalse(SolrQuerySanitizer.containsSpecialChars(null));
    }

    @Test
    public void testContainsSpecialCharsEmpty() {
        assertFalse(SolrQuerySanitizer.containsSpecialChars(""));
    }

    @Test
    public void testContainsSpecialCharsNoSpecial() {
        assertFalse(SolrQuerySanitizer.containsSpecialChars("hello world 123"));
    }

    @Test
    public void testContainsSpecialCharsWithPlus() {
        assertTrue(SolrQuerySanitizer.containsSpecialChars("hello+world"));
    }

    @Test
    public void testContainsSpecialCharsWithDoubleAmpersand() {
        assertTrue(SolrQuerySanitizer.containsSpecialChars("foo&&bar"));
    }

    @Test
    public void testContainsSpecialCharsWithColon() {
        assertTrue(SolrQuerySanitizer.containsSpecialChars("field:value"));
    }

    // ========== Security-focused tests ==========

    @Test
    public void testInjectionViaRepositoryIdBypass() {
        // Attempt to bypass repository filter: ") OR repository_id:other OR ("
        String malicious = "\") OR repository_id:other OR (\"";
        String escaped = SolrQuerySanitizer.escape(malicious);
        // Should contain escaped characters
        assertTrue(escaped.contains("\\\""));
        assertTrue(escaped.contains("\\)"));
        assertTrue(escaped.contains("\\("));
        assertTrue(escaped.contains("\\:"));
    }

    @Test
    public void testInjectionViaNegation() {
        // Attempt: "-repository_id:bedroom" to exclude all from bedroom
        String malicious = "-repository_id:bedroom";
        String escaped = SolrQuerySanitizer.escape(malicious);
        assertEquals("\\-repository_id\\:bedroom", escaped);
    }

    @Test
    public void testInjectionViaWildcard() {
        // Attempt: "*:*" to match everything
        String malicious = "*:*";
        String escaped = SolrQuerySanitizer.escape(malicious);
        assertEquals("\\*\\:\\*", escaped);
    }

    @Test
    public void testInjectionViaFunctionQuery() {
        // Attempt function query injection
        String malicious = "_val_:\"recip(ms(NOW,timestamp),3.16e-11,1,1)\"";
        String escaped = SolrQuerySanitizer.escape(malicious);
        // Should escape the colons and quotes
        assertTrue(escaped.contains("\\:"));
        assertTrue(escaped.contains("\\\""));
    }
}
