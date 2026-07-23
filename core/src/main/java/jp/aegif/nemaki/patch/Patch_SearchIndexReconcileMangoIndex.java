package jp.aegif.nemaki.patch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.IndexDefinition;
import com.ibm.cloud.cloudant.v1.model.IndexField;
import com.ibm.cloud.cloudant.v1.model.IndexResult;
import com.ibm.cloud.cloudant.v1.model.PostIndexOptions;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Registers Cloudant / CouchDB Mango indexes for the
 * {@code searchIndexAclReindexTask} reconciliation-queue records stored in
 * {@code nemaki_conf} (see {@link jp.aegif.nemaki.reconcile.SearchIndexReconciliationService}).
 * Without these, every {@code _find} on the queue falls back to a full
 * {@code _all_docs} scan.
 *
 * <p>Idempotent (Cloudant {@code postIndex} returns {@code result="exists"} for an
 * identical index; PatchHistory dedupes across deployments). System-level, so it
 * runs in {@link #applySystemPatch()}.
 */
public class Patch_SearchIndexReconcileMangoIndex extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_SearchIndexReconcileMangoIndex.class);
    private static final String PATCH_NAME = "SearchIndexReconcileMangoIndex-20260724";

    private record IndexSpec(String name, List<String> fields) {
        IndexSpec(String name, String... fields) {
            this(name, Arrays.asList(fields));
        }
    }

    private static final List<IndexSpec> INDEXES = List.of(
            // upsert key-match / getByTaskId / delete
            new IndexSpec("idx_type_sirTaskId", "type", "taskId"),
            // listDue (status=PENDING) + admin list by status
            new IndexSpec("idx_type_sirStatus", "type", "status"),
            // enqueue dedupe by (repositoryId, objectId)
            new IndexSpec("idx_type_sirRepoObject", "type", "repositoryId", "objectId")
    );

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        if (patchUtil == null || patchUtil.getConnectorPool() == null) {
            log.warn("[patch=" + PATCH_NAME + "] connectorPool unavailable — skipping");
            return;
        }
        CloudantClientWrapper client;
        try {
            client = patchUtil.getConnectorPool().getClient(SystemConst.NEMAKI_CONF_DB);
        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + "] could not obtain client for "
                    + SystemConst.NEMAKI_CONF_DB + ": " + e.getMessage());
            return;
        }
        if (client == null) {
            log.error("[patch=" + PATCH_NAME + "] no client for " + SystemConst.NEMAKI_CONF_DB);
            return;
        }

        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        log.info("[patch=" + PATCH_NAME + "] registering " + INDEXES.size()
                + " Mango indexes on database '" + db + "'");

        int processed = 0, failed = 0;
        for (IndexSpec spec : INDEXES) {
            try {
                IndexDefinition def = buildDefinition(spec.fields());
                PostIndexOptions opts = new PostIndexOptions.Builder()
                        .db(db)
                        .index(def)
                        .name(spec.name())
                        .type(PostIndexOptions.Type.JSON)
                        .ddoc("search-index-reconcile-indexes")
                        .build();
                IndexResult result = cloudant.postIndex(opts).execute().getResult();
                processed++;
                if (log.isDebugEnabled()) {
                    log.debug("[patch=" + PATCH_NAME + "] index '" + spec.name()
                            + "' result=" + (result != null ? result.getResult() : "null"));
                }
            } catch (Exception e) {
                failed++;
                log.warn("[patch=" + PATCH_NAME + "] failed to register index '"
                        + spec.name() + "': " + e.getMessage());
            }
        }
        log.info("[patch=" + PATCH_NAME + "] complete — processed=" + processed
                + ", failed=" + failed + " (out of " + INDEXES.size() + ")");
        if (failed > 0) {
            throw new RuntimeException("Patch_SearchIndexReconcileMangoIndex: " + failed
                    + " index(es) failed to register (see WARN log)");
        }
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        // nemaki_conf is system-wide; no per-repository work
    }

    private IndexDefinition buildDefinition(List<String> fields) {
        List<IndexField> indexFields = new ArrayList<>();
        for (String f : fields) {
            indexFields.add(new IndexField.Builder().add(f, "asc").build());
        }
        return new IndexDefinition.Builder().fields(indexFields).build();
    }
}
