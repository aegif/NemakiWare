package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Patch to add CouchDB views required for WebAuthn credential persistence.
 *
 * These views were originally included in Patch_StandardCmisViews, but
 * environments where that patch was already applied before WebAuthn support
 * was added will be missing these views. This separate patch ensures the
 * views are added regardless of StandardCmisViews execution order.
 *
 * Views added:
 * - webauthnCredentialsByUserId: Query credentials by userId
 * - webauthnCredentialsByCredentialId: Query credentials by credentialId
 */
public class Patch_WebAuthnCredentialViews extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_WebAuthnCredentialViews.class);
    private static final String PATCH_NAME = "WebAuthnCredentialViews";

    @Override
    protected void applySystemPatch() {
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding WebAuthn credential views");

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

            boolean modified = false;

            if (!views.has("webauthnCredentialsByUserId")) {
                ObjectNode viewDef = mapper.createObjectNode();
                viewDef.put("map", "function(doc) { if (doc.type == 'cmis:item' && doc.objectType == 'nemaki:webauthnCredential') emit(doc.userId, doc) }");
                views.set("webauthnCredentialsByUserId", viewDef);
                modified = true;
                log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added view: webauthnCredentialsByUserId");
            }

            if (!views.has("webauthnCredentialsByCredentialId")) {
                ObjectNode viewDef = mapper.createObjectNode();
                viewDef.put("map", "function(doc) { if (doc.type == 'cmis:item' && doc.objectType == 'nemaki:webauthnCredential') emit(doc.credentialId, doc) }");
                views.set("webauthnCredentialsByCredentialId", viewDef);
                modified = true;
                log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added view: webauthnCredentialsByCredentialId");
            }

            if (modified) {
                client.update(updatedDoc);
                log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Successfully added WebAuthn credential views");
            } else {
                log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] WebAuthn views already exist - no changes needed");
            }

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add WebAuthn credential views", e);
            throw new RuntimeException("Failed to apply WebAuthn credential views patch", e);
        }
    }


    /**
     * Always run this patch regardless of patch history.
     * applyPerRepositoryPatch() is idempotent (checks views.has() before adding),
     * so re-running is safe and ensures views exist even if the design document
     * was recreated after the patch was originally applied.
     */
    @Override
    public boolean apply() {
        log.info("Applying patch: " + getName() + " (always-run, idempotent)");
        applySystemPatch();

        boolean allSucceeded = true;
        for (String repositoryId : patchUtil.getRepositoryInfoMap().keys()) {
            // Skip archive repositories — patches are only for main repositories
            if (patchUtil.getRepositoryInfoMap().isArchiveRepository(repositoryId)) {
                if (log.isDebugEnabled()) {
                    log.debug("[patch=" + getName() + "] Skipping archive repository: " + repositoryId);
                }
                continue;
            }
            // The gate, which this override used to skip entirely. "Always run, idempotent"
            // is true of the VIEW work — views.has() is read from the design document — but
            // not of the two lines below it: isApplied() is a view-based existence check and
            // createPathHistory() writes under a generated id, which is exactly how bedroom
            // ended up with two history rows for one patch name.
            if (!patchUtil.cmisViewsAreAnswering(repositoryId)) {
                log.error("[patch=" + getName() + ", repositoryId=" + repositoryId
                        + "] skipped: the repository's views are not answering, so its patch"
                        + " history cannot be read without risking a duplicate row.");
                allSucceeded = false;
                continue;
            }
            try {
                applyPerRepositoryPatch(repositoryId);
                if (!patchUtil.isApplied(repositoryId, getName())) {
                    patchUtil.createPathHistory(repositoryId, getName());
                }
            } catch (Exception e) {
                log.error("[patch=" + getName() + ", repositoryId=" + repositoryId + "] failed", e);
                allSucceeded = false;
            }
        }
        log.info("Patch " + getName() + " completed (success=" + allSucceeded + ")");
        return allSucceeded;
    }

    @Override
    public String getName() {
        return PATCH_NAME;
    }
}
