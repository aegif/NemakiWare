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
 * The two identities a lineage record has: which business fact it is, and which delivery
 * obligation it is.
 *
 * <h2>Why two</h2>
 *
 * <p>One key was doing both jobs and could not. {@code processKey} answers "which business fact
 * is this?" and becomes the Atlas Process qualified name. {@code deliveryId} answers "which
 * journal record is this?" and becomes the CouchDB {@code _id} and the spool filename. A replay
 * and its original are the same fact and different records; so are two chunks of one export.
 * With a single key the store's duplicate check would have discarded the compensation event, and
 * the spool would have written both to one file.
 *
 * <h2>deliveryId is a tagged union</h2>
 *
 * <p>Not one formula with a target field. An ORIGINAL is a multi-target document — it has
 * {@code publishStatusByTarget}, not a target — so there is nothing to put there; it keys off the
 * canonical target set instead. Only a REPLAY is per-target. {@link DeliveryKind} is the tag.
 *
 * <h2>Not here yet</h2>
 *
 * <p>{@code creationPayloadDigest} — which decides whether a 409 from create-if-absent means
 * "already written" or "someone else's record under my id" — needs an event to digest, so it
 * lands with the v2 event shape in increment A-2. Its field list is fixed in the design:
 * creation-time fields only, never sequence, publish state, claim token or {@code _rev}.
 */
public final class LineageIdentity {

    /** Version of the identity contract. Bump only together with the encoding in {@link LineageCanonicalHash}. */
    public static final int IDEMPOTENCY_KEY_VERSION = 2;

    private static final String PROCESS_KEY_PREFIX = "v2:";

    private LineageIdentity() {
    }

    /**
     * The logical business identity, used as the Atlas Process qualified name suffix.
     *
     * <p>Includes the event-level {@code operationId}, which is what makes a second import into
     * the same folder a different process rather than a duplicate of the first, and the chunk
     * coordinates, because a chunk publishes as its own Process.
     */
    public static String processKey(String repositoryId,
                                    LineageProcessType processType,
                                    String operationId,
                                    List<LineageEndpoint> inputs,
                                    List<LineageEndpoint> outputs,
                                    int schemaVersion,
                                    int chunkIndex,
                                    int chunkCount) {
        return PROCESS_KEY_PREFIX + LineageCanonicalHash.hash(
                "PROCESS",
                repositoryId,
                processType == null ? null : processType.name(),
                LineageCanonicalHash.canonicalQualifiedNames(inputs),
                LineageCanonicalHash.canonicalQualifiedNames(outputs),
                operationId,
                (long) schemaVersion,
                (long) chunkIndex,
                (long) chunkCount);
    }

    /** The journal record identity of a first-time emission. */
    public static String originalDeliveryId(String processKey, Iterable<String> targets) {
        return LineageCanonicalHash.hash(DeliveryKind.ORIGINAL.name(), processKey,
                LineageCanonicalHash.canonicalTargetSet(targets));
    }

    /**
     * The journal record identity of a replay.
     *
     * <p>Deterministic so that a crash between creating the compensation event and acking the
     * request cannot produce a second one: the retry recomputes the same id and the
     * create-if-absent collapses.
     */
    public static String replayDeliveryId(String originalDeliveryId, String target,
                                          long replayGeneration) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("replay target must not be null or blank");
        }
        return LineageCanonicalHash.hash(DeliveryKind.REPLAY.name(), originalDeliveryId,
                target.trim(), replayGeneration);
    }

    /** The journal record identity of a repair driven from a dead letter. */
    public static String repairDeliveryId(String deadLetterId, long repairGeneration) {
        if (deadLetterId == null || deadLetterId.isBlank()) {
            throw new IllegalArgumentException("deadLetterId must not be null or blank");
        }
        return LineageCanonicalHash.hash(DeliveryKind.REPAIR.name(), deadLetterId,
                repairGeneration);
    }
}
