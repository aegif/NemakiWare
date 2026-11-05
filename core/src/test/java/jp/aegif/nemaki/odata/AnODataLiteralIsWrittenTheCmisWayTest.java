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
                Expression.class, org.apache.olingo.commons.api.edm.EdmEntityType.class);
        convert.setAccessible(true);
        Literal literal = mock(Literal.class);
        when(literal.getText()).thenReturn("'O''Neil\\'");
        assertEquals("'O\\'Neil\\\\'", convert.invoke(processor, literal, null));
    }
    @Test
    @DisplayName("startswith and endswith write the value's own % and _ as characters; only the wildcard they add is one")
    void startsWithKeepsTheValuesWildcardsLiteral() throws Exception {
        CmisEntityCollectionProcessor processor = new CmisEntityCollectionProcessor(null, null, null, null, "bedroom",
                null);
        Method convert = CmisEntityCollectionProcessor.class.getDeclaredMethod("convertExpressionToCmis",
                Expression.class, org.apache.olingo.commons.api.edm.EdmEntityType.class);
        convert.setAccessible(true);
        Member member = mock(Member.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        UriResource part = mock(UriResource.class);
        when(part.toString()).thenReturn("name");
        when(member.getResourcePath().getUriResourceParts()).thenReturn(java.util.List.of(part));
        when(member.getStartTypeFilter()).thenReturn(null);
        Literal literal = stringLiteral("'100%_x'");
        org.apache.olingo.server.api.uri.queryoption.expression.Method startsWith =
                mock(org.apache.olingo.server.api.uri.queryoption.expression.Method.class);
        when(startsWith.getMethod()).thenReturn(MethodKind.STARTSWITH);
        when(startsWith.getParameters()).thenReturn(java.util.List.of(member, literal));
        assertEquals("cmis:name LIKE '100\\%\\_x%'", convert.invoke(processor, startsWith, null));
    }
    @Test
    @DisplayName("contains is a LIKE on both sides by the same rule — the value's own % and _ and its quote are characters")
    void containsIsALikeThatKeepsTheValuesWildcardsLiteral() throws Exception {
        // It was CONTAINS(cmis:name, '...'), which CMIS does not have: OpenCMIS could not parse the WHERE clause, and
        // since R188 (3.4.1) the server refuses such a clause with a 400 — every $filter with contains() did (3.4.2).
        CmisEntityCollectionProcessor processor = new CmisEntityCollectionProcessor(null, null, null, null, "bedroom",
                null);
        Method convert = CmisEntityCollectionProcessor.class.getDeclaredMethod("convertExpressionToCmis",
                Expression.class, org.apache.olingo.commons.api.edm.EdmEntityType.class);
        convert.setAccessible(true);
        assertEquals("cmis:name LIKE '%100\\%\\_x%'", convert.invoke(processor, containsOn("'100%_x'"), null));
        assertEquals("cmis:name LIKE '%O\\'Neil%'", convert.invoke(processor, containsOn("'O''Neil'"), null));
    }

    private static org.apache.olingo.server.api.uri.queryoption.expression.Method containsOn(String odataLiteral) {
        return method(MethodKind.CONTAINS, member("name"), stringLiteral(odataLiteral));
    }

    @Test
    @DisplayName("startswith, endswith and contains refuse a property or a function as the value, and a literal first (R248)")
    void aLikeMethodTakesAPropertyAndAStringLiteralOnly() throws Exception {
        for (MethodKind kind : java.util.List.of(MethodKind.STARTSWITH, MethodKind.ENDSWITH, MethodKind.CONTAINS)) {
            // A property as the value: it became the property's name, searched for as text.
            assertRefused(kind, member("name"), member("createdBy"));
            // A function as the value: it became Olingo's toString(), whose raw literal closed the CMIS literal.
            org.apache.olingo.server.api.uri.queryoption.expression.Method tolower =
                    method(MethodKind.TOLOWER, stringLiteral("' OR cmis:objectId IS NOT NULL OR cmis:name = '"));
            when(tolower.toString()).thenReturn("{tolower [' OR cmis:objectId IS NOT NULL OR cmis:name = ']}");
            assertRefused(kind, member("name"), tolower);
            // Quoted text is not what makes a string literal: its EDM type is (a null literal has none).
            Literal untyped = mock(Literal.class);
            when(untyped.getText()).thenReturn("'x'");
            assertRefused(kind, member("name"), untyped);
            // A literal first — with the property second, and with a string literal second, which only the
            // first argument's own check refuses.
            assertRefused(kind, stringLiteral("'foobar'"), member("name"));
            assertRefused(kind, stringLiteral("'foobar'"), stringLiteral("'x'"));
            // A string literal whose text is not quoted is refused, not trimmed of two characters it does not have.
            java.lang.reflect.InvocationTargetException unquoted = org.junit.jupiter.api.Assertions.assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> convert(method(kind, member("name"), stringLiteral("abc"))), kind + " trimmed an unquoted literal");
            assertEquals(kind + " got a string literal that is not written quoted", unquoted.getCause().getMessage());
        }
    }

    @Test
    @DisplayName("with a property and a string literal each of the three is still a LIKE (R248)")
    void aLikeMethodWithAPropertyAndAStringLiteralIsALike() throws Exception {
        assertEquals("cmis:name LIKE 'ab%'", convert(method(MethodKind.STARTSWITH, member("name"), stringLiteral("'ab'"))));
        assertEquals("cmis:name LIKE '%ab'", convert(method(MethodKind.ENDSWITH, member("name"), stringLiteral("'ab'"))));
        assertEquals("cmis:name LIKE '%ab%'", convert(method(MethodKind.CONTAINS, member("name"), stringLiteral("'ab'"))));
    }

    private static void assertRefused(MethodKind kind, Expression first, Expression second) throws Exception {
        java.lang.reflect.InvocationTargetException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> convert(method(kind, first, second)),
                kind + " translated " + first + ", " + second);
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                org.apache.olingo.server.api.ODataApplicationException.class, thrown.getCause(), kind + ": " + thrown.getCause());
        assertEquals(kind + " is translated only with a property first and a string literal second",
                thrown.getCause().getMessage(), kind + " was refused by another arm");
    }

    private static String convert(Expression expression) throws Exception {
        CmisEntityCollectionProcessor processor = new CmisEntityCollectionProcessor(null, null, null, null, "bedroom",
                null);
        Method convert = CmisEntityCollectionProcessor.class.getDeclaredMethod("convertExpressionToCmis",
                Expression.class, org.apache.olingo.commons.api.edm.EdmEntityType.class);
        convert.setAccessible(true);
        return (String) convert.invoke(processor, expression, null);
    }

    private static Member member(String name) {
        Member member = mock(Member.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        UriResource part = mock(UriResource.class);
        when(part.toString()).thenReturn(name);
        when(member.getResourcePath().getUriResourceParts()).thenReturn(java.util.List.of(part));
        // No type cast (R249): a deep stub would answer one.
        when(member.getStartTypeFilter()).thenReturn(null);
        return member;
    }

    /** A literal as Olingo writes a quoted one: the text with its quotes, and the Edm.String type. */
    private static Literal stringLiteral(String odataText) {
        Literal literal = mock(Literal.class);
        when(literal.getText()).thenReturn(odataText);
        when(literal.getType()).thenReturn(org.apache.olingo.commons.core.edm.primitivetype.EdmPrimitiveTypeFactory
                .getInstance(org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind.String));
        return literal;
    }

    private static org.apache.olingo.server.api.uri.queryoption.expression.Method method(MethodKind kind,
            Expression... parameters) {
        org.apache.olingo.server.api.uri.queryoption.expression.Method method =
                mock(org.apache.olingo.server.api.uri.queryoption.expression.Method.class);
        when(method.getMethod()).thenReturn(kind);
        when(method.getParameters()).thenReturn(java.util.List.of(parameters));
        return method;
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
