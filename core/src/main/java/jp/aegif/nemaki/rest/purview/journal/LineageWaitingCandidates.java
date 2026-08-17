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
    @FunctionalInterface
    interface OriginLookup {
        /**
         * The origin v2 row, or null when it is genuinely not there.
         *
         * @throws RuntimeException when it could not be read — the caller must turn that into
         *         INDETERMINATE, never into "no origin"
         */
        LineageJournalRowV2 read(String deliveryId);
    }

    static LineageWaitingSnapshotResolver.Candidate from(LineageJournalRowV2 row, String target,
            String taskKey, OriginLookup origins) {
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
                provenanceOf(event, origins, snapshot), snapshot);
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
    private static LineageObservationProvenance provenanceOf(LineageEventV2 event,
            OriginLookup origins, LineageWaitingSnapshot snapshot) {
        LineageDelivery delivery = event.delivery();
        if (delivery instanceof LineageDelivery.Replay replay) {
            return replayProvenance(event, replay, origins, snapshot);
        }
        if (delivery instanceof LineageDelivery.Repair) {
            // No lossless provenance source exists for a repair. The dead-letter store holds v1
            // LineageEvent — a format its own comments record as unable to reconstruct v2
            // identity — and its reader returns null for absence and for failure alike, so it
            // cannot even distinguish "not there" from "could not read". Nothing else records
            // a repair's original observation.
            //
            // Reconstructing from it would mean guessing the observation order that decides
            // whether a purge or a restore wins. Left unestablished on purpose: the provenance
            // is returned with no origin evidence, usable() refuses it, and the resolver
            // reports INDETERMINATE. It is also unreachable today — LineageDelivery.Repair is
            // constructed only by the decoder, never by a producer — so this is a closed door
            // rather than a broken path.
            return new LineageObservationProvenance(
                    LineageObservationProvenance.LineageDeliveryKind.REPAIR, event.deliveryId(),
                    null, event.sequenceNumber(), 0L, event.occurredAt(), null);
        }
        // An original delivery is its own observation.
        return new LineageObservationProvenance(
                LineageObservationProvenance.LineageDeliveryKind.ORIGINAL, event.deliveryId(),
                event.deliveryId(), event.sequenceNumber(), event.sequenceNumber(),
                event.occurredAt(), null);
    }

    /**
     * A replay's observation, taken from the delivery it replays.
     *
     * <h2>Why every field comes from the origin</h2>
     *
     * <p>A replay re-delivers an observation someone already made. Its own sequence is when it
     * was re-sent, not when the source was seen — using it would let re-delivering a week-old
     * purge outrank a restore that happened yesterday. So the observation sequence and the
     * evidence digest are read from the origin row, and there is deliberately no fallback: a
     * replay whose origin cannot be established is not a weaker observation, it is no
     * observation at all.
     */
    private static LineageObservationProvenance replayProvenance(LineageEventV2 event,
            LineageDelivery.Replay replay, OriginLookup origins,
            LineageWaitingSnapshot snapshot) {
        String originId = replay.originalDeliveryId();
        if (originId == null || originId.isBlank()) {
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.BROKEN_ORIGIN_CHAIN);
        }
        if (originId.equals(event.deliveryId())) {
            // A replay of itself has no observation behind it, and following it would loop.
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.BROKEN_ORIGIN_CHAIN);
        }
        if (origins == null) {
            // Nothing to ask. Unestablished rather than corrupt: the caller simply did not
            // supply a way to read the journal.
            return unusableReplay(event, originId);
        }

        LineageJournalRowV2 originRow;
        try {
            originRow = origins.read(originId);
        } catch (RuntimeException unreadable) {
            // A read that failed says nothing about the origin. INDETERMINATE, via an
            // unusable provenance — never CORRUPT, which would blame the data for an outage.
            return unusableReplay(event, originId);
        }
        if (originRow == null || originRow.event() == null) {
            // Genuinely absent — retention, or not replicated yet. Also unestablished.
            return unusableReplay(event, originId);
        }
        LineageEventV2 origin = originRow.event();

        if (!originId.equals(origin.deliveryId())) {
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.BROKEN_ORIGIN_CHAIN);
        }
        if (!event.repositoryId().equals(origin.repositoryId())) {
            // An observation from another repository cannot order this one, and an entity
            // rebuilt across repositories is wrong in a way nothing downstream detects.
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.BROKEN_ORIGIN_CHAIN);
        }
        if (!(origin.delivery() instanceof LineageDelivery.Original)) {
            // The chain is bounded at one hop by contract: a replay's origin must be a
            // first-hand observation. Following replays of replays would need a depth limit
            // and a cycle check on a structure nothing guarantees is acyclic.
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.BROKEN_ORIGIN_CHAIN);
        }
        if (origin.sequenceNumber() == 0L) {
            // UNSEQUENCED: the origin has no place in the order yet, so it cannot supply one.
            return unusableReplay(event, originId);
        }

        // The v2 event schema carries no origin evidence digest field — neither the event nor
        // LineageDelivery.Replay(originalDeliveryId, target, generation) has one — so there is
        // no self-reported claim to check.
        //
        // What is checked instead is SEMANTIC EQUIVALENCE: the digest recomputed from the
        // ORIGIN row is compared against the one recomputed from the replay's own payload.
        // Equal means the replay really re-delivers that observation; different means it
        // carries content the origin never observed.
        //
        // This is NOT stronger than an independently stored origin digest, and must not be
        // described as such. Both sides are read from the journal, so a writer able to modify
        // the origin row and the replay row together can make them agree on content neither
        // originally had. A digest recorded at replay-creation time and never rewritten would
        // resist that; this does not. It is the strongest check the current schema permits,
        // and closing the gap means recording the origin digest at creation — see the REPAIR
        // note for the same requirement.
        String originDigest = originEvidenceDigest(origin, snapshot);
        if (originDigest == null) {
            // The origin does not carry this endpoint. A replay may legitimately cover a
            // different subset, so this is unestablished rather than corrupt.
            return unusableReplay(event, originId);
        }
        if (!originDigest.matches("[0-9a-f]{64}")) {
            return unusableReplay(event, originId);
        }
        if (!constantTimeEquals(snapshot.evidenceDigest(), originDigest)) {
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.ORIGIN_EVIDENCE_MISMATCH);
        }
        return new LineageObservationProvenance(
                LineageObservationProvenance.LineageDeliveryKind.REPLAY, event.deliveryId(),
                originId, event.sequenceNumber(), origin.sequenceNumber(), event.occurredAt(),
                originDigest);
    }

    /** A replay whose origin could not be established. {@code usable()} refuses it. */
    private static LineageObservationProvenance unusableReplay(LineageEventV2 event,
            String originId) {
        return new LineageObservationProvenance(
                LineageObservationProvenance.LineageDeliveryKind.REPLAY, event.deliveryId(),
                originId, event.sequenceNumber(), 0L, event.occurredAt(), null);
    }

    /**
     * The origin's snapshot evidence for the same endpoint, rebuilt from the origin's own row.
     *
     * @return null when the origin does not carry that endpoint — unestablished, not corrupt:
     *         a replay may legitimately be about a different subset
     */
    private static String originEvidenceDigest(LineageEventV2 origin,
            LineageWaitingSnapshot snapshot) {
        java.util.List<LineageEndpoint> all = new java.util.ArrayList<>();
        if (origin.inputs() != null) {
            all.addAll(origin.inputs());
        }
        if (origin.outputs() != null) {
            all.addAll(origin.outputs());
        }
        for (LineageEndpoint endpoint : all) {
            if (endpoint.kind() != snapshot.endpointKind()
                    || !endpoint.catalogQualifiedName().equals(
                            snapshot.catalogQualifiedName())) {
                continue;
            }
            try {
                return LineageWaitingSnapshot.of(snapshot.target(), origin.repositoryId(),
                        endpoint.kind(), endpoint.catalogQualifiedName(),
                        attributesOf(endpoint), LineageSourceDisposition.SOURCE_UNKNOWN,
                        snapshot.snapshotSchemaVersion()).evidenceDigest();
            } catch (RuntimeException unusable) {
                return null;
            }
        }
        return null;
    }

    /** Constant time: neither side is secret, but a digest comparison should not leak either. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
