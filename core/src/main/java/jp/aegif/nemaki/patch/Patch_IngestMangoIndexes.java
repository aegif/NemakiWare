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
 * Registers Cloudant / CouchDB Mango indexes for the External Ingestion
 * record types stored in {@code nemaki_conf}. Without these indexes,
 * every {@code _find} query in
 * {@link jp.aegif.nemaki.rest.ingest.ConnectorDefinitionServiceImpl} /
 * {@link jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionServiceImpl} /
 * {@link jp.aegif.nemaki.rest.ingest.IngestJobService} falls back to a
 * full {@code _all_docs} scan. That's fine at typical scale (10-50
 * connectors + 50-200 profiles) but pathological at 10k+.
 *
 * <p>The patch is idempotent: Cloudant's {@code postIndex} returns
 * {@code result="exists"} if an identically-named index with the same
 * field set is already in place. PatchHistory dedupes across
 * deployments.
 *
 * <p>Indexes registered (all on {@code nemaki_conf}):
 *
 * <ul>
 *   <li>{@code idx_type_connectorId} → {@code (type, connectorId)} —
 *       covers {@code ConnectorDefinitionServiceImpl#get} and
 *       {@code upsertDocument}'s _id/_rev probe.</li>
 *   <li>{@code idx_type_sourceArchetype} → {@code (type, sourceArchetype)} —
 *       covers {@code listByArchetype}.</li>
 *   <li>{@code idx_type_sourceSystem_archetype_enabled} →
 *       {@code (type, sourceSystem, sourceArchetype, enabled)} —
 *       covers {@code findBySystemAndArchetype}.</li>
 *   <li>{@code idx_type_profileId} → {@code (type, profileId)} —
 *       covers {@code ImportProfileDefinitionServiceImpl#get} and
 *       {@code upsertDocument}'s _id/_rev probe.</li>
 *   <li>{@code idx_type_repositoryId} → {@code (type, repositoryId)} —
 *       covers {@code listByRepository} for both profiles and job
 *       records.</li>
 *   <li>{@code idx_type_jobId} → {@code (type, jobId)} — covers
 *       {@code IngestJobService} record lookups.</li>
 *   <li>{@code idx_type_dlqEntryId} → {@code (type, dlqEntryId)} —
 *       covers dead-letter retry lookups.</li>
 * </ul>
 *
 * <p>Operates on {@code nemaki_conf} only (no per-repository state).
 * The patch is a system-level operation so it runs in
 * {@link #applySystemPatch()} rather than the per-repo hook.
 */
public class Patch_IngestMangoIndexes extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_IngestMangoIndexes.class);
    private static final String PATCH_NAME = "IngestMangoIndexes-20260518";

    /** A single index definition the patch should register. */
    private record IndexSpec(String name, List<String> fields) {
        IndexSpec(String name, String... fields) {
            this(name, Arrays.asList(fields));
        }
    }

    /**
     * Indexes to register. Ordered by the use frequency we see in
     * production (single-key lookups first, multi-key last).
     */
    private static final List<IndexSpec> INDEXES = List.of(
            new IndexSpec("idx_type_connectorId", "type", "connectorId"),
            new IndexSpec("idx_type_sourceArchetype", "type", "sourceArchetype"),
            new IndexSpec("idx_type_sourceSystem_archetype_enabled",
                    "type", "sourceSystem", "sourceArchetype", "enabled"),
            new IndexSpec("idx_type_profileId", "type", "profileId"),
            new IndexSpec("idx_type_repositoryId", "type", "repositoryId"),
            new IndexSpec("idx_type_jobId", "type", "jobId"),
            new IndexSpec("idx_type_dlqEntryId", "type", "dlqEntryId")
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

        int created = 0, existed = 0, failed = 0;
        for (IndexSpec spec : INDEXES) {
            try {
                IndexDefinition def = buildDefinition(spec.fields());
                PostIndexOptions opts = new PostIndexOptions.Builder()
                        .db(db)
                        .index(def)
                        .name(spec.name())
                        .type(PostIndexOptions.Type.JSON)
                        .ddoc("ingest-indexes")
                        .build();
                IndexResult result = cloudant.postIndex(opts).execute().getResult();
                String resultStr = result != null ? result.getResult() : null;
                if ("exists".equalsIgnoreCase(resultStr)) {
                    existed++;
                    if (log.isDebugEnabled()) {
                        log.debug("[patch=" + PATCH_NAME + "] index '" + spec.name() + "' already exists");
                    }
                } else {
                    created++;
                    log.info("[patch=" + PATCH_NAME + "] created index '" + spec.name()
                            + "' on fields " + spec.fields());
                }
            } catch (Exception e) {
                failed++;
                // Cloudant tends to return a 4xx with a body like
                // "an index already exists with this name" when an index
                // of the same name + different fields exists. Don't
                // crash the patch — log loudly and continue.
                log.warn("[patch=" + PATCH_NAME + "] failed to register index '"
                        + spec.name() + "': " + e.getMessage());
            }
        }
        log.info("[patch=" + PATCH_NAME + "] complete — created=" + created
                + ", existing=" + existed + ", failed=" + failed);
        if (failed > 0) {
            // Surface as a patch failure so PatchHistory does NOT mark
            // it applied; next startup will retry the failed entries.
            throw new RuntimeException("Patch_IngestMangoIndexes: " + failed
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
            indexFields.add(new IndexField.Builder()
                    .add(f, "asc")
                    .build());
        }
        return new IndexDefinition.Builder()
                .fields(indexFields)
                .build();
    }
}
