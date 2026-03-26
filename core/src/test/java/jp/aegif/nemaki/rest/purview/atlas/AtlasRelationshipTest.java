package jp.aegif.nemaki.rest.purview.atlas;

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

import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rest.purview.atlas.AtlasDirectClient.AtlasResponse;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/**
 * Verifies that relationship payloads matching PurviewEntityPayloadFactory output
 * are accepted by a real Apache Atlas 2.3 instance.
 *
 * <p>Note: Atlas 2.3 requires entity GUIDs in relationship endpoints, while Purview
 * resolves endpoints via uniqueAttributes alone. This test uses the factory's guid-aware
 * overload to validate the relationship payload structure works with Atlas.</p>
 */
@Tag("atlas-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(600)
public class AtlasRelationshipTest {

    private static AtlasDirectClient client;
    private static final PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();

    private static final String TS = String.valueOf(System.currentTimeMillis());
    private static final String REPO_ID = "rel-repo-" + TS;
    private static final String FOLDER_ID = "rel-folder-" + TS;
    private static final String DOC_ID = "rel-doc-" + TS;

    private static final String REPO_QN = "nemaki://" + REPO_ID;
    private static final String FOLDER_QN = "nemaki://" + REPO_ID + "/objects/" + FOLDER_ID;
    private static final String DOC_QN = "nemaki://" + REPO_ID + "/objects/" + DOC_ID;

    private static String folderGuid;
    private static String docGuid;
    private static String relationshipGuid;

    /** GUID map built from bulk create response, passed to factory overloads. */
    private static Map<String, String> guidByQualifiedName;

    @BeforeAll
    static void setUp() throws Exception {
        client = new AtlasDirectClient(
                AtlasContainer.getEndpoint(),
                AtlasContainer.getBasicAuthHeader());

        assertTrue(AtlasTestHelper.ensureSchemaApplied(client), "Schema must be applied first");

        // Create prerequisite entities: repository, folder, document
        Map<String, Object> bulkPayload = Map.of(
                "referredEntities", Map.of(),
                "entities", List.of(
                        buildEntity("nemaki_repository", REPO_QN, REPO_ID, Map.of(
                                "repositoryId", REPO_ID,
                                "rootFolderId", FOLDER_ID,
                                "lifecycleState", "ACTIVE")),
                        buildEntity("nemaki_folder", FOLDER_QN, "Rel Test Folder", Map.of(
                                "repositoryId", REPO_ID,
                                "objectId", FOLDER_ID,
                                "typeId", "cmis:folder",
                                "lifecycleState", "ACTIVE")),
                        buildEntity("nemaki_document", DOC_QN, "Rel Test Document", Map.of(
                                "repositoryId", REPO_ID,
                                "objectId", DOC_ID,
                                "parentId", FOLDER_ID,
                                "typeId", "cmis:document",
                                "lifecycleState", "ACTIVE"))));

        AtlasResponse entityResponse = client.bulkCreateEntities(bulkPayload);
        assertTrue(entityResponse.isSuccess(),
                "Prerequisite entities must be created. Status: " + entityResponse.statusCode()
                        + ", Body: " + entityResponse.body());

        // Extract GUIDs directly from bulk create response (keyed by typeName)
        Map<String, String> guidMap = AtlasTestHelper.extractGuidsFromBulkResponse(entityResponse);
        folderGuid = guidMap.get("nemaki_folder");
        docGuid = guidMap.get("nemaki_document");

        // Fallback: resolve GUIDs via GET with retry (Atlas eventual consistency)
        if (folderGuid == null) {
            folderGuid = AtlasTestHelper.resolveGuidWithRetry(client, "nemaki_folder", FOLDER_QN, 5, 2000);
        }
        if (docGuid == null) {
            docGuid = AtlasTestHelper.resolveGuidWithRetry(client, "nemaki_document", DOC_QN, 5, 2000);
        }

        assertNotNull(folderGuid, "Folder GUID must be resolved. Bulk response: " + entityResponse.body());
        assertNotNull(docGuid, "Document GUID must be resolved. Bulk response: " + entityResponse.body());

        // Build guidByQualifiedName map for factory overloads
        guidByQualifiedName = Map.of(FOLDER_QN, folderGuid, DOC_QN, docGuid);
    }

    @Test
    @Order(1)
    void createContainmentRelationship() throws Exception {
        // Use factory guid-aware overload — validates that factory-generated payloads work with Atlas
        Document document = new Document();
        document.setId(DOC_ID);
        document.setParentId(FOLDER_ID);

        Map<String, Object> relationship = factory.buildFolderDocumentRelationship(
                REPO_ID, document, guidByQualifiedName);

        AtlasResponse response = client.createRelationship(relationship);
        assertTrue(response.isSuccess(),
                "Relationship creation should succeed. Status: " + response.statusCode()
                        + ", Body: " + response.body());

        // Extract guid for later deletion test
        Object guid = response.body().get("guid");
        assertNotNull(guid, "Response should contain 'guid' field");
        relationshipGuid = guid.toString();
    }

    @Test
    @Order(2)
    void createDuplicateRelationshipHandledGracefully() throws Exception {
        Document document = new Document();
        document.setId(DOC_ID);
        document.setParentId(FOLDER_ID);

        Map<String, Object> relationship = factory.buildFolderDocumentRelationship(
                REPO_ID, document, guidByQualifiedName);

        AtlasResponse response = client.createRelationship(relationship);

        // Atlas may return 200 (update) or 409 (conflict with existing guid)
        assertTrue(response.statusCode() == 200 || response.statusCode() == 409,
                "Duplicate relationship should return 200 or 409. Status: " + response.statusCode()
                        + ", Body: " + response.body());
    }

    @Test
    @Order(3)
    void deleteRelationshipByGuid() throws Exception {
        assertNotNull(relationshipGuid, "Relationship GUID must be captured from create test");

        AtlasResponse response = client.deleteRelationshipByGuid(relationshipGuid);
        assertTrue(response.statusCode() == 200 || response.statusCode() == 204,
                "Relationship deletion should return 200 or 204. Status: " + response.statusCode());
    }

    private static Map<String, Object> buildEntity(String typeName, String qualifiedName, String name,
            Map<String, Object> extraAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", qualifiedName);
        attributes.put("name", name);
        attributes.put("description", "Atlas relationship test entity");
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
