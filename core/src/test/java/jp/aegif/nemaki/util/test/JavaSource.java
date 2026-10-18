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
package jp.aegif.nemaki.util.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading one method out of a source file, for the few tests that must assert on source.
 *
 * <h2>Frozen (owner's decision, 2026-10-17)</h2>
 *
 * <p>Do not teach this reader anything new. Six consecutive review rounds found defects in the
 * machinery that source-reading locks need — brace matching, stripping comments, stripping
 * literal text, delimiting a catch clause, resolving a qualified constant, approximating a
 * family of messages by its wording — and every guard added for that machinery turned out never
 * to fire on this tree's real input. The locks that assert something about a WHOLE SET (every
 * destructive consumer checks its listing, no test throws an AssertionError, every field name
 * goes through the one escaping rule) have produced no defects and have no cheap behavioural
 * substitute; the cost is in locks that read source text to assert a RUNTIME property. So this
 * class stays as it is: a new lock either uses it as it stands, or is written as a
 * behaviour-driven lock instead of growing a new reader. Recorded in
 * {@code docs/design/v3.4.1-plan.md} (owner's decision, 2026-10-17).
 *
 * <h2>Why this is shared code and not three inline helpers</h2>
 *
 * <p>A handful of defects live entirely in a query string or in an argument that never reaches
 * any observable output, so the only place to pin them is the source. Every ad-hoc attempt at
 * that in this repository has since failed silently rather than loudly, in a different way each
 * time:
 *
 * <ul>
 *   <li>A fixed 6000-character window stopped covering the constructions it was counting as soon
 *       as the method grew, and reported "found 0" as though the code had been fixed.</li>
 *   <li>Bounding on the next {@code @Override} assumes every following method carries one. The
 *       first that does not silently extends the window over unrelated methods, so their code is
 *       asserted against as if it were this method's.</li>
 *   <li>Searching the raw file for a broken construct finds the construct quoted in the comment
 *       that explains why it is broken — so the test fails against the FIXED code. This project
 *       has shipped that one twice.</li>
 * </ul>
 *
 * <p>All three have the same shape: the test keeps passing (or fails for a reason unrelated to
 * the code) while no longer checking what it claims to. Brace matching is the only boundary that
 * does not drift, and stripping comments is the only way to be sure a match came from code.
 */
public final class JavaSource {

    private JavaSource() {
    }

    public static String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }

    /**
     * The body of the method whose declaration contains {@code signatureFragment}, from the
     * declaration through its closing brace.
     *
     * <p>Bounded by brace matching, skipping braces that appear inside string and character
     * literals. String literals are KEPT in the result: some of these assertions are about a
     * query string, so removing them would remove the subject.
     *
     * @throws HarnessBroken if the fragment is absent or the braces do not balance — either
     *     means the test is no longer looking at what it thinks it is. It is deliberately not
     *     an AssertionError: the control runner reads an AssertionError as "the lock fired",
     *     and this is the opposite of that.
     */
    public static String methodBody(String source, String signatureFragment) {
        int start = source.indexOf(signatureFragment);
        if (start < 0) {
            throw new HarnessBroken("method not found, so nothing was checked: " + signatureFragment);
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            throw new HarnessBroken("no body for: " + signatureFragment);
        }
        return source.substring(start, matchingClose(source, open));
    }

    /**
     * The index just past the '{@code }}' closing the block opened at or after {@code from}.
     *
     * <p>Extracted from {@link #methodBody} because a source-level test had hand-rolled a
     * second brace counter that ignored string literals and comments — so a '{@code {}' inside
     * a literal added anywhere in the file it reads would have shifted the region it thought
     * it was checking, silently, rather than tripping {@link HarnessBroken}. One scanner, so
     * the careful one is the only one.
     */
    public static int matchingClose(String source, int from) {
        int open = source.indexOf('{', from);
        if (open < 0) {
            throw new HarnessBroken("no block opens at or after offset " + from);
        }
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString || inChar) {
                if (c == '\\') {
                    i++;
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                inLine = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        throw new HarnessBroken("unbalanced braces from offset " + from);
    }

    /**
     * The same text with comments removed, so a match is known to come from code.
     *
     * <p>String literals are preserved: an assertion about a query string needs them, and the
     * dangerous write a lock looks for can be spelled {@code doc.addField("dynamicDate." + k, v)}.
     *
     * <p>Text blocks are read as ONE literal, and an escape inside one is consumed with the
     * character after it. The first form of this reader did neither: a file with a text block
     * ({@code McpToolsProvider} has five) was read as "literal, close, literal" and everything after
     * it was taken for string, so a lock reading that file measured nothing (3.4.1, rounds 12-13 of
     * the batch-18 review).
     */
    public static String withoutComments(String body) {
        return scan(body, true);
    }

    /**
     * The same text with comments removed AND the contents of literals removed.
     *
     * <p>For the locks that read what the code CALLS: there a method name inside a message is not a
     * call, so prose about a method must neither satisfy nor refuse a check about calling it.
     */
    public static String withoutLiteralText(String body) {
        return scan(body, false);
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
                    } else if (next == '\n') {
                        // A line continuation inside a text block: the newline is still a line of
                        // the file, and eating it shifted every line number after it
                        // (round-13 review, P3).
                        code.append(next);
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
            throw new HarnessBroken("this source ends inside "
                    + (inTextBlock ? "a text block" : inString ? "a string literal"
                            : inChar ? "a char literal" : "a block comment")
                    + ", so the locks that read it cannot vouch for what they measured."
                    + " Either the file does not compile or this reader needs to learn the form");
        }
        return code.toString();
    }
}
