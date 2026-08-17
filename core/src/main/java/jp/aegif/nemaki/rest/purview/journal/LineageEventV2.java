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
 * A lineage journal record in the design's v2 shape.
 *
 * <h2>Why this is a new type rather than a change to {@link LineageEvent}</h2>
 *
 * <p>{@code LineageEvent} appears as a type in the store contract
 * ({@link LineageJournalStore}) and the sink contract ({@link LineageTargetSink}), so changing its
 * shape changes both, and with them the projector, the controller, the dead-letter store and all
 * three sinks — in one commit. The rollout in the design's §6-a needs the opposite: a period in
 * which v2 exists and nothing writes it, so the reading side can be moved to a version-neutral
 * form while v1 is still the only thing on disk.
 *
 * <p>So both types exist. Nothing in production constructs this one until A-2 Slice 4.
 *
 * <h2>Identity is recomputed here, never accepted</h2>
 *
 * <p>{@code processKey} and {@code deliveryId} are components of the record, which invites a
 * caller to supply them. They are recomputed from the other fields and compared, and a mismatch is
 * rejected. Accepting them would make "an event whose id does not describe its contents" a value
 * that constructs cleanly and only fails later — at the journal, as a 409 with a digest mismatch,
 * far from the code that built it.
 *
 * <p>The formulas are {@link LineageIdentity}'s and are frozen by golden vectors; nothing here
 * reimplements them. Same for the endpoint-set rules ({@link LineageCanonicalHash}), the
 * repository and qualified-name checks ({@link LineageRepositoryScope}) and the attribute
 * allowlist ({@link EndpointKind}).
 *
 * <h2>What is not here</h2>
 *
 * <p>v1's {@code eventKey}, {@code runId}, {@code version} and event-level
 * {@code snapshotAttributes} have no v2 equivalent in this record: {@code processKey} replaces the
 * first, {@code operationId} the second, the delivery union the third, and §2's endpoint-local
 * attributes the fourth. They are not dropped from the system — the v1 record keeps carrying them
 * and the read model in Slice 1b exposes both forms — but they are not fields of a v2 event.
 */
public record LineageEventV2(
        int schemaVersion,
        int idempotencyKeyVersion,
        String eventId,
        String processKey,
        LineageDelivery delivery,
        String deliveryId,
        String repositoryId,
        LineageProcessType processType,
        String operationId,
        String occurredAt,
        List<LineageEndpoint> inputs,
        List<LineageEndpoint> outputs,
        int chunkIndex,
        int chunkCount,
        long sequenceNumber,
        String correlationId,
        String spoolRecordId,
        String legacyEventKey,
        Map<String, LineagePublishStatus> publishStatusByTarget,
        String creationPayloadDigest
) {

    /** The envelope version this record is. */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public LineageEventV2 {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("LineageEventV2 requires schemaVersion="
                    + CURRENT_SCHEMA_VERSION + ", got " + schemaVersion);
        }
        if (idempotencyKeyVersion != LineageIdentity.IDEMPOTENCY_KEY_VERSION) {
            // Persisted alongside the key so that a future change to the processKey formula can be
            // recognised in stored records rather than inferred. A record claiming a version this
            // binary does not implement is not one this binary may validate.
            throw new IllegalArgumentException("idempotencyKeyVersion="
                    + LineageIdentity.IDEMPOTENCY_KEY_VERSION + " is the only version this binary"
                    + " computes, got " + idempotencyKeyVersion);
        }
        if (delivery == null) {
            throw new IllegalArgumentException("delivery must not be null — the reason a journal"
                    + " record exists is part of its identity");
        }
        requireText(eventId, "eventId");
        requireText(repositoryId, "repositoryId");
        requireText(occurredAt, "occurredAt");
        if (processType == null) {
            throw new IllegalArgumentException("processType must not be null");
        }
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must not be negative, got "
                    + sequenceNumber);
        }
        // spoolRecordId, legacyEventKey and correlationId are audit-only and may be absent; blank
        // is normalised to null so that "absent" has one representation.
        spoolRecordId = blankToNull(spoolRecordId);
        legacyEventKey = blankToNull(legacyEventKey);
        correlationId = blankToNull(correlationId);

        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        publishStatusByTarget = publishStatusByTarget == null ? Map.of()
                : Map.copyOf(publishStatusByTarget);

        // A-1's checks, called rather than copied. Order matters only in that the repository and
        // qualified-name check gives the clearest message, so it runs first.
        LineageRepositoryScope.validate(repositoryId, inputs, outputs);
        LineageRepositoryScope.validateArtifactOperation(operationId, inputs, outputs);
        LineageProcessShape.validate(processType, inputs, outputs);

        String expectedProcessKey = LineageIdentity.processKey(repositoryId, processType,
                operationId, inputs, outputs, schemaVersion, chunkIndex, chunkCount);
        if (!expectedProcessKey.equals(processKey)) {
            throw new IllegalArgumentException("processKey does not describe this event's contents"
                    + " — expected " + expectedProcessKey + ", got " + processKey);
        }
        String expectedDeliveryId = delivery.deliveryId(expectedProcessKey);
        if (!expectedDeliveryId.equals(deliveryId)) {
            throw new IllegalArgumentException("deliveryId does not describe this event's "
                    + delivery.kind() + " delivery — expected " + expectedDeliveryId + ", got "
                    + deliveryId);
        }

        String expectedDigest = LineageEventDigest.creationPayloadDigest(expectedProcessKey,
                expectedDeliveryId, schemaVersion, repositoryId, processType, operationId,
                occurredAt, inputs, outputs, chunkIndex, chunkCount);
        if (!expectedDigest.equals(creationPayloadDigest)) {
            throw new IllegalArgumentException("creationPayloadDigest does not match the immutable"
                    + " payload — expected " + expectedDigest + ", got " + creationPayloadDigest);
        }
    }

    /** True when this record was materialised from a version-free spool fact (§6-a). */
    public boolean isMaterialisedFromSpool() {
        return spoolRecordId != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be null or blank");
        }
    }
}
