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

    /** A hold on an intent, and the only thing that authorises advancing it. */
    record IntentClaim(String intentId, String owner, String token, long leaseUntilMs) { }

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
}
