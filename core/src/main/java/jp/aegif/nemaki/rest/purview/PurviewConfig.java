package jp.aegif.nemaki.rest.purview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jp.aegif.nemaki.util.PropertyManager;

@Component
public class PurviewConfig {

    private static final Logger logger = LoggerFactory.getLogger(PurviewConfig.class);

    public static final String DEFAULT_ATLAS_BASE_PATH = "datamap/api/atlas/v2";

    @Autowired(required = false)
    private PropertyManager propertyManager;

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

    @Value("${purview.auth.type:oauth2}")
    private String authType;

    @Value("${purview.basic.username:}")
    private String basicUsername;

    @Value("${purview.basic.password:}")
    private String basicPassword;

    @Value("${purview.delete-resolution.delay.ms:5000}")
    private long deleteResolutionDelayMs;

    @Value("${purview.sync.cron:}")
    private String syncCron;

    @PostConstruct
    void warnIfPlaintextSecret() {
        if (!enabled) {
            return;
        }
        String secret = trimToEmpty(clientSecret);
        if (!secret.isEmpty() && looksLikePlaintext(secret)) {
            logger.warn("purview.client.secret appears to be a plaintext value. "
                    + "Consider using an encrypted or externalized secret (e.g., environment variable, vault).");
        }
    }

    static boolean looksLikePlaintext(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        // Encrypted values and env-var references typically start with specific prefixes
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

    public String getAuthType() {
        return trimToEmpty(readDynamic("purview.auth.type", authType)).isEmpty()
                ? "oauth2"
                : trimToEmpty(readDynamic("purview.auth.type", authType));
    }

    public boolean isBasicAuth() {
        return "basic".equalsIgnoreCase(getAuthType());
    }

    public boolean isPurviewDataMap() {
        return getAtlasBasePath().startsWith("datamap/");
    }

    /**
     * Returns true when the configured atlas base path indicates an Apache Atlas
     * on-prem deployment (i.e. the path does not start with a known Purview cloud
     * prefix such as {@code datamap/} or {@code catalog/}).
     */
    public boolean isAtlasOnPrem() {
        String path = getAtlasBasePath();
        return !path.startsWith("datamap/") && !path.startsWith("catalog/");
    }

    public String getBasicUsername() {
        return trimToEmpty(readDynamic("purview.basic.username", basicUsername));
    }

    public String getBasicPassword() {
        return trimToEmpty(readDynamic("purview.basic.password", basicPassword));
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
