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

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the provenance event actually records about the thing that was captured.
 *
 * <p>Before P1-1(b) the snapshot named the source and the target folder but not the bytes, not
 * the conversation they came from, and not who caused the import. An object id is not a
 * substitute for a digest — the object can be updated afterwards — and "the connector did it" is
 * not the responsible agent the InterPARES A.1 identity attributes ask for.
 *
 * <p>These call the production builder directly. Reaching it through {@code emitLineageEvent}
 * requires a resolved emitter, and an unwired one fails long before the snapshot exists — the
 * exact shape that let an earlier test in this area pass while proving nothing.
 */
class IngestEvidenceSnapshotTest {

    private static ConnectorDefinition connector() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("slack-1");
        c.setSourceSystem("slack");
        c.setSourceArchetype(SourceArchetype.CHAT_CONTEXT);
        return c;
    }

    private static ExternalIngestRequest request(Map<String, Object> metadata) {
        ExternalIngestRequest r = new ExternalIngestRequest();
        r.setSourceObjectId("F07J2K9QX1M");
        r.setSourceObjectType("file");
        r.setMetadata(metadata);
        return r;
    }

    @Test
    @DisplayName("the digest of the stored bytes is recorded, with its algorithm")
    void contentHashIsRecorded() {
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1",
                "9f2c4e1a7b8d3056c9e4f1a2b7d8e3f0c5a6b9d2e7f4a1c8b3d6e9f2a5c8b1d4", "otsuka");

        assertEquals("9f2c4e1a7b8d3056c9e4f1a2b7d8e3f0c5a6b9d2e7f4a1c8b3d6e9f2a5c8b1d4",
                snapshot.get("contentHash"),
                "an object id can be updated later; the digest is what ties this event to bytes");
        assertEquals("SHA-256", snapshot.get("contentHashAlgorithm"),
                "a bare hex string is not self-describing to a verifier years from now");
    }

    @Test
    @DisplayName("no content is stated as such, not left blank")
    void absentContentIsStated() {
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1", null, "otsuka");

        String recorded = snapshot.get("contentHash");
        assertNotNull(recorded,
                "silence would leave a reader unable to tell 'nothing was stored' from "
                        + "'something was stored and we failed to hash it'");
        assertTrue(recorded.startsWith("none:"), recorded);
        assertFalse(snapshot.containsKey("contentHashAlgorithm"),
                "there is no algorithm where there is no digest");
    }

    @Test
    @DisplayName("the principal who caused the import is recorded")
    void ingestedByIsRecorded() {
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1", "abc", "otsuka");

        assertEquals("otsuka", snapshot.get("ingestedBy"),
                "A.1 asks for a responsible agent; 'the connector' is not one");
    }

    @Test
    @DisplayName("conversation context travels with the event, not only on the object")
    void chatContextIsRecorded() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workspaceId", "T01ABCD");
        metadata.put("channelId", "C02AMPJAY");
        metadata.put("channelName", "board-minutes");
        metadata.put("threadId", "1720000000.000100");
        metadata.put("messageId", "1720000000.000200");
        metadata.put("selectionReason", "retention policy R-7");
        metadata.put("evidenceScope", "thread");
        metadata.put("captureWindowStart", "2026-07-14T02:00:00Z");
        metadata.put("captureWindowEnd", "2026-07-14T03:00:00Z");

        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(metadata), "folder-1", "abc", "otsuka");

        // The object's properties can be edited; the event is the record of what was observed
        // at capture time, so the context has to be in the event as well.
        assertEquals("T01ABCD", snapshot.get("chat.workspaceId"));
        assertEquals("C02AMPJAY", snapshot.get("chat.channelId"));
        assertEquals("board-minutes", snapshot.get("chat.channelName"));
        assertEquals("1720000000.000100", snapshot.get("chat.threadId"));
        assertEquals("retention policy R-7", snapshot.get("chat.selectionReason"));
        assertEquals("thread", snapshot.get("chat.evidenceScope"));
        assertEquals("2026-07-14T02:00:00Z", snapshot.get("chat.captureWindowStart"));
        assertEquals("2026-07-14T03:00:00Z", snapshot.get("chat.captureWindowEnd"));
    }

    @Test
    @DisplayName("absent chat metadata adds nothing rather than empty keys")
    void absentChatContextAddsNothing() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workspaceId", "T01ABCD");
        metadata.put("channelId", "   ");          // blank must not become a recorded fact

        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(metadata), "folder-1", "abc", "otsuka");

        assertEquals("T01ABCD", snapshot.get("chat.workspaceId"));
        assertFalse(snapshot.containsKey("chat.channelId"),
                "an empty value recorded as fact is worse than an absent one");
        assertFalse(snapshot.containsKey("chat.threadId"));
    }

    @Test
    @DisplayName("the fields that were already there are still there")
    void existingFieldsAreUnchanged() {
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1", "abc", "otsuka");

        assertEquals("slack", snapshot.get("sourceSystem"));
        assertEquals("CHAT_CONTEXT", snapshot.get("sourceArchetype"));
        assertEquals("F07J2K9QX1M", snapshot.get("sourceObjectId"));
        assertEquals("file", snapshot.get("sourceObjectType"));
        assertEquals("folder-1", snapshot.get("targetFolderId"));
    }
}
