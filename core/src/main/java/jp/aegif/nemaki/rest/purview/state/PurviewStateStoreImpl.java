package jp.aegif.nemaki.rest.purview.state;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.PostViewOptions;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.constant.SystemConst;

@Service
public class PurviewStateStoreImpl implements PurviewStateStore {

    private static final Log log = LogFactory.getLog(PurviewStateStoreImpl.class);
    private static final String DOCUMENT_ID_PREFIX = "system_config_";
    private static final String DEAD_LETTER_PREFIX = "purview.dead-letter.state.";
    private static final String VIEW_BY_SCOPE_AND_KIND = "purviewStateByScopeAndKind";
    private static final String DESIGN_DOC = "_repo";

    private final ContentDaoService contentDaoService;
    private final CloudantClientPool connectorPool;

    @Autowired
    public PurviewStateStoreImpl(
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            CloudantClientPool connectorPool) {
        this.contentDaoService = contentDaoService;
        this.connectorPool = connectorPool;
    }

    PurviewStateStoreImpl(@Qualifier("ContentDaoService") ContentDaoService contentDaoService) {
        this(contentDaoService, null);
    }

    @Override
    public String getString(String key) {
        Object value = getConfigurationMap().get(key);
        return value == null ? "" : value.toString();
    }

    @Override
    public int getInt(String key) {
        Object value = getConfigurationMap().get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public Map<String, Object> getAll() {
        return new LinkedHashMap<>(getConfigurationMap());
    }

    @Override
    public Map<String, Object> getAllByPrefix(String keyPrefix) {
        if (connectorPool == null || !keyPrefix.startsWith(DEAD_LETTER_PREFIX)) {
            return PurviewStateStore.super.getAllByPrefix(keyPrefix);
        }
        try {
            return queryViewByPrefix(keyPrefix);
        } catch (Exception e) {
            log.debug("View query failed, falling back to full scan: " + e.getMessage());
            return PurviewStateStore.super.getAllByPrefix(keyPrefix);
        }
    }

    @Override
    public void putAll(Map<String, Object> values) {
        if (connectorPool == null) {
            putAllLegacy(values);
            return;
        }
        CloudantClientWrapper configClient = getConfigClient();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            upsertConfigDocument(configClient, entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void removeAll(Collection<String> keys) {
        if (connectorPool == null) {
            removeAllLegacy(keys);
            return;
        }
        for (String key : keys) {
            deleteConfigDocument(key);
        }
    }

    private Map<String, Object> queryViewByPrefix(String keyPrefix) {
        CloudantClientWrapper configClient = getConfigClient();
        Cloudant client = configClient.getClient();
        String dbName = configClient.getDatabaseName();

        String scope = keyPrefix.substring(DEAD_LETTER_PREFIX.length());
        String[] scopeParts = scope.split("\\.", -1);

        List<Object> startKey;
        List<Object> endKey;
        if (scopeParts.length >= 2 && !scopeParts[0].isEmpty() && !scopeParts[1].isEmpty()) {
            startKey = List.of(scopeParts[0], scopeParts[1]);
            endKey = List.of(scopeParts[0], scopeParts[1], Collections.emptyMap());
        } else if (scopeParts.length >= 1 && !scopeParts[0].isEmpty()) {
            startKey = List.of(scopeParts[0]);
            endKey = List.of(scopeParts[0], Collections.emptyMap());
        } else {
            startKey = List.of();
            endKey = List.of(Collections.emptyMap());
        }

        PostViewOptions options = new PostViewOptions.Builder()
                .db(dbName)
                .ddoc(DESIGN_DOC)
                .view(VIEW_BY_SCOPE_AND_KIND)
                .includeDocs(true)
                .reduce(false)
                .startKey(startKey)
                .endKey(endKey)
                .build();

        ViewResult result = client.postView(options).execute().getResult();

        Map<String, Object> entries = new LinkedHashMap<>();
        for (ViewResultRow row : result.getRows()) {
            if (row.getDoc() != null) {
                Map<String, Object> docProps = row.getDoc().getProperties();
                if (docProps != null) {
                    Object docKey = docProps.get("key");
                    Object docValue = docProps.get("value");
                    if (docKey != null) {
                        String keyStr = docKey.toString();
                        if (keyStr.startsWith(keyPrefix)) {
                            entries.put(keyStr, docValue);
                        }
                    }
                }
            }
        }
        return entries;
    }

    private Map<String, Object> getConfigurationMap() {
        Configuration configuration = contentDaoService.getConfiguration(SystemConst.NEMAKI_CONF_DB);
        if (configuration == null || configuration.getConfiguration() == null) {
            return Map.of();
        }
        return configuration.getConfiguration();
    }

    private void upsertConfigDocument(CloudantClientWrapper configClient, String key, Object value) {
        String documentId = buildDocumentId(key);
        String now = Instant.now().toString();
        Map<String, Object> existing = configClient.get(Map.class, documentId);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("_id", documentId);
        document.put("type", "configuration");
        document.put("key", key);
        document.put("value", value);
        document.put("description", "Purview state for " + key);
        document.put("created", getExistingValue(existing, "created", now));
        document.put("creator", getExistingValue(existing, "creator", "system"));
        document.put("modified", now);
        document.put("modifier", "system");

        if (existing != null && existing.get("_rev") != null) {
            document.put("_rev", existing.get("_rev"));
            configClient.update(document);
            return;
        }

        configClient.create(documentId, document);
    }

    private void deleteConfigDocument(String key) {
        Map<String, Object> existing = getConfigClient().get(Map.class, buildDocumentId(key));
        if (existing != null) {
            getConfigClient().delete(existing);
        }
    }

    private void putAllLegacy(Map<String, Object> values) {
        Configuration configuration = getOrCreateSystemConfiguration();
        Map<String, Object> updatedMap = new HashMap<>(configuration.getConfiguration());
        updatedMap.putAll(values);
        configuration.setConfiguration(updatedMap);
        persist(configuration);
    }

    private void removeAllLegacy(Collection<String> keys) {
        Configuration configuration = getOrCreateSystemConfiguration();
        Map<String, Object> updatedMap = new HashMap<>(configuration.getConfiguration());
        for (String key : keys) {
            updatedMap.remove(key);
        }
        configuration.setConfiguration(updatedMap);
        persist(configuration);
    }

    private String getExistingValue(Map<String, Object> existing, String fieldName, String defaultValue) {
        if (existing == null) {
            return defaultValue;
        }
        Object value = existing.get(fieldName);
        return value == null ? defaultValue : value.toString();
    }

    private String buildDocumentId(String key) {
        return DOCUMENT_ID_PREFIX + key.replace('.', '_');
    }

    private Configuration getOrCreateSystemConfiguration() {
        Configuration configuration = contentDaoService.getConfiguration(SystemConst.NEMAKI_CONF_DB);
        if (configuration == null) {
            configuration = new Configuration();
        }
        if (configuration.getId() == null || configuration.getId().isBlank()) {
            configuration.setId("config_" + SystemConst.NEMAKI_CONF_DB);
        }
        if (configuration.getConfiguration() == null) {
            configuration.setConfiguration(new HashMap<>());
        }
        return configuration;
    }

    private void persist(Configuration configuration) {
        try {
            contentDaoService.update(SystemConst.NEMAKI_CONF_DB, configuration);
        } catch (RuntimeException e) {
            contentDaoService.create(SystemConst.NEMAKI_CONF_DB, configuration);
        }
    }

    private CloudantClientWrapper getConfigClient() {
        CloudantClientWrapper configClient = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
        if (configClient == null) {
            throw new IllegalStateException("nemaki_conf client is not available");
        }
        return configClient;
    }
}
