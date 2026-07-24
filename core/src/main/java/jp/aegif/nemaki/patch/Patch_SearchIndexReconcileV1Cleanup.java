package jp.aegif.nemaki.patch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask;
import jp.aegif.nemaki.util.constant.SystemConst;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;
import java.util.Map;

/**
 * One-time cleanup of FIRST-GENERATION reconciliation-queue documents in
 * {@code nemaki_conf}. The initial {@code searchIndexAclReindexTask} format used
 * an auto-generated {@code _id} and ISO-string timestamps; the reworked format
 * uses a DETERMINISTIC {@code _id}
 * ({@code search-index-acl-reconcile::{repo}::{object}}) and epoch-millis times.
 * An old document therefore (a) fails to deserialize into the new model
 * (ISO→long) and is ignored, and (b) never collides with the new deterministic
 * {@code _id}, so it lingers as a dead row.
 *
 * <p>The first format was never tagged/released, so the safe migration is
 * deletion: any queue entry that is still relevant will be re-enqueued the next
 * time its object's ACL refresh fails (or covered by the mandatory full reindex).
 * This deletes only {@code searchIndexAclReindexTask} documents whose {@code _id}
 * does NOT start with the deterministic prefix. Idempotent (once removed the query
 * matches nothing). System-level ({@code nemaki_conf}).
 */
public class Patch_SearchIndexReconcileV1Cleanup extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_SearchIndexReconcileV1Cleanup.class);
    private static final String PATCH_NAME = "SearchIndexReconcileV1Cleanup-20260724";

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
            log.error("[patch=" + PATCH_NAME + "] could not obtain client: " + e.getMessage());
            return;
        }
        if (client == null) {
            log.error("[patch=" + PATCH_NAME + "] no client for " + SystemConst.NEMAKI_CONF_DB);
            return;
        }
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();

        int deleted = 0, kept = 0;
        try {
            FindResult r = cloudant.postFind(new PostFindOptions.Builder()
                    .db(db)
                    .selector(Map.of("type", SearchIndexAclReindexTask.DOC_TYPE))
                    .limit(10000)
                    .build()).execute().getResult();
            List<Document> docs = r.getDocs();
            if (docs != null) {
                for (Document doc : docs) {
                    String id = doc.getId();
                    if (id != null && id.startsWith(SearchIndexAclReindexTask.ID_PREFIX)) {
                        kept++;
                        continue; // new deterministic-id document — keep
                    }
                    try {
                        cloudant.deleteDocument(new DeleteDocumentOptions.Builder()
                                .db(db).docId(id).rev(doc.getRev()).build()).execute();
                        deleted++;
                    } catch (Exception e) {
                        log.warn("[patch=" + PATCH_NAME + "] failed to delete old doc " + id + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + "] scan failed: " + e.getMessage());
            throw new RuntimeException("Patch_SearchIndexReconcileV1Cleanup failed to scan: " + e.getMessage(), e);
        }
        log.info("[patch=" + PATCH_NAME + "] complete — deleted " + deleted
                + " first-generation doc(s), kept " + kept + " current doc(s)");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        // nemaki_conf is system-wide; no per-repository work
    }
}
