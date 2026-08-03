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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.rest.purview.journal.LineageMaterializationDecision.V1Entry;
import jp.aegif.nemaki.rest.purview.journal.LineageMaterializationDecision.V2Entry;

/**
 * D-rest-4 (v2.3.21): frozen identities, decision invariants, deterministic reconstruction,
 * and the convergent materializer's crash/forgery matrix.
 */
public class LineageMaterializationTest {

    private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";

    // ---------------------------------------------------------------- golden vectors

    @Nested
    class GoldenVectors {

        private Map<String, String> fixture() throws Exception {
            byte[] bytes = getClass().getResourceAsStream(
                    "/lineage/identity-golden-vectors.json").readAllBytes();
            @SuppressWarnings("unchecked")
            Map<String, String> map = new ObjectMapper().readValue(bytes, Map.class);
            return map;
        }

        @Test
        public void v1EventDigestMatchesTheFrozenVectors() throws Exception {
            Map<String, String> fixture = fixture();
            assertEquals(fixture.get("v1EventDigest_minimal"),
                    LineageSpoolIdentity.v1EventDigest(EVENT_ID,
                            "bedroom:IMPORT_UPLOADED:100:200", "bedroom",
                            LineageProcessType.IMPORT_UPLOADED,
                            List.of("upload://zip-upload"),
                            List.of("nemaki://bedroom/objects/folder-1"), Map.of(),
                            "2026-08-01T00:00:00Z", ""));
            assertEquals(fixture.get("v1EventDigest_full"),
                    LineageSpoolIdentity.v1EventDigest(EVENT_ID,
                            "bedroom:IMPORT_UPLOADED:100:200", "bedroom",
                            LineageProcessType.IMPORT_UPLOADED,
                            List.of("upload://zip-upload", "upload://zip-upload"),
                            List.of("nemaki://bedroom/objects/folder-1"),
                            Map.of("importMode", "zip-upload", "objectCount", "2"),
                            "2026-08-01T00:00:00Z", "corr-1"));
        }

        @Test
        public void planDigestMatchesTheFrozenVectorsForBothEntryShapes() throws Exception {
            Map<String, String> fixture = fixture();
            String spoolId = fixture.get("spoolRecordId");
            String factDigest = fixture.get("spoolPayloadDigest_full");
            V1Entry v1 = new V1Entry(EVENT_ID, fixture.get("v1EventDigest_full"));
            assertEquals(fixture.get("materializationPlanDigest_v1"),
                    LineageSpoolIdentity.materializationPlanDigest(spoolId, factDigest, 1,
                            EVENT_ID, List.of(v1.asRecord())));
            V2Entry v2 = new V2Entry(0, "d".repeat(64), "e".repeat(64));
            assertEquals(fixture.get("materializationPlanDigest_v2"),
                    LineageSpoolIdentity.materializationPlanDigest(spoolId, factDigest, 2,
                            "22222222-3333-4444-5555-666666666666", List.of(v2.asRecord())));
        }
    }

    // ---------------------------------------------------------------- decision invariants

    @Nested
    class DecisionInvariants {

        private LineageMaterializationDecision v1Decision() {
            String digest = LineageSpoolIdentity.v1EventDigest(EVENT_ID, "k", "bedroom",
                    LineageProcessType.IMPORT_UPLOADED, List.of("i"), List.of("o"),
                    Map.of(), "2026-08-01T00:00:00Z", "");
            return LineageMaterializationDecision.of("a".repeat(64), "b".repeat(64), 1, 0L,
                    EVENT_ID, List.of(new V1Entry(EVENT_ID, digest)), 1000L);
        }

        @Test
        public void aTamperedDigestNeverBecomesAValue() {
            LineageMaterializationDecision decision = v1Decision();
            assertThrows(IllegalArgumentException.class, () ->
                    new LineageMaterializationDecision(decision.spoolRecordId(),
                            decision.factPayloadDigest(), 1, 0L, decision.allocatedEventId(),
                            decision.planEntries(), "f".repeat(64), 1000L),
                    "a stored digest that does not recompute is a tampered decision");
        }

        @Test
        public void aTamperedAllocatedEventIdNeverBecomesAValue() {
            LineageMaterializationDecision decision = v1Decision();
            // The V2 plan digest binds allocatedEventId (v2.3.21 B5): changing it without
            // recomputing the digest is refused at construction/decode.
            assertThrows(IllegalArgumentException.class, () ->
                    new LineageMaterializationDecision(decision.spoolRecordId(),
                            decision.factPayloadDigest(), 1, 0L,
                            "99999999-9999-9999-9999-999999999999", decision.planEntries(),
                            decision.materializationPlanDigest(), 1000L));
        }

        @Test
        public void shapeContradictionsAreRefused() {
            String digest = "c".repeat(64);
            assertThrows(IllegalArgumentException.class, () ->
                    LineageMaterializationDecision.of("a".repeat(64), "b".repeat(64), 1, 0L,
                            EVENT_ID, List.of(new V2Entry(0, "d".repeat(64), digest)), 1000L),
                    "a v2 entry under schema 1 contradicts the decision");
            assertThrows(IllegalArgumentException.class, () ->
                    LineageMaterializationDecision.of("a".repeat(64), "b".repeat(64), 1, 0L,
                            "other-id", List.of(new V1Entry(EVENT_ID, digest)), 1000L),
                    "the v1 entry's eventId must BE the allocatedEventId");
        }
    }

    // ---------------------------------------------------------------- determinism

    @Nested
    class Determinism {

        private LineageSpoolPayloadV1 payload(boolean withLegacy) {
            LineageFact.LegacyV1Projection legacy = withLegacy
                    ? new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                            List.of("upload://zip-upload"),
                            List.of("nemaki://bedroom/objects/folder-1"),
                            Map.of("importMode", "zip-upload"), null)
                    : null;
            LineageFact fact = new LineageFact("bedroom",
                    LineageProcessType.IMPORT_UPLOADED, "op-fixed", "2026-08-01T00:00:00Z",
                    List.of(LineageEndpoint.importArtifact("bedroom", "op-fixed",
                            "zip-upload", Map.of())),
                    List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                    List.of("atlas"), "corr-1", legacy);
            return LineageSpoolPayloadV1.of(fact);
        }

        @Test
        public void reconstructionIsAPureFunctionOfPayloadAndAllocation() {
            LineageSpoolPayloadV1 p = payload(true);
            LineageEventV2 a = LineageSpoolMaterializer.v2EventOf(p, EVENT_ID);
            LineageEventV2 b = LineageSpoolMaterializer.v2EventOf(p, EVENT_ID);
            assertEquals(a.deliveryId(), b.deliveryId());
            assertEquals(a.creationPayloadDigest(), b.creationPayloadDigest());
            assertEquals(p.spoolRecordId(), a.spoolRecordId(),
                    "materialized-from binding IS the spool fact");

            LineageEvent v1a = LineageSpoolMaterializer.v1EventOf(p,
                    p.legacyV1Projection(), EVENT_ID);
            LineageEvent v1b = LineageSpoolMaterializer.v1EventOf(p,
                    p.legacyV1Projection(), EVENT_ID);
            assertEquals(v1a, v1b, "no clock, no random — records are equal");
            assertEquals("", v1a.runId());
            assertEquals(0L, v1a.sequenceNumber(), "sequence is assigned at write");
        }

        @Test
        public void theMaterializedV1DocIsTheWriterShape() {
            LineageSpoolPayloadV1 p = payload(true);
            LineageEvent event = LineageSpoolMaterializer.v1EventOf(p,
                    p.legacyV1Projection(), EVENT_ID);
            Map<String, Object> doc = new CouchLineageEvent(event).toMap();
            assertEquals("lineage:" + EVENT_ID, doc.get("_id"));
            assertEquals("lineage_event", doc.get("type"));
            assertEquals(EVENT_ID, doc.get("eventId"));
            assertTrue(doc.containsKey("snapshotAttributes"),
                    "non-empty snapshot attributes are stored");
        }
    }

    // ---------------------------------------------------------------- the materializer

    @Nested
    class MaterializerMatrix {

        @TempDir
        Path spoolDir;

        private LineageJournalStore journal; // + materialization + v2 interfaces
        private LineageMaterializationStore decisions;
        private LineageV2TransitionStore v2reads;
        private WriteVersionResolver resolver;
        private LineageFactSpool spool;
        private LineageSpoolMaterializer materializer;
        private LineageSpoolPayloadV1 payload;
        private Path factFile;

        @BeforeEach
        void setUp() throws Exception {
            journal = mock(LineageJournalStore.class, withSettings().extraInterfaces(
                    LineageMaterializationStore.class, LineageV2TransitionStore.class));
            decisions = (LineageMaterializationStore) journal;
            v2reads = (LineageV2TransitionStore) journal;
            resolver = mock(WriteVersionResolver.class);
            spool = new LineageFactSpool(spoolDir, null);
            materializer = new LineageSpoolMaterializer(decisions, journal, v2reads,
                    resolver, spool, null, () -> EVENT_ID, () -> 1000L);

            LineageFact fact = new LineageFact("bedroom",
                    LineageProcessType.IMPORT_UPLOADED, "op-fixed", "2026-08-01T00:00:00Z",
                    List.of(LineageEndpoint.importArtifact("bedroom", "op-fixed",
                            "zip-upload", Map.of())),
                    List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                    List.of("atlas"), null,
                    new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                            List.of("upload://zip-upload"),
                            List.of("nemaki://bedroom/objects/folder-1"), Map.of(), null));
            payload = LineageSpoolPayloadV1.of(fact);
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));
            try (var walk = Files.walk(spoolDir)) {
                factFile = walk.filter(f -> f.getFileName().toString()
                                .equals("fact-" + payload.spoolRecordId() + ".json"))
                        .findFirst().orElseThrow();
            }
        }

        private LineageMaterializationDecision v2Decision() {
            LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID);
            return LineageMaterializationDecision.of(payload.spoolRecordId(),
                    payload.payloadDigest(), 2, 0L, EVENT_ID,
                    List.of(new V2Entry(0, event.deliveryId(),
                            event.creationPayloadDigest())), 1000L);
        }

        private LineageJournalRowV2 storedRow() {
            LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID);
            return new LineageJournalRowV2(event, "1-x",
                    LineageJournalRowV2.SequencingState.UNSEQUENCED, null, null);
        }

        /** Round-1 fix 2: the budget bounds the WALK; rotation reaches later files. */
        @Test
        public void theScanBudgetBoundsTheWalkAndRotationIsFair() throws Exception {
            when(resolver.resolve(any())).thenReturn(Optional.empty());
            when(decisions.readDecision(anyString())).thenReturn(null);
            // Two more facts beside the fixture's one → 3 fact files total.
            for (String op : List.of("op-b", "op-c")) {
                LineageFact fact = new LineageFact("bedroom",
                        LineageProcessType.IMPORT_UPLOADED, op, "2026-08-01T00:00:00Z",
                        List.of(LineageEndpoint.importArtifact("bedroom", op,
                                "zip-upload", Map.of())),
                        List.of(LineageEndpoint.document("bedroom", "doc-" + op, "a.txt")),
                        List.of("atlas"), null,
                        new LineageFact.LegacyV1Projection(
                                LineageProcessType.IMPORT_UPLOADED, List.of("i"),
                                List.of("o"), Map.of(), null));
                spool.append(LineageSpoolPayloadV1.of(fact));
            }
            LineageSpoolScanner scanner = new LineageSpoolScanner(spool, null);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < 3; i++) {
                LineageSpoolScanner.ScanSummary summary = scanner.scan(spoolDir,
                        new LineageSpoolScanner.SpoolMaterializer() {
                            @Override
                            public void materialize(LineageSpoolPayloadV1 f) {
                                seen.add(f.spoolRecordId());
                            }
                        }, new LineageSpoolScanner.ScanBudget(1, 100, 60_000));
                assertEquals(1, summary.verified(), "the cap bounds each scan to one file");
                assertTrue(summary.budgetExhausted());
            }
            assertEquals(3, seen.size(),
                    "three capped scans visit all three files — the rotation is fair");
        }

        /** Round-1 fix 3: an UNREADABLE canonical ACK is broken, not absent — repaired. */
        @Test
        public void anUnreadableAckIsRoutedThroughRepairNotTreatedAsAbsent()
                throws Exception {
            when(decisions.readDecision(payload.spoolRecordId())).thenReturn(v2Decision());
            when(v2reads.findV2ByRecordId(anyString())).thenReturn(storedRow());
            // GENUINELY oversized for the spool's bound: a small-cap spool instance makes
            // a 4 KiB ACK exceed maxRecordBytes → the bounded read refuses → AckUnreadable.
            LineageFactSpool smallCap = new LineageFactSpool(spoolDir, null, 2048);
            LineageSpoolMaterializer boundedMat = new LineageSpoolMaterializer(decisions,
                    journal, v2reads, resolver, smallCap, null, () -> EVENT_ID, () -> 1000L);
            Path ack = LineageFactSpool.ackPathFor(factFile);
            byte[] huge = new byte[4 * 1024];
            Files.write(ack, huge);

            LineageSpoolMaterializer.MaterializeResult result =
                    boundedMat.materialize(payload, factFile);
            assertEquals(LineageSpoolMaterializer.Outcome.ACKED, result.outcome());
            assertTrue(result.brokenAck(), "unreadable = broken, counted");
            assertTrue(Files.exists(LineageFactSpool.ackQuarantinePathFor(ack)),
                    "the unreadable occupant went to the evidence slot");
            LineageFactSpool.AckRead republished = spool.readAck(factFile);
            assertTrue(republished instanceof LineageFactSpool.AckBytes b
                    && materializer.isValidAck(b.bytes(), payload));

            // The publication-collision read path is bounded too: an unreadable occupant at
            // publish time is CONFLICT, never a crash.
            Files.deleteIfExists(LineageFactSpool.ackQuarantinePathFor(ack));
            Files.deleteIfExists(ack);
            Files.write(ack, huge);
            assertEquals(LineageFactSpool.AckOutcome.CONFLICT,
                    smallCap.publishAck(factFile, "x".getBytes()));
        }

        @Test
        public void resolverUnavailableMeansNothingHappens() {
            when(resolver.resolve(any())).thenReturn(Optional.empty());
            when(decisions.readDecision(payload.spoolRecordId())).thenReturn(null);
            assertEquals(LineageSpoolMaterializer.Outcome.UNRESOLVED,
                    materializer.materialize(payload, factFile).outcome());
            verify(journal, never()).appendV2(any());
            assertTrue(spool.readAck(factFile) instanceof LineageFactSpool.AckAbsent,
                    "no decision, no rows, no ACK");
        }

        @Test
        public void theHappyPathMaterializesVerifiesAndAcks() {
            when(decisions.readDecision(payload.spoolRecordId()))
                    .thenReturn(null, v2Decision());
            when(resolver.resolve(any())).thenReturn(
                    Optional.of(new WriteVersionResolver.ResolvedWrite(2, 0L)));
            when(decisions.createDecisionIfAbsent(any())).thenAnswer(i -> i.getArgument(0));
            when(v2reads.findV2ByRecordId(anyString())).thenReturn(storedRow());

            assertEquals(LineageSpoolMaterializer.Outcome.ACKED,
                    materializer.materialize(payload, factFile).outcome());
            verify(journal).appendV2(any());
            LineageFactSpool.AckRead ack = spool.readAck(factFile);
            assertTrue(ack instanceof LineageFactSpool.AckBytes b
                    && materializer.isValidAck(b.bytes(), payload));

            // Second pass: the valid ACK suppresses after FULL verification.
            assertEquals(LineageSpoolMaterializer.Outcome.ALREADY_ACKED,
                    materializer.materialize(payload, factFile).outcome());
        }

        @Test
        public void everyBrokenAckBindingIsIgnoredAndRepaired() throws Exception {
            when(decisions.readDecision(payload.spoolRecordId())).thenReturn(v2Decision());
            when(v2reads.findV2ByRecordId(anyString())).thenReturn(storedRow());

            LineageMaterializationDecision decision = v2Decision();
            String[] forged = {
                    "{\"spoolRecordId\":\"" + "f".repeat(64)
                            + "\",\"factPayloadDigest\":\"" + payload.payloadDigest()
                            + "\",\"parentDecisionId\":\"" + decision.documentId()
                            + "\",\"materializationPlanDigest\":\""
                            + decision.materializationPlanDigest() + "\"}",
                    "{\"spoolRecordId\":\"" + payload.spoolRecordId()
                            + "\",\"factPayloadDigest\":\"" + "f".repeat(64)
                            + "\",\"parentDecisionId\":\"" + decision.documentId()
                            + "\",\"materializationPlanDigest\":\""
                            + decision.materializationPlanDigest() + "\"}",
                    "{\"spoolRecordId\":\"" + payload.spoolRecordId()
                            + "\",\"factPayloadDigest\":\"" + payload.payloadDigest()
                            + "\",\"parentDecisionId\":\"lineage_materialization:other"
                            + "\",\"materializationPlanDigest\":\""
                            + decision.materializationPlanDigest() + "\"}",
                    "{\"spoolRecordId\":\"" + payload.spoolRecordId()
                            + "\",\"factPayloadDigest\":\"" + payload.payloadDigest()
                            + "\",\"parentDecisionId\":\"" + decision.documentId()
                            + "\",\"materializationPlanDigest\":\"" + "f".repeat(64)
                            + "\"}"};
            for (String forgery : forged) {
                Path ack = LineageFactSpool.ackPathFor(factFile);
                Files.deleteIfExists(ack);
                Files.deleteIfExists(LineageFactSpool.ackQuarantinePathFor(ack));
                Files.write(ack, forgery.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                LineageSpoolMaterializer.MaterializeResult result =
                        materializer.materialize(payload, factFile);
                assertEquals(LineageSpoolMaterializer.Outcome.ACKED, result.outcome(),
                        "a forged ACK never suppresses; repair + re-materialize converge");
                assertTrue(result.brokenAck(), "the summary sees the broken ACK");
                LineageFactSpool.AckRead republished = spool.readAck(factFile);
                assertTrue(republished instanceof LineageFactSpool.AckBytes b
                                && materializer.isValidAck(b.bytes(), payload),
                        "the canonical path converges to the VALID ACK");
            }
        }

        @Test
        public void mapperDriftAgainstTheFrozenPlanWritesNothing() {
            // A decision whose entry digest disagrees with today's reconstruction.
            LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID);
            LineageMaterializationDecision drifted = LineageMaterializationDecision.of(
                    payload.spoolRecordId(), payload.payloadDigest(), 2, 0L, EVENT_ID,
                    List.of(new V2Entry(0, event.deliveryId(), "f".repeat(64))), 1000L);
            when(decisions.readDecision(payload.spoolRecordId())).thenReturn(drifted);

            assertEquals(LineageSpoolMaterializer.Outcome.FAILED,
                    materializer.materialize(payload, factFile).outcome());
            verify(journal, never()).appendV2(any());
        }

        @Test
        public void aDecisionBoundToADifferentFactIsRefused() {
            LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID);
            LineageMaterializationDecision other = LineageMaterializationDecision.of(
                    payload.spoolRecordId(), "f".repeat(64), 2, 0L, EVENT_ID,
                    List.of(new V2Entry(0, event.deliveryId(),
                            event.creationPayloadDigest())), 1000L);
            when(decisions.readDecision(payload.spoolRecordId())).thenReturn(other);
            assertEquals(LineageSpoolMaterializer.Outcome.FAILED,
                    materializer.materialize(payload, factFile).outcome());
            verify(journal, never()).appendV2(any());
        }

        @Test
        public void noAckUntilEveryRowIsRereadVerified() {
            when(decisions.readDecision(payload.spoolRecordId())).thenReturn(v2Decision());
            when(v2reads.findV2ByRecordId(anyString())).thenReturn(null); // not yet durable

            assertEquals(LineageSpoolMaterializer.Outcome.PARTIAL,
                    materializer.materialize(payload, factFile).outcome());
            assertTrue(spool.readAck(factFile) instanceof LineageFactSpool.AckAbsent,
                    "unverified rows never ACK");
        }

        @Test
        public void aStoredRowWithADifferentAuditIdRefusesTheAck() {
            when(decisions.readDecision(payload.spoolRecordId())).thenReturn(v2Decision());
            LineageEventV2 otherAudit = LineageSpoolMaterializer.v2EventOf(payload,
                    "99999999-9999-9999-9999-999999999999");
            // Same digest is impossible for a different eventId? creationPayloadDigest
            // EXCLUDES eventId — so the digest matches while the audit id differs: exactly
            // the drift A7 exists to catch.
            when(v2reads.findV2ByRecordId(anyString())).thenReturn(new LineageJournalRowV2(
                    otherAudit, "1-x", LineageJournalRowV2.SequencingState.UNSEQUENCED,
                    null, null));
            assertEquals(LineageSpoolMaterializer.Outcome.PARTIAL,
                    materializer.materialize(payload, factFile).outcome());
            assertTrue(spool.readAck(factFile) instanceof LineageFactSpool.AckAbsent);
        }
    }
}
