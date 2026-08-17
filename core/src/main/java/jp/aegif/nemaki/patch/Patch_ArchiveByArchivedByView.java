package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Patch to add byArchivedBy view to the closet (archive) database.
 *
 * This view enables querying archives by the user who performed the deletion,
 * as opposed to byCreator which queries by the original document owner.
 * Together, these views allow both the original owner and the deleting user
 * to access their archives.
 *
 * This patch is idempotent - it will not create duplicate views on restart.
 */
public class Patch_ArchiveByArchivedByView extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_ArchiveByArchivedByView.class);
    private static final String PATCH_NAME = "ArchiveByArchivedByView";

    @Override
    protected void applySystemPatch() {
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        // Get the archive (closet) repository ID for this repository
        String archiveRepositoryId = patchUtil.getRepositoryInfoMap().getArchiveId(repositoryId);
        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding byArchivedBy view to archive DB (" + archiveRepositoryId + ")");

        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(archiveRepositoryId);
            if (client == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Could not get client for archive repository: " + archiveRepositoryId);
                return;
            }

            String designDocId = "_design/_repo";
            ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();

            JsonNode currentDoc = client.get(JsonNode.class, designDocId);
            if (currentDoc == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document not found in " + archiveRepositoryId);
                return;
            }

            ObjectNode updatedDoc = (ObjectNode) currentDoc.deepCopy();
            ObjectNode views = (ObjectNode) updatedDoc.get("views");
            if (views == null) {
                views = mapper.createObjectNode();
                updatedDoc.set("views", views);
            }

            if (!views.has("byArchivedBy")) {
                ObjectNode viewDef = mapper.createObjectNode();
                viewDef.put("map", "function(doc) { if (doc.archivedBy) emit(doc.archivedBy, doc); }");
                views.set("byArchivedBy", viewDef);
                client.update(updatedDoc);
                log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added byArchivedBy view to " + archiveRepositoryId);
            } else {
                log.debug("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] byArchivedBy view already exists in " + archiveRepositoryId);
            }

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add byArchivedBy view", e);
            throw new RuntimeException("Failed to apply archive byArchivedBy view patch", e);
        }
    }

    @Override
    public String getName() {
        return PATCH_NAME;
    }
}
