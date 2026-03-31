package jp.aegif.nemaki.rest.purview.journal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.util.PropertyManager;

/**
 * Configuration for Apache Atlas lineage sink.
 * Supports dynamic property reload via PropertyManager (CouchDB-backed settings).
 */
@Component
public class AtlasConfig {

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Value("${atlas.enabled:false}")
    private boolean enabled;

    @Value("${atlas.endpoint:}")
    private String endpoint;

    @Value("${atlas.username:}")
    private String username;

    @Value("${atlas.password:}")
    private String password;

    @Value("${atlas.connect.timeout.ms:5000}")
    private int connectTimeoutMs;

    @Value("${atlas.read.timeout.ms:30000}")
    private int readTimeoutMs;

    @Value("${atlas.sync.cron:}")
    private String syncCron;

    @Value("${atlas.collection:NemakiWare}")
    private String collection;

    public boolean isEnabled() { return readDynamicBoolean("atlas.enabled", enabled); }
    public String getEndpoint() { return trimToEmpty(readDynamic("atlas.endpoint", endpoint)); }
    public String getUsername() { return trimToEmpty(readDynamic("atlas.username", username)); }
    public String getPassword() { return trimToEmpty(readDynamic("atlas.password", password)); }
    public int getConnectTimeoutMs() { return readDynamicInt("atlas.connect.timeout.ms", connectTimeoutMs); }
    public int getReadTimeoutMs() { return readDynamicInt("atlas.read.timeout.ms", readTimeoutMs); }
    public String getSyncCron() { return trimToEmpty(readDynamic("atlas.sync.cron", syncCron)); }
    public String getCollection() {
        String c = trimToEmpty(readDynamic("atlas.collection", collection));
        return c.isEmpty() ? "NemakiWare" : c;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public void setSyncCron(String syncCron) { this.syncCron = syncCron; }
    public void setCollection(String collection) { this.collection = collection; }

    private String readDynamic(String key, String startupDefault) {
        if (propertyManager != null) {
            String value = propertyManager.readValue(key);
            if (value != null) {
                return value;
            }
        }
        return startupDefault;
    }

    private boolean readDynamicBoolean(String key, boolean startupDefault) {
        String value = readDynamic(key, null);
        if (value == null) {
            return startupDefault;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private int readDynamicInt(String key, int startupDefault) {
        String value = readDynamic(key, null);
        if (value == null || value.trim().isEmpty()) {
            return startupDefault;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return startupDefault;
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
