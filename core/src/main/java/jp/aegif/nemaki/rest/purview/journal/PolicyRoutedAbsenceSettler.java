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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ABSENT branch, routed by what can happen to the source.
 *
 * <h2>Two branches, and why they cannot be one</h2>
 *
 * <p>For a kind NemakiWare destroys, an absent catalog entity has two possible meanings: the
 * authoritative publisher has not run yet, or the source is gone and the entity should be a
 * tombstone. Only the authoritative source resolver can tell them apart, and only the historical
 * machine may act on a purge — with its intent, its fence and its compensation.
 *
 * <p>For a kind NemakiWare never destroys, the second meaning does not exist. There is no purge
 * to witness, so the obligation is settled by materialising what the event observed. That path
 * must never reach the historical machine, and the type system enforces it:
 * {@link ObservedEntitySnapshot} refuses a LEDGERED kind and {@link HistoricalEntitySnapshot}
 * refuses anything but a proven purge.
 *
 * <h2>CORRUPT does not end a shared obligation</h2>
 *
 * <p>One event's row being unreadable says nothing about the others waiting on the same catalog
 * entity. Terminalising the obligation would take all of them down for one bad row, so a
 * corruption is counted and reported and the obligation stays open.
 */
public final class PolicyRoutedAbsenceSettler implements LineageCatalogAbsenceSettler {

    private static final Logger logger =
            LoggerFactory.getLogger(PolicyRoutedAbsenceSettler.class);

    private final LineageWaitingSnapshotResolver waitingSnapshotResolver;
    private final LineageHistoricalPublishMachine historicalMachine;
    private final LineageObservedEntityMaterializer observedMaterializer;
    private final LineageSourceDispositionRegistry sourceResolvers;

    /**
     * Corruption counts by fixed reason, for metrics and the preflight.
     *
     * <p>Reasons only — never a payload, a qualified name or a task key. The counts are what
     * tells an operator that something is wrong at all, since the obligation itself stays open
     * and would otherwise look like ordinary waiting.
     */
    private final Map<String, LongAdder> corruptionCounts = new ConcurrentHashMap<>();

    public PolicyRoutedAbsenceSettler(LineageWaitingSnapshotResolver waitingSnapshotResolver,
            LineageHistoricalPublishMachine historicalMachine,
            LineageObservedEntityMaterializer observedMaterializer,
            LineageSourceDispositionRegistry sourceResolvers) {
        this.waitingSnapshotResolver = waitingSnapshotResolver;
        this.historicalMachine = historicalMachine;
        this.observedMaterializer = observedMaterializer;
        this.sourceResolvers = sourceResolvers;
    }

    @Override
    public LineageWaitingSnapshotResolver waitingSnapshotResolverRef() {
        return waitingSnapshotResolver;
    }

    @Override
    public LineageHistoricalPublishMachine historicalMachineRef() {
        return historicalMachine;
    }

    @Override
    public LineageObservedEntityMaterializer observedMaterializerRef() {
        return observedMaterializer;
    }

    /** Corruption counts by fixed reason. A copy; callers cannot reset the machine's view. */
    public Map<String, Long> corruptionCounts() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        corruptionCounts.forEach((reason, count) -> snapshot.put(reason, count.sum()));
        return snapshot;
    }

    @Override
    public Verdict settle(LineageCatalogObligation obligation) {
        if (obligation == null || waitingSnapshotResolver == null) {
            return Verdict.RETRY;
        }
        var classified = LineagePurgeLifecyclePolicy.of(obligation.endpointKind());
        if (classified.isEmpty()) {
            // A kind nobody has established either destroys its source or does not. Neither
            // branch is safe to take, and readiness already refuses to activate over it.
            return Verdict.INDETERMINATE;
        }

        LineageWaitingSnapshotResolver.Resolution resolution =
                waitingSnapshotResolver.resolve(obligation);
        if (resolution instanceof LineageWaitingSnapshotResolver.Resolution.Corrupt corrupt) {
            // Counted, not terminalised: other events wait on this same entity, and one
            // unreadable row is no verdict on theirs.
            corruptionCounts.computeIfAbsent(corrupt.reason(), r -> new LongAdder()).increment();
            logger.warn("A waiting row for an obligation is corrupt: {}", corrupt.reason());
            return Verdict.INDETERMINATE;
        }
        if (!(resolution
                instanceof LineageWaitingSnapshotResolver.Resolution.LatestWaitingSnapshot
                        latest)) {
            // NoWaitingEvent or Indeterminate. Neither is a licence to write anything.
            return Verdict.INDETERMINATE;
        }

        return classified.get().policy() == LineagePurgeLifecyclePolicy.LEDGERED
                ? settleLedgered(obligation, latest)
                : settleObserved(obligation, latest);
    }

    /**
     * A kind NemakiWare destroys: ask what the repository says, then act through the machine.
     *
     * <p>Nothing here decides PURGED. The authoritative resolver does, from a ledger mark the
     * destroying code wrote, and the historical machine owns the intent, the subject fence, the
     * read-back and the compensation. Publishing directly would skip all of them.
     */
    private Verdict settleLedgered(LineageCatalogObligation obligation,
            LineageWaitingSnapshotResolver.Resolution.LatestWaitingSnapshot latest) {
        if (historicalMachine == null || sourceResolvers == null) {
            return Verdict.RETRY;
        }
        LineageSourceDispositionResolver.SourceEvidence evidence;
        try {
            evidence = sourceResolvers.dispositionOf(obligation.repositoryId(),
                    obligation.endpointKind(), obligation.catalogQualifiedName());
        } catch (RuntimeException e) {
            logger.warn("An authoritative source lookup failed: {}",
                    e.getClass().getSimpleName());
            return Verdict.RETRY;
        }
        if (evidence == null || evidence.disposition() == null) {
            return Verdict.RETRY;
        }
        switch (evidence.disposition()) {
            case SOURCE_EXISTS -> {
                // The source is there, so the authoritative publisher owes the entity. Nothing
                // for this machine to write; the obligation retries until that publisher runs.
                return Verdict.RETRY;
            }
            case SOURCE_UNKNOWN -> {
                // Not established. Never a licence to tombstone.
                return Verdict.RETRY;
            }
            default -> {
                // SOURCE_PURGED, and only reachable through a ledger mark.
            }
        }

        java.util.Optional<HistoricalEntitySnapshot> historical;
        try {
            historical = HistoricalEntitySnapshot.from(latest.snapshot(), obligation,
                    obligation.target(), evidence);
        } catch (IllegalArgumentException refused) {
            historical = java.util.Optional.empty();
        }
        if (historical.isEmpty()) {
            // The type refused it — a snapshot that cannot reconstruct the entity, or evidence
            // that does not authorise a tombstone. Only the first is terminal, and the two are
            // told apart by whether the snapshot is actually short.
            return latest.snapshot().hasAll(LineageHistoricalEntityFactory
                    .mandatoryAttributes(obligation.endpointKind()))
                    ? Verdict.RETRY : Verdict.SNAPSHOT_INCOMPLETE;
        }

        LineageHistoricalPublishMachine.Verdict published;
        try {
            published = historicalMachine.publish(obligation, historical.get(),
                    latest.provenance(), LineageHistoricalEntityFactory
                            .mandatoryAttributes(obligation.endpointKind()));
        } catch (RuntimeException e) {
            logger.warn("The historical publish machine failed: {}",
                    e.getClass().getSimpleName());
            return Verdict.RETRY;
        }
        return switch (published) {
            case RESOLVED_PURGED -> Verdict.RESOLVED;
            case SNAPSHOT_INCOMPLETE -> Verdict.SNAPSHOT_INCOMPLETE;
            // COMPENSATING, COMPENSATED, SUPERSEDED and RETRY all mean the obligation is not
            // finished. None of them is terminal.
            default -> Verdict.RETRY;
        };
    }

    /**
     * A kind NemakiWare never destroys: materialise what the event observed.
     *
     * <p>No source lookup, because there is no purge to establish — and asking would invite
     * treating a failed lookup as evidence of one. The claim is only that a durable event saw
     * this endpoint.
     */
    private Verdict settleObserved(LineageCatalogObligation obligation,
            LineageWaitingSnapshotResolver.Resolution.LatestWaitingSnapshot latest) {
        if (observedMaterializer == null) {
            return Verdict.RETRY;
        }
        ObservedEntitySnapshot observed;
        try {
            observed = new ObservedEntitySnapshot(latest.snapshot(), obligation.taskKey());
        } catch (IllegalArgumentException refused) {
            // The type refused it: wrong policy, wrong subject, unverified evidence. None of
            // those improve by retrying, but none of them is the snapshot being short either —
            // INDETERMINATE keeps the obligation open and visible.
            logger.warn("An observed snapshot was refused for a {} obligation",
                    obligation.endpointKind());
            return Verdict.INDETERMINATE;
        }
        LineageObservedEntityMaterializer.Outcome outcome;
        try {
            outcome = observedMaterializer.materialize(observed);
        } catch (RuntimeException e) {
            logger.warn("Observed materialisation failed: {}", e.getClass().getSimpleName());
            return Verdict.RETRY;
        }
        return switch (outcome) {
            // Only these two mean the catalog holds the right entity, confirmed by a read.
            case MATCHED, MATERIALIZED -> Verdict.RESOLVED;
            case SNAPSHOT_INCOMPLETE -> Verdict.SNAPSHOT_INCOMPLETE;
            // CONFLICT is not terminal: something else owns that name, and a later pass may
            // find it corrected. RETRYABLE is not terminal by definition.
            default -> Verdict.RETRY;
        };
    }
}
