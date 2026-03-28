package jp.aegif.nemaki.rest.purview.journal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for Google Dataplex lineage sink.
 */
@Component
public class DataplexConfig {

    @Value("${dataplex.enabled:false}")
    private boolean enabled;

    @Value("${dataplex.project-id:}")
    private String projectId;

    @Value("${dataplex.location:}")
    private String location;

    @Value("${dataplex.credentials-file:}")
    private String credentialsFile;

    public boolean isEnabled() { return enabled; }
    public String getProjectId() { return projectId; }
    public String getLocation() { return location; }
    public String getCredentialsFile() { return credentialsFile; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public void setLocation(String location) { this.location = location; }
    public void setCredentialsFile(String credentialsFile) { this.credentialsFile = credentialsFile; }
}
