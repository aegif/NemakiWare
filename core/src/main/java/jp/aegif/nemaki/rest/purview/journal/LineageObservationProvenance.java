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

import java.util.Comparator;

/**
 * When a snapshot <em>observed</em> the source — which is not when it was delivered.
 *
 * <h2>The failure this exists to stop</h2>
 *
 * <p>A replay re-delivers an old observation and is given a <b>new</b> journal sequence. Order
 * the candidates by delivery sequence and this happens:
 *
 * <pre>
 *   seq 10  ORIGINAL  the object was purged
 *   seq 20  ORIGINAL  the object was restored
 *   seq 30  REPLAY of seq 10 — still says "purged", now sorts last
 * </pre>
 *
 * <p>The resolver takes seq 30 as the latest evidence, concludes the source is gone, and writes
 * a tombstone for an object sitting in the repository. Repair has the same shape.
 *
 * <p>So there are two orders and they are kept apart by name:
 *
 * <ul>
 *   <li><b>delivery order</b> — {@link #deliverySequence()}. What the projector and the cursor
 *       advance along. Correct for delivery, meaningless for "which observation is newer".</li>
 *   <li><b>observation order</b> — {@link #observationOrder()}. Which snapshot saw the source
 *       later. A replay <em>inherits</em> its origin's, so re-delivering an old observation
 *       does not make it a new one.</li>
 * </ul>
 */
public record LineageObservationProvenance(
        LineageDeliveryKind deliveryKind,
        String deliveryId,
        String originDeliveryId,
        long deliverySequence,
        long originObservationSequence,
        String occurredAt,
        String originEvidenceDigest) {

    /** How this delivery came to be. */
    public enum LineageDeliveryKind {
        /** A first-hand observation. Its own delivery is its observation. */
        ORIGINAL,
        /** A re-delivery of an earlier observation. Carries no new information about the source. */
        REPLAY,
        /** A reconstruction from a dead letter. Also carries an earlier observation. */
        REPAIR
    }

    /**
     * Whether this provenance can place the snapshot in observation order.
     *
     * <p>A replay whose origin cannot be traced is not "probably recent" — nothing is known
     * about when it observed the source, and guessing would reintroduce the bug this record
     * exists to prevent.
     */
    public boolean usable() {
        if (deliveryKind == null || isBlank(deliveryId) || deliverySequence <= 0L
                || originObservationSequence <= 0L) {
            return false;
        }
        if (deliveryKind == LineageDeliveryKind.ORIGINAL) {
            // An original is its own origin. Anything else means the fields disagree about
            // what this delivery is.
            return deliveryId.equals(originDeliveryId)
                    && deliverySequence == originObservationSequence;
        }
        // A replay or repair must name the observation it is re-delivering, and that origin
        // must be a different delivery — otherwise it is claiming to be its own source.
        //
        // It must ALSO carry the origin's evidence digest. Without it there is no way to show
        // that what arrived is what the origin recorded, and a re-delivery that cannot prove
        // that is exactly the thing this provenance exists to distinguish. Optional proof is
        // not proof: a tampered or truncated replay would simply omit the field.
        return !isBlank(originDeliveryId) && !originDeliveryId.equals(deliveryId)
                && wellFormedDigest(originEvidenceDigest);
    }

    /**
     * The order that decides which snapshot saw the source later.
     *
     * <p>The origin's sequence, never this delivery's: a replay of an old observation must not
     * sort after a newer original.
     */
    public long observationOrder() {
        return originObservationSequence;
    }

    /**
     * Two deliveries of the same observation. Dedupe key.
     *
     * <p>Replaying one original five times does not produce five observations; it produces one,
     * delivered five times. Counting them separately would let a burst of replays outvote a
     * genuine later observation on any rule that looked at how many candidates agreed.
     */
    public String observationKey() {
        return originDeliveryId + "@" + originObservationSequence;
    }

    /** Same observation, re-delivered. */
    public boolean sameObservationAs(LineageObservationProvenance other) {
        return other != null && observationKey().equals(other.observationKey());
    }

    /**
     * Observation order within one repository: origin sequence, then origin delivery id.
     *
     * <p>The tie-break is the origin's id rather than this delivery's, so two replays of one
     * original never order differently from the original itself.
     */
    public static Comparator<LineageObservationProvenance> byObservation() {
        return Comparator.comparingLong(LineageObservationProvenance::observationOrder)
                .thenComparing(LineageObservationProvenance::originDeliveryId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 64 lowercase hex. A malformed digest proves nothing and must not be treated as proof. */
    private static boolean wellFormedDigest(String digest) {
        if (digest == null || digest.length() != 64) {
            return false;
        }
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** No delivery ids: they identify events, and an event carries endpoint attributes. */
    @Override
    public String toString() {
        return "LineageObservationProvenance[" + deliveryKind
                + " observationOrder=" + originObservationSequence
                + " deliverySequence=" + deliverySequence + "]";
    }
}
