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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * §8-d executor and crash recovery (D-rest-3, v2.3.20).
 *
 * <p>The compensation event is a PURE function of (original event, target, generation) — the
 * original's {@code eventId} and {@code occurredAt} are reused, no clock is read, and
 * {@code spoolRecordId} is deliberately NOT copied (a compensation constructed from a journal
 * row was not materialized from that spool fact; copying would falsely bind two deliveries to
 * one materialization decision). Same inputs → same deliveryId and digest → {@code appendV2}'s
 * exact-match 409 converges across any number of crash retries.
 *
 * <p>Completion is never inferred from a transition's boolean: the driver REREADS and decides
 * on what it OBSERVES — ACKED is reported only when ACKED is seen stored. A false CAS is just
 * "look again", bounded by a small conflict budget whose exhaustion reports indeterminate
 * (the durable request stays recoverable by the next poll), never success.
 */
@Component
public class LineageReplayService {

    private static final Logger logger = LoggerFactory.getLogger(LineageReplayService.class);

    /** CAS-conflict budget per drive; normal REQUESTED→CREATED→ACKED never consumes it. */
    private static final int CONFLICT_BUDGET = 5;

    @Autowired
    private LineageJournalStore journalStore;

    @Autowired
    private LineageDrestReadiness readiness;

    @Autowired
    private LineageConfig lineageConfig;

    @Autowired(required = false)
    private LineageMetrics lineageMetrics;

    /** The outcome the admin route renders: exactly one of the fields set per shape. */
    public record ReplayOutcome(String state, long generation, String requestId,
                                String compensationDeliveryId, List<String> violations,
                                String message) {
        static ReplayOutcome acked(long generation, String requestId, String deliveryId) {
            return new ReplayOutcome("ACKED", generation, requestId, deliveryId, List.of(),
                    null);
        }

        static ReplayOutcome notReady(List<String> violations) {
            return new ReplayOutcome("NOT_READY", 0, null, null, violations, null);
        }

        static ReplayOutcome refused(String message) {
            return new ReplayOutcome("REFUSED", 0, null, null, List.of(), message);
        }

        static ReplayOutcome indeterminate(String message) {
            return new ReplayOutcome("INDETERMINATE", 0, null, null, List.of(), message);
        }

        static ReplayOutcome failed(String message) {
            return new ReplayOutcome("FAILED", 0, null, null, List.of(), message);
        }
    }

    public ReplayOutcome execute(String recordId, String target) {
        LineageDrestReadiness.Readiness verdict = readiness.evaluate();
        if (!verdict.ready()) {
            return ReplayOutcome.notReady(verdict.violations());
        }
        LineageV2ReplayStore store = (LineageV2ReplayStore) journalStore;
        LineageV2ReplayStore.ReplayGrant grant;
        try {
            grant = store.requestReplay(recordId, target);
        } catch (LineageV2ReplayStore.ReplayRefusedException refusal) {
            return ReplayOutcome.refused(refusal.getMessage());
        }
        if (grant == null) {
            return ReplayOutcome.refused("a concurrent request won the CAS — a replay is"
                    + " already in progress for this target");
        }
        return drive(grant.recordId(), grant.target(), grant.requestId(), grant.generation());
    }

    /**
     * Reread-driven convergence: acts only on the observed state, reports only observed
     * terminal outcomes.
     */
    private ReplayOutcome drive(String recordId, String target, String requestId,
                                long generation) {
        LineageV2ReplayStore store = (LineageV2ReplayStore) journalStore;
        LineageV2TransitionStore v2store = (LineageV2TransitionStore) journalStore;
        int conflicts = 0;
        while (true) {
            LineageJournalRowV2 row = v2store.findV2ByRecordId(recordId);
            if (row == null) {
                return ReplayOutcome.refused("the source row vanished under the request");
            }
            LineageReplayRequest request = row.replayRequests().get(target);
            if (request == null) {
                return ReplayOutcome.refused("the replay request vanished — the row changed"
                        + " underneath (admin repair?)");
            }
            if (!requestId.equals(request.requestId())
                    || request.generation() != generation) {
                return ReplayOutcome.refused("a newer request fence owns this target"
                        + " (generation " + request.generation() + ")");
            }
            switch (request.state()) {
                case ACKED -> {
                    return ReplayOutcome.acked(generation, requestId,
                            compensationOf(row, target, generation).deliveryId());
                }
                case FAILED -> {
                    return ReplayOutcome.failed("replay request failed durably: "
                            + request.reason().reason() + " — " + request.reason().detail());
                }
                case REQUESTED, CREATED -> {
                    // §8-d recovery rule: BOTH unacked states resume from step 3 — the
                    // deterministic compensation is (re-)established before any advance, so a
                    // missing or replaced compensation row can never be acknowledged past.
                    if (!readiness.evaluate().ready()) {
                        return ReplayOutcome.indeterminate("readiness went red mid-drive —"
                                + " dormant until the gate is green again");
                    }
                    if (!lineageConfig.getTargets().contains(target)) {
                        // B1's stranded case: the target was removed between request and
                        // recovery. Refuse loudly, leave the durable request for the operator
                        // (re-add the target, or the future audited repair surface).
                        return ReplayOutcome.refused("target '" + target + "' is no longer"
                                + " configured — the request stays " + request.state()
                                + " until the target returns or an audited repair resolves"
                                + " it");
                    }
                    LineageEventV2 compensation = compensationOf(row, target, generation);
                    try {
                        journalStore.appendV2(compensation);
                    } catch (LineageIntegrityException collision) {
                        return convergeToFailed(store, recordId, target, requestId,
                                collision);
                    }
                    boolean advanced = request.state() == LineageReplayRequest.State.REQUESTED
                            ? store.advanceReplay(recordId, target, requestId,
                                    LineageReplayRequest.State.REQUESTED,
                                    LineageReplayRequest.State.CREATED)
                            : store.advanceReplay(recordId, target, requestId,
                                    LineageReplayRequest.State.CREATED,
                                    LineageReplayRequest.State.ACKED);
                    if (advanced) {
                        if (request.state() == LineageReplayRequest.State.CREATED
                                && lineageMetrics != null) {
                            // Count the ACK where OUR CAS landed it — observation-side
                            // counting would double-count across concurrent drivers.
                            lineageMetrics.recordReplayAcked(target);
                        }
                    } else {
                        conflicts++;
                    }
                }
            }
            if (conflicts > CONFLICT_BUDGET) {
                // The durable request stays recoverable by the next poll; only success is
                // never fabricated.
                return ReplayOutcome.indeterminate("CAS conflict budget exhausted at state "
                        + request.state() + " — the request remains durable and recovery"
                        + " will resume it");
            }
        }
    }

    /** Collision → durable FAILED, reread-converged; the 500 is fenced on OBSERVING it. */
    private ReplayOutcome convergeToFailed(LineageV2ReplayStore store, String recordId,
                                           String target, String requestId,
                                           LineageIntegrityException collision) {
        LineageV2TransitionStore v2store = (LineageV2TransitionStore) journalStore;
        var reason = new LineageTargetLifecycle.TerminalReason("COMPENSATION_ID_COLLISION",
                String.valueOf(collision.getMessage()),
                java.time.Instant.now().toEpochMilli());
        for (int attempt = 0; attempt <= CONFLICT_BUDGET; attempt++) {
            store.failReplay(recordId, target, requestId, reason);
            LineageJournalRowV2 row = v2store.findV2ByRecordId(recordId);
            LineageReplayRequest request = row == null ? null
                    : row.replayRequests().get(target);
            if (request == null || !requestId.equals(request.requestId())) {
                return ReplayOutcome.refused("ownership lost while failing the request");
            }
            if (request.state() == LineageReplayRequest.State.FAILED) {
                logger.error("Replay compensation collision for {} target '{}': {}", recordId,
                        target, collision.getMessage());
                return ReplayOutcome.failed("compensation id collision (digest mismatch): "
                        + collision.getMessage());
            }
        }
        return ReplayOutcome.indeterminate("could not converge the request to FAILED —"
                + " recovery will meet the collision again");
    }

    /**
     * The deterministic compensation (§8-d step 3): pure in (original, target, generation).
     */
    static LineageEventV2 compensationOf(LineageJournalRowV2 originalRow, String target,
                                         long generation) {
        LineageEventV2 original = originalRow.event();
        LineageEventV2Builder builder = new LineageEventV2Builder()
                .eventId(original.eventId())
                .repositoryId(original.repositoryId())
                .processType(original.processType())
                .operationId(original.operationId())
                .occurredAt(original.occurredAt())
                .delivery(new LineageDelivery.Replay(original.deliveryId(), target,
                        generation))
                .chunk(original.chunkIndex(), original.chunkCount())
                .correlationId(original.correlationId())
                .legacyEventKey(original.legacyEventKey())
                .sequenceNumber(0L);
        original.inputs().forEach(builder::addInput);
        original.outputs().forEach(builder::addOutput);
        return builder.build();
    }

    /**
     * B1 (v2.3.20): once-per-poll crash recovery. Runs after the leader guard, BEFORE the
     * empty-target early returns — a stranded request whose only target was removed is still
     * visited and refused loudly every poll. Red gate → fully dormant.
     */
    public int recoverUnacked(int limit) {
        return recoverUnackedOutcome(limit).recovered();
    }

    /** The manual route's shape: dormancy is distinguishable from an empty queue (F4). */
    public record RecoveryOutcome(boolean ready, List<String> violations, int recovered,
                                  boolean moreRemaining) {
    }

    public RecoveryOutcome recoverUnackedOutcome(int limit) {
        LineageDrestReadiness.Readiness verdict = readiness.evaluate();
        if (!verdict.ready()) {
            return new RecoveryOutcome(false, verdict.violations(), 0, false);
        }
        LineageV2ReplayStore store = (LineageV2ReplayStore) journalStore;
        // Probe at limit+1: a scan bounded by its own limit cannot see past itself.
        List<LineageV2ReplayStore.ReplayRecovery> items =
                store.findUnackedReplayRequests(Math.addExact(limit, 1));
        boolean more = items.size() > limit;
        int recovered = 0;
        for (LineageV2ReplayStore.ReplayRecovery item
                : items.subList(0, Math.min(items.size(), limit))) {
            ReplayOutcome outcome = drive(item.recordId(), item.target(),
                    item.request().requestId(), item.request().generation());
            switch (outcome.state()) {
                case "ACKED" -> {
                    recovered++;
                    if (lineageMetrics != null) {
                        lineageMetrics.recordReplayRecovered(item.target());
                    }
                }
                case "FAILED" -> logger.error("Replay recovery met a durable failure for {}"
                        + " target '{}': {}", item.recordId(), item.target(),
                        outcome.message());
                default -> logger.warn("Replay recovery left {} target '{}' as {}: {}",
                        item.recordId(), item.target(), outcome.state(), outcome.message());
            }
        }
        return new RecoveryOutcome(true, List.of(), recovered, more);
    }
}
