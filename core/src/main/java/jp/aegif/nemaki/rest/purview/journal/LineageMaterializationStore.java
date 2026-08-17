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

/**
 * v2.3.18 ⑦'s store surface for the convergent materializer (D-rest-4).
 *
 * <p>Strict-IO rules as everywhere in D-rest: absence and CAS loss are ordinary answers,
 * infrastructure failures are {@link LineageSequencingStore.SequencingStorageException} and
 * propagate; digest disagreement is {@link LineageIntegrityException}.
 */
public interface LineageMaterializationStore {

    /**
     * Create-if-absent of the parent decision under
     * {@code lineage_materialization:{spoolRecordId}}. On 409 the occupant is decoded
     * strictly (which recomputes the plan digest) and this call succeeds IFF its
     * factPayloadDigest AND materializationPlanDigest match exactly — returning the STORED
     * decision, whose allocations are the frozen truth every later step must use.
     *
     * @throws LineageIntegrityException on an occupant with different content
     * @throws LineageSequencingStore.SequencingStorageException on infrastructure failure
     */
    LineageMaterializationDecision createDecisionIfAbsent(
            LineageMaterializationDecision decision);

    /**
     * Strict read of the decision; {@code null} when absent; malformed (including a plan
     * digest that does not recompute) throws — a tampered decision never becomes a value.
     *
     * @throws LineageSequencingStore.SequencingStorageException on malformed doc or infra
     */
    LineageMaterializationDecision readDecision(String spoolRecordId);

    /**
     * Strict raw read of a MATERIALIZED v1 row at {@code lineage:{eventId}} — the exact
     * writer shape, no defaulting: required {@code type=lineage_event}, integral
     * {@code schemaVersion=1}, string eventId/eventKey/occurredAt/repositoryId/runId/
     * correlationId, known processType, ordered raw string lists, integral sequenceNumber
     * and version; {@code snapshotAttributes}/{@code publishStatusByTarget} may be ABSENT
     * (the writer omits them when empty — absence decodes as canonical empty) but when
     * present must be correctly-typed maps. The permissive read paths are never part of
     * digest verification.
     *
     * @return the decoded event plus its {@code _rev}, or {@code null} when absent
     * @throws LineageSequencingStore.SequencingStorageException on malformed doc or infra
     */
    MaterializedV1Row readMaterializedV1RowStrict(String eventId);

    /** A strictly-decoded v1 row and the revision it was read at. */
    record MaterializedV1Row(LineageEvent event, String rev) {
    }

    /**
     * Create-if-absent of a materialized v1 row: allocates the sequence via the FENCED
     * allocator (shared counter, watermark-checked, fail-closed — never the eager v1
     * helper), writes the exact writer shape under {@code lineage:{eventId}}, and on 409
     * rereads strictly and succeeds IFF the occupant's recomputed v1EventDigest equals
     * {@code expectedV1EventDigest} (a burned sequence on a lost race is an accepted gap,
     * I-1..I-4).
     *
     * @throws LineageIntegrityException on an occupant with a different digest
     * @throws LineageSequencingStore.SequencingStorageException on infra failure, and
     *         {@link LineageSequencingStore.SequenceCounterException} when the counter is
     *         missing/rewound
     */
    void createMaterializedV1RowIfAbsent(LineageEvent event, String expectedV1EventDigest);

    /**
     * CouchDB's own verdict that a document cannot be stored (v2.3.22 D1). CouchDB measures
     * {@code max_document_size} on its internal representation, so no JSON-side ruler can
     * prove acceptance — this deterministic rejection is what routes a fact into the
     * {@code .oversize} parking path. Every other failure stays an infrastructure failure.
     */
    class DocumentTooLargeException extends RuntimeException {
        public DocumentTooLargeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * §2's creation-time classification (v2.3.22 B1/C1): writes the event with a TERMINAL
     * status and its durable reason for exactly the classified targets, atomically, in one
     * document. {@link LineageJournalStore#appendV2} stays PENDING-only and unchanged.
     *
     * <p>The status target set and the classification target set must be exactly equal, and
     * every status must be UNRESOLVED or REJECTED. A 409 converges only when the occupant's
     * creationPayloadDigest AND its complete per-target classification match — an existing
     * PENDING row at the same key is an integrity refusal, never a silent accept.
     *
     * @throws LineageIntegrityException on an occupant with different content or classification
     * @throws DocumentTooLargeException when CouchDB refuses the document for its size
     */
    void appendV2Classified(LineageEventV2 event,
            java.util.Map<String, LineageMaterializationDecision.CreationClassification>
                    classification);
}
