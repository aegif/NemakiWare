package jp.aegif.nemaki.rest.purview.journal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.util.PropertyManager;

/**
 * Configuration for Google Dataplex lineage sink.
 * Supports dynamic property reload via PropertyManager (CouchDB-backed settings).
 */
@Component
public class DataplexConfig {

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Value("${dataplex.enabled:false}")
    private boolean enabled;

    @Value("${dataplex.project-id:}")
    private String projectId;

    @Value("${dataplex.location:}")
    private String location;

    @Value("${dataplex.credentials-file:}")
    private String credentialsFile;

    public boolean isEnabled() { return readDynamicBoolean("dataplex.enabled", enabled); }
    public String getProjectId() { return trimToEmpty(readDynamic("dataplex.project-id", projectId)); }
    public String getLocation() { return trimToEmpty(readDynamic("dataplex.location", location)); }
    public String getCredentialsFile() { return trimToEmpty(readDynamic("dataplex.credentials-file", credentialsFile)); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public void setLocation(String location) { this.location = location; }
    public void setCredentialsFile(String credentialsFile) { this.credentialsFile = credentialsFile; }

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

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
