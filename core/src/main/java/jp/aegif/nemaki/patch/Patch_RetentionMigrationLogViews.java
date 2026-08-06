package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Patch to add CouchDB views required for RetentionMigrationLog persistence.
 *
 * This patch adds the following view to the _design/_repo design document:
 * - retentionMigrationLogs: Query retention migration logs by startedAt (for descending listing)
 *
 * This patch is idempotent - it will not create duplicate views on restart.
 *
 * CRITICAL: This patch must execute AFTER Patch_StandardCmisViews to ensure
 * the design document exists.
 */
public class Patch_RetentionMigrationLogViews extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_RetentionMigrationLogViews.class);
    private static final String PATCH_NAME = "RetentionMigrationLogViews";

    @Override
    protected void applySystemPatch() {
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding retention migration log views");

        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
            if (client == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Could not get client for repository");
                return;
            }

            String designDocId = "_design/_repo";
            ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();

            JsonNode currentDoc = client.get(JsonNode.class, designDocId);
            if (currentDoc == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document not found");
                return;
            }

            ObjectNode updatedDoc = (ObjectNode) currentDoc.deepCopy();
            ObjectNode views = (ObjectNode) updatedDoc.get("views");
            if (views == null) {
                views = mapper.createObjectNode();
                updatedDoc.set("views", views);
            }

            // View: retentionMigrationLogs - Query by startedAt for descending listing
            addViewIfMissing(views, "retentionMigrationLogs",
                "function(doc) { if (doc.type == 'retentionMigrationLog' && doc.startedAt) emit(doc.startedAt, doc) }",
                null, repositoryId);

            client.update(updatedDoc);

            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Successfully added retention migration log views");

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add retention migration log views", e);
            throw new RuntimeException("Failed to apply retention migration log views patch", e);
        }
    }

    private void addViewIfMissing(ObjectNode views, String viewName, String mapFunction, String reduceFunction, String repositoryId) {
        if (!views.has(viewName)) {
            ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();
            ObjectNode viewDef = mapper.createObjectNode();
            viewDef.put("map", mapFunction);
            if (reduceFunction != null && !reduceFunction.isEmpty()) {
                viewDef.put("reduce", reduceFunction);
            }
            views.set(viewName, viewDef);
            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added missing view: " + viewName);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] View already exists: " + viewName);
            }
        }
    }

    @Override
    public String getName() {
        return PATCH_NAME;
    }
}
