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

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for PasswordEncryptionUtil.
 */
public class PasswordEncryptionUtilTest {

    @Test
    public void testEncryptAndDecrypt() {
        String originalPassword = "mySecretPassword123!";
        
        String encrypted = PasswordEncryptionUtil.encrypt(originalPassword);
        
        assertNotNull("Encrypted password should not be null", encrypted);
        assertTrue("Encrypted password should start with ENC(", encrypted.startsWith("ENC("));
        assertTrue("Encrypted password should end with )", encrypted.endsWith(")"));
        assertNotEquals("Encrypted password should differ from original", originalPassword, encrypted);
        
        String decrypted = PasswordEncryptionUtil.decrypt(encrypted);
        
        assertEquals("Decrypted password should match original", originalPassword, decrypted);
    }

    @Test
    public void testEncryptProducesDifferentOutputs() {
        String password = "testPassword";
        
        String encrypted1 = PasswordEncryptionUtil.encrypt(password);
        String encrypted2 = PasswordEncryptionUtil.encrypt(password);
        
        assertNotEquals("Each encryption should produce different output due to random salt/IV", 
                encrypted1, encrypted2);
        
        assertEquals("Both should decrypt to same password", password, 
                PasswordEncryptionUtil.decrypt(encrypted1));
        assertEquals("Both should decrypt to same password", password, 
                PasswordEncryptionUtil.decrypt(encrypted2));
    }

    @Test
    public void testIsEncrypted() {
        assertTrue("Should detect ENC() format", 
                PasswordEncryptionUtil.isEncrypted("ENC(somedata)"));
        assertFalse("Should not detect plain text", 
                PasswordEncryptionUtil.isEncrypted("plainPassword"));
        assertFalse("Should not detect partial format", 
                PasswordEncryptionUtil.isEncrypted("ENC(incomplete"));
        assertFalse("Should handle null", 
                PasswordEncryptionUtil.isEncrypted(null));
        assertFalse("Should handle empty string", 
                PasswordEncryptionUtil.isEncrypted(""));
    }

    @Test
    public void testIsEnvironmentVariable() {
        assertTrue("Should detect ENV() format", 
                PasswordEncryptionUtil.isEnvironmentVariable("ENV(MY_VAR)"));
        assertFalse("Should not detect plain text", 
                PasswordEncryptionUtil.isEnvironmentVariable("plainPassword"));
        assertFalse("Should not detect partial format", 
                PasswordEncryptionUtil.isEnvironmentVariable("ENV(incomplete"));
        assertFalse("Should handle null", 
                PasswordEncryptionUtil.isEnvironmentVariable(null));
        assertFalse("Should handle empty string", 
                PasswordEncryptionUtil.isEnvironmentVariable(""));
    }

    @Test
    public void testResolvePasswordPlainText() {
        String plainPassword = "myPlainPassword";
        
        String resolved = PasswordEncryptionUtil.resolvePassword(plainPassword);
        
        assertEquals("Plain text should be returned as-is", plainPassword, resolved);
    }

    @Test
    public void testResolvePasswordEncrypted() {
        String originalPassword = "secretPassword";
        String encrypted = PasswordEncryptionUtil.encrypt(originalPassword);
        
        String resolved = PasswordEncryptionUtil.resolvePassword(encrypted);
        
        assertEquals("Encrypted password should be decrypted", originalPassword, resolved);
    }

    @Test
    public void testResolvePasswordNull() {
        assertNull("Null should return null", PasswordEncryptionUtil.resolvePassword(null));
    }

    @Test
    public void testResolvePasswordEmpty() {
        assertEquals("Empty string should return empty", "", PasswordEncryptionUtil.resolvePassword(""));
    }

    @Test
    public void testEncryptEmptyPassword() {
        String encrypted = PasswordEncryptionUtil.encrypt("");

        // Empty string is returned as-is (not encrypted), which is the correct behavior
        assertEquals("Empty password should be returned as-is", "", encrypted);
        assertFalse("Empty password should not be in encrypted format",
                PasswordEncryptionUtil.isEncrypted(encrypted));
    }

    @Test
    public void testEncryptSpecialCharacters() {
        String specialPassword = "p@$$w0rd!#%^&*()_+-=[]{}|;':\",./<>?`~";
        
        String encrypted = PasswordEncryptionUtil.encrypt(specialPassword);
        String decrypted = PasswordEncryptionUtil.decrypt(encrypted);
        
        assertEquals("Special characters should be preserved", specialPassword, decrypted);
    }

    @Test
    public void testEncryptUnicodeCharacters() {
        String unicodePassword = "パスワード密码пароль";
        
        String encrypted = PasswordEncryptionUtil.encrypt(unicodePassword);
        String decrypted = PasswordEncryptionUtil.decrypt(encrypted);
        
        assertEquals("Unicode characters should be preserved", unicodePassword, decrypted);
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
        
        assertEquals("Long password should be preserved", longPassword, decrypted);
    }

    @Test(expected = RuntimeException.class)
    public void testDecryptInvalidData() {
        PasswordEncryptionUtil.decrypt("ENC(invalidbase64data!!!)");
    }

    @Test(expected = RuntimeException.class)
    public void testDecryptMalformedEncrypted() {
        PasswordEncryptionUtil.decrypt("ENC()");
    }
}
