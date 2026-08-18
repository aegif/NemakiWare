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
package jp.aegif.nemaki.rest.purview.anchor;

/**
 * How far an anchoring attempt got.
 *
 * <p>{@link #PENDING} is a first-class outcome, not a soft failure: an OpenTimestamps receipt is
 * legitimately pending for hours before Bitcoin confirms it, and collapsing that into "failed"
 * would make a working anchor look broken. Conversely {@link #CONFIRMED} must never be reported
 * for something still pending — an evidence report that says "confirmed" about a proof nobody
 * can yet verify is worse than one that says "pending".
 */
public enum AnchorStatus {

    /** Verifiable now, by the procedure this anchor's kind prescribes. */
    CONFIRMED,

    /**
     * Accepted by the anchor service but not yet verifiable. Expected for OpenTimestamps until
     * the commitment is upgraded; a caller should retry the upgrade rather than re-anchor.
     */
    PENDING,

    /** The attempt failed. The receipt carries why, so an operator is not left guessing. */
    FAILED,

    /** The target is not configured in this deployment. Not an error — a deliberate rung choice. */
    NOT_CONFIGURED
}
