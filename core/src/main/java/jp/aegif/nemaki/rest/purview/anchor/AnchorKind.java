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
 *   <li><b>Independence.</b> An anchor inside the operating organization proves nothing against
 *       that organization's own administrator. Only {@link #independentOfOperator()} targets
 *       support the claim "not even an administrator of this deployment could alter this".</li>
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
    ATLAS_CATALOG(1, false, TimeSemantics.NOT_A_TIME_PROOF),

    /**
     * OpenTimestamps, committed into the Bitcoin blockchain. Rung 2.
     *
     * <p>Independent of the operator and free, but <b>upper bound only</b> and not immediate:
     * confirmation waits on calendar aggregation and block confirmations (measured at hours to
     * roughly half a day, calendar-dependent). A receipt is therefore legitimately
     * {@link AnchorStatus#PENDING} for a while, and pending is not failure.
     */
    OPENTIMESTAMPS(2, true, TimeSemantics.UPPER_BOUND_ONLY),

    /**
     * An RFC 3161 time-stamp authority. Rung 3.
     *
     * <p>Independent of the operator and immediate, with an accuracy the token itself states.
     * Whether the authority is accredited is a property of the configured TSA, not of this
     * enum — see {@code rfc3161.accreditation} in the evidence report.
     */
    RFC3161_TSA(3, true, TimeSemantics.BIDIRECTIONAL_WITHIN_ACCURACY);

    /** What a time claim derived from this anchor may say. */
    public enum TimeSemantics {
        /** "existed no later than" — nothing about how early. */
        UPPER_BOUND_ONLY,
        /** A point in time, good to the accuracy the token states. */
        BIDIRECTIONAL_WITHIN_ACCURACY,
        /** Records receipt, not existence. Must not be presented as a time proof at all. */
        NOT_A_TIME_PROOF
    }

    private final int rung;
    private final boolean independentOfOperator;
    private final TimeSemantics timeSemantics;

    AnchorKind(int rung, boolean independentOfOperator, TimeSemantics timeSemantics) {
        this.rung = rung;
        this.independentOfOperator = independentOfOperator;
        this.timeSemantics = timeSemantics;
    }

    /** Trust-ladder rung, 1-3. Rung 0 (the internal hash chain) has no anchor target. */
    public int rung() {
        return rung;
    }

    /**
     * Whether evidence resting on this anchor survives an administrator of this deployment.
     * False for anchors that live inside the operating organization.
     */
    public boolean independentOfOperator() {
        return independentOfOperator;
    }

    public TimeSemantics timeSemantics() {
        return timeSemantics;
    }
}
