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
 * <h2>The request JSON obeys the same rule</h2>
 *
 * <p>The note orchestrator transiently injects attachment bytes into request metadata as
 * {@code contentBase64}, and a failure inside that window used to serialize them verbatim into
 * {@code originalRequestJson} — the same bytes the payload rule refuses to store, through a
 * side door (external review, Codex/audit N1). {@code buildDlqRecord} now strips them and
 * records the count; replay restores only what the encrypted channel holds, and the next poll's
 * dedupe-skip fall-through re-fetches orchestrator attachments.
 *
 * <h2>What this does NOT cover</h2>
 *
 * <p>These tests drive the decision points directly — including {@code buildDlqRecord}, which
 * assembles everything up to the upsert. The remaining wiring ({@code saveToDlq}'s payload
 * encryption, upsert and attachment) is not covered, because it needs a live CouchDB. Stated
 * rather than implied.
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
    // ── The request JSON is byte-free ────────────────────────────────────────────────────

    private static ExternalIngestRequest noteRequestWithInjectedBytes() {
        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setProfileId("p1");
        request.setConnectorId("c1");
        request.setRepositoryId("bedroom");
        request.setSourceObjectId("page-1");
        request.setSourceObjectType("page");
        request.setFileName("page.md");
        java.util.Map<String, Object> att = new java.util.LinkedHashMap<>();
        att.put("filename", "diagram.png");
        att.put("mimeType", "image/png");
        att.put("contentBase64", java.util.Base64.getEncoder()
                .encodeToString("SECRET-ATTACHMENT-BYTES".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("pageId", "page-1");
        metadata.put("attachments", new java.util.ArrayList<>(java.util.List.of(att)));
        // A caller can also put one at the top level; the rule is any depth.
        metadata.put("contentBase64", "dG9wLWxldmVs");
        request.setMetadata(metadata);
        return request;
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the DLQ row's request JSON carries no contentBase64, at any depth")
    void theStoredRequestJsonIsByteFree() {
        IngestDeadLetterRecord row = IngestJobService.buildDlqRecord(
                noteRequestWithInjectedBytes(), "connector timed out", null,
                "2026-08-23T00:00:00Z");

        org.junit.jupiter.api.Assertions.assertFalse(
                row.getOriginalRequestJson().contains("contentBase64"),
                "ingested bytes entered nemaki_conf in the clear through the request JSON — the "
                        + "side door around the encrypted-payload rule: "
                        + row.getOriginalRequestJson());
        // The ENCODED form, not the raw string: the raw bytes never appear in JSON anyway
        // (base64), so asserting their absence would pass with the strip removed — a
        // non-discriminating assertion wearing a reassuring name.
        org.junit.jupiter.api.Assertions.assertFalse(
                row.getOriginalRequestJson().contains(java.util.Base64.getEncoder()
                        .encodeToString("SECRET-ATTACHMENT-BYTES"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                "the base64 value itself survived");
        org.junit.jupiter.api.Assertions.assertEquals(2, row.getRequestBinaryStrippedCount(),
                "the strip happened but was not recorded, so a replay reads as complete when the "
                        + "attachments are gone");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("what a replay needs survives the strip")
    void replayRelevantFieldsSurvive() {
        IngestDeadLetterRecord row = IngestJobService.buildDlqRecord(
                noteRequestWithInjectedBytes(), "connector timed out", null,
                "2026-08-23T00:00:00Z");

        String json = row.getOriginalRequestJson();
        // The attachment ENTRY stays — filename and mime type are what tell the next poll and
        // the operator which bytes are missing. Only the bytes go.
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("diagram.png"), json);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("image/png"), json);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("page-1"), json);
        org.junit.jupiter.api.Assertions.assertEquals("p1", row.getProfileId());
        org.junit.jupiter.api.Assertions.assertEquals(1, row.getFailureCount());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a request with no bytes is stored verbatim, count 0 — the control")
    void aByteFreeRequestIsUntouched() {
        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setProfileId("p1");
        request.setConnectorId("c1");
        request.setRepositoryId("bedroom");
        request.setSourceObjectId("msg-1");
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("channelId", "C123");
        request.setMetadata(metadata);

        IngestDeadLetterRecord row = IngestJobService.buildDlqRecord(
                request, "boom", null, "2026-08-23T00:00:00Z");

        org.junit.jupiter.api.Assertions.assertEquals(0, row.getRequestBinaryStrippedCount(),
                "a clean request was reported as stripped, so every DLQ row would warn about "
                        + "missing bytes that never existed");
        org.junit.jupiter.api.Assertions.assertTrue(
                row.getOriginalRequestJson().contains("C123"));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the live request is not mutated by the act of saving it")
    void savingDoesNotMutateTheCallersRequest() {
        // The orchestrator is still holding this request when the save happens; its own finally
        // strips the bytes later. A save that stripped the LIVE map would be a hidden mutation
        // of an argument — and would also break the attachment import if the save ran first.
        ExternalIngestRequest request = noteRequestWithInjectedBytes();
        IngestJobService.buildDlqRecord(request, "boom", null, "2026-08-23T00:00:00Z");

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> atts =
                (java.util.List<java.util.Map<String, Object>>) request.getMetadata().get("attachments");
        org.junit.jupiter.api.Assertions.assertTrue(atts.get(0).containsKey("contentBase64"),
                "buildDlqRecord stripped the caller's live metadata as a side effect");
    }

}
