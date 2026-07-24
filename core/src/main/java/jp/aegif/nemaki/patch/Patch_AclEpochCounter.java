package jp.aegif.nemaki.patch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.IndexDefinition;
import com.ibm.cloud.cloudant.v1.model.IndexField;
import com.ibm.cloud.cloudant.v1.model.PostIndexOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.service.exception.ConflictException;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.epoch.AclEpochCounterService;
import jp.aegif.nemaki.util.constant.SystemConst;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds the repository-wide ACL-epoch counter (design
 * {@code docs/design/acl-epoch-fencing.md} §2.1 / §8 — SIGNED OFF 2026-07-24,
 * increment 1) and registers its lookup Mango index.
 *
 * <ul>
 *   <li><b>Per repository</b>: create {@code acl-epoch-counter::{repo}} at value
 *       {@link AclEpochCounterService#SEED_VALUE} (0) IF ABSENT. It is NEVER
 *       overwritten — re-running the patch must not roll a live counter back below
 *       already-issued epochs (the whole point of a persisted high-watermark). The
 *       first {@link AclEpochCounterService#allocate} then returns 1, the
 *       fresh-repository baseline.</li>
 *   <li><b>System</b>: a {@code (type)} Mango index on {@code nemaki_conf} so a
 *       recovery / enumeration {@code _find {type:"aclEpochCounter"}} avoids a full
 *       scan (the counters are otherwise addressed by deterministic {@code _id}).</li>
 * </ul>
 *
 * <p>Idempotent: an existing counter is left untouched; {@code postIndex} returns
 * {@code result="exists"} for an identical index; PatchHistory dedupes per repository.
 *
 * <p><b>Fail-closed staging:</b> this only seeds inert data — no writer allocates from
 * the counter until the later ACL-UPDATE increment, so shipping it changes no ACL
 * behaviour. The {@code (type, aclEpochState)} scanner index on CONTENT DBs belongs to
 * the subsequent outbox-finalization increment, not here.
 */
public class Patch_AclEpochCounter extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_AclEpochCounter.class);
    private static final String PATCH_NAME = "AclEpochCounter-20260724";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        CloudantClientWrapper client = confClient();
        if (client == null) {
            return; // logged in confClient()
        }
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        try {
            IndexDefinition def = new IndexDefinition.Builder()
                    .fields(List.of(new IndexField.Builder().add("type", "asc").build()))
                    .build();
            PostIndexOptions opts = new PostIndexOptions.Builder()
                    .db(db).index(def).name("idx_type_aclEpochCounter")
                    .type(PostIndexOptions.Type.JSON)
                    .ddoc("acl-epoch-indexes")
                    .build();
            cloudant.postIndex(opts).execute();
            log.info("[patch=" + PATCH_NAME + "] (type) Mango index ensured on '" + db + "'");
        } catch (Exception e) {
            // A missing lookup index is not fatal (counters are _id-addressed); the
            // recovery/enumeration scan just degrades. Do NOT fail the patch on it.
            log.warn("[patch=" + PATCH_NAME + "] could not register (type) index: " + e.getMessage());
        }
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        CloudantClientWrapper client = confClient();
        if (client == null) {
            throw new IllegalStateException("nemaki_conf client unavailable for " + PATCH_NAME);
        }
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        String docId = AclEpochCounterService.counterDocId(repositoryId);

        // Present? Leave it exactly as-is — re-seeding would roll the high-watermark back.
        try {
            Document existing = cloudant.getDocument(new GetDocumentOptions.Builder()
                    .db(db).docId(docId).build()).execute().getResult();
            if (existing != null) {
                log.info("[patch=" + PATCH_NAME + "] counter already present for '" + repositoryId
                        + "' (value preserved)");
                return;
            }
        } catch (NotFoundException nfe) {
            // fall through to create
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", AclEpochCounterService.DOC_TYPE);
        props.put("value", AclEpochCounterService.SEED_VALUE);
        Document doc = new Document();
        doc.setId(docId);
        doc.setProperties(props);
        try {
            cloudant.putDocument(new PutDocumentOptions.Builder()
                    .db(db).docId(docId).document(doc).build()).execute();
            log.info("[patch=" + PATCH_NAME + "] seeded counter for '" + repositoryId
                    + "' at value " + AclEpochCounterService.SEED_VALUE);
        } catch (ConflictException ce) {
            // A concurrent create won — the counter now exists, which is the goal.
            log.info("[patch=" + PATCH_NAME + "] counter concurrently created for '" + repositoryId + "'");
        }
    }

    private CloudantClientWrapper confClient() {
        if (patchUtil == null || patchUtil.getConnectorPool() == null) {
            log.warn("[patch=" + PATCH_NAME + "] connectorPool unavailable — skipping");
            return null;
        }
        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(SystemConst.NEMAKI_CONF_DB);
            if (client == null) {
                log.error("[patch=" + PATCH_NAME + "] no client for " + SystemConst.NEMAKI_CONF_DB);
            }
            return client;
        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + "] could not obtain nemaki_conf client: " + e.getMessage());
            return null;
        }
    }
}
