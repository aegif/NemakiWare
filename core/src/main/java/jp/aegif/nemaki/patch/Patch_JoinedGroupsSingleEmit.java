package jp.aegif.nemaki.patch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Collapse {@code joinedDirectGroupsByGroupId} from twenty identical emits per edge to one
 * (ledger item F4).
 *
 * <h2>What it was doing</h2>
 *
 * <p>For every nested-group edge the map emitted the SAME parent document twenty times, under
 * keys {@code [parentGroupId, 0]} through {@code [parentGroupId, 19]}. The twenty rows are
 * byte-identical — the second element carries no depth or any other information — so the index
 * held twenty entries where one would do, and every write to a group rewrote twenty rows.
 *
 * <h2>Why the key SHAPE is preserved</h2>
 *
 * <p>The obvious tidy-up is a scalar key. It is the wrong move here: both consumers
 * ({@code checkIndirectGroup} and the delete-time reverse lookup) query
 * {@code startkey}/{@code endkey} = {@code [id, 0]}, and a scalar key matches neither — the
 * query would silently return nothing rather than fail, which is how this view's composite key
 * caused a bug before. Emitting only at index 0 keeps every existing query correct with no
 * coordinated change, so there is no ordering hazard between the view and its readers.
 *
 * <p>Consequently this patch does not change any Java. The consumers already read exactly one
 * row; what they stop doing is asking an index that is twenty times larger than it needs to be.
 *
 * <h2>Cost of applying it</h2>
 *
 * <p>Changing a map function invalidates the design document's whole index, so CouchDB rebuilds
 * ALL views in {@code _design/_repo} on the next query. That is a maintenance-window operation
 * on a large repository, not a routine upgrade step. It is separate from
 * {@code Patch_StandardCmisViews} for the usual reason — that patch is already applied
 * everywhere, so editing its source would never reach an existing deployment.
 */
public class Patch_JoinedGroupsSingleEmit extends AbstractNemakiPatch {

	private static final Log log = LogFactory.getLog(Patch_JoinedGroupsSingleEmit.class);
	private static final String PATCH_NAME = "JoinedGroupsSingleEmit";
	private static final String VIEW = "joinedDirectGroupsByGroupId";

	/** One emit per edge, at index 0 — the key the consumers already ask for. */
	private static final String SINGLE_EMIT_MAP =
			"function(doc) {\n"
			+ "    if (doc.type == 'cmis:item' && doc.groupId) {\n"
			+ "        if (doc.subTypeProperties) {\n"
			+ "            for (var i in doc.subTypeProperties) {\n"
			+ "                if (doc.subTypeProperties[i].key == 'nemaki:groups') {\n"
			+ "                    for (var group in doc.subTypeProperties[i].value) {\n"
			+ "                        emit([doc.subTypeProperties[i].value[group],0], doc);\n"
			+ "                    }\n"
			+ "                }\n"
			+ "            }\n"
			+ "        }\n"
			+ "    }\n"
			+ "}";

	@Override
	protected void applySystemPatch() {
		// No system-level changes needed
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
		if (client == null) {
			throw new RuntimeException("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId
					+ "] Could not get client");
		}

		String designDocId = "_design/_repo";
		ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();

		JsonNode currentDoc = client.get(JsonNode.class, designDocId);
		if (currentDoc == null) {
			throw new RuntimeException("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId
					+ "] Design document not found");
		}

		ObjectNode updatedDoc = (ObjectNode) currentDoc.deepCopy();
		ObjectNode views = (ObjectNode) updatedDoc.get("views");
		if (views == null || !views.has(VIEW)) {
			// Not an error: a repository created before this view existed simply has nothing to
			// collapse, and Patch_StandardCmisViews will add the (already single-emit) version.
			log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] " + VIEW
					+ " not present — nothing to collapse");
			return;
		}

		JsonNode view = views.get(VIEW);
		String existingMap = view.has("map") ? view.get("map").asText() : "";
		if (existingMap.equals(SINGLE_EMIT_MAP)) {
			log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] " + VIEW
					+ " already emits once");
			return;
		}
		// Count what is being removed, so the log says what the rebuild bought.
		int emits = existingMap.split("emit\\(", -1).length - 1;

		ObjectNode updatedView = mapper.createObjectNode();
		updatedView.put("map", SINGLE_EMIT_MAP);
		if (view.has("reduce")) {
			updatedView.put("reduce", view.get("reduce").asText());
		}
		views.set(VIEW, updatedView);

		client.update(updatedDoc);
		log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] " + VIEW
				+ ": " + emits + " emits per edge -> 1. CouchDB will rebuild every view in "
				+ designDocId + " on the next query — expect the first query after this to be "
				+ "slow in proportion to the repository size.");
	}

	@Override
	public String getName() {
		return PATCH_NAME;
	}
}
