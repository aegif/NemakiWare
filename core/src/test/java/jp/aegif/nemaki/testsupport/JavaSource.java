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
package jp.aegif.nemaki.testsupport;

/**
 * Reads a production file the way the locks that measure its STRUCTURE need to read it.
 *
 * <p>Several locks in this tree assert what a production does by reading its source. Prose is not
 * code, and every form of "strip the comments" written inline in one of those locks has been broken
 * from one side or the other:
 *
 * <ul>
 * <li>cutting a line at the first {@code //} hid an indexing call written after a string literal
 *     that contained {@code //}, and turned a line with a trailing block comment red for nothing;</li>
 * <li>keeping the contents of string literals let a diagnostic message that merely NAMES a method
 *     satisfy — or refuse — a check about calling it;</li>
 * <li>a file with a text block ({@code McpToolsProvider} has ten) was read as "literal, close,
 *     literal" and everything after it was taken for string, so the lock measured nothing.</li>
 * </ul>
 *
 * <p>One reader, so that the next lock does not get a fourth variant of the same mistake
 * ([[a-second-reader-must-be-diffed-against-the-first]]). What it does NOT do is parse Java: it
 * tracks literals and comments, nothing else. When it reaches the end of a file still inside one, it
 * says so and fails rather than returning text whose meaning it cannot vouch for.
 */
public final class JavaSource {

    private JavaSource() {
    }

    /** The source with comments removed and the contents of literals kept. */
    public static String codeOnly(String source) {
        return scan(source, true);
    }

    /**
     * The source with comments removed AND the contents of string and char literals removed.
     *
     * <p>For the locks that read what the code CALLS: there, a method name inside a message is not a
     * call, and a field name inside a message is not a field.
     */
    public static String codeWithoutLiteralText(String source) {
        return scan(source, false);
    }

    private static String scan(String source, boolean keepLiteralText) {
        StringBuilder code = new StringBuilder(source.length());
        boolean inTextBlock = false;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            boolean tripleQuote = c == '"' && next == '"' && i + 2 < source.length()
                    && source.charAt(i + 2) == '"';
            if (inTextBlock) {
                // Newlines are kept whatever the mode, so the line NUMBERS a lock reports still
                // point at the file. An escape is consumed with the character after it: a text block
                // may contain \""" , and reading that as the terminator made the scanner treat the
                // real terminator as a new opening delimiter and swallow the code that followed.
                if (c == '\\') {
                    if (keepLiteralText) {
                        code.append(c);
                        if (i + 1 < source.length()) {
                            code.append(next);
                        }
                    }
                    i++;
                } else if (tripleQuote) {
                    inTextBlock = false;
                    i += 2;
                    if (keepLiteralText) {
                        code.append("\"\"\"");
                    }
                } else if (c == '\n' || keepLiteralText) {
                    code.append(c);
                }
                continue;
            }
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    code.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                    code.append(' ');
                } else if (c == '\n') {
                    code.append(c);
                }
                continue;
            }
            if (inString || inChar) {
                boolean closingDelimiter = (inString && c == '"') || (inChar && c == '\'');
                if (keepLiteralText || closingDelimiter) {
                    code.append(c);
                }
                if (c == '\\') {
                    if (i + 1 < source.length()) {
                        if (keepLiteralText) {
                            code.append(next);
                        }
                        i++;
                    }
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (tripleQuote) {
                inTextBlock = true;
                i += 2;
                if (keepLiteralText) {
                    code.append("\"\"\"");
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            }
            code.append(c);
        }
        // Fail-closed: a file that ends inside a literal or a comment was not read the way this
        // scanner assumes, and whatever a lock concludes from the text would be about a file it did
        // not understand (round-12 review, P2).
        if (inTextBlock || inString || inChar || inBlockComment) {
            throw new AssertionError("this source ends inside "
                    + (inTextBlock ? "a text block" : inString ? "a string literal"
                            : inChar ? "a char literal" : "a block comment")
                    + ", so the locks that read it cannot vouch for what they measured."
                    + " Either the file does not compile or this reader needs to learn the form");
        }
        return code.toString();
    }
}
