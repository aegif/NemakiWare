package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Patch to add CouchDB view for querying documents by lastModificationDate
 * that do NOT have cmis:rm_expirationDate set.
 *
 * Used by the retention scheduler's "local archive after days" fallback:
 * documents without an explicit expiration date are archived based on
 * how long ago they were last modified.
 *
 * View: documentsByLastModification
 *   - Filters: type='cmis:document', latestVersion=true, no cmis:rm_expirationDate
 *   - Key: modified (epoch millis)
 *   - Value: doc._id
 *
 * Query: startkey=0&endkey=<cutoffEpochMillis> to find stale documents.
 *
 * This patch is idempotent.
 */
public class Patch_RetentionLastModificationView extends AbstractNemakiPatch {
	private static final Log log = LogFactory.getLog(Patch_RetentionLastModificationView.class);
	private static final String PATCH_NAME = "RetentionLastModificationView";

	@Override
	protected void applySystemPatch() {
		log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding last-modification date view");

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

			// View: documentsByLastModification
			// Emits modified (epoch millis) for latestVersion documents WITHOUT cmis:rm_expirationDate.
			// This enables the "archive after N days of inactivity" fallback retention.
			String mapFunction =
				"function(doc) { " +
				"if (doc.type == 'cmis:document' && doc.latestVersion && doc.modified) { " +
				"var hasExpiration = false; " +
				"if (doc.subTypeProperties) { " +
				"for (var i in doc.subTypeProperties) { " +
				"if (doc.subTypeProperties[i].key == 'cmis:rm_expirationDate') { " +
				"hasExpiration = true; break; " +
				"} } } " +
				"if (!hasExpiration) { emit(doc.modified, doc._id); } " +
				"} }";

			boolean modified = false;
			if (!views.has("documentsByLastModification")) {
				ObjectNode viewDef = mapper.createObjectNode();
				viewDef.put("map", mapFunction);
				views.set("documentsByLastModification", viewDef);
				modified = true;
				log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId
						+ "] Added view: documentsByLastModification");
			} else {
				log.debug("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId
						+ "] View already exists: documentsByLastModification");
			}

			if (modified) {
				client.update(updatedDoc);
			}

			log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Successfully added last-modification view");

		} catch (Exception e) {
			log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add last-modification view", e);
			throw new RuntimeException("Failed to apply retention last-modification view patch", e);
		}
	}

	@Override
	public String getName() {
		return PATCH_NAME;
	}
}
