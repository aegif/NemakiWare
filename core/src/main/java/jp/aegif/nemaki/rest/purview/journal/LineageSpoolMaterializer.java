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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The convergent materializer (v2.3.18 ⑦ as amended by v2.3.21): spool fact → parent
 * decision → journal rows → verified bound ACK.
 *
 * <p>Convergence rules, in order:
 * <ol>
 *   <li>a VALID bound ACK (every edge verified, every time) suppresses work; a broken/forged
 *       ACK never does — it is quarantined via hard links and re-materialization proceeds;</li>
 *   <li>no decision + resolver unavailable = nothing happens (pre-4a this is every fact);</li>
 *   <li>the STORED decision is the only truth: reconstruction is compared against its frozen
 *       entries BEFORE any write (mapper drift can never create an unplanned row);</li>
 *   <li>the ACK is written only after every entry's row is REREAD and digest-verified, and
 *       for v2 also audit-id-verified against {@code allocatedEventId}.</li>
 * </ol>
 */
public class LineageSpoolMaterializer implements LineageSpoolScanner.SpoolMaterializer {

    private static final Logger logger =
            LoggerFactory.getLogger(LineageSpoolMaterializer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Per-fact outcome, tallied by the scanner. */
    public enum Outcome { ACKED, ALREADY_ACKED, UNRESOLVED, PARTIAL, FAILED }

    /** The outcome plus whether a broken/unreadable ACK was met (summary accounting). */
    public record MaterializeResult(Outcome outcome, boolean brokenAck) {
    }

    private final LineageMaterializationStore decisions;
    private final LineageJournalStore journal;
    private final LineageV2TransitionStore v2reads;
    private final WriteVersionResolver resolver;
    private final LineageFactSpool spool;
    private final LineageMetrics metrics;
    private final java.util.function.Supplier<String> eventIdAllocator;
    private final java.util.function.LongSupplier clockMs;
    private final LineageChunkPlanner.ChunkLimits chunkLimits;
    private final long maxDocumentBytes;

    public LineageSpoolMaterializer(LineageMaterializationStore decisions,
                                    LineageJournalStore journal,
                                    LineageV2TransitionStore v2reads,
                                    WriteVersionResolver resolver,
                                    LineageFactSpool spool,
                                    LineageMetrics metrics,
                                    java.util.function.Supplier<String> eventIdAllocator,
                                    java.util.function.LongSupplier clockMs) {
        this(decisions, journal, v2reads, resolver, spool, metrics, eventIdAllocator, clockMs,
                new LineageChunkPlanner.ChunkLimits(1000L, 1024L * 1024L), 4L * 1024 * 1024);
    }

    public LineageSpoolMaterializer(LineageMaterializationStore decisions,
                                    LineageJournalStore journal,
                                    LineageV2TransitionStore v2reads,
                                    WriteVersionResolver resolver,
                                    LineageFactSpool spool,
                                    LineageMetrics metrics,
                                    java.util.function.Supplier<String> eventIdAllocator,
                                    java.util.function.LongSupplier clockMs,
                                    LineageChunkPlanner.ChunkLimits chunkLimits) {
        this(decisions, journal, v2reads, resolver, spool, metrics, eventIdAllocator, clockMs,
                chunkLimits, 4L * 1024 * 1024);
    }

    public LineageSpoolMaterializer(LineageMaterializationStore decisions,
                                    LineageJournalStore journal,
                                    LineageV2TransitionStore v2reads,
                                    WriteVersionResolver resolver,
                                    LineageFactSpool spool,
                                    LineageMetrics metrics,
                                    java.util.function.Supplier<String> eventIdAllocator,
                                    java.util.function.LongSupplier clockMs,
                                    LineageChunkPlanner.ChunkLimits chunkLimits,
                                    long maxDocumentBytes) {
        this.chunkLimits = chunkLimits;
        this.maxDocumentBytes = maxDocumentBytes;
        this.decisions = decisions;
        this.journal = journal;
        this.v2reads = v2reads;
        this.resolver = resolver;
        this.spool = spool;
        this.metrics = metrics;
        this.eventIdAllocator = eventIdAllocator;
        this.clockMs = clockMs;
    }

    @Override
    public void materialize(LineageSpoolPayloadV1 verifiedFact) {
        // Path-less entry point (D-spool seam compat): no ACK IO possible — used only by
        // legacy callers; the scanner always calls the 2-arg form.
        materialize(verifiedFact, null);
    }

    @Override
    public MaterializeResult materialize(LineageSpoolPayloadV1 payload, Path factFile) {
        boolean brokenAck = false;
        try {
            // 1. A valid ACK — fully verified on EVERY encounter — suppresses work (B2).
            // Absence and unreadability route differently: an unreadable canonical ACK is
            // BROKEN and goes through hard-link repair, never treated as merely missing.
            if (factFile != null) {
                // A verified PARKING marker suppresses work exactly like a verified ACK, and
                // is re-verified on every encounter for the same reason (a forged marker must
                // never suppress).
                LineageFactSpool.AckRead parked = spool.readOversizeMarker(factFile);
                if (parked instanceof LineageFactSpool.AckBytes parkedBytes) {
                    if (isValidOversizeMarker(parkedBytes.bytes(), payload)) {
                        return new MaterializeResult(Outcome.ALREADY_ACKED, false);
                    }
                    logger.error("Broken/forged oversize marker for {} — quarantining",
                            payload.spoolRecordId());
                    spool.repairInvalidOversizeMarker(factFile);
                } else if (parked instanceof LineageFactSpool.AckUnreadable) {
                    spool.repairInvalidOversizeMarker(factFile);
                }
                LineageFactSpool.AckRead read = spool.readAck(factFile);
                if (read instanceof LineageFactSpool.AckBytes bytes) {
                    if (isValidAck(bytes.bytes(), payload)) {
                        if (metrics != null) {
                            metrics.recordAckVerified();
                        }
                        return new MaterializeResult(Outcome.ALREADY_ACKED, false);
                    }
                    brokenAck = true;
                } else if (read instanceof LineageFactSpool.AckUnreadable) {
                    brokenAck = true;
                }
                if (brokenAck) {
                    logger.error("Broken/forged/unreadable ACK for {} — quarantining,"
                            + " materialization proceeds (a forged ACK must never suppress"
                            + " work)", payload.spoolRecordId());
                    if (metrics != null) {
                        metrics.recordAckBroken();
                    }
                    spool.repairInvalidAck(LineageFactSpool.ackPathFor(factFile));
                }
            }

            // 2. The decision: existing (bound to THIS fact, A2) or newly resolved.
            LineageMaterializationDecision decision =
                    decisions.readDecision(payload.spoolRecordId());
            if (decision != null) {
                requireDecisionBinding(decision, payload);
            } else {
                Optional<WriteVersionResolver.ResolvedWrite> resolved =
                        resolver.resolve(payload);
                if (resolved.isEmpty()) {
                    if (metrics != null) {
                        metrics.recordUnresolvedSkipped();
                    }
                    return new MaterializeResult(Outcome.UNRESOLVED, brokenAck);
                }
                decision = decisions.createDecisionIfAbsent(
                        buildDecision(payload, resolved.get()));
                requireDecisionBinding(decision, payload);
            }

            // 3. Pre-write drift fence (A1): reconstruction must equal the frozen entries
            // BEFORE anything is written.
            List<PlannedRow> planned = reconstructAndCompare(payload, decision);

            // 4. Write each row (create-if-absent, digest-exact convergence). CouchDB's own
            // size verdict (D1) is the authority on storability: it parks the fact
            // deterministically instead of retrying an impossible write forever.
            for (PlannedRow row : planned) {
                try {
                    row.write();
                } catch (LineageMaterializationStore.DocumentTooLargeException tooLarge) {
                    long measured = LineageChunkPlanner.measure(payload,
                            new LineageChunkPlanner.ChunkSlice(payload.inputs(),
                                    payload.outputs()),
                            decision.allocatedEventId(), decision.creationClassification());
                    return parkOversize(payload, factFile, tooLarge.getMessage(), measured,
                            maxDocumentBytes);
                }
            }

            // 5. Reread + verify EVERY row (durability fence) — only then the ACK.
            for (PlannedRow row : planned) {
                if (!row.rereadAndVerify()) {
                    logger.error("Materialized row for {} not yet verifiable — no ACK this"
                            + " pass", payload.spoolRecordId());
                    return new MaterializeResult(Outcome.PARTIAL, brokenAck);
                }
            }
            if (factFile == null) {
                return new MaterializeResult(Outcome.PARTIAL, brokenAck);
            }
            byte[] ack = ackBytes(payload, decision);
            LineageFactSpool.AckOutcome published = spool.publishAck(factFile, ack);
            switch (published) {
                case PUBLISHED, IDEMPOTENT -> {
                    if (metrics != null) {
                        metrics.recordMaterialized();
                    }
                    return new MaterializeResult(Outcome.ACKED, brokenAck);
                }
                case CONFLICT -> {
                    // Someone else's ACK landed between our check and publish; verify it.
                    LineageFactSpool.AckRead occupant = spool.readAck(factFile);
                    if (occupant instanceof LineageFactSpool.AckBytes b
                            && isValidAck(b.bytes(), payload)) {
                        if (metrics != null) {
                            metrics.recordAckVerified();
                        }
                        return new MaterializeResult(Outcome.ALREADY_ACKED, brokenAck);
                    }
                    logger.error("ACK conflict with invalid occupant for {} — repaired next"
                            + " pass", payload.spoolRecordId());
                    return new MaterializeResult(Outcome.PARTIAL, true);
                }
                default -> {
                    return new MaterializeResult(Outcome.PARTIAL, brokenAck);
                }
            }
        } catch (LineageIntegrityException integrity) {
            logger.error("Materialization integrity refusal for {}: {}",
                    payload.spoolRecordId(), integrity.getMessage());
            return new MaterializeResult(Outcome.FAILED, brokenAck);
        } catch (RuntimeException e) {
            logger.error("Materialization failed for {}: {}", payload.spoolRecordId(),
                    e.getMessage());
            return new MaterializeResult(Outcome.FAILED, brokenAck);
        }
    }

    /**
     * Parks a fact CouchDB refuses to store: a deterministic marker beside it (published like
     * the ACK), the fact file retained as evidence, and the fact removed from the work set —
     * no wedge, no corruption-quarantine reuse.
     */
    private MaterializeResult parkOversize(LineageSpoolPayloadV1 payload, Path factFile,
                                           String detail, long measuredBytes,
                                           long ceilingBytes) {
        logger.error("CouchDB refused the materialized document for {} — parking:"
                + " {}", payload.spoolRecordId(), detail);
        if (metrics != null) {
            metrics.recordOversizeParked();
        }
        if (factFile == null) {
            return new MaterializeResult(Outcome.FAILED, false);
        }
        // Deterministic evidence: what we measured, what the guard rail allowed, and the
        // canonical hash of the whole fact's endpoint records — all re-derivable, so the
        // verification can recompute rather than trust them.
        byte[] marker = oversizeMarkerBytes(payload, measuredBytes, ceilingBytes,
                evidenceHashOf(payload));
        LineageFactSpool.AckOutcome published = spool.publishOversizeMarker(factFile, marker);
        return new MaterializeResult(
                published == LineageFactSpool.AckOutcome.PUBLISHED
                        || published == LineageFactSpool.AckOutcome.IDEMPOTENT
                        ? Outcome.ALREADY_ACKED : Outcome.PARTIAL, false);
    }

    // ---------------------------------------------------------------- decision + binding

    private void requireDecisionBinding(LineageMaterializationDecision decision,
                                        LineageSpoolPayloadV1 payload) {
        // A2: every edge, explicitly. The typed decode already pinned _id vs spoolRecordId.
        if (!decision.spoolRecordId().equals(payload.spoolRecordId())
                || !decision.factPayloadDigest().equals(payload.payloadDigest())) {
            throw new LineageIntegrityException(decision.documentId(),
                    decision.factPayloadDigest(),
                    "decision is bound to a DIFFERENT fact than the one in hand (restored"
                            + " conflicting fact under the same spool identity?)");
        }
    }

    private LineageMaterializationDecision buildDecision(LineageSpoolPayloadV1 payload,
            WriteVersionResolver.ResolvedWrite resolved) {
        long now = clockMs.getAsLong();
        if (resolved.materializeSchemaVersion() == 1) {
            LineageFact.LegacyV1Projection legacy = payload.legacyV1Projection();
            if (legacy == null) {
                throw new LineageIntegrityException(payload.spoolRecordId(), "",
                        "resolver chose schema 1 but the fact carries no LegacyV1Projection"
                                + " — v1 cannot be reconstructed faithfully");
            }
            String eventId = legacy.presetEventId() != null ? legacy.presetEventId()
                    : eventIdAllocator.get();
            LineageEvent event = v1EventOf(payload, legacy, eventId);
            String digest = LineageSpoolIdentity.v1EventDigest(eventId, event.eventKey(),
                    event.repositoryId(), event.processType(), event.inputs(),
                    event.outputs(), event.snapshotAttributes(), event.occurredAt(),
                    event.correlationId());
            return LineageMaterializationDecision.of(payload.spoolRecordId(),
                    payload.payloadDigest(), 1, resolved.barrierGeneration(), eventId,
                    List.of(new LineageMaterializationDecision.V1Entry(eventId, digest)),
                    now);
        }
        String eventId = eventIdAllocator.get();
        List<LineageChunkPlanner.ChunkSlice> slices;
        Map<String, LineageMaterializationDecision.CreationClassification> classification =
                Map.of();
        try {
            slices = LineageChunkPlanner.partition(payload, chunkLimits, eventId);
        } catch (LineageChunkPlanner.OversizeException oversize) {
            // §2: the whole fact becomes ONE terminal row — publishing the fitting half of a
            // process would emit a lineage process that claims to be complete.
            slices = List.of(new LineageChunkPlanner.ChunkSlice(payload.inputs(),
                    payload.outputs()));
            var reason = new LineageTargetLifecycle.TerminalReason("OVERSIZE",
                    oversize.getMessage() + " endpointRecordHash="
                            + oversize.offendingEndpointRecordHash(),
                    now); // frozen with the decision — never a fresh clock read
            Map<String, LineageMaterializationDecision.CreationClassification> classified =
                    new LinkedHashMap<>();
            for (String target : payload.canonicalTargetSet()) {
                classified.put(target,
                        new LineageMaterializationDecision.CreationClassification(
                                LineagePublishStatus.UNRESOLVED, reason));
            }
            classification = classified;
        }
        List<LineageMaterializationDecision.PlanEntry> entries = new ArrayList<>();
        for (int i = 0; i < slices.size(); i++) {
            LineageEventV2 event = v2EventOf(payload, eventId, slices.get(i), i,
                    slices.size());
            entries.add(new LineageMaterializationDecision.V2Entry(i, event.deliveryId(),
                    event.creationPayloadDigest()));
        }
        return LineageMaterializationDecision.ofV3(payload.spoolRecordId(),
                payload.payloadDigest(), resolved.barrierGeneration(), eventId, entries, now,
                LineageChunkPlanner.PARTITION_VERSION, chunkLimits, classification);
    }

    // ---------------------------------------------------------------- reconstruction

    /** Deterministic v1 event: direct record construction, no clock, no random. */
    static LineageEvent v1EventOf(LineageSpoolPayloadV1 payload,
                                  LineageFact.LegacyV1Projection legacy, String eventId) {
        Map<String, LineagePublishStatus> statuses = new LinkedHashMap<>();
        for (String target : payload.canonicalTargetSet()) {
            statuses.put(target, LineagePublishStatus.PENDING);
        }
        return new LineageEvent(1, eventId,
                LineageEvent.computeEventKey(payload.repositoryId(), legacy.processType(),
                        legacy.inputs(), legacy.outputs()),
                0L, payload.occurredAt(), payload.repositoryId(), legacy.processType(),
                legacy.inputs(), legacy.outputs(), "",
                payload.correlationId() == null ? "" : payload.correlationId(), 1,
                legacy.snapshotAttributes(), statuses);
    }

    /** Deterministic v2 event via the pure builder — the whole fact, chunk(0,1). */
    static LineageEventV2 v2EventOf(LineageSpoolPayloadV1 payload, String eventId) {
        return v2EventOf(payload, eventId,
                new LineageChunkPlanner.ChunkSlice(payload.inputs(), payload.outputs()),
                (int) payload.chunkIndex(), (int) payload.chunkCount());
    }

    /**
     * Deterministic v2 event for ONE chunk slice (v2.3.22): the slice's endpoints at the
     * slice's chunk coordinates. Everything else is the fact, verbatim.
     */
    static LineageEventV2 v2EventOf(LineageSpoolPayloadV1 payload, String eventId,
                                    LineageChunkPlanner.ChunkSlice slice, int chunkIndex,
                                    int chunkCount) {
        LineageEventV2Builder builder = new LineageEventV2Builder()
                .eventId(eventId)
                .occurredAt(payload.occurredAt())
                .repositoryId(payload.repositoryId())
                .processType(payload.processType())
                .operationId(payload.operationId())
                .delivery(new LineageDelivery.Original(payload.canonicalTargetSet()))
                .chunk(chunkIndex, chunkCount)
                .sequenceNumber(0L)
                .spoolRecordId(payload.spoolRecordId());
        if (payload.correlationId() != null) {
            builder.correlationId(payload.correlationId());
        }
        LineageFact.LegacyV1Projection legacy = payload.legacyV1Projection();
        if (legacy != null) {
            builder.legacyEventKey(LineageEvent.computeEventKey(payload.repositoryId(),
                    legacy.processType(), legacy.inputs(), legacy.outputs()));
        }
        slice.inputs().forEach(builder::addInput);
        slice.outputs().forEach(builder::addOutput);
        return builder.build();
    }

    /** One frozen entry, reconstructed and drift-checked, ready to write and verify. */
    private interface PlannedRow {
        void write();

        boolean rereadAndVerify();
    }

    private List<PlannedRow> reconstructAndCompare(LineageSpoolPayloadV1 payload,
            LineageMaterializationDecision decision) {
        List<PlannedRow> planned = new ArrayList<>();
        if (decision.materializeSchemaVersion() == 1) {
            LineageFact.LegacyV1Projection legacy = payload.legacyV1Projection();
            if (legacy == null) {
                throw new LineageIntegrityException(decision.documentId(), "",
                        "a v1 decision exists but the fact in hand has no legacy projection");
            }
            LineageMaterializationDecision.V1Entry entry =
                    (LineageMaterializationDecision.V1Entry) decision.planEntries().get(0);
            LineageEvent event = v1EventOf(payload, legacy, decision.allocatedEventId());
            String digest = LineageSpoolIdentity.v1EventDigest(event.eventId(),
                    event.eventKey(), event.repositoryId(), event.processType(),
                    event.inputs(), event.outputs(), event.snapshotAttributes(),
                    event.occurredAt(), event.correlationId());
            if (!entry.eventId().equals(event.eventId())
                    || !entry.v1EventDigest().equals(digest)) {
                throw new LineageIntegrityException(decision.documentId(), entry.v1EventDigest(),
                        "reconstruction drifted from the frozen v1 plan entry — nothing"
                                + " written (A1)");
            }
            LineageMaterializationStore store = (LineageMaterializationStore) journal;
            planned.add(new PlannedRow() {
                @Override
                public void write() {
                    store.createMaterializedV1RowIfAbsent(event, entry.v1EventDigest());
                }

                @Override
                public boolean rereadAndVerify() {
                    LineageMaterializationStore.MaterializedV1Row stored =
                            store.readMaterializedV1RowStrict(entry.eventId());
                    if (stored == null) {
                        return false;
                    }
                    String recomputed = LineageSpoolIdentity.v1EventDigest(
                            stored.event().eventId(), stored.event().eventKey(),
                            stored.event().repositoryId(), stored.event().processType(),
                            stored.event().inputs(), stored.event().outputs(),
                            stored.event().snapshotAttributes(), stored.event().occurredAt(),
                            stored.event().correlationId());
                    return recomputed.equals(entry.v1EventDigest())
                            && stored.event().eventId().equals(decision.allocatedEventId());
                }
            });
            return planned;
        }
        // Re-derive the partition under the DECISION's frozen limits (never live config), so
        // a config change after the decision cannot re-partition and wedge the fact.
        List<LineageChunkPlanner.ChunkSlice> slices;
        boolean classified = !decision.creationClassification().isEmpty();
        LineageChunkPlanner.ChunkSlice wholeFact =
                new LineageChunkPlanner.ChunkSlice(payload.inputs(), payload.outputs());
        if (classified) {
            slices = List.of(wholeFact);
        } else if (decision.chunkLimits() != null) {
            slices = LineageChunkPlanner.partition(payload, decision.chunkLimits(),
                    decision.allocatedEventId());
        } else {
            // A pre-chunking V2 decision: every entry reconstructed the WHOLE fact at the
            // fact's own chunk coordinates — including historical multi-entry decisions,
            // whose shape v2.3.22 deliberately did not narrow (F2).
            List<LineageChunkPlanner.ChunkSlice> legacy = new ArrayList<>();
            for (int i = 0; i < decision.planEntries().size(); i++) {
                legacy.add(wholeFact);
            }
            slices = legacy;
        }
        if (slices.size() != decision.planEntries().size()) {
            throw new LineageIntegrityException(decision.documentId(),
                    decision.materializationPlanDigest(),
                    "the partition re-derived " + slices.size() + " chunks but the frozen plan"
                            + " has " + decision.planEntries().size() + " — nothing written");
        }
        for (int i = 0; i < decision.planEntries().size(); i++) {
            LineageMaterializationDecision.V2Entry entry =
                    (LineageMaterializationDecision.V2Entry) decision.planEntries().get(i);
            boolean legacyV2 = decision.chunkLimits() == null && !classified;
            LineageEventV2 event = legacyV2
                    ? v2EventOf(payload, decision.allocatedEventId(), slices.get(i),
                            (int) payload.chunkIndex(), (int) payload.chunkCount())
                    : v2EventOf(payload, decision.allocatedEventId(), slices.get(i), i,
                            slices.size());
            if (!entry.deliveryId().equals(event.deliveryId())
                    || !entry.eventDigest().equals(event.creationPayloadDigest())) {
                throw new LineageIntegrityException(decision.documentId(), entry.eventDigest(),
                        "reconstruction drifted from the frozen v2 plan entry — nothing"
                                + " written (A1)");
            }
            LineageEventV2 toWrite = classified
                    ? withClassifiedStatuses(event, decision.creationClassification())
                    : event;
            planned.add(new PlannedRow() {
                @Override
                public void write() {
                    if (classified) {
                        ((LineageMaterializationStore) journal).appendV2Classified(toWrite,
                                decision.creationClassification());
                    } else {
                        journal.appendV2(event);
                    }
                }

                @Override
                public boolean rereadAndVerify() {
                    LineageJournalRowV2 stored = v2reads.findV2ByRecordId(entry.deliveryId());
                    if (stored == null
                            || !stored.event().creationPayloadDigest()
                                    .equals(entry.eventDigest())
                            || !stored.event().eventId()
                                    .equals(decision.allocatedEventId())) {
                        return false;
                    }
                    // C1: the classification is part of the decision, so it is part of what
                    // the pre-ACK verification checks — a PENDING row where the decision says
                    // UNRESOLVED is not the row the decision committed to.
                    for (var e : decision.creationClassification().entrySet()) {
                        LineageTargetLifecycle lifecycle =
                                stored.targetLifecycles().get(e.getKey());
                        if (lifecycle == null || lifecycle.status() != e.getValue().status()
                                || !e.getValue().reason()
                                        .equals(lifecycle.terminalReason())) {
                            // Value equality: the DETAIL is the evidence, so a row with
                            // different evidence is not the row the decision committed to.
                            return false;
                        }
                    }
                    return true;
                }
            });
        }
        return planned;
    }

    // ---------------------------------------------------------------- ACK bytes + checks

    /** The event with its targets' statuses set to the decision's classification. */
    static LineageEventV2 withClassifiedStatuses(LineageEventV2 event,
            Map<String, LineageMaterializationDecision.CreationClassification> classification) {
        Map<String, LineagePublishStatus> statuses = new LinkedHashMap<>();
        classification.forEach((target, c) -> statuses.put(target, c.status()));
        return new LineageEventV2(event.schemaVersion(), event.idempotencyKeyVersion(),
                event.eventId(), event.processKey(), event.delivery(), event.deliveryId(),
                event.repositoryId(), event.processType(), event.operationId(),
                event.occurredAt(), event.inputs(), event.outputs(), event.chunkIndex(),
                event.chunkCount(), event.sequenceNumber(), event.correlationId(),
                event.spoolRecordId(), event.legacyEventKey(), statuses,
                event.creationPayloadDigest());
    }

    /** The parking marker's deterministic body (v2.3.22 C3). */
    static byte[] oversizeMarkerBytes(LineageSpoolPayloadV1 payload, long measuredBytes,
                                      long ceilingBytes, String endpointRecordHash) {
        String json = "{\"spoolRecordId\":\"" + payload.spoolRecordId()
                + "\",\"factPayloadDigest\":\"" + payload.payloadDigest()
                + "\",\"reason\":\"OVERSIZE_UNSTORABLE\",\"measuredBytes\":" + measuredBytes
                + ",\"ceilingBytes\":" + ceilingBytes
                + ",\"offendingEndpointRecordHash\":\"" + endpointRecordHash + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** The canonical hash of the fact's COMPLETE endpoint records — the marker's evidence. */
    static String evidenceHashOf(LineageSpoolPayloadV1 payload) {
        return LineageCanonicalHash.hash("OVERSIZE_ENDPOINT_V1",
                LineageEventDigest.endpointRecords(payload.inputs()),
                LineageEventDigest.endpointRecords(payload.outputs()));
    }

    /** Every binding of a parking marker, verified on every encounter. */
    boolean isValidOversizeMarker(byte[] markerBytes, LineageSpoolPayloadV1 payload) {
        try {
            Map<?, ?> marker = JSON.readValue(markerBytes, Map.class);
            // The COMPLETE shape, every time: a marker missing its evidence is not a marker
            // this materializer wrote, and must not suppress work (F7).
            if (marker.size() != 6) {
                return false;
            }
            Object measured = marker.get("measuredBytes");
            Object ceiling = marker.get("ceilingBytes");
            Object hash = marker.get("offendingEndpointRecordHash");
            if (!(measured instanceof Number measuredBytes)
                    || !(ceiling instanceof Number ceilingBytes)
                    || !(hash instanceof String hashText)) {
                return false;
            }
            // The evidence is RECOMPUTED, not merely shaped (round-2 R2): a marker whose
            // hash, ceiling or measurement was altered is not a marker this materializer
            // wrote for this fact, and must not suppress work.
            if (!hashText.equals(evidenceHashOf(payload))) {
                return false;
            }
            if (ceilingBytes.longValue() != maxDocumentBytes) {
                return false;
            }
            if (measuredBytes.longValue() <= 0) {
                return false;
            }
            return payload.spoolRecordId().equals(marker.get("spoolRecordId"))
                    && payload.payloadDigest().equals(marker.get("factPayloadDigest"))
                    && "OVERSIZE_UNSTORABLE".equals(marker.get("reason"));
        } catch (Exception e) {
            return false;
        }
    }

    /** The frozen four-field ACK body, in fixed order — deterministic bytes. */
    static byte[] ackBytes(LineageSpoolPayloadV1 payload,
                           LineageMaterializationDecision decision) {
        String json = "{\"spoolRecordId\":\"" + payload.spoolRecordId()
                + "\",\"factPayloadDigest\":\"" + payload.payloadDigest()
                + "\",\"parentDecisionId\":\"" + decision.documentId()
                + "\",\"materializationPlanDigest\":\""
                + decision.materializationPlanDigest() + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Every edge of the fact↔decision↔ACK triangle, verified strictly (A2/B2). */
    boolean isValidAck(byte[] ackBytes, LineageSpoolPayloadV1 payload) {
        try {
            Map<?, ?> ack = JSON.readValue(ackBytes, Map.class);
            if (ack.size() != 4) {
                return false;
            }
            Object spoolRecordId = ack.get("spoolRecordId");
            Object factDigest = ack.get("factPayloadDigest");
            Object parentId = ack.get("parentDecisionId");
            Object planDigest = ack.get("materializationPlanDigest");
            if (!(spoolRecordId instanceof String s) || !(factDigest instanceof String f)
                    || !(parentId instanceof String p) || !(planDigest instanceof String d)) {
                return false;
            }
            if (!s.equals(payload.spoolRecordId()) || !f.equals(payload.payloadDigest())) {
                return false;
            }
            LineageMaterializationDecision decision = decisions.readDecision(s);
            return decision != null
                    && p.equals(decision.documentId())
                    && d.equals(decision.materializationPlanDigest())
                    && decision.factPayloadDigest().equals(f);
        } catch (Exception e) {
            return false;
        }
    }
}
