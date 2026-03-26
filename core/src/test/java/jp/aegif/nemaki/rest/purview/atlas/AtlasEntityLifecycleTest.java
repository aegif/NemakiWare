package jp.aegif.nemaki.rest.purview.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import jp.aegif.nemaki.rest.purview.atlas.AtlasDirectClient.AtlasResponse;

/**
 * Verifies that entity payloads matching PurviewEntityPayloadFactory output
 * are accepted by a real Apache Atlas 2.3 instance.
 */
@Tag("atlas-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(600)
public class AtlasEntityLifecycleTest {

    private static AtlasDirectClient client;

    // Timestamp suffix ensures test isolation across runs
    private static final String TS = String.valueOf(System.currentTimeMillis());
    private static final String REPO_ID = "test-repo-" + TS;
    private static final String FOLDER_ID = "folder-" + TS;
    private static final String DOC_ID = "doc-" + TS;
    private static final String ARCHIVE_ID = "archive-" + TS;
    private static final String EXTERNAL_KEY = "cold-storage:/data/" + TS;

    // qualifiedName helpers matching PurviewEntityPayloadFactory conventions
    private static final String REPO_QN = "nemaki://" + REPO_ID;
    private static final String FOLDER_QN = "nemaki://" + REPO_ID + "/objects/" + FOLDER_ID;
    private static final String DOC_QN = "nemaki://" + REPO_ID + "/objects/" + DOC_ID;
    private static final String ARCHIVE_QN = "nemaki://" + REPO_ID + "/archives/" + ARCHIVE_ID;
    private static final String EXTERNAL_QN = "nemaki://" + REPO_ID + "/external-assets/"
            + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(EXTERNAL_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @BeforeAll
    static void setUp() throws Exception {
        client = new AtlasDirectClient(
                AtlasContainer.getEndpoint(),
                AtlasContainer.getBasicAuthHeader());

        assertTrue(AtlasTestHelper.ensureSchemaApplied(client),
                "Schema must be applied before entity tests");
    }

    @Test
    @Order(1)
    void bulkCreateRepositoryAndFolderAndDocument() throws Exception {
        Map<String, Object> repoEntity = buildEntity("nemaki_repository", REPO_QN, REPO_ID, Map.of(
                "repositoryId", REPO_ID,
                "rootFolderId", FOLDER_ID,
                "lifecycleState", "ACTIVE"));

        Map<String, Object> folderEntity = buildEntity("nemaki_folder", FOLDER_QN, "Test Folder", Map.of(
                "repositoryId", REPO_ID,
                "objectId", FOLDER_ID,
                "typeId", "cmis:folder",
                "lifecycleState", "ACTIVE"));

        Map<String, Object> docEntity = buildEntity("nemaki_document", DOC_QN, "Test Document", Map.of(
                "repositoryId", REPO_ID,
                "objectId", DOC_ID,
                "parentId", FOLDER_ID,
                "typeId", "cmis:document",
                "lifecycleState", "ACTIVE"));

        Map<String, Object> bulkPayload = Map.of(
                "referredEntities", Map.of(),
                "entities", List.of(repoEntity, folderEntity, docEntity));

        AtlasResponse response = client.bulkCreateEntities(bulkPayload);
        assertTrue(response.isSuccess(),
                "Bulk create should succeed. Status: " + response.statusCode()
                        + ", Body: " + response.body());
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void getDocumentEntityByQualifiedName() throws Exception {
        AtlasResponse response = client.getEntityByQualifiedName("nemaki_document", DOC_QN);

        assertEquals(200, response.statusCode(), "GET entity should return 200");
        assertNotNull(response.body().get("entity"), "Response should contain 'entity' field");

        Map<String, Object> entity = (Map<String, Object>) response.body().get("entity");
        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertEquals(DOC_QN, attributes.get("qualifiedName"));
        assertEquals("Test Document", attributes.get("name"));
        assertEquals(REPO_ID, attributes.get("repositoryId"));
    }

    @Test
    @Order(3)
    void bulkCreateArchiveEntity() throws Exception {
        Map<String, Object> archiveEntity = buildEntity("nemaki_archive", ARCHIVE_QN, "Archived Doc", Map.of(
                "originalObjectId", DOC_ID,
                "archiveRepositoryId", REPO_ID,
                "lifecycleState", "ARCHIVED",
                "archiveState", "archived"));

        Map<String, Object> bulkPayload = Map.of(
                "referredEntities", Map.of(),
                "entities", List.of(archiveEntity));

        AtlasResponse response = client.bulkCreateEntities(bulkPayload);
        assertTrue(response.isSuccess(),
                "Archive entity creation should succeed. Status: " + response.statusCode()
                        + ", Body: " + response.body());
    }

    @Test
    @Order(4)
    void bulkCreateExternalAssetEntity() throws Exception {
        Map<String, Object> externalEntity = buildEntity("nemaki_external_asset", EXTERNAL_QN,
                "External Asset", Map.of(
                        "externalStableKey", EXTERNAL_KEY,
                        "sourceSystem", "cold-storage"));

        Map<String, Object> bulkPayload = Map.of(
                "referredEntities", Map.of(),
                "entities", List.of(externalEntity));

        AtlasResponse response = client.bulkCreateEntities(bulkPayload);
        assertTrue(response.isSuccess(),
                "External asset creation should succeed. Status: " + response.statusCode()
                        + ", Body: " + response.body());
    }

    @Test
    @Order(5)
    void getMissingEntityReturns404() throws Exception {
        AtlasResponse response = client.getEntityByQualifiedName(
                "nemaki_document", "nemaki://nonexistent/objects/missing-" + TS);

        assertEquals(404, response.statusCode(),
                "Missing entity should return 404");
    }

    @Test
    @Order(6)
    void deleteEntityByQualifiedName() throws Exception {
        AtlasResponse response = client.deleteEntityByQualifiedName("nemaki_document", DOC_QN);

        assertTrue(response.statusCode() == 200 || response.statusCode() == 204,
                "Delete should return 200 or 204. Status: " + response.statusCode());
    }

    /**
     * Builds an entity Map matching the structure produced by PurviewEntityPayloadFactory.
     */
    private Map<String, Object> buildEntity(String typeName, String qualifiedName, String name,
            Map<String, Object> extraAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", qualifiedName);
        attributes.put("name", name);
        attributes.put("description", "Atlas integration test entity");
        attributes.put("owner", "system");
        attributes.put("createTime", System.currentTimeMillis());
        attributes.put("modifiedTime", System.currentTimeMillis());
        attributes.putAll(extraAttributes);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", typeName);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", "system");
        entity.put("updatedBy", "system");
        entity.put("version", 0);
        return entity;
    }
}
