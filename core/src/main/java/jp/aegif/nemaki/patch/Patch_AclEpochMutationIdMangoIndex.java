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
 * Registers a Mango {@code (aclEpochMutationId)} index on each per-repository content database
 * (review 3b [P1]).
 *
 * <p>Every OTHER scanner selector keys on {@code aclEpochState}, so a document that LOST its state
 * while keeping its {@code aclEpochMutationId} — e.g. a move whose marker was dropped — is
 * invisible to all of them, yet its {@code aclSourceEpoch} would be consumed as "settled" by the
 * effective-epoch walk and could fence out later correct writers indefinitely. The scanner has a
 * dedicated pass for that shape ({@code aclEpochMutationId $exists AND aclEpochState NOT $exists}),
 * and a {@code $exists:false} condition cannot be served by the {@code (aclEpochState)} index (a
 * JSON index contains only documents that HAVE the indexed field). This index does the opposite:
 * it contains only MUTATION-BEARING documents — empty in steady state, a handful mid-mutation — so
 * the pass is cheap and index-served.
 *
 * <p>Separate patch rather than an addition to {@link Patch_AclEpochStateMangoIndex}: that patch
 * has already been recorded as applied in existing deployments, so extending it would never run.
 *
 * <p>Per repository. Idempotent (Cloudant {@code postIndex} returns {@code result="exists"};
 * PatchHistory dedupes). A registration failure throws so PatchHistory is not recorded and the next
 * startup retries.
 *
 * <p><b>Fail-closed staging:</b> this only adds an index; nothing reads it in production until the
 * scanner is explicitly driven (it is NOT auto-started).
 */
public class Patch_AclEpochMutationIdMangoIndex extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_AclEpochMutationIdMangoIndex.class);
    private static final String PATCH_NAME = "AclEpochMutationIdMangoIndex-20260725";
    private static final String INDEX_NAME = "idx_aclEpochMutationId";
    private static final String DDOC_NAME = "acl-epoch-indexes";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        // No system-wide (nemaki_conf) work — epoch fields live on content documents.
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        if (patchUtil == null || patchUtil.getConnectorPool() == null) {
            // THROW (not return): returning would let AbstractNemakiPatch record PatchHistory as
            // applied, so the index would never be created on a later, healthy startup.
            throw new RuntimeException("Patch_AclEpochMutationIdMangoIndex: connectorPool unavailable for repo "
                    + repositoryId);
        }
        CloudantClientWrapper client;
        try {
            client = patchUtil.getConnectorPool().getClient(repositoryId);
        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + "] could not obtain client for repo "
                    + repositoryId + ": " + e.getMessage());
            throw new RuntimeException("Patch_AclEpochMutationIdMangoIndex: client unavailable for "
                    + repositoryId, e);
        }
        if (client == null) {
            throw new RuntimeException("Patch_AclEpochMutationIdMangoIndex: no client for repo " + repositoryId);
        }

        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        try {
            IndexDefinition def = new IndexDefinition.Builder()
                    .fields(List.of(new IndexField.Builder()
                            .add(AclEpochState.FIELD_MUTATION_ID, "asc").build()))
                    .build();
            PostIndexOptions opts = new PostIndexOptions.Builder()
                    .db(db)
                    .index(def)
                    .name(INDEX_NAME)
                    .type(PostIndexOptions.Type.JSON)
                    .ddoc(DDOC_NAME)
                    .build();
            cloudant.postIndex(opts).execute().getResult();
            log.info("[patch=" + PATCH_NAME + "] registered (aclEpochMutationId) Mango index on '" + db + "'");
        } catch (Exception e) {
            log.warn("[patch=" + PATCH_NAME + "] failed to register (aclEpochMutationId) index on '"
                    + db + "': " + e.getMessage());
            throw new RuntimeException("Patch_AclEpochMutationIdMangoIndex: index registration failed for "
                    + db, e);
        }
    }
}
