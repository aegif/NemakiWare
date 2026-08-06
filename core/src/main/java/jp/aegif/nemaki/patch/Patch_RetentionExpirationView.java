package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Patch to add CouchDB view for querying documents by cmis:rm_expirationDate.
 *
 * This patch adds the following view to the _design/_repo design document
 * in the MAIN repository database (not closet):
 * - documentsByExpirationDate: Emits expirationDate value as key for
 *   efficient range queries to find expired documents.
 *
 * The view scans subTypeProperties for cmis:rm_expirationDate and only
 * indexes latestVersion documents of type cmis:document.
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
	private static final String PATCH_NAME = "RetentionExpirationViewV2";

	@Override
	protected void applySystemPatch() {
		log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding expiration date view");

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

			// View: documentsByExpirationDate
			// Scans subTypeProperties for cmis:rm_expirationDate and emits its value as key.
			// Only indexes latestVersion documents.
			// NOTE: CouchDB field is "latestVersion" (not "isLatestVersion").
			String mapFunction =
				"function(doc) { " +
				"if (doc.type == 'cmis:document' && doc.latestVersion && doc.subTypeProperties) { " +
				"for (var i in doc.subTypeProperties) { " +
				"if (doc.subTypeProperties[i].key == 'cmis:rm_expirationDate') { " +
				"emit(doc.subTypeProperties[i].value, doc._id); " +
				"} } } }";

			// Force-update the view to fix the isLatestVersion→latestVersion field name bug
			if (views.has("documentsByExpirationDate")) {
				JsonNode existingView = views.get("documentsByExpirationDate");
				String existingMap = existingView.has("map") ? existingView.get("map").asText() : "";
				if (existingMap.contains("isLatestVersion")) {
					log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId
						+ "] Updating documentsByExpirationDate view: fixing isLatestVersion→latestVersion");
					ObjectNode viewDef = mapper.createObjectNode();
					viewDef.put("map", mapFunction);
					views.set("documentsByExpirationDate", viewDef);
				}
			} else {
				addViewIfMissing(views, "documentsByExpirationDate", mapFunction, null, repositoryId);
			}

			client.update(updatedDoc);

			log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Successfully added expiration date view");

		} catch (Exception e) {
			log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add expiration date view", e);
			throw new RuntimeException("Failed to apply retention expiration view patch", e);
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
