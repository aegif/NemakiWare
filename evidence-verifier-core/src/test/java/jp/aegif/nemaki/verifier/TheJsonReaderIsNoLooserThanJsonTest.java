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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The canonical form is {@code enc(parse(D))} of a JSON document (§3.2), so what this reader
 * accepts IS the set of documents that have one. It accepted more than JSON's grammar — a
 * leading zero, a raw control character, a signed {@code \\u}, a lone surrogate, Unicode
 * whitespace — and the Python reference did not, so one byte sequence could have a canonical
 * form on the JVM and none outside it (9-6 review, P3).
 */
class TheJsonReaderIsNoLooserThanJsonTest {

    private static void refused(String document, String why) {
        assertThrows(Json.NotCanonicalisable.class, () -> Json.parse(document), why + ": " + document);
    }

    @Test
    @DisplayName("a number with a leading zero is refused; zero and negative numbers are read")
    void aLeadingZeroIsRefused() {
        refused("{\"a\":007}", "JSON has no leading zeros, and 007 digested as 7 is a value the "
                + "document does not hold in any reading the grammar allows");
        refused("{\"a\":-01}", "the sign does not change the rule");
        assertEquals(Map.of("a", 0L), Json.parse("{\"a\":0}"));
        assertEquals(Map.of("a", 0L), Json.parse("{\"a\":-0}"));
        assertEquals(Map.of("a", 10L), Json.parse("{\"a\":10}"));
    }

    @Test
    @DisplayName("a raw control character inside a string is refused; its escape is read")
    void aRawControlCharacterIsRefused() {
        refused("{\"a\":\"x\ny\"}", "a raw newline in a string is outside the grammar");
        refused("{\"a\":\"x\u0001y\"}", "so is U+0001");
        assertEquals(Map.of("a", "x\ny"), Json.parse("{\"a\":\"x\\ny\"}"));
    }

    @Test
    @DisplayName("a \\u escape is four hex digits, not what Integer.parseInt also takes")
    void aUnicodeEscapeIsFourHexDigits() {
        refused("{\"a\":\"\\u+041\"}", "Integer.parseInt reads a sign; the grammar does not");
        refused("{\"a\":\"\\u00G1\"}", "G is not a hex digit");
        assertEquals(Map.of("a", "A"), Json.parse("{\"a\":\"\\u0041\"}"));
    }

    @Test
    @DisplayName("an unpaired surrogate is refused; a pair is read as one code point")
    void anUnpairedSurrogateIsRefused() {
        refused("{\"a\":\"\\ud800\"}", "a lone high surrogate is not text: encoded to UTF-8 it "
                + "becomes '?', and \"\\ud800\" would digest like \"?\"");
        refused("{\"a\":\"\\udc00x\"}", "a lone low surrogate, the same");
        Object parsed = Json.parse("{\"a\":\"\\ud83d\\ude00\"}");
        assertEquals(Map.of("a", "\uD83D\uDE00"), parsed);
        assertArrayEquals(Canonical.encode(Map.of("a", "\uD83D\uDE00")), Canonical.encode(parsed));
    }

    @Test
    @DisplayName("only JSON's four whitespace characters separate tokens")
    void onlyJsonWhitespaceIsWhitespace() {
        refused("{\u00a0\"a\":1}", "a no-break space is whitespace to Character.isWhitespace and "
                + "not to JSON");
        refused("{\"a\":1}\u2028", "a line separator after the document, the same");
        assertEquals(Map.of("a", 1L), Json.parse(" \t\n\r{ \"a\" : 1 }\r\n"));
    }
}
