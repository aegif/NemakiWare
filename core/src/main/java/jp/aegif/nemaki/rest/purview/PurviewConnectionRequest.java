package jp.aegif.nemaki.rest.purview;

public class PurviewConnectionRequest {

    private final String endpoint;
    private final String atlasBasePath;
    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public PurviewConnectionRequest(
            String endpoint,
            String atlasBasePath,
            String tenantId,
            String clientId,
            String clientSecret,
            int connectTimeoutMs,
            int readTimeoutMs) {
        this.endpoint = endpoint;
        this.atlasBasePath = atlasBasePath;
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getAtlasBasePath() {
        return atlasBasePath;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }
}
