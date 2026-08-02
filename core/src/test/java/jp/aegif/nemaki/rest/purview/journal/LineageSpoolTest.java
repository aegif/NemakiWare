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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D-spool: the version-independent fact spool — identity properties, the strict codec, the
 * durable create-if-absent file store, and the scanner's verify-before-anything rule.
 *
 * <p>PIT status: 217/221 killed. Every durability call <em>site</em> is pinned with exact
 * per-flow counts through the {@code fsyncDirectory}/{@code forceFile}/{@code
 * createLinkAtomically} seams; both size bounds are pinned at their exact boundary;
 * fail-closed dropped-metric accounting is pinned on every reachable branch (probe-fail,
 * oversize, refuse, retry-exhaustion, mid-flow IO failure); the probe cache is pinned in
 * both directions including the inner double-checked-locking return (latched concurrent
 * probe). The 4 accepted survivors: the bounded-retry count's off-by-one (the contract is
 * "positive and bounded", not "exactly three"), the two {@code force(true)} syscalls inside
 * the seams themselves (the seam pins every call site; the syscall's effect is only
 * observable across a crash), and one Jackson-equivalent mutant
 * ({@code ObjectNode.set(field, null)} coerces to {@code NullNode}).
 */
public class LineageSpoolTest {

    private static final String REPO = "bedroom";
    private static final String OCCURRED = "2026-08-01T00:00:00Z";

    private static LineageFact fact() {
        return new LineageFact(
                REPO,
                LineageProcessType.ARCHIVE_LOCAL,
                "op-1",
                OCCURRED,
                List.of(LineageEndpoint.document(REPO, "doc-1", "a.txt")),
                List.of(LineageEndpoint.archive(REPO, "doc-1", "doc-1", 1_700_000_000_000L)),
                List.of("purview"),
                "corr-1",
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.ARCHIVE_LOCAL,
                        List.of("nemaki://bedroom/objects/doc-1"),
                        List.of("nemaki://bedroom/archives/doc-1"),
                        Map.of("reason", "retention"),
                        null));
    }

    private static LineageSpoolPayloadV1 payload() {
        return LineageSpoolPayloadV1.of(fact());
    }

    // ------------------------------------------------------------------ identity

    @Nested
    class Identity {

        /** The §6-a table: a retried business fact converges on one id; a repeat does not. */
        @Test
        public void retriesShareTheIdAndRepeatsDoNot() {
            LineageSpoolPayloadV1 first = payload();
            LineageSpoolPayloadV1 retry = payload();
            assertEquals(first.spoolRecordId(), retry.spoolRecordId());
            assertEquals(first.payloadDigest(), retry.payloadDigest());

            LineageFact repeated = new LineageFact(REPO, LineageProcessType.ARCHIVE_LOCAL,
                    "op-2", OCCURRED, fact().inputs(), fact().outputs(), List.of("purview"),
                    "corr-1", fact().legacyProjection());
            assertNotEquals(first.spoolRecordId(),
                    LineageSpoolPayloadV1.of(repeated).spoolRecordId(),
                    "a new operationId is a new fact");
        }

        /** Identity sees qualified names only; attributes are the digest's job. */
        @Test
        public void attributeChangesMoveTheDigestButNotTheId() {
            LineageSpoolPayloadV1 base = payload();
            LineageFact renamed = new LineageFact(REPO, LineageProcessType.ARCHIVE_LOCAL,
                    "op-1", OCCURRED,
                    List.of(LineageEndpoint.document(REPO, "doc-1", "RENAMED.txt")),
                    fact().outputs(), List.of("purview"), "corr-1", fact().legacyProjection());
            LineageSpoolPayloadV1 changed = LineageSpoolPayloadV1.of(renamed);
            assertEquals(base.spoolRecordId(), changed.spoolRecordId());
            assertNotEquals(base.payloadDigest(), changed.payloadDigest());
        }

        /** The legacy projection is content: its order, multiplicity and presence all digest. */
        @Test
        public void theLegacyProjectionParticipatesInTheDigestVerbatim() {
            String id = payload().spoolRecordId();
            String withLegacy = LineageSpoolIdentity.payloadDigest(id, 1L, fact().inputs(),
                    fact().outputs(), "corr-1", fact().legacyProjection());
            String withoutLegacy = LineageSpoolIdentity.payloadDigest(id, 1L, fact().inputs(),
                    fact().outputs(), "corr-1", null);
            assertNotEquals(withLegacy, withoutLegacy);

            LineageFact.LegacyV1Projection reordered = new LineageFact.LegacyV1Projection(
                    LineageProcessType.ARCHIVE_LOCAL,
                    List.of("nemaki://bedroom/objects/doc-1", "nemaki://bedroom/objects/doc-1"),
                    List.of("nemaki://bedroom/archives/doc-1"),
                    Map.of("reason", "retention"), null);
            assertNotEquals(withLegacy, LineageSpoolIdentity.payloadDigest(id, 1L,
                            fact().inputs(), fact().outputs(), "corr-1", reordered),
                    "legacy multiplicity is content — it drives the v1 eventKey");
        }

        @Test
        public void nullAndEmptyCorrelationIdsAreDifferentDigests() {
            String id = payload().spoolRecordId();
            assertNotEquals(
                    LineageSpoolIdentity.payloadDigest(id, 1L, fact().inputs(), fact().outputs(),
                            null, null),
                    LineageSpoolIdentity.payloadDigest(id, 1L, fact().inputs(), fact().outputs(),
                            "", null));
        }

        @Test
        public void blankIdentityInputsAreRejectedUpFront() {
            assertThrows(IllegalArgumentException.class, () -> LineageSpoolIdentity.spoolRecordId(
                    " ", LineageProcessType.ARCHIVE_LOCAL, "op-1", fact().inputs(),
                    fact().outputs(), List.of("purview"), 0L, 1L, OCCURRED));
            assertThrows(IllegalArgumentException.class, () -> LineageSpoolIdentity.spoolRecordId(
                    REPO, LineageProcessType.ARCHIVE_LOCAL, " ", fact().inputs(),
                    fact().outputs(), List.of("purview"), 0L, 1L, OCCURRED));
            assertThrows(IllegalArgumentException.class, () -> LineageSpoolIdentity.spoolRecordId(
                    REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1", fact().inputs(),
                    fact().outputs(), List.of("purview"), 0L, 1L, " "));
            assertThrows(IllegalArgumentException.class, () -> LineageSpoolIdentity.payloadDigest(
                    " ", 1L, fact().inputs(), fact().outputs(), null, null));
        }

        /**
         * Verification means "a valid spool fact", not "a self-consistent hash pair": a record
         * that could never have come from the producer conversion must not verify even when
         * its hashes agree.
         */
        @Test
        public void hashConsistencyAloneIsNotVerification() {
            LineageSpoolPayloadV1 good = payload();

            LineageSpoolPayloadV1 wrongSchema = new LineageSpoolPayloadV1(2L,
                    good.spoolRecordId(), good.repositoryId(), good.processType(),
                    good.operationId(), good.occurredAt(), good.inputs(), good.outputs(),
                    good.canonicalTargetSet(), 0L, 1L, good.correlationId(),
                    good.legacyV1Projection(),
                    LineageSpoolIdentity.payloadDigest(good.spoolRecordId(), 2L, good.inputs(),
                            good.outputs(), good.correlationId(), good.legacyV1Projection()));
            assertFalse(wrongSchema.selfVerifies(), "schema 2 is not this record type");

            String chunkedId = LineageSpoolIdentity.spoolRecordId(REPO,
                    LineageProcessType.ARCHIVE_LOCAL, "op-1", fact().inputs(), fact().outputs(),
                    List.of("purview"), 1L, 2L, OCCURRED);
            LineageSpoolPayloadV1 chunked = new LineageSpoolPayloadV1(1L, chunkedId, REPO,
                    LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED, fact().inputs(),
                    fact().outputs(), List.of("purview"), 1L, 2L, null, null,
                    LineageSpoolIdentity.payloadDigest(chunkedId, 1L, fact().inputs(),
                            fact().outputs(), null, null));
            assertFalse(chunked.selfVerifies(),
                    "producer-level facts are 0/1 — chunking precedes no schema decision");

            LineageSpoolPayloadV1 nonCanonicalTargets = new LineageSpoolPayloadV1(1L,
                    good.spoolRecordId(), good.repositoryId(), good.processType(),
                    good.operationId(), good.occurredAt(), good.inputs(), good.outputs(),
                    List.of("purview", "atlas"), 0L, 1L, good.correlationId(),
                    good.legacyV1Projection(), good.payloadDigest());
            assertFalse(nonCanonicalTargets.selfVerifies(),
                    "'purview,atlas' is not in canonical (sorted) form");

            List<LineageEndpoint> crossRepo = List.of(
                    LineageEndpoint.document("canopy", "doc-x", "x.txt"));
            String crossId = LineageSpoolIdentity.spoolRecordId(REPO,
                    LineageProcessType.ARCHIVE_LOCAL, "op-1", crossRepo, fact().outputs(),
                    List.of("purview"), 0L, 1L, OCCURRED);
            LineageSpoolPayloadV1 crossRepoPayload = new LineageSpoolPayloadV1(1L, crossId,
                    REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED, crossRepo,
                    fact().outputs(), List.of("purview"), 0L, 1L, null, null,
                    LineageSpoolIdentity.payloadDigest(crossId, 1L, crossRepo,
                            fact().outputs(), null, null));
            assertFalse(crossRepoPayload.selfVerifies(),
                    "a cross-repository endpoint never came from the producer conversion");

            List<LineageEndpoint> foreignArtifact = List.of(
                    LineageEndpoint.exportArtifact(REPO, "op-OTHER", "ZIP", "z.zip", 1L));
            List<LineageEndpoint> artifactInputs = List.of(
                    LineageEndpoint.document(REPO, "doc-1", "a.txt"));
            String artifactId = LineageSpoolIdentity.spoolRecordId(REPO,
                    LineageProcessType.EXPORT_ZIP_FOLDER, "op-1", artifactInputs,
                    foreignArtifact, List.of("purview"), 0L, 1L, OCCURRED);
            LineageSpoolPayloadV1 foreignArtifactPayload = new LineageSpoolPayloadV1(1L,
                    artifactId, REPO, LineageProcessType.EXPORT_ZIP_FOLDER, "op-1", OCCURRED,
                    artifactInputs, foreignArtifact, List.of("purview"), 0L, 1L, null, null,
                    LineageSpoolIdentity.payloadDigest(artifactId, 1L, artifactInputs,
                            foreignArtifact, null, null));
            assertFalse(foreignArtifactPayload.selfVerifies(),
                    "an artifact bound to another operation is not this fact's");

            List<LineageEndpoint> wrongShape = List.of(
                    LineageEndpoint.folder(REPO, "f-1", "F"));
            String shapeId = LineageSpoolIdentity.spoolRecordId(REPO,
                    LineageProcessType.ARCHIVE_LOCAL, "op-1", wrongShape, fact().outputs(),
                    List.of("purview"), 0L, 1L, OCCURRED);
            LineageSpoolPayloadV1 wrongShapePayload = new LineageSpoolPayloadV1(1L, shapeId,
                    REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED, wrongShape,
                    fact().outputs(), List.of("purview"), 0L, 1L, null, null,
                    LineageSpoolIdentity.payloadDigest(shapeId, 1L, wrongShape,
                            fact().outputs(), null, null));
            assertFalse(wrongShapePayload.selfVerifies(),
                    "ARCHIVE_LOCAL never archives a folder — the shape table binds here too");

            String blankCorrId = payload().spoolRecordId();
            LineageSpoolPayloadV1 blankCorrelation = new LineageSpoolPayloadV1(1L,
                    blankCorrId, REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED,
                    fact().inputs(), fact().outputs(), List.of("purview"), 0L, 1L, "",
                    fact().legacyProjection(),
                    LineageSpoolIdentity.payloadDigest(blankCorrId, 1L, fact().inputs(),
                            fact().outputs(), "", fact().legacyProjection()));
            assertFalse(blankCorrelation.selfVerifies(),
                    "a blank correlationId would be appendable but undecodable");

            LineageSpoolPayloadV1 badTimestamp = new LineageSpoolPayloadV1(1L,
                    good.spoolRecordId(), good.repositoryId(), good.processType(),
                    good.operationId(), "yesterday", good.inputs(), good.outputs(),
                    good.canonicalTargetSet(), 0L, 1L, good.correlationId(),
                    good.legacyV1Projection(), good.payloadDigest());
            assertFalse(badTimestamp.selfVerifies());
        }

        @Test
        public void aVerifiedPayloadSelfVerifiesAndATamperedOneDoesNot() {
            LineageSpoolPayloadV1 good = payload();
            assertTrue(good.selfVerifies());
            LineageSpoolPayloadV1 tampered = new LineageSpoolPayloadV1(
                    good.spoolSchemaVersion(), good.spoolRecordId(), good.repositoryId(),
                    good.processType(), "op-TAMPERED", good.occurredAt(), good.inputs(),
                    good.outputs(), good.canonicalTargetSet(), good.chunkIndex(),
                    good.chunkCount(), good.correlationId(), good.legacyV1Projection(),
                    good.payloadDigest());
            assertFalse(tampered.selfVerifies());
        }
    }

    // ------------------------------------------------------------------ codec

    @Nested
    class Codec {

        @Test
        public void encodeDecodeRoundTripsAndStillSelfVerifies() {
            LineageSpoolPayloadV1 original = payload();
            LineageSpoolPayloadV1 decoded =
                    LineageSpoolCodec.decode(LineageSpoolCodec.encode(original));
            assertEquals(original, decoded, "COUNT attributes must come back as Long, not"
                    + " Integer — record equality covers every field");
            assertTrue(decoded.selfVerifies());
        }

        @Test
        public void aPayloadWithoutLegacyProjectionRoundTrips() {
            LineageFact noLegacyCorr = fact();
            LineageSpoolPayloadV1 original = new LineageSpoolPayloadV1(1L,
                    payload().spoolRecordId(), REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1",
                    OCCURRED, noLegacyCorr.inputs(), noLegacyCorr.outputs(), List.of("purview"),
                    0L, 1L, null, null,
                    LineageSpoolIdentity.payloadDigest(payload().spoolRecordId(), 1L,
                            noLegacyCorr.inputs(), noLegacyCorr.outputs(), null, null));
            LineageSpoolPayloadV1 decoded =
                    LineageSpoolCodec.decode(LineageSpoolCodec.encode(original));
            assertEquals(original, decoded);
            assertTrue(decoded.selfVerifies());
        }

        /** operationId-bearing endpoints and a preset v1 eventId must survive the round trip. */
        @Test
        public void artifactEndpointsAndPresetEventIdsRoundTrip() {
            LineageEndpoint artifact = LineageEndpoint.exportArtifact(
                    REPO, "op-9", "ZIP", "out.zip", 2L);
            LineageFact.LegacyV1Projection preset = new LineageFact.LegacyV1Projection(
                    LineageProcessType.EXPORT_ZIP_FOLDER,
                    List.of("nemaki://bedroom/objects/folder-1"), List.of(),
                    Map.of("folderName", "Docs"), "evt-preset-1");
            List<LineageEndpoint> inputs = List.of(
                    LineageEndpoint.folder(REPO, "folder-1", "Docs"));
            String id = LineageSpoolIdentity.spoolRecordId(REPO,
                    LineageProcessType.EXPORT_ZIP_FOLDER, "op-9", inputs, List.of(artifact),
                    List.of("purview"), 0L, 1L, OCCURRED);
            LineageSpoolPayloadV1 original = new LineageSpoolPayloadV1(1L, id, REPO,
                    LineageProcessType.EXPORT_ZIP_FOLDER, "op-9", OCCURRED, inputs,
                    List.of(artifact), List.of("purview"), 0L, 1L, "corr-9", preset,
                    LineageSpoolIdentity.payloadDigest(id, 1L, inputs, List.of(artifact),
                            "corr-9", preset));
            LineageSpoolPayloadV1 decoded =
                    LineageSpoolCodec.decode(LineageSpoolCodec.encode(original));
            assertEquals(original, decoded);
            assertTrue(decoded.selfVerifies());
            assertEquals("evt-preset-1", decoded.legacyV1Projection().presetEventId());
        }

        /** Strictness is symmetry: representations encode never writes are rejected. */
        @Test
        public void representationsEncodeNeverWritesAreRejected() {
            String json = LineageSpoolCodec.encode(payload());
            IllegalArgumentException unknownTop = assertThrows(IllegalArgumentException.class,
                    () -> LineageSpoolCodec.decode(
                            json.replaceFirst("\\{", "{ \"unknownField\" : 1,")));
            assertTrue(unknownTop.getMessage().contains("unknown"), unknownTop.getMessage());
            IllegalArgumentException missingField = assertThrows(IllegalArgumentException.class,
                    () -> LineageSpoolCodec.decode(json.replaceFirst(
                            "\"correlationId\"", "\"correlationIdRenamed\"")));
            assertTrue(missingField.getMessage().contains("missing"),
                    missingField.getMessage());
            assertThrows(IllegalArgumentException.class, () -> LineageSpoolCodec.decode(
                    json.replaceFirst("\"kind\"", "\"extraEndpointField\" : 1, \"kind\"")),
                    "unknown endpoint-level fields");
            assertThrows(IllegalArgumentException.class, () -> LineageSpoolCodec.decode(
                    json.replaceFirst("\"presetEventId\"",
                            "\"extraLegacyField\" : 1, \"presetEventId\"")),
                    "unknown legacy-level fields");
            assertThrows(IllegalArgumentException.class, () -> LineageSpoolCodec.decode(
                    json.replaceFirst("\"spoolSchemaVersion\" : 1",
                            "\"spoolSchemaVersion\" : 1, \"spoolSchemaVersion\" : 1")),
                    "duplicate keys");
            assertThrows(IllegalArgumentException.class, () -> LineageSpoolCodec.decode(
                    json.replaceFirst("\"chunkIndex\" : 0",
                            "\"chunkIndex\" : 99999999999999999999999999")),
                    "integers beyond signed-long range");
            assertThrows(IllegalArgumentException.class, () -> LineageSpoolCodec.decode(
                    json.replaceFirst("\"presetEventId\" : null",
                            "\"presetEventId\" : \"  \"")),
                    "a blank presetEventId is a representation the producer types cannot hold");
        }

        @Test
        public void unknownSchemaVersionsAndMalformedRecordsAreRejected() {
            String json = LineageSpoolCodec.encode(payload());
            assertThrows(IllegalArgumentException.class, () ->
                    LineageSpoolCodec.decode(json.replace("\"spoolSchemaVersion\" : 1",
                            "\"spoolSchemaVersion\" : 2")));
            assertThrows(IllegalArgumentException.class, () ->
                    LineageSpoolCodec.decode("not json at all"));
            assertThrows(IllegalArgumentException.class, () ->
                    LineageSpoolCodec.decode("[1,2,3]"));
            assertThrows(IllegalArgumentException.class, () ->
                    LineageSpoolCodec.decode(json.replace("ARCHIVE_LOCAL", "NO_SUCH_TYPE")));
        }
    }

    // ------------------------------------------------------------------ file store

    @Nested
    class FileStore {

        @TempDir
        Path dir;

        private LineageFactSpool spool(LineageMetrics metrics) {
            return new LineageFactSpool(dir, metrics);
        }

        @Test
        public void appendsLandOnTheOccurredAtUtcPathAndAreIdempotent() throws Exception {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool spool = spool(metrics);
            LineageSpoolPayloadV1 payload = payload();

            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));
            Path expected = dir.resolve(LineageFactSpool.repositorySegment(REPO))
                    .resolve("20260801")
                    .resolve("fact-" + payload.spoolRecordId() + ".json");
            assertTrue(Files.isRegularFile(expected), "yyyyMMdd comes from occurredAt in UTC,"
                    + " and the repository id is safe-encoded, never a raw path segment");

            assertEquals(LineageFactSpool.AppendOutcome.IDEMPOTENT, spool.append(payload),
                    "the same fact retried converges instead of duplicating");
            assertEquals(1, metrics.getSpoolAppended());
            assertEquals(1, metrics.getSpoolIdempotent());
            try (var entries = Files.list(expected.getParent())) {
                assertEquals(1, entries.count(), "no temp files left behind");
            }
        }

        @Test
        public void aConflictingPayloadIsQuarantinedNeverOverwritten() throws Exception {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool spool = spool(metrics);
            LineageSpoolPayloadV1 original = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(original));

            // Same identity, different verified content — the identity rule "broke".
            LineageFact renamed = new LineageFact(REPO, LineageProcessType.ARCHIVE_LOCAL,
                    "op-1", OCCURRED,
                    List.of(LineageEndpoint.document(REPO, "doc-1", "RENAMED.txt")),
                    fact().outputs(), List.of("purview"), "corr-1", fact().legacyProjection());
            LineageSpoolPayloadV1 conflicting = LineageSpoolPayloadV1.of(renamed);
            assertEquals(original.spoolRecordId(), conflicting.spoolRecordId());

            assertEquals(LineageFactSpool.AppendOutcome.QUARANTINED, spool.append(conflicting));
            Path record = spool.recordPath(original);
            Path quarantine = LineageFactSpool.quarantinePath(record);
            assertTrue(Files.isRegularFile(quarantine), "the conflicter is preserved");
            assertEquals(original, spool.readVerified(record),
                    "the original record is untouched");
            assertEquals(1, metrics.getSpoolQuarantined());
            assertEquals(1, metrics.getSpoolQuarantined("digest_mismatch"));

            // A second conflicter finds the quarantine slot occupied and is dropped loudly.
            assertEquals(LineageFactSpool.AppendOutcome.QUARANTINED, spool.append(conflicting));
            assertEquals(conflicting, spool.readVerified(quarantine),
                    "the first conflicter is never overwritten by later ones");
        }

        @Test
        public void aCorruptDurableRecordIsHealedAndTheEvidencePreserved() throws Exception {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool spool = spool(metrics);
            LineageSpoolPayloadV1 payload = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));

            Path record = spool.recordPath(payload);
            Files.writeString(record, "{ corrupted", StandardCharsets.UTF_8);

            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload),
                    "a verified retry republishes over a corrupt record");
            assertEquals(payload, spool.readVerified(record));
            assertTrue(Files.isRegularFile(LineageFactSpool.quarantinePath(record)),
                    "the corrupt bytes are preserved as evidence");
            assertEquals(1, metrics.getSpoolQuarantined(), "the healed corruption is counted");
            assertEquals(1, metrics.getSpoolQuarantined("self_check_failed"));
            assertEquals(2, metrics.getSpoolAppended(), "initial write plus the republish");
        }

        @Test
        public void aCorruptRecordWithAnOccupiedQuarantineSlotIsLeftAlone() throws Exception {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool spool = spool(metrics);
            LineageSpoolPayloadV1 payload = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));
            Path record = spool.recordPath(payload);
            Files.writeString(record, "{ corrupted", StandardCharsets.UTF_8);
            Files.writeString(LineageFactSpool.quarantinePath(record), "{ earlier evidence",
                    StandardCharsets.UTF_8);

            assertEquals(LineageFactSpool.AppendOutcome.QUARANTINED, spool.append(payload),
                    "no slot to heal into — the retry is dropped loudly, nothing overwritten");
            assertEquals("{ corrupted", Files.readString(record));
            assertEquals("{ earlier evidence",
                    Files.readString(LineageFactSpool.quarantinePath(record)));
            assertEquals(1, metrics.getSpoolQuarantined());
        }

        @Test
        public void theReadinessProbeCleansUpAfterItself() throws Exception {
            LineageFactSpool spool = spool(null);
            assertTrue(spool.probeReadiness());
            Path probeDir = dir.resolve(".probe");
            try (var entries = Files.list(probeDir)) {
                assertEquals(0, entries.count(), "probe files are removed after the probe");
            }
        }

        @Test
        public void concurrentWritersOfTheSameFactConvergeOnOneRecord() throws Exception {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool spool = spool(metrics);
            LineageSpoolPayloadV1 payload = payload();
            int writers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger appended = new AtomicInteger();
            AtomicInteger idempotent = new AtomicInteger();
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < writers; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        switch (spool.append(payload)) {
                            case APPENDED -> appended.incrementAndGet();
                            case IDEMPOTENT -> idempotent.incrementAndGet();
                            default -> { }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (var future : futures) {
                    future.get();
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(writers, appended.get() + idempotent.get(),
                    "every writer converges; none fails or quarantines");
            assertEquals(1, appended.get(), "exactly one writer creates the record");
            assertEquals(payload, spool.readVerified(spool.recordPath(payload)));
        }

        @Test
        public void aPayloadThatFailsSelfVerificationIsRefused() {
            LineageMetrics metrics = new LineageMetrics();
            LineageSpoolPayloadV1 good = payload();
            LineageSpoolPayloadV1 tampered = new LineageSpoolPayloadV1(
                    good.spoolSchemaVersion(), good.spoolRecordId(), good.repositoryId(),
                    good.processType(), "op-TAMPERED", good.occurredAt(), good.inputs(),
                    good.outputs(), good.canonicalTargetSet(), good.chunkIndex(),
                    good.chunkCount(), good.correlationId(), good.legacyV1Projection(),
                    good.payloadDigest());
            assertEquals(LineageFactSpool.AppendOutcome.FAILED, spool(metrics).append(tampered));
            assertEquals(1, metrics.getSpoolWriteFailed());
        }

        @Test
        public void theReadinessProbeAnswersTrueOnARealFilesystem() {
            assertTrue(spool(null).probeReadiness(),
                    "temp dirs support hard links on every CI platform we run");
        }

        /** §8's safety table: raw repository ids are traversal vectors and never path segments. */
        @Test
        public void hostileRepositoryIdsCannotEscapeTheSpoolRoot() {
            String segment = LineageFactSpool.repositorySegment("../../etc/passwd");
            assertFalse(segment.contains("/"), segment);
            assertFalse(segment.contains(".."), segment);
            assertNotEquals(LineageFactSpool.repositorySegment("Bedroom"),
                    LineageFactSpool.repositorySegment("bedroom"),
                    "distinct even where base64 differs only by letter case");
        }

        @Test
        public void filesAndDirectoriesCarryRestrictivePermissions() throws Exception {
            LineageFactSpool spool = spool(null);
            LineageSpoolPayloadV1 payload = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));
            Path record = spool.recordPath(payload);
            assertEquals("rw-------",
                    java.nio.file.attribute.PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(record)));
            assertEquals("rwx------",
                    java.nio.file.attribute.PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(record.getParent())));
        }

        /** The record limit is symmetric: what the reader would refuse is never written. */
        @Test
        public void oversizedPayloadsAreRefusedAtWriteTime() {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool tiny = new LineageFactSpool(dir, metrics, 64);
            assertEquals(LineageFactSpool.AppendOutcome.FAILED, tiny.append(payload()),
                    "a record the reader would reject must never be written");
            assertEquals(1, metrics.getSpoolWriteFailed());
        }

        /**
         * Every durability call is pinned with EXACT counts per flow — a threshold would let a
         * removed publication fsync hide behind the directory-creation fsyncs. The exact
         * numbers encode the protocol: createDirectoriesDurably syncs each level from the leaf
         * to the base (3 for {@code repo/day} under the base), publication adds one, an
         * idempotent acknowledgement adds one, and every durable write forces its file once.
         */
        @Test
        public void everyDurabilityCallIsPinnedExactly() throws Exception {
            AtomicInteger dirFsyncs = new AtomicInteger();
            AtomicInteger fileForces = new AtomicInteger();
            LineageFactSpool counting = new LineageFactSpool(dir, null) {
                @Override
                protected void fsyncDirectory(Path directory) throws java.io.IOException {
                    dirFsyncs.incrementAndGet();
                    super.fsyncDirectory(directory);
                }

                @Override
                protected void forceFile(java.nio.channels.FileChannel channel)
                        throws java.io.IOException {
                    fileForces.incrementAndGet();
                    super.forceFile(channel);
                }
            };
            LineageSpoolPayloadV1 payload = payload();

            assertTrue(counting.probeReadiness());
            // probe: createDirectoriesDurably(.probe) = 2 (probe dir + base), publication = 1
            assertEquals(3, dirFsyncs.get(), "probe fsyncs: probe dir, base, post-link");
            assertEquals(1, fileForces.get(), "the probe's write forces its file");

            dirFsyncs.set(0);
            fileForces.set(0);
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, counting.append(payload));
            // createDirectoriesDurably(repo/day) = 3 (day, repo, base) + publication = 1
            assertEquals(4, dirFsyncs.get(), "append fsyncs: day, repo, base, post-link");
            assertEquals(1, fileForces.get(), "the record write forces its file");

            dirFsyncs.set(0);
            fileForces.set(0);
            assertEquals(LineageFactSpool.AppendOutcome.IDEMPOTENT, counting.append(payload));
            // same 3 for the directory chain + the acknowledgement fsync of the winner's link
            assertEquals(4, dirFsyncs.get(),
                    "an IDEMPOTENT answer is an acknowledgement and must fsync first");
            assertEquals(1, fileForces.get(), "the loser still durably wrote its temp");
        }

        /** A symlink anywhere on the spool path is filesystem traversal and fails closed. */
        @Test
        public void symlinkedSpoolLevelsAreRejected() throws Exception {
            LineageFactSpool spool = spool(null);
            LineageSpoolPayloadV1 payload = payload();
            Path repoDir = dir.resolve(LineageFactSpool.repositorySegment(REPO));
            Path elsewhere = Files.createDirectory(dir.resolveSibling(
                    dir.getFileName() + "-elsewhere"));
            Files.createSymbolicLink(repoDir, elsewhere);
            assertEquals(LineageFactSpool.AppendOutcome.FAILED, spool.append(payload),
                    "an existing symlink at a spool level must not be followed");
            try (var entries = Files.list(elsewhere)) {
                assertEquals(0, entries.count(), "nothing was written through the symlink");
            }
        }

        /** The spool root is operator-provisioned; an absent root is NOT_READY, not mkdir. */
        @Test
        public void anAbsentBaseDirectoryFailsTheProbeClosed() {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool spool = new LineageFactSpool(dir.resolve("does-not-exist"),
                    metrics);
            assertFalse(spool.probeReadiness());
            assertEquals(LineageFactSpool.AppendOutcome.FAILED, spool.append(payload()));
            assertEquals(1, metrics.getSpoolWriteFailed(),
                    "a fail-closed write is the caller's dropped-metric cue and is counted");
        }

        /** The size bound is exact on both sides: the limit itself fits, one less refuses. */
        @Test
        public void theRecordLimitBoundaryIsExact() throws Exception {
            LineageSpoolPayloadV1 payload = payload();
            int encoded = LineageSpoolCodec.encode(payload)
                    .getBytes(StandardCharsets.UTF_8).length;
            LineageFactSpool exact = new LineageFactSpool(dir, null, encoded);
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, exact.append(payload),
                    "a record of exactly the limit is within it");
            assertEquals(payload, exact.readVerified(exact.recordPath(payload)),
                    "and reads back at exactly the limit");

            Path second = Files.createDirectory(dir.resolveSibling(
                    dir.getFileName() + "-second"));
            LineageFactSpool oneLess = new LineageFactSpool(second, null, encoded - 1);
            assertEquals(LineageFactSpool.AppendOutcome.FAILED, oneLess.append(payload));
            assertThrows(IllegalArgumentException.class,
                    () -> oneLess.readVerified(exact.recordPath(payload)));
        }

        @Test
        public void aNonPositiveRecordLimitIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new LineageFactSpool(dir, null, 0));
        }

        /** The read side enforces the bound while streaming, not via a raceable pre-check. */
        @Test
        public void readsOfOversizedFilesAreRefused() throws Exception {
            LineageFactSpool writer = new LineageFactSpool(dir, null);
            LineageSpoolPayloadV1 payload = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, writer.append(payload));
            LineageFactSpool smallReader = new LineageFactSpool(dir, null, 64);
            assertThrows(IllegalArgumentException.class,
                    () -> smallReader.readVerified(writer.recordPath(payload)));
        }

        /** The heal and conflict paths pin their fsyncs exactly, like the happy paths. */
        @Test
        public void healAndQuarantineAlsoFsyncExactly() throws Exception {
            AtomicInteger fsyncs = new AtomicInteger();
            LineageFactSpool counting = new LineageFactSpool(dir, null) {
                @Override
                protected void fsyncDirectory(Path directory) throws java.io.IOException {
                    fsyncs.incrementAndGet();
                    super.fsyncDirectory(directory);
                }
            };
            // Two independent facts so each scenario meets a FREE quarantine slot.
            LineageSpoolPayloadV1 healed = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, counting.append(healed));
            Files.writeString(counting.recordPath(healed), "{ corrupted",
                    StandardCharsets.UTF_8);
            fsyncs.set(0);
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, counting.append(healed));
            // directory chain (day, repo, base) = 3, the quarantine move's fsync = 1, the
            // republished link's fsync = 1
            assertEquals(5, fsyncs.get(),
                    "heal fsyncs: chain ×3, quarantine move, republished link");

            LineageFact other = new LineageFact(REPO, LineageProcessType.ARCHIVE_LOCAL,
                    "op-conflict", OCCURRED, fact().inputs(), fact().outputs(),
                    List.of("purview"), "corr-1", fact().legacyProjection());
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED,
                    counting.append(LineageSpoolPayloadV1.of(other)));
            LineageFact otherRenamed = new LineageFact(REPO, LineageProcessType.ARCHIVE_LOCAL,
                    "op-conflict", OCCURRED,
                    List.of(LineageEndpoint.document(REPO, "doc-1", "RENAMED.txt")),
                    fact().outputs(), List.of("purview"), "corr-1", fact().legacyProjection());
            fsyncs.set(0);
            assertEquals(LineageFactSpool.AppendOutcome.QUARANTINED,
                    counting.append(LineageSpoolPayloadV1.of(otherRenamed)));
            // directory chain ×3 + the quarantine link's fsync (the slot is free here)
            assertEquals(4, fsyncs.get(), "conflict fsyncs: chain ×3, quarantine link");
        }

        /** The probe result is cached in both directions — pinned against flipped returns. */
        @Test
        public void theProbeResultIsCachedInBothDirections() {
            LineageFactSpool healthy = spool(null);
            assertTrue(healthy.probeReadiness());
            assertTrue(healthy.probeReadiness(), "a true result stays true");

            LineageFactSpool broken = new LineageFactSpool(dir.resolve("missing"), null);
            assertFalse(broken.probeReadiness());
            assertFalse(broken.probeReadiness(), "a false result stays false — no re-probe");
        }

        /** A mid-flow IO failure (after a passing probe) lands in the dropped metric too. */
        @Test
        public void aMidFlowIoFailureIsCountedAsDropped() {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool failing = new LineageFactSpool(dir, metrics) {
                @Override
                protected void createLinkAtomically(Path target, Path tmp)
                        throws java.io.IOException {
                    throw new java.io.IOException("disk vanished mid-flow");
                }
            };
            assertEquals(LineageFactSpool.AppendOutcome.FAILED, failing.append(payload()));
            assertEquals(1, metrics.getSpoolWriteFailed());
        }

        /**
         * The inner double-checked-locking return: thread B blocks at the monitor while
         * thread A probes; once A publishes its (negative) result, B must return that result
         * from the inner check — never a fabricated true.
         */
        @Test
        public void aConcurrentProbeSeesTheFirstProbesResult() throws Exception {
            CountDownLatch probeEntered = new CountDownLatch(1);
            CountDownLatch releaseProbe = new CountDownLatch(1);
            LineageFactSpool gated = new LineageFactSpool(dir, null) {
                @Override
                protected void fsyncDirectory(Path directory) throws java.io.IOException {
                    probeEntered.countDown();
                    try {
                        releaseProbe.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(e);
                    }
                    throw new java.io.IOException("fsync unsupported");
                }
            };
            java.util.concurrent.atomic.AtomicBoolean second =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread first = new Thread(gated::probeReadiness);
            first.start();
            assertTrue(probeEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Thread waiter = new Thread(() -> second.set(gated.probeReadiness()));
            waiter.start();
            Thread.sleep(50); // let the waiter reach the monitor
            releaseProbe.countDown();
            first.join(5000);
            waiter.join(5000);
            assertFalse(second.get(), "the blocked thread adopts the first probe's verdict");
        }

        /** A sustained link race exhausts the bounded retry and lands in the dropped metric. */
        @Test
        public void aSustainedLinkRaceExhaustsTheRetryLoudly() throws Exception {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool contested = new LineageFactSpool(dir, metrics) {
                @Override
                protected void createLinkAtomically(Path target, Path tmp)
                        throws java.io.IOException {
                    // Every attempt finds the target taken, and by the time converge looks,
                    // it is gone again — the pathological scanner/healer race, sustained.
                    throw new java.nio.file.FileAlreadyExistsException(target.toString());
                }
            };
            assertEquals(LineageFactSpool.AppendOutcome.FAILED, contested.append(payload()),
                    "bounded retry exhaustion is a loud FAILED, not a hang or a lie");
            assertEquals(1, metrics.getSpoolWriteFailed(),
                    "exhaustion lands in the caller's dropped-metric accounting");
        }

        /** The scanner's quarantine normalises externally injected modes to 0600. */
        @Test
        public void scannerQuarantineNormalisesPermissions() throws Exception {
            LineageFactSpool spool = new LineageFactSpool(dir, null);
            LineageSpoolPayloadV1 payload = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));
            Path broken = spool.recordPath(payload).getParent()
                    .resolve("fact-" + "0".repeat(64) + ".json");
            Files.writeString(broken, "{ injected", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(broken,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

            new LineageSpoolScanner(spool, null).scan(dir, null);
            Path quarantine = LineageFactSpool.quarantinePath(broken);
            assertEquals("rw-------",
                    java.nio.file.attribute.PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(quarantine)));
        }

        /** A too-open provisioned root is tightened by the probe, per §8's 0700 contract. */
        @Test
        public void theProbeNormalisesTheBaseDirectoryMode() throws Exception {
            Files.setPosixFilePermissions(dir,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            LineageFactSpool spool = spool(null);
            assertTrue(spool.probeReadiness());
            assertEquals("rwx------",
                    java.nio.file.attribute.PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(dir)));
        }

        /** A filesystem that cannot fsync directories must fail the probe, not limp on. */
        @Test
        public void aFailingDirectoryFsyncFailsTheProbeClosed() {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool broken = new LineageFactSpool(dir, metrics) {
                @Override
                protected void fsyncDirectory(Path directory) throws java.io.IOException {
                    throw new java.io.IOException("directory fsync unsupported");
                }
            };
            assertFalse(broken.probeReadiness());
            assertEquals(LineageFactSpool.AppendOutcome.FAILED, broken.append(payload()));
            assertEquals(1, metrics.getSpoolWriteFailed());
        }
    }

    // ------------------------------------------------------------------ scanner

    @Nested
    class Scanner {

        @TempDir
        Path dir;

        @Test
        public void verifiedFactsReachTheMaterializerAndBrokenOnesNeverDo() throws Exception {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool spool = new LineageFactSpool(dir, metrics);
            LineageSpoolPayloadV1 payload = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));

            // A tampered record sitting beside it: §6-a test 13c — rejected by self-check,
            // never handed to the materializer, quarantined.
            Path broken = spool.recordPath(payload).getParent()
                    .resolve("fact-" + "0".repeat(64) + ".json");
            Files.writeString(broken,
                    LineageSpoolCodec.encode(payload).replace("corr-1", "corr-TAMPERED"),
                    StandardCharsets.UTF_8);

            java.util.List<String> materialized = new java.util.ArrayList<>();
            LineageSpoolScanner scanner = new LineageSpoolScanner(spool, metrics);
            LineageSpoolScanner.ScanSummary summary =
                    scanner.scan(dir, p -> materialized.add(p.spoolRecordId()));

            assertEquals(List.of(payload.spoolRecordId()), materialized);
            assertEquals(1, summary.verified());
            assertEquals(1, summary.quarantinedNow());
            assertFalse(Files.exists(broken), "the broken record was moved aside");
            assertTrue(Files.isRegularFile(LineageFactSpool.quarantinePath(broken)));
            assertEquals(1, metrics.getSpoolQuarantined(), "the scan counts what it moved");
            assertEquals(1, metrics.getSpoolQuarantined("self_check_failed"));
        }

        /** Enumeration failures are injectable through the seam and land in failed. */
        @Test
        public void enumerationFailuresAreCounted() throws Exception {
            LineageFactSpool spool = new LineageFactSpool(dir, null);
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload()));
            LineageSpoolScanner failing = new LineageSpoolScanner(spool, null) {
                @Override
                protected void collectDay(Path day, java.util.List<Path> files)
                        throws java.io.IOException {
                    throw new java.io.IOException("directory went away");
                }
            };
            LineageSpoolScanner.ScanSummary summary = failing.scan(dir, null);
            assertEquals(0, summary.verified());
            assertEquals(1, summary.failed(), "an unenumerable day directory is failed work");
        }

        /** The scanner's quarantine move follows the same durability rule as the store's. */
        @Test
        public void theScannerFsyncsItsQuarantineMove() throws Exception {
            AtomicInteger fsyncs = new AtomicInteger();
            LineageFactSpool counting = new LineageFactSpool(dir, null) {
                @Override
                protected void fsyncDirectory(Path directory) throws java.io.IOException {
                    fsyncs.incrementAndGet();
                    super.fsyncDirectory(directory);
                }
            };
            LineageSpoolPayloadV1 payload = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, counting.append(payload));
            Path broken = counting.recordPath(payload).getParent()
                    .resolve("fact-" + "0".repeat(64) + ".json");
            Files.writeString(broken, "{ corrupted", StandardCharsets.UTF_8);
            int before = fsyncs.get();
            LineageSpoolScanner.ScanSummary summary =
                    new LineageSpoolScanner(counting, null).scan(dir, null);
            assertEquals(1, summary.quarantinedNow());
            assertTrue(fsyncs.get() > before, "the move is not real until the entry is");
        }

        @Test
        public void quarantineFilesAreCountedButNeverMaterialized() throws Exception {
            LineageMetrics metrics = new LineageMetrics();
            LineageFactSpool spool = new LineageFactSpool(dir, metrics);
            LineageSpoolPayloadV1 payload = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));
            Path record = spool.recordPath(payload);
            Files.copy(record, LineageFactSpool.quarantinePath(record));

            java.util.List<String> materialized = new java.util.ArrayList<>();
            LineageSpoolScanner.ScanSummary summary = new LineageSpoolScanner(spool, metrics)
                    .scan(dir, p -> materialized.add(p.spoolRecordId()));
            assertEquals(1, summary.verified());
            assertEquals(1, summary.alreadyQuarantined());
            assertEquals(1, materialized.size());
        }

        @Test
        public void aMaterializerFailureLeavesTheFactInTheSpool() throws Exception {
            LineageFactSpool spool = new LineageFactSpool(dir, null);
            LineageSpoolPayloadV1 payload = payload();
            assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));

            LineageSpoolScanner.ScanSummary summary = new LineageSpoolScanner(spool, null)
                    .scan(dir, p -> {
                        throw new IllegalStateException("decision store down");
                    });
            assertEquals(1, summary.verified());
            assertEquals(1, summary.failed(), "failed work is reported, not silently dropped");
            assertTrue(Files.isRegularFile(spool.recordPath(payload)),
                    "the fact is retained for the next scan — that is what a spool is for");
        }
    }
}
