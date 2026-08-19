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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                IngestLineageEmitter.CapturedContent.hashed(
                        "9f2c4e1a7b8d3056c9e4f1a2b7d8e3f0c5a6b9d2e7f4a1c8b3d6e9f2a5c8b1d4"),
                "otsuka", null);

        assertEquals("9f2c4e1a7b8d3056c9e4f1a2b7d8e3f0c5a6b9d2e7f4a1c8b3d6e9f2a5c8b1d4",
                snapshot.get("contentHash"),
                "an object id can be updated later; the digest is what ties this event to bytes");
        assertEquals("SHA-256", snapshot.get("contentHashAlgorithm"),
                "a bare hex string is not self-describing to a verifier years from now");
    }

    @Test
    @DisplayName("absence of content is a separate field, never prose in the digest field")
    void absentContentIsItsOwnField() {
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1", IngestLineageEmitter.CapturedContent.none(), "otsuka", null);

        assertEquals("false", snapshot.get("contentStored"),
                "a reader must be able to tell 'nothing was stored' from 'something was stored "
                        + "and we failed to hash it' — but not by parsing the digest field");
        assertFalse(snapshot.containsKey("contentHash"),
                "putting English in a digest field forces a future consumer to invent prefix "
                        + "parsing, and the evidence report schema requires hex");
        assertFalse(snapshot.containsKey("contentHashAlgorithm"),
                "there is no algorithm where there is no digest");
    }

    @Test
    @DisplayName("stored content marks contentStored true alongside the digest")
    void storedContentIsMarked() {
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1", IngestLineageEmitter.CapturedContent.hashed("abc"), "otsuka", null);

        assertEquals("true", snapshot.get("contentStored"));
        assertEquals("abc", snapshot.get("contentHash"));
    }

    @Test
    @DisplayName("who ran it and on whose authority are separate facts")
    void agentIsRecordedAsTwoFields() {
        Map<String, String> authenticated = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1", IngestLineageEmitter.CapturedContent.hashed("abc"), "otsuka", null);
        assertEquals("otsuka", authenticated.get("executedBy"),
                "A.1 asks for a responsible agent; 'the connector' is not one");
        assertFalse(authenticated.containsKey("onBehalfOf"),
                "a direct import has no separate authority to name");

        Map<String, String> delegated = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1", IngestLineageEmitter.CapturedContent.hashed("abc"), "svc-scheduler", "otsuka");
        assertEquals("svc-scheduler", delegated.get("executedBy"));
        assertEquals("otsuka", delegated.get("onBehalfOf"),
                "one field cannot answer both 'who ran it' and 'on whose authority'");
    }

    @Test
    @DisplayName("an unauthenticated (scheduled/webhook) import says so rather than omitting it")
    void absentAgentIsStated() {
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1", IngestLineageEmitter.CapturedContent.hashed("abc"), null, null);

        String executedBy = snapshot.get("executedBy");
        assertNotNull(executedBy,
                "leaving the key out made an absent agent look like a delegated import whose "
                        + "name simply was not filled in");
        assertTrue(executedBy.startsWith("service:"), executedBy);
    }

    @Test
    @DisplayName("content carried forward is stored but unhashed — a third state, not 'no content'")
    void carriedOverContentIsItsOwnState() {
        // A check-in with no stream keeps the previous version's bytes. Reporting that as
        // contentStored=false would describe the repository wrongly; reporting a digest would
        // be a lie, since this import never read those bytes.
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1",
                IngestLineageEmitter.CapturedContent.storedWithoutDigest(
                        "the new version carried the previous version's content forward"),
                "otsuka", null);

        assertEquals("true", snapshot.get("contentStored"),
                "bytes are present, so saying otherwise misdescribes what is held");
        assertFalse(snapshot.containsKey("contentHash"),
                "this import never read those bytes; any digest here would be invented");
        assertTrue(snapshot.get("contentHashUnavailable").contains("carried the previous"),
                snapshot.get("contentHashUnavailable"));
    }

    @Test
    @DisplayName("the production decision reads the object: held bytes are not reported as none")
    void productionDecisionReadsTheStoredObject() throws Exception {
        // The builder tests above only prove the snapshot renders what it is given. This drives
        // the code that CHOOSES, which is where every previous version of this logic was wrong
        // (external review): metadata-only and no-change updates retain their attachment.
        CanonicalImportServiceImpl service = new CanonicalImportServiceImpl();
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                org.mockito.Mockito.mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        java.lang.reflect.Field f = CanonicalImportServiceImpl.class
                .getDeclaredField("contentService");
        f.setAccessible(true);
        f.set(service, contentService);

        jp.aegif.nemaki.model.Document withContent = new jp.aegif.nemaki.model.Document();
        withContent.setAttachmentNodeId("att-1");
        org.mockito.Mockito.when(contentService.getContent("bedroom", "obj-1"))
                .thenReturn(withContent);
        // The reference must resolve; an unresolvable one is UNKNOWN, tested separately.
        org.mockito.Mockito.when(contentService.getAttachment("bedroom", "att-1"))
                .thenReturn(new jp.aegif.nemaki.model.AttachmentNode());
        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.STORED,
                service.describeCapturedContent("bedroom", "obj-1", null).state(),
                "the object holds bytes from an earlier import; 'none' would misdescribe it");

        jp.aegif.nemaki.model.Document withoutContent = new jp.aegif.nemaki.model.Document();
        org.mockito.Mockito.when(contentService.getContent("bedroom", "obj-2"))
                .thenReturn(withoutContent);
        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.NONE,
                service.describeCapturedContent("bedroom", "obj-2", null).state());

        org.mockito.Mockito.when(contentService.getContent("bedroom", "obj-3"))
                .thenThrow(new IllegalStateException("couchdb unreachable"));
        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.UNKNOWN,
                service.describeCapturedContent("bedroom", "obj-3", null).state(),
                "a failed read must not become the positive claim that nothing is stored");

        // The DAO layer catches its own failures and returns NULL rather than throwing, so this
        // — not the exception above — is what a real read failure looks like. Mapping it to
        // NONE would assert emptiness over an object that was just written successfully.
        org.mockito.Mockito.when(contentService.getContent("bedroom", "obj-4")).thenReturn(null);
        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.UNKNOWN,
                service.describeCapturedContent("bedroom", "obj-4", null).state(),
                "a null read is the production failure shape, and it is not evidence of emptiness");

        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.STORED,
                service.describeCapturedContent("bedroom", "obj-3", "abc").state(),
                "a hash this import computed needs no read-back at all");
    }

    @Test
    @DisplayName("the call site picks the actor: delegated is admitted-unknown, direct is the caller")
    void productionDecisionPicksTheActor() {
        // The builder test below only proves the snapshot renders what it is handed. This drives
        // the DECISION, which is what a revert would break while leaving every other test green
        // (external review).
        ImportProfileDefinition delegated = new ImportProfileDefinition();
        delegated.setProfileId("p-1");
        delegated.setDelegated(true);
        delegated.setCreatedByUserId("otsuka");

        org.apache.chemistry.opencmis.commons.server.CallContext ctx =
                org.mockito.Mockito.mock(
                        org.apache.chemistry.opencmis.commons.server.CallContext.class);
        org.mockito.Mockito.when(ctx.getUsername()).thenReturn("svc-caller");

        String executed = CanonicalImportServiceImpl.resolveExecutedBy(delegated, ctx);
        assertTrue(executed.startsWith("unknown:"),
                "the synthesized context names the authority, not the actor — a service label "
                        + "here would assert an actor nobody observed; got: " + executed);
        assertEquals("otsuka", CanonicalImportServiceImpl.resolveOnBehalfOf(delegated));

        ImportProfileDefinition direct = new ImportProfileDefinition();
        direct.setProfileId("p-2");
        direct.setDelegated(false);
        assertEquals("svc-caller", CanonicalImportServiceImpl.resolveExecutedBy(direct, ctx),
                "a direct import HAS an observed actor and must not discard it");
        assertNull(CanonicalImportServiceImpl.resolveOnBehalfOf(direct),
                "a direct import has no separate authority to name");
    }

    @Test
    @DisplayName("a blank or unresolvable attachment reference is not 'stored'")
    void attachmentReferenceIsResolved() throws Exception {
        CanonicalImportServiceImpl service = new CanonicalImportServiceImpl();
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                org.mockito.Mockito.mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        java.lang.reflect.Field f = CanonicalImportServiceImpl.class
                .getDeclaredField("contentService");
        f.setAccessible(true);
        f.set(service, contentService);

        jp.aegif.nemaki.model.Document blank = new jp.aegif.nemaki.model.Document();
        blank.setAttachmentNodeId("   ");
        org.mockito.Mockito.when(contentService.getContent("bedroom", "blank")).thenReturn(blank);
        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.NONE,
                service.describeCapturedContent("bedroom", "blank", null).state(),
                "the repository treats a blank id as no attachment, and so must the evidence");

        jp.aegif.nemaki.model.Document dangling = new jp.aegif.nemaki.model.Document();
        dangling.setAttachmentNodeId("att-missing");
        org.mockito.Mockito.when(contentService.getContent("bedroom", "dangling"))
                .thenReturn(dangling);
        org.mockito.Mockito.when(contentService.getAttachment("bedroom", "att-missing"))
                .thenReturn(null);
        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.UNKNOWN,
                service.describeCapturedContent("bedroom", "dangling", null).state(),
                "a reference that resolves to nothing is not proof that bytes are held");
    }

    @Test
    @DisplayName("the delegated-profile actor is recorded as unknown, not as a plausible service")
    void delegatedActorIsHonestlyUnknown() {
        // The call site cannot tell a manually-driven delegated import from a scheduled one, so
        // naming a service would assert an actor nobody observed. The gap is stated instead.
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1",
                IngestLineageEmitter.CapturedContent.hashed("abc"),
                "unknown: delegated profile p-1 — execution origin is not recorded yet", "otsuka");

        assertTrue(snapshot.get("executedBy").startsWith("unknown:"),
                "a label that looks like an identity is worse than an admitted gap");
        assertEquals("otsuka", snapshot.get("onBehalfOf"),
                "the authority IS known for a delegated profile: its creator");
    }

    @Test
    @DisplayName("SHA-256 of empty content is a digest, and empty content that was stored has one")
    void emptyStoredContentStillHasADigest() throws Exception {
        // computeContentHash used to return null for zero bytes, so a legitimately empty file
        // that WAS stored reported "no content stored" — describing the repository wrongly.
        java.lang.reflect.Method m = CanonicalImportServiceImpl.class
                .getDeclaredMethod("computeContentHash", byte[].class);
        m.setAccessible(true);

        String emptyDigest = (String) m.invoke(null, (Object) new byte[0]);
        assertNotNull(emptyDigest, "zero bytes hash to a perfectly valid SHA-256");
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                emptyDigest, "the well-known SHA-256 of the empty input");
        assertNull(m.invoke(null, (Object) null), "only ABSENT content is absence");
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
                connector(), request(metadata), "folder-1", IngestLineageEmitter.CapturedContent.hashed("abc"), "otsuka", null);

        // The object's properties can be edited; the event is the record of what was observed
        // at capture time, so the context has to be in the event as well.
        assertEquals("T01ABCD", snapshot.get("chat.workspaceId"));
        assertEquals("C02AMPJAY", snapshot.get("chat.channelId"));
        assertEquals("board-minutes", snapshot.get("chat.channelName"));
        assertEquals("1720000000.000100", snapshot.get("chat.threadId"));
        assertEquals("1720000000.000200", snapshot.get("chat.messageId"),
                "supplied but never asserted, so a dropped mapping would have gone unnoticed");
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
                connector(), request(metadata), "folder-1", IngestLineageEmitter.CapturedContent.hashed("abc"), "otsuka", null);

        assertEquals("T01ABCD", snapshot.get("chat.workspaceId"));
        assertFalse(snapshot.containsKey("chat.channelId"),
                "an empty value recorded as fact is worse than an absent one");
        assertFalse(snapshot.containsKey("chat.threadId"));
    }

    @Test
    @DisplayName("the fields that were already there are still there")
    void existingFieldsAreUnchanged() {
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1", IngestLineageEmitter.CapturedContent.hashed("abc"), "otsuka", null);

        assertEquals("slack", snapshot.get("sourceSystem"));
        assertEquals("CHAT_CONTEXT", snapshot.get("sourceArchetype"));
        assertEquals("F07J2K9QX1M", snapshot.get("sourceObjectId"));
        assertEquals("file", snapshot.get("sourceObjectType"));
        assertEquals("folder-1", snapshot.get("targetFolderId"));
    }
}
