package jp.aegif.nemaki.rest.purview.atlas;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Verifies that Atlas REST API responses contain the fields that NemakiWare's
 * Purview client code expects when parsing (mutatedEntities, entity.attributes, guid).
 */
@Tag("atlas-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(600)
public class AtlasResponseFormatTest {

    private static AtlasDirectClient client;

    private static final String TS = String.valueOf(System.currentTimeMillis());
    private static final String REPO_ID = "fmt-repo-" + TS;
    private static final String FOLDER_ID = "fmt-folder-" + TS;
    private static final String DOC_ID = "fmt-doc-" + TS;

    private static final String REPO_QN = "nemaki://" + REPO_ID;
    private static final String FOLDER_QN = "nemaki://" + REPO_ID + "/objects/" + FOLDER_ID;
    private static final String DOC_QN = "nemaki://" + REPO_ID + "/objects/" + DOC_ID;

    private static String folderGuid;
    private static String docGuid;

    @BeforeAll
    static void setUp() throws Exception {
        client = new AtlasDirectClient(
                AtlasContainer.getEndpoint(),
                AtlasContainer.getBasicAuthHeader());

        assertTrue(AtlasTestHelper.ensureSchemaApplied(client), "Schema must be applied first");
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void bulkCreateResponseContainsMutatedEntitiesWithGuids() throws Exception {
        Map<String, Object> repoEntity = buildEntity("nemaki_repository", REPO_QN, REPO_ID, Map.of(
                "repositoryId", REPO_ID,
                "rootFolderId", FOLDER_ID,
                "lifecycleState", "ACTIVE"));

        Map<String, Object> folderEntity = buildEntity("nemaki_folder", FOLDER_QN, "Format Test Folder", Map.of(
                "repositoryId", REPO_ID,
                "objectId", FOLDER_ID,
                "typeId", "cmis:folder",
                "lifecycleState", "ACTIVE"));

        Map<String, Object> docEntity = buildEntity("nemaki_document", DOC_QN, "Format Test Document", Map.of(
                "repositoryId", REPO_ID,
                "objectId", DOC_ID,
                "parentId", FOLDER_ID,
                "typeId", "cmis:document",
                "lifecycleState", "ACTIVE"));

        Map<String, Object> bulkPayload = Map.of(
                "referredEntities", Map.of(),
                "entities", List.of(repoEntity, folderEntity, docEntity));

        AtlasResponse response = client.bulkCreateEntities(bulkPayload);
        assertTrue(response.isSuccess(), "Bulk create should succeed");

        // Verify the response structure matches what NemakiWare's parseBulkResponse() expects
        Map<String, Object> body = response.body();
        assertNotNull(body.get("mutatedEntities"),
                "Response must contain 'mutatedEntities' field. Body: " + body);

        Map<String, Object> mutatedEntities = (Map<String, Object>) body.get("mutatedEntities");
        // Atlas uses CREATE or UPDATE depending on whether entities exist
        assertTrue(mutatedEntities.containsKey("CREATE") || mutatedEntities.containsKey("UPDATE"),
                "mutatedEntities must contain CREATE or UPDATE. Keys: " + mutatedEntities.keySet());

        // Verify each created entity has a guid
        List<Map<String, Object>> created = mutatedEntities.containsKey("CREATE")
                ? (List<Map<String, Object>>) mutatedEntities.get("CREATE")
                : (List<Map<String, Object>>) mutatedEntities.get("UPDATE");

        assertFalse(created.isEmpty(), "At least one entity should be in the response");
        for (Map<String, Object> entry : created) {
            assertNotNull(entry.get("guid"),
                    "Each mutated entity must have a 'guid' field. Entry: " + entry);
        }

        // Cache GUIDs from bulk create response (keyed by typeName) for relationship test
        Map<String, String> guidMap = AtlasTestHelper.extractGuidsFromBulkResponse(response);
        folderGuid = guidMap.get("nemaki_folder");
        docGuid = guidMap.get("nemaki_document");
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void getEntityResponseContainsEntityWithAttributes() throws Exception {
        // Use retry to handle Atlas eventual consistency after bulk creation
        AtlasResponse response = null;
        for (int attempt = 0; attempt <= 5; attempt++) {
            response = client.getEntityByQualifiedName("nemaki_document", DOC_QN);
            if (response.isSuccess()) break;
            if (attempt < 5) Thread.sleep(2000);
        }

        assertTrue(response.isSuccess(),
                "GET entity should succeed. Status: " + response.statusCode()
                        + ", Body: " + response.body());
        Map<String, Object> body = response.body();

        // Verify the response structure matches what NemakiWare's parseGetEntityResponse() expects
        assertNotNull(body.get("entity"),
                "Response must contain 'entity' field. Body: " + body);

        Map<String, Object> entity = (Map<String, Object>) body.get("entity");
        assertNotNull(entity.get("guid"), "Entity must have 'guid' field");
        assertNotNull(entity.get("attributes"), "Entity must have 'attributes' field");

        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertNotNull(attributes.get("qualifiedName"),
                "Attributes must contain 'qualifiedName'. Attributes: " + attributes);

        // Fallback: resolve GUIDs if not cached from bulk create (test 1)
        if (docGuid == null) {
            docGuid = (String) entity.get("guid");
        }
        if (folderGuid == null) {
            folderGuid = AtlasTestHelper.resolveGuidWithRetry(client, "nemaki_folder", FOLDER_QN, 5, 2000);
        }
    }

    @Test
    @Order(3)
    void createRelationshipResponseContainsGuid() throws Exception {
        assertNotNull(folderGuid, "Folder GUID must be resolved from previous test");
        assertNotNull(docGuid, "Document GUID must be resolved from previous test");

        Map<String, Object> relationship = new LinkedHashMap<>();
        relationship.put("typeName", "nemaki_folder_contains_document");
        relationship.put("end1", relationshipEndWithGuid("nemaki_folder", folderGuid, FOLDER_QN));
        relationship.put("end2", relationshipEndWithGuid("nemaki_document", docGuid, DOC_QN));
        relationship.put("attributes", Map.of());

        AtlasResponse response = client.createRelationship(relationship);
        assertTrue(response.isSuccess(),
                "Relationship creation should succeed. Status: " + response.statusCode());

        // Verify the response contains guid, which NemakiWare extracts after creation
        assertNotNull(response.body().get("guid"),
                "Relationship response must contain 'guid' field. Body: " + response.body());
    }

    private Map<String, Object> relationshipEndWithGuid(String typeName, String guid, String qualifiedName) {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("typeName", typeName);
        end.put("guid", guid);
        end.put("uniqueAttributes", Map.of("qualifiedName", qualifiedName));
        return end;
    }

    private Map<String, Object> buildEntity(String typeName, String qualifiedName, String name,
            Map<String, Object> extraAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", qualifiedName);
        attributes.put("name", name);
        attributes.put("description", "Atlas response format test entity");
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
