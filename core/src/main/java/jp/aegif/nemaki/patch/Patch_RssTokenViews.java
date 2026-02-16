package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Patch to add CouchDB views required for RSS token persistence.
 *
 * This patch adds the following views to the _design/_repo design document:
 * - rssTokensByToken: Query tokens by token value (for authentication)
 * - rssTokensByUserId: Query tokens by userId (for user's token list)
 * - rssTokensByExpiresAt: Query tokens by expiration date (for cleanup)
 *
 * This patch is idempotent - it will not create duplicate views on restart.
 *
 * CRITICAL: This patch must execute AFTER Patch_StandardCmisViews to ensure
 * the design document exists.
 */
public class Patch_RssTokenViews extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_RssTokenViews.class);
    private static final String PATCH_NAME = "RssTokenViews";

    @Override
    protected void applySystemPatch() {
        // No system-wide changes needed
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding RSS token views");

        // Skip archive and canopy repositories
        if ("canopy".equals(repositoryId) ||
            "bedroom_closet".equals(repositoryId) ||
            "canopy_closet".equals(repositoryId)) {
            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Skipping - archive/canopy repository");
            return;
        }

        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
            if (client == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Could not get client for repository");
                return;
            }

            // Get current design document
            String designDocId = "_design/_repo";
            ObjectMapper mapper = new ObjectMapper();

            // Read current design document
            JsonNode currentDoc = client.get(JsonNode.class, designDocId);
            if (currentDoc == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document not found");
                return;
            }

            // Clone the document as ObjectNode for modification
            ObjectNode updatedDoc = currentDoc.deepCopy();
            ObjectNode views = (ObjectNode) updatedDoc.get("views");
            if (views == null) {
                views = mapper.createObjectNode();
                updatedDoc.set("views", views);
            }

            // View: rssTokensByToken - Query by token value (for authentication)
            addViewIfMissing(views, "rssTokensByToken",
                "function(doc) { if (doc.type == 'rssToken' && doc.token) emit(doc.token, doc) }",
                null, repositoryId);

            // View: rssTokensByUserId - Query by userId (for user's token list)
            addViewIfMissing(views, "rssTokensByUserId",
                "function(doc) { if (doc.type == 'rssToken' && doc.userId) emit(doc.userId, doc) }",
                null, repositoryId);

            // View: rssTokensByExpiresAt - Query by expiration date (for cleanup)
            addViewIfMissing(views, "rssTokensByExpiresAt",
                "function(doc) { if (doc.type == 'rssToken' && doc.expiresAt) emit(doc.expiresAt, doc) }",
                null, repositoryId);

            // Update the design document
            client.update(updatedDoc);

            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Successfully added RSS token views");

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add RSS token views", e);
            throw new RuntimeException("Failed to apply RSS token views patch", e);
        }
    }

    private void addViewIfMissing(ObjectNode views, String viewName, String mapFunction, String reduceFunction, String repositoryId) {
        if (!views.has(viewName)) {
            ObjectMapper mapper = new ObjectMapper();
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
