package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Patch to add CouchDB view for querying documents by cmis:rm_expirationDate.
 *
 * This patch adds the following view to the _design/_repo design document
 * in the MAIN repository database (not closet):
 * - documentsByExpirationDate: Emits expirationDate value as key for
 *   efficient range queries to find expired documents.
 *
 * The view scans subTypeProperties for cmis:rm_expirationDate and only
 * indexes isLatestVersion documents of type cmis:document.
 *
 * Query: startkey=0&endkey=<currentEpochMillis> to find expired documents.
 *
 * This patch is idempotent.
 *
 * CRITICAL: Must execute AFTER Patch_StandardCmisViews to ensure
 * the design document exists.
 */
public class Patch_RetentionExpirationView extends AbstractNemakiPatch {
	private static final Log log = LogFactory.getLog(Patch_RetentionExpirationView.class);
	private static final String PATCH_NAME = "RetentionExpirationView";

	@Override
	protected void applySystemPatch() {
		log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding expiration date view");

		// Only apply to main repositories, skip archive/closet
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

			// View: documentsByExpirationDate
			// Scans subTypeProperties for cmis:rm_expirationDate and emits its value as key.
			// Only indexes isLatestVersion documents.
			String mapFunction =
				"function(doc) { " +
				"if (doc.type == 'cmis:document' && doc.isLatestVersion && doc.subTypeProperties) { " +
				"for (var i in doc.subTypeProperties) { " +
				"if (doc.subTypeProperties[i].key == 'cmis:rm_expirationDate') { " +
				"emit(doc.subTypeProperties[i].value, doc._id); " +
				"} } } }";

			addViewIfMissing(views, "documentsByExpirationDate", mapFunction, null, repositoryId);

			client.update(updatedDoc);

			log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Successfully added expiration date view");

		} catch (Exception e) {
			log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add expiration date view", e);
			throw new RuntimeException("Failed to apply retention expiration view patch", e);
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
