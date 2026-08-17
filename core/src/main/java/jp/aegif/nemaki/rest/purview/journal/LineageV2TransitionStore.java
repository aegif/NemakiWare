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

/**
 * §8-b v2: the CAS + claim-lease transition machine for per-target projection lifecycles on
 * v2 journal rows (D-rest-2).
 *
 * <p>This is deliberately a NEW interface beside {@link LineageJournalStore} (v2.3.18 ⑧: add
 * overloads and new interfaces, dispatch on schema version — never mutate the v1 surface). The
 * v1 three-argument {@code updatePublishStatus} remains byte-identical for v1 rows and refuses
 * v2 documents outright; every v2 mutation goes through here, token-fenced and CAS'd on
 * {@code _rev}.
 *
 * <p>Failure classification follows D-rest-1's strict IO rule: "not found" and "CAS lost" are
 * ordinary answers ({@code null} / {@code false} / skip), infrastructure failures are
 * {@link LineageSequencingStore.SequencingStorageException} and must propagate — a projector
 * that cannot tell "lost the race" from "CouchDB is down" must halt, not continue.
 */
public interface LineageV2TransitionStore {

    /** A won projection claim: the fencing token and the lease the claimant must renew. */
    record V2ClaimGrant(String recordId, String target, String claimToken, Instant leaseExpiresAt) {
    }

    /**
     * CAS-claims a v2 row for projection: {@code PENDING→PROJECTING} or
     * {@code FAILED→PROJECTING}, minting a fresh token and lease. Legal only on rows whose
     * sequencing state is {@code SEQUENCED} — an unsequenced row is not deliverable, whatever
     * its status map says. Clears the per-attempt {@code verifyingSinceMs} marker; initializes
     * {@code retryCount} to 0 on first claim, retains it otherwise.
     *
     * @return the grant, or {@code null} when the claim was lost or the row is not claimable
     *         (wrong status, unsequenced, absent, or a live unexpired claim exists)
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    V2ClaimGrant claimForProjection(String recordId, String target, Duration lease);

    /**
     * Token-fenced CAS transition out of a live claim. Allowed pairs and their effects
     * (everything else is an {@link IllegalArgumentException} — a caller bug, not a race):
     * <ul>
     *   <li>PROJECTING→VERIFYING — sets verifyingSinceMs, renews lease;</li>
     *   <li>VERIFYING→PUBLISHED — clears the live lease; audit fields retained;</li>
     *   <li>VERIFYING→FAILED — verify max-age exceeded; no retry increment;</li>
     *   <li>PROJECTING→FAILED — observed publish failure; retryCount increments;</li>
     *   <li>VERIFYING→UNPROJECTABLE — requires a durable reason;</li>
     *   <li>PROJECTING→REJECTED — §7 gate verdict on the v2 route; requires a reason.</li>
     * </ul>
     *
     * @param reason required for UNPROJECTABLE/REJECTED, forbidden otherwise
     * @return {@code true} iff the transition persisted; {@code false} on any CAS loss, token
     *         mismatch, or state mismatch (the caller must treat its claim as dead)
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    boolean transitionV2(String recordId, String target, LineagePublishStatus expected,
                         LineagePublishStatus next, String claimToken,
                         LineageTargetLifecycle.TerminalReason reason);

    /**
     * Renews the lease of a live claim (PROJECTING or VERIFYING), same token only, and only
     * while the current lease is UNEXPIRED — an expired claim must go through the reaper, never
     * resurrect itself.
     *
     * @return {@code true} iff renewed
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    /**
     * Enter the catalog wait for one target, storing the whole waiting set in the same CAS.
     *
     * <p>The caller must have confirmed every obligation durable first. A partial set stored
     * here is a row that resumes when only some of its obligations are answered.
     *
     * @return false when another writer moved the row first
     */
    boolean enterCatalogWait(String recordId, String target, java.util.List<String> taskKeys);

    /**
     * Return a waiting row to {@code PENDING}, keeping its original {@code waitingSinceMs}.
     *
     * <p>Only legal from {@code WAITING_FOR_CATALOG}, and only when every task resolved.
     */
    boolean resumeFromCatalogWait(String recordId, String target);

    /**
     * Give up on this event's wait, leaving the shared obligation untouched.
     *
     * <p>{@code UNRESOLVED} for the event only. The obligation belongs to every event waiting
     * on the same catalog entity.
     */
    boolean expireCatalogWait(String recordId, String target,
            LineageTargetLifecycle.TerminalReason reason);

    boolean renewClaim(String recordId, String target, String claimToken, Duration lease);

    /**
     * Pre-claim transitions (no token exists): the obligation rows of the frozen table
     * (PENDING→WAITING_FOR_CATALOG, WAITING_FOR_CATALOG→PENDING,
     * WAITING_FOR_CATALOG→UNRESOLVED), the admin rows (PENDING→DISCARDED, FAILED→DISCARDED),
     * and PENDING→UNRESOLVED (v2.3.24 F1: a created row whose plan proved unstorable — the
     * creation-time verdict, learned late. It is the only terminalization legal on an
     * UNSEQUENCED row, where every status beyond the creation-time set is refused).
     * Expected-state CAS on {@code _rev}. FAILED→DISCARDED preserves the audit bundle
     * byte-for-byte; no transition into a terminal state removes audit fields.
     *
     * @param reason required for UNRESOLVED, forbidden otherwise
     * @throws IllegalArgumentException for any pair outside the six above
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    boolean transitionV2Unclaimed(String recordId, String target, LineagePublishStatus expected,
                                  LineagePublishStatus next,
                                  LineageTargetLifecycle.TerminalReason reason);

    /**
     * Reaps expired claims for a target: PROJECTING→FAILED / VERIFYING→FAILED, only where the
     * lease expired before {@code cutoff} AND the token CAS'd is the token just reread from the
     * document (reap-by-CAS — the view row is a hint, the reread is the truth). No retry
     * increment. Pagination is stable (key + doc id continuation against the fixed cutoff);
     * corrupt rows are skipped loudly and cannot pin a page.
     *
     * @return the number of claims reaped
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    int reapExpiredClaims(String target, Instant cutoff);

    /**
     * The v2 ordered stream for one repository: SEQUENCED v2 rows with
     * {@code sequenceNumber > fromSequence}, ascending, from the v2-only view (old binaries
     * query the legacy view name and physically cannot see these rows).
     *
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    java.util.List<LineageJournalRowV2> findV2ByRepositoryAndSequenceRange(
            String repositoryId, long fromSequence, int limit);

    /**
     * The v1 half of the D-rest merged ordered stream, STRICT: same rows and order as the
     * legacy {@code findByRepositoryAndSequenceRange}, but infrastructure failures and
     * store-inactive conditions THROW instead of returning an empty list — the merge window
     * reads "fewer than a full batch" as coverage-to-infinity, so a silent empty v1 answer
     * would let the v2 side advance the cursor past unseen v1 rows (parallel review, D-rest-2
     * tip). No limit clamp: the caller's batch size is the fetch size, so the coverage
     * arithmetic and the real fetch agree exactly.
     *
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure or
     *         inactive store
     */
    java.util.List<LineageJournalRow> findV1ByRepositoryAndSequenceRangeStrict(
            String repositoryId, long fromSequence, int limit);

    /**
     * Strict single-row read by record id (deliveryId): {@code null} when absent, throws on a
     * malformed document or infrastructure failure — a claimant rereading its row must never
     * mistake an outage for a vanished claim.
     *
     * @throws LineageSequencingStore.SequencingStorageException on malformed row or infra failure
     */
    LineageJournalRowV2 findV2ByRecordId(String recordId);

    /**
     * Distinct repository ids with at least one v2 SEQUENCED row whose status for the target is
     * non-terminal (PENDING/FAILED/PROJECTING/VERIFYING) — the v2 side of ordered-path
     * repository discovery.
     *
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    java.util.List<String> findV2NonTerminalRepositoryIds(String target);

    /**
     * v2.3.22 C2: every repository with a SEQUENCED v2 row owing this target, TERMINAL rows
     * included. The non-terminal discovery above cannot see a row that was classified
     * terminal at creation, so its cursor would never advance past it.
     *
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    java.util.List<String> findV2SequencedRepositoryIds(String target);
}
