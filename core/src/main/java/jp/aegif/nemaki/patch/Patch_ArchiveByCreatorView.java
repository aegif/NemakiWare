package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Patch to add byCreator view to the closet (archive) database.
 *
 * This view enables querying archives by the user who created/deleted them,
 * allowing non-admin users to see and restore their own archives.
 *
 * This patch is idempotent - it will not create duplicate views on restart.
 */
public class Patch_ArchiveByCreatorView extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_ArchiveByCreatorView.class);
    private static final String PATCH_NAME = "ArchiveByCreatorView";

    @Override
    protected void applySystemPatch() {
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        // Only apply to closet (archive) repositories
        if (!repositoryId.endsWith("_closet")) {
            log.debug("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Skipping - not a closet repository");
            return;
        }

        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding byCreator view to archive DB");

        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
            if (client == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Could not get client for repository");
                return;
            }

            String designDocId = "_design/_repo";
            ObjectMapper mapper = new ObjectMapper();

            JsonNode currentDoc = client.get(JsonNode.class, designDocId);
            if (currentDoc == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document not found");
                return;
            }

            ObjectNode updatedDoc = currentDoc.deepCopy();
            ObjectNode views = (ObjectNode) updatedDoc.get("views");
            if (views == null) {
                views = mapper.createObjectNode();
                updatedDoc.set("views", views);
            }

            if (!views.has("byCreator")) {
                ObjectNode viewDef = mapper.createObjectNode();
                viewDef.put("map", "function(doc) { if (doc.creator) emit(doc.creator, doc); }");
                views.set("byCreator", viewDef);
                client.update(updatedDoc);
                log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added byCreator view");
            } else {
                log.debug("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] byCreator view already exists");
            }

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add byCreator view", e);
            throw new RuntimeException("Failed to apply archive byCreator view patch", e);
        }
    }

    @Override
    public String getName() {
        return PATCH_NAME;
    }
}
