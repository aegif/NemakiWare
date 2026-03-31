package jp.aegif.nemaki.rest.purview;

import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.journal.AtlasConfig;
import org.springframework.stereotype.Component;

/**
 * Resolves which metadata catalog backend (Purview or Atlas) is active
 * and builds the appropriate {@link PurviewConnectionRequest}.
 */
@Component
public class MetadataCatalogConnectionResolver {

    private final PurviewConfig purviewConfig;
    private final AtlasConfig atlasConfig;

    public MetadataCatalogConnectionResolver(PurviewConfig purviewConfig, AtlasConfig atlasConfig) {
        this.purviewConfig = purviewConfig;
        this.atlasConfig = atlasConfig;
    }

    public boolean isAnyEnabled() {
        return purviewConfig.isEnabled() || atlasConfig.isEnabled();
    }

    public CatalogBackendKind activeBackend() {
        if (purviewConfig.isEnabled()) return CatalogBackendKind.PURVIEW;
        if (atlasConfig.isEnabled()) return CatalogBackendKind.ATLAS;
        return CatalogBackendKind.NONE;
    }

    public PurviewConnectionRequest buildConnectionRequest() {
        return switch (activeBackend()) {
            case PURVIEW -> new PurviewConnectionRequest(
                    purviewConfig.getEndpoint(),
                    purviewConfig.getAtlasBasePath(),
                    "oauth2",
                    purviewConfig.getTenantId(),
                    purviewConfig.getClientId(),
                    purviewConfig.getClientSecret(),
                    "", "",
                    purviewConfig.getConnectTimeoutMs(),
                    purviewConfig.getReadTimeoutMs());
            case ATLAS -> new PurviewConnectionRequest(
                    atlasConfig.getEndpoint(),
                    "api/atlas/v2",
                    "basic",
                    "", "", "",
                    atlasConfig.getUsername(),
                    atlasConfig.getPassword(),
                    atlasConfig.getConnectTimeoutMs(),
                    atlasConfig.getReadTimeoutMs());
            case NONE -> throw new IllegalStateException("No catalog backend enabled");
        };
    }

    /**
     * Builds a connection request using a specific atlas base path (for governance candidate probing).
     */
    public PurviewConnectionRequest buildConnectionRequest(String atlasBasePath) {
        return switch (activeBackend()) {
            case PURVIEW -> new PurviewConnectionRequest(
                    purviewConfig.getEndpoint(),
                    atlasBasePath,
                    "oauth2",
                    purviewConfig.getTenantId(),
                    purviewConfig.getClientId(),
                    purviewConfig.getClientSecret(),
                    "", "",
                    purviewConfig.getConnectTimeoutMs(),
                    purviewConfig.getReadTimeoutMs());
            case ATLAS -> new PurviewConnectionRequest(
                    atlasConfig.getEndpoint(),
                    atlasBasePath,
                    "basic",
                    "", "", "",
                    atlasConfig.getUsername(),
                    atlasConfig.getPassword(),
                    atlasConfig.getConnectTimeoutMs(),
                    atlasConfig.getReadTimeoutMs());
            case NONE -> throw new IllegalStateException("No catalog backend enabled");
        };
    }

    public boolean isAtlasOnPrem() {
        return activeBackend() == CatalogBackendKind.ATLAS;
    }

    public String getCollection() {
        return switch (activeBackend()) {
            case PURVIEW -> purviewConfig.getCollection();
            case ATLAS -> atlasConfig.getCollection();
            case NONE -> "NemakiWare";
        };
    }
}
