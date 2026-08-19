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

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Property;

import java.util.ArrayList;
import java.util.List;

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

    private static final String UNCHANGED_BYTES = "unchanged bytes";
    private static final String UNCHANGED_SHA256 =
            "e35b2252583cefc6c764da00830c86b8d929c925703a88ebcb622c8f0caf24f9";

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
    @DisplayName("content carried forward is undetermined — neither 'none' nor an unbacked 'stored'")
    void carriedOverContentIsItsOwnState() {
        // A check-in with no stream keeps the previous version's bytes. Reporting that as
        // contentStored=false would describe the repository wrongly, and a digest would be
        // invented — but "true" is not available either: this import never established that
        // those bytes are there, and no cheap check could (external review). So the third
        // state is "undetermined", carrying the observation as prose.
        Map<String, String> snapshot = new IngestLineageEmitter().buildV1Snapshot(
                connector(), request(null), "folder-1",
                IngestLineageEmitter.CapturedContent.unknown(
                        "the new version carried the previous version's content forward"),
                "otsuka", null);

        assertEquals("unknown", snapshot.get("contentStored"),
                "'false' misdescribes the repository and 'true' asserts bytes nobody checked");
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
        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.UNKNOWN,
                service.describeCapturedContent("bedroom", "obj-1", null).state(),
                "the object REFERENCES content, which is neither 'none' nor proof that bytes "
                        + "are held — no cheap check can tell, so the gap is stated");
        assertTrue(service.describeCapturedContent("bedroom", "obj-1", null).reason()
                        .contains("att-1"),
                "the reference itself is an observation and belongs in the record");

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

        jp.aegif.nemaki.model.Document referencing = new jp.aegif.nemaki.model.Document();
        referencing.setAttachmentNodeId("att-1");
        org.mockito.Mockito.when(contentService.getContent("bedroom", "ref"))
                .thenReturn(referencing);
        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.UNKNOWN,
                service.describeCapturedContent("bedroom", "ref", null).state(),
                "a reference is not proof that bytes are held");

        // No attachment lookup may happen here at all. getAttachment leaks a stream and swallows
        // its own failure; getAttachmentRef falls back to the stored length field so it cannot
        // see the binary; getAttachmentActualSize downloads compressed ones. All three were
        // tried and all three were wrong (external review) — the answer is fixity, P1-2.
        org.mockito.Mockito.verify(contentService, org.mockito.Mockito.never())
                .getAttachment(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(contentService, org.mockito.Mockito.never())
                .getAttachmentRef(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(contentService, org.mockito.Mockito.never())
                .getAttachmentActualSize(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("the import actually calls the decisions: neither is bypassed at the call site")
    void theCallSiteUsesBothDecisions() {
        // Every test above pins a decision in isolation. None of them notices if the call site
        // stops asking — hardcoding CapturedContent.none() or reverting the actor to
        // getUsername() would leave them all green (external review). So this drives the real
        // import and reads what the emitter was actually handed.
        CanonicalImportServiceImpl service = new CanonicalImportServiceImpl();
        jp.aegif.nemaki.rest.ingest.ConnectorDefinitionService connectorService =
                org.mockito.Mockito.mock(
                        jp.aegif.nemaki.rest.ingest.ConnectorDefinitionService.class);
        jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService profileService =
                org.mockito.Mockito.mock(
                        jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService.class);
        jp.aegif.nemaki.cmis.service.ObjectService objectService =
                org.mockito.Mockito.mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                org.mockito.Mockito.mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);

        // Delegated, so the two candidate actors DIFFER: resolveExecutedBy admits it does not
        // know, while the synthesized context names the profile's creator.
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setDelegated(true);
        profile.setCreatedByUserId("otsuka");
        org.mockito.Mockito.when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(jp.aegif.nemaki.rest.ingest.SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("google_drive");
        org.mockito.Mockito.when(connectorService.get("c1")).thenReturn(connector);
        org.mockito.Mockito.when(objectService.createDocument(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("bedroom"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("folder-1"),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn("new-obj-id");

        // No content stream is supplied, so the content state can only come from reading the
        // object — which is exactly the decision the call site must delegate.
        // The object REFERENCES content, so the honest state is UNKNOWN and the reason names
        // the attachment. That pair is reachable only by actually asking — a hardcoded none(),
        // a hardcoded none()/hashed(), or a state guessed from which branch ran, all fail.
        jp.aegif.nemaki.model.Document stored = new jp.aegif.nemaki.model.Document();
        stored.setAttachmentNodeId("att-1");
        org.mockito.Mockito.when(contentService.getContent("bedroom", "new-obj-id"))
                .thenReturn(stored);

        RecordingEmitter emitter = new RecordingEmitter();
        service.setIngestLineageEmitter(emitter);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("file-123");
        req.setSourceObjectType("files");
        req.setFileName("test.txt");
        org.apache.chemistry.opencmis.commons.server.CallContext ctx =
                org.mockito.Mockito.mock(
                        org.apache.chemistry.opencmis.commons.server.CallContext.class);
        org.mockito.Mockito.when(ctx.getUsername()).thenReturn("otsuka");

        assertTrue(service.execute(ctx, req).isSuccess(), "control: the import must succeed");

        assertNotNull(emitter.captured, "the import must record provenance at all");
        assertEquals(IngestLineageEmitter.CapturedContent.ContentState.UNKNOWN,
                emitter.captured.state(),
                "the call site must ask what the repository holds, not assert a constant");
        assertTrue(emitter.captured.reason() != null
                        && emitter.captured.reason().contains("att-1"),
                "only the real decision can name the attachment it saw; a constant cannot. "
                        + "Got: " + emitter.captured.reason());
        assertTrue(emitter.executedBy != null && emitter.executedBy.startsWith("unknown:"),
                "a delegated run has no observed actor; passing the context's username would "
                        + "name the authority as the executor. Got: " + emitter.executedBy);
        assertEquals("otsuka", emitter.onBehalfOf,
                "the authority IS known and must reach the emitter");
    }

    @Test
    @DisplayName("a re-import that stores no bytes emits no digest, on either update policy")
    void reimportWithoutStoringBytesEmitsNoDigest() {
        // compareContent is pinned in isolation elsewhere, but that proved nothing about the
        // import: dropping the assignment that feeds its answer back into computedHash — or the
        // matching clear on the metadata-only policy — left every test green while the record
        // went back to certifying a digest for bytes this import threw away (external review).
        // So this drives the two real branches.
        for (String updatePolicy : new String[]{"version_up_on_content_change",
                "update_metadata_only"}) {
            RecordingEmitter emitter = runReimport(updatePolicy);
            assertNotNull(emitter.captured,
                    "control: the re-import must reach the emitter (" + updatePolicy + ")");
            assertNull(emitter.captured.digest(),
                    "no bytes were stored under " + updatePolicy + ": the incoming ones were "
                            + "discarded, and the hash they matched sits in a MUTABLE aspect "
                            + "property. Recording the digest would certify content this import "
                            + "never stored. Got: " + emitter.captured.digest());
        }
    }

    /** Drives a real re-import of an already-imported document whose recorded hash matches. */
    private static RecordingEmitter runReimport(String updatePolicy) {
        CanonicalImportServiceImpl service = new CanonicalImportServiceImpl();
        jp.aegif.nemaki.rest.ingest.ConnectorDefinitionService connectorService =
                org.mockito.Mockito.mock(
                        jp.aegif.nemaki.rest.ingest.ConnectorDefinitionService.class);
        jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService profileService =
                org.mockito.Mockito.mock(
                        jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService.class);
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                org.mockito.Mockito.mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                org.mockito.Mockito.mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setContentService(contentService);
        service.setContentDaoService(contentDaoService);
        service.setObjectService(
                org.mockito.Mockito.mock(jp.aegif.nemaki.cmis.service.ObjectService.class));

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        // The default policy returns a skip before any of this is reached.
        profile.setDedupePolicy("create_new_version");
        profile.setUpdatePolicy(updatePolicy);
        org.mockito.Mockito.when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(jp.aegif.nemaki.rest.ingest.SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("google_drive");
        org.mockito.Mockito.when(connectorService.get("c1")).thenReturn(connector);

        // An already-imported document whose recorded hash MATCHES the incoming bytes.
        Aspect integration = new Aspect();
        integration.setName("nemaki:externalIntegration");
        integration.setProperties(new ArrayList<>(List.of(
                new Property("nemaki:sourceObjectId", "file-123"),
                new Property("nemaki:sourceSystem", "google_drive"),
                new Property("nemaki:sourceObjectType", "files"),
                new Property("nemaki:contentHash", UNCHANGED_SHA256))));
        jp.aegif.nemaki.model.Document existing = new jp.aegif.nemaki.model.Document();
        existing.setId("existing-1");
        existing.setType("cmis:document");
        existing.setName("test.txt");
        existing.setAttachmentNodeId("att-1");
        existing.setAspects(new ArrayList<>(List.of(integration)));
        org.mockito.Mockito.when(contentDaoService.getChildren("bedroom", "folder-1"))
                .thenReturn(new ArrayList<>(List.of(existing)));
        org.mockito.Mockito.when(contentService.getContent("bedroom", "existing-1"))
                .thenReturn(existing);

        RecordingEmitter emitter = new RecordingEmitter();
        service.setIngestLineageEmitter(emitter);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("file-123");
        req.setSourceObjectType("files");
        req.setFileName("test.txt");
        req.setContentStream(new java.io.ByteArrayInputStream(
                UNCHANGED_BYTES.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        service.execute(org.mockito.Mockito.mock(
                org.apache.chemistry.opencmis.commons.server.CallContext.class), req);
        return emitter;
    }

    /** Captures what the production call site actually hands the emitter. */
    private static final class RecordingEmitter extends IngestLineageEmitter {
        private IngestLineageEmitter.CapturedContent captured;
        private String executedBy;
        private String onBehalfOf;

        @Override
        public String emitLineageEvent(String repositoryId, String objectId, String targetFolderId,
                String documentName, String operationId, ConnectorDefinition connector,
                ExternalIngestRequest request, IngestLineageEmitter.CapturedContent content,
                String executedBy, String onBehalfOf) {
            this.captured = content;
            this.executedBy = executedBy;
            this.onBehalfOf = onBehalfOf;
            return "evt-1";
        }
    }

    @Test
    @DisplayName("custody time is persisted as the object's own age, not today's date")
    void chatImportPersistsCustodyTimeAsObjectAge() {
        // Calling applyChatCapturedAt directly proved the helper worked but not that anything
        // called it, and asserting on the in-memory aspect proved the property was set but not
        // that it was ever stored (external review). So this goes through
        // executeChatContextImport and reads back only what an update() actually persisted.
        java.util.GregorianCalendar createdLongAgo = new java.util.GregorianCalendar();
        createdLongAgo.setTimeInMillis(
                java.time.Instant.parse("2024-03-01T09:00:00Z").toEpochMilli());

        ChatStore store = new ChatStore(createdLongAgo, null);
        runChatImport(store);

        assertEquals(createdLongAgo, store.persisted,
                "an object first imported before this property existed is re-decorated on every "
                        + "poll; stamping the current clock would date years-old holdings from "
                        + "today, which is the opposite of what custody time means. And an "
                        + "in-memory mutation that never reaches update() is not a record.");
    }

    @Test
    @DisplayName("a custody time already on the object is never overwritten")
    void chatImportDoesNotOverwriteExistingCustodyTime() {
        // Now that the value is the object's creation time, a restamp would write the SAME
        // answer — so the rule only bites where the stored value DIFFERS: a legacy stamp, or one
        // a client planted while the property is still READWRITE (P1-1(c)).
        java.util.GregorianCalendar created = new java.util.GregorianCalendar();
        created.setTimeInMillis(java.time.Instant.parse("2024-03-01T09:00:00Z").toEpochMilli());
        java.util.GregorianCalendar alreadyRecorded = new java.util.GregorianCalendar();
        alreadyRecorded.setTimeInMillis(
                java.time.Instant.parse("2023-01-15T00:00:00Z").toEpochMilli());

        ChatStore store = new ChatStore(created, alreadyRecorded);
        runChatImport(store);

        assertEquals(alreadyRecorded, store.persisted,
                "the first observation is the one that means anything; moving it would quietly "
                        + "erase how long the record has actually been held");
    }

    /**
     * A stand-in for storage: {@code getContent} hands back only what an {@code update} persisted,
     * so a property set in memory but never written is invisible — which is the point.
     */
    private static final class ChatStore {
        private final java.util.GregorianCalendar created;
        private Object persisted;

        ChatStore(java.util.GregorianCalendar created, Object initialStamp) {
            this.created = created;
            this.persisted = initialStamp;
        }

        jp.aegif.nemaki.model.Document read() {
            Aspect chatAspect = new Aspect();
            chatAspect.setName("nemaki:chatContextMetadata");
            List<Property> props = new ArrayList<>(List.of(
                    new Property("nemaki:chatChannelId", "C1")));
            if (persisted != null) {
                props.add(new Property("nemaki:chatCapturedAt", persisted));
            }
            chatAspect.setProperties(props);
            jp.aegif.nemaki.model.Document doc = new jp.aegif.nemaki.model.Document();
            doc.setId("chat-1");
            doc.setType("cmis:document");
            doc.setAspects(new ArrayList<>(List.of(chatAspect)));
            doc.setCreated(created);
            return doc;
        }

        void write(jp.aegif.nemaki.model.Content content) {
            if (content == null || content.getAspects() == null) return;
            content.getAspects().stream()
                    .filter(a -> "nemaki:chatContextMetadata".equals(a.getName()))
                    .filter(a -> a.getProperties() != null)
                    .flatMap(a -> a.getProperties().stream())
                    .filter(p -> "nemaki:chatCapturedAt".equals(p.getKey()))
                    .findFirst().ifPresent(p -> persisted = p.getValue());
        }
    }

    private static void runChatImport(ChatStore store) {
        CanonicalImportServiceImpl service = new CanonicalImportServiceImpl();
        jp.aegif.nemaki.rest.ingest.ConnectorDefinitionService connectorService =
                org.mockito.Mockito.mock(
                        jp.aegif.nemaki.rest.ingest.ConnectorDefinitionService.class);
        jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService profileService =
                org.mockito.Mockito.mock(
                        jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService.class);
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                org.mockito.Mockito.mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        jp.aegif.nemaki.cmis.service.ObjectService objectService =
                org.mockito.Mockito.mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setContentService(contentService);
        service.setObjectService(objectService);
        service.setIngestMetadataService(org.mockito.Mockito.mock(IngestMetadataService.class));
        service.setIngestLineageEmitter(new RecordingEmitter());

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        org.mockito.Mockito.when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(jp.aegif.nemaki.rest.ingest.SourceArchetype.CHAT_CONTEXT);
        connector.setSourceSystem("slack");
        org.mockito.Mockito.when(connectorService.get("c1")).thenReturn(connector);
        org.mockito.Mockito.when(objectService.createDocument(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("bedroom"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("folder-1"),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn("chat-1");

        org.mockito.Mockito.when(contentService.getContent("bedroom", "chat-1"))
                .thenAnswer(inv -> store.read());
        org.mockito.Mockito.doAnswer(inv -> {
            store.write(inv.getArgument(2));
            return null;
        }).when(contentService).update(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("bedroom"), org.mockito.ArgumentMatchers.any());

        ExternalIngestResult result = service.executeChatContextImport(
                org.mockito.Mockito.mock(
                        org.apache.chemistry.opencmis.commons.server.CallContext.class),
                chatRequest());
        assertTrue(result.isSuccess(), "control: the chat import must succeed");
    }

    private static ExternalIngestRequest chatRequest() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("1720000000.000200");
        req.setSourceObjectType("message");
        req.setFileName("message.txt");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelId", "C02AMPJAY");
        metadata.put("participants", "otsuka, ishii");
        req.setMetadata(metadata);
        return req;
    }

    @Test
    @DisplayName("an unchanged-content import records no digest it did not establish")
    void unchangedContentRecordsNoDigest() {
        // Reverting the clear left all fourteen other tests green while restoring the claim
        // (external review), so the decision is pinned directly.
        CanonicalImportServiceImpl.ContentComparison same =
                CanonicalImportServiceImpl.compareContent("abc", "abc");
        assertFalse(same.contentChanged());
        assertNull(same.hashToRecord(),
                "the match is against a MUTABLE aspect property, not the bytes — a stale or "
                        + "edited nemaki:contentHash would otherwise certify content this import "
                        + "never stored, and the incoming bytes are discarded either way");

        CanonicalImportServiceImpl.ContentComparison changed =
                CanonicalImportServiceImpl.compareContent("abc", "def");
        assertTrue(changed.contentChanged());
        assertEquals("abc", changed.hashToRecord(),
                "this import DID store and hash these bytes, so discarding the digest would "
                        + "throw away the one thing it can honestly attest");

        CanonicalImportServiceImpl.ContentComparison firstTime =
                CanonicalImportServiceImpl.compareContent("abc", null);
        assertTrue(firstTime.contentChanged());
        assertEquals("abc", firstTime.hashToRecord());

        assertNull(CanonicalImportServiceImpl.compareContent(null, "abc").hashToRecord(),
                "no bytes were supplied, so no digest was established");
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
        metadata.put("participants", "otsuka, ishii, sasaki");
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
        assertEquals("otsuka, ishii, sasaki", snapshot.get("chat.participants"),
                "the record said which channel a message came from but not who was in it — "
                        + "participants was stored on the object and dropped from the evidence");
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
