package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Patch to add CouchDB views required for WebhookDeliveryLog persistence.
 *
 * This patch adds the following views to the _design/_repo design document:
 * - webhookDeliveryLogsByDeliveryId: Query delivery logs by deliveryId
 * - webhookDeliveryLogsByObjectId: Query delivery logs by objectId
 * - webhookDeliveryLogsByTimestamp: Query delivery logs by timestamp (for cleanup)
 * - webhookDeliveryLogsByWebhookId: Query delivery logs by webhookId
 * - webhookDeliveryLogsByStatus: Query delivery logs by status (for retry)
 *
 * This patch is idempotent - it will not create duplicate views on restart.
 *
 * CRITICAL: This patch must execute AFTER Patch_StandardCmisViews to ensure
 * the design document exists.
 */
public class Patch_WebhookDeliveryLogViews extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_WebhookDeliveryLogViews.class);
    private static final String PATCH_NAME = "WebhookDeliveryLogViews";

    @Override
    protected void applySystemPatch() {
        // No system-wide changes needed
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding webhook delivery log views");

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

            // Add webhook delivery log views
            // View: webhookDeliveryLogsByDeliveryId - Query by deliveryId
            addViewIfMissing(views, "webhookDeliveryLogsByDeliveryId",
                "function(doc) { if (doc.type == 'webhookDeliveryLog' && doc.deliveryId) emit(doc.deliveryId, doc) }",
                null, repositoryId);

            // View: webhookDeliveryLogsByObjectId - Query by objectId
            addViewIfMissing(views, "webhookDeliveryLogsByObjectId",
                "function(doc) { if (doc.type == 'webhookDeliveryLog' && doc.objectId) emit(doc.objectId, doc) }",
                null, repositoryId);

            // View: webhookDeliveryLogsByTimestamp - Query by timestamp for cleanup
            addViewIfMissing(views, "webhookDeliveryLogsByTimestamp",
                "function(doc) { if (doc.type == 'webhookDeliveryLog' && doc.timestamp) emit(doc.timestamp, doc) }",
                null, repositoryId);

            // View: webhookDeliveryLogsByWebhookId - Query by webhookId
            addViewIfMissing(views, "webhookDeliveryLogsByWebhookId",
                "function(doc) { if (doc.type == 'webhookDeliveryLog' && doc.webhookId) emit(doc.webhookId, doc) }",
                null, repositoryId);

            // View: webhookDeliveryLogsByStatus - Query by status for retry
            addViewIfMissing(views, "webhookDeliveryLogsByStatus",
                "function(doc) { if (doc.type == 'webhookDeliveryLog' && doc.status) emit(doc.status, doc) }",
                null, repositoryId);

            // Update the design document
            client.update(updatedDoc);

            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Successfully added webhook delivery log views");

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add webhook delivery log views", e);
            throw new RuntimeException("Failed to apply webhook delivery log views patch", e);
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
