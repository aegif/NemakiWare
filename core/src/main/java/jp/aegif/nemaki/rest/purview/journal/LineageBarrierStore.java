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

/**
 * The only storage surface §6-a's barrier code sees (A-2 Slice 4a).
 *
 * <p>It is deliberately four methods wide. The journal store's own read helper
 * ({@code ensureClientForRead}) cannot back this: it answers {@code false} for BOTH a verified
 * absent database and an infrastructure failure, and that is exactly the distinction the whole
 * fence rests on — a proven absence means "pristine deployment, keep writing v1", while an
 * outage means "we cannot know, spool the fact". It also deploys design documents as a side
 * effect, which a barrier lookup must never do.
 *
 * <p>So this seam brings its own discovery: <b>absence only for a 404, every other failure
 * propagates, and no view is ever deployed.</b> Document-level semantics are the journal's
 * existing strict ones — 404 is an ordinary "absent", 409 is an ordinary CAS loss, anything
 * else throws.
 */
public interface LineageBarrierStore {

    /** Thrown when the barrier's storage could not be reached or understood. */
    class BarrierStorageException extends RuntimeException {
        public BarrierStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The raw barrier document.
     *
     * @return its properties (including {@code _id}/{@code _rev}), or {@code null} when the
     *         document — or the database itself — is verifiably absent
     * @throws BarrierStorageException on any failure that is not a verified 404
     */
    Map<String, Object> readBarrierRaw();

    /**
     * Writes the barrier under its own {@code _rev} (absent {@code _rev} = create-if-absent).
     *
     * @return true when committed, false on a 409 — an ordinary CAS loss the caller retries by
     *         rereading, never by replaying what it computed against the older revision
     * @throws BarrierStorageException on infrastructure failure
     */
    boolean casBarrier(Map<String, Object> raw);

    /**
     * @return the witness document, or {@code null} when it — or the database — is absent
     * @throws BarrierStorageException on any failure that is not a verified 404
     */
    Map<String, Object> readWitness();

    /**
     * Records that a barrier has been observed here, if that is not already recorded.
     *
     * <p>Ordering matters more than the content: {@code prepare} writes the witness BEFORE the
     * barrier, so a barrier can never become durable without a witness that already is. A
     * later 404 on the barrier is then an anomaly ("someone deleted it") rather than the
     * pristine state, and cannot silently restore v1 semantics.
     *
     * @return true if the witness now exists (including "it already did")
     * @throws BarrierStorageException on infrastructure failure
     */
    boolean writeWitnessIfAbsent(long observedAtMs);

    /**
     * The node's durable id, allocated on first use and never at startup.
     *
     * @return the persisted node id, or {@code null} if none has been allocated
     * @throws BarrierStorageException on any failure that is not a verified 404
     */
    String readNodeId();

    /**
     * Persists the node id if absent, and returns whichever id is durable afterwards — a
     * concurrent allocator's id wins over the caller's proposal, so two racing callers cannot
     * end up believing different things about who this node is.
     *
     * @throws BarrierStorageException on infrastructure failure
     */
    String allocateNodeIdIfAbsent(String proposed, long allocatedAtMs);
}
