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
        if (connectorPool != null) {
            String value = readFromConfigDocument(key);
            if (value != null) {
                return value;
            }
        }
        Object value = getConfigurationMap().get(key);
        return value == null ? "" : value.toString();
    }

    /**
     * The four-state read (4b preflight): absence, an empty value, a value, or a failure —
     * never one impersonating another. {@link #getString} deliberately collapses the first
     * three, which is fine for its callers and not fine for an acceptance check.
     */
    @Override
    public RawEntry getRaw(String key) {
        try {
            if (connectorPool != null) {
                Object raw = readRawValueFromConfigDocumentStrict(key);
                if (raw != NOT_FOUND) {
                    // A present-but-null value is NOT empty: the key holds something this
                    // code cannot interpret, which is unknown, not clean.
                    return raw == null ? RawEntry.error("NullValue")
                        : RawEntry.of(raw.toString());
                }
                return RawEntry.absent();
            }
            Map<String, Object> configuration = getConfigurationMap();
            if (!configuration.containsKey(key)) {
                return RawEntry.absent();
            }
            Object value = configuration.get(key);
            return value == null ? RawEntry.error("NullValue")
                    : RawEntry.of(value.toString());
        } catch (RuntimeException e) {
            // Not "absent": a key we could not read has not been checked. The exception's
            // message is NOT logged — it is uncontrolled text that may carry response
            // fragments, and this read exists to look for residual tokens.
            log.warn("Raw state read failed for key " + key + " ("
                    + e.getClass().getSimpleName() + ") — reporting ERROR, not absence");
            return RawEntry.error(e.getClass().getSimpleName());
        }
    }

    /**
     * Both stores, independently (4b preflight). The migration leaves the legacy
     * {@code nemaki_conf} document in place when the dedicated one already exists, so a clean
     * dedicated cursor can sit on top of a legacy one that still carries a raw URL.
     */
    @Override
    public java.util.List<RawEntry> getRawEverywhere(String key) {
        java.util.List<RawEntry> entries = new java.util.ArrayList<>();
        if (connectorPool != null) {
            try {
                Object raw = readRawValueFromConfigDocumentStrict(key);
                entries.add(raw == NOT_FOUND ? RawEntry.absent()
                        : raw == null ? RawEntry.error("NullValue")
                                : RawEntry.of(raw.toString()));
            } catch (RuntimeException e) {
                log.warn("Dedicated-store read failed for key " + key + " ("
                        + e.getClass().getSimpleName() + ") — reporting ERROR");
                entries.add(RawEntry.error("dedicated:" + e.getClass().getSimpleName()));
            }
        }
        try {
            Map<String, Object> configuration = connectorPool == null
                    ? getConfigurationMap() : readLegacyConfigurationStrict();
            if (!configuration.containsKey(key)) {
                entries.add(RawEntry.absent());
            } else {
                Object value = configuration.get(key);
                entries.add(value == null ? RawEntry.error("NullValue")
                        : RawEntry.of(value.toString()));
            }
        } catch (RuntimeException e) {
            log.warn("Legacy-store read failed for key " + key + " ("
                    + e.getClass().getSimpleName() + ") — reporting ERROR");
            entries.add(RawEntry.error("legacy:" + e.getClass().getSimpleName()));
        }
        return entries;
    }

    /**
     * The dedicated Purview state database, and ONLY it (4b preflight).
     *
     * <p>{@link #getConfigClient} falls back to {@code nemaki_conf} when the dedicated client
     * is unavailable, which is right for ordinary reads and wrong here: the whole point of
     * inspecting both stores is that they are two stores, and a fallback would silently make
     * them one.
     */
    private CloudantClientWrapper strictDedicatedClient() {
        CloudantClientWrapper client =
                connectorPool.getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
        if (client == null) {
            throw new IllegalStateException("the dedicated Purview state database is not"
                    + " available");
        }
        return client;
    }

    /** The legacy {@code nemaki_conf} database, read directly rather than through the DAO. */
    private CloudantClientWrapper strictLegacyClient() {
        CloudantClientWrapper client = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
        if (client == null) {
            throw new IllegalStateException("the legacy configuration database is not"
                    + " available");
        }
        return client;
    }

    /**
     * The legacy configuration map, read so that a failure THROWS.
     *
     * <p>{@link #getConfigurationMap} goes through {@code ContentDaoService}, which catches
     * database failures and answers with an empty configuration — and the cache may then keep
     * that empty answer. For an ordinary read that is a graceful degradation; for a residue
     * check it is a clean verdict for a store nobody could read.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readLegacyConfigurationStrict() {
        CloudantClientWrapper client = strictLegacyClient();
        com.ibm.cloud.cloudant.v1.model.AllDocsResult result = client.getClient()
                .postAllDocs(new com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.Builder()
                        .db(client.getDatabaseName())
                        .includeDocs(true)
                        .build())
                .execute().getResult();
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (com.ibm.cloud.cloudant.v1.model.DocsResultRow row : result.getRows()) {
            if (row.getValue() != null && Boolean.TRUE.equals(row.getValue().isDeleted())) {
                continue;
            }
            if (row.getId() != null && row.getId().startsWith("_design/")) {
                continue;
            }
            if (row.getDoc() == null || row.getDoc().getProperties() == null) {
                throw new IllegalStateException("legacy configuration row '" + row.getId()
                        + "' returned no document — the inventory would be incomplete");
            }
            Map<String, Object> props = row.getDoc().getProperties();
            // The real legacy schema: one document per key, global entries carrying no
            // repositoryId. Reading only an aggregate "configuration" map — which is what an
            // earlier version of this method did — misses every one of them, which would
            // report a dirty legacy cursor as absent.
            Object key = props.get("key");
            if (key instanceof String name && !name.isBlank()) {
                if (props.get("repositoryId") == null) {
                    putOnce(values, name, props.get("value"));
                }
                continue;
            }
            // Aggregate shape, kept for compatibility with any deployment that still has one.
            Object configuration = props.get("configuration");
            if (configuration instanceof Map) {
                ((Map<String, Object>) configuration)
                        .forEach((k, v) -> putOnce(values, k, v));
            }
            // Anything else in this database is not configuration and is not our business.
        }
        return values;
    }

    /**
     * Both legacy shapes can coexist, so the same logical key can appear twice. Last-write-wins
     * would let a clean per-key document overwrite a dirty aggregate value and report green;
     * there is no basis for choosing between them, so neither is chosen.
     */
    private static void putOnce(Map<String, Object> values, String key, Object value) {
        if (values.containsKey(key)) {
            throw new IllegalStateException("legacy key '" + key + "' is stored in more than"
                    + " one place — refusing to pick one");
        }
        values.put(key, value);
    }

    /** Distinguishes "the document has no such value" from "the value is there and empty". */
    private static final Object NOT_FOUND = new Object();

    /**
     * A read that preserves not-found versus failure.
     *
     * <p>{@code CloudantClientWrapper.get} catches every read exception and answers
     * {@code null}, which would turn an outage into "absent" — the one answer this whole check
     * must never invent. So the SDK is called directly here: a 404 is absence, anything else
     * propagates and becomes {@code ERROR}.
     */
    @SuppressWarnings("unchecked")
    private Object readRawValueFromConfigDocumentStrict(String key) {
        CloudantClientWrapper configClient = strictDedicatedClient();
        Map<String, Object> existing;
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc = configClient.getClient()
                    .getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
                            .db(configClient.getDatabaseName())
                            .docId(buildDocumentId(key))
                            .build())
                    .execute().getResult();
            existing = new java.util.HashMap<>();
            if (doc.getProperties() != null) {
                existing.putAll(doc.getProperties());
            }
        } catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException absent) {
            return NOT_FOUND;
        }
        // buildDocumentId maps '.' to '_', so it is NOT injective: a document whose key is
        // "purview_cursor_state_..." lands on the same id as "purview.cursor.state...". The
        // fetched document must therefore say it is the key we asked for.
        Object storedKey = existing.get("key");
        if (!(storedKey instanceof String name) || !name.equals(key)) {
            throw new IllegalStateException("the state document at the derived id holds key '"
                    + storedKey + "', not the requested one — refusing to read a collision");
        }
        if (existing.containsKey("value")) {
            return existing.get("value");
        }
        Object props = existing.get("properties");
        if (props instanceof Map && ((Map<String, Object>) props).containsKey("value")) {
            return ((Map<String, Object>) props).get("value");
        }
        // The document EXISTS but holds no value. That is not absence — it is a shape this
        // code does not understand, and calling it absent would report clean for a key nobody
        // could read.
        throw new IllegalStateException("state document for the key exists but carries no"
                + " 'value' field");
    }

    /**
     * Enumeration that cannot swallow a failure (4b preflight).
     *
     * <p>{@link #getAll} suppresses Cloudant errors and returns whatever it has, which would
     * let a cursor inventory look complete while missing every persisted key. This throws
     * instead, so the caller reports an unreadable inventory rather than a short one.
     */
    @Override
    public Map<String, Object> getAllStrict() {
        if (connectorPool == null) {
            return new java.util.LinkedHashMap<>(getConfigurationMap());
        }
        // Both stores, both strictly: a legacy enumeration that silently returned nothing
        // would hide every repository whose cursor lives only there.
        Map<String, Object> merged = new java.util.LinkedHashMap<>(
                readLegacyConfigurationStrict());
        merged.putAll(readAllFromConfigDocumentsStrict());
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readAllFromConfigDocumentsStrict() {
        CloudantClientWrapper configClient = strictDedicatedClient();
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        com.ibm.cloud.cloudant.v1.model.AllDocsResult result = configClient.getClient()
                .postAllDocs(new com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.Builder()
                        .db(configClient.getDatabaseName())
                        .includeDocs(true)
                        .startKey(DOCUMENT_ID_PREFIX)
                        .endKey(DOCUMENT_ID_PREFIX + "\ufff0")
                        .build())
                .execute().getResult();
        for (com.ibm.cloud.cloudant.v1.model.DocsResultRow row : result.getRows()) {
            if (row.getValue() != null && Boolean.TRUE.equals(row.getValue().isDeleted())) {
                continue; // a tombstone is genuinely gone
            }
            if (row.getDoc() == null || row.getDoc().getProperties() == null) {
                // A live row whose document did not come back is a row we did not read.
                // Skipping it would make a short inventory look complete.
                throw new IllegalStateException("state row '" + row.getId() + "' returned no"
                        + " document — the inventory would be incomplete");
            }
            Map<String, Object> props = row.getDoc().getProperties();
            Object key = props.get("key");
            if (!(key instanceof String name) || name.isBlank()) {
                throw new IllegalStateException("state row '" + row.getId() + "' has no usable"
                        + " key — refusing to enumerate around it");
            }
            // The point read derives the document id from the key. A row whose id does not
            // match would be enumerated here and then 404 there, reporting ABSENT for a key
            // that is demonstrably present.
            if (!buildDocumentId(name).equals(row.getId())) {
                throw new IllegalStateException("state row '" + row.getId() + "' holds a key"
                        + " whose derived id does not match it — a later point read would"
                        + " report it absent");
            }
            // containsKey, not "put returned non-null": a first row holding a null value
            // returns null from put both times, and the second, clean row would win.
            putOnce(values, name, props.get("value"));
        }
        return values;
    }

    @Override
    public int getInt(String key) {
        if (connectorPool != null) {
            Object rawValue = readRawValueFromConfigDocument(key);
            if (rawValue != null) {
                if (rawValue instanceof Number number) {
                    return number.intValue();
                }
                try {
                    return Integer.parseInt(rawValue.toString());
                } catch (NumberFormatException e) {
                    try {
                        return (int) Double.parseDouble(rawValue.toString());
                    } catch (NumberFormatException e2) {
                        return 0;
                    }
                }
            }
        }
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
            try {
                return (int) Double.parseDouble(value.toString());
            } catch (NumberFormatException e2) {
                return 0;
            }
        }
    }

    @Override
    public Map<String, Object> getAll() {
        Map<String, Object> result = new LinkedHashMap<>(getConfigurationMap());
        if (connectorPool != null) {
            mergeFromPurviewStateDb(result);
        }
        return result;
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

    @Override
    public void putObject(String key, Map<String, Object> value) {
        if (connectorPool == null) {
            // Legacy mode: flatten the map as individual keys (fallback)
            PurviewStateStore.super.putObject(key, value);
            return;
        }
        CloudantClientWrapper configClient = getConfigClient();
        upsertConfigDocument(configClient, key, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> getObject(String key) {
        if (connectorPool == null) {
            return PurviewStateStore.super.getObject(key);
        }
        CloudantClientWrapper configClient = getConfigClient();
        String documentId = buildDocumentId(key);
        Map<String, Object> existing = configClient.get(Map.class, documentId);
        if (existing == null) {
            return null;
        }
        // Cloudant SDK wraps custom fields under "properties" when converting to Map
        Object value = existing.get("value");
        if (value == null) {
            Object props = existing.get("properties");
            if (props instanceof Map) {
                value = ((Map<String, Object>) props).get("value");
            }
        }
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
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

    private String readFromConfigDocument(String key) {
        Object value = readRawValueFromConfigDocument(key);
        if (value != null) {
            return value.toString();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object readRawValueFromConfigDocument(String key) {
        try {
            CloudantClientWrapper configClient = getConfigClient();
            String documentId = buildDocumentId(key);
            Map<String, Object> existing = configClient.get(Map.class, documentId);
            if (existing == null) {
                return null;
            }
            // Cloudant SDK wraps custom fields under "properties" when converting to Map
            Object value = existing.get("value");
            if (value != null) {
                return value;
            }
            Object props = existing.get("properties");
            if (props instanceof Map) {
                value = ((Map<String, Object>) props).get("value");
                if (value != null) {
                    return value;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Failed to read from purview state DB for key " + key + ": " + e.getMessage());
        }
        return null;
    }

    private void mergeFromPurviewStateDb(Map<String, Object> target) {
        try {
            CloudantClientWrapper configClient = getConfigClient();
            Cloudant client = configClient.getClient();
            String dbName = configClient.getDatabaseName();
            com.ibm.cloud.cloudant.v1.model.AllDocsResult allDocs = client.postAllDocs(
                    new com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.Builder()
                            .db(dbName)
                            .includeDocs(true)
                            .startKey(DOCUMENT_ID_PREFIX)
                            .endKey(DOCUMENT_ID_PREFIX + "\ufff0")
                            .build()
            ).execute().getResult();
            for (com.ibm.cloud.cloudant.v1.model.DocsResultRow row : allDocs.getRows()) {
                if (row.getDoc() != null) {
                    Map<String, Object> docProps = row.getDoc().getProperties();
                    if (docProps != null) {
                        Object docKey = docProps.get("key");
                        Object docValue = docProps.get("value");
                        if (docKey != null) {
                            target.put(docKey.toString(), docValue);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("Failed to merge from purview state DB: " + e.getMessage());
        }
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
        if (configuration != null && configuration.isLoadFailed()) {
            // Both callers replace the whole map. Building that map from a read that failed
            // would persist an aggregate document holding only what this call adds, discarding
            // settings that are still there but were not returned (external review). "Create"
            // in this method's name covers an absent configuration, not an unreadable one.
            throw new IllegalStateException(
                    "Configuration could not be read, so it must not be rewritten: the update"
                            + " would drop every setting the failed read did not return");
        }
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
        // Prefer dedicated Purview state database
        try {
            CloudantClientWrapper client = connectorPool.getClient(SystemConst.NEMAKI_PURVIEW_STATE_DB);
            if (client != null) {
                return client;
            }
        } catch (RuntimeException ignored) {
            // Fall through to legacy DB
        }
        // Fallback: legacy nemaki_conf
        CloudantClientWrapper configClient = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
        if (configClient == null) {
            throw new IllegalStateException("No Purview state database available");
        }
        return configClient;
    }
}
