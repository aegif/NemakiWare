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
 * The obligation machine's storage contract (§2).
 *
 * <p>Every transition is a compare-and-set on the document's {@code _rev}. A lost CAS is an
 * ordinary answer ({@code false} / empty), not an exception — two workers racing for the same
 * obligation is the normal case, and an exception would turn it into an error to handle.
 * Anything that is not a CAS loss throws, so an outage cannot be mistaken for a race.
 */
public interface LineageCatalogObligationStore {

    /** A claim, and the only thing that authorises a later transition on it. */
    record Claim(String taskKey, String owner, String token, long leaseUntilMs) {
    }

    /** Storage or protocol failure. Never used for a CAS loss. */
    class ObligationStorageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ObligationStorageException(String message, Throwable cause) {
            super(message, cause);
        }

        public ObligationStorageException(String message) {
            super(message);
        }
    }

    /**
     * Refused because a document with this task key describes something else.
     *
     * <p>Separate from a storage failure because the two need opposite responses: a storage
     * failure should be retried, and this must not be — retrying would keep asserting that two
     * different subjects are the same obligation.
     */
    class ObligationSubjectConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ObligationSubjectConflictException(String message) {
            super(message);
        }
    }

    /**
     * Creates the obligation if it is not there, and returns what is there either way.
     *
     * <p>Create-if-absent, so a restart, a replay and a duplicate delivery converge on one
     * document. Finding an existing one is success <em>only</em> if it describes the same
     * subject; a task key holding something else is refused rather than adopted.
     *
     * @throws ObligationSubjectConflictException if an existing document means something else
     */
    LineageCatalogObligation createIfAbsent(LineageCatalogObligation obligation);

    /** The obligation, or empty if there is none. A read failure throws. */
    Optional<LineageCatalogObligation> read(String taskKey);

    /**
     * PENDING → CLAIMED with a fresh token, or empty if someone else got there first.
     *
     * <p>Also claims an obligation whose lease has expired: the previous worker is gone, and
     * the new token is what stops it from coming back and finishing on top of the new one.
     */
    Optional<Claim> claim(String taskKey, String owner, Duration lease, long nowMs);

    /**
     * Extends a live claim. Token must match; a stale claimant is refused.
     *
     * @return the renewed claim, or empty if the token no longer holds it
     */
    Optional<Claim> renew(Claim claim, Duration lease, long nowMs);

    /**
     * CLAIMED → RESOLVED. Token must match.
     *
     * @param reason why it resolved, bound to the outcome; never a catalog response body
     * @param evidence what was observed — a digest or an identifier, never a value
     */
    boolean resolve(Claim claim, LineageCatalogObligation.Outcome outcome, String reason,
            String evidence);

    /**
     * CLAIMED → UNRESOLVED, terminal. Token must match.
     *
     * <p>Refuses {@link LineageCatalogObligation.Outcome#SOURCE_ERROR}: that outcome is
     * retryable by contract, and recording it here would make a transient catalog failure
     * permanent for every event waiting on this obligation.
     */
    boolean giveUp(Claim claim, LineageCatalogObligation.Outcome outcome, String reason,
            String evidence);

    /**
     * CLAIMED → PENDING after a retryable failure, incrementing {@code attempts}.
     *
     * <p>Token must match, so a worker whose lease expired cannot release a claim that has
     * since been taken by someone else.
     */
    boolean release(Claim claim, String reason, long nowMs, long baseMs, long maxMs);

    /**
     * PENDING obligations whose backoff has elapsed. Bounded.
     *
     * <p>Separate from {@link #findByState} because a scanner that took every PENDING one and
     * then failed to claim the backed-off ones would still have asked the catalog about them —
     * the backoff has to keep work away from the catalog, not merely from the CAS.
     */
    List<LineageCatalogObligation> findClaimable(int limit, long nowMs);

    /**
     * Returns expired claims to PENDING, so an obligation does not outlive the worker holding
     * it. Bounded per call.
     *
     * @return how many were reclaimed
     */
    int reclaimExpired(int limit, long nowMs);

    /** Obligations in a state, bounded. For the scanner and for admin status. */
    List<LineageCatalogObligation> findByState(LineageCatalogObligation.State state, int limit);

    /** Counts by state, for metrics and admin status. Never returns a value from a document. */
    java.util.Map<LineageCatalogObligation.State, Long> countByState();
}
