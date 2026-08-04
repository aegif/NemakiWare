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
 * §2's chunking, as a pure function (v2.3.22).
 *
 * <p>One fact becomes K v2 events sharing an {@code operationId} and differing only in
 * {@code chunkIndex}/{@code chunkCount} — which §3's {@code processKey} folds in, so every
 * chunk has its own {@code deliveryId} and its own journal row.
 *
 * <p>Three rules make the partition reproducible by any binary, forever:
 * <ol>
 *   <li><b>Canonical order, never producer order.</b> The MANY side is walked in
 *       {@link LineageCanonicalHash#canonicalQualifiedNames} order — the same frozen
 *       canonicalisation the digest uses. Two permutations of one fact share a
 *       {@code spoolRecordId} and {@code payloadDigest}, so they must share chunk membership
 *       too; producer order would make identity depend on traversal order.</li>
 *   <li><b>The anchor is replicated verbatim into every chunk.</b> Every shape in
 *       {@link LineageProcessShape} pairs a ×1 side with a 1..n side; carrying the ×1 side
 *       into each chunk is what keeps each chunk independently shape-valid and publishable.</li>
 *   <li><b>Chunk coordinates cost nothing extra to measure.</b> The ruler charges every
 *       number its widest rendering, so {@code chunkIndex} and {@code chunkCount} are already
 *       covered whatever their digits turn out to be — a partition's size decisions never
 *       depend on the chunk count it is still computing.</li>
 * </ol>
 *
 * <p>A fact that fits produces exactly one slice at {@code chunk(0,1)} — byte-identical to
 * the unchunked output, so the ordinary fact is untouched by this machinery.
 */
public final class LineageChunkPlanner {

    /** The frozen algorithm identity, bound into a V3 decision. */
    public static final long PARTITION_VERSION = 1L;

    /** The limits a partition was computed under; frozen into the decision that used them. */
    public record ChunkLimits(long maxEndpointsPerEvent, long maxPayloadBytes) {
        public ChunkLimits {
            if (maxEndpointsPerEvent < 2) {
                throw new IllegalArgumentException("maxEndpointsPerEvent must admit an anchor"
                        + " plus one payload endpoint (>= 2), got " + maxEndpointsPerEvent);
            }
            if (maxPayloadBytes < 1) {
                throw new IllegalArgumentException("maxPayloadBytes must be positive, got "
                        + maxPayloadBytes);
            }
        }

        public Map<String, Object> asRecord() {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("maxEndpointsPerEvent", maxEndpointsPerEvent);
            record.put("maxPayloadBytes", maxPayloadBytes);
            return record;
        }
    }

    /** One chunk's endpoints, in the order the reconstruction will use. */
    public record ChunkSlice(List<LineageEndpoint> inputs, List<LineageEndpoint> outputs) {
        public ChunkSlice {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    /** A fact that cannot be split small enough — the caller classifies it terminally. */
    public static class OversizeException extends RuntimeException {
        private final String offendingEndpointRecordHash;
        private final long measuredBytes;

        public OversizeException(String message, String offendingEndpointRecordHash,
                                 long measuredBytes) {
            super(message);
            this.offendingEndpointRecordHash = offendingEndpointRecordHash;
            this.measuredBytes = measuredBytes;
        }

        /** The canonical hash of the COMPLETE offending endpoint record (audit evidence). */
        public String offendingEndpointRecordHash() {
            return offendingEndpointRecordHash;
        }

        public long measuredBytes() {
            return measuredBytes;
        }
    }

    private LineageChunkPlanner() {
    }

    /**
     * Partitions one fact into chunk slices.
     *
     * @throws OversizeException when even a single payload endpoint plus the anchor breaches
     *                           a limit, or the shape has no MANY side to split
     */
    public static List<ChunkSlice> partition(LineageSpoolPayloadV1 payload, ChunkLimits limits,
                                             String allocatedEventId) {
        if (payload == null || limits == null) {
            throw new IllegalArgumentException("payload and limits are required");
        }
        // The whole fact FIRST (F1): the overwhelming majority of facts fit, including every
        // 1→1 shape, and asking a 1→1 shape which side to split is a question it has no
        // answer to. Only a fact that does not fit needs an anchor.
        ChunkSlice wholeFact = new ChunkSlice(payload.inputs(), payload.outputs());
        if (fits(payload, wholeFact, limits, allocatedEventId)) {
            return List.of(wholeFact);
        }
        boolean outputsAreMany;
        try {
            outputsAreMany = manySideIsOutputs(payload.processType());
        } catch (IllegalArgumentException noAnchor) {
            // A shape with nothing to split, over the limit: terminal, with its evidence.
            throw oversize(payload, wholeFact, limits, allocatedEventId,
                    payload.inputs().isEmpty() ? null : payload.inputs().get(0));
        }
        List<LineageEndpoint> anchor = outputsAreMany ? payload.inputs() : payload.outputs();
        List<LineageEndpoint> many = canonicalOrder(outputsAreMany
                ? payload.outputs() : payload.inputs());

        ChunkSlice whole = slice(anchor, many, outputsAreMany);
        if (many.size() <= 1) {
            // Nothing left to split: the anchor plus one payload endpoint is the floor.
            throw oversize(payload, whole, limits, allocatedEventId,
                    many.isEmpty() ? null : many.get(0));
        }

        List<ChunkSlice> slices = new ArrayList<>();
        List<LineageEndpoint> run = new ArrayList<>();
        for (LineageEndpoint endpoint : many) {
            List<LineageEndpoint> candidate = new ArrayList<>(run);
            candidate.add(endpoint);
            ChunkSlice candidateSlice = slice(anchor, candidate, outputsAreMany);
            if (fits(payload, candidateSlice, limits, allocatedEventId)) {
                run = candidate;
                continue;
            }
            if (run.isEmpty()) {
                // This one endpoint cannot share a chunk with the anchor — unsplittable.
                throw oversize(payload, candidateSlice, limits, allocatedEventId, endpoint);
            }
            slices.add(slice(anchor, run, outputsAreMany));
            run = new ArrayList<>(List.of(endpoint));
            if (!fits(payload, slice(anchor, run, outputsAreMany), limits, allocatedEventId)) {
                throw oversize(payload, slice(anchor, run, outputsAreMany), limits,
                        allocatedEventId, endpoint);
            }
        }
        slices.add(slice(anchor, run, outputsAreMany));
        return List.copyOf(slices);
    }

    /** True when the process type's 1..n side is the OUTPUT side (import-shaped). */
    static boolean manySideIsOutputs(LineageProcessType processType) {
        List<LineageProcessShape.Shape> shapes = LineageProcessShape.shapesOf(processType);
        if (shapes.isEmpty()) {
            throw new IllegalArgumentException("processType " + processType
                    + " is deliberately unconstructible — it has no shape to chunk");
        }
        LineageProcessShape.Shape shape = shapes.get(0);
        boolean outputsMany = shape.outputs().max() > 1;
        boolean inputsMany = shape.inputs().max() > 1;
        if (outputsMany == inputsMany) {
            // Both sides ×1 (nothing to split) or both 1..n (no anchor to replicate): the
            // caller only reaches partition() for a fact that did not fit, and neither shape
            // can be chunked without inventing an anchor.
            throw new IllegalArgumentException("processType " + processType + " has no single"
                    + " anchor side — chunking needs a ×1 side to replicate");
        }
        return outputsMany;
    }

    private static List<LineageEndpoint> canonicalOrder(List<LineageEndpoint> endpoints) {
        List<String> order = LineageCanonicalHash.canonicalQualifiedNames(endpoints);
        Map<String, LineageEndpoint> byName = new LinkedHashMap<>();
        for (LineageEndpoint endpoint : endpoints) {
            byName.put(endpoint.catalogQualifiedName(), endpoint);
        }
        List<LineageEndpoint> sorted = new ArrayList<>(order.size());
        for (String qualifiedName : order) {
            sorted.add(byName.get(qualifiedName));
        }
        return sorted;
    }

    private static ChunkSlice slice(List<LineageEndpoint> anchor, List<LineageEndpoint> many,
                                    boolean outputsAreMany) {
        return outputsAreMany ? new ChunkSlice(anchor, many) : new ChunkSlice(many, anchor);
    }

    /**
     * Measures the COMPLETE creation document this slice would store, with the chunk
     * coordinates replaced by their fixed allowance.
     */
    static long measure(LineageSpoolPayloadV1 payload, ChunkSlice candidate,
                        String allocatedEventId) {
        return measure(payload, candidate, allocatedEventId, Map.of());
    }

    /**
     * Measures the COMPLETE creation document this slice would store, classification
     * included. The ruler already charges every number its widest rendering (20 bytes), so
     * the chunk coordinates are covered exactly once — no separate allowance is added (F6).
     */
    static long measure(LineageSpoolPayloadV1 payload, ChunkSlice candidate,
                        String allocatedEventId,
                        Map<String, LineageMaterializationDecision.CreationClassification>
                                classification) {
        LineageEventV2 probe = LineageSpoolMaterializer.v2EventOf(payload, allocatedEventId,
                candidate, 0, 1);
        if (classification != null && !classification.isEmpty()) {
            probe = LineageSpoolMaterializer.withClassifiedStatuses(probe, classification);
        }
        Map<String, Object> document = new LinkedHashMap<>(CouchLineageEventV2.toMap(probe));
        document.put("state", LineageJournalRowV2.SequencingState.UNSEQUENCED.name());
        if (classification != null && !classification.isEmpty()) {
            Map<String, Object> reasons = new LinkedHashMap<>();
            classification.forEach((target, c) -> {
                Map<String, Object> reason = new LinkedHashMap<>();
                reason.put("reason", c.reason().reason());
                reason.put("detail", c.reason().detail());
                reason.put("atMs", c.reason().atMs());
                reasons.put(target, reason);
            });
            document.put("v2TerminalReasonByTarget", reasons);
        }
        return LineageDocumentSizeRuler.upperBound(document);
    }

    private static boolean fits(LineageSpoolPayloadV1 payload, ChunkSlice candidate,
                                ChunkLimits limits, String allocatedEventId) {
        long endpoints = (long) candidate.inputs().size() + candidate.outputs().size();
        if (endpoints > limits.maxEndpointsPerEvent()) {
            return false;
        }
        return measure(payload, candidate, allocatedEventId) <= limits.maxPayloadBytes();
    }

    private static OversizeException oversize(LineageSpoolPayloadV1 payload, ChunkSlice slice,
                                              ChunkLimits limits, String allocatedEventId,
                                              LineageEndpoint offending) {
        long measured = measure(payload, slice, allocatedEventId);
        String hash = offending == null ? "" : LineageCanonicalHash.hash("OVERSIZE_ENDPOINT_V1",
                LineageEventDigest.endpointRecords(List.of(offending)));
        return new OversizeException("a single endpoint plus the anchor breaches the chunk"
                + " limits (endpoints=" + (slice.inputs().size() + slice.outputs().size())
                + ", measured=" + measured + ", maxBytes=" + limits.maxPayloadBytes()
                + ", maxEndpoints=" + limits.maxEndpointsPerEvent() + ")", hash, measured);
    }
}
