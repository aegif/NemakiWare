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
 * Whether the catalog holds the entity an endpoint names — in three values, never two.
 *
 * <p>The two-valued version of this question is the bug. "Not present" and "could not ask" lead
 * to opposite actions: the first means wait for the authoritative publisher, the second means
 * we know nothing and must not decide. Collapsing them makes an outage look like a missing
 * entity, and every endpoint checked during that outage acquires an obligation for something
 * that was there all along.
 *
 * <p>Kept as an interface in this package so the obligation machine does not depend on the
 * catalog client: the machine is about parking and resuming projections, and the only thing it
 * needs from a catalog is this one answer.
 */
public interface LineageCatalogEntityProbe {

    enum Presence {
        /** The catalog holds it. A projection may proceed. */
        PRESENT,
        /** The catalog answered, and does not hold it. An obligation is owed. */
        ABSENT,
        /**
         * The catalog did not answer.
         *
         * <p>Not {@code ABSENT}: nothing was established. Treated the same as {@code ABSENT} by
         * the producer (both mean "do not publish yet") and differently by the consumer (only
         * {@code ABSENT} can lead to building a historical entity; {@code UNKNOWN} is retried).
         */
        UNKNOWN
    }

    /**
     * @param catalogQualifiedName the name the endpoint resolves to; never logged by callers
     */
    Presence presenceOf(EndpointKind kind, String catalogQualifiedName);
}
