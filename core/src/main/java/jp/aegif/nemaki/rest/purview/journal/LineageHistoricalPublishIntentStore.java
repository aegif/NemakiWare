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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Durable storage for historical publish intents.
 *
 * <p>Every transition is a {@code _rev} CAS. The intent carries its own lease and token rather
 * than borrowing the obligation's: an intent outlives the claim that created it, and a token
 * that has been fenced out of the obligation must not still be able to advance the intent.
 */
public interface LineageHistoricalPublishIntentStore {

    /**
     * A hold on an intent, and the only thing that authorises advancing it.
     *
     * <p>Carries the state observed <em>at the moment of the CAS</em>. A caller that branched on
     * the state of the intent object it was handed would be acting on a reading from before the
     * claim — which is exactly the window another worker uses to move it.
     */
    record IntentClaim(String intentId, String owner, String token, long leaseUntilMs,
            LineageHistoricalPublishIntent.State stateAtClaim) { }

    /**
     * Exclusive right to write the catalog entity for one subject.
     *
     * <p>Intent ids differ per evidence, which is necessary and not sufficient: two intents
     * built from different observations of one object would otherwise publish to the same
     * qualified name concurrently, and the surviving entity would be whichever call finished
     * last. The fence serialises them; ordering between them is then decided by the source,
     * which is the only authority on which observation is current.
     *
     * @param subjectKey target + repository + kind + subject digest
     */
    record SubjectFence(String subjectKey, String intentId, String token, long leaseUntilMs) { }

    /** Storage or protocol failure. Never used for a CAS loss. */
    class IntentStorageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IntentStorageException(String message) {
            super(message);
        }

        public IntentStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Refused because an intent with this id describes a different plan. */
    class IntentPlanConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IntentPlanConflictException(String message) {
            super(message);
        }
    }

    /**
     * Creates the intent if absent, returning what is there.
     *
     * @throws IntentPlanConflictException if an existing intent is a different plan
     */
    LineageHistoricalPublishIntent createIfAbsent(LineageHistoricalPublishIntent intent);

    Optional<LineageHistoricalPublishIntent> read(String intentId);

    /** Takes or re-takes an intent. A fresh token each time — that is the fence. */
    Optional<IntentClaim> claim(String intentId, String owner, Duration lease, long nowMs);

    /** Extends a live hold. Token must match. */
    Optional<IntentClaim> renew(IntentClaim claim, Duration lease, long nowMs);

    /**
     * Moves the intent forward. Token must match, and the current state must be {@code from} —
     * so a worker that slept through a reclaim cannot re-apply a transition already made.
     */
    boolean transition(IntentClaim claim, LineageHistoricalPublishIntent.State from,
            LineageHistoricalPublishIntent.State to, String reason);

    /** Records a transient failure without moving the state: attempts up, reason recorded. */
    boolean recordAttempt(IntentClaim claim, String reason);

    /** Bounded, for the recovery scanner and for admin status. */
    List<LineageHistoricalPublishIntent> findByState(LineageHistoricalPublishIntent.State state,
            int limit);

    /**
     * Takes the exclusive right to write this subject's entity, or reports who holds it.
     *
     * <p>Leased, so an abandoned holder cannot block the subject forever. Empty means another
     * intent holds it and this one must wait — not that it may proceed anyway.
     */
    Optional<SubjectFence> acquireSubjectFence(String subjectKey, String intentId,
            Duration lease, long nowMs);

    /**
     * Extends the fence. Token must match.
     *
     * <p>Needed because a source lookup and a catalog write both take time, and a fence that
     * expired mid-write would let another intent take the subject while the first is still
     * writing to it. Renewing the intent lease alone does not restore that exclusivity.
     */
    Optional<SubjectFence> renewSubjectFence(SubjectFence fence, Duration lease, long nowMs);

    /** Releases the fence, if this holder still has it. */
    boolean releaseSubjectFence(SubjectFence fence);

    /**
     * The intents still contending for one subject — {@code PLANNED} and {@code PUBLISHED}.
     *
     * <p>The fence stops two from writing at once; this is what decides which of them <em>may</em>
     * write. A source that has been purged cannot say which of two snapshots is newer, so the
     * answer has to come from the observation coordinate the intents carry.
     */
    /**
     * Exact counts per state, from the view's own reduce.
     *
     * <p>A preflight reads these. A count that cannot be read must come back as a lower bound
     * rather than as zero — zero is the one answer that looks finished.
     */
    java.util.Map<LineageHistoricalPublishIntent.State,
            LineageCatalogObligationStore.StateCount> countByState();

    /**
     * How many subject fences exist, and how many of them have expired.
     *
     * @param nowMs the instant expiry is measured against
     */
    record FenceCounts(long active, long expired, boolean truncated) { }

    /** Fence counts, or a truncated verdict when they cannot be established. */
    FenceCounts countFences(long nowMs, int limit);

    List<LineageHistoricalPublishIntent> findContendingForSubject(String subjectKey, int limit);

    /**
     * Settles a losing intent, recording only the winner's digest.
     *
     * <p>Terminal and not a failure. Without it the loser sits in {@code PLANNED} for ever,
     * finding a conflict on every scan, and every event waiting on its obligation waits with it.
     */
    boolean markSuperseded(IntentClaim claim, String supersededByDigest, String reason);

    /** The subject key a fence is taken on. One per catalog entity. */
    static String subjectKey(String target, String repositoryId, EndpointKind kind,
            String subjectDigest) {
        return LineageCanonicalHash.hash("LINEAGE_HISTORICAL_SUBJECT_FENCE_V1", target,
                repositoryId, kind == null ? null : kind.name(), subjectDigest);
    }
}
