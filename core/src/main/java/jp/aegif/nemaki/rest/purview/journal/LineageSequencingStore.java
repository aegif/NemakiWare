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
 * The persistence surface of §8-a v2's fenced sequencer — a separate interface on purpose:
 * D-rest is deployed dual and inert, and none of {@link LineageJournalStore}'s v1 methods
 * (eager counter, three-argument status updates, v1 claim/cursor paths) may change until the
 * flip. {@code CouchLineageJournalStore} implements both; the v1 surface stays byte-identical.
 *
 * <p>Every mutation here is a CAS: the row's {@code _rev} plus the fencing coordinates
 * {@code (generation, sequencerLeaseToken)} decide, and a {@code false} return means the world
 * moved — the caller re-reads and re-decides, never retries blindly.
 */
public interface LineageSequencingStore {

    /** A held lease: what acquire/renew return and what every fenced write carries. */
    record LeaseGrant(String repositoryId, long generation, String sequencerLeaseToken,
                      String owner, String expiresAt, String rev) {
    }

    /** The lease as observed — what the pre-write re-verification compares against. */
    record LeaseView(long generation, String sequencerLeaseToken, String owner,
                     String expiresAt) {
    }

    /**
     * The lease document is absent. §8-a: it is created by the bootstrap patch only — never
     * auto-created — so absence in operation is fail-closed ({@code LEASE_MISSING}).
     */
    class LeaseMissingException extends RuntimeException {
        public LeaseMissingException(String repositoryId) {
            super("sequencer lease document is missing for repository '" + repositoryId
                    + "' — bootstrap creates it; operation never does");
        }
    }

    /**
     * The sequence counter is unusable — missing, malformed, negative, or behind the
     * finalized high-watermark. §8-a I-4: fail-closed, no auto-seed; recovery is a management
     * operation.
     */
    class SequenceCounterException extends RuntimeException {
        private final SequencerHealth health;

        public SequenceCounterException(SequencerHealth health, String message) {
            super(message);
            this.health = health;
        }

        public SequencerHealth health() {
            return health;
        }
    }

    /**
     * An infrastructure failure — distinct from absence (404) and conflict (409), which are
     * ordinary answers. Callers must treat this as unsafe and latch, never as "missing".
     */
    class SequencingStorageException extends RuntimeException {
        public SequencingStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** §8-a's health states, surfaced on the health endpoint and metrics. */
    enum SequencerHealth { FENCED_OK, COUNTER_MISSING, COUNTER_REWOUND, STOPPED, LEASE_MISSING }

    // ------------------------------------------------------------------ lease

    /**
     * Acquire: only when the lease document exists and is free ({@code owner=null} or
     * expired); CAS to {@code generation+1} with a fresh random {@code sequencerLeaseToken}.
     *
     * @return empty when another node holds it or the CAS lost
     * @throws LeaseMissingException when the document is absent (never auto-created)
     */
    Optional<LeaseGrant> acquireSequencerLease(String repositoryId, String nodeId,
                                               Duration ttl);

    /**
     * Renew: CAS extending {@code expiresAt}, matching owner, generation and token.
     * An empty return is the fence latch's trigger — the caller must never write again under
     * this grant.
     */
    Optional<LeaseGrant> renewSequencerLease(LeaseGrant grant, Duration ttl);

    /**
     * Release: owner/generation/token/_rev CAS to {@code owner=null, expiresAt=past}. The
     * generation survives and the document is never deleted — deleting would erase the
     * high-watermark and let a future acquire reuse an old leader's generation.
     */
    void releaseSequencerLease(LeaseGrant grant);

    /** The current lease, for the pre-write re-verification. Empty means missing. */
    Optional<LeaseView> readSequencerLease(String repositoryId);

    // ------------------------------------------------------------------ rows

    /** UNSEQUENCED rows, occurredAt ascending then _id ascending — the claim order. */
    List<LineageJournalRowV2> findUnsequencedV2(String repositoryId, int limit);

    /** SEQUENCING rows, same order — the reclaim scan (stale generations only get taken). */
    List<LineageJournalRowV2> findSequencingV2(String repositoryId, int limit);

    /** claim: UNSEQUENCED → SEQUENCING(G, T). False = the row moved; re-read. */
    boolean claimForSequencing(LineageJournalRowV2 row, long generation,
                               String sequencerLeaseToken);

    /**
     * reclaim: SEQUENCING(G_stale) → SEQUENCING(G, T), only when {@code staleGeneration}
     * matches the stored one and is strictly below {@code generation}. The only transition
     * that recovers an old leader's stalled row.
     */
    boolean reclaimForSequencing(LineageJournalRowV2 row, long staleGeneration,
                                 long generation, String sequencerLeaseToken);

    /**
     * finalize: SEQUENCING(G, T) → SEQUENCED with {@code sequence} — the state and the number
     * in one CAS. The fencing coordinates stay on the row for audit.
     */
    boolean finalizeSequence(LineageJournalRowV2 row, long generation,
                             String sequencerLeaseToken, long sequence);

    // ------------------------------------------------------------------ counter

    /**
     * The fenced allocator (v2.3.18 ③): the same per-repository counter document v1 uses, but
     * with none of v1's forgiveness — the counter must exist, parse, and be non-negative, and
     * this method never seeds it. CAS-increments and returns the allocated number.
     *
     * @throws SequenceCounterException missing/malformed/rewound — fail-closed (I-4)
     */
    long allocateSequenceFenced(String repositoryId);

    /**
     * The sequence high-watermark for the rewind check ({@code COUNTER_REWOUND} when the
     * counter is below it): the max over finalized v1 AND v2 sequences and every target's
     * projection cursor — the counter is shared with v1 (v2.3.18 ③), so v1 history bounds it
     * too. 0 when the repository has no history.
     *
     * @throws SequencingStorageException when the watermark cannot be read — allocation must
     *                                    fail closed, not proceed blind
     */
    long sequenceHighWatermark(String repositoryId);
}
