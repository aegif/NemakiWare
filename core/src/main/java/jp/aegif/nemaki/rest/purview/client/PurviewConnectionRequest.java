package jp.aegif.nemaki.rest.purview.client;

public class PurviewConnectionRequest {

    private final String endpoint;
    private final String atlasBasePath;
    private final String authType;
    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String basicUsername;
    private final String basicPassword;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String collectionId;

    /** Backward-compatible constructor — defaults to OAuth2 auth. */
    public PurviewConnectionRequest(
            String endpoint,
            String atlasBasePath,
            String tenantId,
            String clientId,
            String clientSecret,
            int connectTimeoutMs,
            int readTimeoutMs) {
        this(endpoint, atlasBasePath, "oauth2", tenantId, clientId, clientSecret, "", "", connectTimeoutMs, readTimeoutMs);
    }

    /** Backward-compatible constructor — no collection placement. */
    public PurviewConnectionRequest(
            String endpoint,
            String atlasBasePath,
            String authType,
            String tenantId,
            String clientId,
            String clientSecret,
            String basicUsername,
            String basicPassword,
            int connectTimeoutMs,
            int readTimeoutMs) {
        this(endpoint, atlasBasePath, authType, tenantId, clientId, clientSecret, basicUsername,
                basicPassword, connectTimeoutMs, readTimeoutMs, "");
    }

    public PurviewConnectionRequest(
            String endpoint,
            String atlasBasePath,
            String authType,
            String tenantId,
            String clientId,
            String clientSecret,
            String basicUsername,
            String basicPassword,
            int connectTimeoutMs,
            int readTimeoutMs,
            String collectionId) {
        this.endpoint = endpoint;
        this.atlasBasePath = atlasBasePath;
        this.authType = authType != null ? authType : "oauth2";
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.basicUsername = basicUsername != null ? basicUsername : "";
        this.basicPassword = basicPassword != null ? basicPassword : "";
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.collectionId = collectionId == null ? "" : collectionId.trim();
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getAtlasBasePath() {
        return atlasBasePath;
    }

    public String getAuthType() {
        return authType;
    }

    public boolean isBasicAuth() {
        return "basic".equalsIgnoreCase(authType);
    }

    public boolean isPurviewDataMap() {
        return atlasBasePath != null && atlasBasePath.startsWith("datamap/");
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

    public String getBasicUsername() {
        return basicUsername;
    }

    public String getBasicPassword() {
        return basicPassword;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * The Purview collection entity writes should land in — the collection's immutable
     * <em>reference name</em>, never its friendly display name; the Data Map API's
     * {@code collectionId} parameter matches on the former.
     *
     * <p>Empty for Atlas OSS (no such concept in its API) and for a deployment that accepts the
     * root collection.
     */
    public String getCollectionId() {
        return collectionId;
    }

    public boolean hasCollection() {
        return !collectionId.isEmpty();
    }
}
