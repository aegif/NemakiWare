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
package jp.aegif.nemaki.rest.purview.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.purview.atlas.AtlasDirectClient.AtlasResponse;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/**
 * Increment B against a live catalog: do the new types exist, and do the payloads land?
 *
 * <p>Everything the unit tests assert is about payloads and decisions. What they cannot assert is
 * whether a catalog accepts the type definitions and stores the entities — a schema this build
 * considers valid can still be rejected by a real backend over an attribute type, a supertype, or
 * a relationship end. That is what this exercises.
 *
 * <p><b>Excluded by default.</b> Tagged {@code atlas-integration}, so it does not run in an
 * ordinary build. To run it, see {@code docs/operations/lineage-increment-b-runbook.md}.
 *
 * <p><b>An Atlas OSS pass is not a Purview pass.</b> The two do not share an error vocabulary,
 * an attribute-type mapping, or relationship semantics. A green run here is evidence about Atlas
 * and nothing else; Purview needs its own, which the runbook records as
 * {@code EXTERNAL_EVIDENCE_REQUIRED}.
 */
@Tag("atlas-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(600)
public class LineageIncrementBAtlasTest {

    private static AtlasDirectClient client;
    private static final PurviewEntityPayloadFactory FACTORY = new PurviewEntityPayloadFactory();

    private static final String TS = String.valueOf(System.currentTimeMillis());
    private static final String REPO_ID = "incb-repo-" + TS;
    private static final String FOLDER_ID = "incb-folder-" + TS;

    private static final String FOLDER_QN = "nemaki://" + REPO_ID + "/objects/" + FOLDER_ID;
    private static final String COMPANION_QN =
            "nemaki://" + REPO_ID + "/folders/" + FOLDER_ID + "/dataset";

    private static Folder folder() {
        Folder folder = new Folder();
        folder.setId(FOLDER_ID);
        folder.setName("Increment B folder");
        folder.setType("cmis:folder");
        folder.setCreator("tester");
        return folder;
    }

    @BeforeAll
    static void setUp() throws Exception {
        client = new AtlasDirectClient(
                AtlasContainer.getEndpoint(), AtlasContainer.getBasicAuthHeader());
        assertTrue(AtlasTestHelper.ensureSchemaApplied(client),
                "the increment-B schema must apply before anything else can be checked");
    }

    @Test
    @Order(1)
    @DisplayName("the catalog has the types increment B adds")
    void newTypesExist() throws Exception {
        for (String typeName : List.of("nemaki_folder_dataset", "nemaki_import_artifact",
                "nemaki_export_artifact")) {
            AtlasResponse response = client.getTypeDef(typeName);
            assertTrue(response.isSuccess(),
                    typeName + " is not in the catalog after schema apply (HTTP "
                            + response.statusCode() + ")");
        }
    }

    /** Applying the same schema again must not fail — the upgrade has to be re-runnable. */
    @Test
    @Order(2)
    @DisplayName("applying the schema twice is not an error")
    void schemaApplyIsRepeatable() throws Exception {
        assertTrue(AtlasTestHelper.ensureSchemaApplied(client),
                "a second apply must succeed or report the types already exist");
    }

    @Test
    @Order(3)
    @DisplayName("a folder and its companion are accepted in one bulk")
    void folderAndCompanionInOneBulk() throws Exception {
        Map<String, Object> payload = FACTORY.buildBulkPayload(List.of(
                FACTORY.buildFolderEntity(REPO_ID, folder(), "/increment-b"),
                FACTORY.buildFolderDatasetEntity(REPO_ID, folder(),
                        PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE)));

        AtlasResponse response = client.bulkCreateEntities(payload);
        assertTrue(response.isSuccess(),
                "the catalog rejected the folder/companion bulk: HTTP " + response.statusCode());

        assertNotNull(AtlasTestHelper.resolveGuidWithRetry(
                client, "nemaki_folder_dataset", COMPANION_QN, 5, 1000),
                "the companion is not queryable by its qualified name");
    }

    @Test
    @Order(4)
    @DisplayName("the 1:1 tie is accepted")
    void tieIsAccepted() throws Exception {
        AtlasResponse response = client.createRelationship(
                FACTORY.buildFolderDatasetRelationship(REPO_ID, folder()));
        assertTrue(response.isSuccess() || response.statusCode() == 409,
                "the catalog rejected nemaki_folder_has_dataset: HTTP " + response.statusCode());
    }

    /** The property the backfill and the reconciliation both depend on. */
    @Test
    @Order(5)
    @DisplayName("republishing the same companion leaves one entity, not two")
    void republishIsIdempotent() throws Exception {
        String before = AtlasTestHelper.resolveGuidWithRetry(
                client, "nemaki_folder_dataset", COMPANION_QN, 5, 1000);

        client.bulkCreateEntities(FACTORY.buildBulkPayload(List.of(
                FACTORY.buildFolderDatasetEntity(REPO_ID, folder(),
                        PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE))));

        String after = AtlasTestHelper.resolveGuidWithRetry(
                client, "nemaki_folder_dataset", COMPANION_QN, 5, 1000);
        assertEquals(before, after,
                "a second publish produced a different entity — the backfill would duplicate");
    }

    @Test
    @Order(6)
    @DisplayName("a lifecycle transition is stored and does not delete the companion")
    void lifecycleTransitionIsStored() throws Exception {
        Map<String, Object> archived = FACTORY.buildFolderDatasetEntity(REPO_ID, folder(),
                PurviewEntityPayloadFactory.SOURCE_STATE_ARCHIVED);
        assertTrue(client.bulkCreateEntities(FACTORY.buildBulkPayload(List.of(archived)))
                .isSuccess(), "the catalog rejected the ARCHIVED transition");

        AtlasResponse read = client.getEntityByQualifiedName("nemaki_folder_dataset", COMPANION_QN);
        assertTrue(read.isSuccess(), "the companion vanished after being marked ARCHIVED");

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) read.body().get("entity");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertEquals("ARCHIVED", attributes.get("sourceState"));
        assertEquals(Boolean.FALSE, attributes.get("active"));
    }

    @Test
    @Order(7)
    @DisplayName("the increment-B attributes survive a round trip")
    void newAttributesRoundTrip() throws Exception {
        AtlasResponse read = client.getEntityByQualifiedName("nemaki_folder_dataset", COMPANION_QN);
        assertTrue(read.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) read.body().get("entity");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");

        // If the catalog silently dropped these, every lineage query over them would return
        // nothing and nothing would say why — the failure the allowlist exists to prevent.
        assertEquals(REPO_ID, attributes.get("repositoryId"));
        assertEquals(FOLDER_ID, attributes.get("objectId"));
        assertNotNull(attributes.get("sourceState"));
    }

    @Test
    @Order(8)
    @DisplayName("the folder entity is still there, unchanged by any of this")
    void folderItselfIsUntouched() throws Exception {
        AtlasResponse read = client.getEntityByQualifiedName("nemaki_folder", FOLDER_QN);
        assertTrue(read.isSuccess(),
                "the folder must be unaffected — increment B is additive by contract");
    }
}
