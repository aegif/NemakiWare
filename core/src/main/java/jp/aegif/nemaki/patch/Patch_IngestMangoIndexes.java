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
 *       covered {@code findBySystemAndArchetype} until that resolution moved to an
 *       index-free walk (the selector could not answer while its own index rebuilt). Kept
 *       for ad-hoc queries and for the admin listings, which are still selector-backed.</li>
 *   <li>{@code idx_type_profileId} → {@code (type, profileId)} —
 *       covers {@code ImportProfileDefinitionServiceImpl#get} and
 *       {@code upsertDocument}'s _id/_rev probe.</li>
 *   <li>{@code idx_type_repositoryId} → {@code (type, repositoryId)} —
 *       covers {@code listByRepository} for both profiles and job
 *       records.</li>
 *   <li>{@code idx_type_jobId} → {@code (type, jobId)} — covers
 *       {@code IngestJobService} record lookups.</li>
 *   <li>{@code idx_type_dlqId} → {@code (type, dlqId)} — covers
 *       dead-letter retry lookups.</li>
 *   <li>{@code idx_type_dlqId_id} → {@code (type, dlqId, _id)} — orders
 *       the dead-letter LISTING. The pair alone is not a total order:
 *       twin rows share a dlqId, so an offset page could repeat one and
 *       pass over the other (R13).</li>
 * </ul>
 *
 * <p><b>RC4.1 (F2)</b>: the previous spelling
 * {@code idx_type_dlqEntryId} was based on a guess at the field
 * name; the actual selectors at {@code IngestJobService:176}
 * (loadDlqContent), {@code 234} (getDlqEntry), {@code 278}
 * (deleteDlqEntry), {@code 300} (upsert key-match) all use
 * {@code dlqId}. The index was created without error but matched
 * no real selector — Cloudant fell back to {@code _all_docs}
 * scan for DLQ lookups. Existing deployments will get the
 * correctly-named index on next boot; the obsolete
 * {@code idx_type_dlqEntryId} index can be removed manually via
 * {@code DELETE /nemaki_conf/_index/ingest-indexes/json/idx_type_dlqEntryId}
 * (we deliberately don't auto-delete to avoid touching state we
 * didn't create with the current patch instance).
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
            // RC4.1 (F2): renamed from idx_type_dlqEntryId — the actual
            // selector field in IngestJobService is dlqId. Operators
            // upgrading from RC4 will get this new index; the old dead
            // one stays put until removed manually (see class javadoc).
            new IndexSpec("idx_type_dlqId", "type", "dlqId"),
            // R13: the dead-letter LISTING's order, not a lookup. (type, dlqId) alone is not a
            // total order over this collection — the same dlqId can be stored twice (the twin
            // rows deleteDlqEntry calls a recorded residual, from before the deterministic _id
            // of R23), and two rows with equal sort keys have no defined order between them, so
            // an offset page can repeat one and pass over the other. _id breaks the tie and is
            // unique by definition. Measured on CouchDB 3.3.3: without this index the three-key
            // sort is refused (400 no_usable_index) and the listing says the page is unordered;
            // with it, four twin pairs page with no repeat and no row missed.
            new IndexSpec("idx_type_dlqId_id", "type", "dlqId", "_id")
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

        // RC4.1 (F3): collapse the created/exists counters into a single
        // "processed" tally. The SDK returns "created" on both the first
        // call and on idempotent re-registrations against newer Cloudant
        // builds, so distinguishing them in the summary log was
        // unreliable. Failure detection (the only counter that drives a
        // RuntimeException) stays exactly as before.
        int processed = 0, failed = 0;
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
                processed++;
                if (log.isDebugEnabled()) {
                    String resultStr = result != null ? result.getResult() : "null";
                    log.debug("[patch=" + PATCH_NAME + "] index '" + spec.name()
                            + "' result=" + resultStr);
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
        log.info("[patch=" + PATCH_NAME + "] complete — processed=" + processed
                + ", failed=" + failed + " (out of " + INDEXES.size() + ")");
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
