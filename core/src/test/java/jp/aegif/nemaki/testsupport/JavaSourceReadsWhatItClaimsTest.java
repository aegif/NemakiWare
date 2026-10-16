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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reader the structural locks depend on reads what it says it reads.
 *
 * <p>Those locks assert things about productions by reading their text, so every hole in this reader
 * is a hole in all of them at once — and each form of it has had one: a line cut at the first
 * {@code //} inside a string literal, a trailing block comment taken for code, a text block read as
 * two literals. None of those shapes exists in the two productions the locks read today, which is
 * exactly why they are measured HERE, on inputs written for the purpose, instead of waiting for a
 * production to grow one ([[a-vocabulary-with-no-producer]]).
 */
class JavaSourceReadsWhatItClaimsTest {

    @Test
    @DisplayName("a comment goes, and a literal that contains // stays")
    void aCommentGoesAndALiteralWithSlashesStays() {
        String source = "String marker = \"//\"; doc.addField(PREFIX + key, v); // not here\n";
        String code = JavaSource.codeOnly(source);

        assertTrue(code.contains("doc.addField(PREFIX + key, v);"),
                "the call was cut away by the // inside the literal, which is how an indexer could"
                        + " hide from the premise lock: " + code);
        assertFalse(code.contains("not here"), "the line comment survived: " + code);
    }

    @Test
    @DisplayName("a block comment at the END of a code line goes, and the code stays")
    void aTrailingBlockCommentGoes() {
        String code = JavaSource.codeOnly("doc.addField(f, v); /* not dynamicDate */\n");

        assertTrue(code.contains("doc.addField(f, v);"), code);
        assertFalse(code.contains("dynamicDate"),
                "a name written in a trailing block comment was read as code, which turns a lock red"
                        + " for a defect that is not there: " + code);
    }

    @Test
    @DisplayName("the contents of literals stay for codeOnly and go for codeWithoutLiteralText")
    void literalContentsStayOrGoByTheMode() {
        String source = "log.warn(\"parseSecondaryTypeWhereClause was not invoked\");\n";

        assertTrue(JavaSource.codeOnly(source).contains("parseSecondaryTypeWhereClause"));
        assertFalse(JavaSource.codeWithoutLiteralText(source).contains("parseSecondaryTypeWhereClause"),
                "a method NAMED in a message was read as a call, which refuses a production for"
                        + " writing prose about itself");
    }

    @Test
    @DisplayName("a text block does not end at an ESCAPED triple quote — the code after it survives")
    void anEscapedTripleQuoteInsideATextBlockIsNotTheEnd() {
        // The shape the round-12 review named: reading \""" as the terminator makes the real
        // terminator look like a new opening delimiter, and everything after it is swallowed as
        // string — including an indexer the premise lock exists to find.
        String source = "String t = \"\"\"\n  a \\\"\"\" b\n  \"\"\";\n"
                + "doc.addField(DATETIME_FIELD_PREFIX + key, v);\n";

        String code = JavaSource.codeOnly(source);

        assertTrue(code.contains("doc.addField(DATETIME_FIELD_PREFIX + key, v);"),
                "the code after the text block was swallowed, so a lock reading this file measures"
                        + " nothing: " + code);
    }

    @Test
    @DisplayName("a text block's contents go with codeWithoutLiteralText, and the code around it stays")
    void aTextBlockIsOneLiteral() {
        String source = "String t = \"\"\"\n  dynamicDate.property.x\n  \"\"\";\nint n = 1;\n";

        assertFalse(JavaSource.codeWithoutLiteralText(source).contains("dynamicDate"),
                "the text block's contents were read as code");
        assertTrue(JavaSource.codeWithoutLiteralText(source).contains("int n = 1;"));
        assertTrue(JavaSource.codeOnly(source).contains("dynamicDate.property.x"),
                "codeOnly dropped a literal's contents, and the premise lock needs them");
    }

    @Test
    @DisplayName("the line count is unchanged, so the numbers a lock reports point at the file")
    void theLineCountIsUnchanged() {
        String source = "int a = 1; // one\n/* two\n   three */\nString t = \"\"\"\n  four\n  \"\"\";\nint b = 2;\n";

        for (String code : new String[] { JavaSource.codeOnly(source),
                JavaSource.codeWithoutLiteralText(source) }) {
            assertEquals(source.chars().filter(c -> c == '\n').count(),
                    code.chars().filter(c -> c == '\n').count(),
                    "the reader lost or added a line, so every file:line a lock reports is off: "
                            + code);
        }
    }

    @Test
    @DisplayName("a char literal that holds a quote does not open a string")
    void aCharLiteralHoldingAQuoteDoesNotOpenAString() {
        String code = JavaSource.codeOnly("char q = '\"'; doc.addField(f, v);\n");

        assertTrue(code.contains("doc.addField(f, v);"),
                "the quote inside a char literal opened a string and swallowed the rest: " + code);
    }

    @Test
    @DisplayName("a source that ends inside a literal or a comment fails loudly instead of answering")
    void anUnterminatedSourceFailsLoudly() {
        for (String broken : new String[] { "String s = \"unterminated\n", "char c = 'x\n",
                "/* unterminated\n", "String t = \"\"\"\n  unterminated\n" }) {
            AssertionError refused = assertThrows(AssertionError.class,
                    () -> JavaSource.codeOnly(broken),
                    "the reader answered for a source it did not understand: " + broken);
            assertTrue(refused.getMessage().contains("ends inside"),
                    "the refusal does not say what was left open: " + refused.getMessage());
        }
    }
}
