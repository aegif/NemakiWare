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
import java.util.Map;
import java.util.UUID;

/**
 * The version-free business fact a producer emits — one object, both emission forms.
 *
 * <h2>Why producers stop building events</h2>
 *
 * <p>The write flip (A-2 Slice 4) must be a change of <em>mapping choice inside the emitter</em>,
 * not a rewrite of twelve producer call sites under a fence. So a producer describes what
 * happened once — typed endpoints, the server-issued operation, when — and the emitter projects
 * it to v1 today and to v2 after the flip. This is the write-side mirror of {@code LineageRecord}
 * on the read side, and it is deliberately the same semantic shape as §6-a's spool fact, which
 * D-spool will persist (through a versioned payload type, not this class — facts evolve, durable
 * records must not).
 *
 * <h2>The v1 strings are carried, not inferred</h2>
 *
 * <p>v1's {@code eventKey} — the catalog Process identity and the append idempotency key — is a
 * hash of the raw input/output <em>strings</em>. One changed character splits every event of that
 * type into a new catalog Process and breaks replay idempotency across the deploy. An inference
 * table from typed endpoints back to v1 strings was evaluated and rejected: the real producers
 * are full of unmappable history — a null-archetype ingest is {@code IMPORT_UPLOADED} in v1, ZIP
 * exports emit no output at all, imports emit the destination folder rather than what was
 * created. So the fact carries a {@link LegacyV1Projection} — the v1 process type and exact
 * strings, stated by the producer that used to build them — and v1 emission uses it verbatim.
 * Preservation is by construction; the projection is deleted at the flip.
 *
 * <h2>Validated as v2 at construction</h2>
 *
 * <p>The typed side must satisfy the v2 shape table ({@link LineageProcessShape}) and A-1's
 * repository-scope and artifact-operation rules <em>now</em>, not at the flip — a producer
 * supplying a v2-invalid shape today would otherwise be discovered the day the writer changes.
 * That makes construction throwing, which is why producers go through
 * {@link LineageFactEmission}, never a bare constructor in a business method (the fail-open
 * boundary must hold even against this class's own strictness).
 */
public record LineageFact(
        String repositoryId,
        LineageProcessType processType,
        String operationId,
        String occurredAt,
        List<LineageEndpoint> inputs,
        List<LineageEndpoint> outputs,
        List<String> targets,
        String correlationId,
        LegacyV1Projection legacyProjection,
        LineageExecutionAttribution attribution,
        Map<String, String> processFacts,
        Map<String, String> journalFacts
) {

    /** The pre-(e) shape: no attribution, no fact compartments. */
    public LineageFact(String repositoryId, LineageProcessType processType, String operationId,
            String occurredAt, List<LineageEndpoint> inputs, List<LineageEndpoint> outputs,
            List<String> targets, String correlationId, LegacyV1Projection legacyProjection) {
        this(repositoryId, processType, operationId, occurredAt, inputs, outputs, targets,
                correlationId, legacyProjection, null, Map.of(), Map.of());
    }

    /**
     * The v1 emission, exactly as the producer used to build it.
     *
     * <p>{@code processType} is carried separately from the fact's own: a null-archetype ingest
     * is {@code IMPORT_UPLOADED} in v1 while the fact may classify it differently, and the v1
     * type participates in {@code eventKey}. {@code snapshotAttributes} are the event-level
     * strings v1 carries (requestedBy, reason, objectCount …); most have no v2 home and end with
     * the projection.
     */
    public record LegacyV1Projection(
            LineageProcessType processType,
            List<String> inputs,
            List<String> outputs,
            Map<String, String> snapshotAttributes,
            String presetEventId
    ) {
        public LegacyV1Projection {
            if (processType == null) {
                throw new IllegalArgumentException("legacy processType must not be null — it is"
                        + " part of the v1 eventKey");
            }
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            snapshotAttributes = snapshotAttributes == null ? Map.of()
                    : Map.copyOf(snapshotAttributes);
            presetEventId = presetEventId == null || presetEventId.isBlank()
                    ? null : presetEventId;
        }

        /**
         * The common form: the eventId is generated at emission (audit-only, as v1 always
         * treated it).
         *
         * <p>Preset it only when the producer must hand its caller an id that resolves to the
         * stored v1 event — ingest returns {@code lineageEventId} in its API response, and
         * returning an id the journal does not contain would break that contract. The preset
         * dies with the projection at the v2 flip.
         */
        public LegacyV1Projection(LineageProcessType processType, List<String> inputs,
                                  List<String> outputs, Map<String, String> snapshotAttributes) {
            this(processType, inputs, outputs, snapshotAttributes, null);
        }
    }

    public LineageFact {
        requireText(repositoryId, "repositoryId");
        if (processType == null) {
            throw new IllegalArgumentException("processType must not be null");
        }
        // The §3 contracts: the operation id is server-issued and mandatory, and occurredAt is
        // allocated once when the business fact is established — both before anything fallible,
        // both never re-derived.
        requireText(operationId, "operationId");
        requireText(occurredAt, "occurredAt");
        if (legacyProjection == null) {
            throw new IllegalArgumentException("legacyProjection must not be null until the v2"
                    + " flip: v1 emission uses its strings verbatim, and inferring them was"
                    + " rejected as unsound");
        }
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        targets = targets == null ? List.of() : List.copyOf(targets);
        correlationId = correlationId == null || correlationId.isBlank() ? null : correlationId;
        // The same closed sets the v2 event enforces — failing at fact construction puts the
        // discovery next to the producer, not inside the fenced rollout (same reasoning as the
        // shape checks below).
        processFacts = processFacts == null ? Map.of() : Map.copyOf(processFacts);
        journalFacts = journalFacts == null ? Map.of() : Map.copyOf(journalFacts);
        for (String key : processFacts.keySet()) {
            if (!LineageEventV2.PROCESS_FACT_KEYS.contains(key)) {
                throw new IllegalArgumentException("processFacts does not accept key '" + key
                        + "' — the compartments are closed sets (P1-1(e) §2.2)");
            }
        }
        for (String key : journalFacts.keySet()) {
            if (!LineageEventV2.JOURNAL_FACT_KEYS.contains(key)) {
                throw new IllegalArgumentException("journalFacts does not accept key '" + key
                        + "' — the compartments are closed sets (P1-1(e) §2.2)");
            }
        }

        // v2-valid from day one: A-1's scope rules and the shape table. A fact that only became
        // checkable at the flip would put the discovery of every mis-shaped producer inside the
        // fenced rollout.
        LineageRepositoryScope.validate(repositoryId, inputs, outputs);
        LineageRepositoryScope.validateArtifactOperation(operationId, inputs, outputs);
        LineageProcessShape.validate(processType, inputs, outputs);
    }

    /**
     * The v1 event, from the projection's strings verbatim.
     *
     * <p>{@code occurredAt} is the fact's — allocated at fact establishment, per §3 — where the
     * old builder stamped it inside {@code build()}. It is not part of {@code eventKey}, so the
     * change is invisible to identity. {@code eventId} is generated here (audit-only, as v1
     * always had it) unless the projection preset one so the producer could return a
     * resolvable id. {@code runId} stays empty: populating it would change the persisted v1
     * envelope for no flip-relevant gain.
     */
    public LineageEvent toV1Event() {
        Map<String, LineagePublishStatus> status = new java.util.LinkedHashMap<>();
        for (String target : targets) {
            status.put(target, LineagePublishStatus.PENDING);
        }
        return new LineageEvent(
                LineageEvent.CURRENT_SCHEMA_VERSION,
                legacyProjection.presetEventId() != null
                        ? legacyProjection.presetEventId()
                        : UUID.randomUUID().toString(),
                LineageEvent.computeEventKey(repositoryId, legacyProjection.processType(),
                        legacyProjection.inputs(), legacyProjection.outputs()),
                0L,
                occurredAt,
                repositoryId,
                legacyProjection.processType(),
                legacyProjection.inputs(),
                legacyProjection.outputs(),
                "",
                correlationId == null ? "" : correlationId,
                1,
                legacyProjection.snapshotAttributes(),
                status);
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be null or blank");
        }
    }
}
