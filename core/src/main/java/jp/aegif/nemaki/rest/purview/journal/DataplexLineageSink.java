package jp.aegif.nemaki.rest.purview.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Google Dataplex implementation of {@link LineageTargetSink}.
 *
 * <p>Publishes lineage events to the Dataplex Lineage API using a
 * Google Service Account for authentication. Uses {@link SimpleHttpClient}
 * with OAuth2 Bearer Token (no external dependencies).
 */
@Component
public class DataplexLineageSink implements LineageTargetSink {

    private static final Logger logger = LoggerFactory.getLogger(DataplexLineageSink.class);
    private static final String DATAPLEX_API_BASE = "https://datalineage.googleapis.com";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DataplexConfig dataplexConfig;

    private SimpleHttpClient httpClient;
    private SimpleHttpClient metadataClient;
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.MIN;

    @PostConstruct
    void init() {
        httpClient = new SimpleHttpClient(Duration.ofSeconds(30));
        metadataClient = new SimpleHttpClient(Duration.ofSeconds(10));
    }

    @PreDestroy
    void destroy() {
        if (httpClient != null) httpClient.close();
        if (metadataClient != null) metadataClient.close();
    }

    @Override
    public String targetName() {
        return "dataplex";
    }

    @Override
    public LineageTargetSinkResult publish(LineageRecord record) throws Exception {
        if (!isAvailable()) {
            return LineageTargetSinkResult.failure("Dataplex sink not available");
        }
        String unresolved = LineageSinkAssets.firstUnresolvedReason(record);
        if (unresolved != null) {
            return LineageTargetSinkResult.failure(unresolved);
        }

        String token = getAccessToken();
        if (token == null) {
            return LineageTargetSinkResult.failure("Failed to obtain Dataplex access token");
        }

        SimpleHttpClient client = httpClient.withBearerToken(token);

        String projectId = URLEncoder.encode(dataplexConfig.getProjectId(), StandardCharsets.UTF_8);
        String location = URLEncoder.encode(dataplexConfig.getLocation(), StandardCharsets.UTF_8);

        // Step 1: Create or reference a process
        String processId = "nemakiware-" + record.repositoryId() + "-" +
                (record.processType() != null ? record.processType().name().toLowerCase() : "unknown");
        String safeProcessId = URLEncoder.encode(processId, StandardCharsets.UTF_8);
        String processUrl = DATAPLEX_API_BASE + "/v1/projects/" + projectId +
                "/locations/" + location + "/processes";

        Map<String, Object> processPayload = buildProcessPayload(record, processId);
        HttpResponse<String> processResp = client.postJson(processUrl, processPayload);

        // Check process creation response
        if (processResp.statusCode() >= 400) {
            String body = processResp.body();
            logger.warn("Dataplex process creation returned {}: {}", processResp.statusCode(), body);
            return LineageTargetSinkResult.failure(
                    "Dataplex process creation " + processResp.statusCode() + ": " + body);
        }

        // Step 2: Create a lineage event under the process
        String safeEventId = URLEncoder.encode(record.eventId(), StandardCharsets.UTF_8);
        String eventUrl = DATAPLEX_API_BASE + "/v1/projects/" + projectId +
                "/locations/" + location + "/processes/" + safeProcessId + "/runs/" +
                safeEventId + "/lineageEvents";

        Map<String, Object> eventPayload = buildEventPayload(record);
        HttpResponse<String> eventResp = client.postJson(eventUrl, eventPayload);

        if (eventResp.statusCode() >= 200 && eventResp.statusCode() < 300) {
            logger.debug("Published lineage event to Dataplex: eventKey={}", record.processIdentity());
            return LineageTargetSinkResult.success(1, "Dataplex OK");
        } else {
            String body = eventResp.body();
            logger.warn("Dataplex API returned {}: {}", eventResp.statusCode(), body);
            return LineageTargetSinkResult.failure("Dataplex API " + eventResp.statusCode() + ": " + body);
        }
    }

    @Override
    public boolean isAvailable() {
        return dataplexConfig.isEnabled()
                && dataplexConfig.getProjectId() != null
                && !dataplexConfig.getProjectId().isBlank()
                && dataplexConfig.getLocation() != null
                && !dataplexConfig.getLocation().isBlank();
    }

    Map<String, Object> buildProcessPayload(LineageRecord record, String processId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", processId);
        payload.put("displayName", "NemakiWare " + record.repositoryId() + " — " +
                (record.processType() != null ? record.processType().name() : "UNKNOWN"));
        Map<String, String> origin = new LinkedHashMap<>();
        origin.put("sourceType", "CUSTOM");
        origin.put("name", "NemakiWare CMIS");
        payload.put("origin", origin);
        return payload;
    }

    Map<String, Object> buildEventPayload(LineageRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("startTime", record.occurredAt());
        payload.put("endTime", record.occurredAt());

        // Build links (input → output)
        List<Map<String, Object>> links = new ArrayList<>();
        for (LineageAssetRef source : record.inputs()) {
            for (LineageAssetRef target : record.outputs()) {
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("source", buildEntityRef(record.repositoryId(), source.qualifiedName()));
                link.put("target", buildEntityRef(record.repositoryId(), target.qualifiedName()));
                links.add(link);
            }
        }
        payload.put("links", links);
        return payload;
    }

    private Map<String, Object> buildEntityRef(String repositoryId, String objectId) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("fullyQualifiedName", "nemakiware:" + repositoryId + ":" + objectId);
        return ref;
    }

    @SuppressWarnings("unchecked")
    private synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }

        try {
            String credentialsFile = dataplexConfig.getCredentialsFile();
            if (credentialsFile == null || credentialsFile.isBlank()) {
                logger.warn("Dataplex credentials file not configured");
                return null;
            }

            // Credentials file presence validates configuration.
            // Actual auth uses GCE metadata server (Application Default Credentials).
            if (!Files.exists(Path.of(credentialsFile))) {
                logger.warn("Dataplex credentials file not found: {}", credentialsFile);
                return null;
            }

            // Use GCE metadata server for Application Default Credentials
            SimpleHttpClient client = metadataClient.withHeader("Metadata-Flavor", "Google");
            HttpResponse<String> resp = client.getJson(
                    "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token");

            if (resp.statusCode() == 200) {
                Map<String, Object> tokenResp = objectMapper.readValue(resp.body(), Map.class);
                cachedToken = (String) tokenResp.get("access_token");
                int expiresIn = tokenResp.containsKey("expires_in") ?
                        ((Number) tokenResp.get("expires_in")).intValue() : 3600;
                tokenExpiry = Instant.now().plusSeconds(expiresIn - 60);
                return cachedToken;
            }

            logger.warn("Failed to obtain access token: HTTP {}", resp.statusCode());
            return null;
        } catch (Exception e) {
            logger.warn("Failed to obtain Dataplex access token: {}", e.getMessage());
            return null;
        }
    }
}
