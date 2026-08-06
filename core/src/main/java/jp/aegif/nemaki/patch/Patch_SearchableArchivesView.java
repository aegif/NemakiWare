package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Patch to add searchableArchives view to the closet (archive) database.
 *
 * This view pre-filters attachments and old document versions at the DB level,
 * emitting only "searchable" archives keyed by archiveState.
 * This significantly reduces the data loaded for the archive/search endpoint.
 *
 * This patch is idempotent.
 */
public class Patch_SearchableArchivesView extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_SearchableArchivesView.class);
    private static final String PATCH_NAME = "SearchableArchivesView";

    @Override
    protected void applySystemPatch() {
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        // Get the archive (closet) repository ID for this repository
        String archiveRepositoryId = patchUtil.getRepositoryInfoMap().getArchiveId(repositoryId);
        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding/updating searchableArchives view in archive DB (" + archiveRepositoryId + ")");

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

            // Composite key [state, archivedAt] enables:
            //   - State-based filtering via startkey/endkey
            //   - Chronological sorting within each state (archivedAt as second key element)
            //   - DB-level pagination with skip/limit preserving chronological order
            // The _count reduce enables accurate per-state counts via group_level=1.
            // Note: CouchDB field is "latestVersion" (not "isLatestVersion").
            String mapFn = "function(doc) { "
                    + "if (doc.type === 'attachment') return; "
                    + "if (doc.type === 'cmis:document' && doc.latestVersion !== true) return; "
                    + "var state = doc.archiveState || 'ARCHIVED_LOCAL'; "
                    + "var ts = doc.archivedAt || 0; "
                    + "emit([state, ts], null); "
                    + "}";
            String reduceFn = "_count";

            boolean needsUpdate = false;
            if (!views.has("searchableArchives")) {
                needsUpdate = true;
            } else {
                // Check if existing view has correct map/reduce functions
                JsonNode existingView = views.get("searchableArchives");
                String existingMap = existingView.has("map") ? existingView.get("map").asText() : "";
                String existingReduce = existingView.has("reduce") ? existingView.get("reduce").asText() : "";
                if (!existingMap.equals(mapFn) || !existingReduce.equals(reduceFn)) {
                    needsUpdate = true;
                    log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Updating searchableArchives view (map/reduce function changed)");
                }
            }

            if (needsUpdate) {
                ObjectNode viewDef = mapper.createObjectNode();
                viewDef.put("map", mapFn);
                viewDef.put("reduce", reduceFn);
                views.set("searchableArchives", viewDef);
                client.update(updatedDoc);
                log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added/updated searchableArchives view in " + archiveRepositoryId);
            } else {
                log.debug("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] searchableArchives view already up to date in " + archiveRepositoryId);
            }

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add searchableArchives view", e);
            throw new RuntimeException("Failed to apply searchableArchives view patch", e);
        }
    }

    @Override
    public String getName() {
        return PATCH_NAME;
    }
}
