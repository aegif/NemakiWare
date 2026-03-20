package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Patch to add CouchDB views for Purview state queries in nemaki_conf.
 *
 * Adds:
 * - purviewStateByKind: Indexes purview state config documents by streamKind
 * - purviewStateByScopeAndKind: Indexes by [repositoryId, streamKind] compound key
 *
 * These views allow efficient query of dead-letter states and other purview state
 * without full-scanning all configuration documents.
 *
 * This patch operates on nemaki_conf (not per-repository).
 */
public class Patch_PurviewStateViews extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_PurviewStateViews.class);
    private static final String PATCH_NAME = "PurviewStateViews";

    private static final String PURVIEW_STATE_BY_KIND_MAP =
            "function(doc) {"
            + " if (doc.type === 'configuration' && doc.key && doc.key.indexOf('purview.dead-letter.state.') === 0) {"
            + "   var parts = doc.key.substring('purview.dead-letter.state.'.length).split('.');"
            + "   if (parts.length >= 4) {"
            + "     var repoId = parts[0];"
            + "     var streamKind = parts[1];"
            + "     var entryKeyEncoded = parts[2];"
            + "     var field = parts.slice(3).join('.');"
            + "     emit([streamKind, repoId, entryKeyEncoded, field], doc.value);"
            + "   }"
            + " }"
            + "}";

    private static final String PURVIEW_STATE_BY_SCOPE_AND_KIND_MAP =
            "function(doc) {"
            + " if (doc.type === 'configuration' && doc.key && doc.key.indexOf('purview.dead-letter.state.') === 0) {"
            + "   var parts = doc.key.substring('purview.dead-letter.state.'.length).split('.');"
            + "   if (parts.length >= 4) {"
            + "     var repoId = parts[0];"
            + "     var streamKind = parts[1];"
            + "     var entryKeyEncoded = parts[2];"
            + "     var field = parts.slice(3).join('.');"
            + "     emit([repoId, streamKind, entryKeyEncoded, field], doc.value);"
            + "   }"
            + " }"
            + "}";

    @Override
    protected void applySystemPatch() {
        log.info("[patch=" + PATCH_NAME + "] Adding Purview state views to nemaki_conf");

        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(SystemConst.NEMAKI_CONF_DB);
            if (client == null) {
                log.error("[patch=" + PATCH_NAME + "] Could not get client for nemaki_conf");
                return;
            }

            client.createOrUpdateView("_repo", "purviewStateByKind", PURVIEW_STATE_BY_KIND_MAP, null);
            client.createOrUpdateView("_repo", "purviewStateByScopeAndKind", PURVIEW_STATE_BY_SCOPE_AND_KIND_MAP, null);

            log.info("[patch=" + PATCH_NAME + "] Successfully added Purview state views to nemaki_conf");

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + "] Failed to add Purview state views", e);
            throw new RuntimeException("Failed to apply Purview state views patch", e);
        }
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.debug("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] No per-repository changes needed");
    }

    @Override
    public String getName() {
        return PATCH_NAME;
    }
}
