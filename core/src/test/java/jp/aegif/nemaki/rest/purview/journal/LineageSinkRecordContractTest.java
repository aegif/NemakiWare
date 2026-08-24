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
package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;

/**
 * What the catalog sinks do with each kind of {@link LineageAssetRef}.
 *
 * <h2>Why this is a separate class from the three sink tests</h2>
 *
 * <p>Those tests were written against v1 and still pass, unedited except for wrapping their event
 * in {@link LineageRecord#fromV1}. That is the evidence that the contract change preserved v1
 * behaviour, and it is worth keeping them looking exactly like that.
 *
 * <p>This class covers what they cannot: the branches a v1 record never reaches. A sink that
 * handled all three reference kinds identically would still pass every v1 test — it would just
 * drop the v1 snapshot, or carry the event-level map onto v2 assets and reintroduce the defect
 * §2 removed.
 */
public class LineageSinkRecordContractTest {

    private static final String REPO = "bedroom";
    private static final String OCCURRED = "2026-08-01T00:00:00Z";

    private AtlasLineageSink atlas;
    private AtlasConfig atlasConfig;
    private PurviewLineageSink purview;
    private PurviewEntityRegistryClient purviewClient;

    @BeforeEach
    void setUp() throws Exception {
        atlas = new AtlasLineageSink();
        atlasConfig = mock(AtlasConfig.class);
        set(atlas, AtlasLineageSink.class, "atlasConfig", atlasConfig);

        purview = new PurviewLineageSink();
        purviewClient = mock(PurviewEntityRegistryClient.class);
        set(purview, PurviewLineageSink.class, "registryClient", purviewClient);
        set(purview, PurviewLineageSink.class, "connectionResolver",
                mock(MetadataCatalogConnectionResolver.class));
    }

    private static void set(Object target, Class<?> type, String field, Object value)
            throws Exception {
        Field f = type.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ------------------------------------------------------------------ fixtures

    private static LineageRecord v1Record() {
        return LineageRecord.fromV1(new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .addOutput("nemaki://" + REPO + "/archives/doc-1")
                .snapshotAttribute("name", "shared-name.txt")
                .snapshotAttribute("requestedBy", "alice")
                .targets(List.of("atlas"))
                .build());
    }

    /** A chat ingest's snapshot, carrying both the personal facts and the identifying ones. */
    private static LineageRecord chatRecord() {
        return LineageRecord.fromV1(new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .addOutput("nemaki://" + REPO + "/archives/doc-1")
                .snapshotAttribute("chat.channelId", "C123")
                .snapshotAttribute("chat.participants", "otsuka,ishii")
                .snapshotAttribute("chat.channelName", "dm-otsuka-ishii")
                .snapshotAttribute("chat.selectionReason", "quarterly review")
                .snapshotAttribute("contentHash", "a".repeat(64))
                .targets(List.of("atlas"))
                .build());
    }

    /**
     * Personal data must not reach the catalogue payload (P1-1(d)).
     *
     * <p>Until this, the only thing keeping chat participants out of Purview was the destination
     * type not declaring the attribute — a protection living outside this product, one schema
     * change away from not protecting anything. The sink now consults the evidence table.
     */
    @Test
    public void purviewWithholdsPersonalDataFromTheProcessAttributes() throws Exception {
        Map<String, Object> processAttrs = attributes(entities(purviewPayload(chatRecord())).get(0));

        assertFalse(processAttrs.containsKey("chat.participants"),
                "the participants of a captured conversation went to the catalogue: "
                        + processAttrs.keySet());
        assertFalse(processAttrs.containsKey("chat.channelName"), processAttrs.keySet().toString());
        assertFalse(processAttrs.containsKey("chat.selectionReason"),
                processAttrs.keySet().toString());
    }

    /**
     * The counterweight. Withholding everything would satisfy the test above and empty the
     * catalogue of the lineage it exists to show.
     */
    @Test
    public void purviewStillSendsTheIdentifyingFacts() throws Exception {
        Map<String, Object> processAttrs = attributes(entities(purviewPayload(chatRecord())).get(0));

        assertEquals("C123", processAttrs.get("chat.channelId"),
                "the channel id is the conversation's identity and is inside the qualified name "
                        + "anyway; withholding the attribute would hide the key and ship the value");
        assertEquals("a".repeat(64), processAttrs.get("contentHash"));
    }

    /**
     * The sink now passes every attribute map through {@code CatalogSecretBoundary.sealed} —
     * the one publisher disclosure §4 named as not calling the gate. qualifiedName is
     * identity-exempted there (scheme+path allowed, query/fragment/userinfo refused), so the
     * legacy external URIs keep flowing while stored-URL-shaped values in ordinary attributes
     * are refused instead of published.
     */
    @Test
    public void sealedGateRefusesAStoredUrlInAnOrdinaryAttribute() {
        LineageRecord poisoned = LineageRecord.fromV1(new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .addOutput("nemaki://" + REPO + "/archives/doc-1")
                // A sharing link keeps its token in the PATH — no transformation of a stored
                // URL can be shown safe, so the gate refuses rather than logs (§4).
                .snapshotAttribute("callbackUrl", "https://tenant.sharepoint.example/g/EaB12345")
                .targets(List.of("atlas"))
                .build());

        org.junit.jupiter.api.Assertions.assertThrows(
                jp.aegif.nemaki.rest.purview.payload.CatalogSecretBoundary
                        .SecretAtBoundaryException.class,
                () -> purviewPayload(poisoned),
                "a stored URL sailed through the sink the gate was built to close");
    }

    /** The counterweight: a legacy external-asset URI is IDENTITY and must keep publishing. */
    @Test
    public void sealedGateStillPassesLegacyExternalIdentity() throws Exception {
        LineageRecord external = LineageRecord.fromV1(new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInput("acme-chat://org/W1/channels/C1/messages/1720000000.000200")
                .addOutput("nemaki://" + REPO + "/archives/doc-1")
                .targets(List.of("atlas"))
                .build());

        Map<String, Object> payload = purviewPayload(external);
        boolean uriSurvived = entities(payload).stream()
                .map(LineageSinkRecordContractTest::attributes)
                .anyMatch(attrs -> "acme-chat://org/W1/channels/C1/messages/1720000000.000200"
                        .equals(attrs.get("qualifiedName")));
        org.junit.jupiter.api.Assertions.assertTrue(uriSurvived,
                "the canonical source URI is the asset's identity — refusing it removes no "
                        + "secret and breaks every reference through it");
    }

    private static LineageRecord v2Record() {
        return LineageRecord.fromV2(new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt(OCCURRED)
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("atlas")))
                .addInput(LineageEndpoint.document(REPO, "doc-1", "input-name.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "arc-1", "doc-1", 1_700_000_000_000L))
                .build());
    }

    /** An import-type v2 record CARRYING the digest-covered Process supply (P1-1(e)). */
    private static LineageRecord v2ImportRecord() {
        java.util.Map<String, String> processFacts = new java.util.LinkedHashMap<>();
        processFacts.put("targetFolderId", "folder-1");
        processFacts.put("sourceArchetype", "CHAT_CONTEXT");
        processFacts.put("sourceDescription", "slack:1720000000.000200");
        processFacts.put("reimportOutcome", "stored nothing");
        java.util.Map<String, String> journalFacts = new java.util.LinkedHashMap<>();
        journalFacts.put("appliedChatEvidenceHash", "mh1:aa");
        return LineageRecord.fromV2(new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt(OCCURRED)
                .repositoryId(REPO)
                .processType(LineageProcessType.CHAT_MESSAGE_IMPORT)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.externalAsset(REPO,
                        "acme-chat://org/W1/channels/C1", "acme-chat"))
                .addOutput(LineageEndpoint.document(REPO, "doc-1", "message.txt"))
                .digestV2(new LineageExecutionAttribution("admin", "otsuka"),
                        processFacts, journalFacts)
                .build());
    }

    /**
     * The flip's open prerequisite, closed: a v2 record used to fill the REQUIRED Process
     * attributes with "" and "external" because its v1 snapshot is empty ((b) §3.4). The sink
     * now reads the digest-covered processFacts — and ONLY that compartment: journalFacts and
     * the attribution are not even projected onto the record (Codex H1's structural half).
     */
    @Test
    public void purviewFillsProcessAttributesFromV2ProcessFacts() throws Exception {
        Map<String, Object> processAttrs =
                attributes(entities(purviewPayload(v2ImportRecord())).get(0));

        assertEquals("folder-1", processAttrs.get("folderId"),
                "the REQUIRED folderId fell back to \"\" — the v2 supply was not read");
        assertEquals("CHAT_CONTEXT", processAttrs.get("importMode"),
                "importMode fell back to \"external\"");
        assertEquals("slack:1720000000.000200", processAttrs.get("sourceDescription"),
                "the emitter-precomputed sourceDescription must be read, not re-derived");
        assertEquals("stored nothing", processAttrs.get("reimportOutcome"),
                "the per-pass facts are declared in the catalog type since SCHEMA_VERSION 17 (18 now) "
                        + "and must be delivered");
        assertEquals("acme-chat://org/W1/channels/C1", processAttrs.get("externalStableKey"),
                "the raw stable key comes from the typed input endpoint (Codex M4)");
        assertFalse(processAttrs.containsKey("appliedChatEvidenceHash"),
                "a journal-only fact reached the catalog payload");
        assertFalse(processAttrs.toString().contains("otsuka"),
                "the attribution reached the catalog payload");
    }

    private static LineageRecord withUnresolvedInput() {
        LineageRecord base = v1Record();
        return new LineageRecord(base.schemaVersion(), base.idempotencyKeyVersion(),
                base.recordId(), base.eventId(), base.processIdentity(), base.repositoryId(),
                base.processType(), base.occurredAt(), base.sequenceNumber(), base.correlationId(),
                List.of(new LineageAssetRef.Unresolved(
                        "nemaki://" + REPO + "/objects/doc-1", "SOURCE_PURGED")),
                base.outputs(), base.publishStatusByTarget(), base.legacyEventAttributes());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entities(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("entities");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> entity) {
        return (Map<String, Object>) entity.get("attributes");
    }

    // ------------------------------------------------------------------ Atlas: reference typing

    /**
     * A legacy reference has no kind, so {@code DataSet} is the only thing it can be. That is why
     * a v1 event whose input is a folder links to nothing: {@code nemaki_folder} does not extend
     * {@code DataSet}.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void atlasReferencesALegacyAssetAsDataSet() {
        Map<String, Object> processAttrs = attributes(entities(
                atlas.buildAtlasPayload(v1Record())).get(0));
        Map<String, Object> input = ((List<Map<String, Object>>) processAttrs.get("inputs")).get(0);
        assertEquals("DataSet", input.get("typeName"));
    }

    /** A typed reference knows its own Atlas type, which is how that stops being true. */
    @SuppressWarnings("unchecked")
    @Test
    public void atlasReferencesATypedAssetByItsOwnAtlasType() {
        Map<String, Object> processAttrs = attributes(entities(
                atlas.buildAtlasPayload(v2Record())).get(0));
        Map<String, Object> input = ((List<Map<String, Object>>) processAttrs.get("inputs")).get(0);
        Map<String, Object> output =
                ((List<Map<String, Object>>) processAttrs.get("outputs")).get(0);
        assertEquals("nemaki_document", input.get("typeName"));
        assertEquals("nemaki_archive", output.get("typeName"));
    }

    /**
     * The Atlas payload's ONE caller-bearing value is the reference qualifiedName. Atlas sealed
     * its attributes BEFORE adding the references, so the gate covered three constants and
     * skipped the only value worth checking (adversarial review, finding 2). The fixture is a
     * v1 LEGACY reference on purpose: a v2 external asset is already refused at construction
     * ({@code ExternalAssetIdentity.requireNoUriBorneSecrets}), but a stored v1 row predates
     * that gate, so for it the seal is the only check there is. Deleting the {@code sealed()}
     * call in {@code buildAtlasPayload} fails this.
     */
    @Test
    public void atlasSealsTheReferencesItActuallySends() {
        LineageRecord poisoned = LineageRecord.fromV1(new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInput("sharepoint://host/share/doc?token=SECRET")
                .addOutput("nemaki://" + REPO + "/archives/doc-1")
                .targets(List.of("atlas"))
                .build());

        assertThrows(
                jp.aegif.nemaki.rest.purview.payload.CatalogSecretBoundary
                        .SecretAtBoundaryException.class,
                () -> atlas.buildAtlasPayload(poisoned),
                "a query-bearing stable key rode an input reference into the Atlas payload — "
                        + "the seal ran before the references existed");

        // The §4 shape proper: an UPPER-CASE http(s) sharing link with the token in the PATH.
        // The query check above cannot see it, and the first http(s) refusal was a lower-case
        // prefix match (external review) — this pins the case-insensitive form end to end.
        LineageRecord tokenInPath = LineageRecord.fromV1(new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInput("HTTPS://contoso.sharepoint.com/:x:/g/EtokenXY")
                .addOutput("nemaki://" + REPO + "/archives/doc-1")
                .targets(List.of("atlas"))
                .build());
        assertThrows(
                jp.aegif.nemaki.rest.purview.payload.CatalogSecretBoundary
                        .SecretAtBoundaryException.class,
                () -> atlas.buildAtlasPayload(tokenInPath),
                "an upper-case token-in-path sharing link passed the reference seal");
    }

    /** The Process name comes from processIdentity, which differs per version by design (§3). */
    @Test
    public void atlasNamesTheProcessFromTheIdentityOfWhicheverVersionItIs() {
        LineageRecord v1 = v1Record();
        LineageRecord v2 = v2Record();
        String v1Name = (String) attributes(entities(atlas.buildAtlasPayload(v1)).get(0))
                .get("qualifiedName");
        String v2Name = (String) attributes(entities(atlas.buildAtlasPayload(v2)).get(0))
                .get("qualifiedName");

        assertEquals("nemakiware:bedroom:archive_local:" + v1.processIdentity(), v1Name);
        assertEquals("nemakiware:bedroom:archive_local:" + v2.processIdentity(), v2Name);
        assertNotEquals(v1Name, v2Name,
                "the two naming rules produce different Process entities for one operation —"
                        + " §3 accepts this and keeps the v1 Process as an audit fact");
    }

    // ------------------------------------------------------------------ Purview: attributes

    /**
     * v1's event-level map applied to every asset. This is the behaviour §2 replaced, kept exactly
     * as-is for v1 records so that nothing already in a catalog changes shape.
     */
    @Test
    public void purviewSpreadsTheLegacyEventSnapshotOntoEveryLegacyAsset() throws Exception {
        Map<String, Object> payload = purviewPayload(v1Record());
        List<Map<String, Object>> all = entities(payload);

        // entity 0 is the Process; 1..n are the assets
        assertEquals("alice", attributes(all.get(0)).get("requestedBy"));
        for (Map<String, Object> asset : all.subList(1, all.size())) {
            assertEquals("shared-name.txt", attributes(asset).get("name"),
                    "one event-level name on every asset — the v1 behaviour, preserved verbatim");
        }
    }

    /**
     * The same map must not reach a v2 asset. A v2 endpoint carries its own attributes, and
     * applying an event-level map on top is the exact defect endpoint-local snapshots removed.
     */
    @Test
    public void purviewGivesATypedAssetOnlyItsOwnAttributes() throws Exception {
        List<Map<String, Object>> all = entities(purviewPayload(v2Record()));

        Map<String, Object> input = attributes(all.get(1));
        assertEquals("input-name.txt", input.get("name"));
        assertFalse(input.containsKey("requestedBy"),
                "an event-level fact is not an attribute of an asset");

        Map<String, Object> output = attributes(all.get(2));
        assertEquals("doc-1", output.get("originalObjectId"));
        assertEquals(1_700_000_000_000L, output.get("archivedAt"),
                "a COUNT attribute stays a number, as the Atlas type declares it");
        assertNotEquals("input-name.txt", output.get("name"),
                "two assets in one event must not share one name");
    }

    @Test
    public void purviewTypesATypedAssetFromItsKindRatherThanItsName() throws Exception {
        List<Map<String, Object>> all = entities(purviewPayload(v2Record()));
        assertEquals("nemaki_document", all.get(1).get("typeName"));
        assertEquals("nemaki_archive", all.get(2).get("typeName"));
    }

    @Test
    public void purviewCarriesNoLegacyAttributesOnAV2Process() throws Exception {
        Map<String, Object> processAttrs = attributes(entities(purviewPayload(v2Record())).get(0));
        assertFalse(processAttrs.containsKey("requestedBy"));
        assertFalse(processAttrs.containsKey("reason"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> purviewPayload(LineageRecord record) throws Exception {
        Map<String, Object>[] captured = new Map[1];
        when(purviewClient.bulkCreateOrUpdateEntities(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                    captured[0] = invocation.getArgument(1);
                    return failingPublishResult();
                });
        purview.publish(record);
        return captured[0];
    }

    private static jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult
            failingPublishResult() {
        // The payload is what this class asserts on; the response only has to be well-formed.
        jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult result =
                mock(jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult.class);
        when(result.isSuccess()).thenReturn(false);
        when(result.getMessage()).thenReturn("captured");
        return result;
    }

    // ------------------------------------------------------------------ Unresolved

    /**
     * An unresolved asset would become a catalog entity with a name and nothing else — the "shell"
     * §10's verification rejects. The sink must not send it at all.
     *
     * <p>Reported as a failure rather than a skip, deliberately: {@code skipped} carries
     * {@code success=true}, which the projection loop records as {@code PUBLISHED} and advances
     * the cursor past. That would lose the event silently. §2's {@code WAITING_FOR_CATALOG} is the
     * right answer and does not exist yet; until it does, loud beats quiet.
     */
    @Test
    public void purviewSendsNothingWhenAnAssetIsUnresolved() throws Exception {
        LineageTargetSinkResult result = purview.publish(withUnresolvedInput());
        assertFalse(result.success());
        verifyNoInteractions(purviewClient);
        assertTrue(result.message().contains("SOURCE_PURGED"), result.message());
    }

    /**
     * The choice above, stated as a property rather than as a comment: whatever the sinks return
     * for an unresolved asset must not be something the projection loop treats as delivered.
     */
    @Test
    public void anUnresolvedAssetIsNeverReportedAsPublished() throws Exception {
        when(atlasConfig.isEnabled()).thenReturn(true);
        when(atlasConfig.getEndpoint()).thenReturn("https://atlas.example.com");

        assertFalse(atlas.publish(withUnresolvedInput()).success());
        assertFalse(purview.publish(withUnresolvedInput()).success());
        assertTrue(LineageTargetSinkResult.skipped("x").success(),
                "this is why skipped() is the wrong result here — the loop reads success() and"
                        + " marks the event PUBLISHED");
    }

    @Test
    public void atlasSkipsWhenAnAssetIsUnresolved() throws Exception {
        when(atlasConfig.isEnabled()).thenReturn(true);
        when(atlasConfig.getEndpoint()).thenReturn("https://atlas.example.com");

        LineageTargetSinkResult result = atlas.publish(withUnresolvedInput());
        assertFalse(result.success());
        assertTrue(result.message().contains("SOURCE_PURGED"), result.message());
    }

    /** The reason and a digest, never the name: it can be reversible base64 of a stable key. */
    @Test
    public void theSkipReasonNamesNoQualifiedName() {
        LineageEndpoint external =
                LineageEndpoint.externalAsset(REPO, "slack:super-secret-file-id", "slack");
        LineageRecord base = v1Record();
        LineageRecord record = new LineageRecord(base.schemaVersion(),
                base.idempotencyKeyVersion(), base.recordId(), base.eventId(),
                base.processIdentity(), base.repositoryId(), base.processType(),
                base.occurredAt(), base.sequenceNumber(), base.correlationId(),
                List.of(new LineageAssetRef.Unresolved(
                        external.catalogQualifiedName(), "SOURCE_ERROR")),
                base.outputs(), base.publishStatusByTarget(), base.legacyEventAttributes());

        String reason = LineageSinkAssets.firstUnresolvedReason(record);
        assertTrue(reason.contains("SOURCE_ERROR"), reason);
        assertFalse(reason.contains(external.catalogQualifiedName()), reason);
        assertFalse(reason.contains("super-secret-file-id"), reason);
    }

    @Test
    public void aRecordWithNoUnresolvedAssetPassesTheGuard() {
        org.junit.jupiter.api.Assertions.assertNull(
                LineageSinkAssets.firstUnresolvedReason(v1Record()));
        org.junit.jupiter.api.Assertions.assertNull(
                LineageSinkAssets.firstUnresolvedReason(v2Record()));
    }

    // ------------------------------------------------------------------ Dataplex: direction

    /**
     * Dataplex builds the input × output product, so a projection that lost the sides would
     * silently produce the wrong graph rather than failing.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void dataplexLinksEveryInputToEveryOutputInThatDirection() {
        DataplexLineageSink dataplex = new DataplexLineageSink();
        LineageEvent event = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.EXPORT_SELECTED_OBJECTS)
                .addInputObject(REPO, "doc-1")
                .addInputObject(REPO, "doc-2")
                .addOutput("file:///srv/out")
                .targets(List.of("dataplex"))
                .build();

        Map<String, Object> payload = dataplex.buildEventPayload(LineageRecord.fromV1(event));
        List<Map<String, Object>> links = (List<Map<String, Object>>) payload.get("links");
        assertEquals(2, links.size());
        for (Map<String, Object> link : links) {
            Map<String, Object> source = (Map<String, Object>) link.get("source");
            Map<String, Object> target = (Map<String, Object>) link.get("target");
            assertTrue(((String) source.get("fullyQualifiedName")).contains("/objects/"),
                    "inputs are the sources");
            assertTrue(((String) target.get("fullyQualifiedName")).contains("file:///srv/out"),
                    "outputs are the targets");
        }
    }

    @Test
    public void dataplexProducesNoLinkWhenOneSideIsEmpty() {
        DataplexLineageSink dataplex = new DataplexLineageSink();
        LineageEvent noOutput = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .addInputObject(REPO, "folder-1")
                .targets(List.of("dataplex"))
                .build();

        Map<String, Object> payload = dataplex.buildEventPayload(LineageRecord.fromV1(noOutput));
        assertEquals(List.of(), payload.get("links"));
    }

    // ------------------------------------------------------------------ golden v1 payload

    /**
     * The Atlas payload for a v1 record, spelled out.
     *
     * <p>The three sink tests assert on structure; this asserts on the whole thing, so that a
     * change to any part of the projection shows up as a diff rather than as a still-passing
     * structural check.
     */
    @Test
    public void theV1AtlasPayloadIsFrozen() {
        LineageRecord record = v1Record();
        Map<String, Object> expectedProcessAttrs = new LinkedHashMap<>();
        expectedProcessAttrs.put("qualifiedName",
                "nemakiware:bedroom:archive_local:" + record.processIdentity());
        expectedProcessAttrs.put("name", "ARCHIVE_LOCAL");
        expectedProcessAttrs.put("description",
                "NemakiWare lineage event: " + record.processIdentity());
        expectedProcessAttrs.put("inputs", List.of(Map.of("typeName", "DataSet",
                "uniqueAttributes", Map.of("qualifiedName",
                        "nemaki://bedroom/objects/doc-1"))));
        expectedProcessAttrs.put("outputs", List.of(Map.of("typeName", "DataSet",
                "uniqueAttributes", Map.of("qualifiedName",
                        "nemaki://bedroom/archives/doc-1"))));

        assertEquals(Map.of("entities", List.of(Map.of(
                        "typeName", "Process",
                        "attributes", expectedProcessAttrs))),
                atlas.buildAtlasPayload(record));
    }
}
