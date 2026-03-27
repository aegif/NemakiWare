package jp.aegif.nemaki.rest.purview;

import jp.aegif.nemaki.rest.purview.client.PurviewApiClient;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.client.PurviewProbeResult;
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
    private final PurviewApiClient purviewApiClient;

    @Autowired
    public PurviewConnectionServiceImpl(PurviewConfig purviewConfig, PurviewApiClient purviewApiClient) {
        this.purviewConfig = purviewConfig;
        this.purviewApiClient = purviewApiClient;
    }

    @Override
    public PurviewConnectionStatus testConnection() {
        return doTestConnection(
                purviewConfig.getEndpoint(),
                purviewConfig.getAtlasBasePath(),
                purviewConfig.getAuthType(),
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.getBasicUsername(),
                purviewConfig.getBasicPassword(),
                purviewConfig.isEnabled());
    }

    @Override
    public PurviewConnectionStatus testConnection(Map<String, String> formValues) {
        String endpoint = firstNonBlank(formValues.get("purview.endpoint"), purviewConfig.getEndpoint());
        String basePath = firstNonBlank(formValues.get("purview.atlas.base-path"), purviewConfig.getAtlasBasePath());
        String authType = firstNonBlank(formValues.get("purview.auth.type"), purviewConfig.getAuthType());
        String tenantId = firstNonBlank(formValues.get("purview.tenant.id"), purviewConfig.getTenantId());
        String clientId = firstNonBlank(formValues.get("purview.client.id"), purviewConfig.getClientId());
        String clientSecret = formValues.containsKey("purview.client.secret")
                && !isPlaceholder(formValues.get("purview.client.secret"))
                ? formValues.get("purview.client.secret") : purviewConfig.getClientSecret();
        String basicUsername = firstNonBlank(formValues.get("purview.basic.username"), purviewConfig.getBasicUsername());
        String basicPassword = formValues.containsKey("purview.basic.password")
                && !isPlaceholder(formValues.get("purview.basic.password"))
                ? formValues.get("purview.basic.password") : purviewConfig.getBasicPassword();
        boolean featureEnabled = formValues.containsKey("purview.enabled")
                ? "true".equals(formValues.get("purview.enabled")) : purviewConfig.isEnabled();

        return doTestConnection(endpoint, basePath, authType, tenantId, clientId, clientSecret,
                basicUsername, basicPassword, featureEnabled);
    }

    private PurviewConnectionStatus doTestConnection(
            String endpoint, String configuredBasePath, String authType,
            String tenantId, String clientId, String clientSecret,
            String basicUsername, String basicPassword, boolean featureEnabled) {
        if (!featureEnabled) {
            return new PurviewConnectionStatus(false, false, endpoint, configuredBasePath,
                    "Purview / Atlas integration is currently disabled");
        }

        boolean basicAuth = "basic".equalsIgnoreCase(authType);

        List<String> missing = new ArrayList<>();
        if (isBlank(endpoint)) {
            missing.add("endpoint");
        }
        if (basicAuth) {
            if (isBlank(basicUsername)) {
                missing.add("basicUsername");
            }
            if (isBlank(basicPassword)) {
                missing.add("basicPassword");
            }
        } else {
            if (isBlank(tenantId)) {
                missing.add("tenantId");
            }
            if (isBlank(clientId)) {
                missing.add("clientId");
            }
            if (isBlank(clientSecret)) {
                missing.add("clientSecret");
            }
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
                    authType,
                    tenantId,
                    clientId,
                    clientSecret,
                    basicUsername,
                    basicPassword,
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

    private boolean isPlaceholder(String value) {
        return value != null && value.startsWith("[") && value.endsWith("]");
    }

    private String firstNonBlank(String preferred, String fallback) {
        return !isBlank(preferred) ? preferred : fallback;
    }
}
