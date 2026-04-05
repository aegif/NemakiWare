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

public class ConnectorDefinitionServiceImpl implements ConnectorDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectorDefinitionServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        upsertDocument(def);
        logger.info("Created connector definition: {}", def.getConnectorId());
        return def;
    }

    @Override
    public ConnectorDefinition get(String connectorId) {
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
        upsertDocument(def);
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

    private void validateRequiredFields(ConnectorDefinition def) {
        if (def.getConnectorId() == null || def.getConnectorId().isBlank()) {
            throw new IllegalArgumentException("connectorId is required");
        }
        if (def.getSourceArchetype() == null) {
            throw new IllegalArgumentException("sourceArchetype is required. Valid values: "
                    + java.util.Arrays.toString(SourceArchetype.values()));
        }
        if (def.getSourceSystem() == null || def.getSourceSystem().isBlank()) {
            throw new IllegalArgumentException("sourceSystem is required (e.g. imap, gmail_mail, m365_mail, "
                    + "slack, teams, mattermost, chatwork, notion, salesforce, box, dropbox, google, microsoft)");
        }
    }

    // --- Internal ---

    @SuppressWarnings("unchecked")
    private void upsertDocument(ConnectorDefinition def) {
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
        if (!existing.isEmpty()) {
            doc.setId(existing.get(0).getId());
            doc.setRev(existing.get(0).getRev());
        }

        PostDocumentOptions options = new PostDocumentOptions.Builder()
                .db(dbName).document(doc).build();
        DocumentResult result = cloudant.postDocument(options).execute().getResult();
        if (!result.isOk()) {
            throw new IllegalStateException("Failed to save connector " + def.getConnectorId() + ": " + result.getError());
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
