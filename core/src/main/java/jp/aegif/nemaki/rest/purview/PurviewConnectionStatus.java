package jp.aegif.nemaki.rest.purview;

public class PurviewConnectionStatus {

    private final boolean connected;
    private final boolean featureEnabled;
    private final String endpoint;
    private final String atlasBasePath;
    private final String message;

    public PurviewConnectionStatus(
            boolean connected,
            boolean featureEnabled,
            String endpoint,
            String atlasBasePath,
            String message) {
        this.connected = connected;
        this.featureEnabled = featureEnabled;
        this.endpoint = endpoint;
        this.atlasBasePath = atlasBasePath;
        this.message = message;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isFeatureEnabled() {
        return featureEnabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getAtlasBasePath() {
        return atlasBasePath;
    }

    public String getMessage() {
        return message;
    }
}
