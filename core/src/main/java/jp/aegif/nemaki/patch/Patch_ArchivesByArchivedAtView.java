package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Patch to add archivesByArchivedAt view to the closet (archive) database.
 *
 * This view emits the archivedAt timestamp (epoch ms) as the key, filtering out
 * attachments and non-latest document versions. CouchDB sorts by key natively,
 * enabling efficient chronological pagination with skip/limit/descending at the
 * DB level — without loading all archives into memory.
 *
 * This patch is idempotent.
 */
public class Patch_ArchivesByArchivedAtView extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_ArchivesByArchivedAtView.class);
    private static final String PATCH_NAME = "ArchivesByArchivedAtView";

    @Override
    protected void applySystemPatch() {
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        // Get the archive (closet) repository ID for this repository
        String archiveRepositoryId = patchUtil.getRepositoryInfoMap().getArchiveId(repositoryId);
        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding/updating archivesByArchivedAt view in archive DB (" + archiveRepositoryId + ")");

        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(archiveRepositoryId);
            if (client == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Could not get client for archive repository: " + archiveRepositoryId);
                return;
            }

            String designDocId = "_design/_repo";
            ObjectMapper mapper = new ObjectMapper();

            JsonNode currentDoc = client.get(JsonNode.class, designDocId);
            if (currentDoc == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document not found in " + archiveRepositoryId);
                return;
            }

            ObjectNode updatedDoc = currentDoc.deepCopy();
            ObjectNode views = (ObjectNode) updatedDoc.get("views");
            if (views == null) {
                views = mapper.createObjectNode();
                updatedDoc.set("views", views);
            }

            // Filter out attachments and non-latest document versions.
            // Key by archivedAt (epoch ms) for chronological sorting by CouchDB.
            // Note: CouchDB field is "latestVersion" (not "isLatestVersion").
            String mapFn = "function(doc) { "
                    + "if (doc.type === 'attachment') return; "
                    + "if (doc.type === 'cmis:document' && doc.latestVersion !== true) return; "
                    + "var ts = doc.archivedAt || 0; "
                    + "emit(ts, null); "
                    + "}";

            boolean needsUpdate = false;
            if (!views.has("archivesByArchivedAt")) {
                needsUpdate = true;
            } else {
                // Check if existing view has correct map function (fix isLatestVersion → latestVersion)
                String existing = views.get("archivesByArchivedAt").get("map").asText();
                if (!existing.equals(mapFn)) {
                    needsUpdate = true;
                    log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Updating archivesByArchivedAt view (map function changed)");
                }
            }

            if (needsUpdate) {
                ObjectNode viewDef = mapper.createObjectNode();
                viewDef.put("map", mapFn);
                views.set("archivesByArchivedAt", viewDef);
                client.update(updatedDoc);
                log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added/updated archivesByArchivedAt view in " + archiveRepositoryId);
            } else {
                log.debug("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] archivesByArchivedAt view already up to date in " + archiveRepositoryId);
            }

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed", e);
            throw new RuntimeException("Failed to apply archivesByArchivedAt view patch", e);
        }
    }

    @Override
    public String getName() {
        return PATCH_NAME;
    }
}
