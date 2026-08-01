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

import java.util.List;

/**
 * Why a journal record exists, carrying the fields that kind of delivery is identified by.
 *
 * <h2>Why this is a sealed hierarchy and not three nullable fields</h2>
 *
 * <p>The design's {@code deliveryId} is a tagged union: an {@code ORIGINAL} is identified by the
 * process it delivers plus the set of targets, a {@code REPLAY} by the original delivery, one
 * target and a generation, a {@code REPAIR} by a dead letter and a generation. Flattening those
 * into one record means every field is nullable, and "a REPAIR without a deadLetterId" becomes a
 * value that compiles, constructs, and only fails when something tries to compute an id from it.
 *
 * <p>Here each kind carries exactly its own fields, so that state cannot be built.
 *
 * <h2>{@link #deliveryId} takes the process key, and two of three ignore it</h2>
 *
 * <p>Deliberately. The union is over <em>what distinguishes this delivery from another</em>, and
 * only the first delivery of a process is distinguished by the process. A replay is distinguished
 * by which delivery it replays; a repair by which dead letter it rebuilds. Both of those already
 * identify a process transitively, so including it again would let the same replay produce two ids
 * if the caller passed a different process key.
 *
 * <p>The formulas themselves live in {@link LineageIdentity} and are frozen by golden vectors.
 * Nothing here recomputes a hash.
 */
public sealed interface LineageDelivery
        permits LineageDelivery.Original, LineageDelivery.Replay, LineageDelivery.Repair {

    /** The tag. */
    DeliveryKind kind();

    /**
     * The journal document {@code _id} for this delivery.
     *
     * @param processKey the process this delivery belongs to; used only by {@link Original}
     */
    String deliveryId(String processKey);

    /** The first emission, delivered to a set of targets. */
    record Original(List<String> targets) implements LineageDelivery {

        public Original {
            if (targets == null) {
                throw new IllegalArgumentException("targets must not be null"
                        + " — an ORIGINAL delivery with no target is not a delivery");
            }
            targets = List.copyOf(targets);
        }

        @Override
        public DeliveryKind kind() {
            return DeliveryKind.ORIGINAL;
        }

        /** Canonicalisation of the target set (trim, dedupe, sort) is {@link LineageIdentity}'s. */
        @Override
        public String deliveryId(String processKey) {
            return LineageIdentity.originalDeliveryId(processKey, targets);
        }
    }

    /**
     * A compensation record for one target of an earlier delivery.
     *
     * <p>Per target rather than per event: the original is a multi-target document, and replaying
     * it wholesale would re-deliver targets that succeeded.
     */
    record Replay(String originalDeliveryId, String target, long generation)
            implements LineageDelivery {

        public Replay {
            requireText(originalDeliveryId, "originalDeliveryId");
            requireText(target, "target");
            if (generation < 1) {
                throw new IllegalArgumentException("replay generation must be at least 1, got "
                        + generation);
            }
        }

        @Override
        public DeliveryKind kind() {
            return DeliveryKind.REPLAY;
        }

        @Override
        public String deliveryId(String ignoredProcessKey) {
            return LineageIdentity.replayDeliveryId(originalDeliveryId, target, generation);
        }
    }

    /** A compensation record rebuilt from a dead letter. */
    record Repair(String deadLetterId, long generation) implements LineageDelivery {

        public Repair {
            requireText(deadLetterId, "deadLetterId");
            if (generation < 1) {
                throw new IllegalArgumentException("repair generation must be at least 1, got "
                        + generation);
            }
        }

        @Override
        public DeliveryKind kind() {
            return DeliveryKind.REPAIR;
        }

        @Override
        public String deliveryId(String ignoredProcessKey) {
            return LineageIdentity.repairDeliveryId(deadLetterId, generation);
        }
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be null or blank");
        }
    }
}
