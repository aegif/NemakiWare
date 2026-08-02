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

import java.util.Map;

/**
 * Decodes a raw v2 CouchDB document into the typed mutable envelope
 * {@link LineageJournalRowV2}, strictly.
 *
 * <p>The immutable half goes through {@link CouchLineageEventV2#fromMap} — the canonical
 * constructor path, which re-verifies identity and digest — and the mutable half
 * ({@code state}, {@code sequencerGeneration}, {@code sequencerLeaseToken}) is typed here,
 * with the state-dependent requirements enforced by the envelope record itself. A document
 * whose mutable fields contradict its state is malformed, loudly; it never becomes a value
 * the sequencer could act on.
 *
 * <p>Mutations are <b>field-preserving</b>: the store mutates the raw map it read (state and
 * fencing fields only) and writes it back under its {@code _rev} — fields this slice does not
 * know about (v1-side lifecycle maps, future replay metadata) ride along untouched.
 */
public final class CouchLineageJournalRowV2 {

    static final String FIELD_STATE = "state";
    static final String FIELD_GENERATION = "sequencerGeneration";
    static final String FIELD_LEASE_TOKEN = "sequencerLeaseToken";

    // §8-b v2 per-target lifecycle (D-rest-2). publishStatusByTarget shares v1's field name
    // (views and the codec already read it on v2 rows); everything claim-related lives in
    // v2-only nested fields so no v1 surface can misread a shared field, and all timestamps
    // are epoch millis — numeric view keys, no ISO fraction-width ordering defect.
    static final String FIELD_STATUS_BY_TARGET = "publishStatusByTarget";
    static final String FIELD_REPLAY_BY_TARGET = "v2ReplayRequestsByTarget";
    static final String REPLAY_STATE = "state";
    static final String REPLAY_GENERATION = "generation";
    static final String REPLAY_REQUEST_ID = "requestId";
    static final String REPLAY_REQUESTED_AT = "requestedAtMs";
    static final String REPLAY_UPDATED_AT = "updatedAtMs";
    static final String REPLAY_REASON = "reason";
    static final String FIELD_CLAIM_BY_TARGET = "v2ClaimByTarget";
    static final String FIELD_REASON_BY_TARGET = "v2TerminalReasonByTarget";
    static final String CLAIM_TOKEN = "token";
    static final String CLAIM_CLAIMED_AT = "claimedAtMs";
    static final String CLAIM_LEASE_EXPIRES = "leaseExpiresAtMs";
    static final String CLAIM_VERIFYING_SINCE = "verifyingSinceMs";
    static final String CLAIM_RETRY_COUNT = "retryCount";
    static final String REASON_REASON = "reason";
    static final String REASON_DETAIL = "detail";
    static final String REASON_AT = "atMs";

    private CouchLineageJournalRowV2() {
    }

    /**
     * @throws IllegalArgumentException when the document is not a well-formed v2 row with a
     *                                  consistent mutable envelope
     */
    public static LineageJournalRowV2 fromRaw(Map<String, Object> doc) {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        LineageEventV2 event = CouchLineageEventV2.fromMap(doc);
        String rev = doc.get("_rev") instanceof String r ? r : null;

        Object stateValue = doc.get(FIELD_STATE);
        if (!(stateValue instanceof String stateName) || stateName.isBlank()) {
            throw new IllegalArgumentException("v2 row '" + doc.get("_id") + "' has no state —"
                    + " appendV2 writes UNSEQUENCED, so absence is corruption, not legacy");
        }
        LineageJournalRowV2.SequencingState state;
        try {
            state = LineageJournalRowV2.SequencingState.valueOf(stateName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("v2 row '" + doc.get("_id")
                    + "' has unknown state '" + stateName + "' — this build cannot act on it");
        }

        Long generation = null;
        Object generationValue = doc.get(FIELD_GENERATION);
        if (generationValue != null) {
            if (!(generationValue instanceof Number n)) {
                throw new IllegalArgumentException("sequencerGeneration must be a number");
            }
            try {
                generation = new java.math.BigDecimal(n.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException("sequencerGeneration must be an exact"
                        + " integral value, got " + n);
            }
        }
        String token = null;
        Object tokenValue = doc.get(FIELD_LEASE_TOKEN);
        if (tokenValue != null) {
            if (!(tokenValue instanceof String t) || t.isBlank()) {
                throw new IllegalArgumentException("sequencerLeaseToken must be a non-blank"
                        + " string when present");
            }
            token = t;
        }

        return new LineageJournalRowV2(event, rev, state, generation, token,
                decodeLifecycles(doc), decodeReplayRequests(doc));
    }

    private static Map<String, LineageReplayRequest> decodeReplayRequests(
            Map<String, Object> doc) {
        Map<String, Object> requests = requireMapOrNull(doc.get(FIELD_REPLAY_BY_TARGET),
                FIELD_REPLAY_BY_TARGET);
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }
        Map<String, LineageReplayRequest> out = new java.util.LinkedHashMap<>();
        for (var e : requests.entrySet()) {
            String target = e.getKey();
            Map<String, Object> r = requireMapOrNull(e.getValue(),
                    FIELD_REPLAY_BY_TARGET + "." + target);
            if (r == null) {
                throw new IllegalArgumentException("replay request for target '" + target
                        + "' must be a map");
            }
            Object stateValue = r.get(REPLAY_STATE);
            if (!(stateValue instanceof String stateName) || stateName.isBlank()) {
                throw new IllegalArgumentException("replay request for target '" + target
                        + "' has no state");
            }
            LineageReplayRequest.State state;
            try {
                state = LineageReplayRequest.State.valueOf(stateName);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unknown replay request state '" + stateName
                        + "' for target '" + target + "' — this build cannot act on it");
            }
            Object requestId = r.get(REPLAY_REQUEST_ID);
            Long generation = exactLongOrNull(r.get(REPLAY_GENERATION), REPLAY_GENERATION);
            Long requestedAt = exactLongOrNull(r.get(REPLAY_REQUESTED_AT), REPLAY_REQUESTED_AT);
            Long updatedAt = exactLongOrNull(r.get(REPLAY_UPDATED_AT), REPLAY_UPDATED_AT);
            if (generation == null || requestedAt == null || updatedAt == null
                    || !(requestId instanceof String rid)) {
                throw new IllegalArgumentException("replay request for target '" + target
                        + "' is missing generation/requestId/timestamps");
            }
            LineageTargetLifecycle.TerminalReason reason = null;
            Map<String, Object> reasonMap = requireMapOrNull(r.get(REPLAY_REASON),
                    REPLAY_REASON);
            if (reasonMap != null) {
                Object rr = reasonMap.get(REASON_REASON);
                Object rd = reasonMap.get(REASON_DETAIL);
                Long at = exactLongOrNull(reasonMap.get(REASON_AT), REASON_AT);
                if (!(rr instanceof String rs) || !(rd instanceof String ds) || at == null) {
                    throw new IllegalArgumentException("replay failure reason for target '"
                            + target + "' is malformed");
                }
                reason = new LineageTargetLifecycle.TerminalReason(rs, ds, at);
            }
            try {
                out.put(target, new LineageReplayRequest(state, generation, rid, requestedAt,
                        updatedAt, reason));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("v2 row '" + doc.get("_id")
                        + "' replay request for '" + target + "': " + ex.getMessage());
            }
        }
        return out;
    }

    /** CAS-creates / supersedes (from ACKED only) the target's replay request as REQUESTED. */
    static void applyReplayRequested(Map<String, Object> raw, String target, long generation,
                                     String requestId, long nowMs) {
        Map<String, Object> requests = mutableChild(raw, FIELD_REPLAY_BY_TARGET);
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put(REPLAY_STATE, LineageReplayRequest.State.REQUESTED.name());
        r.put(REPLAY_GENERATION, generation);
        r.put(REPLAY_REQUEST_ID, requestId);
        r.put(REPLAY_REQUESTED_AT, nowMs);
        r.put(REPLAY_UPDATED_AT, nowMs);
        requests.put(target, r);
    }

    /** Advances the request's state in place; generation/requestId/requestedAt untouched. */
    static void applyReplayState(Map<String, Object> raw, String target,
                                 LineageReplayRequest.State next, long nowMs,
                                 LineageTargetLifecycle.TerminalReason reason) {
        Map<String, Object> requests = mutableChild(raw, FIELD_REPLAY_BY_TARGET);
        Map<String, Object> r = mutableChild(requests, target);
        r.put(REPLAY_STATE, next.name());
        r.put(REPLAY_UPDATED_AT, nowMs);
        if (reason != null) {
            Map<String, Object> reasonMap = new java.util.LinkedHashMap<>();
            reasonMap.put(REASON_REASON, reason.reason());
            reasonMap.put(REASON_DETAIL, reason.detail());
            reasonMap.put(REASON_AT, reason.atMs());
            r.put(REPLAY_REASON, reasonMap);
        }
    }

    private static Map<String, LineageTargetLifecycle> decodeLifecycles(Map<String, Object> doc) {
        Map<String, Object> statuses = requireMapOrNull(doc.get(FIELD_STATUS_BY_TARGET),
                FIELD_STATUS_BY_TARGET);
        Map<String, Object> claims = requireMapOrNull(doc.get(FIELD_CLAIM_BY_TARGET),
                FIELD_CLAIM_BY_TARGET);
        Map<String, Object> reasons = requireMapOrNull(doc.get(FIELD_REASON_BY_TARGET),
                FIELD_REASON_BY_TARGET);
        if (claims != null) {
            for (String t : claims.keySet()) {
                if (statuses == null || !statuses.containsKey(t)) {
                    throw new IllegalArgumentException("v2 row '" + doc.get("_id")
                            + "' has a claim for target '" + t + "' but no status for it");
                }
            }
        }
        if (reasons != null) {
            for (String t : reasons.keySet()) {
                if (statuses == null || !statuses.containsKey(t)) {
                    throw new IllegalArgumentException("v2 row '" + doc.get("_id")
                            + "' has a terminal reason for target '" + t + "' but no status");
                }
            }
        }
        if (statuses == null || statuses.isEmpty()) {
            return Map.of();
        }
        Map<String, LineageTargetLifecycle> out = new java.util.LinkedHashMap<>();
        for (var e : statuses.entrySet()) {
            String target = e.getKey();
            if (!(e.getValue() instanceof String statusName) || statusName.isBlank()) {
                throw new IllegalArgumentException("status for target '" + target
                        + "' must be a non-blank string");
            }
            LineagePublishStatus status;
            try {
                status = LineagePublishStatus.valueOf(statusName);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unknown publish status '" + statusName
                        + "' for target '" + target + "' — this build cannot act on it");
            }
            Map<String, Object> claim = claims == null ? null
                    : requireMapOrNull(claims.get(target), FIELD_CLAIM_BY_TARGET + "." + target);
            Map<String, Object> reason = reasons == null ? null
                    : requireMapOrNull(reasons.get(target), FIELD_REASON_BY_TARGET + "." + target);
            String token = null;
            Long claimedAt = null;
            Long leaseExpires = null;
            Long verifyingSince = null;
            Long retryCount = null;
            if (claim != null) {
                Object t = claim.get(CLAIM_TOKEN);
                if (!(t instanceof String s) || s.isBlank()) {
                    throw new IllegalArgumentException("claim token for target '" + target
                            + "' must be a non-blank string");
                }
                token = s;
                claimedAt = exactLongOrNull(claim.get(CLAIM_CLAIMED_AT), CLAIM_CLAIMED_AT);
                leaseExpires = exactLongOrNull(claim.get(CLAIM_LEASE_EXPIRES), CLAIM_LEASE_EXPIRES);
                verifyingSince = exactLongOrNull(claim.get(CLAIM_VERIFYING_SINCE),
                        CLAIM_VERIFYING_SINCE);
                retryCount = exactLongOrNull(claim.get(CLAIM_RETRY_COUNT), CLAIM_RETRY_COUNT);
            }
            LineageTargetLifecycle.TerminalReason terminalReason = null;
            if (reason != null) {
                Object r = reason.get(REASON_REASON);
                Object d = reason.get(REASON_DETAIL);
                if (!(r instanceof String rs) || !(d instanceof String ds)) {
                    throw new IllegalArgumentException("terminal reason for target '" + target
                            + "' must carry string reason and detail");
                }
                Long at = exactLongOrNull(reason.get(REASON_AT), REASON_AT);
                if (at == null) {
                    throw new IllegalArgumentException("terminal reason for target '" + target
                            + "' must carry atMs");
                }
                terminalReason = new LineageTargetLifecycle.TerminalReason(rs, ds, at);
            }
            try {
                out.put(target, new LineageTargetLifecycle(status, token, claimedAt,
                        leaseExpires, verifyingSince, retryCount, terminalReason));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("v2 row '" + doc.get("_id") + "' target '"
                        + target + "': " + ex.getMessage());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMapOrNull(Object value, String what) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(what + " must be a map when present");
        }
        return (Map<String, Object>) value;
    }

    private static Long exactLongOrNull(Object value, String what) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException(what + " must be a number when present");
        }
        try {
            return new java.math.BigDecimal(n.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be an exact integral value, got " + n);
        }
    }

    /** Applies the claim/reclaim transition to the raw map: SEQUENCING + fencing coordinates. */
    static void applySequencing(Map<String, Object> raw, long generation, String leaseToken) {
        raw.put(FIELD_STATE, LineageJournalRowV2.SequencingState.SEQUENCING.name());
        raw.put(FIELD_GENERATION, generation);
        raw.put(FIELD_LEASE_TOKEN, leaseToken);
    }

    /** Applies finalize: SEQUENCED and the sequence in the same write (one CAS). */
    static void applyFinalize(Map<String, Object> raw, long sequence) {
        raw.put(FIELD_STATE, LineageJournalRowV2.SequencingState.SEQUENCED.name());
        raw.put("sequenceNumber", sequence);
    }

    // ---- §8-b projection lifecycle mutations (D-rest-2), field-preserving on the raw map ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableChild(Map<String, Object> raw, String field) {
        Object existing = raw.get(field);
        Map<String, Object> child = existing instanceof Map
                ? new java.util.LinkedHashMap<>((Map<String, Object>) existing)
                : new java.util.LinkedHashMap<>();
        raw.put(field, child);
        return child;
    }

    /**
     * Applies a projection claim (PENDING/FAILED → PROJECTING): fresh token + lease, and clears
     * the per-attempt verifyingSince marker. retryCount initialized to 0 on first claim,
     * retained otherwise — FAILED→PROJECTING is the machine's one deliberate audit-reset point.
     */
    static void applyProjectionClaim(Map<String, Object> raw, String target, String token,
                                     long nowMs, long leaseExpiresAtMs) {
        mutableChild(raw, FIELD_STATUS_BY_TARGET)
                .put(target, LineagePublishStatus.PROJECTING.name());
        Map<String, Object> claims = mutableChild(raw, FIELD_CLAIM_BY_TARGET);
        Map<String, Object> claim = mutableChild(claims, target);
        Object priorRetry = claim.get(CLAIM_RETRY_COUNT);
        claim.put(CLAIM_TOKEN, token);
        claim.put(CLAIM_CLAIMED_AT, nowMs);
        claim.put(CLAIM_LEASE_EXPIRES, leaseExpiresAtMs);
        claim.remove(CLAIM_VERIFYING_SINCE);
        claim.put(CLAIM_RETRY_COUNT, priorRetry == null ? 0L : priorRetry);
    }

    /** PROJECTING → VERIFYING: sets the verify marker and renews the lease, same token. */
    static void applyVerifying(Map<String, Object> raw, String target, long nowMs,
                               long leaseExpiresAtMs) {
        mutableChild(raw, FIELD_STATUS_BY_TARGET)
                .put(target, LineagePublishStatus.VERIFYING.name());
        Map<String, Object> claim = mutableChild(mutableChild(raw, FIELD_CLAIM_BY_TARGET), target);
        claim.put(CLAIM_VERIFYING_SINCE, nowMs);
        claim.put(CLAIM_LEASE_EXPIRES, leaseExpiresAtMs);
    }

    /** Lease renewal in place (PROJECTING or VERIFYING), same token, nothing else moves. */
    static void applyRenew(Map<String, Object> raw, String target, long leaseExpiresAtMs) {
        Map<String, Object> claim = mutableChild(mutableChild(raw, FIELD_CLAIM_BY_TARGET), target);
        claim.put(CLAIM_LEASE_EXPIRES, leaseExpiresAtMs);
    }

    /**
     * A transition out of the live-claim states into a non-live state: writes the status,
     * clears the live lease, optionally increments retryCount (observed publish failure only),
     * optionally writes the durable terminal reason. Never removes audit fields.
     */
    static void applySettle(Map<String, Object> raw, String target, LineagePublishStatus next,
                            boolean incrementRetry,
                            LineageTargetLifecycle.TerminalReason reason) {
        mutableChild(raw, FIELD_STATUS_BY_TARGET).put(target, next.name());
        Map<String, Object> claims = mutableChild(raw, FIELD_CLAIM_BY_TARGET);
        if (claims.get(target) instanceof Map) {
            Map<String, Object> claim = mutableChild(claims, target);
            claim.remove(CLAIM_LEASE_EXPIRES);
            if (incrementRetry) {
                Object prior = claim.get(CLAIM_RETRY_COUNT);
                long current = prior instanceof Number n ? n.longValue() : 0L;
                claim.put(CLAIM_RETRY_COUNT, current + 1L);
            }
        }
        if (reason != null) {
            Map<String, Object> reasons = mutableChild(raw, FIELD_REASON_BY_TARGET);
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put(REASON_REASON, reason.reason());
            r.put(REASON_DETAIL, reason.detail());
            r.put(REASON_AT, reason.atMs());
            reasons.put(target, r);
        }
    }

    /** Status-only write for the pre-claim transitions (obligation and admin table rows). */
    static void applyStatusOnly(Map<String, Object> raw, String target, LineagePublishStatus next,
                                LineageTargetLifecycle.TerminalReason reason) {
        mutableChild(raw, FIELD_STATUS_BY_TARGET).put(target, next.name());
        if (reason != null) {
            Map<String, Object> reasons = mutableChild(raw, FIELD_REASON_BY_TARGET);
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put(REASON_REASON, reason.reason());
            r.put(REASON_DETAIL, reason.detail());
            r.put(REASON_AT, reason.atMs());
            reasons.put(target, r);
        }
    }
}
