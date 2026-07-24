package jp.aegif.nemaki.patch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.IndexDefinition;
import com.ibm.cloud.cloudant.v1.model.IndexField;
import com.ibm.cloud.cloudant.v1.model.PostIndexOptions;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.epoch.AclEpochState;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;

/**
 * Registers a Mango {@code (aclEpochState)} index on each per-repository content database
 * so the epoch-outbox scanner ({@link jp.aegif.nemaki.epoch.AclEpochFinalizationService})
 * can {@code _find} the (few) documents carrying an epoch state without scanning the whole
 * collection — and, crucially, so a state-less (normal) document is NEVER selected.
 *
 * <p>Design {@code docs/design/acl-epoch-fencing.md} §3 sketched a {@code (type,
 * aclEpochState)} index, but the epoch state spans MULTIPLE content types (documents,
 * folders, …) and the scanner selects purely by state ({@code aclEpochState $in
 * [PENDING_EPOCH, FINALIZED_NEEDS_RECONCILE]}); a state-first single-field index serves
 * that {@code $in} directly and excludes state-less documents, whereas a type-first
 * compound index would require enumerating types. Hence {@code (aclEpochState)}.
 *
 * <p>Per repository (epoch state lives on content docs, like {@link Patch_ApiKeyMangoIndex}).
 * Idempotent (Cloudant {@code postIndex} returns {@code result="exists"}; PatchHistory
 * dedupes). A registration failure throws so PatchHistory is not recorded and the next
 * startup retries.
 *
 * <p><b>Increment-2 fail-closed staging:</b> this only adds an index; nothing reads it in
 * production until the scanner is explicitly driven (it is NOT auto-started).
 */
public class Patch_AclEpochStateMangoIndex extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_AclEpochStateMangoIndex.class);
    private static final String PATCH_NAME = "AclEpochStateMangoIndex-20260724";
    private static final String INDEX_NAME = "idx_aclEpochState";
    private static final String DDOC_NAME = "acl-epoch-indexes";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        // No system-wide (nemaki_conf) work — epoch state lives on content documents.
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
            throw new RuntimeException("Patch_AclEpochStateMangoIndex: client unavailable for " + repositoryId, e);
        }
        if (client == null) {
            throw new RuntimeException("Patch_AclEpochStateMangoIndex: no client for repo " + repositoryId);
        }

        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        try {
            IndexDefinition def = new IndexDefinition.Builder()
                    .fields(List.of(new IndexField.Builder().add(AclEpochState.FIELD_STATE, "asc").build()))
                    .build();
            PostIndexOptions opts = new PostIndexOptions.Builder()
                    .db(db)
                    .index(def)
                    .name(INDEX_NAME)
                    .type(PostIndexOptions.Type.JSON)
                    .ddoc(DDOC_NAME)
                    .build();
            cloudant.postIndex(opts).execute().getResult();
            log.info("[patch=" + PATCH_NAME + "] registered (aclEpochState) Mango index on '" + db + "'");
        } catch (Exception e) {
            log.warn("[patch=" + PATCH_NAME + "] failed to register (aclEpochState) index on '"
                    + db + "': " + e.getMessage());
            throw new RuntimeException("Patch_AclEpochStateMangoIndex: index registration failed for "
                    + db, e);
        }
    }
}
