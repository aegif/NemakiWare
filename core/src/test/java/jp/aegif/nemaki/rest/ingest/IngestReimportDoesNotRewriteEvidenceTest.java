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

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Re-importing the same source object must not rewrite evidence that is already captured.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code skip_if_same_version} — the default dedupe policy — returns from {@code execute} at
 * the dedupe check, which is <b>before</b> the lineage emit. The chat wrapper then deliberately
 * falls through on a skip so that a late {@code derivedFromContext} link or a previously failed
 * field can still land. But it called {@code applyArchetypeMetadata}, whose {@code mergeAspect}
 * puts the request's value over the stored one. So polling the same channel twice was enough to
 * silently rewrite eight chat evidence properties and both capture-window values — the same
 * eleven that P1-1(c) made READONLY through CMIS — with no lineage event anywhere, because the
 * only code that emits one had already returned (external review, P1-1(d) D6).
 *
 * <p>Ingest writes {@code Property} objects straight through {@code ContentService.update}, so it
 * never passes {@code injectPropertyValue} and the READONLY declaration does not touch it. That
 * asymmetry is deliberate — it is what lets a capture write its own evidence — which is exactly
 * why the re-import path has to decide for itself.
 *
 * <h2>What the fix is, and what it is not</h2>
 *
 * <p>Not "do nothing on a skip": the retry the fall-through exists for has to keep working. The
 * rule is <b>fill gaps, keep captured values</b>, and it is bound to "no lineage event will be
 * emitted for this pass" rather than to the word skip, so it stays true if the dedupe branches
 * move.
 */
class IngestReimportDoesNotRewriteEvidenceTest {

    private static final String CHAT_ASPECT = "nemaki:chatContextMetadata";

    private CanonicalImportServiceImpl service;
    private jp.aegif.nemaki.businesslogic.ContentService contentService;
    private Document stored;
    private final List<jp.aegif.nemaki.model.Content> children = new ArrayList<>();

    @BeforeEach
    void wire() {
        service = new CanonicalImportServiceImpl();
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ImportProfileDefinitionService profileService = mock(ImportProfileDefinitionService.class);
        contentService = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);

        // The REAL metadata service, because the fill/overwrite decision is what is under test.
        IngestMetadataService metadataService = new IngestMetadataService();
        metadataService.setContentService(contentService);

        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(mock(jp.aegif.nemaki.cmis.service.ObjectService.class));
        service.setContentService(contentService);
        service.setVersioningService(mock(jp.aegif.nemaki.cmis.service.VersioningService.class));
        service.setIngestMetadataService(metadataService);
        service.setContentDaoService(contentDaoService);

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.CHAT_CONTEXT);
        connector.setSourceSystem("acme");
        when(connectorService.get("c1")).thenReturn(connector);
        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(children);

        // What the first capture left behind: a channel id, a window start, and — as if that one
        // write had failed — no channel name.
        Aspect integration = new Aspect();
        integration.setName("nemaki:externalIntegration");
        integration.setProperties(new ArrayList<>(List.of(
                new Property("nemaki:sourceObjectId", "1720000000.000200"),
                new Property("nemaki:sourceSystem", "acme"),
                new Property("nemaki:sourceObjectType", "message"))));

        // A DOUBLE, not a GregorianCalendar. That is what a stored datetime aspect value really
        // looks like coming back: CouchContent carries aspect values untyped and
        // ContentDaoServiceImpl.normalizeJsonNumber does not recurse into aspects, so epoch
        // millis return as a JSON number. A fixture holding a live Calendar made the first
        // version of the equality check look correct while it could never be true in production
        // (external review). CompileServiceImpl coerces DATETIME aspect values from Long for the
        // same reason.
        Object capturedWindow =
                (double) java.time.Instant.parse("2026-07-14T02:00:00Z").toEpochMilli();
        Aspect chat = new Aspect();
        chat.setName(CHAT_ASPECT);
        chat.setProperties(new ArrayList<>(List.of(
                new Property("nemaki:chatChannelId", "C-CAPTURED"),
                new Property("nemaki:chatParticipants", "otsuka,ishii"),
                new Property("nemaki:chatCaptureWindowStart", capturedWindow))));

        stored = new Document();
        stored.setId("chat-obj");
        stored.setType("cmis:document");
        stored.setName("chat-obj");
        stored.setAspects(new ArrayList<>(List.of(integration, chat)));
        children.add(stored);
        when(contentService.getContent("bedroom", "chat-obj")).thenReturn(stored);
    }

    /** The second poll, carrying different values for everything. */
    private ExternalIngestRequest secondPoll() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("1720000000.000200");
        req.setSourceObjectType("message");
        req.setFileName("message.txt");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelId", "C-REWRITTEN");
        metadata.put("participants", "someone-else");
        metadata.put("channelName", "general");
        metadata.put("captureWindowStart", "2011-01-01T00:00:00Z");
        req.setMetadata(metadata);
        return req;
    }

    private String chatValue(String key) {
        for (Aspect a : stored.getAspects()) {
            if (!CHAT_ASPECT.equals(a.getName()) || a.getProperties() == null) continue;
            for (Property p : a.getProperties()) {
                if (key.equals(p.getKey())) {
                    Object v = p.getValue();
                    if (v instanceof GregorianCalendar gc) {
                        return java.time.Instant.ofEpochMilli(gc.getTimeInMillis()).toString();
                    }
                    if (v instanceof Number n) {
                        return java.time.Instant.ofEpochMilli(n.longValue()).toString();
                    }
                    return String.valueOf(v);
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("a second poll does not replace evidence the first capture stored")
    void capturedValuesSurviveAReimport() {
        ExternalIngestResult result = service.executeChatContextImport(
                testContext(), secondPoll());

        assertTrue(result.skipped(), "the fixture must produce a dedupe skip, or this proves nothing");
        assertEquals("C-CAPTURED", chatValue("nemaki:chatChannelId"),
                "a re-import rewrote a captured chat evidence property, with no lineage event");
        assertEquals("otsuka,ishii", chatValue("nemaki:chatParticipants"),
                "a re-import rewrote the captured participants");
        assertEquals("2026-07-14T02:00:00Z", chatValue("nemaki:chatCaptureWindowStart"),
                "a re-import rewrote the captured capture-window start");
    }

    @Test
    @DisplayName("the fill opens a capture intent BEFORE it writes")
    void theFillOpensTheIntentBeforeWriting() {
        // The first shape decided from a separate preflight read whether to open the intent,
        // and the fill then read again — the two reads could disagree, letting the fill WRITE
        // WITHOUT AN OPEN INTENT (external review, Codex). The hook now opens it between the
        // fill's own decision and its write. Nothing else pinned that: with the hook emptied,
        // every capture suite stayed green (measured), so this test is the pin.
        List<String> order = new ArrayList<>();
        jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore store =
                new jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore() {
                    @Override
                    public boolean openIntent(jp.aegif.nemaki.rest.ingest.capture.CaptureIntent intent) {
                        order.add("open");
                        return true;
                    }

                    @Override
                    public CaptureCompletion completeIntent(
                            jp.aegif.nemaki.rest.ingest.capture.CaptureIntent intent,
                            Map<String, Object> evidence) {
                        order.add("complete");
                        return CaptureCompletion.COMPLETED;
                    }

                    @Override
                    public boolean isActive() {
                        return true;
                    }

                    @Override
                    public Applicability appliesTo(String repositoryId) {
                        return Applicability.APPLIES;
                    }
                };
        service.setCaptureIntentStore(store);
        org.mockito.Mockito.doAnswer(inv -> {
            order.add("write");
            return null;
        }).when(contentService).update(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        service.executeChatContextImport(
                testContext(), secondPoll());

        int open = order.indexOf("open");
        int write = order.indexOf("write");
        assertTrue(write >= 0, "the fixture must produce a fill write, or this pins nothing: " + order);
        assertTrue(open >= 0,
                "the fill wrote evidence with NO capture intent open — the one state the "
                        + "boundary exists to prevent: " + order);
        assertTrue(open < write,
                "the intent was opened after the write, so a crash between them leaves an "
                        + "unrecorded mutation: " + order);
    }

    @Test
    @DisplayName("the completed row records the hash of the POST-fill state")
    void theCompletionRecordsThePostFillHash() {
        // AC1+AC6 of p1-1d-metadata-hash.md. The hash must be of what the pass LEFT BEHIND —
        // computing it before the fill would notarize the pre-fill state as this pass's result.
        Map<String, Object>[] completedEvidence = new Map[1];
        jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore store =
                new jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore() {
                    @Override
                    public boolean openIntent(jp.aegif.nemaki.rest.ingest.capture.CaptureIntent intent) {
                        return true;
                    }

                    @Override
                    public CaptureCompletion completeIntent(
                            jp.aegif.nemaki.rest.ingest.capture.CaptureIntent intent,
                            Map<String, Object> evidence) {
                        completedEvidence[0] = evidence;
                        return CaptureCompletion.COMPLETED;
                    }

                    @Override
                    public boolean isActive() {
                        return true;
                    }

                    @Override
                    public Applicability appliesTo(String repositoryId) {
                        return Applicability.APPLIES;
                    }
                };
        service.setCaptureIntentStore(store);

        service.executeChatContextImport(
                testContext(), secondPoll());

        Map<String, Object> evidence = completedEvidence[0];
        assertNotNull(evidence, "the fill pass must complete a row");
        String recorded = (String) evidence.get("appliedChatEvidenceHash");
        assertNotNull(recorded, "the completed row carries no metadata hash: " + evidence.keySet());
        assertEquals("applied", evidence.get("metadataHashSubject"));
        assertEquals("mh1", evidence.get("metadataHashFormula"));

        // The post-fill state, recomputed from the SAME stored object the wrapper mutated.
        String expected = EvidenceMetadataHash.compute(stored.getAspects()).chatEvidenceHash();
        assertEquals(expected, recorded,
                "the recorded hash is not the post-fill state — either it was computed before "
                        + "the fill, or from the request instead of the object");
        // And it names the filled value: recompute WITHOUT chatChannelName and confirm the
        // recorded hash is not that — i.e. the fill genuinely moved the hash.
        java.util.List<Aspect> withoutFill = new ArrayList<>();
        for (Aspect a : stored.getAspects()) {
            if (!CHAT_ASPECT.equals(a.getName())) { withoutFill.add(a); continue; }
            Aspect copy = new Aspect();
            copy.setName(a.getName());
            java.util.List<Property> props = new ArrayList<>();
            for (Property pr : a.getProperties()) {
                if (!"nemaki:chatChannelName".equals(pr.getKey())) props.add(pr);
            }
            copy.setProperties(props);
            withoutFill.add(copy);
        }
        assertNotEquals(EvidenceMetadataHash.compute(withoutFill).chatEvidenceHash(), recorded,
                "the hash did not change with the fill, so it cannot be of the applied state");
    }

    @Test
    @DisplayName("but a gap the first capture left IS filled — the retry still works")
    void aMissingValueIsStillFilled() {
        service.executeChatContextImport(
                testContext(), secondPoll());

        assertEquals("general", chatValue("nemaki:chatChannelName"),
                "the fix turned into 'never write on a re-import', which breaks the retry that "
                        + "the skip fall-through exists for");
    }

    /** The same poll again, byte for byte — the ordinary case for a five-minute poller. */
    private ExternalIngestRequest identicalPoll() {
        ExternalIngestRequest req = secondPoll();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelId", "C-CAPTURED");
        metadata.put("participants", "otsuka,ishii");
        metadata.put("captureWindowStart", "2026-07-14T02:00:00Z");
        req.setMetadata(metadata);
        return req;
    }

    @Test
    @DisplayName("a pass that refused or filled something is recorded as an event")
    void aPassThatChangedSomethingIsRecorded() {
        IngestLineageEmitter emitter = mock(IngestLineageEmitter.class);
        service.setIngestLineageEmitter(emitter);

        service.executeChatContextImport(
                testContext(), secondPoll());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<CaptureEvidenceField, String>> outcome =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(emitter).emitLineageEvent(
                org.mockito.ArgumentMatchers.eq("bedroom"),
                org.mockito.ArgumentMatchers.eq("chat-obj"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                outcome.capture());

        Map<CaptureEvidenceField, String> recorded = outcome.getValue();
        assertTrue(recorded.containsKey(CaptureEvidenceField.REIMPORT_OUTCOME),
                "the event did not say why it exists: " + recorded);
        assertTrue(recorded.getOrDefault(CaptureEvidenceField.REIMPORT_REFUSED, "")
                        .contains("nemaki:chatChannelId"),
                "the event did not record which value was refused: " + recorded);
        assertTrue(recorded.getOrDefault(CaptureEvidenceField.REIMPORT_FILLED, "")
                        .contains("nemaki:chatChannelName"),
                "the event did not record which gap was filled: " + recorded);
    }

    @Test
    @DisplayName("a pass that changed nothing is NOT recorded — a poller must not flood the ledger")
    void anIdenticalPassIsNotRecorded() {
        // The counterweight to the test above. Emitting per pass rather than per change would put
        // 288 events a day on one unchanged message and bury the ones that matter.
        IngestLineageEmitter emitter = mock(IngestLineageEmitter.class);
        service.setIngestLineageEmitter(emitter);

        service.executeChatContextImport(
                testContext(),
                identicalPoll());

        org.mockito.Mockito.verifyNoInteractions(emitter);
    }

    @Test
    @DisplayName("a re-import whose record could not be written says so")
    void aLostRecordIsReported() {
        IngestLineageEmitter emitter = mock(IngestLineageEmitter.class);
        when(emitter.lastEmissionFailure()).thenReturn("lineage database unreachable");
        service.setIngestLineageEmitter(emitter);

        ExternalIngestResult result = service.executeChatContextImport(
                testContext(), secondPoll());

        String all = String.join(" | ", result.warnings());
        assertTrue(all.contains("NOT written"),
                "evidence changed and the record was lost, silently: " + all);
    }

    @Test
    @DisplayName("the caller is told which values were kept rather than replaced")
    void theCallerIsToldWhatWasKept() {
        ExternalIngestResult result = service.executeChatContextImport(
                testContext(), secondPoll());

        assertNotNull(result.warnings());
        String all = String.join(" | ", result.warnings());
        assertTrue(all.contains("nemaki:chatChannelId"),
                "nothing told the caller its channelId was ignored: " + all);
        assertTrue(all.contains("nemaki:chatCaptureWindowStart"),
                "nothing told the caller its capture window was ignored: " + all);
    }
    private static org.apache.chemistry.opencmis.commons.server.CallContext testContext() {
        org.apache.chemistry.opencmis.commons.server.CallContext ctx = mock(
                org.apache.chemistry.opencmis.commons.server.CallContext.class);
        org.mockito.Mockito.when(ctx.getUsername()).thenReturn("test-user");
        return ctx;
    }

}
