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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link LineageProcessShape} — what each process type may connect.
 *
 * <p>The rule this exists to enforce is that shapes are <em>whole</em>. Two independent allowlists
 * would have accepted the union of {@code IMPORT_UPLOADED}'s two producers, and neither
 * cross-pairing is a thing any producer emits or any sink can project.
 */
public class LineageProcessShapeTest {

    private static final String REPO = "bedroom";

    private static LineageEndpoint doc(String id) {
        return LineageEndpoint.document(REPO, id, id + ".txt");
    }

    private static LineageEndpoint folder(String id) {
        return LineageEndpoint.folder(REPO, id, id);
    }

    private static LineageEndpoint archive(String id) {
        return LineageEndpoint.archive(REPO, id, "doc-" + id, 1_700_000_000_000L);
    }

    private static LineageEndpoint cold(String ref) {
        return LineageEndpoint.coldStorage(REPO, ref, "s3");
    }

    private static LineageEndpoint cloud(String fileId) {
        return LineageEndpoint.cloudObject(REPO, "gdrive", fileId);
    }

    private static LineageEndpoint external(String key) {
        return LineageEndpoint.externalAsset(REPO, key, "slack");
    }

    private static LineageEndpoint importArtifact(String operationId) {
        return LineageEndpoint.importArtifact(REPO, operationId, "zip-upload", Map.of());
    }

    private static LineageEndpoint exportArtifact(String operationId) {
        return LineageEndpoint.exportArtifact(REPO, operationId, "ZIP", "export.zip", 3L);
    }

    // ------------------------------------------------------------------ completeness

    /**
     * Every constant has a rule, including the reserved one.
     *
     * <p>A missing entry and a deliberately empty one are different facts, and this is what keeps
     * them different: adding a constant without a rule fails here rather than silently producing
     * an unconstrained process type.
     */
    @Test
    public void everyProcessTypeHasARule() {
        for (LineageProcessType type : LineageProcessType.values()) {
            LineageProcessShape.shapesOf(type); // throws IllegalStateException if absent
        }
    }

    /** The count is asserted so that adding a constant is a deliberate act here too. */
    @Test
    public void theEnumHasEighteenConstants() {
        assertEquals(18, LineageProcessType.values().length,
                "a new process type needs a shape rule and a producer decision; if you added one,"
                        + " add its rule to LineageProcessShape and update this count");
    }

    @Test
    public void fileShareSyncUploadIsTheOnlyReservedType() {
        Set<LineageProcessType> reserved = new java.util.HashSet<>();
        for (LineageProcessType type : LineageProcessType.values()) {
            if (LineageProcessShape.isReserved(type)) {
                reserved.add(type);
            }
        }
        assertEquals(Set.of(LineageProcessType.FILE_SHARE_SYNC_UPLOAD), reserved);
    }

    @Test
    public void aReservedTypeCannotBeProduced() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.FILE_SHARE_SYNC_UPLOAD,
                        List.of(doc("d1")), List.of(external("slack:f1"))));
        assertTrue(thrown.getMessage().contains("reserved"), thrown.getMessage());
    }

    @Test
    public void nullProcessTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LineageProcessShape.shapesOf(null));
    }

    // ------------------------------------------------------------------ imports move content

    /** The moved content is the output — one document, many, or the folders themselves. */
    @Test
    public void anImportProducesTheContentItCreated() {
        LineageProcessShape.validate(LineageProcessType.IMPORT_UPLOADED,
                List.of(importArtifact("op-1")), List.of(doc("d1"), doc("d2"), folder("f1")));
        LineageProcessShape.validate(LineageProcessType.IMPORT_UPLOADED,
                List.of(importArtifact("op-1")), List.of(folder("f1")));
        LineageProcessShape.validate(LineageProcessType.IMPORT_FILESYSTEM,
                List.of(importArtifact("op-1")), List.of(doc("d1")));
    }

    /**
     * §3 v2.3.13: the archetype-null fallback shape (external asset → document) is gone from
     * IMPORT_UPLOADED. Unclassified connector ingest is not a user upload; v2 carries it as
     * GENERIC_EXTERNAL_INGEST instead of mislabelling it.
     */
    @Test
    public void importUploadedNoLongerAcceptsTheFallbackShape() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.IMPORT_UPLOADED,
                        List.of(external("slack:file-1")), List.of(doc("d1"))));
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.IMPORT_UPLOADED,
                        List.of(external("slack:file-1")), List.of(folder("f1"))));
    }

    /** An import that created nothing is not a movement; v1 producers guard on objCount > 0. */
    @Test
    public void anImportWithNoCreatedContentIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.IMPORT_UPLOADED,
                        List.of(importArtifact("op-1")), List.of()));
    }

    @Test
    public void everyNonReservedTypeHasExactlyOneShape() {
        for (LineageProcessType type : LineageProcessType.values()) {
            int shapes = LineageProcessShape.shapesOf(type).size();
            if (type == LineageProcessType.FILE_SHARE_SYNC_UPLOAD) {
                assertEquals(0, shapes, type.name());
            } else {
                assertEquals(1, shapes, type + " should have exactly one shape; a second one is"
                        + " added as a whole alternative, deliberately");
            }
        }
    }

    // ------------------------------------------------------------------ each family

    @Test
    public void archiveShapes() {
        LineageProcessShape.validate(LineageProcessType.ARCHIVE_LOCAL,
                List.of(doc("d1")), List.of(archive("a1")));
        LineageProcessShape.validate(LineageProcessType.ARCHIVE_COLD,
                List.of(archive("a1")), List.of(cold("s3://bucket/a1")));
        // reversed
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.ARCHIVE_LOCAL,
                        List.of(archive("a1")), List.of(doc("d1"))));
    }

    @Test
    public void cloudSyncShapesAreDirectional() {
        LineageProcessShape.validate(LineageProcessType.CLOUD_SYNC_UPLOAD,
                List.of(doc("d1")), List.of(cloud("file-1")));
        LineageProcessShape.validate(LineageProcessType.CLOUD_SYNC_DOWNLOAD,
                List.of(cloud("file-1")), List.of(doc("d1")));
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.CLOUD_SYNC_UPLOAD,
                        List.of(cloud("file-1")), List.of(doc("d1"))));
    }

    /**
     * All three exports converge on one shape: the exported content in, one artifact out. How the
     * inputs were chosen (a whole folder, a hand-picked set) is what the processType records; the
     * moved content is documents and folders either way (§3 v2.3.13).
     */
    @Test
    public void exportShapesAllConsumeContentAndProduceAnArtifact() {
        LineageProcessShape.validate(LineageProcessType.EXPORT_FILESYSTEM,
                List.of(doc("d1"), doc("d2")), List.of(exportArtifact("op-1")));
        LineageProcessShape.validate(LineageProcessType.EXPORT_ZIP_FOLDER,
                List.of(folder("f1"), doc("d1")), List.of(exportArtifact("op-1")));
        LineageProcessShape.validate(LineageProcessType.EXPORT_SELECTED_OBJECTS,
                List.of(doc("d1"), folder("f1")), List.of(exportArtifact("op-1")));
    }

    /**
     * v1 emits these with no output at all. The table states where A-2 takes them, so an event
     * still shaped the old way must fail — otherwise the rewrite has nothing enforcing it.
     */
    @Test
    public void anExportWithNoOutputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.EXPORT_ZIP_FOLDER,
                        List.of(folder("f1")), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.EXPORT_SELECTED_OBJECTS,
                        List.of(doc("d1")), List.of()));
    }

    /** The one type whose inputs are legitimately many and legitimately mixed. */
    @Test
    public void selectedObjectsExportTakesManyMixedInputs() {
        LineageProcessShape.validate(LineageProcessType.EXPORT_SELECTED_OBJECTS,
                List.of(doc("d1"), doc("d2"), folder("f1"), doc("d3")),
                List.of(exportArtifact("op-1")));
    }

    @Test
    public void selectedObjectsExportStillNeedsAtLeastOneInput() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.EXPORT_SELECTED_OBJECTS,
                        List.of(), List.of(exportArtifact("op-1"))));
    }

    @Test
    public void selectedObjectsExportProducesExactlyOneArtifactPerChunk() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.EXPORT_SELECTED_OBJECTS,
                        List.of(doc("d1")),
                        List.of(exportArtifact("op-1"), exportArtifact("op-2"))));
    }

    @Test
    public void everyIngestTypeIsOneExternalAssetBecomingOneDocument() {
        for (LineageProcessType ingest : List.of(
                LineageProcessType.EXTERNAL_NOTE_IMPORT,
                LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT,
                LineageProcessType.BUSINESS_RECORD_IMPORT,
                LineageProcessType.CHAT_ATTACHMENT_IMPORT,
                LineageProcessType.FILE_SHARE_SYNC_DOWNLOAD,
                LineageProcessType.MAIL_MESSAGE_IMPORT,
                LineageProcessType.MAIL_ATTACHMENT_IMPORT,
                LineageProcessType.GENERIC_EXTERNAL_INGEST)) {
            LineageProcessShape.validate(ingest,
                    List.of(external("slack:file-1")), List.of(doc("d1")));
            assertThrows(IllegalArgumentException.class,
                    () -> LineageProcessShape.validate(ingest,
                            List.of(external("slack:file-1")), List.of(folder("f1"))),
                    ingest + " must not produce a folder");
            assertThrows(IllegalArgumentException.class,
                    () -> LineageProcessShape.validate(ingest,
                            List.of(external("a:1"), external("b:2")), List.of(doc("d1"))),
                    ingest + " is one asset, not many");
        }
    }

    @Test
    public void importFilesystemTakesAnArtifactNotARawPath() {
        LineageProcessShape.validate(LineageProcessType.IMPORT_FILESYSTEM,
                List.of(importArtifact("op-1")), List.of(folder("f1")));
        // what v1 emits today: a filesystem external asset. Rejected on purpose.
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.IMPORT_FILESYSTEM,
                        List.of(LineageEndpoint.filesystemPath(REPO, "/srv/in")),
                        List.of(folder("f1"))));
    }

    // ------------------------------------------------------------------ mechanics

    @Test
    public void nullEndpointListsAreTreatedAsEmptyAndStillHaveToMatch() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.ARCHIVE_LOCAL, null, null));
    }

    @Test
    public void aSideMustAllowAtLeastOneKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageProcessShape.Side(Set.of(), 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageProcessShape.Side(null, 1, 1));
    }

    @Test
    public void aSideRejectsAnImpossibleCardinality() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageProcessShape.Side(Set.of(EndpointKind.CMIS_DOCUMENT), 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageProcessShape.Side(Set.of(EndpointKind.CMIS_DOCUMENT), -1, 1));
    }

    @Test
    public void aShapeNeedsBothSides() {
        LineageProcessShape.Side side =
                new LineageProcessShape.Side(Set.of(EndpointKind.CMIS_DOCUMENT), 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new LineageProcessShape.Shape(side, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageProcessShape.Shape(null, side));
    }

    /**
     * The message has to name the accepted shapes, or a producer author has nothing to act on.
     * It must not name a qualified name: an external one carries the stable key.
     */
    @Test
    public void theRejectionSaysWhatWouldHaveBeenAcceptedAndLeaksNoQualifiedName() {
        LineageEndpoint secret = external("slack:super-secret-file-id");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.ARCHIVE_LOCAL,
                        List.of(secret), List.of(archive("a1"))));
        assertTrue(thrown.getMessage().contains("CMIS_DOCUMENT"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("ARCHIVE"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("super-secret-file-id"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(secret.catalogQualifiedName()),
                thrown.getMessage());
    }

    /**
     * Naming the accepted shapes is only half of a usable message: it also has to say what was
     * actually given, or the author is told what is legal without being told how their event
     * differs. Asserted on the counts specifically, because those appear nowhere in the
     * accepted-shape rendering and so cannot be satisfied by accident.
     */
    @Test
    public void theRejectionAlsoSaysWhatWasGiven() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.EXPORT_SELECTED_OBJECTS,
                        List.of(doc("d1"), doc("d2")),
                        List.of(exportArtifact("op-1"), exportArtifact("op-2"))));
        assertTrue(thrown.getMessage().contains("{CMIS_DOCUMENT=2}"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("{EXPORT_ARTIFACT=2}"), thrown.getMessage());
    }

    /** An empty side has to read as empty rather than vanishing from the message. */
    @Test
    public void theRejectionShowsAnEmptySideAsEmpty() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LineageProcessShape.validate(LineageProcessType.EXPORT_ZIP_FOLDER,
                        List.of(folder("f1")), List.of()));
        assertTrue(thrown.getMessage().contains("{CMIS_FOLDER=1} -> []"), thrown.getMessage());
    }

    @Test
    public void sideAndShapeRenderReadably() {
        assertEquals("[CMIS_DOCUMENT]×1",
                new LineageProcessShape.Side(Set.of(EndpointKind.CMIS_DOCUMENT), 1, 1).toString());
        assertEquals("[CMIS_DOCUMENT]×1..n",
                new LineageProcessShape.Side(Set.of(EndpointKind.CMIS_DOCUMENT), 1,
                        Integer.MAX_VALUE).toString());
        assertEquals("[CMIS_DOCUMENT]×1..3",
                new LineageProcessShape.Side(Set.of(EndpointKind.CMIS_DOCUMENT), 1, 3).toString());
        assertTrue(LineageProcessShape.shapesOf(LineageProcessType.ARCHIVE_LOCAL).get(0).toString()
                .contains("->"));
    }
}
