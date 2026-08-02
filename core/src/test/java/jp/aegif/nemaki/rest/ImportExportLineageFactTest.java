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
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.rest.importexport.ImportExportUtils.CreatedObject;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ExportedObject;
import jp.aegif.nemaki.rest.purview.journal.EndpointKind;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageEventBuilder;
import jp.aegif.nemaki.rest.purview.journal.LineageFact;
import jp.aegif.nemaki.rest.purview.journal.LineageMode;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * Producer P-3's preservation contract, exercised through the PRODUCTION fact factories on
 * {@link ImportExportResource} — not through hand-built replicas, which would stay green while
 * the resource drifted. A changed legacy string, guard value, snapshot key or artifact kind
 * fails here instead of splitting every catalog Process identity at the next deploy.
 */
public class ImportExportLineageFactTest {

    private static final String REPO = "bedroom";
    private static final String OCCURRED = "2026-08-01T00:00:00Z";

    /** Full-field v1 equality — eventKey alone hashes sorted lists and would hide order bugs. */
    private static void assertSameV1Event(LineageEvent expected, LineageEvent actual) {
        assertEquals(expected.repositoryId(), actual.repositoryId());
        assertEquals(expected.processType(), actual.processType());
        assertEquals(expected.inputs(), actual.inputs(), "order and multiplicity included");
        assertEquals(expected.outputs(), actual.outputs());
        assertEquals(expected.snapshotAttributes(), actual.snapshotAttributes());
        assertEquals(expected.publishStatusByTarget(), actual.publishStatusByTarget());
        assertEquals(expected.correlationId(), actual.correlationId());
        assertEquals(expected.runId(), actual.runId());
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.sequenceNumber(), actual.sequenceNumber());
        assertEquals(expected.schemaVersion(), actual.schemaVersion());
        assertEquals(expected.eventKey(), actual.eventKey());
    }

    @Test
    public void theUploadedImportFactoryPreservesTheV1EventExactly() {
        LineageEvent old = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInput("upload://zip-upload")
                .addOutputObject(REPO, "target-folder")
                .snapshotAttribute("importMode", "zip-upload")
                .snapshotAttribute("objectCount", "3")
                .snapshotAttribute("requestedBy", "admin")
                .targets(List.of("purview"))
                .build();

        LineageFact fact = ImportExportResource.uploadedImportFact(
                REPO, "target-folder", "zip-upload", "admin", 3L,
                List.of(new CreatedObject("created-f1", "Reports", true, "target-folder"),
                        new CreatedObject("created-d1", "a.txt", false, "created-f1"),
                        new CreatedObject("created-d2", "b.txt", false, "target-folder")),
                List.of("purview"), "op-1", OCCURRED);

        assertSameV1Event(old, fact.toV1Event());
        assertEquals(3, fact.outputs().size(), "the typed side carries the created content");
        assertEquals(EndpointKind.IMPORT_ARTIFACT, fact.inputs().get(0).kind());
    }

    @Test
    public void theFilesystemImportFactoryPreservesTheV1EventExactly() {
        LineageEvent old = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.IMPORT_FILESYSTEM)
                .addInput("file:///data/inbound")
                .addOutputObject(REPO, "target-folder")
                .snapshotAttribute("sourcePath", "/data/inbound")
                .snapshotAttribute("objectCount", "2")
                .snapshotAttribute("requestedBy", "admin")
                .targets(List.of("purview"))
                .build();

        LineageFact fact = ImportExportResource.filesystemImportFact(
                REPO, "target-folder", "/data/inbound", "admin", 2L,
                List.of(new CreatedObject("d1", "a.txt", false, "target-folder"),
                        new CreatedObject("d2", "b.txt", false, "target-folder")),
                List.of("purview"), "op-1", OCCURRED);

        assertSameV1Event(old, fact.toV1Event());
    }

    /** v1 counts the container root in objectCount and emits no output; both stay verbatim. */
    @Test
    public void theZipFolderExportFactoryPreservesTheV1EventExactly() {
        LineageEvent old = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .addInputObject(REPO, "folder-1")
                .snapshotAttribute("folderName", "Docs")
                .snapshotAttribute("objectCount", "3")
                .snapshotAttribute("requestedBy", "admin")
                .targets(List.of("purview"))
                .build();

        LineageFact fact = ImportExportResource.zipFolderExportFact(
                REPO, "folder-1", "Docs", "admin", 3L,
                List.of(new ExportedObject("d1", "a.txt", false),
                        new ExportedObject("sub-1", "Sub", true)),
                "Docs_export.zip", List.of("purview"), "op-1", OCCURRED);

        assertSameV1Event(old, fact.toV1Event());
        assertEquals(2L, fact.outputs().get(0).attributes().get("objectCount"),
                "the artifact counts the typed moved set, not v1's root-inclusive count");
    }

    /**
     * An empty folder exports nothing, but v1 still emitted (the legacy id set counts the
     * root) — the fact must exist to carry it, and the folder itself is the only honest typed
     * input.
     */
    @Test
    public void anEmptyFolderExportFallsBackToTheFolderEndpoint() {
        LineageFact fact = ImportExportResource.zipFolderExportFact(
                REPO, "folder-1", "Docs", "admin", 1L, List.of(),
                "Docs_export.zip", List.of("purview"), "op-1", OCCURRED);

        assertEquals(1, fact.inputs().size());
        assertEquals(EndpointKind.CMIS_FOLDER, fact.inputs().get(0).kind());
        assertEquals(0L, fact.outputs().get(0).attributes().get("objectCount"));
        assertEquals(List.of(LineageEvent.qualifiedName(REPO, "folder-1")),
                fact.toV1Event().inputs());
        assertEquals("1", fact.toV1Event().snapshotAttributes().get("objectCount"),
                "v1's count keeps the root-inclusive legacy value");
    }

    /** v1 inputs are the top-level selection in iteration order, duplicates included. */
    @Test
    public void theSelectedObjectsExportFactoryPreservesTheV1EventExactly() {
        LineageEvent old = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.EXPORT_SELECTED_OBJECTS)
                .addInputObject(REPO, "d1")
                .addInputObject(REPO, "f1")
                .addInputObject(REPO, "d1")
                .snapshotAttribute("objectCount", "3")
                .snapshotAttribute("requestedBy", "admin")
                .targets(List.of("purview"))
                .build();

        LineageFact fact = ImportExportResource.selectedObjectsExportFact(
                REPO, List.of("d1", "f1", "d1"), "admin", 3L,
                List.of(new ExportedObject("d1", "a.txt", false),
                        new ExportedObject("f1", "F", true),
                        new ExportedObject("nested-d2", "b.txt", false)),
                "export_selected_1.zip", List.of("purview"), "op-1", OCCURRED);

        assertSameV1Event(old, fact.toV1Event());
    }

    @Test
    public void theFilesystemExportFactoryPreservesTheV1EventExactly() {
        LineageEvent old = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.EXPORT_FILESYSTEM)
                .addInputObject(REPO, "folder-1")
                .addOutput("file:///data/outbound")
                .snapshotAttribute("targetPath", "/data/outbound")
                .snapshotAttribute("objectCount", "2")
                .snapshotAttribute("requestedBy", "admin")
                .targets(List.of("purview"))
                .build();

        LineageFact fact = ImportExportResource.filesystemExportFact(
                REPO, "folder-1", "/data/outbound", "admin", 2L,
                List.of(new ExportedObject("d1", "a.txt", false),
                        new ExportedObject("d2", "b.txt", false)),
                List.of("purview"), "op-1", OCCURRED);

        assertSameV1Event(old, fact.toV1Event());
        assertEquals("FILESYSTEM", fact.outputs().get(0).attributes().get("artifactKind"));
    }

    // ------------------------------------------------------------------ journalOwnsLineage

    private final ApplicationContext originalContext = SpringContext.getApplicationContext();

    @AfterEach
    public void restoreSpringContext() {
        new SpringContext().setApplicationContext(originalContext);
    }

    private static void installContext(ApplicationContext ctx) {
        new SpringContext().setApplicationContext(ctx);
    }

    /**
     * The routing contract: a deliberately absent config keeps direct publish available; a
     * FAILED lookup routes to "journal-owned" so a transient failure cannot cause a duplicate
     * direct publication while the journal is in fact active.
     */
    @Test
    public void aFailedConfigLookupRoutesToJournalOwned() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(LineageConfig.class)).thenThrow(new IllegalStateException("bean broke"));
        installContext(ctx);

        assertTrue(new ImportExportResource().journalOwnsLineage(REPO));
    }

    @Test
    public void aDeliberatelyAbsentConfigLeavesDirectPublishAvailable() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(LineageConfig.class))
                .thenThrow(new NoSuchBeanDefinitionException(LineageConfig.class));
        installContext(ctx);
        assertFalse(new ImportExportResource().journalOwnsLineage(REPO));

        installContext(null);
        assertFalse(new ImportExportResource().journalOwnsLineage(REPO),
                "no Spring context at all is the same deliberate absence");
    }

    @Test
    public void theModeDecidesWhenTheConfigResolves() {
        LineageConfig config = mock(LineageConfig.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(LineageConfig.class)).thenReturn(config);
        installContext(ctx);

        when(config.getModeForRepository(REPO)).thenReturn(LineageMode.DISABLED);
        assertFalse(new ImportExportResource().journalOwnsLineage(REPO));

        when(config.getModeForRepository(REPO)).thenReturn(LineageMode.JOURNALED);
        assertTrue(new ImportExportResource().journalOwnsLineage(REPO));

        when(config.getModeForRepository(REPO)).thenThrow(new IllegalStateException("mode broke"));
        assertTrue(new ImportExportResource().journalOwnsLineage(REPO));
    }
}
