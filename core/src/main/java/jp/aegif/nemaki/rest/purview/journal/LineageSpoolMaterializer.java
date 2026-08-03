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

    public LineageSpoolMaterializer(LineageMaterializationStore decisions,
                                    LineageJournalStore journal,
                                    LineageV2TransitionStore v2reads,
                                    WriteVersionResolver resolver,
                                    LineageFactSpool spool,
                                    LineageMetrics metrics,
                                    java.util.function.Supplier<String> eventIdAllocator,
                                    java.util.function.LongSupplier clockMs) {
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

            // 4. Write each row (create-if-absent, digest-exact convergence).
            for (PlannedRow row : planned) {
                row.write();
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
        LineageEventV2 event = v2EventOf(payload, eventId);
        return LineageMaterializationDecision.of(payload.spoolRecordId(),
                payload.payloadDigest(), 2, resolved.barrierGeneration(), eventId,
                List.of(new LineageMaterializationDecision.V2Entry(payload.chunkIndex(),
                        event.deliveryId(), event.creationPayloadDigest())),
                now);
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

    /** Deterministic v2 event via the pure builder. */
    static LineageEventV2 v2EventOf(LineageSpoolPayloadV1 payload, String eventId) {
        LineageEventV2Builder builder = new LineageEventV2Builder()
                .eventId(eventId)
                .occurredAt(payload.occurredAt())
                .repositoryId(payload.repositoryId())
                .processType(payload.processType())
                .operationId(payload.operationId())
                .delivery(new LineageDelivery.Original(payload.canonicalTargetSet()))
                .chunk((int) payload.chunkIndex(), (int) payload.chunkCount())
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
        payload.inputs().forEach(builder::addInput);
        payload.outputs().forEach(builder::addOutput);
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
        for (LineageMaterializationDecision.PlanEntry planEntry : decision.planEntries()) {
            LineageMaterializationDecision.V2Entry entry =
                    (LineageMaterializationDecision.V2Entry) planEntry;
            LineageEventV2 event = v2EventOf(payload, decision.allocatedEventId());
            if (!entry.deliveryId().equals(event.deliveryId())
                    || !entry.eventDigest().equals(event.creationPayloadDigest())) {
                throw new LineageIntegrityException(decision.documentId(), entry.eventDigest(),
                        "reconstruction drifted from the frozen v2 plan entry — nothing"
                                + " written (A1)");
            }
            planned.add(new PlannedRow() {
                @Override
                public void write() {
                    journal.appendV2(event);
                }

                @Override
                public boolean rereadAndVerify() {
                    LineageJournalRowV2 stored = v2reads.findV2ByRecordId(entry.deliveryId());
                    return stored != null
                            && stored.event().creationPayloadDigest()
                                    .equals(entry.eventDigest())
                            && stored.event().eventId().equals(decision.allocatedEventId());
                }
            });
        }
        return planned;
    }

    // ---------------------------------------------------------------- ACK bytes + checks

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
