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
package jp.aegif.nemaki.sync.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for PasswordEncryptionUtil.
 */
public class PasswordEncryptionUtilTest {

    @Test
    public void testEncryptAndDecrypt() {
        String originalPassword = "mySecretPassword123!";
        
        String encrypted = PasswordEncryptionUtil.encrypt(originalPassword);
        
        assertNotNull(encrypted, "Encrypted password should not be null");
        assertTrue(encrypted.startsWith("ENC("), "Encrypted password should start with ENC(");
        assertTrue(encrypted.endsWith(")"), "Encrypted password should end with )");
        assertNotEquals(originalPassword, encrypted, "Encrypted password should differ from original");
        
        String decrypted = PasswordEncryptionUtil.decrypt(encrypted);
        
        assertEquals(originalPassword, decrypted, "Decrypted password should match original");
    }

    @Test
    public void testEncryptProducesDifferentOutputs() {
        String password = "testPassword";
        
        String encrypted1 = PasswordEncryptionUtil.encrypt(password);
        String encrypted2 = PasswordEncryptionUtil.encrypt(password);
        
        assertNotEquals(encrypted1, encrypted2, "Each encryption should produce different output due to random salt/IV");
        
        assertEquals(password, PasswordEncryptionUtil.decrypt(encrypted1), "Both should decrypt to same password");
        assertEquals(password, PasswordEncryptionUtil.decrypt(encrypted2), "Both should decrypt to same password");
    }

    @Test
    public void testIsEncrypted() {
        assertTrue(PasswordEncryptionUtil.isEncrypted("ENC(somedata)"), "Should detect ENC() format");
        assertFalse(PasswordEncryptionUtil.isEncrypted("plainPassword"), "Should not detect plain text");
        assertFalse(PasswordEncryptionUtil.isEncrypted("ENC(incomplete"),
                "Should not detect partial format");
        assertFalse(PasswordEncryptionUtil.isEncrypted(null), "Should handle null");
        assertFalse(PasswordEncryptionUtil.isEncrypted(""), "Should handle empty string");
    }

    @Test
    public void testIsEnvironmentVariable() {
        assertTrue(PasswordEncryptionUtil.isEnvironmentVariable("ENV(MY_VAR)"), "Should detect ENV() format");
        assertFalse(PasswordEncryptionUtil.isEnvironmentVariable("plainPassword"), "Should not detect plain text");
        assertFalse(PasswordEncryptionUtil.isEnvironmentVariable("ENV(incomplete"),
                "Should not detect partial format");
        assertFalse(PasswordEncryptionUtil.isEnvironmentVariable(null), "Should handle null");
        assertFalse(PasswordEncryptionUtil.isEnvironmentVariable(""), "Should handle empty string");
    }

    @Test
    public void testResolvePasswordPlainText() {
        String plainPassword = "myPlainPassword";
        
        String resolved = PasswordEncryptionUtil.resolvePassword(plainPassword);
        
        assertEquals(plainPassword, resolved, "Plain text should be returned as-is");
    }

    @Test
    public void testResolvePasswordEncrypted() {
        String originalPassword = "secretPassword";
        String encrypted = PasswordEncryptionUtil.encrypt(originalPassword);
        
        String resolved = PasswordEncryptionUtil.resolvePassword(encrypted);
        
        assertEquals(originalPassword, resolved, "Encrypted password should be decrypted");
    }

    @Test
    public void testResolvePasswordNull() {
        assertNull(PasswordEncryptionUtil.resolvePassword(null), "Null should return null");
    }

    @Test
    public void testResolvePasswordEmpty() {
        assertEquals("", PasswordEncryptionUtil.resolvePassword(""), "Empty string should return empty");
    }

    @Test
    public void testEncryptEmptyPassword() {
        String encrypted = PasswordEncryptionUtil.encrypt("");

        // Empty string is returned as-is (not encrypted), which is the correct behavior
        assertEquals("", encrypted, "Empty password should be returned as-is");
        assertFalse(PasswordEncryptionUtil.isEncrypted(encrypted), "Empty password should not be in encrypted format");
    }

    @Test
    public void testEncryptSpecialCharacters() {
        String specialPassword = "p@$$w0rd!#%^&*()_+-=[]{}|;':\",./<>?`~";
        
        String encrypted = PasswordEncryptionUtil.encrypt(specialPassword);
        String decrypted = PasswordEncryptionUtil.decrypt(encrypted);
        
        assertEquals(specialPassword, decrypted, "Special characters should be preserved");
    }

    @Test
    public void testEncryptUnicodeCharacters() {
        String unicodePassword = "パスワード密码пароль";
        
        String encrypted = PasswordEncryptionUtil.encrypt(unicodePassword);
        String decrypted = PasswordEncryptionUtil.decrypt(encrypted);
        
        assertEquals(unicodePassword, decrypted, "Unicode characters should be preserved");
    }

    @Test
    public void testEncryptLongPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("a");
        }
        String longPassword = sb.toString();
        
        String encrypted = PasswordEncryptionUtil.encrypt(longPassword);
        String decrypted = PasswordEncryptionUtil.decrypt(encrypted);
        
        assertEquals(longPassword, decrypted, "Long password should be preserved");
    }

    @Test
    public void testDecryptInvalidData() {
        assertThrows(RuntimeException.class, () ->
            PasswordEncryptionUtil.decrypt("ENC(invalidbase64data!!!)"));
    }

    @Test
    public void testDecryptMalformedEncrypted() {
        assertThrows(RuntimeException.class, () ->
            PasswordEncryptionUtil.decrypt("ENC()"));
    }
}
