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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The order in which a projection enters, holds and leaves the catalog wait.
 *
 * <h2>Why the ordering is the whole point</h2>
 *
 * <p>Every dangerous state here is a partial one. A row that reaches
 * {@code WAITING_FOR_CATALOG} after only some of its obligations were created resumes when
 * those few are answered, and publishes an event whose other endpoints still have no catalog
 * entity. A row that stores its keys in a second write can crash between the two and become
 * unresumable. So the sequence is fixed:
 *
 * <ol>
 *   <li>probe <em>every</em> endpoint, before any claim and before any publish;</li>
 *   <li>compute the deterministic task key for every ABSENT or UNKNOWN one;</li>
 *   <li>create every obligation and read each one back;</li>
 *   <li>store the complete key set and the status in one CAS.</li>
 * </ol>
 *
 * <p>Stopping anywhere before the last step leaves the row {@code PENDING}, which is correct
 * and self-healing: the keys are a pure function of the endpoints, so the next pass recomputes
 * exactly the same set and fills in whatever is missing. Nothing is lost by stopping early, and
 * that is what makes the crash boundaries safe rather than merely unlikely.
 *
 * <h2>Why the probe runs before the claim</h2>
 *
 * <p>The frozen §8-b table reaches the waiting state from {@code PENDING}, and the lifecycle
 * contract says a waiting row carries no claim bundle. Probing first means a row that turns out
 * to be waiting never took a claim at all — so no retry is consumed by a wait, and there is no
 * window in which a row is both waiting and claimed.
 */
public final class LineageCatalogWaitCoordinator {

    private static final Logger logger =
            LoggerFactory.getLogger(LineageCatalogWaitCoordinator.class);

    /** What the projector should do with this row, right now. */
    public sealed interface Decision {

        /** Nothing is owed. Claim and publish as usual. */
        record Proceed() implements Decision { }

        /**
         * The row is now {@code WAITING_FOR_CATALOG} and the walk must stop at it.
         *
         * @param taskKeys what it waits for, deduped and sorted
         */
        record Waiting(List<String> taskKeys) implements Decision { }

        /**
         * Nothing was established, so nothing was changed.
         *
         * <p>Distinct from waiting: the row is still {@code PENDING} and the walk halts without
         * a durable record of why. The next pass repeats the work from the beginning.
         */
        record Halt(String why) implements Decision { }
    }

    private final LineageObligationProjectorCollaborator obligations;
    private final LineageV2TransitionStore transitions;
    private final LongSupplier clockMs;
    private final long maxWaitAgeMs;

    public LineageCatalogWaitCoordinator(LineageObligationProjectorCollaborator obligations,
            LineageV2TransitionStore transitions, LongSupplier clockMs, long maxWaitAgeMs) {
        this.obligations = obligations;
        this.transitions = transitions;
        this.clockMs = clockMs;
        this.maxWaitAgeMs = maxWaitAgeMs;
    }

    /**
     * Steps 1–4, for a row that is {@code PENDING}.
     *
     * @param endpoints every endpoint of the event — inputs and outputs together, because an
     *        input whose catalog entity is missing breaks the lineage edge just as surely
     */
    public Decision beforePublish(String recordId, String target, String repositoryId,
            List<LineageEndpoint> endpoints) {
        if (obligations == null) {
            // No collaborator wired: this node cannot owe an obligation, and pretending it can
            // proceed would publish edges into a catalog nobody probed.
            return new Decision.Halt("no obligation collaborator is wired");
        }
        if (endpoints == null || endpoints.isEmpty()) {
            return new Decision.Proceed();
        }

        // Step 1 and 2. Every endpoint is probed even after the first one owes something: the
        // waiting set has to be complete, and stopping at the first miss would store a set that
        // resumes too early.
        Set<String> taskKeys = new LinkedHashSet<>();
        List<String> unprobed = new ArrayList<>();
        for (LineageEndpoint endpoint : endpoints) {
            Optional<String> owed;
            try {
                owed = obligations.requireCatalogEntity(target, repositoryId, endpoint.kind(),
                        endpoint.catalogQualifiedName());
            } catch (RuntimeException e) {
                // One endpoint that could not be asked makes the whole set unknown. Proceeding
                // would publish it; waiting on a partial set would resume without it.
                unprobed.add(endpoint.kind().name());
                continue;
            }
            owed.ifPresent(taskKeys::add);
        }
        if (!unprobed.isEmpty()) {
            // Kinds only — a qualified name can carry an external asset's stable key.
            return new Decision.Halt("could not establish catalog presence for "
                    + unprobed.size() + " endpoint(s) of kind " + unprobed);
        }
        if (taskKeys.isEmpty()) {
            return new Decision.Proceed();
        }

        // Step 3: every obligation must be readable from the store before the row is allowed to
        // depend on it. createIfAbsent's return value came from the write path; a read-back is
        // what proves the document a later pass will look for actually exists.
        List<String> confirmed = new ArrayList<>();
        for (String taskKey : taskKeys) {
            boolean durable;
            try {
                durable = obligations.isDurable(taskKey);
            } catch (RuntimeException e) {
                durable = false;
            }
            if (!durable) {
                // Stop before the CAS. The row stays PENDING and the next pass recomputes the
                // same deterministic keys — nothing is lost, and no partial set is stored.
                return new Decision.Halt("obligation " + shortKey(taskKey)
                        + " could not be confirmed durable");
            }
            confirmed.add(taskKey);
        }

        // Step 4: one CAS carries the status and the complete set.
        boolean entered;
        try {
            entered = transitions.enterCatalogWait(recordId, target, confirmed);
        } catch (RuntimeException e) {
            return new Decision.Halt("the catalog wait transition failed: "
                    + e.getClass().getSimpleName());
        }
        if (!entered) {
            // Another writer moved the row. Not a wait — the walk stops and re-reads.
            return new Decision.Halt("another writer moved the row before the wait was stored");
        }
        return new Decision.Waiting(List.copyOf(confirmed));
    }

    /** What a waiting row should do now. */
    public sealed interface WaitOutcome {

        /** Every obligation resolved; the row is back to {@code PENDING}. */
        record Resumed() implements WaitOutcome { }

        /** Still waiting, or nothing could be established. Nothing changed. */
        record Holding(String why) implements WaitOutcome { }

        /** The row is terminal — for this event only. */
        record Terminal(String reason) implements WaitOutcome { }
    }

    /**
     * Steps for a row that is already {@code WAITING_FOR_CATALOG}.
     *
     * <p>Note what is <em>not</em> here: no claim, no publish, no verify, no retry increment,
     * no cursor advance. A waiting row is not being worked on, and every one of those would
     * either consume a budget it should not or move a row that is not ready.
     */
    public WaitOutcome whileWaiting(String recordId, String target,
            LineageTargetLifecycle lifecycle) {
        if (lifecycle == null || lifecycle.status() != LineagePublishStatus.WAITING_FOR_CATALOG) {
            return new WaitOutcome.Holding("not waiting");
        }
        List<String> taskKeys = lifecycle.waitingTaskKeys();
        if (taskKeys == null || taskKeys.isEmpty()) {
            // Should be impossible — the record refuses it — but a row written by an older
            // build would be unresumable, and expiring it would be inventing a verdict.
            return new WaitOutcome.Holding("the waiting row carries no task keys");
        }
        if (obligations == null) {
            return new WaitOutcome.Holding("no obligation collaborator is wired");
        }

        LineageCatalogObligationService.Verdict verdict;
        try {
            verdict = obligations.verdictFor(taskKeys);
        } catch (RuntimeException e) {
            return new WaitOutcome.Holding("the obligation verdict could not be read");
        }
        switch (verdict.kind()) {
            case ALL_RESOLVED -> {
                boolean resumed = transitions.resumeFromCatalogWait(recordId, target);
                // A lost CAS is not a failure: someone else resumed it, or it moved on. Either
                // way this pass changes nothing more.
                return resumed ? new WaitOutcome.Resumed()
                        : new WaitOutcome.Holding("another writer moved the row first");
            }
            case TERMINAL_UNRESOLVED -> {
                return expire(recordId, target, "CATALOG_OBLIGATION_UNRESOLVED",
                        "an obligation ended SNAPSHOT_INCOMPLETE: " + verdict.reason());
            }
            case INDETERMINATE -> {
                // Fail-closed and explicitly separate from expiry: nothing was established, so
                // the wait is neither over nor too old. Ageing out on INDETERMINATE would turn
                // an unreadable store into a terminal verdict on the event.
                return new WaitOutcome.Holding("indeterminate: " + verdict.reason());
            }
            case WAITING -> {
                Long since = lifecycle.waitingSinceMs();
                if (since != null && maxWaitAgeMs > 0
                        && clockMs.getAsLong() - since >= maxWaitAgeMs) {
                    // The EVENT gives up. The obligation is shared with every other event
                    // waiting on the same catalog entity and is deliberately left alone.
                    return expire(recordId, target, "CATALOG_WAIT_EXPIRED",
                            "waited past the configured maximum; the shared obligation is"
                                    + " untouched and other events continue to wait");
                }
                return new WaitOutcome.Holding("waiting on " + taskKeys.size() + " obligation(s)");
            }
            default -> {
                return new WaitOutcome.Holding("unrecognised verdict");
            }
        }
    }

    private WaitOutcome expire(String recordId, String target, String reason, String detail) {
        boolean expired;
        try {
            expired = transitions.expireCatalogWait(recordId, target,
                    new LineageTargetLifecycle.TerminalReason(reason, detail,
                            clockMs.getAsLong()));
        } catch (RuntimeException e) {
            logger.warn("Catalog wait expiry failed for {}: {}", recordId,
                    e.getClass().getSimpleName());
            return new WaitOutcome.Holding("the expiry transition failed");
        }
        return expired ? new WaitOutcome.Terminal(reason)
                : new WaitOutcome.Holding("another writer moved the row first");
    }

    /** Log-safe: a task key is a digest, but the whole of it is still an identifier. */
    private static String shortKey(String taskKey) {
        return taskKey == null || taskKey.length() < 12 ? "?" : taskKey.substring(0, 12);
    }
}
