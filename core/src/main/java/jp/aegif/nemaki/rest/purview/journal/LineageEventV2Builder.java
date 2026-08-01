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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link LineageEventV2}, computing the identities the record then verifies.
 *
 * <h2>{@link #build()} reads no clock and generates no id</h2>
 *
 * <p>This is the difference from {@link LineageEventBuilder}, which calls {@code Instant.now()}
 * inside {@code build()}. That was harmless while the journal {@code _id} was a UUID; in v2 it is
 * not. {@code occurredAt} is part of {@code creationPayloadDigest} but not of {@code processKey},
 * so a retry that rebuilds the event instead of carrying the original object gets the <em>same</em>
 * {@code deliveryId} with a <em>different</em> digest — which the journal reports as an id
 * collision, and which the design's §6-a spool identity turns into a duplicate materialisation.
 *
 * <p>So {@code occurredAt} and {@code eventId} are required inputs, allocated once by the emitter
 * when the business fact happens and before anything that can fail. {@code build()} is then a pure
 * function of its inputs: calling it twice yields two equal events.
 *
 * <h2>Targets are derived from the delivery, not supplied alongside it</h2>
 *
 * <p>An {@code ORIGINAL} delivers to the set of targets it names; a {@code REPLAY} delivers to
 * exactly the one target it names. Deriving rather than accepting is what stops a replay from
 * being built with every target {@code PENDING}, which would re-deliver the targets that already
 * succeeded — the failure §8-d's per-target compensation exists to avoid.
 *
 * <p>{@code REPAIR} is the exception: a dead letter's targets are a fact about the dead letter,
 * not about the repair, so they are supplied with {@link #repairTargets}.
 */
public final class LineageEventV2Builder {

    private String eventId;
    private String occurredAt;
    private String repositoryId;
    private LineageProcessType processType;
    private String operationId;
    private LineageDelivery delivery;
    private final List<LineageEndpoint> inputs = new ArrayList<>();
    private final List<LineageEndpoint> outputs = new ArrayList<>();
    private int chunkIndex = 0;
    private int chunkCount = 1;
    private long sequenceNumber = 0L;
    private String correlationId;
    private String spoolRecordId;
    private String legacyEventKey;
    private List<String> repairTargets;

    /** The audit-only identifier. Required, so that {@code build()} stays a pure function. */
    public LineageEventV2Builder eventId(String eventId) {
        this.eventId = eventId;
        return this;
    }

    /** When the business fact happened. Allocated once by the emitter; never re-derived. */
    public LineageEventV2Builder occurredAt(String occurredAt) {
        this.occurredAt = occurredAt;
        return this;
    }

    public LineageEventV2Builder repositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
        return this;
    }

    public LineageEventV2Builder processType(LineageProcessType processType) {
        this.processType = processType;
        return this;
    }

    /** The server-issued id of the business operation. Mandatory on every v2 event (§3). */
    public LineageEventV2Builder operationId(String operationId) {
        this.operationId = operationId;
        return this;
    }

    public LineageEventV2Builder delivery(LineageDelivery delivery) {
        this.delivery = delivery;
        return this;
    }

    public LineageEventV2Builder addInput(LineageEndpoint endpoint) {
        inputs.add(endpoint);
        return this;
    }

    public LineageEventV2Builder addOutput(LineageEndpoint endpoint) {
        outputs.add(endpoint);
        return this;
    }

    /** Which chunk of a split event this is, and how many there are (§2). */
    public LineageEventV2Builder chunk(int chunkIndex, int chunkCount) {
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        return this;
    }

    /** Left at 0 by the producer; the fenced sequencer assigns the real value (§8-a). */
    public LineageEventV2Builder sequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        return this;
    }

    public LineageEventV2Builder correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    /** Set only when this event is materialised from a version-free spool fact (§6-a). */
    public LineageEventV2Builder spoolRecordId(String spoolRecordId) {
        this.spoolRecordId = spoolRecordId;
        return this;
    }

    /** The v1 {@code eventKey} of a record this was mapped from. Audit only; never an identity. */
    public LineageEventV2Builder legacyEventKey(String legacyEventKey) {
        this.legacyEventKey = legacyEventKey;
        return this;
    }

    /** The targets of the dead letter being repaired. Only meaningful for a REPAIR delivery. */
    public LineageEventV2Builder repairTargets(List<String> targets) {
        this.repairTargets = targets == null ? null : List.copyOf(targets);
        return this;
    }

    /**
     * @throws IllegalArgumentException if anything required is missing, or if the endpoints do not
     *                                  form a shape this process type accepts
     */
    public LineageEventV2 build() {
        if (delivery == null) {
            throw new IllegalArgumentException("delivery is required: an event has to say why it"
                    + " exists (ORIGINAL / REPLAY / REPAIR)");
        }
        if (occurredAt == null || occurredAt.isBlank()) {
            throw new IllegalArgumentException("occurredAt is required and must be allocated once"
                    + " by the emitter — build() does not read the clock, because re-deriving it"
                    + " changes the event's digest without changing its deliveryId");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required — generating one here would"
                    + " make build() impure, and a retry would produce a different record");
        }

        List<LineageEndpoint> in = List.copyOf(inputs);
        List<LineageEndpoint> out = List.copyOf(outputs);

        String processKey = LineageIdentity.processKey(repositoryId, processType, operationId,
                in, out, LineageEventV2.CURRENT_SCHEMA_VERSION, chunkIndex, chunkCount);
        String deliveryId = delivery.deliveryId(processKey);
        String digest = LineageEventDigest.creationPayloadDigest(processKey, deliveryId,
                LineageEventV2.CURRENT_SCHEMA_VERSION, repositoryId, processType, operationId,
                occurredAt, in, out, chunkIndex, chunkCount);

        return new LineageEventV2(
                LineageEventV2.CURRENT_SCHEMA_VERSION,
                LineageIdentity.IDEMPOTENCY_KEY_VERSION,
                eventId,
                processKey,
                delivery,
                deliveryId,
                repositoryId,
                processType,
                operationId,
                occurredAt,
                in,
                out,
                chunkIndex,
                chunkCount,
                sequenceNumber,
                correlationId,
                spoolRecordId,
                legacyEventKey,
                pendingTargets(),
                digest);
    }

    /** Every target this delivery owes, all {@code PENDING}. */
    private Map<String, LineagePublishStatus> pendingTargets() {
        List<String> targets = switch (delivery) {
            case LineageDelivery.Original original -> original.targets();
            case LineageDelivery.Replay replay -> List.of(replay.target());
            case LineageDelivery.Repair ignored -> {
                if (repairTargets == null || repairTargets.isEmpty()) {
                    throw new IllegalArgumentException("a REPAIR delivery needs repairTargets:"
                            + " which targets the dead letter owed is a fact about the dead letter,"
                            + " not something the repair can derive");
                }
                yield repairTargets;
            }
        };
        // Canonicalised by A-1 (trim, non-blank, dedupe, unsigned UTF-8 byte order) so that the
        // status map's key set matches the target set the deliveryId was computed from.
        Map<String, LineagePublishStatus> pending = new LinkedHashMap<>();
        for (String target : LineageCanonicalHash.canonicalTargetSet(targets)) {
            pending.put(target, LineagePublishStatus.PENDING);
        }
        return pending;
    }
}
