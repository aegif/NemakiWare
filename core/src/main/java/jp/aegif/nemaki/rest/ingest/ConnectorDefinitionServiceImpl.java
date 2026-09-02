package jp.aegif.nemaki.rest.ingest;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jp.aegif.nemaki.config.ObjectMapperFactory;

public class ConnectorDefinitionServiceImpl implements ConnectorDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectorDefinitionServiceImpl.class);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private CloudantClientPool connectorPool;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    @Override
    public ConnectorDefinition create(ConnectorDefinition def) {
        validateRequiredFields(def);
        if (exists(def.getConnectorId())) {
            throw new IllegalStateException("Connector already exists: " + def.getConnectorId());
        }
        String now = Instant.now().toString();
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        upsertDocument(def, true);
        logger.info("Created connector definition: {}", def.getConnectorId());
        return def;
    }

    @Override
    public ConnectorDefinition get(String connectorId) {
        // Null means "no such connector", not a crash: Map.of rejects null values with an NPE,
        // so an ingest request that simply omits connectorId used to answer 500 with a stack
        // trace, while a WRONG id answered a clean 404. Callers already treat null as
        // not-found. findBySystemAndArchetype below has guarded this way all along.
        if (connectorId == null) return null;
        List<ConnectorDefinition> results = findBySelector(Map.of(
                "type", ConnectorDefinition.DOC_TYPE,
                "connectorId", connectorId));
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<ConnectorDefinition> list() {
        return findBySelector(Map.of("type", ConnectorDefinition.DOC_TYPE));
    }

    @Override
    public List<ConnectorDefinition> listByArchetype(SourceArchetype archetype) {
        return findBySelector(Map.of(
                "type", ConnectorDefinition.DOC_TYPE,
                "sourceArchetype", archetype.name()));
    }

    @Override
    public ConnectorDefinition update(ConnectorDefinition def) {
        validateRequiredFields(def);
        def.setUpdatedAt(Instant.now().toString());
        upsertDocument(def, false);
        logger.info("Updated connector definition: {}", def.getConnectorId());
        return def;
    }

    @Override
    public void delete(String connectorId) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        List<com.ibm.cloud.cloudant.v1.model.Document> docs = findRawDocs(cloudant, dbName,
                Map.of("type", ConnectorDefinition.DOC_TYPE, "connectorId", connectorId));
        for (com.ibm.cloud.cloudant.v1.model.Document doc : docs) {
            cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                    .db(dbName).docId(doc.getId()).rev(doc.getRev()).build()).execute();
        }
        logger.info("Deleted connector definition: {}", connectorId);
    }

    @Override
    public boolean exists(String connectorId) {
        return get(connectorId) != null;
    }

    @Override
    public ConnectorDefinition findBySystemAndArchetype(String sourceSystem, SourceArchetype archetype) {
        if (sourceSystem == null || archetype == null) return null;
        List<ConnectorDefinition> candidates = findBySelector(Map.of(
                "type", ConnectorDefinition.DOC_TYPE,
                "sourceSystem", sourceSystem,
                "sourceArchetype", archetype.name(),
                "enabled", true));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static final int MAX_ID_LENGTH = 255;
    private static final int MAX_NAME_LENGTH = 1024;
    private static final int MAX_URL_LENGTH = 2048;

    private void validateRequiredFields(ConnectorDefinition def) {
        if (def.getConnectorId() == null || def.getConnectorId().isBlank()) {
            throw new IllegalArgumentException("connectorId is required");
        }
        if (def.getConnectorId().length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("connectorId exceeds max length of " + MAX_ID_LENGTH);
        }
        if (def.getDisplayName() != null && def.getDisplayName().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName exceeds max length of " + MAX_NAME_LENGTH);
        }
        if (def.getEndpoint() != null && def.getEndpoint().length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("endpoint URL exceeds max length of " + MAX_URL_LENGTH);
        }
        // SSRF prevention: reject private/loopback endpoint URLs
        if (def.getEndpoint() != null && !def.getEndpoint().isBlank()) {
            try {
                AdapterHttpClient.validateExternalUrl(def.getEndpoint());
            } catch (SecurityException se) {
                throw new IllegalArgumentException("endpoint: " + se.getMessage());
            }
        }
        if (def.getSourceArchetype() == null) {
            throw new IllegalArgumentException("sourceArchetype is required. Valid values: "
                    + java.util.Arrays.toString(SourceArchetype.values()));
        }
        if (def.getSourceSystem() == null || def.getSourceSystem().isBlank()) {
            throw new IllegalArgumentException("sourceSystem is required. Valid values: "
                    + String.join(", ", AdapterRegistry.allSourceSystems()));
        }
        // Normalize legacy short aliases before registry validation
        String system = def.getSourceSystem();
        if ("google".equals(system)) { def.setSourceSystem("google_drive"); system = "google_drive"; }
        else if ("microsoft".equals(system)) { def.setSourceSystem("onedrive"); system = "onedrive"; }
        // Validate sourceSystem against adapter registry — reject unknown values
        if (!AdapterRegistry.isRegistered(system)) {
            throw new IllegalArgumentException("Unknown sourceSystem '" + def.getSourceSystem()
                    + "'. Valid values: " + String.join(", ", AdapterRegistry.allSourceSystems()));
        }
        AdapterDescriptor desc = AdapterRegistry.get(def.getSourceSystem());
        if (desc.archetype() != def.getSourceArchetype()) {
            throw new IllegalArgumentException("sourceSystem '" + def.getSourceSystem()
                    + "' expects archetype " + desc.archetype()
                    + " but connector declares " + def.getSourceArchetype());
        }
        validateDelegationFields(def);
    }

    /**
     * Delegation invariants (enforced even though admin sets them, to keep
     * the data shape consistent and to surface configuration mistakes early):
     *
     * <ul>
     *   <li>If {@code delegated=false}, the scope fields must be empty —
     *       leaving stale folder/principal IDs around when delegation is
     *       toggled off would be confusing on a later re-enable.</li>
     *   <li>If {@code delegated=true} AND {@code delegateAllFolders=false},
     *       {@code allowedFolderIds} must be non-empty. Empty means
     *       "no delegation" by design (we deliberately don't treat empty
     *       as "all folders" — that would turn an admin oversight into a
     *       silent broad credential delegation).</li>
     *   <li>If {@code delegated=false} AND {@code delegateAllFolders=true},
     *       reject — the combination is meaningless and likely a typo.</li>
     * </ul>
     */
    private void validateDelegationFields(ConnectorDefinition def) {
        boolean hasFolderScope = def.getAllowedFolderIds() != null && !def.getAllowedFolderIds().isEmpty();
        boolean hasPrincipalScope = def.getAllowedPrincipalIds() != null && !def.getAllowedPrincipalIds().isEmpty();

        if (!def.isDelegated()) {
            if (def.isDelegateAllFolders() || hasFolderScope || hasPrincipalScope) {
                throw new IllegalArgumentException(
                        "delegateAllFolders / allowedFolderIds / allowedPrincipalIds may only be set when delegated=true");
            }
            return;
        }

        if (!def.isDelegateAllFolders() && !hasFolderScope) {
            throw new IllegalArgumentException(
                    "delegated=true requires either delegateAllFolders=true or a non-empty allowedFolderIds list. "
                            + "Empty allowedFolderIds is treated as 'no delegation' and is not the safe default for repository-wide delegation");
        }
        if (def.isDelegateAllFolders() && hasFolderScope) {
            throw new IllegalArgumentException(
                    "delegateAllFolders=true and a non-empty allowedFolderIds list are mutually exclusive");
        }
        for (String fid : def.getAllowedFolderIds() != null ? def.getAllowedFolderIds() : List.<String>of()) {
            if (fid == null || fid.isBlank()) {
                throw new IllegalArgumentException("allowedFolderIds must not contain null/blank entries");
            }
        }
        for (String pid : def.getAllowedPrincipalIds() != null ? def.getAllowedPrincipalIds() : List.<String>of()) {
            if (pid == null || pid.isBlank()) {
                throw new IllegalArgumentException("allowedPrincipalIds must not contain null/blank entries");
            }
        }
    }

    // --- Internal ---

    @SuppressWarnings("unchecked")
    private void upsertDocument(ConnectorDefinition def, boolean creating) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        Map<String, Object> jsonMap = MAPPER.convertValue(def, Map.class);
        jsonMap.put("type", ConnectorDefinition.DOC_TYPE);

        Document doc = new Document();
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            doc.put(entry.getKey(), entry.getValue());
        }

        // Find existing document for _id and _rev
        List<com.ibm.cloud.cloudant.v1.model.Document> existing = findRawDocs(cloudant, dbName,
                Map.of("type", ConnectorDefinition.DOC_TYPE, "connectorId", def.getConnectorId()));
        com.ibm.cloud.cloudant.v1.model.Document deterministic = existing.isEmpty()
                ? readByDeterministicId(cloudant, dbName, def.getConnectorId())
                : null;
        if (!existing.isEmpty()) {
            doc.setId(existing.get(0).getId());
            doc.setRev(existing.get(0).getRev());
        } else if (deterministic != null) {
            // The selector said "no such connector" and an ID-ADDRESSED read says otherwise.
            // A Mango index being rebuilt answers empty, and writing on the strength of it
            // is what produces a second definition. An id-addressed read needs no index.
            //
            // What to do about it depends on what was asked, and the first version of this
            // refused both the same way. A CREATE must still be refused — the connector is
            // there, and overwriting it is not what "create" means. An UPDATE must NOT be:
            // the id-addressed read conclusively found the row the administrator meant and
            // handed back its _id AND _rev, which is everything a conflict-safe write needs.
            // Refusing it turned a perfectly ordinary PUT into a 500 whenever the index
            // happened to be rebuilding — over-throwing on evidence that was conclusive.
            if (creating) {
                throw new IllegalStateException("Connector already exists: "
                        + def.getConnectorId() + " (found by an id-addressed read; the index"
                        + " did not report it, so the duplicate check before this one passed)");
            }
            // An UPDATE is refused here too, and the round that changed this to "adopt the
            // row, it carries _id and _rev" was wrong in a way worth writing down.
            //
            // _id and _rev make the write safe against a CONCURRENT writer. They say nothing
            // about whether the PAYLOAD is complete — and it is not. The controller rebuilds
            // the masked secrets and the omitted delegation arrays from
            // connectorDefinitionService.get(), which is answered by the SAME Mango selector
            // that just missed. So on exactly this path the request arriving here carries the
            // literal string "[configured]" where a credential belongs and nulls where the
            // scope arrays belong. Adopting the row would write that over the real
            // configuration: the refusal was not over-throwing, it was the only thing
            // standing between a rebuilding index and a destroyed connector.
            //
            // What WAS a real defect is that this reached the client as a 500. It is a
            // transient, retryable condition and now says so.
            throw new ConnectorIndexNotReadyException("connector " + def.getConnectorId()
                    + " exists under its deterministic id but the index did not report it."
                    + " The update is refused rather than applied because the request was"
                    + " assembled against that same index — masked secrets and omitted"
                    + " scope lists could not be restored from it. Retry once the index has"
                    + " caught up.");
        } else {
            // A DETERMINISTIC id for anything created from here on.
            //
            // Existence is decided by a Mango selector, and a selector whose index is being
            // rebuilt answers "no such connector" — after which this method used to save
            // under a CouchDB-generated id, so a second document appeared with nothing able
            // to reject it. Patch_DefaultCloudDriveConnectorProfile does exactly that
            // sequence at startup. The patch gate added for it probes each repository's
            // _repo views, which say nothing about THIS database's index; a review pointed
            // that out. Deriving the id from the connectorId closes it where it actually
            // happens: the second write is a 409, not a duplicate.
            //
            // Documents created before this keep their GENERATED ids. They are found by the
            // selector above when it answers, and the id-addressed check just above cannot
            // see them — so a legacy document plus a stale selector can still produce a
            // second definition, once, after which both are deterministic-id protected. A
            // review pointed out that the first version of this comment said "nothing needs
            // migrating", which was the stronger claim: what is true is that nothing needs
            // migrating FOR THE NEW PATH, and a legacy row stays exposed until it is
            // rewritten (an update through this method rewrites it under its own id, not the
            // deterministic one — closing that is a migration, and it is not done here).
            doc.setId(ConnectorDefinition.DOC_TYPE + ":" + def.getConnectorId());
        }

        PostDocumentOptions options = new PostDocumentOptions.Builder()
                .db(dbName).document(doc).build();
        DocumentResult result = cloudant.postDocument(options).execute().getResult();
        if (!result.isOk()) {
            throw new IllegalStateException("Failed to save connector " + def.getConnectorId() + ": " + result.getError());
        }
    }

    /**
     * The store holds the connector but the index has not caught up, so the request cannot be
     * completed SAFELY — not that anything is wrong with it. Separate from
     * {@link IllegalStateException} so the controller can answer 503 rather than 500: a
     * caller that retries succeeds, and one that reads 500 opens a ticket.
     */
    public static class ConnectorIndexNotReadyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConnectorIndexNotReadyException(String message) {
            super(message);
        }
    }

    /**
     * An id-addressed read of the deterministic document id. Needs no index, so it answers
     * while a Mango index is being rebuilt — which is the window this class has to survive.
     */
    private com.ibm.cloud.cloudant.v1.model.Document readByDeterministicId(
            com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName, String connectorId) {
        try {
            return cloudant.getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions
                    .Builder().db(dbName)
                    .docId(ConnectorDefinition.DOC_TYPE + ":" + connectorId).build())
                    .execute().getResult();
        } catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<ConnectorDefinition> findBySelector(Map<String, Object> selector) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        List<com.ibm.cloud.cloudant.v1.model.Document> rawDocs = findRawDocs(cloudant, dbName, selector);
        List<ConnectorDefinition> results = new ArrayList<>();
        for (com.ibm.cloud.cloudant.v1.model.Document rawDoc : rawDocs) {
            try {
                Map<String, Object> props = new HashMap<>(rawDoc.getProperties());
                props.remove("_id");
                props.remove("_rev");
                props.remove("type");
                results.add(MAPPER.convertValue(props, ConnectorDefinition.class));
            } catch (Exception e) {
                logger.warn("Failed to deserialize connector definition: {}", e.getMessage());
            }
        }
        return results;
    }

    private List<com.ibm.cloud.cloudant.v1.model.Document> findRawDocs(
            com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName, Map<String, Object> selector) {
        PostFindOptions findOptions = new PostFindOptions.Builder()
                .db(dbName).selector(selector).limit(200).build();
        FindResult findResult = cloudant.postFind(findOptions).execute().getResult();
        List<com.ibm.cloud.cloudant.v1.model.Document> docs = findResult.getDocs();
        return docs != null ? docs : List.of();
    }

    private CloudantClientWrapper getConfClient() {
        CloudantClientWrapper client = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
        if (client == null) {
            throw new IllegalStateException("nemaki_conf database client not available");
        }
        return client;
    }
}
