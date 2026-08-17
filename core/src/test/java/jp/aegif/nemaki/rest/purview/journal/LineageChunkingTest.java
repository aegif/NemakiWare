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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * §2's chunking (v2.3.22): the partition's determinism and shape safety, the conservative
 * size ruler, and the V2/V3 decision coexistence.
 */
public class LineageChunkingTest {

    private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";
    private static final LineageChunkPlanner.ChunkLimits GENEROUS =
            new LineageChunkPlanner.ChunkLimits(1000L, 1024L * 1024L);

    /** An export fact: 1..n documents in, one artifact out (the MANY side is inputs). */
    private static LineageSpoolPayloadV1 exportFact(int documents) {
        List<LineageEndpoint> inputs = new ArrayList<>();
        for (int i = 0; i < documents; i++) {
            inputs.add(LineageEndpoint.document("bedroom", String.format("doc-%03d", i),
                    "file-" + i + ".txt"));
        }
        return payloadOf(inputs, List.of(LineageEndpoint.exportArtifact("bedroom", "op-fixed",
                "ZIP", "out.zip", 1L)), LineageProcessType.EXPORT_SELECTED_OBJECTS);
    }

    private static LineageSpoolPayloadV1 payloadOf(List<LineageEndpoint> inputs,
            List<LineageEndpoint> outputs, LineageProcessType processType) {
        LineageFact fact = new LineageFact("bedroom", processType, "op-fixed",
                "2026-08-01T00:00:00Z", inputs, outputs, List.of("atlas"), null,
                new LineageFact.LegacyV1Projection(processType, List.of("i"), List.of("o"),
                        Map.of(), null));
        return LineageSpoolPayloadV1.of(fact);
    }

    // ---------------------------------------------------------------- the partition

    @Nested
    class Partition {

        @Test
        public void aFittingFactIsExactlyOneUnchangedSlice() {
            LineageSpoolPayloadV1 payload = exportFact(3);
            List<LineageChunkPlanner.ChunkSlice> slices =
                    LineageChunkPlanner.partition(payload, GENEROUS, EVENT_ID);
            assertEquals(1, slices.size());
            LineageEventV2 chunked = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID,
                    slices.get(0), 0, 1);
            LineageEventV2 whole = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID);
            assertEquals(whole.deliveryId(), chunked.deliveryId(),
                    "the ordinary fact is untouched by the chunking machinery");
            assertEquals(whole.creationPayloadDigest(), chunked.creationPayloadDigest());
        }

        @Test
        public void theCountLimitSplitsAndReplicatesTheAnchor() {
            LineageSpoolPayloadV1 payload = exportFact(5);
            // 3 endpoints per event = anchor + 2 documents.
            var limits = new LineageChunkPlanner.ChunkLimits(3L, 1024L * 1024L);
            List<LineageChunkPlanner.ChunkSlice> slices =
                    LineageChunkPlanner.partition(payload, limits, EVENT_ID);
            assertEquals(3, slices.size(), "5 documents at 2 per chunk");
            int documents = 0;
            for (LineageChunkPlanner.ChunkSlice slice : slices) {
                assertEquals(1, slice.outputs().size(), "the anchor rides in every chunk");
                assertEquals(EndpointKind.EXPORT_ARTIFACT, slice.outputs().get(0).kind());
                assertTrue(slice.inputs().size() <= 2);
                documents += slice.inputs().size();
                // Every chunk is independently shape-valid — that is why the anchor is copied.
                LineageProcessShape.validate(LineageProcessType.EXPORT_SELECTED_OBJECTS,
                        slice.inputs(), slice.outputs());
            }
            assertEquals(5, documents, "every endpoint lands in exactly one chunk");
        }

        @Test
        public void thePartitionIsCanonicalNotProducerOrder() {
            LineageSpoolPayloadV1 declared = exportFact(5);
            // The same fact with its inputs reversed: same spoolRecordId and payloadDigest,
            // so it MUST partition identically — otherwise identity depends on traversal.
            List<LineageEndpoint> reversed = new ArrayList<>(declared.inputs());
            java.util.Collections.reverse(reversed);
            LineageSpoolPayloadV1 permuted = payloadOf(reversed, declared.outputs(),
                    LineageProcessType.EXPORT_SELECTED_OBJECTS);
            assertEquals(declared.spoolRecordId(), permuted.spoolRecordId());
            assertEquals(declared.payloadDigest(), permuted.payloadDigest());

            var limits = new LineageChunkPlanner.ChunkLimits(3L, 1024L * 1024L);
            List<LineageChunkPlanner.ChunkSlice> a =
                    LineageChunkPlanner.partition(declared, limits, EVENT_ID);
            List<LineageChunkPlanner.ChunkSlice> b =
                    LineageChunkPlanner.partition(permuted, limits, EVENT_ID);
            assertEquals(a, b, "permutations of one fact partition identically");
            for (int i = 0; i < a.size(); i++) {
                assertEquals(
                        LineageSpoolMaterializer.v2EventOf(declared, EVENT_ID, a.get(i), i,
                                a.size()).deliveryId(),
                        LineageSpoolMaterializer.v2EventOf(permuted, EVENT_ID, b.get(i), i,
                                b.size()).deliveryId());
            }
        }

        @Test
        public void chunksHaveDistinctIdentitiesAndShareTheirOperation() {
            LineageSpoolPayloadV1 payload = exportFact(4);
            var limits = new LineageChunkPlanner.ChunkLimits(2L, 1024L * 1024L);
            List<LineageChunkPlanner.ChunkSlice> slices =
                    LineageChunkPlanner.partition(payload, limits, EVENT_ID);
            assertEquals(4, slices.size(), "anchor + 1 document per chunk");
            java.util.Set<String> deliveryIds = new java.util.LinkedHashSet<>();
            for (int i = 0; i < slices.size(); i++) {
                LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID,
                        slices.get(i), i, slices.size());
                assertEquals("op-fixed", event.operationId(), "chunks share the operation");
                assertEquals(EVENT_ID, event.eventId(), "and the audit event");
                assertEquals(i, event.chunkIndex());
                assertEquals(slices.size(), event.chunkCount());
                deliveryIds.add(event.deliveryId());
            }
            assertEquals(slices.size(), deliveryIds.size(),
                    "each chunk is its own journal row");
        }

        @Test
        public void theByteLimitSplitsToo() {
            LineageSpoolPayloadV1 payload = exportFact(6);
            // A budget sized to exactly three documents plus the anchor: the envelope is a
            // large fixed cost, so the limit has to be derived from a real slice.
            long threeDocuments = LineageChunkPlanner.measure(payload,
                    new LineageChunkPlanner.ChunkSlice(payload.inputs().subList(0, 3),
                            payload.outputs()), EVENT_ID);
            var limits = new LineageChunkPlanner.ChunkLimits(1000L, threeDocuments);
            List<LineageChunkPlanner.ChunkSlice> slices =
                    LineageChunkPlanner.partition(payload, limits, EVENT_ID);
            assertTrue(slices.size() > 1, "half the whole fact's budget must split it");
            for (LineageChunkPlanner.ChunkSlice slice : slices) {
                assertTrue(LineageChunkPlanner.measure(payload, slice, EVENT_ID)
                        <= limits.maxPayloadBytes(), "every chunk is inside the budget");
            }
        }

        @Test
        public void anUnsplittableFactIsRefusedWithItsEvidence() {
            LineageSpoolPayloadV1 payload = exportFact(3);
            var tiny = new LineageChunkPlanner.ChunkLimits(2L, 64L);
            LineageChunkPlanner.OversizeException e = assertThrows(
                    LineageChunkPlanner.OversizeException.class,
                    () -> LineageChunkPlanner.partition(payload, tiny, EVENT_ID));
            assertEquals(64, e.offendingEndpointRecordHash().length(),
                    "the COMPLETE offending endpoint record's hash is the audit evidence");
            assertTrue(e.measuredBytes() > tiny.maxPayloadBytes());
        }

        /** F1: an ordinary 1→1 fact must partition cleanly, not throw. */
        @Test
        public void aFittingOneToOneFactIsOneSliceNotAFailure() {
            LineageSpoolPayloadV1 payload = payloadOf(
                    List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                    List.of(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L)),
                    LineageProcessType.ARCHIVE_LOCAL);
            List<LineageChunkPlanner.ChunkSlice> slices =
                    LineageChunkPlanner.partition(payload, GENEROUS, EVENT_ID);
            assertEquals(1, slices.size(), "archive/cloud/ingest facts are the common case");
            assertEquals(payload.inputs(), slices.get(0).inputs());
            assertEquals(payload.outputs(), slices.get(0).outputs());
        }

        /** F6: the coordinates are charged once — the ruler already gives numbers 20 bytes. */
        @Test
        public void theMeasurementDoesNotDoubleChargeTheChunkCoordinates() {
            LineageSpoolPayloadV1 payload = exportFact(2);
            LineageChunkPlanner.ChunkSlice whole =
                    new LineageChunkPlanner.ChunkSlice(payload.inputs(), payload.outputs());
            LineageEventV2 probe = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID,
                    whole, 0, 1);
            Map<String, Object> document =
                    new LinkedHashMap<>(CouchLineageEventV2.toMap(probe));
            document.put("state", "UNSEQUENCED");
            assertEquals(LineageDocumentSizeRuler.upperBound(document),
                    LineageChunkPlanner.measure(payload, whole, EVENT_ID),
                    "no separate allowance on top of the ruler's own number bound");
        }

        /** F6: the classified document (statuses + reasons) is what gets measured. */
        @Test
        public void theClassifiedDocumentIsMeasuredWithItsReasons() {
            LineageSpoolPayloadV1 payload = exportFact(2);
            LineageChunkPlanner.ChunkSlice whole =
                    new LineageChunkPlanner.ChunkSlice(payload.inputs(), payload.outputs());
            long plain = LineageChunkPlanner.measure(payload, whole, EVENT_ID);
            long classified = LineageChunkPlanner.measure(payload, whole, EVENT_ID,
                    Map.of("atlas", new LineageMaterializationDecision.CreationClassification(
                            LineagePublishStatus.UNRESOLVED,
                            new LineageTargetLifecycle.TerminalReason("OVERSIZE",
                                    "d".repeat(100), 1000L))));
            assertTrue(classified > plain, "the terminal reasons are part of the document");
        }

        @Test
        public void aOneToOneShapeHasNothingToSplit() {
            LineageSpoolPayloadV1 payload = payloadOf(
                    List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                    List.of(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L)),
                    LineageProcessType.ARCHIVE_LOCAL);
            var tiny = new LineageChunkPlanner.ChunkLimits(2L, 64L);
            LineageChunkPlanner.OversizeException e = assertThrows(
                    LineageChunkPlanner.OversizeException.class,
                    () -> LineageChunkPlanner.partition(payload, tiny, EVENT_ID),
                    "an OVERSIZE 1→1 fact is terminal — it has no MANY side to split");
            assertEquals(64, e.offendingEndpointRecordHash().length());
        }
    }

    // ---------------------------------------------------------------- the size ruler

    @Nested
    class SizeRuler {

        private final ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();

        private void assertUpperBound(Map<String, Object> document) throws Exception {
            long bound = LineageDocumentSizeRuler.upperBound(document);
            int actual = mapper.writeValueAsBytes(document).length;
            assertTrue(bound >= actual, "the ruler must never under-measure: bound=" + bound
                    + " actual=" + actual);
        }

        @Test
        public void theBoundHoldsForEveryAdmittedShape() throws Exception {
            assertUpperBound(Map.of());
            assertUpperBound(new LinkedHashMap<>(Map.of("a", "b")));
            assertUpperBound(new LinkedHashMap<>(Map.of("n", 42L, "b", true)));
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("list", List.of("x", 1L, false));
            nested.put("map", Map.of("k", "v"));
            nested.put("nullable", null);
            assertUpperBound(nested);
        }

        @Test
        public void theBoundHoldsForMultiByteAndEscapeHeavyText() throws Exception {
            assertUpperBound(new LinkedHashMap<>(Map.of("k", "契約書.txt")));
            assertUpperBound(new LinkedHashMap<>(Map.of("k", "\"\\\n\t")));
            assertUpperBound(new LinkedHashMap<>(Map.of("k", "😀 astral")));
            assertUpperBound(new LinkedHashMap<>(Map.of("契約", "書")));
        }

        @Test
        public void theBoundHoldsForARealMaterializedDocument() throws Exception {
            LineageSpoolPayloadV1 payload = exportFact(3);
            LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID);
            Map<String, Object> document =
                    new LinkedHashMap<>(CouchLineageEventV2.toMap(event));
            document.put("state", "UNSEQUENCED");
            assertUpperBound(document);
        }

        @Test
        public void anUnknownValueTypeIsRefusedRatherThanGuessed() {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("k", new java.util.Date());
            assertThrows(IllegalArgumentException.class,
                    () -> LineageDocumentSizeRuler.upperBound(document));
        }
    }

    // ---------------------------------------------------------------- V2/V3 decisions

    @Nested
    class DecisionVersions {

        private final LineageMaterializationDecision.V2Entry entryA =
                new LineageMaterializationDecision.V2Entry(0, "d".repeat(64), "e".repeat(64));
        private final LineageMaterializationDecision.V2Entry entryB =
                new LineageMaterializationDecision.V2Entry(1, "f".repeat(64), "0".repeat(64));

        @Test
        public void aStoredMultiEntryV2DecisionStillDecodes() {
            // v2.3.22 B5: V3 must not narrow what V2 accepted.
            LineageMaterializationDecision v2 = LineageMaterializationDecision.of(
                    "a".repeat(64), "b".repeat(64), 2, 0L, EVENT_ID,
                    List.of(entryA, entryB), 1000L);
            assertEquals(2, v2.planDigestVersion());
            assertEquals(2, v2.planEntries().size());
            assertEquals(null, v2.chunkLimits());
        }

        @Test
        public void theV3DigestBindsTheLimitsAndThePartitionVersion() {
            LineageMaterializationDecision a = LineageMaterializationDecision.ofV3(
                    "a".repeat(64), "b".repeat(64), 0L, EVENT_ID, List.of(entryA), 1000L,
                    1L, GENEROUS, Map.of());
            LineageMaterializationDecision b = LineageMaterializationDecision.ofV3(
                    "a".repeat(64), "b".repeat(64), 0L, EVENT_ID, List.of(entryA), 1000L,
                    1L, new LineageChunkPlanner.ChunkLimits(500L, 1024L * 1024L), Map.of());
            assertNotEquals(a.materializationPlanDigest(), b.materializationPlanDigest(),
                    "a different limit is a different decision");
            // A different algorithm version yields a different digest — computed directly,
            // since the decision refuses to CARRY a version this binary cannot run (F4).
            assertNotEquals(a.materializationPlanDigest(),
                    LineageSpoolIdentity.materializationPlanDigestV3("a".repeat(64),
                            "b".repeat(64), 2, EVENT_ID, 2L, GENEROUS.asRecord(),
                            Map.of(), List.of(entryA.asRecord())),
                    "a different partition algorithm is a different decision");
        }

        @Test
        public void theV3DigestBindsTheCreationClassificationStatusAndReason() {
            var reason = new LineageTargetLifecycle.TerminalReason("OVERSIZE", "d", 1000L);
            LineageMaterializationDecision unresolved = LineageMaterializationDecision.ofV3(
                    "a".repeat(64), "b".repeat(64), 0L, EVENT_ID, List.of(entryA), 1000L,
                    1L, GENEROUS, Map.of("atlas",
                            new LineageMaterializationDecision.CreationClassification(
                                    LineagePublishStatus.UNRESOLVED, reason)));
            LineageMaterializationDecision rejected = LineageMaterializationDecision.ofV3(
                    "a".repeat(64), "b".repeat(64), 0L, EVENT_ID, List.of(entryA), 1000L,
                    1L, GENEROUS, Map.of("atlas",
                            new LineageMaterializationDecision.CreationClassification(
                                    LineagePublishStatus.REJECTED, reason)));
            assertNotEquals(unresolved.materializationPlanDigest(),
                    rejected.materializationPlanDigest(),
                    "creationPayloadDigest excludes statuses, so the plan digest must bind them");
            LineageMaterializationDecision plain = LineageMaterializationDecision.ofV3(
                    "a".repeat(64), "b".repeat(64), 0L, EVENT_ID, List.of(entryA), 1000L,
                    1L, GENEROUS, Map.of());
            assertNotEquals(unresolved.materializationPlanDigest(),
                    plain.materializationPlanDigest());
        }

        @Test
        public void chunkIndexesMustBeTheListOrder() {
            assertThrows(IllegalArgumentException.class, () ->
                    LineageMaterializationDecision.ofV3("a".repeat(64), "b".repeat(64), 0L,
                            EVENT_ID, List.of(entryB, entryA), 1000L, 1L, GENEROUS, Map.of()),
                    "the entries ARE the chunks, in order");
        }

        @Test
        public void versionSpecificFieldsAreRefusedOnTheWrongVersion() {
            assertThrows(IllegalArgumentException.class, () ->
                    new LineageMaterializationDecision("a".repeat(64), "b".repeat(64), 2, 0L,
                            EVENT_ID, List.of(entryA), "x".repeat(64), 1000L, 2, 1L, GENEROUS,
                            Map.of()),
                    "a V2 decision carries no chunk fields");
            assertThrows(IllegalArgumentException.class, () ->
                    new LineageMaterializationDecision("a".repeat(64), "b".repeat(64), 2, 0L,
                            EVENT_ID, List.of(entryA), "x".repeat(64), 1000L, 3, null, null,
                            Map.of()),
                    "a V3 decision requires them");
            assertThrows(IllegalArgumentException.class, () ->
                    new LineageMaterializationDecision("a".repeat(64), "b".repeat(64), 2, 0L,
                            EVENT_ID, List.of(entryA), "x".repeat(64), 1000L, 4, null, null,
                            Map.of()),
                    "an unknown version is refused");
        }

        /** F4: an unimplemented partition algorithm is refused, not silently run as v1. */
        @Test
        public void anUnknownPartitionVersionIsRefused() {
            assertThrows(IllegalArgumentException.class, () ->
                    LineageMaterializationDecision.ofV3("a".repeat(64), "b".repeat(64), 0L,
                            EVENT_ID, List.of(entryA), 1000L, 99L, GENEROUS, Map.of()),
                    "reconstruction only knows algorithm 1");
        }

        @Test
        public void aClassificationIsAlwaysTerminalAndAlwaysCarriesItsReason() {
            var reason = new LineageTargetLifecycle.TerminalReason("OVERSIZE", "d", 1L);
            assertThrows(IllegalArgumentException.class, () ->
                    new LineageMaterializationDecision.CreationClassification(
                            LineagePublishStatus.PENDING, reason));
            assertThrows(IllegalArgumentException.class, () ->
                    new LineageMaterializationDecision.CreationClassification(
                            LineagePublishStatus.UNRESOLVED, null));
        }
    }

    // ---------------------------------------------------------------- the parking marker

    /** F3: only CouchDB's size verdict parks; a 503 is infrastructure and propagates. */
    @Nested
    class UnstorableClassification {

        private com.ibm.cloud.sdk.core.service.exception.ServiceResponseException response(
                int status, String reason) {
            var e = org.mockito.Mockito.mock(
                    com.ibm.cloud.sdk.core.service.exception.ServiceResponseException.class);
            org.mockito.Mockito.when(e.getStatusCode()).thenReturn(status);
            org.mockito.Mockito.when(e.getDebuggingInfo()).thenReturn(
                    reason == null ? Map.of() : Map.of("reason", reason));
            return e;
        }

        @Test
        public void only413OrTheCouchReasonCounts() {
            assertTrue(LineageStoreDecoding.isDocumentTooLarge(response(413, null)));
            assertTrue(LineageStoreDecoding.isDocumentTooLarge(
                    response(400, "document_too_large")));
            assertTrue(LineageStoreDecoding.isDocumentTooLarge(
                    response(500, "document_too_large")));
        }

        @Test
        public void infrastructureFailuresNeverPark() {
            assertEquals(false, LineageStoreDecoding.isDocumentTooLarge(
                    response(503, null)), "an outage must propagate, never park a fact");
            assertEquals(false, LineageStoreDecoding.isDocumentTooLarge(
                    response(503, "document_too_large")),
                    "even a 503 whose body says so is an outage, not a verdict");
            assertEquals(false, LineageStoreDecoding.isDocumentTooLarge(
                    new RuntimeException("document_too_large")),
                    "a bare message is not a CouchDB response");
            assertEquals(false, LineageStoreDecoding.isDocumentTooLarge(
                    response(400, "too large")), "'too large' prose is not the reason code");
        }
    }

    @Test
    public void theParkingMarkerIsDeterministicAndFullyBound() {
        LineageSpoolPayloadV1 payload = exportFact(2);
        byte[] a = LineageSpoolMaterializer.oversizeMarkerBytes(payload, 10L, 5L, "h".repeat(64));
        byte[] b = LineageSpoolMaterializer.oversizeMarkerBytes(payload, 10L, 5L, "h".repeat(64));
        assertEquals(new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8),
                "deterministic bytes are what makes create-if-absent idempotent");
        assertTrue(new String(a, StandardCharsets.UTF_8).contains("OVERSIZE_UNSTORABLE"));
        assertTrue(new String(a, StandardCharsets.UTF_8).contains(payload.payloadDigest()));
        assertTrue(new String(a, StandardCharsets.UTF_8).contains("\"measuredBytes\":10"));
    }

    /** F7/R2: a marker whose evidence is missing OR altered never suppresses work. */
    @Test
    public void aParkingMarkerWithAlteredEvidenceIsRefused() {
        LineageSpoolPayloadV1 payload = exportFact(2);
        long ceiling = 4L * 1024 * 1024;
        LineageSpoolMaterializer materializer = new LineageSpoolMaterializer(
                org.mockito.Mockito.mock(LineageMaterializationStore.class),
                org.mockito.Mockito.mock(LineageJournalStore.class),
                org.mockito.Mockito.mock(LineageV2TransitionStore.class),
                org.mockito.Mockito.mock(WriteVersionResolver.class),
                org.mockito.Mockito.mock(LineageFactSpool.class), null, () -> EVENT_ID,
                () -> 1000L);
        String evidence = LineageSpoolMaterializer.evidenceHashOf(payload);
        byte[] complete = LineageSpoolMaterializer.oversizeMarkerBytes(payload, 12345L,
                ceiling, evidence);
        assertTrue(materializer.isValidOversizeMarker(complete, payload));

        String threeFields = "{\"spoolRecordId\":\"" + payload.spoolRecordId()
                + "\",\"factPayloadDigest\":\"" + payload.payloadDigest()
                + "\",\"reason\":\"OVERSIZE_UNSTORABLE\"}";
        assertEquals(false, materializer.isValidOversizeMarker(
                threeFields.getBytes(StandardCharsets.UTF_8), payload),
                "the evidence fields are part of the marker's shape");

        // Same shape, altered evidence: a plausible 64-char hash is not THIS fact's hash.
        assertEquals(false, materializer.isValidOversizeMarker(
                LineageSpoolMaterializer.oversizeMarkerBytes(payload, 12345L, ceiling,
                        "a".repeat(64)), payload),
                "the endpoint-record hash is recomputed, not trusted");
        assertEquals(false, materializer.isValidOversizeMarker(
                LineageSpoolMaterializer.oversizeMarkerBytes(payload, 12345L, 999L, evidence),
                payload), "the ceiling is bound to the configured one");
        assertEquals(false, materializer.isValidOversizeMarker(
                LineageSpoolMaterializer.oversizeMarkerBytes(payload, 0L, ceiling, evidence),
                payload), "a zero measurement is not a measurement");
    }

    /** R1: the size verdict reaches the caller from the ORDINARY v2 write path too. */
    @Test
    public void theOrdinaryV2WritePathAlsoSurfacesTheSizeVerdict() throws Exception {
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        var wrapper = org.mockito.Mockito.mock(
                jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        org.mockito.Mockito.when(wrapper.getDatabaseName()).thenReturn("nemaki_lineage");
        var raw = org.mockito.Mockito.mock(com.ibm.cloud.cloudant.v1.Cloudant.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(wrapper.getClient()).thenReturn(raw);
        java.lang.reflect.Field client =
                CouchLineageJournalStore.class.getDeclaredField("lineageClient");
        client.setAccessible(true);
        client.set(store, wrapper);
        java.lang.reflect.Field provisioned =
                CouchLineageJournalStore.class.getDeclaredField("dbProvisioned");
        provisioned.setAccessible(true);
        provisioned.set(store, new java.util.concurrent.atomic.AtomicBoolean(true));

        var tooLarge = org.mockito.Mockito.mock(
                com.ibm.cloud.sdk.core.service.exception.ServiceResponseException.class);
        org.mockito.Mockito.when(tooLarge.getStatusCode()).thenReturn(413);
        org.mockito.Mockito.when(raw.putDocument(org.mockito.ArgumentMatchers.any(
                com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.class)).execute())
                .thenThrow(tooLarge);
        org.mockito.Mockito.when(raw.getDocument(org.mockito.ArgumentMatchers.any(
                com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.class)).execute())
                .thenThrow(org.mockito.Mockito.mock(
                        com.ibm.cloud.sdk.core.service.exception.NotFoundException.class));

        LineageSpoolPayloadV1 payload = exportFact(2);
        LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(payload, EVENT_ID);
        assertThrows(LineageMaterializationStore.DocumentTooLargeException.class,
                () -> store.appendV2(event),
                "an ordinary chunk write must surface the verdict, not retry forever");
    }
}
