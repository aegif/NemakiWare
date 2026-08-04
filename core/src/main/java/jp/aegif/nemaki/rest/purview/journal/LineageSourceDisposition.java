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
 * What happened to the <em>source</em> an endpoint names — which is not what the catalog says.
 *
 * <h2>The conflation this exists to prevent</h2>
 *
 * <p>A catalog answering {@code ABSENT} means the catalog does not hold the entity. It says
 * nothing whatever about whether the source object still exists. Treating catalog-absent as
 * "the source was purged" would build a tombstone for a live object the authoritative publisher
 * simply had not reached yet — and that tombstone would then be the catalog's record of a
 * document that is sitting in the repository.
 *
 * <p>So the two questions are asked separately, and only this one may authorise a historical
 * entity.
 */
public enum LineageSourceDisposition {

    /**
     * The source is still there. The catalog will get it from the authoritative publisher;
     * a historical entity must not be built.
     */
    SOURCE_EXISTS,

    /**
     * The source is gone, and that is established — the event snapshot recorded it, or a live
     * read returned an unambiguous not-found. Only this permits a historical entity.
     */
    SOURCE_PURGED,

    /**
     * Not established. A permission error, a timeout, a 5xx, a decode failure, an unreachable
     * repository — all of them. Retryable, never terminal, and never a licence to build.
     */
    SOURCE_UNKNOWN;

    /** Only a purged source may be rebuilt from its snapshot. */
    public boolean permitsHistoricalEntity() {
        return this == SOURCE_PURGED;
    }
}
