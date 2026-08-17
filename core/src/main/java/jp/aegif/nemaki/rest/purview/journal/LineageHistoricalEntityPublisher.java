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

/**
 * Builds the historical entity for a source that is gone, from the endpoint snapshot alone (§2).
 *
 * <p>This is the one thing {@code LineageCatalogReconciliationService} was separated for: a
 * purged source has no live record to re-read, so the only material left is the snapshot the
 * endpoint carried when the fact was emitted. A publisher that fell back to reading the source
 * would work in testing and fail for exactly the objects this exists to serve.
 *
 * <h2>The three answers, and why none of them may be guessed</h2>
 *
 * <ul>
 *   <li>{@code PUBLISHED} — the entity was written <em>and</em> read back as present. A publish
 *       that returned success is not evidence: the read-back is what makes
 *       {@code RESOLVED(SOURCE_PURGED)} true rather than hoped.</li>
 *   <li>{@code RETRYABLE} — a timeout, a 5xx, or a read-back that came back UNKNOWN. The
 *       catalog's problem, not the snapshot's.</li>
 *   <li>{@code SNAPSHOT_INCOMPLETE} — the snapshot structurally lacks a required attribute.
 *       The <b>only</b> terminal answer, and the only route to
 *       {@code UNRESOLVED(SNAPSHOT_INCOMPLETE)}. Never returned because a catalog misbehaved:
 *       confusing the two turns an outage into a permanent verdict about our own data.</li>
 * </ul>
 *
 * <p>Identity is not recomputed here. The qualified name is the one the endpoint already
 * resolves to, so a historical entity lands where the lineage already points.
 */
public interface LineageHistoricalEntityPublisher {

    enum Outcome {
        /** Written and confirmed present by a read-back. */
        PUBLISHED,
        /** The catalog could not complete it. Try again later. */
        RETRYABLE,
        /** The snapshot cannot rebuild the entity. Terminal, and only from evidence. */
        SNAPSHOT_INCOMPLETE
    }

    /**
     * Writes the historical entity, and confirms it by reading it back.
     *
     * <p>Takes a {@link HistoricalEntitySnapshot} rather than a subject plus a raw map: that
     * type can only exist for a purged source whose snapshot matches the obligation and whose
     * target matches this publisher's registration, so none of those checks can be forgotten
     * here or at any other call site.
     *
     * <p>Snapshot values, the qualified name and the task key are never logged, put in a
     * reason, or echoed in an exception.
     */
    LineageHistoricalPublishReceipt publishHistorical(HistoricalEntitySnapshot snapshot);

    /**
     * Whether the catalog holds <em>this plan's</em> historical entity.
     *
     * <p>Needed because a crash between the external write and the durable state update leaves
     * a {@code PLANNED} intent with the entity already written. Resuming without looking would
     * either write it twice or — worse — re-check the source, find it restored, and walk away
     * from a tombstone nobody will come back for.
     *
     * <p>Must distinguish "this plan's write is there" from "something is there": the
     * authoritative publisher and older intents write to the same qualified name. Where the
     * planned operation digest cannot be read back from the catalog, publish it as a historical
     * marker attribute so that it can — a verdict that cannot tell the two apart is
     * {@link LineageHistoricalReadBack#UNKNOWN}, not {@code MATCH}.
     *
     * @param plannedOperationDigest what this plan intends to have written
     * @return {@code UNKNOWN} on any failure; never guessed
     */
    LineageHistoricalReadBack readBackHistorical(HistoricalEntitySnapshot snapshot,
            String plannedOperationDigest);
}
