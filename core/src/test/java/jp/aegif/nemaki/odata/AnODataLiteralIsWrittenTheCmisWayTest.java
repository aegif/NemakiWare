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
package jp.aegif.nemaki.odata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.Literal;
import org.apache.olingo.server.api.uri.queryoption.expression.Member;
import org.apache.olingo.server.api.uri.queryoption.expression.MethodKind;
import org.apache.olingo.server.api.uri.UriResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An OData string literal goes into the CMIS statement as a CMIS string literal (closing batch 4 review, subagent P3-3).
 *
 * <p>OData doubles a quote inside a string; CMIS escapes a quote and a backslash with a backslash, and the server reads
 * them that way since closing batch 3. The literal's text went in as OData wrote it, so {@code 'O''Neil'} ended the
 * CMIS literal at the second quote and {@code 'a\b'} was searched as {@code ab}.
 */
class AnODataLiteralIsWrittenTheCmisWayTest {

    @Test
    @DisplayName("a quote OData doubled and a backslash are written with CMIS's escapes; a bare literal is left as it is")
    void theLiteralIsRewritten() {
        assertEquals("'O\\'Neil'", CmisEntityCollectionProcessor.cmisLiteral("'O''Neil'"));
        assertEquals("'a\\\\b'", CmisEntityCollectionProcessor.cmisLiteral("'a\\b'"));
        assertEquals("'plain'", CmisEntityCollectionProcessor.cmisLiteral("'plain'"));
        assertEquals("42", CmisEntityCollectionProcessor.cmisLiteral("42"));
        assertEquals("true", CmisEntityCollectionProcessor.cmisLiteral("true"));
    }

    @Test
    @DisplayName("the $filter conversion writes an OData literal through it")
    void theFilterConversionUsesIt() throws Exception {
        CmisEntityCollectionProcessor processor = new CmisEntityCollectionProcessor(null, null, null, null, "bedroom",
                null);
        Method convert = CmisEntityCollectionProcessor.class.getDeclaredMethod("convertExpressionToCmis",
                Expression.class);
        convert.setAccessible(true);
        Literal literal = mock(Literal.class);
        when(literal.getText()).thenReturn("'O''Neil\\'");
        assertEquals("'O\\'Neil\\\\'", convert.invoke(processor, literal));
    }
    @Test
    @DisplayName("startswith and endswith write the value's own % and _ as characters; only the wildcard they add is one")
    void startsWithKeepsTheValuesWildcardsLiteral() throws Exception {
        CmisEntityCollectionProcessor processor = new CmisEntityCollectionProcessor(null, null, null, null, "bedroom",
                null);
        Method convert = CmisEntityCollectionProcessor.class.getDeclaredMethod("convertExpressionToCmis",
                Expression.class);
        convert.setAccessible(true);
        Member member = mock(Member.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        UriResource part = mock(UriResource.class);
        when(part.toString()).thenReturn("name");
        when(member.getResourcePath().getUriResourceParts()).thenReturn(java.util.List.of(part));
        Literal literal = mock(Literal.class);
        when(literal.getText()).thenReturn("'100%_x'");
        org.apache.olingo.server.api.uri.queryoption.expression.Method startsWith =
                mock(org.apache.olingo.server.api.uri.queryoption.expression.Method.class);
        when(startsWith.getMethod()).thenReturn(MethodKind.STARTSWITH);
        when(startsWith.getParameters()).thenReturn(java.util.List.of(member, literal));
        assertEquals("cmis:name LIKE '100\\%\\_x%'", convert.invoke(processor, startsWith));
    }
    @Test
    @DisplayName("a $search term's quote and backslash are escaped twice — the text search expression's, then the literal's")
    void aSearchTermsQuoteIsEscapedTheCmisWay() throws Exception {
        // Escaped once, CONTAINS('it\'s') was refused by OpenCMIS's text search lexer — a 400; escaped twice it is
        // answered (closing batch 5 review, subagent P3-1, measured on bedroom). Through the conversion $search uses.
        CmisEntityCollectionProcessor processor = new CmisEntityCollectionProcessor(null, null, null, null, "bedroom",
                null);
        Method convert = CmisEntityCollectionProcessor.class.getDeclaredMethod("convertSearchToContains", String.class);
        convert.setAccessible(true);
        assertEquals("CONTAINS('it\\\\\\'s')", convert.invoke(processor, "it's"));
        assertEquals("CONTAINS('a\\\\\\\\b')", convert.invoke(processor, "a\\b"));
    }
}
