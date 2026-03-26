package jp.aegif.nemaki.patch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.AllDocsResult;
import com.ibm.cloud.cloudant.v1.model.DocsResultRow;
import com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Migrates Purview state documents from nemaki_conf to dedicated nemaki_purview_state database.
 *
 * Documents with _id prefix "system_config_purview" are copied to the new database
 * and then deleted from the old database. The migration is idempotent: if a document
 * already exists in the target, it is skipped.
 *
 * This patch operates on system databases (not per-repository).
 */
public class Patch_PurviewStateMigration extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_PurviewStateMigration.class);
    private static final String PATCH_NAME = "PurviewStateMigration";
    private static final String DOC_ID_PREFIX = "system_config_purview";

    @Override
    protected void applySystemPatch() {
        log.info("[patch=" + PATCH_NAME + "] Migrating Purview state from nemaki_conf to " + SystemConst.NEMAKI_PURVIEW_STATE_DB);

        CloudantClientWrapper sourceClient;
        CloudantClientWrapper targetClient;
        try {
            sourceClient = patchUtil.getConnectorPool().getClient(SystemConst.NEMAKI_CONF_DB);
            if (sourceClient == null) {
                log.error("[patch=" + PATCH_NAME + "] Source client (nemaki_conf) not available");
                return;
            }
        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + "] Failed to get source client", e);
            return;
        }

        try {
            targetClient = patchUtil.getConnectorPool().getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
            if (targetClient == null) {
                log.info("[patch=" + PATCH_NAME + "] Target database not yet available, skipping migration");
                return;
            }
        } catch (Exception e) {
            log.info("[patch=" + PATCH_NAME + "] Target database not yet available, skipping migration: " + e.getMessage());
            return;
        }

        try {
            Cloudant client = sourceClient.getClient();
            String sourceDbName = sourceClient.getDatabaseName();

            // Query all Purview documents from nemaki_conf
            PostAllDocsOptions options = new PostAllDocsOptions.Builder()
                    .db(sourceDbName)
                    .includeDocs(true)
                    .startKey(DOC_ID_PREFIX)
                    .endKey(DOC_ID_PREFIX + "\uffff")
                    .build();

            AllDocsResult result = client.postAllDocs(options).execute().getResult();
            List<DocsResultRow> rows = result.getRows();

            if (rows == null || rows.isEmpty()) {
                log.info("[patch=" + PATCH_NAME + "] No Purview state documents found in nemaki_conf");
                return;
            }

            log.info("[patch=" + PATCH_NAME + "] Found " + rows.size() + " Purview state documents to migrate");

            int migrated = 0;
            int skipped = 0;
            int failed = 0;

            for (DocsResultRow row : rows) {
                if (row.getDoc() == null) {
                    continue;
                }

                String docId = row.getId();
                try {
                    // Check if already exists in target
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingInTarget = targetClient.get(Map.class, docId);
                    if (existingInTarget != null) {
                        skipped++;
                        continue;
                    }

                    // Copy to target (without _rev)
                    Map<String, Object> docProps = new LinkedHashMap<>(row.getDoc().getProperties());
                    docProps.remove("_rev");
                    docProps.put("_id", docId);

                    targetClient.create(docId, docProps);

                    // Delete from source after successful copy
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sourceDoc = sourceClient.get(Map.class, docId);
                    if (sourceDoc != null) {
                        sourceClient.delete(sourceDoc);
                    }

                    migrated++;
                } catch (Exception e) {
                    log.warn("[patch=" + PATCH_NAME + "] Failed to migrate document " + docId + ": " + e.getMessage());
                    failed++;
                    // Continue with other documents
                }
            }

            log.info("[patch=" + PATCH_NAME + "] Migration complete: migrated=" + migrated
                    + ", skipped=" + skipped + ", failed=" + failed);

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + "] Migration failed", e);
            // Don't throw — allow re-execution on next startup
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
