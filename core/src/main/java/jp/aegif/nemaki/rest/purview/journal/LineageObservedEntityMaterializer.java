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
 * Puts the entity an event observed into the catalog, for a source NemakiWare never destroys.
 *
 * <h2>Read before writing, and read again after</h2>
 *
 * <p>The pre-read is not an optimisation. Without it, a crash between a successful write and the
 * obligation being resolved means the next pass writes again — and if something else has since
 * put a different entity at that qualified name, the second write silently replaces it. Reading
 * first turns that into a CONFLICT this refuses to overwrite.
 *
 * <p>The post-read is what makes MATERIALIZED mean anything. A 2xx from a bulk endpoint says the
 * request was accepted, not that the entity is there.
 */
public interface LineageObservedEntityMaterializer {

    /** What happened. Five answers, and none of them collapses into another. */
    enum Outcome {
        /**
         * The catalog already held exactly this plan's content, so nothing was written. The
         * obligation may be resolved: whoever wrote it, the entity is correct.
         */
        MATCHED,
        /** This call wrote it and read it back as a match. */
        MATERIALIZED,
        /**
         * Nothing terminal. Every failure, every UNKNOWN read, every refused write — all of
         * which may succeed later, so the obligation stays open.
         */
        RETRYABLE,
        /**
         * The catalog holds something else at this qualified name. Not overwritten: this plan
         * has no authority to replace an entity it did not write, and doing so would erase
         * whatever a different producer put there.
         */
        CONFLICT,
        /**
         * The snapshot cannot produce a publishable entity — a mandatory attribute the event
         * never carried. Terminal: retrying cannot add data the event does not contain.
         */
        SNAPSHOT_INCOMPLETE
    }

    /**
     * Materialise one observed endpoint.
     *
     * @param observed already validated by its own constructor: right policy, right subject,
     *        verified evidence
     * @return what happened; never null
     */
    Outcome materialize(ObservedEntitySnapshot observed);

    /**
     * Materialise the current entity of a source proven to exist.
     *
     * <p>Same shape — pre-read, publish, exact post-read — over a snapshot whose constructor
     * demanded a positive {@code SOURCE_EXISTS} verdict rather than a non-purgeable policy.
     * Separate method because the two inputs are separate types on purpose: one cannot be
     * passed where the other is expected.
     *
     * <p>The attributes come from {@code projection}, not from the snapshot. The snapshot holds
     * what the <em>event</em> observed, which may be an older revision than the verdict that
     * authorises this write — and nothing in the v2 schema records which revision the event saw,
     * so the two cannot be compared. Publishing the projection makes the write assert only what
     * this execution's own repository read established.
     *
     * @param projection the catalog entity built from the same read as the authorising verdict;
     *        callers must not pass the snapshot's own attributes in its place
     */
    Outcome materializeCurrent(VerifiedCurrentEntitySnapshot current,
            java.util.Map<String, Object> projection);

    /** The catalog this materializer writes to. Answers for it and refuses every other. */
    String targetName();
}
