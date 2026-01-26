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

import static org.junit.Assert.*;

import org.junit.Test;

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
        assertEquals("Backslash should be escaped", expected, result);
    }

    @Test
    public void testSanitizeLdapFilterAsterisk() {
        String input = "user*name";
        String expected = "user\\2aname";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals("Asterisk should be escaped", expected, result);
    }

    @Test
    public void testSanitizeLdapFilterParentheses() {
        String input = "user(name)";
        String expected = "user\\28name\\29";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals("Parentheses should be escaped", expected, result);
    }

    @Test
    public void testSanitizeLdapFilterNullByte() {
        String input = "user\u0000name";
        String expected = "user\\00name";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals("Null byte should be escaped", expected, result);
    }

    @Test
    public void testSanitizeLdapFilterMultipleSpecialChars() {
        String input = "user\\*(name)\u0000";
        String expected = "user\\5c\\2a\\28name\\29\\00";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals("Multiple special characters should be escaped", expected, result);
    }

    @Test
    public void testSanitizeLdapFilterNoSpecialChars() {
        String input = "normalUsername";
        String result = LdapDirectoryConnector.sanitizeLdapFilter(input);
        assertEquals("Normal string should remain unchanged", input, result);
    }

    @Test
    public void testSanitizeLdapFilterNull() {
        String result = LdapDirectoryConnector.sanitizeLdapFilter(null);
        assertNull("Null input should return null", result);
    }

    @Test
    public void testSanitizeLdapFilterEmpty() {
        String result = LdapDirectoryConnector.sanitizeLdapFilter("");
        assertEquals("Empty string should return empty", "", result);
    }

    @Test
    public void testIsValidLdapFilterSimple() {
        assertTrue("Simple filter should be valid", 
                LdapDirectoryConnector.isValidLdapFilter("(objectClass=person)"));
    }

    @Test
    public void testIsValidLdapFilterAnd() {
        assertTrue("AND filter should be valid", 
                LdapDirectoryConnector.isValidLdapFilter("(&(objectClass=person)(uid=test))"));
    }

    @Test
    public void testIsValidLdapFilterOr() {
        assertTrue("OR filter should be valid", 
                LdapDirectoryConnector.isValidLdapFilter("(|(objectClass=person)(objectClass=user))"));
    }

    @Test
    public void testIsValidLdapFilterNot() {
        assertTrue("NOT filter should be valid", 
                LdapDirectoryConnector.isValidLdapFilter("(!(objectClass=computer))"));
    }

    @Test
    public void testIsValidLdapFilterComplex() {
        assertTrue("Complex filter should be valid", 
                LdapDirectoryConnector.isValidLdapFilter("(&(objectClass=person)(|(uid=admin)(uid=user)))"));
    }

    @Test
    public void testIsValidLdapFilterWildcard() {
        assertTrue("Wildcard filter should be valid", 
                LdapDirectoryConnector.isValidLdapFilter("(cn=John*)"));
    }

    @Test
    public void testIsValidLdapFilterUnbalancedParentheses() {
        assertFalse("Unbalanced parentheses should be invalid", 
                LdapDirectoryConnector.isValidLdapFilter("(objectClass=person"));
    }

    @Test
    public void testIsValidLdapFilterNoParentheses() {
        assertFalse("Filter without parentheses should be invalid", 
                LdapDirectoryConnector.isValidLdapFilter("objectClass=person"));
    }

    @Test
    public void testIsValidLdapFilterNull() {
        assertFalse("Null filter should be invalid", 
                LdapDirectoryConnector.isValidLdapFilter(null));
    }

    @Test
    public void testIsValidLdapFilterEmpty() {
        assertFalse("Empty filter should be invalid", 
                LdapDirectoryConnector.isValidLdapFilter(""));
    }

    @Test
    public void testIsValidLdapFilterExtraClosingParen() {
        assertFalse("Extra closing parenthesis should be invalid", 
                LdapDirectoryConnector.isValidLdapFilter("(objectClass=person))"));
    }

    @Test
    public void testIsValidLdapFilterExtraOpeningParen() {
        assertFalse("Extra opening parenthesis should be invalid", 
                LdapDirectoryConnector.isValidLdapFilter("((objectClass=person)"));
    }
}
