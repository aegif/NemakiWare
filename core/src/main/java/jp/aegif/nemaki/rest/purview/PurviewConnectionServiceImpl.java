package jp.aegif.nemaki.rest.purview;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PurviewConnectionServiceImpl implements PurviewConnectionService {

    static final String ALTERNATE_ATLAS_BASE_PATH = "catalog/api/atlas/v2";

    private final PurviewConfig purviewConfig;
    private final PurviewApiClient purviewApiClient;

    @Autowired
    public PurviewConnectionServiceImpl(PurviewConfig purviewConfig, PurviewApiClient purviewApiClient) {
        this.purviewConfig = purviewConfig;
        this.purviewApiClient = purviewApiClient;
    }

    @Override
    public PurviewConnectionStatus testConnection() {
        String endpoint = purviewConfig.getEndpoint();
        String configuredBasePath = purviewConfig.getAtlasBasePath();
        boolean featureEnabled = purviewConfig.isEnabled();

        List<String> missing = new ArrayList<>();
        if (isBlank(endpoint)) {
            missing.add("endpoint");
        }
        if (isBlank(purviewConfig.getTenantId())) {
            missing.add("tenantId");
        }
        if (isBlank(purviewConfig.getClientId())) {
            missing.add("clientId");
        }
        if (isBlank(purviewConfig.getClientSecret())) {
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
                    purviewConfig.getTenantId(),
                    purviewConfig.getClientId(),
                    purviewConfig.getClientSecret(),
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
}
