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

import java.util.List;
import java.util.Map;

/**
 * The durable, version-independent spool record of one business fact — §6-a's answer to "the
 * write-version flag is unreadable, and an event cannot be encoded without deciding a version".
 *
 * <p>This is an explicit conversion from {@link LineageFact}, never a serialisation of the
 * fact's Java type: the fact evolves with the code, while a spool record on disk must decode
 * years later. What rides here and why:
 *
 * <ul>
 *   <li>typed endpoints (A-1 types, version-free — each carries its own attributes);</li>
 *   <li>{@code canonicalTargetSet}, {@code chunkIndex}/{@code chunkCount} (0/1 for a
 *       producer-level fact: chunking is the fact→v2 mapper's job and happens only after the
 *       schema decision — chunking earlier would make faithful v1 reconstruction impossible);</li>
 *   <li>{@code correlationId} — both materialisations carry it;</li>
 *   <li>the optional {@link LineageFact.LegacyV1Projection} — a later materialisation to
 *       schemaVersion 1 must reproduce the v1 event byte-exactly, and typed endpoints cannot
 *       (the v1 eventKey hashes the raw strings; inference was rejected). Present until
 *       schema-1 materialisation is formally retired.</li>
 * </ul>
 *
 * <p>{@code spoolRecordId} and {@code payloadDigest} are stored for audit and file naming but
 * are <b>recomputed, never trusted</b>, whenever a record is read back.
 */
public record LineageSpoolPayloadV1(
        long spoolSchemaVersion,
        String spoolRecordId,
        String repositoryId,
        LineageProcessType processType,
        String operationId,
        String occurredAt,
        List<LineageEndpoint> inputs,
        List<LineageEndpoint> outputs,
        List<String> canonicalTargetSet,
        long chunkIndex,
        long chunkCount,
        String correlationId,
        LineageFact.LegacyV1Projection legacyV1Projection,
        String payloadDigest,
        LineageExecutionAttribution attribution,
        Map<String, String> processFacts,
        Map<String, String> journalFacts
) {

    /** The original schema version: no attribution, no fact compartments. */
    public static final long SCHEMA_VERSION = 1L;

    /** The P1-1(e) schema version: appends the digest-covered extras (Codex C2). */
    public static final long SCHEMA_VERSION_V2 = 2L;

    /** The pre-(e) shape. */
    public LineageSpoolPayloadV1(long spoolSchemaVersion, String spoolRecordId,
            String repositoryId, LineageProcessType processType, String operationId,
            String occurredAt, List<LineageEndpoint> inputs, List<LineageEndpoint> outputs,
            List<String> canonicalTargetSet, long chunkIndex, long chunkCount,
            String correlationId, LineageFact.LegacyV1Projection legacyV1Projection,
            String payloadDigest) {
        this(spoolSchemaVersion, spoolRecordId, repositoryId, processType, operationId,
                occurredAt, inputs, outputs, canonicalTargetSet, chunkIndex, chunkCount,
                correlationId, legacyV1Projection, payloadDigest, null, Map.of(), Map.of());
    }

    public LineageSpoolPayloadV1 {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        canonicalTargetSet = canonicalTargetSet == null ? List.of()
                : List.copyOf(canonicalTargetSet);
        processFacts = processFacts == null ? Map.of() : Map.copyOf(processFacts);
        journalFacts = journalFacts == null ? Map.of() : Map.copyOf(journalFacts);
        if (spoolSchemaVersion == SCHEMA_VERSION
                && (attribution != null || !processFacts.isEmpty() || !journalFacts.isEmpty())) {
            throw new IllegalArgumentException("a version-1 spool payload cannot carry the"
                    + " P1-1(e) extras — its digest does not cover them");
        }
    }

    /**
     * The producer-level conversion: one unchunked fact, identity and digest computed here.
     *
     * <p>The schema version follows the FACT: a fact carrying the P1-1(e) extras spools as
     * version 2 (digest covers them), a bare fact stays byte-identical to the pre-(e) format —
     * which is what keeps old readers untouched by facts that never carried the extras, and
     * what lets the materializer choose the event's digest version by the payload's (§2.0).
     */
    public static LineageSpoolPayloadV1 of(LineageFact fact) {
        if (fact == null) {
            throw new IllegalArgumentException("fact must not be null");
        }
        String spoolRecordId = LineageSpoolIdentity.spoolRecordId(
                fact.repositoryId(), fact.processType(), fact.operationId(),
                fact.inputs(), fact.outputs(), fact.targets(), 0L, 1L, fact.occurredAt());
        boolean hasExtras = fact.attribution() != null || !fact.processFacts().isEmpty()
                || !fact.journalFacts().isEmpty();
        if (!hasExtras) {
            String payloadDigest = LineageSpoolIdentity.payloadDigest(
                    spoolRecordId, SCHEMA_VERSION, fact.inputs(), fact.outputs(),
                    fact.correlationId(), fact.legacyProjection());
            return new LineageSpoolPayloadV1(
                    SCHEMA_VERSION, spoolRecordId, fact.repositoryId(), fact.processType(),
                    fact.operationId(), fact.occurredAt(), fact.inputs(), fact.outputs(),
                    LineageCanonicalHash.canonicalTargetSet(fact.targets()),
                    0L, 1L, fact.correlationId(), fact.legacyProjection(), payloadDigest);
        }
        String payloadDigest = LineageSpoolIdentity.payloadDigestV2(
                spoolRecordId, SCHEMA_VERSION_V2, fact.inputs(), fact.outputs(),
                fact.correlationId(), fact.legacyProjection(),
                fact.attribution(), fact.journalFacts(), fact.processFacts());
        return new LineageSpoolPayloadV1(
                SCHEMA_VERSION_V2, spoolRecordId, fact.repositoryId(), fact.processType(),
                fact.operationId(), fact.occurredAt(), fact.inputs(), fact.outputs(),
                LineageCanonicalHash.canonicalTargetSet(fact.targets()),
                0L, 1L, fact.correlationId(), fact.legacyProjection(), payloadDigest,
                fact.attribution(), fact.processFacts(), fact.journalFacts());
    }

    /**
     * Recomputes both hashes from the payload's content and compares them to the stored
     * values. False means the record is corrupt, tampered with, or was produced by a broken
     * identity rule — quarantine grade either way, and no decision document may ever be
     * created from it (§6-a test 13c).
     */
    public boolean selfVerifies() {
        try {
            // A verified record is a valid spool fact, not merely a self-consistent hash pair:
            // the schema this type writes, producer-level chunk coordinates, a parseable
            // timestamp, targets already in canonical form, and endpoints that pass the same
            // scope/shape rules a LineageFact must — a record that could never have come from
            // the producer conversion must not materialise just because its hashes agree.
            if (spoolSchemaVersion != SCHEMA_VERSION
                    && spoolSchemaVersion != SCHEMA_VERSION_V2) {
                return false;
            }
            if (chunkIndex != 0L || chunkCount != 1L) {
                return false;
            }
            if (correlationId != null && correlationId.isBlank()) {
                // The producer types normalise blank to null and the codec rejects it — a
                // blank here is appendable-but-undecodable, which is exactly what
                // verification exists to prevent.
                return false;
            }
            java.time.Instant.parse(occurredAt);
            if (!canonicalTargetSet.equals(
                    LineageCanonicalHash.canonicalTargetSet(canonicalTargetSet))) {
                return false;
            }
            LineageRepositoryScope.validate(repositoryId, inputs, outputs);
            LineageRepositoryScope.validateArtifactOperation(operationId, inputs, outputs);
            LineageProcessShape.validate(processType, inputs, outputs);
            String expectedId = LineageSpoolIdentity.spoolRecordId(
                    repositoryId, processType, operationId, inputs, outputs,
                    canonicalTargetSet, chunkIndex, chunkCount, occurredAt);
            String expectedDigest = spoolSchemaVersion == SCHEMA_VERSION
                    ? LineageSpoolIdentity.payloadDigest(
                            expectedId, spoolSchemaVersion, inputs, outputs,
                            correlationId, legacyV1Projection)
                    : LineageSpoolIdentity.payloadDigestV2(
                            expectedId, spoolSchemaVersion, inputs, outputs,
                            correlationId, legacyV1Projection,
                            attribution, journalFacts, processFacts);
            return expectedId.equals(spoolRecordId) && expectedDigest.equals(payloadDigest);
        } catch (RuntimeException e) {
            // A payload any of the rules reject (duplicate endpoints, blank fields, cross-repo
            // endpoints, malformed timestamp) cannot verify; it is exactly as quarantine-grade
            // as a digest mismatch.
            return false;
        }
    }
}
