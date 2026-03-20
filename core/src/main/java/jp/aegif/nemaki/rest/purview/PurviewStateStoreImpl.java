package jp.aegif.nemaki.rest.purview;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.constant.SystemConst;

@Service
public class PurviewStateStoreImpl implements PurviewStateStore {

    private static final String DOCUMENT_ID_PREFIX = "system_config_";

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
