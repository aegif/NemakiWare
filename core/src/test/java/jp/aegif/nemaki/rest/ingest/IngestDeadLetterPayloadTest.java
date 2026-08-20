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
package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ingested bytes held in the dead-letter queue are encrypted, or they are not held.
 *
 * <p>The queue lives in {@code nemaki_conf} — the configuration database, with no ACL of its own
 * and no retention. Attaching the raw ingested content there put a plaintext copy of every failed
 * import somewhere nothing was watching (external review). For a product whose subject is
 * evidence, storing it in the clear is not an acceptable fallback for a missing key: the payload
 * is dropped instead, and the entry records why, keeping the metadata that names the source item
 * so it can be fetched again.
 *
 * <h2>What this does NOT cover</h2>
 *
 * <p>These tests drive the two decision points directly. The wiring — that {@code saveToDlq}
 * calls them and writes {@code payloadDropReason} when the encryption fails — is not covered,
 * because {@code saveToDlq} needs a live CouchDB. Stated rather than implied.
 */
class IngestDeadLetterPayloadTest {

    private static Method method(String name, Class<?>... args) throws Exception {
        Method m = IngestJobService.class.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    private static byte[] encrypt(byte[] plain) throws Exception {
        try {
            return (byte[]) method("encryptDeadLetterPayload", byte[].class)
                    .invoke(new IngestJobService(), (Object) plain);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static byte[] decrypt(byte[] stored) throws Exception {
        try {
            return (byte[]) method("decryptDeadLetterPayload", byte[].class)
                    .invoke(new IngestJobService(), (Object) stored);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    @DisplayName("what is stored is not the ingested bytes, and it round-trips")
    void payloadIsEncryptedAndRoundTrips() throws Exception {
        byte[] plain = "confidential board minutes".getBytes(StandardCharsets.UTF_8);

        byte[] stored = encrypt(plain);

        assertFalse(new String(stored, StandardCharsets.UTF_8).contains("board minutes"),
                "the ingested bytes must not be readable in the configuration database");
        assertTrue(new String(stored, StandardCharsets.UTF_8).startsWith("ENC("),
                "stored in the same envelope the rest of the product uses");
        assertArrayEquals(plain, decrypt(stored),
                "a retry has to get the original bytes back, or the entry is not retryable");
    }

    @Test
    @DisplayName("an entry written before encryption existed is still retryable")
    void legacyPlaintextPayloadsStillLoad() throws Exception {
        // Refusing these would turn an upgrade into data loss: the entry is the only record that
        // the source item was lost.
        byte[] legacy = "written by an older build".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(legacy, decrypt(legacy));
    }

    @Test
    @DisplayName("a payload that claims to be encrypted but will not decrypt is refused, not returned")
    void undecryptablePayloadIsRefused() throws Exception {
        // Returning the ciphertext as if it were content would feed garbage into a retry and
        // record it as a successful re-import.
        byte[] corrupt = "ENC(bm90LXJlYWxseS1lbmNyeXB0ZWQ=)".getBytes(StandardCharsets.UTF_8);

        Exception e = assertThrows(Exception.class, () -> decrypt(corrupt));
        assertTrue(e.getMessage() != null && e.getMessage().contains("NEMAKI_ENCRYPTION_KEY"),
                "the message should point at the key, which is the thing to check. Got: "
                        + e.getMessage());
    }

    @Test
    @DisplayName("an empty payload is left alone rather than wrapped")
    void emptyPayloadIsUntouched() throws Exception {
        assertArrayEquals(new byte[0], decrypt(new byte[0]));
    }
}
