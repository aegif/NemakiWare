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
package jp.aegif.nemaki.rest.ingest.capture;

import java.util.Map;

/**
 * The two writes the capture boundary needs, with results that cannot be mistaken for success.
 *
 * <p>This interface exists because the ordinary journal write path cannot express failure. Both
 * {@code CloudantClientWrapper.create} and {@code .update} catch everything and return
 * {@code null}, and {@code CouchLineageJournalStore.append} does not even look at that — so a
 * 500, an authentication failure and an oversized document all arrive as silence. An
 * implementation of this interface must report those, or the whole boundary quietly reverts to
 * fail-open (design §6.8-1b).
 *
 * <p><b>An absent exception is not evidence of a write.</b> Implementations return a verdict;
 * callers must read it.
 */
public interface CaptureIntentStore {

    /**
     * Writes the intent row, before any mutation has happened.
     *
     * @return {@code true} only when the write was CONFIRMED. Anything else — a swallowed
     *         failure, an unreadable answer, a conflict on an id that should be unique — is
     *         {@code false}. Implementations must not return {@code true} merely because
     *         nothing was thrown.
     */
    boolean openIntent(CaptureIntent intent);

    /**
     * Moves the row to {@link CaptureState#CAPTURED}, attaching what was established.
     *
     * <p>Idempotent from the caller's point of view: completing an already-completed row is not
     * an error. The row keeps {@code type = lineage_capture_intent} — it is never turned into a
     * journal event, because the production emitter routes v1 and v2 through a write-schema
     * barrier and a direct v1 write would erase the v2 representation of ingest events in a
     * migrated deployment (design §6.10-B4).
     */
    CaptureCompletion completeIntent(CaptureIntent intent, Map<String, Object> evidence);

    /**
     * Pushes the open row's lease forward, so the sweeper does not call a live pass dead.
     *
     * <p>Best effort by design: a failed extension is not a failed ingest. The worst it costs
     * is a row swept early, which the age floor already allowed before the lease existed — so
     * this must never throw into the caller and must never be a reason to refuse work.
     *
     * <p>Default implementation does nothing, so a store that predates the lease (or a test
     * double) is unaffected: the sweeper falls back to the age rule for rows with no lease.
     */
    default void extendLease(CaptureIntent intent) {
        // No lease support: the age rule governs, exactly as before.
    }

    /** Whether the collaborator this store needs is actually wired. See {@link CaptureScope}. */
    boolean isActive();

    /**
     * Whether the boundary applies to this repository at all.
     *
     * <p>Lineage mode is per repository. A repository whose mode is {@code disabled} or
     * {@code direct} must behave exactly as it does today — no intent row, and above all no
     * fail-closed refusal, because refusing there would stop ingests in deployments that never
     * asked for the boundary. Reading the global setting instead of the per-repository one
     * would get this wrong the moment two repositories differ (design §6.5-1, AC 19).
     *
     * <p>Consulted ONCE, when the intent is opened. It is deliberately not re-checked at
     * completion: switching a repository to {@code disabled} half way through an ingest must
     * not strand an intent that is already open (AC 20).
     */
    Applicability appliesTo(String repositoryId);

    /**
     * Whether the boundary covers a repository — including "we could not find out".
     *
     * <p>The third value is the point. {@code getModeForRepository} cannot express a failed
     * read: the configuration layer swallows every error and returns an empty configuration,
     * which falls through to the {@code disabled} startup default. Collapsing that into
     * {@code NOT_APPLICABLE} means that while {@code nemaki_conf} is unreachable, a repository
     * the operator configured as {@code journaled} is ingested into with no intent row at all —
     * silently, and by the very mechanism that exists to prevent unrecorded changes.
     *
     * <p>The sibling emitter path already makes this distinction
     * ({@code IngestLineageEmitter.resolveEmitterReporting}), so without it the stronger
     * guarantee would be the weaker judge (external review).
     */
    enum Applicability {

        /** The repository runs the boundary. */
        APPLIES,

        /** The repository genuinely does not — mode read successfully as disabled or direct. */
        NOT_APPLICABLE,

        /** The mode could not be read. Neither answer is established. */
        UNDETERMINED
    }

    /** What happened when completing. Three outcomes, none of which may be inferred. */
    enum CaptureCompletion {

        /** The row is now {@code CAPTURED}. */
        COMPLETED,

        /**
         * The row could not be read back. <b>The cause cannot be determined.</b>
         *
         * <p>The read layer collapses {@code NotFoundException} and every other failure into the
         * same {@code null}, and update collapses a 409 and a network failure the same way. So
         * "retention deleted it", "the database was briefly unreachable" and "the intent write
         * never actually landed" are indistinguishable here. Callers must report the
         * observation and must not name a cause (design §6.8-6).
         */
        ROW_UNREADABLE,

        /**
         * The row was read and is not in a state this transition may leave.
         *
         * <p>{@code CAPTURED} is terminal, and only the ingest may complete an
         * {@code UNRESOLVED} row. A sweeper racing the ingest lands here; it is not an error.
         */
        NOT_COMPLETABLE,

        /**
         * The row stayed completable but the write kept losing.
         *
         * <p>Deliberately not {@link #NOT_COMPLETABLE}: that says the row was in a state this
         * transition may not leave, which was not observed. Collapsing the two would report a
         * write contention as a state problem and send an operator looking for the wrong thing
         * (design §6.8-6, external review).
         */
        CONTENDED
    }
}
