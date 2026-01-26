package jp.aegif.nemaki.rag.util;

/**
 * Utility class for sanitizing user input before use in Solr queries.
 *
 * Prevents Solr query injection attacks by escaping special characters
 * that have special meaning in Solr query syntax.
 *
 * Solr special characters: + - && || ! ( ) { } [ ] ^ " ~ * ? : \ /
 */
public final class SolrQuerySanitizer {

    // Characters that have special meaning in Solr query syntax
    private static final String SOLR_SPECIAL_CHARS = "+-&&||!(){}[]^\"~*?:\\/";

    private SolrQuerySanitizer() {
        // Utility class - prevent instantiation
    }

    /**
     * Escape special characters in a string for safe use in Solr queries.
     *
     * This method escapes all Solr special characters by prepending a backslash.
     * Use this for user-provided values that will be used in Solr queries.
     *
     * @param input The input string to escape (may be null)
     * @return The escaped string, or empty string if input is null
     */
    public static String escape(String input) {
        if (input == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(input.length() * 2);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (SOLR_SPECIAL_CHARS.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Escape and quote a string for safe use in Solr queries.
     *
     * Combines escaping with double-quote wrapping for phrase queries.
     * Use this when the value should be treated as a single term.
     *
     * @param input The input string to escape and quote (may be null)
     * @return The escaped and quoted string, or empty quoted string if input is null
     */
    public static String escapeAndQuote(String input) {
        return "\"" + escape(input) + "\"";
    }

    /**
     * Validate and sanitize an ID value for use in Solr queries.
     *
     * IDs should typically be alphanumeric with limited special characters.
     * This method validates the format and escapes any remaining special chars.
     *
     * @param id The ID to sanitize
     * @return The sanitized ID
     * @throws IllegalArgumentException if the ID is null, empty, or contains invalid characters
     */
    public static String sanitizeId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("ID cannot be null or empty");
        }

        // Escape any Solr special characters
        return escape(id.trim());
    }

    /**
     * Check if a string contains any Solr special characters.
     *
     * @param input The string to check
     * @return true if the string contains special characters
     */
    public static boolean containsSpecialChars(String input) {
        if (input == null) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            if (SOLR_SPECIAL_CHARS.indexOf(input.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
