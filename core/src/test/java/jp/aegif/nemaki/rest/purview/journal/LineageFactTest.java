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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link LineageFact}, the emitter seam and the fail-open facade — producer P-1.
 *
 * <p>Two invariants carry the whole design: the v1 event a fact produces is <b>string-identical</b>
 * to what the old builder produced (eventKey is a hash of those strings), and no fact can exist
 * that the v2 shape table rejects (so the flip discovers no mis-shaped producer).
 */
public class LineageFactTest {

    private static final String REPO = "bedroom";
    private static final String OCCURRED = "2026-08-01T00:00:00Z";

    /** The ARCHIVE_LOCAL fact as RetentionScheduler will build it. */
    private static LineageFact archiveFact() {
        return new LineageFact(
                REPO,
                LineageProcessType.ARCHIVE_LOCAL,
                "op-1",
                OCCURRED,
                List.of(LineageEndpoint.document(REPO, "doc-1", "a.txt")),
                List.of(LineageEndpoint.archive(REPO, "doc-1", "doc-1", 1_700_000_000_000L)),
                List.of("purview"),
                null,
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.ARCHIVE_LOCAL,
                        List.of("nemaki://bedroom/objects/doc-1"),
                        List.of("nemaki://bedroom/archives/doc-1"),
                        Map.of("reason", "retention")));
    }

    // ------------------------------------------------------------------ v1 preservation

    /**
     * The central property. The fact's v1 event must equal the old builder's output on every
     * field that matters — eventKey above all, because it hashes the raw strings and is the
     * catalog Process identity across the deploy.
     */
    @Test
    public void theV1EventIsStringIdenticalToTheOldBuilders() {
        LineageEvent old = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .addOutput("nemaki://bedroom/archives/doc-1")
                .snapshotAttribute("reason", "retention")
                .targets(List.of("purview"))
                .build();

        LineageEvent fromFact = archiveFact().toV1Event();

        assertEquals(old.eventKey(), fromFact.eventKey(),
                "one changed character here splits every catalog Process of this type");
        assertEquals(old.repositoryId(), fromFact.repositoryId());
        assertEquals(old.processType(), fromFact.processType());
        assertEquals(old.inputs(), fromFact.inputs());
        assertEquals(old.outputs(), fromFact.outputs());
        assertEquals(old.snapshotAttributes(), fromFact.snapshotAttributes());
        assertEquals(old.publishStatusByTarget(), fromFact.publishStatusByTarget());
        assertEquals(old.runId(), fromFact.runId(), "runId stays empty — populating it would"
                + " change the persisted v1 envelope for no flip-relevant gain");
        assertEquals(old.version(), fromFact.version());
        assertEquals(old.sequenceNumber(), fromFact.sequenceNumber());
        assertEquals(old.schemaVersion(), fromFact.schemaVersion());
    }

    /**
     * The projection's process type participates in eventKey and may differ from the fact's own
     * classification — a null-archetype ingest is IMPORT_UPLOADED in v1 whatever v2 calls it.
     */
    @Test
    public void theLegacyProcessTypeDrivesTheV1EventNotTheFactsOwn() {
        LineageFact fact = new LineageFact(
                REPO,
                LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT,
                "op-1",
                OCCURRED,
                List.of(LineageEndpoint.externalAsset(REPO, "slack://t1/files/f1", "slack")),
                List.of(LineageEndpoint.document(REPO, "doc-1", "a.txt")),
                List.of("purview"),
                "corr-1",
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.IMPORT_UPLOADED,
                        List.of("slack://t1/files/f1"),
                        List.of("nemaki://bedroom/objects/doc-1"),
                        Map.of("sourceSystem", "slack")));

        LineageEvent v1 = fact.toV1Event();
        assertEquals(LineageProcessType.IMPORT_UPLOADED, v1.processType());
        assertEquals(LineageEvent.computeEventKey(REPO, LineageProcessType.IMPORT_UPLOADED,
                        List.of("slack://t1/files/f1"), List.of("nemaki://bedroom/objects/doc-1")),
                v1.eventKey());
        assertEquals("corr-1", v1.correlationId());
    }

    /** ZIP exports: v1 emits no output while the v2 shape requires the artifact. Both hold. */
    @Test
    public void aZipExportCarriesTheArtifactForV2AndNoOutputForV1() {
        LineageFact fact = new LineageFact(
                REPO,
                LineageProcessType.EXPORT_ZIP_FOLDER,
                "op-1",
                OCCURRED,
                List.of(LineageEndpoint.folder(REPO, "folder-1", "Docs")),
                List.of(LineageEndpoint.exportArtifact(REPO, "op-1", "ZIP", "out.zip", 3L)),
                List.of("purview"),
                null,
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.EXPORT_ZIP_FOLDER,
                        List.of("nemaki://bedroom/objects/folder-1"),
                        List.of(),
                        Map.of("objectCount", "3")));

        LineageEvent v1 = fact.toV1Event();
        assertEquals(List.of(), v1.outputs(), "v1 ZIP exports never had an output; inventing one"
                + " now would change every eventKey of the type");
        assertEquals(1, fact.outputs().size(), "the v2 side carries the artifact from day one");
    }

    /**
     * The ARCHIVE_COLD fact exactly as RetentionScheduler builds it, held against the old
     * builder's output. The typed side names the storage adapter as sourceSystem and carries the
     * archive's own archivedAt; the v1 side keeps the cold:// string and the originalId-or-empty
     * snapshot verbatim, because both are hashed into eventKey.
     */
    @Test
    public void theColdMoveFactPreservesTheV1StringsExactly() {
        LineageEvent old = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_COLD)
                .addInput("nemaki://bedroom/archives/arc-1")
                .addOutput("cold://s3://bucket/key-1")
                .snapshotAttribute("originalId", "doc-1")
                .snapshotAttribute("coldMoveMode", "MOVE")
                .targets(List.of("purview"))
                .build();

        java.util.Map<String, Object> archiveAttributes = new java.util.LinkedHashMap<>();
        archiveAttributes.put("archivedAt", 1_700_000_000_000L);
        archiveAttributes.put("originalObjectId", "doc-1");
        LineageFact fact = new LineageFact(
                REPO,
                LineageProcessType.ARCHIVE_COLD,
                "op-1",
                OCCURRED,
                List.of(new LineageEndpoint(EndpointKind.ARCHIVE,
                        LineageEndpoint.archiveQualifiedName(REPO, "arc-1"),
                        REPO, "arc-1", null, archiveAttributes)),
                List.of(LineageEndpoint.coldStorage(REPO, "s3://bucket/key-1", "S3Adapter")),
                List.of("purview"),
                null,
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.ARCHIVE_COLD,
                        List.of("nemaki://bedroom/archives/arc-1"),
                        List.of("cold://s3://bucket/key-1"),
                        Map.of("originalId", "doc-1", "coldMoveMode", "MOVE")));

        LineageEvent fromFact = fact.toV1Event();
        assertEquals(old.eventKey(), fromFact.eventKey());
        assertEquals(old.inputs(), fromFact.inputs());
        assertEquals(old.outputs(), fromFact.outputs());
        assertEquals(old.snapshotAttributes(), fromFact.snapshotAttributes());
    }

    /** occurredAt is the fact's (allocated at establishment), not re-stamped at build. */
    @Test
    public void theV1EventCarriesTheFactsOccurredAt() {
        assertEquals(OCCURRED, archiveFact().toV1Event().occurredAt());
    }

    /** eventId is audit-only and fresh per emission, exactly as the old builder behaved. */
    @Test
    public void eventIdsAreFreshPerEmissionAndNothingElseDiffers() {
        LineageFact fact = archiveFact();
        LineageEvent first = fact.toV1Event();
        LineageEvent second = fact.toV1Event();
        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(first.eventKey(), second.eventKey());
        assertEquals(first.occurredAt(), second.occurredAt());
    }

    // ------------------------------------------------------------------ v2-valid at construction

    /**
     * The flip must discover no mis-shaped producer, so the shape table binds now. A fact shaped
     * like today's v1 ZIP export (no artifact output) must not construct.
     */
    @Test
    public void aFactTheV2ShapeTableRejectsCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class, () -> new LineageFact(
                REPO,
                LineageProcessType.EXPORT_ZIP_FOLDER,
                "op-1",
                OCCURRED,
                List.of(LineageEndpoint.folder(REPO, "folder-1", "Docs")),
                List.of(), // v1's shape — not v2's
                List.of("purview"),
                null,
                new LineageFact.LegacyV1Projection(LineageProcessType.EXPORT_ZIP_FOLDER,
                        List.of("nemaki://bedroom/objects/folder-1"), List.of(), Map.of())));
    }

    @Test
    public void crossRepositoryEndpointsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LineageFact(
                REPO,
                LineageProcessType.ARCHIVE_LOCAL,
                "op-1",
                OCCURRED,
                List.of(LineageEndpoint.document("canopy", "doc-1", "a.txt")),
                List.of(LineageEndpoint.archive(REPO, "doc-1", "doc-1", 1L)),
                List.of("purview"),
                null,
                new LineageFact.LegacyV1Projection(LineageProcessType.ARCHIVE_LOCAL,
                        List.of(), List.of(), Map.of())));
    }

    @Test
    public void theSection3ContractsAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> factWith(null, OCCURRED),
                "operationId is server-issued and mandatory");
        assertThrows(IllegalArgumentException.class, () -> factWith("op-1", " "),
                "occurredAt is allocated once at fact establishment");
    }

    private static LineageFact factWith(String operationId, String occurredAt) {
        return new LineageFact(REPO, LineageProcessType.ARCHIVE_LOCAL, operationId, occurredAt,
                List.of(LineageEndpoint.document(REPO, "doc-1", "a.txt")),
                List.of(LineageEndpoint.archive(REPO, "doc-1", "doc-1", 1L)),
                List.of("purview"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.ARCHIVE_LOCAL,
                        List.of(), List.of(), Map.of()));
    }

    @Test
    public void theLegacyProjectionIsRequiredUntilTheFlip() {
        assertThrows(IllegalArgumentException.class, () -> new LineageFact(
                REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED,
                List.of(LineageEndpoint.document(REPO, "doc-1", "a.txt")),
                List.of(LineageEndpoint.archive(REPO, "doc-1", "doc-1", 1L)),
                List.of("purview"), null, null));
    }

    // ------------------------------------------------------------------ the emitter seam

    /**
     * The seam's whole claim: emit(fact) reaches only the v1 path today. If this test needs
     * changing, that change IS the write flip and belongs behind §6-a's fence.
     */
    @Test
    public void theSeamProjectsToV1AndNeverTouchesAppendV2() {
        LineageJournalStore store = mock(LineageJournalStore.class);
        LineageConfig config = mock(LineageConfig.class);
        when(config.getTargets()).thenReturn(List.of("purview"));
        JournaledLineageEmitter emitter = new JournaledLineageEmitter(store, config);

        emitter.emit(archiveFact());

        ArgumentCaptor<LineageEvent> appended = ArgumentCaptor.forClass(LineageEvent.class);
        verify(store).append(appended.capture());
        verify(store, never()).appendV2(any());
        assertEquals(1, appended.getValue().schemaVersion());
        assertEquals(archiveFact().toV1Event().eventKey(), appended.getValue().eventKey());
    }

    @Test
    public void theSeamIgnoresANullFact() {
        LineageJournalStore store = mock(LineageJournalStore.class);
        JournaledLineageEmitter emitter =
                new JournaledLineageEmitter(store, mock(LineageConfig.class));
        emitter.emit((LineageFact) null);
        verify(store, never()).append(any());
    }

    // ------------------------------------------------------------------ the fail-open facade

    /**
     * Construction is strict on purpose, and strictness must not leak into the business
     * response: a supplier that throws is a lost fact and a warning, never an exception.
     */
    @Test
    public void aThrowingFactSupplierIsAbsorbed() {
        LineageEmitter emitter = mock(LineageEmitter.class);
        when(emitter.isActive()).thenReturn(true);

        LineageFactEmission.emitSafely(emitter, () -> {
            throw new IllegalArgumentException("shape rejected");
        }, "repo=bedroom op=op-1 type=EXPORT_ZIP_FOLDER");

        verify(emitter, never()).emit(any(LineageFact.class));
    }

    @Test
    public void anEmitterThatBreaksItsNeverThrowContractIsStillContained() {
        LineageEmitter emitter = mock(LineageEmitter.class);
        when(emitter.isActive()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(emitter).emit(any(LineageFact.class));

        LineageFactEmission.emitSafely(emitter, LineageFactTest::archiveFact, "repo=bedroom");
        // reaching here IS the assertion
    }

    /** Lineage-off costs nothing: the supplier never runs. */
    @Test
    public void anInactiveEmitterSkipsConstructionEntirely() {
        LineageEmitter inactive = mock(LineageEmitter.class);
        when(inactive.isActive()).thenReturn(false);
        boolean[] ran = {false};

        LineageFactEmission.emitSafely(inactive, () -> {
            ran[0] = true;
            return archiveFact();
        }, "repo=bedroom");
        LineageFactEmission.emitSafely(null, () -> {
            ran[0] = true;
            return archiveFact();
        }, "repo=bedroom");

        assertTrue(!ran[0], "fact construction is not free; off means off");
    }

    @Test
    public void aNullFactFromTheSupplierIsANoOp() {
        LineageEmitter emitter = mock(LineageEmitter.class);
        when(emitter.isActive()).thenReturn(true);
        LineageFactEmission.emitSafely(emitter, () -> null, "repo=bedroom");
        verify(emitter, never()).emit(any(LineageFact.class));
    }

    @Test
    public void aHealthyFactGoesThrough() {
        LineageEmitter emitter = mock(LineageEmitter.class);
        when(emitter.isActive()).thenReturn(true);
        LineageFactEmission.emitSafely(emitter, LineageFactTest::archiveFact, "repo=bedroom");
        verify(emitter).emit(any(LineageFact.class));
    }
}
