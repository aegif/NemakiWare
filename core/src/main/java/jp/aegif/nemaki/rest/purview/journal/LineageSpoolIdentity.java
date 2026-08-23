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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The spool identity formulas of §6-a — {@code spoolRecordId} and {@code payloadDigest} — on
 * the frozen {@link LineageCanonicalHash} rules.
 *
 * <p>{@code spoolRecordId} names a version-free business fact: the same fact retried after a
 * partial failure computes the same id, so a re-write cannot create a duplicate, while a user
 * repeating the operation gets a new {@code operationId} and therefore a new fact. It is the
 * spool file name and the materialisation key, which is why it is a domain-separated canonical
 * hash and not a UUID.
 *
 * <p>Identity ({@code spoolRecordId}) deliberately sees only the canonical qualified-name
 * sequences — the same rule §3 uses — so two facts over the same endpoints with different
 * attributes collide on id. Detecting that is {@code payloadDigest}'s job, which is why the
 * digest consumes {@link LineageEventDigest#endpointRecords} — the complete record of every
 * endpoint, attributes included, shared with the event digest rather than re-implemented so
 * the two cannot drift.
 *
 * <p>The digest also covers what identity deliberately does not: {@code correlationId} (both
 * materialisations need it) and the optional {@code LegacyV1Projection} — a fact spooled
 * because the write-version flag was unreadable may later materialise as schemaVersion 1, and
 * a v1 event cannot be reproduced from typed endpoints (inference was rejected; the v1
 * eventKey hashes the raw strings). The projection rides until schema-1 materialisation is
 * formally retired — not merely until the first flip, because {@code writeSchemaVersion} may
 * return to 1.
 *
 * <p>Frozen by golden vectors in {@code identity-golden-vectors.json} and re-derived by
 * {@code reference_hash.py}; a change here is a new spool schema version, never an edit.
 */
public final class LineageSpoolIdentity {

    /** Domain tag for {@code spoolRecordId} — the version-free fact identity. */
    static final String SPOOL_FACT_DOMAIN = "SPOOL_FACT_V1";

    /** Domain tag for {@code payloadDigest} — content integrity of one spool record. */
    static final String SPOOL_PAYLOAD_DOMAIN = "SPOOL_PAYLOAD_V1";

    /** Domain tag of the version-2 composition — appends the P1-1(e) extras. */
    static final String SPOOL_PAYLOAD_DOMAIN_V2 = "SPOOL_PAYLOAD_V2";

    private LineageSpoolIdentity() {
    }

    /**
     * {@code spoolRecordId} — 64 hex chars, the spool file name and materialisation key.
     *
     * <p>{@code occurredAt} is §3's once-allocated timestamp carried verbatim by retries;
     * re-deriving it would fork the id and double-materialise the fact, which is exactly what
     * the §3 pure-function contract forbids.
     */
    public static String spoolRecordId(String repositoryId, LineageProcessType processType,
                                       String operationId,
                                       List<LineageEndpoint> inputs,
                                       List<LineageEndpoint> outputs,
                                       Iterable<String> targets,
                                       long chunkIndex, long chunkCount,
                                       String occurredAt) {
        requireText(repositoryId, "repositoryId");
        if (processType == null) {
            throw new IllegalArgumentException("processType must not be null");
        }
        requireText(operationId, "operationId");
        requireText(occurredAt, "occurredAt");
        return LineageCanonicalHash.hash(
                SPOOL_FACT_DOMAIN,
                repositoryId,
                processType.name(),
                operationId,
                LineageCanonicalHash.canonicalQualifiedNames(inputs),
                LineageCanonicalHash.canonicalQualifiedNames(outputs),
                LineageCanonicalHash.canonicalTargetSet(targets),
                chunkIndex,
                chunkCount,
                occurredAt);
    }

    /**
     * {@code payloadDigest} — content integrity of one spool record. Computed at spool time,
     * stored in the record, and <b>recomputed</b> (never trusted) on every read.
     *
     * @param correlationId    the fact's, nullable — null and empty are different digests
     * @param legacyProjection nullable; when present its lists ride in <b>original order and
     *                         multiplicity</b> (never canonicalised — their exact bytes drive
     *                         the v1 eventKey)
     */
    public static String payloadDigest(String spoolRecordId, long spoolSchemaVersion,
                                       List<LineageEndpoint> inputs,
                                       List<LineageEndpoint> outputs,
                                       String correlationId,
                                       LineageFact.LegacyV1Projection legacyProjection) {
        requireText(spoolRecordId, "spoolRecordId");
        return LineageCanonicalHash.hash(
                SPOOL_PAYLOAD_DOMAIN,
                spoolRecordId,
                spoolSchemaVersion,
                LineageEventDigest.endpointRecords(inputs),
                LineageEventDigest.endpointRecords(outputs),
                correlationId,
                legacyProjectionRecord(legacyProjection));
    }

    /** The version-2 composition: V1's inputs, then the attribution pair and the compartments. */
    public static String payloadDigestV2(String spoolRecordId, long spoolSchemaVersion,
                                         List<LineageEndpoint> inputs,
                                         List<LineageEndpoint> outputs,
                                         String correlationId,
                                         LineageFact.LegacyV1Projection legacyProjection,
                                         LineageExecutionAttribution attribution,
                                         Map<String, String> journalFacts,
                                         Map<String, String> processFacts) {
        requireText(spoolRecordId, "spoolRecordId");
        return LineageCanonicalHash.hash(
                SPOOL_PAYLOAD_DOMAIN_V2,
                spoolRecordId,
                spoolSchemaVersion,
                LineageEventDigest.endpointRecords(inputs),
                LineageEventDigest.endpointRecords(outputs),
                correlationId,
                legacyProjectionRecord(legacyProjection),
                attribution == null ? null : attribution.executedBy(),
                attribution == null ? null : attribution.onBehalfOf(),
                journalFacts == null ? Map.of() : journalFacts,
                processFacts == null ? Map.of() : processFacts);
    }

    /**
     * The canonical MAP encoding of a {@link LineageFact.LegacyV1Projection}, or null.
     * Map keys are sorted by the hash encoding itself; the input/output LISTs keep their
     * declared order, which is the point.
     */
    static Map<String, Object> legacyProjectionRecord(LineageFact.LegacyV1Projection projection) {
        if (projection == null) {
            return null;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("processType", projection.processType().name());
        record.put("inputs", projection.inputs());
        record.put("outputs", projection.outputs());
        record.put("snapshotAttributes", projection.snapshotAttributes());
        record.put("presetEventId", projection.presetEventId());
        return record;
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be null or blank");
        }
    }

    /** Domain tag for {@code v1EventDigest} — identity of a materialized v1 journal row. */
    static final String V1_EVENT_DOMAIN = "SPOOL_V1_EVENT_V1";

    /** Domain tag for {@code materializationPlanDigest} (v2.3.21 — V2 binds the audit id). */
    static final String PLAN_DOMAIN = "MATERIALIZATION_PLAN_V2";

    /**
     * Domain tag for the chunk-aware plan digest (v2.3.22 — V3 additionally binds the
     * partition algorithm version, the frozen chunk limits and the creation classification).
     * V2 stays frozen and in use for schema-1 decisions; domains are never redefined.
     */
    static final String PLAN_DOMAIN_V3 = "MATERIALIZATION_PLAN_V3";

    /**
     * v2.3.18 ⑦'s v1EventDigest, on the frozen formula: the identity of the v1 row a plan
     * entry commits to. Covers the identity fields and deliberately EXCLUDES sequenceNumber
     * and targets (assignment-time / mutable), so crash retries converge on the stored row
     * regardless of which attempt's sequence landed.
     */
    public static String v1EventDigest(String eventId, String eventKey, String repositoryId,
                                       LineageProcessType processType,
                                       java.util.List<String> inputs,
                                       java.util.List<String> outputs,
                                       java.util.Map<String, String> snapshotAttributes,
                                       String occurredAt, String correlationId) {
        if (eventId == null || eventId.isBlank() || eventKey == null || eventKey.isBlank()) {
            throw new IllegalArgumentException("v1EventDigest requires eventId and eventKey");
        }
        if (processType == null) {
            throw new IllegalArgumentException("v1EventDigest requires the processType");
        }
        return LineageCanonicalHash.hash(V1_EVENT_DOMAIN, eventId, eventKey, repositoryId,
                processType.name(),
                inputs == null ? java.util.List.of() : inputs,
                outputs == null ? java.util.List.of() : outputs,
                snapshotAttributes == null ? java.util.Map.of() : snapshotAttributes,
                occurredAt, correlationId);
    }

    /**
     * The parent decision's plan digest (v2.3.21, domain V2): binds the fact, the resolved
     * schema version, the once-allocated audit id, and the COMPLETE plan entries. Recomputed
     * (never trusted) at every decode — a decision whose stored allocatedEventId or entries
     * were tampered never becomes a value.
     */
    public static String materializationPlanDigest(String spoolRecordId,
                                                   String factPayloadDigest,
                                                   long materializeSchemaVersion,
                                                   String allocatedEventId,
                                                   java.util.List<java.util.Map<String, Object>>
                                                           planEntryRecords) {
        if (spoolRecordId == null || spoolRecordId.isBlank()
                || factPayloadDigest == null || factPayloadDigest.isBlank()
                || allocatedEventId == null || allocatedEventId.isBlank()) {
            throw new IllegalArgumentException("plan digest requires spoolRecordId,"
                    + " factPayloadDigest and allocatedEventId");
        }
        if (planEntryRecords == null || planEntryRecords.isEmpty()) {
            throw new IllegalArgumentException("plan digest requires at least one entry");
        }
        return LineageCanonicalHash.hash(PLAN_DOMAIN, spoolRecordId, factPayloadDigest,
                materializeSchemaVersion, allocatedEventId, planEntryRecords);
    }

    /**
     * The chunk-aware plan digest (v2.3.22, domain V3). Binds everything a later binary needs
     * to reproduce the decision byte-for-byte: the fact, the resolved schema version, the
     * once-allocated audit id, the partition algorithm version, the limits that partition ran
     * under, the creation classification (status AND reason per target), and the complete
     * plan entries.
     */
    public static String materializationPlanDigestV3(String spoolRecordId,
            String factPayloadDigest, long materializeSchemaVersion, String allocatedEventId,
            long partitionVersion, java.util.Map<String, Object> chunkLimitsRecord,
            java.util.Map<String, Object> creationClassificationRecord,
            java.util.List<java.util.Map<String, Object>> planEntryRecords) {
        if (spoolRecordId == null || spoolRecordId.isBlank()
                || factPayloadDigest == null || factPayloadDigest.isBlank()
                || allocatedEventId == null || allocatedEventId.isBlank()) {
            throw new IllegalArgumentException("plan digest requires spoolRecordId,"
                    + " factPayloadDigest and allocatedEventId");
        }
        if (chunkLimitsRecord == null || chunkLimitsRecord.isEmpty()) {
            throw new IllegalArgumentException("a V3 plan digest requires its chunk limits");
        }
        if (planEntryRecords == null || planEntryRecords.isEmpty()) {
            throw new IllegalArgumentException("plan digest requires at least one entry");
        }
        return LineageCanonicalHash.hash(PLAN_DOMAIN_V3, spoolRecordId, factPayloadDigest,
                materializeSchemaVersion, allocatedEventId, partitionVersion,
                chunkLimitsRecord,
                creationClassificationRecord == null ? java.util.Map.of()
                        : creationClassificationRecord,
                planEntryRecords);
    }
}
