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
    private final LineageObservedEntityMaterializerRegistry observedMaterializers;
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
            LineageObservedEntityMaterializerRegistry observedMaterializers,
            LineageSourceDispositionRegistry sourceResolvers) {
        this.waitingSnapshotResolver = waitingSnapshotResolver;
        this.historicalMachine = historicalMachine;
        this.observedMaterializers = observedMaterializers;
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
    public LineageObservedEntityMaterializerRegistry observedMaterializersRef() {
        return observedMaterializers;
    }

    /** Corruption counts by fixed reason. A copy; callers cannot reset the machine's view. */
    public Map<String, Long> corruptionCounts() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        corruptionCounts.forEach((reason, count) -> snapshot.put(reason, count.sum()));
        return snapshot;
    }

    @Override
    public LineageAbsencePlan prepare(LineageCatalogObligation obligation) {
        if (obligation == null || waitingSnapshotResolver == null) {
            return new LineageAbsencePlan.NoWrite.Retry("no obligation or resolver");
        }
        var classified = LineagePurgeLifecyclePolicy.of(obligation.endpointKind());
        if (classified.isEmpty()) {
            // A kind nobody has established either destroys its source or does not. Neither
            // branch is safe, and readiness already refuses to activate over it.
            return new LineageAbsencePlan.NoWrite.Indeterminate("unclassified purge lifecycle");
        }

        LineageWaitingSnapshotResolver.Resolution resolution =
                waitingSnapshotResolver.resolve(obligation);
        if (resolution instanceof LineageWaitingSnapshotResolver.Resolution.Corrupt corrupt) {
            // Counted, not terminalised: other events wait on this same entity, and one
            // unreadable row is no verdict on theirs.
            corruptionCounts.computeIfAbsent(corrupt.reason(), r -> new LongAdder()).increment();
            logger.warn("A waiting row for an obligation is corrupt: {}", corrupt.reason());
            return new LineageAbsencePlan.NoWrite.Indeterminate(corrupt.reason());
        }
        if (!(resolution
                instanceof LineageWaitingSnapshotResolver.Resolution.LatestWaitingSnapshot
                        latest)) {
            return new LineageAbsencePlan.NoWrite.Indeterminate("no usable waiting snapshot");
        }

        return classified.get().policy() == LineagePurgeLifecyclePolicy.LEDGERED
                ? planLedgered(obligation, latest)
                : planObserved(obligation, latest);
    }

    @Override
    public Verdict execute(LineageAbsencePlan plan) {
        if (plan == null) {
            return Verdict.RETRY;
        }
        // Consumed as it was decided. The route is not re-derived here: it was chosen before
        // the caller renewed the claim, and choosing again would reopen that window.
        return switch (plan) {
            case LineageAbsencePlan.NoWrite.Retry ignored -> Verdict.RETRY;
            case LineageAbsencePlan.NoWrite.Indeterminate ignored -> Verdict.INDETERMINATE;
            case LineageAbsencePlan.NoWrite.SnapshotIncomplete ignored ->
                    Verdict.SNAPSHOT_INCOMPLETE;
            case LineageAbsencePlan.HistoricalPurgedPlan historical ->
                    executeHistorical(historical);
            case LineageAbsencePlan.ObservedPlan observed -> executeObserved(observed);
            case LineageAbsencePlan.CurrentSourcePlan current -> executeCurrent(current);
        };
    }

    private Verdict executeHistorical(LineageAbsencePlan.HistoricalPurgedPlan plan) {
        if (historicalMachine == null) {
            return Verdict.RETRY;
        }
        LineageHistoricalPublishMachine.Verdict published;
        try {
            published = historicalMachine.publish(plan.obligation(), plan.historical(),
                    plan.provenance(), plan.mandatoryAttributes());
        } catch (RuntimeException e) {
            logger.warn("The historical publish machine failed: {}",
                    e.getClass().getSimpleName());
            return Verdict.RETRY;
        }
        return switch (published) {
            case RESOLVED_PURGED -> Verdict.RESOLVED_PURGED;
            case SNAPSHOT_INCOMPLETE -> Verdict.SNAPSHOT_INCOMPLETE;
            // COMPENSATING, COMPENSATED, SUPERSEDED and RETRY all mean unfinished.
            default -> Verdict.RETRY;
        };
    }

    private Verdict executeObserved(LineageAbsencePlan.ObservedPlan plan) {
        return materialize(plan.observed(), Verdict.RESOLVED_OBSERVED);
    }

    private Verdict executeCurrent(LineageAbsencePlan.CurrentSourcePlan plan) {
        if (sourceResolvers == null) {
            return Verdict.RETRY;
        }
        // The verdict that built this plan was read before the renewal. In between the source
        // can be purged, re-created or modified — so it is taken again here, immediately
        // before the catalog is touched, and the plan's own authorisation is rechecked against
        // it. Not a re-decision of the route: a disagreement writes nothing and the next pass
        // starts again from prepare.
        LineageSourceDispositionResolver.SourceEvidence recheck;
        try {
            recheck = sourceResolvers.dispositionOf(plan.current().snapshot().repositoryId(),
                    plan.current().snapshot().endpointKind(),
                    plan.current().snapshot().catalogQualifiedName());
        } catch (RuntimeException e) {
            logger.warn("A live-source re-check failed: {}", e.getClass().getSimpleName());
            return Verdict.RETRY;
        }
        if (!plan.current().stillAuthorised(recheck)) {
            // Zero catalog calls. The write was licensed by a verdict that no longer holds.
            return Verdict.RETRY;
        }
        // The same materialisation shape — pre-read, publish, exact post-read.
        return materializeCurrent(plan.current());
    }

    private Verdict materialize(ObservedEntitySnapshot observed, Verdict onSuccess) {
        LineageObservedEntityMaterializer materializer = materializerFor(observed.target());
        if (materializer == null) {
            return Verdict.RETRY;
        }
        LineageObservedEntityMaterializer.Outcome outcome;
        try {
            outcome = materializer.materialize(observed);
        } catch (RuntimeException e) {
            logger.warn("Observed materialisation failed: {}", e.getClass().getSimpleName());
            return Verdict.RETRY;
        }
        return switch (outcome) {
            case MATCHED, MATERIALIZED -> onSuccess;
            case SNAPSHOT_INCOMPLETE -> Verdict.SNAPSHOT_INCOMPLETE;
            // CONFLICT is not terminal: something else owns that name, and a later pass may
            // find it corrected.
            default -> Verdict.RETRY;
        };
    }

    private Verdict materializeCurrent(VerifiedCurrentEntitySnapshot current) {
        LineageObservedEntityMaterializer materializer = materializerFor(current.target());
        if (materializer == null) {
            return Verdict.RETRY;
        }
        LineageObservedEntityMaterializer.Outcome outcome;
        try {
            outcome = materializer.materializeCurrent(current);
        } catch (RuntimeException e) {
            logger.warn("Current materialisation failed: {}", e.getClass().getSimpleName());
            return Verdict.RETRY;
        }
        return switch (outcome) {
            case MATCHED, MATERIALIZED -> Verdict.RESOLVED_CURRENT;
            case SNAPSHOT_INCOMPLETE -> Verdict.SNAPSHOT_INCOMPLETE;
            default -> Verdict.RETRY;
        };
    }

    /**
     * The materializer for exactly the target the plan's own snapshot names.
     *
     * <p>The target comes from the snapshot, which derived its task key from it, so it is the
     * same target the obligation was claimed under rather than anything a caller supplied.
     *
     * <p>No fallback to "the only one registered". A node with one materializer and two
     * configured targets would otherwise write the second target's entities into the first
     * target's catalog and resolve the obligation, leaving the catalog its task key actually
     * names empty — and nothing downstream re-opens a resolved obligation. RETRY instead: the
     * absence is a wiring condition, correctable by configuration, and readiness names it.
     */
    private LineageObservedEntityMaterializer materializerFor(String target) {
        if (observedMaterializers == null) {
            return null;
        }
        LineageObservedEntityMaterializer materializer =
                observedMaterializers.materializerFor(target);
        if (materializer == null) {
            logger.warn("No observed-entity materializer is registered for the plan's target;"
                    + " nothing was written");
        }
        return materializer;
    }

    /**
     * A kind NemakiWare destroys: ask what the repository says, then act through the machine.
     *
     * <p>Nothing here decides PURGED. The authoritative resolver does, from a ledger mark the
     * destroying code wrote, and the historical machine owns the intent, the subject fence, the
     * read-back and the compensation. Publishing directly would skip all of them.
     */
    private LineageAbsencePlan planLedgered(LineageCatalogObligation obligation,
            LineageWaitingSnapshotResolver.Resolution.LatestWaitingSnapshot latest) {
        if (historicalMachine == null || sourceResolvers == null) {
            return new LineageAbsencePlan.NoWrite.Retry("no historical machine or resolver");
        }
        LineageSourceDispositionResolver.SourceEvidence evidence;
        try {
            evidence = sourceResolvers.dispositionOf(obligation.repositoryId(),
                    obligation.endpointKind(), obligation.catalogQualifiedName());
        } catch (RuntimeException e) {
            logger.warn("An authoritative source lookup failed: {}",
                    e.getClass().getSimpleName());
            return new LineageAbsencePlan.NoWrite.Retry("the source lookup failed");
        }
        if (evidence == null || evidence.disposition() == null) {
            return new LineageAbsencePlan.NoWrite.Retry("no source verdict");
        }
        switch (evidence.disposition()) {
            case SOURCE_EXISTS -> {
                // Not an infinite wait any more. The authoritative publisher may never run for
                // this subject, and the obligation would otherwise retry for ever on a source
                // sitting in the repository. A separate type demands the positive verdict.
                try {
                    return new LineageAbsencePlan.CurrentSourcePlan(
                            new VerifiedCurrentEntitySnapshot(latest.snapshot(), evidence,
                                    obligation.taskKey()));
                } catch (IllegalArgumentException refused) {
                    logger.warn("A live-source materialisation was refused for a {} obligation",
                            obligation.endpointKind());
                    return new LineageAbsencePlan.NoWrite.Indeterminate(
                            "the live-source snapshot was refused");
                }
            }
            case SOURCE_UNKNOWN -> {
                // Not established. Never a licence to write anything.
                return new LineageAbsencePlan.NoWrite.Retry("the source could not be established");
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
            return latest.snapshot().hasAll(LineageHistoricalEntityFactory
                    .mandatoryAttributes(obligation.endpointKind()))
                    ? new LineageAbsencePlan.NoWrite.Retry("the historical snapshot was refused")
                    : new LineageAbsencePlan.NoWrite.SnapshotIncomplete(
                            "the snapshot cannot reconstruct the entity");
        }
        return new LineageAbsencePlan.HistoricalPurgedPlan(obligation, historical.get(),
                latest.provenance(),
                LineageHistoricalEntityFactory.mandatoryAttributes(obligation.endpointKind()));
    }

    /**
     * A kind NemakiWare never destroys: materialise what the event observed.
     *
     * <p>No source lookup, because there is no purge to establish — and asking would invite
     * treating a failed lookup as evidence of one.
     */
    private LineageAbsencePlan planObserved(LineageCatalogObligation obligation,
            LineageWaitingSnapshotResolver.Resolution.LatestWaitingSnapshot latest) {
        try {
            return new LineageAbsencePlan.ObservedPlan(
                    new ObservedEntitySnapshot(latest.snapshot(), obligation.taskKey()));
        } catch (IllegalArgumentException refused) {
            logger.warn("An observed snapshot was refused for a {} obligation",
                    obligation.endpointKind());
            return new LineageAbsencePlan.NoWrite.Indeterminate(
                    "the observed snapshot was refused");
        }
    }
}
