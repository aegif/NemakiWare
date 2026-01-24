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

import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Utility class for encrypting and decrypting LDAP bind passwords.
 * 
 * Supports three password formats:
 * 1. Plain text (legacy, not recommended)
 * 2. Encrypted with prefix "ENC(" and suffix ")" - e.g., ENC(base64encodeddata)
 * 3. Environment variable reference with prefix "ENV(" and suffix ")" - e.g., ENV(LDAP_PASSWORD)
 * 
 * Encryption uses AES-256-GCM with PBKDF2 key derivation.
 */
public class PasswordEncryptionUtil {

    private static final Log log = LogFactory.getLog(PasswordEncryptionUtil.class);

    private static final String ENCRYPTED_PREFIX = "ENC(";
    private static final String ENCRYPTED_SUFFIX = ")";
    private static final String ENV_PREFIX = "ENV(";
    private static final String ENV_SUFFIX = ")";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final int ITERATION_COUNT = 65536;

    private static final String DEFAULT_ENCRYPTION_KEY_ENV = "NEMAKI_ENCRYPTION_KEY";
    private static final String DEFAULT_ENCRYPTION_KEY = "NemakiWare-Default-Key-Change-Me!";
    private static final int MINIMUM_KEY_LENGTH = 16;
    
    private static volatile boolean defaultKeyWarningLogged = false;

    private PasswordEncryptionUtil() {
    }

    /**
     * Resolve a password value that may be plain text, encrypted, or an environment variable reference.
     * 
     * @param passwordValue The password value from configuration
     * @return The resolved plain text password
     */
    public static String resolvePassword(String passwordValue) {
        if (passwordValue == null || passwordValue.isEmpty()) {
            return passwordValue;
        }

        if (isEnvironmentVariable(passwordValue)) {
            return resolveEnvironmentVariable(passwordValue);
        }

        if (isEncrypted(passwordValue)) {
            return decrypt(passwordValue);
        }

        return passwordValue;
    }

    /**
     * Check if the password value is an environment variable reference.
     */
    public static boolean isEnvironmentVariable(String value) {
        return value != null && value.startsWith(ENV_PREFIX) && value.endsWith(ENV_SUFFIX);
    }

    /**
     * Check if the password value is encrypted.
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX) && value.endsWith(ENCRYPTED_SUFFIX);
    }

    /**
     * Resolve an environment variable reference.
     * Format: ENV(VARIABLE_NAME)
     * 
     * @param envRef The environment variable reference in format ENV(VARIABLE_NAME)
     * @return The value of the environment variable
     * @throws IllegalArgumentException if the format is invalid
     * @throws IllegalStateException if the environment variable is not set
     */
    public static String resolveEnvironmentVariable(String envRef) {
        if (!isEnvironmentVariable(envRef)) {
            throw new IllegalArgumentException("Not an environment variable reference: " + envRef);
        }

        String varName = envRef.substring(ENV_PREFIX.length(), envRef.length() - ENV_SUFFIX.length());
        String value = System.getenv(varName);

        if (value == null) {
            String errorMsg = "Environment variable not found: " + varName + 
                    ". Please set this environment variable or use a different password format.";
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        return value;
    }

    /**
     * Encrypt a plain text password.
     * 
     * @param plainText The plain text password
     * @return Encrypted password in format ENC(base64data)
     */
    public static String encrypt(String plainText) {
        return encrypt(plainText, getEncryptionKey());
    }

    /**
     * Encrypt a plain text password with a specific key.
     * 
     * @param plainText The plain text password
     * @param encryptionKey The encryption key
     * @return Encrypted password in format ENC(base64data)
     */
    public static String encrypt(String plainText, String encryptionKey) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }

        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_LENGTH];
            random.nextBytes(salt);

            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);

            SecretKey key = deriveKey(encryptionKey, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[salt.length + iv.length + cipherText.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(iv, 0, combined, salt.length, iv.length);
            System.arraycopy(cipherText, 0, combined, salt.length + iv.length, cipherText.length);

            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined) + ENCRYPTED_SUFFIX;

        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                 InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException |
                 InvalidKeySpecException e) {
            log.error("Failed to encrypt password: " + e.getMessage());
            throw new RuntimeException("Password encryption failed", e);
        }
    }

    /**
     * Decrypt an encrypted password.
     * 
     * @param encryptedValue The encrypted password in format ENC(base64data)
     * @return The decrypted plain text password
     */
    public static String decrypt(String encryptedValue) {
        return decrypt(encryptedValue, getEncryptionKey());
    }

    /**
     * Decrypt an encrypted password with a specific key.
     * 
     * @param encryptedValue The encrypted password in format ENC(base64data)
     * @param encryptionKey The encryption key
     * @return The decrypted plain text password
     */
    public static String decrypt(String encryptedValue, String encryptionKey) {
        if (!isEncrypted(encryptedValue)) {
            throw new IllegalArgumentException("Not an encrypted value: " + encryptedValue);
        }

        try {
            String base64Data = encryptedValue.substring(ENCRYPTED_PREFIX.length(), 
                    encryptedValue.length() - ENCRYPTED_SUFFIX.length());
            byte[] combined = Base64.getDecoder().decode(base64Data);

            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[combined.length - SALT_LENGTH - GCM_IV_LENGTH];

            System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(combined, SALT_LENGTH, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, SALT_LENGTH + GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            SecretKey key = deriveKey(encryptionKey, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);

        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                 InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException |
                 InvalidKeySpecException | IllegalArgumentException e) {
            log.error("Failed to decrypt password: " + e.getMessage());
            throw new RuntimeException("Password decryption failed", e);
        }
    }

    private static SecretKey deriveKey(String password, byte[] salt) 
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    private static String getEncryptionKey() {
        String key = System.getenv(DEFAULT_ENCRYPTION_KEY_ENV);
        if (key != null && !key.isEmpty()) {
            if (key.length() < MINIMUM_KEY_LENGTH) {
                log.warn("SECURITY WARNING: Encryption key is shorter than recommended minimum of " + 
                        MINIMUM_KEY_LENGTH + " characters. Consider using a longer key for better security.");
            }
            return key;
        }
        
        if (!defaultKeyWarningLogged) {
            defaultKeyWarningLogged = true;
            log.error("SECURITY WARNING: Using default encryption key! " +
                    "This is a critical security risk in production environments. " +
                    "The default key is publicly known and passwords encrypted with it can be decrypted by anyone. " +
                    "Set the " + DEFAULT_ENCRYPTION_KEY_ENV + " environment variable to a secure, unique key.");
        }
        return DEFAULT_ENCRYPTION_KEY;
    }

    /**
     * Main method for command-line encryption of passwords.
     * Usage: java PasswordEncryptionUtil encrypt <password>
     *        java PasswordEncryptionUtil decrypt <encrypted>
     * 
     * SECURITY WARNING: This utility outputs passwords to stdout.
     * Ensure that command output is not captured in logs or shell history.
     * Consider using input redirection or environment variables for sensitive data.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: PasswordEncryptionUtil <encrypt|decrypt> <value>");
            System.out.println("  encrypt <password>  - Encrypt a plain text password");
            System.out.println("  decrypt <encrypted> - Decrypt an encrypted password");
            System.out.println();
            System.out.println("Set NEMAKI_ENCRYPTION_KEY environment variable for custom encryption key.");
            System.out.println();
            System.out.println("SECURITY WARNING: This utility outputs passwords to stdout.");
            System.out.println("Ensure command output is not captured in logs or shell history.");
            return;
        }

        String command = args[0];
        String value = args[1];

        if ("encrypt".equalsIgnoreCase(command)) {
            String encrypted = encrypt(value);
            System.out.println("Encrypted: " + encrypted);
        } else if ("decrypt".equalsIgnoreCase(command)) {
            System.err.println("SECURITY WARNING: Decrypted password will be displayed on screen.");
            String decrypted = decrypt(value);
            System.out.println("Decrypted: " + decrypted);
        } else {
            System.out.println("Unknown command: " + command);
        }
    }
}
