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

/**
 * §8-d: the replay-request CAS machine on v2 journal rows (D-rest-3, v2.3.20).
 *
 * <p>Deliberately a separate interface from {@link LineageV2TransitionStore}: the publish
 * lifecycle and the replay-request machine are disjoint state machines that happen to live on
 * the same document. Failure classification follows the same strict-IO rule — "lost the CAS"
 * and "not found" are ordinary answers, infrastructure failures are
 * {@link LineageSequencingStore.SequencingStorageException} and propagate.
 */
public interface LineageV2ReplayStore {

    /** A won replay request: the record owns generation and requestId from this moment. */
    record ReplayGrant(String recordId, String target, long generation, String requestId) {
    }

    /** A refusal whose reason the admin route must carry (409, not 500). */
    class ReplayRefusedException extends RuntimeException {
        public ReplayRefusedException(String message) {
            super(message);
        }
    }

    /** One unacked request the crash scanner must drive to ACKED (or durable FAILED). */
    record ReplayRecovery(String recordId, String target, LineageReplayRequest request) {
    }

    /**
     * CAS-creates the REQUESTED record for {@code target} on a v2 row.
     *
     * <p>Preconditions (strict reread first; violations throw {@link ReplayRefusedException}):
     * the row is v2 and SEQUENCED; the trimmed target is non-blank, present in the source
     * row's lifecycle map, and currently configured; the target's publish lifecycle is
     * TERMINAL (a PENDING/FAILED row is the live machine's business, a PROJECTING/VERIFYING
     * row holds a token-fenced claim replay must not steal). Expected request state is exactly
     * the frozen {absent, ACKED}; FAILED is durable and permanently blocks pending an audited
     * repair surface.
     *
     * <p>generation = Math.addExact(prior, 1) (or 1); requestId = fresh UUID.
     *
     * @return the grant, or {@code null} when the CAS was lost (caller answers
     *         "already in progress")
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    ReplayGrant requestReplay(String recordId, String target);

    /**
     * Advances the request: REQUESTED→CREATED or CREATED→ACKED only, requestId-fenced
     * (the stored requestId must equal the caller's; generation is never touched).
     *
     * @return {@code true} iff persisted; {@code false} on CAS loss, ownership loss, or state
     *         mismatch — the caller REREADS and re-decides, never infers completion
     * @throws IllegalArgumentException for a pair outside the two above
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    boolean advanceReplay(String recordId, String target, String requestId,
                          LineageReplayRequest.State expected, LineageReplayRequest.State next);

    /**
     * Converges the request to durable FAILED with its diagnosis (REQUESTED|CREATED → FAILED,
     * requestId-fenced).
     *
     * @return {@code true} iff persisted
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    boolean failReplay(String recordId, String target, String requestId,
                       LineageTargetLifecycle.TerminalReason reason);

    /**
     * Scans the unacked-request view (REQUESTED/CREATED), strict-rereading each hit; corrupt
     * rows are skipped loudly and cannot pin the scan. Deduplication is per
     * (documentId, target, requestId, generation) — one row with two active targets is two
     * recovery items.
     *
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    List<ReplayRecovery> findUnackedReplayRequests(int limit);
}
