package jp.aegif.nemaki.rest.purview.atlas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.purview.atlas.AtlasDirectClient.AtlasResponse;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaPayloadFactory;

/**
 * Not a real test — a data loader for the manual Atlas container on localhost:21000.
 * Run with: mvn test -Dgroups=atlas-manual-load -Dsurefire.excludedGroups=
 *           -Dtest="AtlasManualDataLoader" -DforkCount=0 -f core/pom.xml -Pdevelopment
 */
@Tag("atlas-manual-load")
public class AtlasManualDataLoader {

    @Test
    void loadSampleData() throws Exception {
        String endpoint = "http://localhost:21000";
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes());
        AtlasDirectClient client = new AtlasDirectClient(endpoint, auth);

        // 1. Apply NemakiWare schema
        System.out.println("=== Step 1: Applying NemakiWare schema ===");
        boolean schemaOk = AtlasTestHelper.ensureSchemaApplied(client);
        assertTrue(schemaOk, "Schema must be applied");
        System.out.println("Schema applied: " + schemaOk);

        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        String repoId = "bedroom";

        // 2. Create entities
        System.out.println("\n=== Step 2: Creating entities ===");
        List<Map<String, Object>> entities = new ArrayList<>();

        entities.add(makeEntity("nemaki_repository", repoAttrs()));
        entities.add(makeEntity("nemaki_folder", folderAttrs(
                "nemaki://bedroom/objects/root-001", "Root Folder", "root-001", null)));
        entities.add(makeEntity("nemaki_folder", folderAttrs(
                "nemaki://bedroom/objects/folder-contracts", "Contracts", "folder-contracts", "root-001")));
        entities.add(makeEntity("nemaki_folder", folderAttrs(
                "nemaki://bedroom/objects/folder-reports", "Reports", "folder-reports", "root-001")));
        entities.add(makeEntity("nemaki_document", docAttrs(
                "nemaki://bedroom/objects/doc-nda", "NDA-2026.pdf", "doc-nda", "folder-contracts", "cmis:document", "1.0")));
        entities.add(makeEntity("nemaki_document", docAttrs(
                "nemaki://bedroom/objects/doc-agreement", "Service Agreement.docx", "doc-agreement", "folder-contracts", "cmis:document", "2.1")));
        entities.add(makeEntity("nemaki_document", docAttrs(
                "nemaki://bedroom/objects/doc-q1report", "Q1 Report 2026.xlsx", "doc-q1report", "folder-reports", "D:custom:report", "1.0")));
        entities.add(makeEntity("nemaki_type_definition", typeDefAttrs()));

        Map<String, Object> bulkPayload = Map.of(
                "referredEntities", Map.of(),
                "entities", entities);

        AtlasResponse bulkResp = client.bulkCreateEntities(bulkPayload);
        System.out.println("Bulk create status: " + bulkResp.statusCode());
        assertTrue(bulkResp.isSuccess(), "Bulk create failed: " + bulkResp.body());

        // 3. Resolve GUIDs
        System.out.println("\n=== Step 3: Resolving GUIDs ===");
        Map<String, String> guidByQn = new LinkedHashMap<>();
        String[][] toResolve = {
            {"nemaki_repository", "nemaki://bedroom"},
            {"nemaki_folder", "nemaki://bedroom/objects/root-001"},
            {"nemaki_folder", "nemaki://bedroom/objects/folder-contracts"},
            {"nemaki_folder", "nemaki://bedroom/objects/folder-reports"},
            {"nemaki_document", "nemaki://bedroom/objects/doc-nda"},
            {"nemaki_document", "nemaki://bedroom/objects/doc-agreement"},
            {"nemaki_document", "nemaki://bedroom/objects/doc-q1report"},
            {"nemaki_type_definition", "nemaki://bedroom/types/D:custom:report"},
        };
        for (String[] pair : toResolve) {
            String guid = AtlasTestHelper.resolveGuidWithRetry(client, pair[0], pair[1], 5, 2000);
            if (guid != null) {
                guidByQn.put(pair[1], guid);
                System.out.println("  " + pair[1] + " -> " + guid);
            } else {
                System.out.println("  WARNING: Could not resolve " + pair[1]);
            }
        }

        // 4. Create relationships using factory guid overloads
        System.out.println("\n=== Step 4: Creating relationships ===");

        Folder rootFolder = new Folder();
        rootFolder.setId("root-001");
        createRel(client, "repo -> root-folder",
                factory.buildRepositoryFolderRelationship(repoId, rootFolder, guidByQn));

        Folder contracts = new Folder();
        contracts.setId("folder-contracts");
        contracts.setParentId("root-001");
        createRel(client, "root-folder -> Contracts",
                factory.buildFolderFolderRelationship(repoId, contracts, guidByQn));

        Folder reports = new Folder();
        reports.setId("folder-reports");
        reports.setParentId("root-001");
        createRel(client, "root-folder -> Reports",
                factory.buildFolderFolderRelationship(repoId, reports, guidByQn));

        Document nda = new Document();
        nda.setId("doc-nda");
        nda.setParentId("folder-contracts");
        createRel(client, "Contracts -> NDA",
                factory.buildFolderDocumentRelationship(repoId, nda, guidByQn));

        Document agreement = new Document();
        agreement.setId("doc-agreement");
        agreement.setParentId("folder-contracts");
        createRel(client, "Contracts -> Agreement",
                factory.buildFolderDocumentRelationship(repoId, agreement, guidByQn));

        Document q1 = new Document();
        q1.setId("doc-q1report");
        q1.setParentId("folder-reports");
        createRel(client, "Reports -> Q1 Report",
                factory.buildFolderDocumentRelationship(repoId, q1, guidByQn));

        Document q1ForType = new Document();
        q1ForType.setId("doc-q1report");
        q1ForType.setObjectType("D:custom:report");
        createRel(client, "Q1 Report -> type_def",
                factory.buildDocumentTypeRelationship(repoId, q1ForType, guidByQn));

        System.out.println("\n=== Done! Open http://localhost:21000 (admin/admin) ===");
    }

    private void createRel(AtlasDirectClient client, String label, Map<String, Object> payload) throws Exception {
        AtlasResponse resp = client.createRelationship(payload);
        System.out.println("  " + label + ": " + resp.statusCode()
                + (resp.isSuccess() ? " OK" : " FAILED: " + resp.body()));
    }

    private static Map<String, Object> makeEntity(String typeName, Map<String, Object> attributes) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", typeName);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", "admin");
        entity.put("updatedBy", "admin");
        entity.put("version", 0);
        return entity;
    }

    private static Map<String, Object> repoAttrs() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("qualifiedName", "nemaki://bedroom");
        attrs.put("name", "Bedroom Repository");
        attrs.put("description", "Primary test repository");
        attrs.put("owner", "admin");
        attrs.put("repositoryId", "bedroom");
        attrs.put("rootFolderId", "root-001");
        attrs.put("lifecycleState", "ACTIVE");
        attrs.put("createTime", System.currentTimeMillis());
        attrs.put("modifiedTime", System.currentTimeMillis());
        return attrs;
    }

    private static Map<String, Object> folderAttrs(String qn, String name, String objectId, String parentId) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("qualifiedName", qn);
        attrs.put("name", name);
        attrs.put("description", name + " folder");
        attrs.put("owner", "admin");
        attrs.put("repositoryId", "bedroom");
        attrs.put("objectId", objectId);
        if (parentId != null) attrs.put("parentId", parentId);
        attrs.put("typeId", "cmis:folder");
        attrs.put("lifecycleState", "ACTIVE");
        attrs.put("createTime", System.currentTimeMillis());
        attrs.put("modifiedTime", System.currentTimeMillis());
        return attrs;
    }

    private static Map<String, Object> docAttrs(String qn, String name, String objectId,
            String parentId, String typeId, String versionLabel) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("qualifiedName", qn);
        attrs.put("name", name);
        attrs.put("description", name);
        attrs.put("owner", "admin");
        attrs.put("repositoryId", "bedroom");
        attrs.put("objectId", objectId);
        attrs.put("parentId", parentId);
        attrs.put("typeId", typeId);
        attrs.put("versionLabel", versionLabel);
        attrs.put("isLatestVersion", true);
        attrs.put("lifecycleState", "ACTIVE");
        attrs.put("createTime", System.currentTimeMillis());
        attrs.put("modifiedTime", System.currentTimeMillis());
        return attrs;
    }

    private static Map<String, Object> typeDefAttrs() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("qualifiedName", "nemaki://bedroom/types/D:custom:report");
        attrs.put("name", "Custom Report");
        attrs.put("description", "Custom report type definition");
        attrs.put("owner", "admin");
        attrs.put("repositoryId", "bedroom");
        attrs.put("typeId", "D:custom:report");
        attrs.put("baseTypeId", "cmis:document");
        attrs.put("parentTypeId", "cmis:document");
        attrs.put("propertyCount", 0);
        attrs.put("lifecycleState", "ACTIVE");
        attrs.put("createTime", System.currentTimeMillis());
        attrs.put("modifiedTime", System.currentTimeMillis());
        return attrs;
    }
}
