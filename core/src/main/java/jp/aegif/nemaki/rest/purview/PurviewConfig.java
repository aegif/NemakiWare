package jp.aegif.nemaki.rest.purview;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import jp.aegif.nemaki.util.PropertyManager;

@Component
public class PurviewConfig {

    private static final Logger logger = LoggerFactory.getLogger(PurviewConfig.class);

    public static final String DEFAULT_ATLAS_BASE_PATH = "datamap/api/atlas/v2";

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Autowired(required = false)
    private IntegrationSettingsService integrationSettingsService;

    @Value("${purview.enabled:false}")
    private boolean enabled;

    @Value("${purview.account.name:}")
    private String accountName;

    @Value("${purview.endpoint:}")
    private String endpoint;

    @Value("${purview.atlas.base-path:" + DEFAULT_ATLAS_BASE_PATH + "}")
    private String atlasBasePath;

    @Value("${purview.collection:NemakiWare}")
    private String collection;

    @Value("${purview.tenant.id:}")
    private String tenantId;

    @Value("${purview.client.id:}")
    private String clientId;

    @Value("${purview.client.secret:}")
    private String clientSecret;

    @Value("${purview.timeout.connect.ms:5000}")
    private int connectTimeoutMs;

    @Value("${purview.timeout.read.ms:30000}")
    private int readTimeoutMs;

    @Value("${purview.delete-resolution.delay.ms:5000}")
    private long deleteResolutionDelayMs;

    @Value("${purview.sync.cron:}")
    private String syncCron;

    // Legacy fields kept for migration detection only
    @Value("${purview.auth.type:oauth2}")
    private String legacyAuthType;

    @Value("${purview.basic.username:}")
    private String legacyBasicUsername;

    @Value("${purview.basic.password:}")
    private String legacyBasicPassword;

    @PostConstruct
    void postConstruct() {
        if (enabled) {
            String secret = trimToEmpty(clientSecret);
            if (!secret.isEmpty() && looksLikePlaintext(secret)) {
                logger.warn("purview.client.secret appears to be a plaintext value. "
                        + "Consider using an encrypted or externalized secret (e.g., environment variable, vault).");
            }
        }
        migrateBasicAuthToAtlas();
    }

    /**
     * One-time migration: if the legacy purview.auth.type=basic configuration is detected,
     * automatically write atlas.* settings to CouchDB and remove the legacy keys.
     * Falls back to warning-only if IntegrationSettingsService is not available.
     */
    void migrateBasicAuthToAtlas() {
        String authType = trimToEmpty(legacyAuthType);
        if (!"basic".equalsIgnoreCase(authType)) {
            return;
        }

        if (integrationSettingsService == null) {
            logger.warn("Detected legacy purview.auth.type=basic but IntegrationSettingsService is not available. "
                    + "Auto-migration skipped. Please manually migrate to atlas.* settings.");
            return;
        }

        // Skip only if a previous migration already wrote atlas.enabled=true
        // to CouchDB. We must check the *source* — if atlas.enabled=true comes
        // from a properties file or environment variable the migration has NOT
        // run yet and purview.enabled=false still needs to be written back.
        if ("true".equalsIgnoreCase(integrationSettingsService.readSetting("atlas.enabled"))
                && "couchdb".equals(integrationSettingsService.readSettingSource("atlas.enabled"))) {
            logger.info("Legacy purview.auth.type=basic detected but atlas.enabled=true already exists in CouchDB. "
                    + "Skipping re-migration. Remove purview.auth.type from your properties file to suppress this message.");
            return;
        }

        try {
            // Write atlas.* settings from the legacy purview.basic.* values
            Map<String, String> atlasSettings = new HashMap<>();
            atlasSettings.put("atlas.enabled", "true");
            String legacyEndpoint = trimToEmpty(readDynamic("purview.endpoint", endpoint));
            if (!legacyEndpoint.isEmpty()) {
                atlasSettings.put("atlas.endpoint", legacyEndpoint);
            }
            String basicUser = trimToEmpty(readDynamic("purview.basic.username", legacyBasicUsername));
            if (!basicUser.isEmpty()) {
                atlasSettings.put("atlas.username", basicUser);
            }
            String basicPass = trimToEmpty(readDynamic("purview.basic.password", legacyBasicPassword));
            if (!basicPass.isEmpty()) {
                atlasSettings.put("atlas.password", basicPass);
            }
            // Preserve collection scope so schema/sync targets don't silently change
            String legacyCollection = trimToEmpty(readDynamic("purview.collection", collection));
            if (!legacyCollection.isEmpty()) {
                atlasSettings.put("atlas.collection", legacyCollection);
            }

            // Atomically disable Purview so the resolver picks Atlas after migration.
            // Without this, purview.enabled=true would keep the Purview/OAuth2 path
            // active (Purview takes priority), and the migrated Atlas credentials
            // would never be used.
            atlasSettings.put("purview.enabled", "false");

            integrationSettingsService.writeSettings(atlasSettings);

            // Remove legacy keys from CouchDB
            integrationSettingsService.deleteSettings(Set.of(
                    "purview.auth.type", "purview.basic.username", "purview.basic.password"));

            logger.warn("Auto-migrated legacy purview.auth.type=basic to atlas.* settings in CouchDB. "
                    + "purview.enabled has been set to false so that Atlas is used. "
                    + "Please remove purview.auth.type, purview.basic.username, and purview.basic.password "
                    + "from your properties file to suppress this message.");
        } catch (Exception e) {
            logger.error("Failed to auto-migrate legacy purview.auth.type=basic to atlas.* settings: {}",
                    e.getMessage(), e);
        }
    }

    static boolean looksLikePlaintext(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.startsWith("${") || value.startsWith("ENC(") || value.startsWith("vault:")) {
            return false;
        }
        return true;
    }

    public boolean isEnabled() {
        return readDynamicBoolean("purview.enabled", enabled);
    }

    public String getAccountName() {
        return trimToEmpty(readDynamic("purview.account.name", accountName));
    }

    public String getEndpoint() {
        String configuredEndpoint = normalizeEndpoint(readDynamic("purview.endpoint", endpoint));
        if (!configuredEndpoint.isEmpty()) {
            return configuredEndpoint;
        }

        String configuredAccountName = getAccountName();
        if (!configuredAccountName.isEmpty()) {
            return "https://" + configuredAccountName + ".purview.azure.com";
        }

        return "";
    }

    public String getAtlasBasePath() {
        String configuredBasePath = normalizeBasePath(readDynamic("purview.atlas.base-path", atlasBasePath));
        return configuredBasePath.isEmpty() ? DEFAULT_ATLAS_BASE_PATH : configuredBasePath;
    }

    public String getCollection() {
        String configuredCollection = trimToEmpty(readDynamic("purview.collection", collection));
        return configuredCollection.isEmpty() ? "NemakiWare" : configuredCollection;
    }

    public String getTenantId() {
        return trimToEmpty(readDynamic("purview.tenant.id", tenantId));
    }

    public String getClientId() {
        return trimToEmpty(readDynamic("purview.client.id", clientId));
    }

    public String getClientSecret() {
        return trimToEmpty(readDynamic("purview.client.secret", clientSecret));
    }

    public int getConnectTimeoutMs() {
        return readDynamicInt("purview.timeout.connect.ms", connectTimeoutMs);
    }

    public int getReadTimeoutMs() {
        return readDynamicInt("purview.timeout.read.ms", readTimeoutMs);
    }

    public boolean isPurviewDataMap() {
        return getAtlasBasePath().startsWith("datamap/");
    }

    public long getDeleteResolutionDelayMs() {
        return readDynamicLong("purview.delete-resolution.delay.ms", deleteResolutionDelayMs);
    }

    public String getSyncCron() {
        return trimToEmpty(readDynamic("purview.sync.cron", syncCron));
    }

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

    private long readDynamicLong(String key, long startupDefault) {
        String value = readDynamic(key, null);
        if (value == null || value.trim().isEmpty()) {
            return startupDefault;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return startupDefault;
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeEndpoint(String value) {
        String trimmed = trimToEmpty(value);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalizeBasePath(String value) {
        String normalized = trimToEmpty(value);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
