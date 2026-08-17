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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The digest that decides whether a create-if-absent 409 was the same event or an id collision.
 *
 * <h2>Why the identity hash is not enough</h2>
 *
 * <p>{@link LineageIdentity#processKey} hashes the <em>qualified names</em> of the endpoints, which
 * is what makes two emissions of the same business fact one process. It deliberately does not hash
 * the endpoints' snapshot attributes: a document whose name changed between two emissions is the
 * same document, and must not become a second Process.
 *
 * <p>That is exactly why a second digest is needed. When the journal answers 409, the question is
 * no longer "is this the same process" — the id already says so — but "is this the same record".
 * The design's §3 answers it over the immutable payload, attributes included, so that two events
 * agreeing on identity but disagreeing on content are recognised as the collision they are rather
 * than silently accepted.
 *
 * <h2>What is in and what is out</h2>
 *
 * <p>Per §3's table. In: {@code processKey}, {@code deliveryId}, {@code schemaVersion},
 * {@code repositoryId}, {@code processType}, {@code operationId}, {@code occurredAt},
 * {@code inputs} and {@code outputs} with their attributes, and the chunk coordinates. Out:
 * everything that legitimately changes after creation — {@code sequence}, state,
 * {@code sequencerGeneration}, {@code publishStatusByTarget}, {@code claimToken},
 * {@code replayRequestsByTarget} — and {@code spoolRecordId}, which is excluded for a subtler
 * reason given in §3: the same business fact can be emitted directly or materialised from a spool
 * record, both under the same {@code deliveryId}, and including it would make that pair look like
 * a collision.
 *
 * <h2>{@code occurredAt} is in, and that is load-bearing</h2>
 *
 * <p>{@code occurredAt} is part of this digest but not of {@code processKey}. So re-deriving it —
 * calling the clock again on a retry instead of carrying the original value — produces the same
 * {@code deliveryId} with a different digest, and the journal reports an integrity violation.
 * That is the design's intent: it makes the "allocate {@code occurredAt} once" contract
 * self-enforcing rather than merely documented.
 */
public final class LineageEventDigest {

    /** Domain separator. Changing it invalidates every stored digest. */
    private static final String CREATION_DOMAIN = "EVENT_CREATION_V1";

    private LineageEventDigest() {
    }

    /**
     * The full record of one endpoint, as canonical values.
     *
     * <p>Shared with the design's §6-a spool {@code payloadDigest}, which needs the same thing:
     * "did the endpoints, attributes included, change". Kept here rather than duplicated there.
     *
     * <p>Map key order is not fixed by this method and does not need to be —
     * {@link LineageCanonicalHash} sorts map keys into unsigned UTF-8 byte order before hashing.
     *
     * @param endpoints validated by {@link LineageCanonicalHash#canonicalQualifiedNames}, which
     *                  rejects a null list, null elements and duplicate qualified names, and fixes
     *                  the order. A null list is a caller bug rather than an empty one — treating
     *                  it as empty would let a mapping failure produce a clean digest over
     *                  endpoints nobody read.
     */
    public static List<Map<String, Object>> endpointRecords(List<LineageEndpoint> endpoints) {
        // Reuse A-1's canonicalisation rather than re-sorting here: it is the frozen definition of
        // "this set of endpoints", and a second sort in a second place is a second thing to drift.
        List<String> order = LineageCanonicalHash.canonicalQualifiedNames(endpoints);
        Map<String, LineageEndpoint> byName = new LinkedHashMap<>();
        for (LineageEndpoint endpoint : endpoints) {
            byName.put(endpoint.catalogQualifiedName(), endpoint);
        }
        List<Map<String, Object>> records = new ArrayList<>(order.size());
        for (String qualifiedName : order) {
            records.add(record(byName.get(qualifiedName)));
        }
        return List.copyOf(records);
    }

    private static Map<String, Object> record(LineageEndpoint endpoint) {
        // LinkedHashMap rather than Map.of: objectId and operationId are null for the kinds that
        // are not identified by them, and Map.of rejects nulls. Null is a value that has to be
        // encodable here — the canonical hash gives it its own type tag precisely so that an
        // absent objectId and an empty one are different records.
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("kind", endpoint.kind().name());
        record.put("repositoryId", endpoint.repositoryId());
        record.put("catalogQualifiedName", endpoint.catalogQualifiedName());
        record.put("objectId", endpoint.objectId());
        record.put("operationId", endpoint.operationId());
        record.put("attributes", endpoint.attributes());
        return record;
    }

    /**
     * The immutable-payload digest of an event.
     *
     * @return 64 lowercase hex characters
     */
    public static String creationPayloadDigest(String processKey,
                                               String deliveryId,
                                               int schemaVersion,
                                               String repositoryId,
                                               LineageProcessType processType,
                                               String operationId,
                                               String occurredAt,
                                               List<LineageEndpoint> inputs,
                                               List<LineageEndpoint> outputs,
                                               int chunkIndex,
                                               int chunkCount) {
        return LineageCanonicalHash.hash(
                CREATION_DOMAIN,
                processKey,
                deliveryId,
                (long) schemaVersion,
                repositoryId,
                processType == null ? null : processType.name(),
                operationId,
                occurredAt,
                endpointRecords(inputs),
                endpointRecords(outputs),
                (long) chunkIndex,
                (long) chunkCount);
    }
}
