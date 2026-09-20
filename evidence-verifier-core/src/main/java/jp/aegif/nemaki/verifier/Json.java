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
package jp.aegif.nemaki.verifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON reader — the one {@code evidence-profile-v1.md} §3.2 describes.
 *
 * <p>Written here rather than pulled in, because this module may not depend on the JSON library
 * the product uses: two implementations reading through the same parser would agree about
 * anything that parser decided, including the things §3.2 forbids it to decide.
 *
 * <p>What it refuses, and why each refusal matters:
 *
 * <ul>
 * <li><b>duplicate keys</b> — the document would have two canonical forms, and which one a
 *     verifier computed would depend on its parser;</li>
 * <li><b>non-integral numbers</b> — the encoding has no tag, and rounding digests a value the
 *     document does not contain;</li>
 * <li><b>integers wider than int64</b> — same, by saturation;</li>
 * <li><b>trailing content</b> — two documents in one file is not one document;</li>
 * <li><b>depth beyond a bound</b> — a package is untrusted input, and a StackOverflowError is
 *     not something a verifier can report as a resource limit.</li>
 * </ul>
 */
public final class Json {

    /** A document with no single canonical form. Carries why; "malformed" is not a reason. */
    public static class NotCanonicalisable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotCanonicalisable(String message) {
            super(message);
        }
    }

    private static final int MAX_DEPTH = 64;

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** Parses to Map / List / String / Long / Boolean / null. */
    public static Object parse(String text) {
        if (text == null) {
            throw new NotCanonicalisable("there is no document to read");
        }
        Json reader = new Json(text);
        reader.skipWhitespace();
        Object value = reader.readValue(0);
        reader.skipWhitespace();
        if (reader.at != text.length()) {
            throw new NotCanonicalisable("the document has trailing content at offset "
                    + reader.at + ", so it is not one document");
        }
        return value;
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw new NotCanonicalisable("the document nests deeper than " + MAX_DEPTH
                    + " levels, which this reader refuses rather than recursing into");
        }
        skipWhitespace();
        if (at >= text.length()) {
            throw new NotCanonicalisable("the document ends where a value was expected");
        }
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject(int depth) {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            if (map.containsKey(key)) {
                throw new NotCanonicalisable("the key \"" + key + "\" appears twice in one "
                        + "object, so the document has two readings and two canonical forms");
            }
            skipWhitespace();
            expect(':');
            map.put(key, readValue(depth + 1));
            skipWhitespace();
            char next = next();
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                throw new NotCanonicalisable("expected , or } at offset " + (at - 1));
            }
        }
    }

    private List<Object> readArray(int depth) {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return list;
        }
        while (true) {
            list.add(readValue(depth + 1));
            skipWhitespace();
            char next = next();
            if (next == ']') {
                return list;
            }
            if (next != ',') {
                throw new NotCanonicalisable("expected , or ] at offset " + (at - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw new NotCanonicalisable("the document ends inside a string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw new NotCanonicalisable("the document ends inside an escape");
            }
            char escape = text.charAt(at++);
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (at + 4 > text.length()) {
                        throw new NotCanonicalisable("a \\u escape is cut short");
                    }
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw new NotCanonicalisable("unknown escape \\" + escape);
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, at)) {
            throw new NotCanonicalisable("expected " + literal + " at offset " + at);
        }
        at += literal.length();
        return value;
    }

    private Long readNumber() {
        int start = at;
        if (peek() == '-') {
            at++;
        }
        while (at < text.length() && Character.isDigit(text.charAt(at))) {
            at++;
        }
        if (at == start || (at == start + 1 && text.charAt(start) == '-')) {
            throw new NotCanonicalisable("expected a value at offset " + start);
        }
        // A fraction or an exponent makes this a non-integral literal. Refused HERE rather than
        // parsed and rounded: the digest must be of the document, not of a nearby number.
        if (at < text.length() && (text.charAt(at) == '.' || text.charAt(at) == 'e'
                || text.charAt(at) == 'E')) {
            throw new NotCanonicalisable("the number at offset " + start + " is not integral, "
                    + "and the canonical encoding has no tag for it");
        }
        String literal = text.substring(start, at);
        try {
            return Long.valueOf(literal);
        } catch (NumberFormatException wider) {
            throw new NotCanonicalisable("the integer " + literal + " does not fit in int64, "
                    + "and saturating it would digest a value the document does not hold");
        }
    }

    private void skipWhitespace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private char next() {
        if (at >= text.length()) {
            throw new NotCanonicalisable("the document ends where more was expected");
        }
        return text.charAt(at++);
    }

    private void expect(char expected) {
        skipWhitespace();
        char c = next();
        if (c != expected) {
            throw new NotCanonicalisable("expected " + expected + " at offset " + (at - 1)
                    + " and found " + c);
        }
    }
}
