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
 * Where a digest can be anchored, and — the part that actually matters — what anchoring it
 * there does and does not let anyone claim.
 *
 * <p>The rungs are not interchangeable destinations. Two distinctions run through the whole
 * design and are carried on this enum so that no caller has to remember them:
 *
 * <ul>
 *   <li><b>Independence is NOT modelled here.</b> Four review rounds established that this code
 *       cannot determine whether an anchor is organizationally independent of the operator:
 *       every computable test was satisfiable by an operator running their own infrastructure.
 *       A destination is not a guarantee, so no flag pretends otherwise. What the receipt
 *       records instead is what it actually holds and what was actually checked.</li>
 *   <li><b>Time semantics.</b> A blockchain anchor proves an <i>upper</i> bound — the data
 *       existed no later than some block. A time-stamp token asserts a point in time within a
 *       stated accuracy. Rendering the two with the same words is how an honest report turns
 *       into an overclaim, so the difference is typed rather than left to prose.</li>
 * </ul>
 */
public enum AnchorKind {

    /**
     * The existing catalog sink (Apache Atlas / Microsoft Purview). Rung 1.
     *
     * <p>Not a time proof: the catalog records when IT was told, not when the data existed, and
     * a same-tenant catalog is administered by the very party whose behaviour is in question.
     */
    ATLAS_CATALOG(TimeSemantics.NOT_A_TIME_PROOF),

    /**
     * OpenTimestamps, committed into the Bitcoin blockchain. Rung 2.
     *
     * <p>Free, but <b>upper bound only</b> and not immediate:
     * confirmation waits on calendar aggregation and block confirmations (measured at hours to
     * roughly half a day, calendar-dependent). A receipt is therefore legitimately
     * {@link AnchorStatus#PENDING} for a while, and pending is not failure.
     */
    OPENTIMESTAMPS(TimeSemantics.UPPER_BOUND_ONLY),

    /**
     * An RFC 3161 time-stamp authority. Rung 3.
     *
     * <p>Immediate, with an accuracy the token itself states when it states one. Who operates
     * the authority — and therefore what its token is worth — is a property of the deployment's
     * configuration and contracts, not something this enum can express.
     */
    RFC3161_TSA(TimeSemantics.BIDIRECTIONAL_WITHIN_ACCURACY);

    /** What a time claim derived from this anchor may say. */
    public enum TimeSemantics {
        /** "existed no later than" — nothing about how early. */
        UPPER_BOUND_ONLY,
        /** A point in time, good to the accuracy the token states. */
        BIDIRECTIONAL_WITHIN_ACCURACY,
        /** Records receipt, not existence. Must not be presented as a time proof at all. */
        NOT_A_TIME_PROOF
    }

    private final TimeSemantics timeSemantics;

    AnchorKind(TimeSemantics timeSemantics) {
        this.timeSemantics = timeSemantics;
    }

    public TimeSemantics timeSemantics() {
        return timeSemantics;
    }
}
