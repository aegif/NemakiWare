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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Whether the obligation machine is actually assembled on this node — structurally.
 *
 * <h2>The false-green this closes</h2>
 *
 * <p>{@code catalog:obligations} is a <em>static</em> capability: it says the code is in the
 * binary. Nothing said the code was wired. A node could therefore satisfy the barrier's
 * condition 8 and a green D-rest readiness while having no store, no probe for a configured
 * target, and no publisher — and would discover that the moment v2 writes opened, which is
 * precisely what 4b being a flag flip forbids.
 *
 * <h2>Presence is not enough</h2>
 *
 * <p>A null check would pass a deployment where the scanner drives one service instance and the
 * projector another: each half looks wired, and obligations resolved by one are invisible to the
 * other. So the collaborators are typed, they expose the instance they use, and this compares
 * them by identity. Same for the service's store — a service reading a different store than the
 * one registered here is a machine whose two halves address different documents.
 *
 * <h2>Why it does not ask the service whether it is active</h2>
 *
 * <p>{@code LineageCatalogObligationService.active()} reads readiness. If readiness asked the
 * service back, the two would recurse. The dependency direction is fixed:
 *
 * <pre>
 *   readiness → wiring descriptor
 *   service   → readiness
 *   scanner / projector → service
 * </pre>
 *
 * <p>Everything this class calls is an identity accessor that reads no gate and does no work.
 * That also makes the check meaningful while D-rest is off, which is when an operator most
 * wants to know whether the flip would land on a wired node.
 */
public final class LineageObligationWiring {

    private final LineageCatalogObligationStore store;
    private final LineageCatalogProbeRegistry probes;
    private final LineageHistoricalPublisherRegistry historicalPublishers;
    private final LineageCatalogObligationService service;
    private final LineageObligationScanner scanner;
    private final LineageObligationProjectorCollaborator projectorCollaborator;
    private final LineageHistoricalPublishIntentStore intentStore;
    private final LineageHistoricalCompensationStore compensationStore;
    private final LineageHistoricalPublishMachine historicalMachine;
    private final LineageSourceDispositionRegistry sourceResolvers;
    private final LineageCurrentEntityRepublisher republisher;
    private final LineageOperationBudgetProvider budgets;

    public LineageObligationWiring(LineageCatalogObligationStore store,
            LineageCatalogProbeRegistry probes,
            LineageHistoricalPublisherRegistry historicalPublishers,
            LineageCatalogObligationService service,
            LineageObligationScanner scanner,
            LineageObligationProjectorCollaborator projectorCollaborator,
            LineageHistoricalPublishIntentStore intentStore,
            LineageHistoricalCompensationStore compensationStore,
            LineageHistoricalPublishMachine historicalMachine,
            LineageSourceDispositionRegistry sourceResolvers,
            LineageCurrentEntityRepublisher republisher,
            LineageOperationBudgetProvider budgets) {
        this.store = store;
        this.probes = probes;
        this.historicalPublishers = historicalPublishers;
        this.service = service;
        this.scanner = scanner;
        this.projectorCollaborator = projectorCollaborator;
        this.intentStore = intentStore;
        this.compensationStore = compensationStore;
        this.historicalMachine = historicalMachine;
        this.sourceResolvers = sourceResolvers;
        this.republisher = republisher;
        this.budgets = budgets;
    }

    /**
     * The margin between the slowest fenced section and the fence that guards it.
     *
     * <p>Half the lease. A section that ran to its full budget would then still have half the
     * lease left to renew in — and a renewal that has to win a race against its own expiry is
     * not a renewal.
     */
    static final double FENCE_SAFETY_FACTOR = 0.5;

    /**
     * What is missing or inconsistent, named. Empty means assembled.
     *
     * @param configuredTargets the targets this node publishes lineage to
     */
    public List<String> violations(Set<String> configuredTargets) {
        List<String> violations = new ArrayList<>();
        if (store == null) {
            violations.add("no catalog obligation store is wired");
        }
        if (service == null) {
            violations.add("no catalog obligation service is wired");
        }
        if (probes == null) {
            violations.add("no catalog probe registry is wired");
        }
        if (historicalPublishers == null) {
            violations.add("no historical entity publisher registry is wired");
        }
        if (intentStore == null) {
            // Without it the machine cannot record an intent before publishing, which is the
            // one thing that makes a crash during publication recoverable.
            violations.add("no historical publish intent store is wired");
        }
        if (compensationStore == null) {
            // Without it a wrong historical entity is never revisited.
            violations.add("no historical compensation store is wired");
        }
        if (historicalMachine == null) {
            violations.add("no historical publish machine is wired");
        }
        if (republisher == null) {
            // A compensation that cannot converge on the current entity is a record of a
            // problem rather than a fix for one.
            violations.add("no current-entity republisher is wired");
        }
        violations.addAll(collaboratorViolations());
        violations.addAll(targetViolations(configuredTargets));
        violations.addAll(kindViolations());
        violations.addAll(budgetViolations(configuredTargets));
        return violations;
    }

    /**
     * Every configured target and endpoint kind must have a budget that fits inside the fence.
     *
     * <h2>What is being budgeted</h2>
     *
     * <p>Not one HTTP request — the whole fenced critical section: the authoritative source
     * re-check, the historical publish, the read-back that confirms it, each with its connect
     * and read timeouts, its retries and the total sleep between them, plus the client's own
     * overhead. The earlier check compared a single read timeout against the lease, which passed
     * configurations whose section takes several times as long as it is allowed to.
     *
     * <h2>Why per target and per kind</h2>
     *
     * <p>Atlas and Purview are configured independently, and a section's cost also depends on
     * the kind, because each kind's authoritative source resolver talks to a different backend.
     * A single number could only be right for one combination and would be a guess for the rest.
     *
     * <h2>Read now, not at startup</h2>
     *
     * <p>The provider is asked on every evaluation, so a timeout an administrator raises past
     * the lease turns the gate red at the next readiness call rather than at the next restart.
     */
    private List<String> budgetViolations(Set<String> configuredTargets) {
        List<String> violations = new ArrayList<>();
        Set<String> targets = configuredTargets == null ? Set.of() : configuredTargets;
        if (targets.isEmpty()) {
            // Nothing is published, so no section is fenced. Consistent with targetViolations.
            return violations;
        }
        if (budgets == null) {
            violations.add("no operation budget provider is wired, so no target can be shown to"
                    + " finish inside the subject fence lease");
            return violations;
        }
        long fenceLeaseMs = LineageHistoricalPublishMachine.INTENT_LEASE.toMillis();
        long safetyMarginMs = (long) (fenceLeaseMs * FENCE_SAFETY_FACTOR);
        for (String target : targets) {
            for (EndpointKind kind : EndpointKind.values()) {
                java.util.Optional<LineageOperationBudget> resolved;
                try {
                    resolved = budgets.budgetFor(target, kind);
                } catch (RuntimeException unreadable) {
                    // An exception is not a small budget. Fail closed, and do not echo the
                    // message: configuration errors can carry endpoints and credentials.
                    resolved = java.util.Optional.empty();
                }
                if (resolved == null || resolved.isEmpty()) {
                    violations.add("no operation budget is resolvable for target '" + target
                            + "' kind " + kind + ", so its fenced section cannot be shown to"
                            + " finish inside the subject fence lease");
                    continue;
                }
                LineageOperationBudget budget = resolved.get();
                if (!budget.bounded()) {
                    violations.add("the operation budget for target '" + target + "' kind "
                            + kind + " is not bounded (unknown or unlimited retries cannot be"
                            + " budgeted)");
                    continue;
                }
                if (!budget.fitsInside(fenceLeaseMs, safetyMarginMs)) {
                    long worst = budget.worstCaseMs();
                    violations.add("the worst-case fenced section for target '" + target
                            + "' kind " + kind + " ("
                            + (worst == Long.MAX_VALUE ? "unbounded" : worst + "ms")
                            + ") plus the safety margin (" + safetyMarginMs
                            + "ms) does not fit inside the subject fence lease (" + fenceLeaseMs
                            + "ms): a publish may still be in flight when another intent takes"
                            + " the subject");
                }
            }
        }
        return violations;
    }

    /**
     * An authoritative source resolver for every kind an endpoint can be.
     *
     * <p>A kind with no resolver can never reach {@code SOURCE_PURGED}, so its obligations
     * retry for ever. Named per kind because partial coverage is the dangerous case — some
     * endpoints work and the rest silently never finish.
     */
    private List<String> kindViolations() {
        List<String> violations = new ArrayList<>();
        if (sourceResolvers == null) {
            violations.add("no authoritative source disposition registry is wired");
            return violations;
        }
        for (EndpointKind kind : EndpointKind.values()) {
            if (!sourceResolvers.canResolve(kind)) {
                violations.add("no authoritative source resolver is wired for " + kind);
            }
        }
        return violations;
    }

    /**
     * The halves must drive one machine.
     *
     * <p>A scanner resolving obligations in one service while the projector waits on another is
     * the failure a presence check cannot see: nothing is null, and nothing works.
     */
    private List<String> collaboratorViolations() {
        List<String> violations = new ArrayList<>();
        if (scanner == null) {
            violations.add("no obligation scanner/reclaimer is wired");
        } else if (service != null && scanner.service() != service) {
            violations.add("the obligation scanner drives a different service instance than the"
                    + " one readiness knows about — obligations it resolves would be invisible"
                    + " to the projector");
        }
        if (projectorCollaborator == null) {
            violations.add("the projector is not wired to the obligation service");
        } else if (service != null && projectorCollaborator.service() != service) {
            violations.add("the projector's obligation collaborator uses a different service"
                    + " instance than the one readiness knows about");
        }
        if (service != null && store != null && service.storeRef() != store) {
            violations.add("the obligation service reads a different store than the one wired"
                    + " here — the two halves would address different documents");
        }
        return violations;
    }

    /**
     * One probe and one publisher per configured target, resolved exactly.
     *
     * <p>An empty registry is allowed only when there is nothing to publish to. Otherwise every
     * target is named individually, because partial coverage is the dangerous case: one target
     * works and the other silently cannot, and an aggregate "some probes exist" would pass.
     */
    private List<String> targetViolations(Set<String> configuredTargets) {
        List<String> violations = new ArrayList<>();
        Set<String> targets = configuredTargets == null ? Set.of() : configuredTargets;
        if (targets.isEmpty()) {
            // Not a violation: a node with no lineage targets owes nothing. Stated explicitly
            // so the empty case is a decision rather than a loop that happened not to run.
            return violations;
        }
        for (String target : targets) {
            if (probes == null || !probes.canProbe(target)) {
                violations.add("no catalog probe is wired for target '" + target + "'");
            }
            if (historicalPublishers == null || !historicalPublishers.canPublish(target)) {
                // Without this, a purged source's obligation can never leave PENDING — the
                // consumer would retry a source that is never coming back.
                violations.add("no historical entity publisher is wired for target '"
                        + target + "'");
            }
        }
        return violations;
    }

    /** The service the scanner and the projector must both be using — identity, not equality. */
    public boolean sharesService(LineageCatalogObligationService other) {
        return service != null && service == other;
    }

    /** The store registered here. */
    public LineageCatalogObligationStore store() {
        return store;
    }
}
