package jp.aegif.nemaki.patch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Patch to narrow userItemsById and groupItemsById view definitions
 * by adding objectType filter (nemaki:user / nemaki:group).
 *
 * Background: The original bedroom_init.dump shipped broad view definitions
 * that match all cmis:item docs with userId/groupId fields, without filtering
 * by objectType. This inflates getUserItemCount/getGroupItemCount results,
 * which in turn destabilises Solr readiness coverage calculations.
 *
 * Separate from Patch_StandardCmisViews to ensure it runs on existing
 * environments where StandardCmisViews was already applied and skipped.
 */
public class Patch_NarrowUserGroupViews extends AbstractNemakiPatch {

	private static final Log log = LogFactory.getLog(Patch_NarrowUserGroupViews.class);
	private static final String PATCH_NAME = "NarrowUserGroupViews";

	private static final String NARROW_USER_MAP =
			"function(doc) { if (doc.type == 'cmis:item' && doc.objectType == 'nemaki:user' && doc.userId)  emit(doc.userId, doc) }";
	private static final String NARROW_GROUP_MAP =
			"function(doc) { if (doc.type == 'cmis:item' && doc.objectType == 'nemaki:group' && doc.groupId)  emit(doc.groupId, doc) }";

	@Override
	protected void applySystemPatch() {
		// No system-level changes needed
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
		if (client == null) {
			throw new RuntimeException("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Could not get client");
		}

		String designDocId = "_design/_repo";
		ObjectMapper mapper = new ObjectMapper();

		JsonNode currentDoc = client.get(JsonNode.class, designDocId);
		if (currentDoc == null) {
			throw new RuntimeException("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document not found");
		}

		ObjectNode updatedDoc = currentDoc.deepCopy();
		ObjectNode views = (ObjectNode) updatedDoc.get("views");
		if (views == null) {
			throw new RuntimeException("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] No views object in design document - database may be uninitialized");
		}

		boolean changed = false;
		changed |= updateViewIfNeeded(views, "userItemsById", NARROW_USER_MAP, repositoryId);
		changed |= updateViewIfNeeded(views, "groupItemsById", NARROW_GROUP_MAP, repositoryId);

		if (changed) {
			client.update(updatedDoc);
			log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document updated");
		} else {
			log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Views already narrow, no update needed");
		}
	}

	private boolean updateViewIfNeeded(ObjectNode views, String viewName, String narrowMap, String repositoryId) {
		if (!views.has(viewName)) {
			// View doesn't exist yet; create it
			ObjectMapper mapper = new ObjectMapper();
			ObjectNode viewDef = mapper.createObjectNode();
			viewDef.put("map", narrowMap);
			views.set(viewName, viewDef);
			log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Created view: " + viewName);
			return true;
		}

		JsonNode existing = views.get(viewName);
		String existingMap = existing.has("map") ? existing.get("map").asText() : "";

		if (narrowMap.equals(existingMap)) {
			return false; // already narrow
		}

		ObjectMapper mapper = new ObjectMapper();
		ObjectNode viewDef = mapper.createObjectNode();
		viewDef.put("map", narrowMap);
		views.set(viewName, viewDef);
		log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Updated view: " + viewName
				+ " (was: " + existingMap.substring(0, Math.min(existingMap.length(), 60)) + "...)");
		return true;
	}

	@Override
	public String getName() {
		return PATCH_NAME;
	}
}
