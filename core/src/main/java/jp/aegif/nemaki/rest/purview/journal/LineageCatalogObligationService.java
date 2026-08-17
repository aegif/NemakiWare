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
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The obligation machine's moving parts: producer, consumer, reclaimer (§2).
 *
 * <h2>One gate, in one place</h2>
 *
 * <p>Everything here is inert while D-rest is off — no obligation is created, none is claimed,
 * the scanner does not run and nothing is reclaimed. The gate is {@link LineageDrestReadiness}
 * and it is consulted in exactly one method, because a gate repeated at five call sites is a
 * gate that will one day be forgotten at the sixth.
 *
 * <p>Distributed inactive on purpose. 4b is a flag flip: the node that will meet an unready
 * catalog entity the moment v2 writes open has to be carrying this code already, not receive it
 * afterwards.
 *
 * <h2>What the producer decides</h2>
 *
 * <p>{@code PRESENT} → proceed. {@code ABSENT} and {@code UNKNOWN} → owe an obligation. The two
 * are the same decision here and different ones for the consumer: only {@code ABSENT} can be
 * answered by building a historical entity, while {@code UNKNOWN} has established nothing and
 * is retried.
 */
public class LineageCatalogObligationService {

    private static final Logger logger =
            LoggerFactory.getLogger(LineageCatalogObligationService.class);

    /** How long a worker holds an obligation before it may be reclaimed. */
    static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    /**
     * Capped exponential backoff between retries of one obligation.
     *
     * <p>Durable, not in-memory: the schedule lives on the document, so a restart during a
     * catalog outage does not reset every obligation to "try now" and turn a recovering
     * catalog into a hammered one.
     *
     * <p>There is no attempt ceiling that terminates. Retrying forever is correct here —
     * {@code ABSENT} means the authoritative publisher has not run yet, and no number of
     * failed checks makes that permanent. Only the historical builder can conclude a snapshot
     * cannot be rebuilt.
     */
    static final long BACKOFF_BASE_MS = 5_000L;
    static final long BACKOFF_MAX_MS = 300_000L;

    private final LineageCatalogObligationStore store;
    private final LineageCatalogEntityProbe probe;
    private final LineageDrestReadiness readiness;
    private final LineageNodeIdentity identity;
    private final LongSupplier clockMs;

    public LineageCatalogObligationService(LineageCatalogObligationStore store,
            LineageCatalogEntityProbe probe, LineageDrestReadiness readiness,
            LineageNodeIdentity identity, LongSupplier clockMs) {
        this.store = store;
        this.probe = probe;
        this.readiness = readiness;
        this.identity = identity;
        this.clockMs = clockMs;
    }

    /**
     * The store this service reads — an identity accessor for the wiring check.
     *
     * <p>Package-private and deliberately not a getter anyone else can reach: it exists so
     * {@link LineageObligationWiring} can establish that the service and the registered store
     * are the same object, without reflection and without reading any gate. Calling
     * {@link #active()} from there would recurse through readiness.
     */
    LineageCatalogObligationStore storeRef() {
        return store;
    }

    /**
     * The probe this service uses. Same purpose as {@link #storeRef()}.
     */
    LineageCatalogEntityProbe probeRef() {
        return probe;
    }

    /**
     * The single gate. Every public method starts here.
     *
     * <p>Reads readiness rather than the raw flag: a node whose sequencer, spool or views are
     * not actually usable must not start parking projections against a machine that cannot
     * drain them.
     */
    public boolean active() {
        if (store == null || probe == null || readiness == null || identity == null) {
            return false;
        }
        return readiness.evaluate().ready();
    }

    // ------------------------------------------------------------------
    // Producer
    // ------------------------------------------------------------------

    /**
     * The pre-publish check: may this endpoint be projected, or is an obligation owed?
     *
     * @return empty if the projection may proceed; otherwise the task key to wait on
     */
    public Optional<String> requireCatalogEntity(String target, String repositoryId,
            EndpointKind kind, String catalogQualifiedName) {
        if (!active()) {
            // Inert: no obligation is created, and the caller is told nothing is owed. With
            // D-rest off there is no v2 projection to park, so this cannot hide a real wait.
            return Optional.empty();
        }
        LineageCatalogEntityProbe.Presence presence;
        try {
            presence = probe.presenceOf(target, repositoryId, kind, catalogQualifiedName);
        } catch (RuntimeException e) {
            // A probe that threw has established nothing. Owing an obligation is the
            // conservative answer; publishing would assert an entity nobody confirmed.
            logger.warn("Catalog probe failed for a {} endpoint: {}",
                    kind, e.getClass().getSimpleName());
            presence = LineageCatalogEntityProbe.Presence.UNKNOWN;
        }
        if (presence == LineageCatalogEntityProbe.Presence.PRESENT) {
            return Optional.empty();
        }

        String taskKey =
                LineageCatalogObligation.taskKey(target, repositoryId, kind, catalogQualifiedName);
        LineageCatalogObligation wanted = new LineageCatalogObligation(null, taskKey, target,
                repositoryId, kind, catalogQualifiedName,
                LineageCatalogObligation.State.PENDING, null, null, 0L, 0L, 0,
                clockMs.getAsLong(), LineageCatalogObligation.Outcome.NONE, null, null);

        LineageCatalogObligation stored = store.createIfAbsent(wanted);
        if (stored.state() == LineageCatalogObligation.State.RESOLVED) {
            // Someone resolved it between the probe and here. Nothing is owed after all —
            // returning the key would park a projection that could run now.
            return Optional.empty();
        }
        return Optional.of(stored.taskKey());
    }

    /**
     * The ABSENT branch, when one is wired.
     *
     * <p>Settable rather than constructor-injected: the settler needs this service's own store
     * and clock, so the two cannot both be constructor arguments of each other. Readiness
     * compares this reference with the registered bean by identity, which is what stops a
     * deployment driving two different settlers.
     */
    private volatile LineageCatalogAbsenceSettler absenceSettler;

    public void setAbsenceSettler(LineageCatalogAbsenceSettler absenceSettler) {
        this.absenceSettler = absenceSettler;
    }

    /** The settler this service uses. Identity only; readiness never calls through it. */
    public LineageCatalogAbsenceSettler settlerRef() {
        return absenceSettler;
    }

    /**
     * Whether the obligation is readable from the store — a read-back, not a write result.
     *
     * <p>{@code createIfAbsent} returns what the write path produced. That is not the same
     * statement as "a later pass will find this document": a create that raced, or a write that
     * was accepted and then lost, both return a value. A projection is only allowed to depend
     * on an obligation whose document has actually been read.
     *
     * <p>False on any failure, including a store that could not be read. The caller's response
     * to false is to change nothing and recompute the same deterministic key next pass, which
     * is safe under both readings.
     */
    public boolean isDurable(String taskKey) {
        if (taskKey == null || taskKey.isBlank() || store == null) {
            return false;
        }
        try {
            return store.read(taskKey).isPresent();
        } catch (RuntimeException e) {
            logger.warn("Obligation read-back failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Consumer
    // ------------------------------------------------------------------

    /** What one pass of the consumer did. Counts only. */
    public record Pass(int claimed, int resolved, int released, int gaveUp, int reclaimed) {

        public static final Pass INERT = new Pass(0, 0, 0, 0, 0);

        public boolean idle() {
            return claimed == 0 && reclaimed == 0;
        }
    }

    /**
     * Claims up to {@code limit} pending obligations, checks each, and records the answer.
     *
     * <p>Bounded, so one pass is a unit of work rather than a run that ends when the backlog
     * does.
     */
    public Pass runOnce(int limit) {
        if (!active()) {
            return Pass.INERT;
        }
        long now = clockMs.getAsLong();
        int reclaimed = store.reclaimExpired(Math.max(1, limit), now);

        int claimed = 0;
        int resolved = 0;
        int released = 0;
        int gaveUp = 0;
        // findClaimable, not findByState: an obligation serving its backoff must not be
        // asked about at all. Filtering after the query would still have called the catalog.
        for (LineageCatalogObligation pending : store.findClaimable(Math.max(1, limit), now)) {
            Optional<LineageCatalogObligationStore.Claim> claim =
                    store.claim(pending.taskKey(), identity.nodeId(), DEFAULT_LEASE, now);
            if (claim.isEmpty()) {
                continue; // someone else has it; that is the ordinary case, not a failure
            }
            claimed++;
            switch (settle(claim.get(), pending)) {
                case RESOLVED -> resolved++;
                case RELEASED -> released++;
                case GAVE_UP -> gaveUp++;
                default -> { }
            }
        }
        return new Pass(claimed, resolved, released, gaveUp, reclaimed);
    }

    private enum Settlement { RESOLVED, RELEASED, GAVE_UP, LOST }

    /**
     * Checks the catalog once and records what it said.
     *
     * <p>Every branch ends in a store call whose token must still hold the obligation, so a
     * worker whose lease expired mid-check cannot land its answer on top of the worker that
     * took over.
     */
    private Settlement settle(LineageCatalogObligationStore.Claim claim,
            LineageCatalogObligation obligation) {
        LineageCatalogEntityProbe.Presence presence;
        try {
            // The obligation's own target and repository: the verdict must come from the
            // catalog the task key names, never from whichever probe answered first.
            presence = probe.presenceOf(obligation.target(), obligation.repositoryId(),
                    obligation.endpointKind(), obligation.catalogQualifiedName());
        } catch (RuntimeException e) {
            presence = LineageCatalogEntityProbe.Presence.UNKNOWN;
        }
        return switch (presence) {
            case PRESENT -> store.resolve(claim,
                    LineageCatalogObligation.Outcome.SOURCE_EXISTS,
                    "the catalog holds the entity",
                    // Evidence is a digest, never the name: an external asset's qualified name
                    // contains its stable key, and evidence is read back in admin routes.
                    LineageEndpoint.shortDigest(obligation.catalogQualifiedName()))
                    ? Settlement.RESOLVED : Settlement.LOST;
            case ABSENT -> settleAbsent(claim, obligation);
            case UNKNOWN -> store.release(claim, "the catalog did not answer",
                    clockMs.getAsLong(), BACKOFF_BASE_MS, BACKOFF_MAX_MS)
                    ? Settlement.RELEASED : Settlement.LOST;
        };
    }

    /**
     * The catalog does not hold the entity. Route it, but only under a claim still held.
     *
     * <h2>Renew immediately before the external call, and use the renewed claim after</h2>
     *
     * <p>The snapshot and source lookups can take as long as a journal read and a repository
     * read together, and the claim's lease is running the whole time. A worker whose lease
     * expired during those lookups has already been superseded — writing to the catalog on its
     * way out would race the worker that took over, and settling on the old token would land its
     * answer on top of that worker's.
     *
     * <p>So the renewal is the authorisation, not a formality: without it nothing external is
     * called at all, and the renewed claim is what the final CAS uses. A failed renew, a stale
     * claim and a lost CAS are all reported as LOST — never as a settlement that happened.
     */
    private Settlement settleAbsent(LineageCatalogObligationStore.Claim claim,
            LineageCatalogObligation obligation) {
        LineageCatalogAbsenceSettler settler = this.absenceSettler;
        if (settler == null) {
            // Unwired: the old behaviour, which writes nothing. Readiness refuses to activate
            // a node in this state, so this is a safe interim rather than a supported one.
            return store.release(claim, "the catalog does not hold the entity yet",
                    clockMs.getAsLong(), BACKOFF_BASE_MS, BACKOFF_MAX_MS)
                    ? Settlement.RELEASED : Settlement.LOST;
        }
        // 1. Read-only. The route is decided here, while the lease may well expire.
        LineageAbsencePlan plan;
        try {
            plan = settler.prepare(obligation);
        } catch (RuntimeException e) {
            logger.warn("The catalog-absence settler failed to prepare: {}",
                    e.getClass().getSimpleName());
            plan = new LineageAbsencePlan.NoWrite.Retry("prepare failed");
        }

        // 2. Renew immediately before anything external, and only then. A worker whose lease
        // expired during the lookups has been superseded; writing on its way out would race
        // the worker that took over, and settling on the old token would land its answer on
        // top of that worker's.
        java.util.Optional<LineageCatalogObligationStore.Claim> renewed =
                store.renew(claim, DEFAULT_LEASE, clockMs.getAsLong());
        if (renewed.isEmpty()) {
            // Nothing external is called at all — not even for a plan that would have written.
            return Settlement.LOST;
        }
        LineageCatalogObligationStore.Claim held = renewed.get();

        LineageCatalogAbsenceSettler.Verdict verdict;
        try {
            verdict = settler.execute(plan);
        } catch (RuntimeException e) {
            logger.warn("The catalog-absence settler failed: {}", e.getClass().getSimpleName());
            verdict = LineageCatalogAbsenceSettler.Verdict.RETRY;
        }
        if (verdict.resolves()) {
            // The renewed claim, and the route's own outcome: a tombstone and an observation
            // must not leave the same durable record.
            return store.resolve(held, verdict.outcome(),
                    "the catalog now holds the entity this obligation owed",
                    LineageEndpoint.shortDigest(obligation.catalogQualifiedName()))
                    ? Settlement.RESOLVED : Settlement.LOST;
        }
        return switch (verdict) {
            case SNAPSHOT_INCOMPLETE -> recordSnapshotIncomplete(held,
                    "the snapshot cannot reconstruct the entity")
                    ? Settlement.GAVE_UP : Settlement.LOST;
            case RESOLVED_PURGED, RESOLVED_OBSERVED, RESOLVED_CURRENT -> Settlement.LOST;
            // RETRY and INDETERMINATE both leave the obligation open. They are different
            // statements about why, and the release reason keeps them distinguishable.
            default -> store.release(held,
                    verdict == LineageCatalogAbsenceSettler.Verdict.INDETERMINATE
                            ? "nothing could be established about the waiting event"
                            : "the catalog does not hold the entity yet",
                    clockMs.getAsLong(), BACKOFF_BASE_MS, BACKOFF_MAX_MS)
                    ? Settlement.RELEASED : Settlement.LOST;
        };
    }

    /**
     * Records that a snapshot cannot rebuild the entity. <b>Not a timeout API.</b>
     *
     * <p>A task key is shared by every event waiting for the same entity. An event that has
     * waited past {@code lineage.catalog-wait.max-age} may terminate <em>itself</em>
     * ({@code CATALOG_WAIT_EXPIRED}), but it must not terminate the obligation: time elapsed is
     * not evidence that a snapshot is incomplete, and doing so would terminate every other
     * event waiting on the same entity — including ones that had only just started waiting.
     *
     * <p>The only caller that may reach this is the historical-entity builder, which has
     * actually examined the snapshot and found it structurally short.
     *
     * @param reason names which fields the snapshot lacks — never the snapshot's values
     */
    public boolean recordSnapshotIncomplete(LineageCatalogObligationStore.Claim claim,
            String reason) {
        if (!active()) {
            return false;
        }
        return store.giveUp(claim, LineageCatalogObligation.Outcome.SNAPSHOT_INCOMPLETE,
                reason, null);
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /**
     * Counts by state, for metrics and admin status. Empty while inert.
     *
     * <p>Each count says whether it is exact. A preflight must read {@code truncated} before
     * treating a zero as "nothing pending".
     */
    public Map<LineageCatalogObligation.State, LineageCatalogObligationStore.StateCount>
            status() {
        return active() ? store.countByState() : Map.of();
    }

    /** What a waiting event's obligations collectively say. Four answers, not two. */
    public enum VerdictKind {
        /** Every task is RESOLVED. The only answer that resumes a projection. */
        ALL_RESOLVED,
        /** At least one is PENDING or CLAIMED, and none is terminal. Keep waiting. */
        WAITING,
        /** One ended SNAPSHOT_INCOMPLETE. The event is terminal too. */
        TERMINAL_UNRESOLVED,
        /**
         * Nothing could be established: a task is missing, the key set is empty or malformed,
         * or the store could not be read.
         *
         * <p>Distinct from {@code WAITING} because the responses differ — waiting is a state
         * the machine will leave on its own, and this one is not. Fail-closed: the projector
         * changes nothing.
         */
        INDETERMINATE
    }

    /**
     * The verdict, plus enough to act on it and nothing that could carry a value.
     *
     * @param reason why, in fixed words — never a catalog message, a key or a name
     * @param taskCount how many tasks were considered; a count, not the keys
     */
    public record Verdict(VerdictKind kind, String reason, int taskCount) {

        static Verdict of(VerdictKind kind, String reason, int taskCount) {
            return new Verdict(kind, reason, taskCount);
        }

        public boolean resumes() {
            return kind == VerdictKind.ALL_RESOLVED;
        }
    }

    /**
     * What the projector should do about an event's obligations.
     *
     * <p>A boolean could not carry this: "not all resolved" folded together still-waiting, a
     * terminal verdict, and a task nobody can find — three situations whose correct responses
     * are keep waiting, terminate the event, and change nothing.
     *
     * <p>An <b>empty</b> key set is {@code INDETERMINATE}, not success. A row in
     * {@code WAITING_FOR_CATALOG} carrying no keys has lost its metadata, and resuming it
     * would publish on the strength of a wait nobody can account for.
     */
    public Verdict verdictFor(List<String> taskKeys) {
        if (!active()) {
            // Inert or red: this machine parked nothing, so it cannot authorise a resume.
            return Verdict.of(VerdictKind.INDETERMINATE,
                    "the obligation machine is not active", taskKeys == null ? 0
                            : taskKeys.size());
        }
        if (taskKeys == null || taskKeys.isEmpty()) {
            return Verdict.of(VerdictKind.INDETERMINATE,
                    "a waiting event carries no obligation keys", 0);
        }
        int waiting = 0;
        for (String taskKey : taskKeys) {
            if (taskKey == null || taskKey.isBlank()) {
                return Verdict.of(VerdictKind.INDETERMINATE,
                        "a waiting event carries a blank obligation key", taskKeys.size());
            }
            Optional<LineageCatalogObligation> read;
            try {
                read = store.read(taskKey);
            } catch (RuntimeException e) {
                // A store that could not answer has established nothing. Not WAITING: that
                // would be a claim about the obligation's state.
                logger.warn("Cannot read an obligation for a waiting event: {}",
                        e.getClass().getSimpleName());
                return Verdict.of(VerdictKind.INDETERMINATE,
                        "an obligation could not be read", taskKeys.size());
            }
            if (read.isEmpty()) {
                return Verdict.of(VerdictKind.INDETERMINATE,
                        "an obligation named by a waiting event does not exist",
                        taskKeys.size());
            }
            LineageCatalogObligation obligation = read.get();
            switch (obligation.state()) {
                case RESOLVED -> { }
                case UNRESOLVED -> {
                    // Terminal wins immediately: no amount of the others resolving makes an
                    // unrebuildable entity appear.
                    return Verdict.of(VerdictKind.TERMINAL_UNRESOLVED,
                            "an obligation ended " + obligation.outcome(), taskKeys.size());
                }
                default -> waiting++;
            }
        }
        return waiting > 0
                ? Verdict.of(VerdictKind.WAITING, "obligations are still open", taskKeys.size())
                : Verdict.of(VerdictKind.ALL_RESOLVED, "every obligation resolved",
                        taskKeys.size());
    }
}
