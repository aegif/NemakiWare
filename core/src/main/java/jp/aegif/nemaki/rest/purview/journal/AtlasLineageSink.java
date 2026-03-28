package jp.aegif.nemaki.rest.purview.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Apache Atlas implementation of {@link LineageTargetSink}.
 *
 * <p>Publishes lineage events to Atlas REST API v2 using the entity bulk endpoint.
 * Uses {@link SimpleHttpClient} with Basic Auth (no external dependencies).
 */
@Component
public class AtlasLineageSink implements LineageTargetSink {

    private static final Logger logger = LoggerFactory.getLogger(AtlasLineageSink.class);

    @Autowired
    private AtlasConfig atlasConfig;

    private SimpleHttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = new SimpleHttpClient(Duration.ofSeconds(30));
    }

    @PreDestroy
    void destroy() {
        if (httpClient != null) httpClient.close();
    }

    @Override
    public String targetName() {
        return "atlas";
    }

    @Override
    public LineageTargetSinkResult publish(LineageEvent event) throws Exception {
        if (!isAvailable()) {
            return LineageTargetSinkResult.failure("Atlas sink not available");
        }

        SimpleHttpClient client = this.httpClient
                .withBasicAuth(atlasConfig.getUsername(), atlasConfig.getPassword());

        String endpoint = atlasConfig.getEndpoint().replaceAll("/+$", "");
        validateEndpoint(endpoint);
        String url = endpoint + "/api/atlas/v2/entity/bulk";

        // Build Atlas entity payload
        Map<String, Object> payload = buildAtlasPayload(event);
        HttpResponse<String> response = client.postJson(url, payload);

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            logger.debug("Published lineage event to Atlas: eventKey={}, status={}",
                    event.eventKey(), response.statusCode());
            return LineageTargetSinkResult.success(1, "Atlas OK");
        } else {
            String body = response.body();
            logger.warn("Atlas API returned {}: {}", response.statusCode(), body);
            return LineageTargetSinkResult.failure("Atlas API " + response.statusCode() + ": " + body);
        }
    }

    @Override
    public boolean isAvailable() {
        return atlasConfig.isEnabled()
                && atlasConfig.getEndpoint() != null
                && !atlasConfig.getEndpoint().isBlank();
    }

    Map<String, Object> buildAtlasPayload(LineageEvent event) {
        List<Map<String, Object>> entities = new ArrayList<>();

        // Process entity
        Map<String, Object> processEntity = new LinkedHashMap<>();
        processEntity.put("typeName", "Process");
        Map<String, Object> processAttrs = new LinkedHashMap<>();
        processAttrs.put("qualifiedName", "nemakiware:" + event.repositoryId() + ":" +
                (event.processType() != null ? event.processType().name().toLowerCase() : "unknown") +
                ":" + event.eventKey());
        processAttrs.put("name", event.processType() != null ? event.processType().name() : "UNKNOWN");
        processAttrs.put("description", "NemakiWare lineage event: " + event.eventKey());
        processEntity.put("attributes", processAttrs);

        // Input entities
        List<Map<String, Object>> inputs = new ArrayList<>();
        if (event.inputs() != null) {
            for (String input : event.inputs()) {
                inputs.add(Map.of("typeName", "DataSet", "uniqueAttributes",
                        Map.of("qualifiedName", "nemakiware:" + event.repositoryId() + ":" + input)));
            }
        }
        processAttrs.put("inputs", inputs);

        // Output entities
        List<Map<String, Object>> outputs = new ArrayList<>();
        if (event.outputs() != null) {
            for (String output : event.outputs()) {
                outputs.add(Map.of("typeName", "DataSet", "uniqueAttributes",
                        Map.of("qualifiedName", "nemakiware:" + event.repositoryId() + ":" + output)));
            }
        }
        processAttrs.put("outputs", outputs);

        entities.add(processEntity);

        return Map.of("entities", entities);
    }

    private static void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new IllegalArgumentException("Atlas endpoint must use http or https scheme: " + endpoint);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Atlas endpoint URL: " + endpoint, e);
        }
    }
}
