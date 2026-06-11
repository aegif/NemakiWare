package jp.aegif.nemaki.patch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.IndexDefinition;
import com.ibm.cloud.cloudant.v1.model.IndexField;
import com.ibm.cloud.cloudant.v1.model.PostIndexOptions;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;

/**
 * Registers a Mango {@code (type)} index on each per-repository content
 * database so {@code ContentDaoServiceImpl#getApiKeys} can resolve
 * {@code {"type":"apiKey"}} via {@code _find} without a full collection
 * scan.
 *
 * <p>Background: {@code getApiKeys} previously loaded the entire content
 * database with {@code _all_docs + include_docs=true} and filtered
 * {@code type=="apiKey"} in the JVM — an OOM risk on large repositories
 * (analogous to the RC7 {@code getLatestChange} fix). It was changed to a
 * Mango {@code _find} selector, which avoids materializing every document
 * body in the JVM, but without a matching index CouchDB still scans the
 * whole collection server-side. This patch adds the index that completes
 * the optimization.
 *
 * <p>Unlike {@link Patch_IngestMangoIndexes} (which targets the single
 * {@code nemaki_conf} system database), API keys live in the same content
 * DB as CMIS objects, so this patch runs per repository.
 *
 * <p>Idempotent: Cloudant's {@code postIndex} returns {@code result="exists"}
 * for an identical name+fields, and PatchHistory dedupes across deployments.
 * A registration failure throws so PatchHistory does not mark the patch
 * applied and the next startup retries.
 */
public class Patch_ApiKeyMangoIndex extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_ApiKeyMangoIndex.class);
    private static final String PATCH_NAME = "ApiKeyMangoIndex-20260611";
    private static final String INDEX_NAME = "idx_type";
    private static final String DDOC_NAME = "apikey-indexes";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        // No system-wide (nemaki_conf) work — API keys are per-repository.
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        if (patchUtil == null || patchUtil.getConnectorPool() == null) {
            log.warn("[patch=" + PATCH_NAME + "] connectorPool unavailable — skipping repo " + repositoryId);
            return;
        }
        CloudantClientWrapper client;
        try {
            client = patchUtil.getConnectorPool().getClient(repositoryId);
        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + "] could not obtain client for repo "
                    + repositoryId + ": " + e.getMessage());
            throw new RuntimeException("Patch_ApiKeyMangoIndex: client unavailable for " + repositoryId, e);
        }
        if (client == null) {
            throw new RuntimeException("Patch_ApiKeyMangoIndex: no client for repo " + repositoryId);
        }

        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        try {
            IndexDefinition def = new IndexDefinition.Builder()
                    .fields(List.of(new IndexField.Builder().add("type", "asc").build()))
                    .build();
            PostIndexOptions opts = new PostIndexOptions.Builder()
                    .db(db)
                    .index(def)
                    .name(INDEX_NAME)
                    .type(PostIndexOptions.Type.JSON)
                    .ddoc(DDOC_NAME)
                    .build();
            cloudant.postIndex(opts).execute().getResult();
            log.info("[patch=" + PATCH_NAME + "] registered (type) Mango index on '" + db + "'");
        } catch (Exception e) {
            log.warn("[patch=" + PATCH_NAME + "] failed to register (type) index on '"
                    + db + "': " + e.getMessage());
            throw new RuntimeException("Patch_ApiKeyMangoIndex: index registration failed for "
                    + db, e);
        }
    }
}
