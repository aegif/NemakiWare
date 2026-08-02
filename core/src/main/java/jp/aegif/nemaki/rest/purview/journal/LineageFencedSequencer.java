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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseGrant;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseMissingException;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseView;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.SequenceCounterException;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.SequencerHealth;

/**
 * §8-a v2's fenced finalizer: assigns sequence numbers to durable {@code UNSEQUENCED} v2 rows,
 * one repository at a time, under a generation+token lease.
 *
 * <p><b>Deployed inert (v2.3.18 ⑤).</b> Nothing schedules this class and no admin entry point
 * exists in D-rest-1 — production has no v2 rows to sequence, and exposing a runnable
 * sequencer before the v2 projector routing (D-rest-2) would let finalized v2 rows reach the
 * legacy status/cursor paths.
 *
 * <h2>The fence</h2>
 *
 * <p>Every write is a CAS carrying {@code (generation, sequencerLeaseToken)}, and each of
 * claim / reclaim / allocate / finalize is preceded by a fresh re-read of the lease document
 * (owner, generation, token, unexpired). A failed renewal or a failed re-check drops a
 * <b>one-way latch</b>: this run never writes again under its grant; recovery is a new
 * acquire with a new generation and token. Sequence gaps are tolerated by design (I-1..I-4) —
 * a number allocated by a fenced-out leader is simply never used.
 *
 * <p>Counter failures are fail-closed: {@link SequenceCounterException} stops the run and the
 * affected rows stay {@code SEQUENCING} under this generation, recoverable by the next
 * generation's reclaim pass. Nothing here ever seeds or repairs the counter.
 */
public class LineageFencedSequencer {

    private static final Logger logger = LoggerFactory.getLogger(LineageFencedSequencer.class);

    /** What one {@code runOnce} did — the admin surface's future payload, and the tests'. */
    public record RunSummary(SequencerHealth health, int finalized, int reclaimed,
                             int backlog, boolean lostLease) {
    }

    private final LineageSequencingStore store;
    private final LineageMetrics metrics;
    private final String nodeId;
    private final Duration leaseTtl;
    private final int batchSize;
    private final int backlogCap;

    public LineageFencedSequencer(LineageSequencingStore store, LineageMetrics metrics,
                                  String nodeId, Duration leaseTtl, int batchSize,
                                  int backlogCap) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
        if (batchSize <= 0 || backlogCap <= 0) {
            throw new IllegalArgumentException("batchSize and backlogCap must be positive");
        }
        this.store = store;
        this.metrics = metrics;
        this.nodeId = nodeId;
        this.leaseTtl = leaseTtl;
        this.batchSize = batchSize;
        this.backlogCap = backlogCap;
    }

    /**
     * One fenced pass over one repository: acquire → reclaim stale {@code SEQUENCING} rows →
     * claim {@code UNSEQUENCED} rows in occurredAt order → finalize each with a fenced
     * allocation → release. Never throws.
     */
    public RunSummary runOnce(String repositoryId) {
        LeaseGrant grant;
        try {
            Optional<LeaseGrant> acquired = store.acquireSequencerLease(repositoryId, nodeId,
                    leaseTtl);
            if (acquired.isEmpty()) {
                int heldBacklog;
                try {
                    heldBacklog = backlog(repositoryId);
                } catch (RuntimeException probeFailed) {
                    logger.error("Backlog probe failed for {} while the lease is held"
                            + " elsewhere: {}", repositoryId, probeFailed.toString());
                    return new RunSummary(SequencerHealth.STOPPED, 0, 0, 0, false);
                }
                return new RunSummary(SequencerHealth.FENCED_OK, 0, 0, heldBacklog, false);
            }
            grant = acquired.get();
        } catch (LeaseMissingException missing) {
            logger.error("Sequencer for {}: {}", repositoryId, missing.getMessage());
            return new RunSummary(SequencerHealth.LEASE_MISSING, 0, 0, 0, false);
        } catch (RuntimeException e) {
            logger.error("Sequencer acquire failed for {}: {}", repositoryId, e.toString());
            return new RunSummary(SequencerHealth.STOPPED, 0, 0, 0, false);
        }

        Run run = new Run(repositoryId, grant);
        try {
            run.reclaimPass();
            run.claimPass();
        } catch (RuntimeException unsafe) {
            // An infrastructure failure (SequencingStorageException et al.) is not a CAS loss:
            // re-reading forever would spin against an outage, and continuing would write
            // blind. Latch and stop; the next acquire starts a new generation.
            logger.error("Sequencer for {} hit an unsafe failure — latching: {}", repositoryId,
                    unsafe.toString());
            run.health = SequencerHealth.STOPPED;
            run.latched = true;
        } finally {
            if (!run.latched) {
                store.releaseSequencerLease(run.grant);
            }
            // A latched run must not release: the lease may already belong to a newer
            // generation, and the release CAS would just lose — but attempting it with a
            // stale grant is exactly the kind of write the latch forbids.
        }
        int backlog;
        try {
            backlog = backlog(repositoryId);
        } catch (RuntimeException probeFailed) {
            // A broken probe is broken infrastructure, not an empty backlog: FENCED_OK with
            // backlog 0 would read as "all caught up" over an outage.
            logger.error("Backlog probe failed for {}: {}", repositoryId,
                    probeFailed.toString());
            return new RunSummary(
                    run.health == SequencerHealth.FENCED_OK ? SequencerHealth.STOPPED
                            : run.health,
                    run.finalized, run.reclaimed, 0, run.latched);
        }
        if (backlog > backlogCap) {
            logger.warn("Sequencer backlog for {} is {} (cap {}) — processing continues;"
                    + " investigate throughput", repositoryId, backlog, backlogCap);
            if (metrics != null) metrics.recordSequencerBacklogAlert();
        }
        return new RunSummary(run.health, run.finalized, run.reclaimed, backlog, run.latched);
    }

    private int backlog(String repositoryId) {
        // Probed one past the cap, not by batchSize: a batch-bounded probe could never see
        // past its own limit, and the alert exists precisely for backlogs bigger than a pass.
        // Failures propagate — the callers decide, and none of them may read a broken probe
        // as an empty backlog.
        return store.findUnsequencedV2(repositoryId, backlogCap + 1).size();
    }

    /** One acquired generation's mutable state — the latch lives and dies with it. */
    private final class Run {

        private final String repositoryId;
        private LeaseGrant grant;
        private boolean latched;
        private SequencerHealth health = SequencerHealth.FENCED_OK;
        private int finalized;
        private int reclaimed;

        private Run(String repositoryId, LeaseGrant grant) {
            this.repositoryId = repositoryId;
            this.grant = grant;
        }

        /** Rows a previous generation left {@code SEQUENCING}: take them over, then finalize. */
        private void reclaimPass() {
            if (latched) {
                return;
            }
            for (LineageJournalRowV2 row : store.findSequencingV2(repositoryId, batchSize)) {
                if (latched || finalized >= batchSize) {
                    return;
                }
                Long staleGeneration = row.sequencerGeneration();
                if (staleGeneration == null || staleGeneration >= grant.generation()) {
                    continue; // our own in-flight row, or a newer leader's — not ours to take
                }
                if (!preWriteCheck()) {
                    return;
                }
                if (!store.reclaimForSequencing(row, staleGeneration, grant.generation(),
                        grant.sequencerLeaseToken())) {
                    continue; // the world moved; the next pass re-reads
                }
                reclaimed++;
                if (metrics != null) metrics.recordSequencerReclaimed();
                finalizeClaimed(row.documentId());
            }
        }

        /** The normal path: UNSEQUENCED rows in occurredAt-then-_id order. */
        private void claimPass() {
            if (latched) {
                return;
            }
            for (LineageJournalRowV2 row : store.findUnsequencedV2(repositoryId, batchSize)) {
                if (latched || finalized >= batchSize) {
                    return;
                }
                if (!preWriteCheck()) {
                    return;
                }
                if (!store.claimForSequencing(row, grant.generation(),
                        grant.sequencerLeaseToken())) {
                    continue;
                }
                finalizeClaimed(row.documentId());
            }
        }

        /**
         * Allocate and finalize one claimed row, re-reading it for the fresh {@code _rev} the
         * finalize CAS needs. Order is §8-a's: the event is durable before any number is
         * consumed (I-1), and a crash after allocation leaves a gap, never a loss.
         */
        private void finalizeClaimed(String documentId) {
            LineageJournalRowV2 claimed = rereadSequencing(documentId);
            if (claimed == null) {
                return;
            }
            long sequence;
            try {
                if (!preWriteCheck()) {
                    return;
                }
                sequence = store.allocateSequenceFenced(repositoryId);
            } catch (SequenceCounterException counter) {
                logger.error("Sequencer for {} stopping: {}", repositoryId,
                        counter.getMessage());
                health = counter.health();
                latch();
                return;
            }
            if (!preWriteCheck()) {
                return; // the allocated number becomes a tolerated gap
            }
            if (store.finalizeSequence(claimed, grant.generation(),
                    grant.sequencerLeaseToken(), sequence)) {
                finalized++;
                if (metrics != null) metrics.recordSequencerFinalized();
            }
            // A false return leaves the row SEQUENCING under some generation and the number as
            // a gap; the next pass (or the next generation's reclaim) re-decides.
        }

        private LineageJournalRowV2 rereadSequencing(String documentId) {
            // The claim changed the row's _rev; the finalize CAS needs the current one. The
            // in-flight view is the cheapest consistent read that also re-verifies state.
            for (LineageJournalRowV2 row : store.findSequencingV2(repositoryId, batchSize)) {
                if (row.documentId().equals(documentId)) {
                    Long generation = row.sequencerGeneration();
                    if (generation != null && generation == grant.generation()
                            && grant.sequencerLeaseToken().equals(row.sequencerLeaseToken())) {
                        return row;
                    }
                    return null; // taken over by a newer generation while we looked away
                }
            }
            return null;
        }

        /**
         * §8-a's pre-write re-verification: owner, generation, token, unexpired — read fresh
         * immediately before every fenced write. Renews when the remaining TTL is below half.
         * Any mismatch or failure drops the latch.
         */
        private boolean preWriteCheck() {
            if (latched) {
                return false;
            }
            Optional<LeaseView> view = store.readSequencerLease(repositoryId);
            if (view.isEmpty()) {
                logger.error("Sequencer lease for {} vanished mid-run — latching",
                        repositoryId);
                health = SequencerHealth.LEASE_MISSING;
                latch();
                return false;
            }
            LeaseView lease = view.get();
            boolean stillOurs = lease.generation() == grant.generation()
                    && grant.sequencerLeaseToken().equals(lease.sequencerLeaseToken())
                    && grant.owner().equals(lease.owner())
                    && !expired(lease.expiresAt());
            if (!stillOurs) {
                latch();
                return false;
            }
            if (remainingBelowHalfTtl(lease.expiresAt())) {
                Optional<LeaseGrant> renewed = store.renewSequencerLease(grant, leaseTtl);
                if (renewed.isEmpty()) {
                    latch();
                    return false;
                }
                grant = renewed.get();
            }
            return true;
        }

        private void latch() {
            if (!latched) {
                latched = true;
                if (health == SequencerHealth.FENCED_OK) {
                    health = SequencerHealth.STOPPED;
                }
                logger.warn("Sequencer generation {} for {} is fenced — no further writes"
                        + " under this grant", grant.generation(), repositoryId);
            }
        }

        private boolean expired(String expiresAt) {
            try {
                return expiresAt == null || Instant.parse(expiresAt).isBefore(Instant.now());
            } catch (RuntimeException e) {
                return true; // unparseable expiry: treat our own hold as lost, never as safe
            }
        }

        private boolean remainingBelowHalfTtl(String expiresAt) {
            try {
                Instant expiry = Instant.parse(expiresAt);
                return Instant.now().plus(leaseTtl.dividedBy(2)).isAfter(expiry);
            } catch (RuntimeException e) {
                return true;
            }
        }
    }
}
