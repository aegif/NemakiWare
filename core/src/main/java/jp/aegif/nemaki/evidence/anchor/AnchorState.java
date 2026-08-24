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
package jp.aegif.nemaki.evidence.anchor;

/**
 * How far an anchor got (P2-0 §1).
 *
 * <p>Four values, not a boolean. The one that must not be lost is the difference between
 * {@link #SUBMITTED} and {@link #CONFIRMED}: OpenTimestamps returns a pending timestamp
 * immediately and only settles hours later, so a design with one "anchored" flag would let a
 * deployment claim a proof it does not yet hold for the whole of that window.
 */
public enum AnchorState {

    /** This tier is switched off. NOT a failure — nothing was attempted and nothing is wrong. */
    NOT_ATTEMPTED,

    /**
     * Sent and accepted by the far side, NOT yet confirmed.
     *
     * <p>Does not count as anchored. {@code AnchorOutcome.confirmedTiers()} excludes it
     * deliberately.
     */
    SUBMITTED,

    /** Settled externally, with the proof in hand. */
    CONFIRMED,

    /** Submission or confirmation failed. Carries a reason. */
    FAILED
}
