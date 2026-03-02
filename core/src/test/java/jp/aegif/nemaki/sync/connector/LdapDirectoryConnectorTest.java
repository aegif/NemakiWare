/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - Directory Sync feature implementation
 ******************************************************************************/
package jp.aegif.nemaki.sync.connector;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for LdapDirectoryConnector.
 * Tests LDAP filter sanitization and validation logic.
 */
public class LdapDirectoryConnectorTest {

    @Test
    public void testSanitizeLdapFilterBackslash() {
        String input = "user\\name";
        String expected = "user\\5cname";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals(expected, result, "Backslash should be escaped");
    }

    @Test
    public void testSanitizeLdapFilterAsterisk() {
        String input = "user*name";
        String expected = "user\\2aname";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals(expected, result, "Asterisk should be escaped");
    }

    @Test
    public void testSanitizeLdapFilterParentheses() {
        String input = "user(name)";
        String expected = "user\\28name\\29";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals(expected, result, "Parentheses should be escaped");
    }

    @Test
    public void testSanitizeLdapFilterNullByte() {
        String input = "user\u0000name";
        String expected = "user\\00name";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals(expected, result, "Null byte should be escaped");
    }

    @Test
    public void testSanitizeLdapFilterMultipleSpecialChars() {
        String input = "user\\*(name)\u0000";
        String expected = "user\\5c\\2a\\28name\\29\\00";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals(expected, result, "Multiple special characters should be escaped");
    }

    @Test
    public void testSanitizeLdapFilterNoSpecialChars() {
        String input = "normalUsername";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals(input, result, "Normal string should remain unchanged");
    }

    @Test
    public void testSanitizeLdapFilterNull() {
        String result = LdapDirectoryConnector.sanitizeLdapFilter(null);
        assertNull(result, "Null input should return null");
    }

    @Test
    public void testSanitizeLdapFilterEmpty() {
        String result = LdapDirectoryConnector.sanitizeLdapFilter("");
        assertEquals("", result, "Empty string should return empty");
    }

    @Test
    public void testIsValidLdapFilterSimple() {
        assertTrue(LdapDirectoryConnector.isValidLdapFilter("(objectClass=person)"), "Simple filter should be valid");
    }

    @Test
    public void testIsValidLdapFilterAnd() {
        assertTrue(LdapDirectoryConnector.isValidLdapFilter("(&(objectClass=person)(uid=test))"), "AND filter should be valid");
    }

    @Test
    public void testIsValidLdapFilterOr() {
        assertTrue(LdapDirectoryConnector.isValidLdapFilter("(|(objectClass=person)(objectClass=user))"), "OR filter should be valid");
    }

    @Test
    public void testIsValidLdapFilterNot() {
        assertTrue(LdapDirectoryConnector.isValidLdapFilter("(!(objectClass=computer))"), "NOT filter should be valid");
    }

    @Test
    public void testIsValidLdapFilterComplex() {
        assertTrue(LdapDirectoryConnector.isValidLdapFilter("(&(objectClass=person)(|(uid=admin)(uid=user)))"), "Complex filter should be valid");
    }

    @Test
    public void testIsValidLdapFilterWildcard() {
        assertTrue(LdapDirectoryConnector.isValidLdapFilter("(cn=John*)"), "Wildcard filter should be valid");
    }

    @Test
    public void testIsValidLdapFilterUnbalancedParentheses() {
        assertFalse(LdapDirectoryConnector.isValidLdapFilter("(objectClass=person"),
                "Unbalanced parentheses should be invalid");
    }

    @Test
    public void testIsValidLdapFilterNoParentheses() {
        assertFalse(LdapDirectoryConnector.isValidLdapFilter("objectClass=person"), "Filter without parentheses should be invalid");
    }

    @Test
    public void testIsValidLdapFilterNull() {
        assertFalse(LdapDirectoryConnector.isValidLdapFilter(null), "Null filter should be invalid");
    }

    @Test
    public void testIsValidLdapFilterEmpty() {
        assertFalse(LdapDirectoryConnector.isValidLdapFilter(""), "Empty filter should be invalid");
    }

    @Test
    public void testIsValidLdapFilterExtraClosingParen() {
        assertFalse(LdapDirectoryConnector.isValidLdapFilter("(objectClass=person))"), "Extra closing parenthesis should be invalid");
    }

    @Test
    public void testIsValidLdapFilterExtraOpeningParen() {
        assertFalse(LdapDirectoryConnector.isValidLdapFilter("((objectClass=person)"),
                "Extra opening parenthesis should be invalid");
    }
}
