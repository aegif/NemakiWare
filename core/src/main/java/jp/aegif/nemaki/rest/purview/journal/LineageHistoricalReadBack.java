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
 * What a read-back established about <em>this plan's</em> write.
 *
 * <h2>Why PRESENT is not an answer</h2>
 *
 * <p>Something existing under the qualified name proves nothing about which write put it there.
 * The authoritative publisher writes to the same name; so does an older historical intent from
 * a different observation. Treating "present" as "my write landed" would let a machine advance
 * to {@code PUBLISHED}, re-check the source, find it purged, and resolve — having published
 * nothing and confirmed someone else's entity.
 *
 * <p>So the verdict is bound to the plan: the target, the subject, the payload schema version,
 * both evidence digests and the planned operation digest. Only that combination is
 * {@link #MATCH}.
 */
public enum LineageHistoricalReadBack {

    /** The entity in the catalog is the one this plan describes, in every bound field. */
    MATCH,

    /** Established: the catalog does not hold it. This plan's write has not happened. */
    ABSENT,

    /**
     * Something is there and it is not this plan's write.
     *
     * <p>Never success. It is either the authoritative current entity — in which case this
     * plan is obsolete — or another intent's historical entity, which is a serialisation
     * failure. Both need a human-visible state rather than a silent overwrite.
     */
    CONFLICT,

    /** The read failed. Nothing was established, and nothing may be concluded. */
    UNKNOWN;

    /** Only a match lets the machine record its own write as done. */
    public boolean provesThisPlan() {
        return this == MATCH;
    }
}
