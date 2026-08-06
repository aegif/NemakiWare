package jp.aegif.nemaki.patch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Patch to add _count reduce function to the children view.
 * This enables efficient getChildrenCount() for server-side pagination.
 *
 * Separate from Patch_StandardCmisViews to ensure it runs on upgrade
 * from 2.4 environments where StandardCmisViews was already applied.
 */
public class Patch_ChildrenViewReduceCount extends AbstractNemakiPatch {

	private static final Log log = LogFactory.getLog(Patch_ChildrenViewReduceCount.class);
	private static final String PATCH_NAME = "ChildrenViewReduceCount";

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
		ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();

		JsonNode currentDoc = client.get(JsonNode.class, designDocId);
		if (currentDoc == null) {
			throw new RuntimeException("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document not found");
		}

		ObjectNode updatedDoc = (ObjectNode) currentDoc.deepCopy();
		ObjectNode views = (ObjectNode) updatedDoc.get("views");
		if (views == null || !views.has("children")) {
			throw new RuntimeException("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] children view not found in design document");
		}

		JsonNode childrenView = views.get("children");
		String existingReduce = childrenView.has("reduce") ? childrenView.get("reduce").asText() : "";

		if ("_count".equals(existingReduce)) {
			log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] children view already has _count reduce");
			return;
		}

		// Add _count reduce while preserving the existing map function
		String mapFunction = childrenView.get("map").asText();
		ObjectNode updatedView = mapper.createObjectNode();
		updatedView.put("map", mapFunction);
		updatedView.put("reduce", "_count");
		views.set("children", updatedView);

		client.update(updatedDoc);
		log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added _count reduce to children view");
	}

	@Override
	public String getName() {
		return PATCH_NAME;
	}
}
