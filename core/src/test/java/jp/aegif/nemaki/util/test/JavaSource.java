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
     * @throws AssertionError if the fragment is absent or the braces do not balance — either
     *     means the test is no longer looking at what it thinks it is, which must be loud.
     */
    public static String methodBody(String source, String signatureFragment) {
        int start = source.indexOf(signatureFragment);
        if (start < 0) {
            throw new AssertionError("method not found, so nothing was checked: " + signatureFragment);
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            throw new AssertionError("no body for: " + signatureFragment);
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
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces after: " + signatureFragment);
    }

    /**
     * The same text with comments removed, so a match is known to come from code.
     *
     * <p>String literals are preserved: an assertion about a query string needs them.
     */
    public static String withoutComments(String body) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            char next = i + 1 < body.length() ? body.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
                continue;
            }
            if (inString || inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < body.length()) {
                    out.append(next);
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
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '\'') {
                    inChar = true;
                }
                out.append(c);
            }
        }
        return out.toString();
    }
}
