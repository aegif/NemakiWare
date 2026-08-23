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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P1-1(e) digest extension: two fact compartments and a typed attribution, covered by a
 * VERSIONED composition so stored rows outlive the formula move (Codex C1/C2/H1).
 */
class LineageEventV2ExtensionTest {

    private static LineageEventV2Builder base() {
        return new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt("2026-08-24T00:00:00Z")
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .chunk(0, 1)
                .sequenceNumber(0L)
                .addInput(LineageEndpoint.importArtifact("bedroom", "op-1", "zip-upload",
                        Map.of()))
                .addOutput(LineageEndpoint.document("bedroom", "out-1", "n"));
    }

    private static Map<String, String> processFacts() {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("targetFolderId", "folder-1");
        facts.put("sourceArchetype", "CHAT_CONTEXT");
        facts.put("sourceDescription", "slack:1720000000.000200");
        return facts;
    }

    private static Map<String, String> journalFacts() {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("chat.capturedAt", "2026-08-24T00:00:00Z");
        facts.put("appliedChatEvidenceHash", "mh1:aa");
        return facts;
    }

    private static LineageExecutionAttribution attribution() {
        return new LineageExecutionAttribution("admin", "otsuka");
    }

    @Test
    @DisplayName("a partial rewrite of a fact map is rejected at decode — the coverage claim")
    void tamperedFactMapIsRejected() {
        LineageEventV2 event = base()
                .digestV2(attribution(), processFacts(), journalFacts()).build();
        Map<String, Object> doc = CouchLineageEventV2.toMap(event);

        @SuppressWarnings("unchecked")
        Map<String, Object> journal = (Map<String, Object>) doc.get("journalFacts");
        journal.put("appliedChatEvidenceHash", "mh1:FORGED");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CouchLineageEventV2.fromMap(doc),
                "the fact map was rewritten under an unchanged digest and the row still "
                        + "decoded — the compartment is a home outside the digest");
        assertTrue(thrown.getMessage().contains("creationPayloadDigest"), thrown.getMessage());
    }

    @Test
    @DisplayName("a rewritten attribution is rejected the same way")
    void tamperedAttributionIsRejected() {
        LineageEventV2 event = base()
                .digestV2(attribution(), processFacts(), Map.of()).build();
        Map<String, Object> doc = CouchLineageEventV2.toMap(event);

        @SuppressWarnings("unchecked")
        Map<String, Object> attribution = (Map<String, Object>) doc.get("attribution");
        attribution.put("executedBy", "someone-else");

        assertThrows(IllegalArgumentException.class, () -> CouchLineageEventV2.fromMap(doc));
    }

    @Test
    @DisplayName("a stored row WITHOUT creationDigestVersion decodes as version 1 — for ever")
    void preExtensionRowsStayReadable() {
        LineageEventV2 v1Era = base().build();
        Map<String, Object> doc = CouchLineageEventV2.toMap(v1Era);
        doc.remove("creationDigestVersion");   // the exact bytes a pre-(e) binary wrote

        LineageEventV2 decoded = CouchLineageEventV2.fromMap(doc);

        assertEquals(LineageEventV2.DIGEST_VERSION_V1, decoded.creationDigestVersion(),
                "a row from before the extension was not recognized as version 1 — every "
                        + "stored digest would mismatch (Codex C1)");
        assertEquals(v1Era.creationPayloadDigest(), decoded.creationPayloadDigest());
    }

    @Test
    @DisplayName("version 1 refuses to carry the extras — no home outside the digest")
    void versionOneRefusesExtras() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageEventV2(2, LineageIdentity.IDEMPOTENCY_KEY_VERSION,
                        "e", "pk", new LineageDelivery.Original(List.of("purview")), "d",
                        "bedroom", LineageProcessType.IMPORT_UPLOADED, "op-1",
                        "2026-08-24T00:00:00Z", List.of(), List.of(), 0, 1, 0L, null, null,
                        null, Map.of(), "0".repeat(64), LineageEventV2.DIGEST_VERSION_V1,
                        attribution(), Map.of(), Map.of()),
                "attribution rode a version-1 digest — carried but not covered (p1-1b §2)");
    }

    @Test
    @DisplayName("unknown and misplaced compartment keys are construction errors")
    void closedKeySetsAreEnforced() {
        Map<String, String> misplaced = new LinkedHashMap<>();
        misplaced.put("chat.capturedAt", "2026-08-24T00:00:00Z");   // a JOURNAL key
        assertThrows(IllegalArgumentException.class,
                () -> base().digestV2(attribution(), misplaced, Map.of()).build(),
                "an INTERNAL_ONLY journal fact was constructible inside the compartment the "
                        + "catalog sink reads (Codex H1)");

        Map<String, String> unknown = new LinkedHashMap<>();
        unknown.put("somethingNew", "x");
        assertThrows(IllegalArgumentException.class,
                () -> base().digestV2(attribution(), Map.of(), unknown).build());
    }

    @Test
    @DisplayName("the spool payload round-trips the extras and self-verifies")
    void spoolRoundTripCarriesTheExtras() {
        LineageFact fact = new LineageFact("bedroom", LineageProcessType.IMPORT_UPLOADED,
                "op-1", "2026-08-24T00:00:00Z",
                List.of(LineageEndpoint.importArtifact("bedroom", "op-1", "zip-upload",
                        Map.of())),
                List.of(LineageEndpoint.document("bedroom", "out-1", "n")),
                List.of("purview"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                        List.of("upload://x"), List.of("nemaki://bedroom/objects/out-1"),
                        Map.of()),
                attribution(), processFacts(), journalFacts());

        LineageSpoolPayloadV1 payload = LineageSpoolPayloadV1.of(fact);
        assertEquals(LineageSpoolPayloadV1.SCHEMA_VERSION_V2, payload.spoolSchemaVersion(),
                "a fact carrying the extras must spool as version 2 — a version-1 payload's "
                        + "digest does not cover them (Codex C2)");
        assertTrue(payload.selfVerifies(), "the v2 digest must verify its own content");

        LineageSpoolPayloadV1 decoded =
                LineageSpoolCodec.decode(LineageSpoolCodec.encode(payload));
        assertEquals(payload, decoded, "the codec lost part of the version-2 payload");
        assertTrue(decoded.selfVerifies());

        LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(decoded, "e-1");
        assertEquals(LineageEventV2.DIGEST_VERSION_V2, event.creationDigestVersion(),
                "the event's digest version must follow the payload's (§2.0)");
        assertEquals("folder-1", event.processFacts().get("targetFolderId"));
        assertEquals("admin", event.attribution().executedBy());
    }

    @Test
    @DisplayName("the classified copy keeps the extras — M1's first half")
    void classifiedCopyKeepsTheExtras() {
        // The copy used the 20-arg pre-(e) constructor: a version-2 event lost its attribution
        // and compartments and then failed its own digest recomputation — classified V2
        // materialization was broken outright (post-implementation Codex review, M1).
        LineageEventV2 event = base()
                .digestV2(attribution(), processFacts(), journalFacts()).build();

        LineageEventV2 copied = LineageSpoolMaterializer.withClassifiedStatuses(event,
                Map.of("purview", new LineageMaterializationDecision.CreationClassification(
                        LineagePublishStatus.UNRESOLVED,
                        new LineageTargetLifecycle.TerminalReason("OVERSIZE", "test", 1L))));

        assertEquals(LineageEventV2.DIGEST_VERSION_V2, copied.creationDigestVersion());
        assertEquals(event.processFacts(), copied.processFacts(),
                "the classified copy dropped the Process supply");
        assertEquals(event.attribution(), copied.attribution());
        assertEquals(event.journalFacts(), copied.journalFacts());
    }

    @Test
    @DisplayName("a replay compensation repeats the ORIGINAL's evidence — M1's second half")
    void replayCompensationKeepsTheExtras() {
        // Rebuilding through the version-1 default stripped the Process supply from exactly
        // the path that exists to re-deliver it (Codex M1).
        LineageEventV2 original = base().sequenceNumber(5L)
                .digestV2(attribution(), processFacts(), journalFacts()).build();
        LineageJournalRowV2 row = new LineageJournalRowV2(original, "1-abc",
                LineageJournalRowV2.SequencingState.SEQUENCED, 1L, "lease-1");

        LineageEventV2 compensation = LineageReplayService.compensationOf(row, "purview", 1L);

        assertEquals(LineageEventV2.DIGEST_VERSION_V2, compensation.creationDigestVersion(),
                "the compensation fell back to a version-1 digest — its facts are uncovered");
        assertEquals(original.processFacts(), compensation.processFacts(),
                "the replay lost the Process supply the catalog needs");
        assertEquals(original.attribution(), compensation.attribution());
    }

    @Test
    @DisplayName("extras without an attribution are a construction error — N2")
    void extrasRequireAttribution() {
        // A fact carrying compartments but no attribution used to reach the spool encoder and
        // NPE inside digest computation — an unspoolable fact AFTER the content write, i.e. a
        // silent evidence drop. The invariant moves the failure to construction (Codex N2).
        assertThrows(IllegalArgumentException.class,
                () -> new LineageFact("bedroom", LineageProcessType.IMPORT_UPLOADED,
                        "op-1", "2026-08-24T00:00:00Z",
                        List.of(LineageEndpoint.importArtifact("bedroom", "op-1",
                                "zip-upload", Map.of())),
                        List.of(LineageEndpoint.document("bedroom", "out-1", "n")),
                        List.of("purview"), null,
                        new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                                List.of("upload://x"),
                                List.of("nemaki://bedroom/objects/out-1"), Map.of()),
                        null, processFacts(), journalFacts()),
                "a fact with compartments but no attribution was constructible — it fails "
                        + "later, inside the spool write, after the object exists");

        LineageSpoolPayloadV1 v2 = LineageSpoolPayloadV1.of(new LineageFact("bedroom",
                LineageProcessType.IMPORT_UPLOADED, "op-1", "2026-08-24T00:00:00Z",
                List.of(LineageEndpoint.importArtifact("bedroom", "op-1", "zip-upload",
                        Map.of())),
                List.of(LineageEndpoint.document("bedroom", "out-1", "n")),
                List.of("purview"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                        List.of("upload://x"), List.of("nemaki://bedroom/objects/out-1"),
                        Map.of()),
                attribution(), processFacts(), journalFacts()));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageSpoolPayloadV1(v2.spoolSchemaVersion(), v2.spoolRecordId(),
                        v2.repositoryId(), v2.processType(), v2.operationId(), v2.occurredAt(),
                        v2.inputs(), v2.outputs(), v2.canonicalTargetSet(), v2.chunkIndex(),
                        v2.chunkCount(), v2.correlationId(), v2.legacyV1Projection(),
                        v2.payloadDigest(), null, v2.processFacts(), v2.journalFacts()),
                "a version-2 spool row without an attribution decoded/constructed cleanly — "
                        + "the digest recompute NPEs at read time instead");
    }

    @Test
    @DisplayName("a bare fact spools byte-identically to the pre-(e) format — the control")
    void bareFactsStayVersionOne() {
        LineageFact bare = new LineageFact("bedroom", LineageProcessType.IMPORT_UPLOADED,
                "op-1", "2026-08-24T00:00:00Z",
                List.of(LineageEndpoint.importArtifact("bedroom", "op-1", "zip-upload",
                        Map.of())),
                List.of(LineageEndpoint.document("bedroom", "out-1", "n")),
                List.of("purview"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                        List.of("upload://x"), List.of("nemaki://bedroom/objects/out-1"),
                        Map.of()));

        LineageSpoolPayloadV1 payload = LineageSpoolPayloadV1.of(bare);
        assertEquals(LineageSpoolPayloadV1.SCHEMA_VERSION, payload.spoolSchemaVersion(),
                "a fact with no extras must keep the old format — old readers and old digests "
                        + "stay untouched");
        assertTrue(payload.selfVerifies());
        LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(
                LineageSpoolCodec.decode(LineageSpoolCodec.encode(payload)), "e-1");
        assertEquals(LineageEventV2.DIGEST_VERSION_V1, event.creationDigestVersion());
    }
}
