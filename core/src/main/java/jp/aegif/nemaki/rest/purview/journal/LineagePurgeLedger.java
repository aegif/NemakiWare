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

import java.util.Optional;

/**
 * The record that an object was destroyed — the only thing that may authorise a tombstone.
 *
 * <h2>Why absence cannot be the proof</h2>
 *
 * <p>A 404, an empty search result and a missing archive all mean the same thing to the reader:
 * <em>I could not find it</em>. None of them distinguishes "it was destroyed" from "the index is
 * behind", "the query was wrong", "the replica is stale" or "it has not been created yet". Any
 * of those readings, turned into {@code SOURCE_PURGED}, writes a permanent tombstone into a
 * catalog for an object that is sitting in the repository.
 *
 * <p>So the purge is recorded when it happens, by the code that does it, with the identifiers
 * that were destroyed. A verdict of PURGED then rests on a positive statement rather than on a
 * failure to find something.
 *
 * <h2>Restore invalidates</h2>
 *
 * <p>An object that comes back must stop authorising a tombstone immediately, and a ledger entry
 * that outlived its restore would keep authorising one for ever. The restore path invalidates
 * rather than deleting, so the history of what happened stays legible and a later reader can see
 * that the object was purged and then restored.
 */
public interface LineagePurgeLedger {

    /**
     * What the ledger knows about one subject.
     *
     * @param incarnation which instance of this identity was destroyed — a restored object is a
     *        new incarnation, so an old mark cannot authorise a tombstone for it
     * @param revision the destroyed instance's revision, so a re-created object at the same id
     *        is distinguishable
     * @param invalidatedAtMs non-null once a restore superseded this mark
     */
    record PurgeMark(String repositoryId, EndpointKind endpointKind, String subjectDigest,
            String incarnation, String revision, long purgedAtMs, Long invalidatedAtMs) {

        /** Whether this mark still says the subject is gone. */
        public boolean authoritative() {
            return invalidatedAtMs == null;
        }
    }

    /** The document type marker, so the type-limited views cannot pick up anything else. */
    String DOCUMENT_TYPE = "lineage_purge_mark";

    String DOCUMENT_ID_PREFIX = "lineage_purge_mark:";

    /**
     * Record a purge. Idempotent per subject: re-recording keeps the first mark.
     *
     * <p>Never throws into the caller's transaction — a lineage ledger must not be able to fail
     * a repository operation. A write that did not happen shows up later as {@code UNKNOWN},
     * which is the fail-closed answer.
     */
    void recordPurge(String repositoryId, EndpointKind kind, String subjectDigest,
            String incarnation, String revision, long purgedAtMs);

    /**
     * Supersede any mark for this subject, because the object is back.
     *
     * <p>Also never throws into the caller.
     */
    void invalidateOnRestore(String repositoryId, EndpointKind kind, String subjectDigest,
            long restoredAtMs);

    /**
     * What the ledger holds, or empty when it holds nothing.
     *
     * @throws RuntimeException when the ledger could not be read — the caller must turn that
     *         into {@code UNKNOWN} rather than into "no mark, so not purged"
     */
    Optional<PurgeMark> find(String repositoryId, EndpointKind kind, String subjectDigest);

    /** Whether the ledger is usable at all, for readiness. */
    boolean available();
}
