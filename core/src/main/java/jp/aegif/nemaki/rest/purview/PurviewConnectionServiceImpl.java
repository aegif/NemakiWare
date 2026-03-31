package jp.aegif.nemaki.rest.purview;

import jp.aegif.nemaki.rest.purview.client.PurviewApiClient;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.client.PurviewProbeResult;
import jp.aegif.nemaki.rest.purview.journal.AtlasConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PurviewConnectionServiceImpl implements PurviewConnectionService {

    public static final String ALTERNATE_ATLAS_BASE_PATH = "catalog/api/atlas/v2";

    private final PurviewConfig purviewConfig;
    private final AtlasConfig atlasConfig;
    private final PurviewApiClient purviewApiClient;

    @Autowired
    public PurviewConnectionServiceImpl(PurviewConfig purviewConfig, AtlasConfig atlasConfig,
            PurviewApiClient purviewApiClient) {
        this.purviewConfig = purviewConfig;
        this.atlasConfig = atlasConfig;
        this.purviewApiClient = purviewApiClient;
    }

    @Override
    public PurviewConnectionStatus testConnection() {
        return doTestPurviewConnection(
                purviewConfig.getEndpoint(),
                purviewConfig.getAtlasBasePath(),
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.isEnabled());
    }

    @Override
    public PurviewConnectionStatus testConnection(Map<String, String> formValues) {
        String endpoint = firstNonBlank(formValues.get("purview.endpoint"), purviewConfig.getEndpoint());
        String basePath = firstNonBlank(formValues.get("purview.atlas.base-path"), purviewConfig.getAtlasBasePath());
        String tenantId = firstNonBlank(formValues.get("purview.tenant.id"), purviewConfig.getTenantId());
        String clientId = firstNonBlank(formValues.get("purview.client.id"), purviewConfig.getClientId());
        String clientSecret = formValues.containsKey("purview.client.secret")
                && !isPlaceholder(formValues.get("purview.client.secret"))
                ? formValues.get("purview.client.secret") : purviewConfig.getClientSecret();
        boolean featureEnabled = formValues.containsKey("purview.enabled")
                ? "true".equals(formValues.get("purview.enabled")) : purviewConfig.isEnabled();

        return doTestPurviewConnection(endpoint, basePath, tenantId, clientId, clientSecret, featureEnabled);
    }

    @Override
    public PurviewConnectionStatus testAtlasConnection(Map<String, String> formValues) {
        String endpoint = firstNonBlank(
                formValues != null ? formValues.get("atlas.endpoint") : null,
                atlasConfig.getEndpoint());
        String username = firstNonBlank(
                formValues != null ? formValues.get("atlas.username") : null,
                atlasConfig.getUsername());
        String password = formValues != null && formValues.containsKey("atlas.password")
                && !isPlaceholder(formValues.get("atlas.password"))
                ? formValues.get("atlas.password") : atlasConfig.getPassword();
        boolean featureEnabled = formValues != null && formValues.containsKey("atlas.enabled")
                ? "true".equals(formValues.get("atlas.enabled")) : atlasConfig.isEnabled();

        return doTestAtlasConnection(endpoint, username, password, featureEnabled);
    }

    private PurviewConnectionStatus doTestPurviewConnection(
            String endpoint, String configuredBasePath,
            String tenantId, String clientId, String clientSecret, boolean featureEnabled) {
        if (!featureEnabled) {
            return new PurviewConnectionStatus(false, false, endpoint, configuredBasePath,
                    "Purview integration is currently disabled");
        }

        List<String> missing = new ArrayList<>();
        if (isBlank(endpoint)) {
            missing.add("endpoint");
        }
        if (isBlank(tenantId)) {
            missing.add("tenantId");
        }
        if (isBlank(clientId)) {
            missing.add("clientId");
        }
        if (isBlank(clientSecret)) {
            missing.add("clientSecret");
        }

        if (!missing.isEmpty()) {
            return new PurviewConnectionStatus(
                    false,
                    featureEnabled,
                    endpoint,
                    configuredBasePath,
                    "Missing required Purview configuration: " + String.join(", ", missing));
        }

        List<String> candidateBasePaths = buildCandidateBasePaths(configuredBasePath);
        PurviewProbeResult lastFailure = null;
        String lastAttemptedBasePath = configuredBasePath;

        for (int i = 0; i < candidateBasePaths.size(); i++) {
            String candidateBasePath = candidateBasePaths.get(i);
            lastAttemptedBasePath = candidateBasePath;

            PurviewConnectionRequest request = new PurviewConnectionRequest(
                    endpoint,
                    candidateBasePath,
                    tenantId,
                    clientId,
                    clientSecret,
                    purviewConfig.getConnectTimeoutMs(),
                    purviewConfig.getReadTimeoutMs());

            try {
                PurviewProbeResult probeResult = purviewApiClient.probeConnection(request);
                if (probeResult.isSuccess()) {
                    String message = candidateBasePath.equals(configuredBasePath)
                            ? "Purview connection succeeded using atlas base path " + candidateBasePath
                            : "Purview connection succeeded using fallback atlas base path " + candidateBasePath;
                    return new PurviewConnectionStatus(true, featureEnabled, endpoint, candidateBasePath, message);
                }

                lastFailure = probeResult;
                if (probeResult.getStatusCode() != 404 || i == candidateBasePaths.size() - 1) {
                    break;
                }
            } catch (PurviewClientException e) {
                return new PurviewConnectionStatus(false, featureEnabled, endpoint, candidateBasePath, e.getMessage());
            }
        }

        String failureMessage = lastFailure != null ? lastFailure.getMessage() : "Purview connection failed";
        return new PurviewConnectionStatus(false, featureEnabled, endpoint, lastAttemptedBasePath, failureMessage);
    }

    private PurviewConnectionStatus doTestAtlasConnection(
            String endpoint, String username, String password, boolean featureEnabled) {
        if (!featureEnabled) {
            return new PurviewConnectionStatus(false, false, endpoint, "api/atlas/v2",
                    "Atlas integration is currently disabled");
        }

        List<String> missing = new ArrayList<>();
        if (isBlank(endpoint)) {
            missing.add("endpoint");
        }
        if (isBlank(username)) {
            missing.add("username");
        }
        if (isBlank(password)) {
            missing.add("password");
        }

        if (!missing.isEmpty()) {
            return new PurviewConnectionStatus(
                    false,
                    featureEnabled,
                    endpoint,
                    "api/atlas/v2",
                    "Missing required Atlas configuration: " + String.join(", ", missing));
        }

        PurviewConnectionRequest request = new PurviewConnectionRequest(
                endpoint,
                "api/atlas/v2",
                "basic",
                "", "", "",
                username,
                password,
                atlasConfig.getConnectTimeoutMs(),
                atlasConfig.getReadTimeoutMs());

        try {
            PurviewProbeResult probeResult = purviewApiClient.probeConnection(request);
            if (probeResult.isSuccess()) {
                return new PurviewConnectionStatus(true, featureEnabled, endpoint, "api/atlas/v2",
                        "Atlas connection succeeded");
            }
            return new PurviewConnectionStatus(false, featureEnabled, endpoint, "api/atlas/v2",
                    probeResult.getMessage());
        } catch (PurviewClientException e) {
            return new PurviewConnectionStatus(false, featureEnabled, endpoint, "api/atlas/v2", e.getMessage());
        }
    }

    private List<String> buildCandidateBasePaths(String configuredBasePath) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(configuredBasePath);
        candidates.add(PurviewConfig.DEFAULT_ATLAS_BASE_PATH);
        candidates.add(ALTERNATE_ATLAS_BASE_PATH);
        return new ArrayList<>(candidates);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isPlaceholder(String value) {
        return value != null && value.startsWith("[") && value.endsWith("]");
    }

    private String firstNonBlank(String preferred, String fallback) {
        return !isBlank(preferred) ? preferred : fallback;
    }
}
