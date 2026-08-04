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

    /** Capped backoff: a retryable failure is retried, but not forever at full speed. */
    static final int MAX_ATTEMPTS_BEFORE_BACKOFF = 10;

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
            presence = probe.presenceOf(kind, catalogQualifiedName);
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
                LineageCatalogObligation.State.PENDING, null, null, 0L, 0, clockMs.getAsLong(),
                LineageCatalogObligation.Outcome.NONE, null, null);

        LineageCatalogObligation stored = store.createIfAbsent(wanted);
        if (stored.state() == LineageCatalogObligation.State.RESOLVED) {
            // Someone resolved it between the probe and here. Nothing is owed after all —
            // returning the key would park a projection that could run now.
            return Optional.empty();
        }
        return Optional.of(stored.taskKey());
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
        for (LineageCatalogObligation pending
                : store.findByState(LineageCatalogObligation.State.PENDING, Math.max(1, limit))) {
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
            presence = probe.presenceOf(obligation.endpointKind(),
                    obligation.catalogQualifiedName());
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
            // ABSENT is retryable too: the authoritative publisher may simply not have run
            // yet. Only the snapshot being unusable is terminal, and nothing here can
            // establish that — building the historical entity is what discovers it, and that
            // is the piece this slice does not yet have.
            case ABSENT -> store.release(claim, "the catalog does not hold the entity yet")
                    ? Settlement.RELEASED : Settlement.LOST;
            case UNKNOWN -> store.release(claim, "the catalog did not answer")
                    ? Settlement.RELEASED : Settlement.LOST;
        };
    }

    /**
     * Gives up on an obligation, terminally.
     *
     * <p>Separate from {@link #runOnce} because nothing automatic reaches this yet: the only
     * legitimate terminal outcome is a snapshot that cannot rebuild the entity, and that verdict
     * belongs to the historical-entity builder. Exposed so the projector's stall timeout and the
     * admin route have one way in rather than each inventing a terminal state.
     */
    public boolean giveUp(LineageCatalogObligationStore.Claim claim, String reason) {
        if (!active()) {
            return false;
        }
        return store.giveUp(claim, LineageCatalogObligation.Outcome.SNAPSHOT_INCOMPLETE,
                reason, null);
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /** Counts by state, for metrics and admin status. Empty while inert. */
    public Map<LineageCatalogObligation.State, Long> status() {
        return active() ? store.countByState() : Map.of();
    }

    /** Whether every one of these obligations is RESOLVED — the projector's resume condition. */
    public boolean allResolved(List<String> taskKeys) {
        if (taskKeys == null || taskKeys.isEmpty()) {
            return true;
        }
        if (!active()) {
            // Inert: nothing was ever parked by this machine, so nothing can be resumed by it.
            return false;
        }
        for (String taskKey : taskKeys) {
            Optional<LineageCatalogObligation> obligation = store.read(taskKey);
            if (obligation.isEmpty()
                    || obligation.get().state() != LineageCatalogObligation.State.RESOLVED) {
                // §2: ALL of them, not any. One resolved obligation does not mean the event's
                // other endpoints have their entities.
                return false;
            }
        }
        return true;
    }
}
