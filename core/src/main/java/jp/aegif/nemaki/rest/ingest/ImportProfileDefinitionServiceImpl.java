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

public class ImportProfileDefinitionServiceImpl implements ImportProfileDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(ImportProfileDefinitionServiceImpl.class);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private CloudantClientPool connectorPool;
    private ConnectorDefinitionService connectorDefinitionService;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    public void setConnectorDefinitionService(ConnectorDefinitionService connectorDefinitionService) {
        this.connectorDefinitionService = connectorDefinitionService;
    }

    @Override
    public ImportProfileDefinition create(ImportProfileDefinition def) {
        validateRequiredFields(def);
        if (exists(def.getProfileId())) {
            throw new IllegalStateException("Import profile already exists: " + def.getProfileId());
        }
        String now = Instant.now().toString();
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        upsertDocument(def);
        logger.info("Created import profile: {}", def.getProfileId());
        return def;
    }

    @Override
    public ImportProfileDefinition get(String profileId) {
        // Null means "no such profile", not a crash — see ConnectorDefinitionServiceImpl.get.
        if (profileId == null) return null;
        List<ImportProfileDefinition> results = findBySelector(Map.of(
                "type", ImportProfileDefinition.DOC_TYPE,
                "profileId", profileId));
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<ImportProfileDefinition> list() {
        return findBySelector(Map.of("type", ImportProfileDefinition.DOC_TYPE));
    }

    @Override
    public List<ImportProfileDefinition> listByRepository(String repositoryId) {
        return findBySelector(Map.of(
                "type", ImportProfileDefinition.DOC_TYPE,
                "repositoryId", repositoryId));
    }

    @Override
    public ImportProfileDefinition update(ImportProfileDefinition def) {
        validateRequiredFields(def);
        def.setUpdatedAt(Instant.now().toString());
        upsertDocument(def);
        logger.info("Updated import profile: {}", def.getProfileId());
        return def;
    }

    @Override
    public void delete(String profileId) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        List<com.ibm.cloud.cloudant.v1.model.Document> docs = findRawDocs(cloudant, dbName,
                Map.of("type", ImportProfileDefinition.DOC_TYPE, "profileId", profileId));
        for (com.ibm.cloud.cloudant.v1.model.Document doc : docs) {
            cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                    .db(dbName).docId(doc.getId()).rev(doc.getRev()).build()).execute();
        }
        logger.info("Deleted import profile: {}", profileId);
    }

    @Override
    public boolean exists(String profileId) {
        return get(profileId) != null;
    }

    @Override
    public ImportProfileDefinition findDefaultForRepository(String repositoryId, SourceArchetype archetype, String connectorId) {
        if (repositoryId == null) return null;
        List<ImportProfileDefinition> candidates = listByRepository(repositoryId);
        // First pass: prefer profiles where this connector is the explicit default
        List<ImportProfileDefinition> byConnector = candidates.stream()
                .filter(p -> p.isEnabled() && p.isArchetypeAllowed(archetype) && p.isConnectorAllowed(connectorId)
                        && connectorId != null && connectorId.equals(p.getDefaultConnectorId()))
                .toList();
        if (byConnector.size() == 1) return byConnector.get(0);
        if (byConnector.size() > 1) {
            throw new IllegalStateException("Ambiguous auto-resolve: " + byConnector.size()
                    + " profiles claim defaultConnectorId=" + connectorId + " in repository " + repositoryId
                    + " — set defaultConnectorId on exactly one profile");
        }

        // Second pass: prefer profiles marked as defaultProfile
        List<ImportProfileDefinition> byDefault = candidates.stream()
                .filter(p -> p.isEnabled() && p.isDefaultProfile()
                        && p.isArchetypeAllowed(archetype) && p.isConnectorAllowed(connectorId))
                .toList();
        if (byDefault.size() == 1) return byDefault.get(0);
        if (byDefault.size() > 1) {
            throw new IllegalStateException("Ambiguous auto-resolve: " + byDefault.size()
                    + " profiles have defaultProfile=true in repository " + repositoryId
                    + " — set defaultProfile on exactly one profile");
        }
        // Third pass: any compatible profile — require exactly one match for determinism
        List<ImportProfileDefinition> fallbacks = candidates.stream()
                .filter(p -> p.isEnabled() && p.isArchetypeAllowed(archetype) && p.isConnectorAllowed(connectorId))
                .toList();
        if (fallbacks.size() == 1) {
            return fallbacks.get(0);
        }
        if (fallbacks.size() > 1) {
            throw new IllegalStateException(
                    "Ambiguous auto-resolve: " + fallbacks.size() + " profiles ("
                    + fallbacks.stream().map(ImportProfileDefinition::getProfileId).toList()
                    + ") match connector " + connectorId + " in repository " + repositoryId
                    + " — set defaultProfile=true or defaultConnectorId on exactly one profile");
        }
        return null;
    }

    private static final int MAX_ID_LENGTH = 255;

    private void validateRequiredFields(ImportProfileDefinition def) {
        if (def.getProfileId() == null || def.getProfileId().isBlank()) {
            throw new IllegalArgumentException("profileId is required");
        }
        if (def.getProfileId().length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("profileId exceeds max length of " + MAX_ID_LENGTH);
        }
        if (def.getRepositoryId() == null || def.getRepositoryId().isBlank()) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        boolean hasFolderId = def.getTargetFolderId() != null && !def.getTargetFolderId().isBlank();
        boolean hasFolderPath = def.getTargetFolderPath() != null && !def.getTargetFolderPath().isBlank();
        if (!hasFolderId && !hasFolderPath) {
            throw new IllegalArgumentException("Either targetFolderId or targetFolderPath is required");
        }
        // Validate scheduler-enabled profiles have required source-scope and connector params.
        // Manual/import-only profiles (schedulerEnabled=false) are not validated for scope params
        // since they don't participate in scheduled fetches or webhook dispatch.
        if (def.isSchedulerEnabled()) {
            validateSchedulerParams(def);
        }
        // Validate auto-resolve uniqueness invariants at save time (only for enabled profiles)
        if (def.isEnabled()) {
            validateAutoResolveUniqueness(def);
        }
    }

    /**
     * Prevents saving profiles that would cause ambiguous auto-resolve at runtime.
     */
    private void validateAutoResolveUniqueness(ImportProfileDefinition def) {
        String repoId = def.getRepositoryId();
        if (repoId == null) return;
        List<ImportProfileDefinition> existing = listByRepository(repoId);

        // Check defaultConnectorId uniqueness
        if (def.getDefaultConnectorId() != null && !def.getDefaultConnectorId().isBlank()) {
            for (ImportProfileDefinition other : existing) {
                if (other.getProfileId().equals(def.getProfileId())) continue;
                if (def.getDefaultConnectorId().equals(other.getDefaultConnectorId())
                        && other.isEnabled()) {
                    throw new IllegalArgumentException(
                            "Profile '" + other.getProfileId() + "' already claims defaultConnectorId='"
                            + def.getDefaultConnectorId() + "' in repository '" + repoId
                            + "'. Only one enabled profile per defaultConnectorId is allowed.");
                }
            }
        }
        // Check defaultProfile uniqueness
        if (def.isDefaultProfile()) {
            for (ImportProfileDefinition other : existing) {
                if (other.getProfileId().equals(def.getProfileId())) continue;
                if (other.isDefaultProfile() && other.isEnabled()) {
                    throw new IllegalArgumentException(
                            "Profile '" + other.getProfileId() + "' already has defaultProfile=true in repository '"
                            + repoId + "'. Only one enabled default profile per repository is allowed.");
                }
            }
        }
    }

    /**
     * Validates that scheduler-enabled profiles reference a usable connector
     * and that adapter-specific required parameters are present in schedulerParams.
     */
    private void validateSchedulerParams(ImportProfileDefinition def) {
        String defaultConnectorId = def.getDefaultConnectorId();
        if (defaultConnectorId == null || defaultConnectorId.isBlank()) {
            throw new IllegalArgumentException(
                    "schedulerEnabled profiles require a defaultConnectorId to determine the fetch adapter");
        }
        if (connectorDefinitionService != null) {
            ConnectorDefinition connector = connectorDefinitionService.get(defaultConnectorId);
            if (connector == null) {
                throw new IllegalArgumentException(
                        "defaultConnectorId '" + defaultConnectorId + "' does not exist");
            }
            if (!connector.isEnabled()) {
                throw new IllegalArgumentException(
                        "defaultConnectorId '" + defaultConnectorId + "' is disabled");
            }
            if (!def.isConnectorAllowed(connector.getConnectorId())) {
                throw new IllegalArgumentException(
                        "defaultConnectorId '" + defaultConnectorId + "' is not in allowedConnectorIds");
            }
            if (!def.isArchetypeAllowed(connector.getSourceArchetype())) {
                throw new IllegalArgumentException(
                        "Connector archetype '" + connector.getSourceArchetype()
                                + "' is not in profile's allowedArchetypes");
            }
            // Adapter-specific required parameters
            validateAdapterRequiredParams(connector.getSourceSystem(), def.getSchedulerParams());
        }
    }

    /**
     * Validates adapter-specific required schedulerParams based on sourceSystem.
     * Only checks params that have no meaningful default — adapters like IMAP, Gmail,
     * M365, Notion, Salesforce work with built-in defaults.
     */
    /**
     * Validate adapter-specific required schedulerParams using the
     * {@link AdapterRegistry} as the single source of truth.
     */
    private void validateAdapterRequiredParams(String sourceSystem, Map<String, String> params) {
        if (sourceSystem == null) return;
        AdapterDescriptor descriptor = AdapterRegistry.get(sourceSystem);
        if (descriptor == null) {
            // Unknown adapter — warn but don't block (forward-compatible)
            logger.warn("Unknown sourceSystem '{}' — no parameter validation applied", sourceSystem);
            return;
        }
        var errors = descriptor.validateParams(params);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Collects non-fatal configuration warnings for a profile (exposed via REST API).
     */
    public List<String> collectWarnings(ImportProfileDefinition def) {
        List<String> warnings = new ArrayList<>();
        if (def.isSchedulerEnabled()) {
            Map<String, String> params = def.getSchedulerParams();
            if (params == null || params.isEmpty()) {
                warnings.add("schedulerEnabled is on but schedulerParams is empty — adapters will use default scope");
            }
        }
        return warnings;
    }

    // --- Internal ---

    @SuppressWarnings("unchecked")
    private void upsertDocument(ImportProfileDefinition def) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        Map<String, Object> jsonMap = MAPPER.convertValue(def, Map.class);
        jsonMap.put("type", ImportProfileDefinition.DOC_TYPE);

        Document doc = new Document();
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            doc.put(entry.getKey(), entry.getValue());
        }

        List<com.ibm.cloud.cloudant.v1.model.Document> existing = findRawDocs(cloudant, dbName,
                Map.of("type", ImportProfileDefinition.DOC_TYPE, "profileId", def.getProfileId()));
        if (!existing.isEmpty()) {
            doc.setId(existing.get(0).getId());
            doc.setRev(existing.get(0).getRev());
        }

        PostDocumentOptions options = new PostDocumentOptions.Builder()
                .db(dbName).document(doc).build();
        DocumentResult result = cloudant.postDocument(options).execute().getResult();
        if (!result.isOk()) {
            throw new IllegalStateException("Failed to save import profile " + def.getProfileId() + ": " + result.getError());
        }
    }

    @SuppressWarnings("unchecked")
    private List<ImportProfileDefinition> findBySelector(Map<String, Object> selector) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        List<com.ibm.cloud.cloudant.v1.model.Document> rawDocs = findRawDocs(cloudant, dbName, selector);
        List<ImportProfileDefinition> results = new ArrayList<>();
        for (com.ibm.cloud.cloudant.v1.model.Document rawDoc : rawDocs) {
            try {
                Map<String, Object> props = new HashMap<>(rawDoc.getProperties());
                props.remove("_id");
                props.remove("_rev");
                props.remove("type");
                results.add(MAPPER.convertValue(props, ImportProfileDefinition.class));
            } catch (Exception e) {
                logger.warn("Failed to deserialize import profile: {}", e.getMessage());
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
