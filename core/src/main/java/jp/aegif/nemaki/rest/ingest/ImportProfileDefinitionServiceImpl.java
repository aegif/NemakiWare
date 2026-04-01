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

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    @Override
    public ImportProfileDefinition create(ImportProfileDefinition def) {
        if (def.getProfileId() == null || def.getProfileId().isBlank()) {
            throw new IllegalArgumentException("profileId is required");
        }
        if (def.getRepositoryId() == null || def.getRepositoryId().isBlank()) {
            throw new IllegalArgumentException("repositoryId is required");
        }
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
        if (def.getProfileId() == null || def.getProfileId().isBlank()) {
            throw new IllegalArgumentException("profileId is required");
        }
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
