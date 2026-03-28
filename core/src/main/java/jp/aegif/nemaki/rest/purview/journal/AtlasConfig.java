package jp.aegif.nemaki.rest.purview.journal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for Apache Atlas lineage sink.
 */
@Component
public class AtlasConfig {

    @Value("${atlas.enabled:false}")
    private boolean enabled;

    @Value("${atlas.endpoint:}")
    private String endpoint;

    @Value("${atlas.username:}")
    private String username;

    @Value("${atlas.password:}")
    private String password;

    public boolean isEnabled() { return enabled; }
    public String getEndpoint() { return endpoint; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }
}
