package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
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

public class ImportProfileDefinitionServiceImpl implements ImportProfileDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(ImportProfileDefinitionServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        for (ImportProfileDefinition p : candidates) {
            if (p.isEnabled() && p.isArchetypeAllowed(archetype) && p.isConnectorAllowed(connectorId)
                    && connectorId != null && connectorId.equals(p.getDefaultConnectorId())) {
                return p;
            }
        }
        // Second pass: prefer profiles marked as defaultProfile
        for (ImportProfileDefinition p : candidates) {
            if (p.isEnabled() && p.isDefaultProfile()
                    && p.isArchetypeAllowed(archetype) && p.isConnectorAllowed(connectorId)) {
                return p;
            }
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

    private void validateRequiredFields(ImportProfileDefinition def) {
        if (def.getProfileId() == null || def.getProfileId().isBlank()) {
            throw new IllegalArgumentException("profileId is required");
        }
        if (def.getRepositoryId() == null || def.getRepositoryId().isBlank()) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        boolean hasFolderId = def.getTargetFolderId() != null && !def.getTargetFolderId().isBlank();
        boolean hasFolderPath = def.getTargetFolderPath() != null && !def.getTargetFolderPath().isBlank();
        if (!hasFolderId && !hasFolderPath) {
            throw new IllegalArgumentException("Either targetFolderId or targetFolderPath is required");
        }
        // Validate scheduler-enabled profiles have required source-scope parameters
        if (def.isSchedulerEnabled()) {
            validateSchedulerParams(def);
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
    private void validateAdapterRequiredParams(String sourceSystem, Map<String, String> params) {
        if (sourceSystem == null) return;
        Map<String, String> p = params != null ? params : Map.of();
        switch (sourceSystem) {
            case "slack" -> {
                if (isBlank(p.get("channelId"))) {
                    throw new IllegalArgumentException(
                            "schedulerParams.channelId is required for Slack adapter");
                }
            }
            case "teams" -> {
                if (isBlank(p.get("teamId"))) {
                    throw new IllegalArgumentException(
                            "schedulerParams.teamId is required for Teams adapter");
                }
                if (isBlank(p.get("channelId"))) {
                    throw new IllegalArgumentException(
                            "schedulerParams.channelId is required for Teams adapter");
                }
            }
            case "mattermost" -> {
                if (isBlank(p.get("channelId"))) {
                    throw new IllegalArgumentException(
                            "schedulerParams.channelId is required for Mattermost adapter");
                }
            }
            case "chatwork" -> {
                if (isBlank(p.get("roomId"))) {
                    throw new IllegalArgumentException(
                            "schedulerParams.roomId is required for Chatwork adapter");
                }
            }
            // imap, gmail_mail, m365_mail, notion, salesforce: all have usable defaults
            default -> { /* no required params */ }
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
