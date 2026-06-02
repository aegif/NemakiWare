package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SolrQueryProcessor#likeToEscapedSolrWildcard} — the
 * char-by-char LIKE→Solr wildcard conversion (security audit follow-up).
 * SQL wildcards must map to Solr wildcards, every other character must be
 * escaped so it can't inject Solr query syntax, and there must be NO
 * sentinel marker a value could collide with.
 */
class SolrQueryProcessorLikeEscapeTest {

    private static String like(String in) {
        return SolrQueryProcessor.likeToEscapedSolrWildcard(in);
    }

    @Test
    void sqlWildcardsMapToSolrWildcards() {
        assertEquals("*", like("%"));
        assertEquals("?", like("_"));
        assertEquals("abc*", like("abc%"));
        assertEquals("a?c", like("a_c"));
    }

    @Test
    void specialCharsAreEscapedNotInjected() {
        // A value trying to break out with a quote / parens / boost etc.
        String out = like("a\" OR x:(1)");
        // The injected syntax characters must be backslash-escaped, so none
        // of them survive as live Solr syntax.
        assertTrue(out.contains("\\\""), "double quote must be escaped: " + out);
        assertTrue(out.contains("\\("), "paren must be escaped: " + out);
        assertTrue(out.contains("\\)"), "paren must be escaped: " + out);
        assertTrue(out.contains("\\:"), "colon must be escaped: " + out);
    }

    @Test
    void noMarkerCollision_valueContainingOldMarkerTextIsLiteral() {
        // The previous implementation used a sentinel like "ZqWILDCARDSTARqZ".
        // A value literally containing that text must NOT turn into a '*'.
        String out = like("ZqWILDCARDSTARqZ");
        assertFalse(out.contains("*"), "literal marker text must not become a wildcard: " + out);
        // And '%' anywhere still becomes a single '*'.
        assertEquals(1, like("ZqWILDCARDSTARqZ%").chars().filter(c -> c == '*').count());
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals("", like(null));
        assertEquals("", like(""));
    }

    @Test
    void plainTextRoundTripsWithoutWildcards() {
        // Letters/digits are not Solr specials → unchanged, no wildcards.
        String out = like("report2026");
        assertEquals("report2026", out);
    }

    @Test
    void whitespaceIsEscapedSoBooleanOperatorsCannotInject() {
        // Whitespace is a token separator (implicit OR) in Lucene, so a
        // value like "foo OR bar" must keep its spaces escaped — it must
        // NOT contain an unescaped space that would split it into clauses.
        for (String in : new String[]{"foo OR bar", "foo AND bar", "foo NOT bar", "foo bar"}) {
            String out = like(in);
            assertFalse(out.matches(".*(?<!\\\\) .*"),
                    "no unescaped space allowed for: " + in + " -> " + out);
        }
    }

    @Test
    void operatorBreakoutAttemptIsNeutralized() {
        // A combined wildcard + operator + injection attempt. Only the SQL
        // '%' may become a LIVE (unescaped) '*'; the literal '*' from "*:*"
        // must be escaped to "\*", spaces escaped, ':' escaped.
        String out = like("ZqWILDCARDSTARqZ OR *:*%");
        // Count live (unescaped) '*': a '*' not immediately preceded by '\'.
        long liveStars = 0;
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '*' && (i == 0 || out.charAt(i - 1) != '\\')) {
                liveStars++;
            }
        }
        assertEquals(1, liveStars,
                "only the SQL % should become a live wildcard: " + out);
        assertFalse(out.matches(".*(?<!\\\\) .*"), "spaces must be escaped: " + out);
        assertTrue(out.contains("\\:"), "colon must be escaped: " + out);
        assertTrue(out.contains("\\*"), "literal '*' must be escaped: " + out);
    }
}
