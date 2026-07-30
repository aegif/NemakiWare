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
 * Why this journal record exists — the tag in {@link LineageIdentity}'s deliveryId union.
 *
 * <p>All three can describe the same business fact, and therefore share a {@code processKey};
 * what separates them is that each is a distinct delivery obligation with its own lifecycle.
 */
public enum DeliveryKind {

    /** The emission that accompanied the business operation. */
    ORIGINAL,

    /** A compensation record created by an administrative replay of a specific target. */
    REPLAY,

    /** A compensation record rebuilt from a dead letter. */
    REPAIR
}
