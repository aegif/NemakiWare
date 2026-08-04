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
 * Whether <em>a given target's</em> catalog holds the entity an endpoint names — in three values.
 *
 * <h2>Why the target is an input</h2>
 *
 * <p>An obligation's identity includes the target, because each target waits independently: the
 * same qualified name can be present in Atlas and absent in Purview, and a projection to Purview
 * must not proceed on the strength of Atlas holding it. A probe that took only the kind and the
 * name could answer for one catalog and have its answer applied to another — the task key would
 * say "purview" while the observation came from somewhere else.
 *
 * <p>{@code repositoryId} is here for the same reason: routing may be per repository, and a
 * probe that has to re-derive it from the qualified name is parsing an identity it was handed.
 *
 * <h2>Why three values</h2>
 *
 * <p>The two-valued version is the bug. "Not present" and "could not ask" lead to opposite
 * actions: the first means wait for the authoritative publisher, the second means we know
 * nothing and must not decide. Collapsing them makes an outage look like a missing entity, and
 * every endpoint checked during that outage acquires an obligation for something that was there
 * all along.
 */
public interface LineageCatalogEntityProbe {

    enum Presence {
        /** This target's catalog holds it. A projection to this target may proceed. */
        PRESENT,
        /** This target's catalog answered, and does not hold it. An obligation is owed. */
        ABSENT,
        /**
         * This target's catalog did not answer, or the target is not one this node can reach.
         *
         * <p>Not {@code ABSENT}: nothing was established. Both mean "do not publish yet" to the
         * producer; only {@code ABSENT} can lead the consumer to build a historical entity.
         */
        UNKNOWN
    }

    /**
     * @param target which catalog is being asked; an unknown one answers {@code UNKNOWN}
     * @param catalogQualifiedName the name the endpoint resolves to; never logged by callers
     */
    Presence presenceOf(String target, String repositoryId, EndpointKind kind,
            String catalogQualifiedName);
}
