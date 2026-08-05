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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns one decoded v2 row into the candidate a resolver can reason about.
 *
 * <p>Separate from the CouchDB reader so the mapping can be tested without a database, and so
 * the reader stays about querying. Everything here comes from the single row it is given —
 * pairing a snapshot with another delivery's observation sequence would let a stale purge
 * outrank a later restore.
 */
final class LineageWaitingCandidates {

    private LineageWaitingCandidates() {
    }

    /**
     * The candidate for one target's wait on one task.
     *
     * @return null when the row carries no endpoint matching the task — the row belongs to the
     *         target but not to this task, which is a filter rather than a fault
     * @throws IllegalStateException when the row belongs and cannot be turned into a usable
     *         candidate; the resolver reports that as CORRUPT rather than as a smaller set
     */
    static LineageWaitingSnapshotResolver.Candidate from(LineageJournalRowV2 row, String target,
            String taskKey) {
        LineageEventV2 event = row == null ? null : row.event();
        if (event == null) {
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.INCONSISTENT_WAITING_METADATA);
        }
        LineageEndpoint matched = endpointForTask(event, target, taskKey);
        if (matched == null) {
            return null;
        }

        LineageWaitingSnapshot snapshot;
        try {
            snapshot = LineageWaitingSnapshot.of(target, event.repositoryId(), matched.kind(),
                    matched.catalogQualifiedName(), attributesOf(matched),
                    // What the event observed, not what the source is now: the disposition is
                    // established separately by the authoritative resolver. UNKNOWN is the only
                    // honest value here, and it is what stops a snapshot alone authorising a
                    // tombstone.
                    LineageSourceDisposition.SOURCE_UNKNOWN,
                    LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION);
        } catch (RuntimeException rejected) {
            // The snapshot's own constructor refuses anything it cannot vouch for — a
            // non-scalar attribute, a secret-bearing value, a digest that does not verify.
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.SNAPSHOT_SELF_VERIFICATION_FAILED);
        }

        return new LineageWaitingSnapshotResolver.Candidate(
                new LineageJournalOrder(event.repositoryId(), event.sequenceNumber(),
                        event.deliveryId()),
                provenanceOf(event), snapshot);
    }

    /**
     * The endpoint this task names, from the event's own inputs and outputs.
     *
     * <p>Matched by recomputing the task key rather than by comparing qualified names: the key
     * is what the obligation is filed under, and recomputing it is the only comparison that
     * cannot be satisfied by a near miss.
     */
    private static LineageEndpoint endpointForTask(LineageEventV2 event, String target,
            String taskKey) {
        java.util.List<LineageEndpoint> all = new java.util.ArrayList<>();
        if (event.inputs() != null) {
            all.addAll(event.inputs());
        }
        if (event.outputs() != null) {
            all.addAll(event.outputs());
        }
        for (LineageEndpoint endpoint : all) {
            String key = LineageCatalogObligation.taskKey(target, event.repositoryId(),
                    endpoint.kind(), endpoint.catalogQualifiedName());
            if (taskKey.equals(key)) {
                return endpoint;
            }
        }
        return null;
    }

    /** The endpoint's attributes, as the snapshot wants them. */
    private static Map<String, Object> attributesOf(LineageEndpoint endpoint) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (endpoint.attributes() != null) {
            attributes.putAll(endpoint.attributes());
        }
        return attributes;
    }

    /**
     * When this delivery observed the source.
     *
     * <p>A replay inherits its origin's observation sequence and evidence digest. Using the
     * delivery's own sequence would make re-delivering an old observation look like a new one,
     * which is how a stale purge outranks a later restore.
     */
    private static LineageObservationProvenance provenanceOf(LineageEventV2 event) {
        LineageDelivery delivery = event.delivery();
        if (delivery instanceof LineageDelivery.Replay replay) {
            // The row names its origin delivery, but not that origin's observation sequence or
            // evidence digest — those live on the origin's own row. Left null deliberately:
            // LineageObservationProvenance.usable() then refuses this candidate, which is the
            // safe answer. A replay whose origin evidence nobody can name must not authorise a
            // tombstone, and inventing the delivery's own sequence in its place is exactly how
            // a re-delivered old observation outranks a later restore.
            return new LineageObservationProvenance(
                    LineageObservationProvenance.LineageDeliveryKind.REPLAY, event.deliveryId(),
                    replay.originalDeliveryId(), event.sequenceNumber(), 0L,
                    event.occurredAt(), null);
        }
        if (delivery instanceof LineageDelivery.Repair repair) {
            // Same reasoning: a repair points at the dead letter it came from, not at the
            // original observation's evidence.
            return new LineageObservationProvenance(
                    LineageObservationProvenance.LineageDeliveryKind.REPAIR, event.deliveryId(),
                    repair.deadLetterId(), event.sequenceNumber(), 0L, event.occurredAt(), null);
        }
        // An original delivery is its own observation.
        return new LineageObservationProvenance(
                LineageObservationProvenance.LineageDeliveryKind.ORIGINAL, event.deliveryId(),
                event.deliveryId(), event.sequenceNumber(), event.sequenceNumber(),
                event.occurredAt(), null);
    }
}
