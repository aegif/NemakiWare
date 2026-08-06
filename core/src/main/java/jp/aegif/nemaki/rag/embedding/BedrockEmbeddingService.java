package jp.aegif.nemaki.rag.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import jp.aegif.nemaki.rag.config.RAGConfig;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Amazon Bedrock implementation of EmbeddingService.
 *
 * This provider generates embeddings via Bedrock runtime InvokeModel.
 * It does not require content synchronization; text is sent at request time only.
 */
@Service
public class BedrockEmbeddingService implements EmbeddingService {

    private static final Log log = LogFactory.getLog(BedrockEmbeddingService.class);

    private final RAGConfig ragConfig;
    private final ObjectMapper objectMapper;

    private final AtomicReference<BedrockRuntimeClient> clientRef = new AtomicReference<>();
    /** Tracks the config used to create the cached client, so changes trigger re-creation. */
    private volatile String clientConfigKey = "";

    // Health check TTL cache (mirrors TEI pattern)
    private static final long HEALTH_CACHE_TTL_SUCCESS_MS = 30_000;  // 30s when healthy
    private static final long HEALTH_CACHE_TTL_FAILURE_MS = 10_000;  // 10s when unhealthy
    private volatile boolean lastHealthy = false;
    private volatile long lastHealthCheckTime = 0;

    @Autowired
    public BedrockEmbeddingService(RAGConfig ragConfig) {
        this.ragConfig = ragConfig;
        this.objectMapper = ObjectMapperFactory.createDefaultObjectMapper();
    }

    @PreDestroy
    public void cleanup() {
        BedrockRuntimeClient client = clientRef.get();
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Failed to close BedrockRuntimeClient", e);
            }
        }
    }

    @Override
    public float[] embed(String text, boolean isQuery) throws EmbeddingException {
        if (StringUtils.isBlank(text)) {
            throw EmbeddingException.invalidInput("Text cannot be null or empty");
        }
        // Note: isQuery is intentionally unused. Amazon Titan Embedding models
        // (e.g. amazon.titan-embed-text-v1/v2) do not distinguish between query
        // and passage inputs, unlike some other embedding models (e.g. E5).
        // The parameter is kept for interface compatibility with TEI and other
        // providers that may use it.
        return embedSingle(text);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, boolean isQuery) throws EmbeddingException {
        if (texts == null || texts.isEmpty()) {
            throw EmbeddingException.invalidInput("Texts list cannot be null or empty");
        }

        // Note: isQuery is intentionally unused — see embed() for rationale.
        // Amazon Titan does not support batch embedding in a single InvokeModel call,
        // so each text is embedded individually. The batchSize config is reserved for
        // future models that may support native batching.
        List<float[]> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            results.add(embedSingle(text));
        }
        return results;
    }

    @Override
    public boolean isHealthy() {
        try {
            validateConfig();
        } catch (EmbeddingException e) {
            return false;
        }

        long now = System.currentTimeMillis();
        long ttl = lastHealthy ? HEALTH_CACHE_TTL_SUCCESS_MS : HEALTH_CACHE_TTL_FAILURE_MS;
        if (now - lastHealthCheckTime < ttl) {
            return lastHealthy;
        }

        boolean healthy = doHealthCheck();
        lastHealthy = healthy;
        lastHealthCheckTime = now;
        return healthy;
    }

    /**
     * Perform a lightweight InvokeModel call to verify Bedrock connectivity and credentials.
     */
    private boolean doHealthCheck() {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("inputText", "health");

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(ragConfig.getBedrockModelId())
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(requestBody)))
                    .build();

            InvokeModelResponse response = getClient().invokeModel(request);
            String body = response.body().asUtf8String();
            JsonNode root = objectMapper.readTree(body);
            JsonNode embeddingNode = root.get("embedding");
            boolean ok = embeddingNode != null && embeddingNode.isArray() && embeddingNode.size() > 0;
            if (log.isDebugEnabled()) {
                log.debug("Bedrock health check result: " + ok + " (dimension=" +
                        (embeddingNode != null ? embeddingNode.size() : 0) + ")");
            }
            return ok;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Bedrock health check failed: " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public int getVectorDimension() {
        return ragConfig.getBedrockVectorDimension();
    }

    private float[] embedSingle(String text) throws EmbeddingException {
        validateConfig();

        String trimmed = text.trim();
        int maxChars = ragConfig.getBedrockMaxInputChars();
        if (maxChars > 0 && trimmed.length() > maxChars) {
            trimmed = trimmed.substring(0, maxChars);
        }

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("inputText", trimmed);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(ragConfig.getBedrockModelId())
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(requestBody)))
                    .build();

            InvokeModelResponse response = getClient().invokeModel(request);
            String responseBody = response.body().asUtf8String();

            return parseEmbeddingResponse(responseBody);
        } catch (EmbeddingException e) {
            // Re-throw EmbeddingException from parseEmbeddingResponse() as-is
            throw e;
        } catch (JacksonException e) {
            throw new EmbeddingException("Failed to serialize Bedrock request", e);
        } catch (Exception e) {
            throw EmbeddingException.connectionError("Bedrock embedding request failed", e);
        }
    }

    private float[] parseEmbeddingResponse(String responseBody) throws EmbeddingException {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingNode = root.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new EmbeddingException("Unexpected Bedrock response format: " + responseBody);
            }

            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }

            int expected = getVectorDimension();
            if (expected > 0 && embedding.length != expected) {
                log.warn(String.format("Unexpected embedding dimension: expected %d, got %d",
                        expected, embedding.length));
            }

            return embedding;
        } catch (JacksonException e) {
            throw new EmbeddingException("Failed to parse Bedrock response: " + responseBody, e);
        }
    }

    private void validateConfig() throws EmbeddingException {
        if (!"bedrock".equalsIgnoreCase(ragConfig.getEmbeddingProvider())) {
            return;
        }
        if (StringUtils.isBlank(ragConfig.getBedrockRegion())) {
            throw EmbeddingException.invalidInput("Bedrock region is required");
        }
        if (StringUtils.isBlank(ragConfig.getBedrockModelId())) {
            throw EmbeddingException.invalidInput("Bedrock modelId is required");
        }
    }

    private synchronized BedrockRuntimeClient getClient() throws EmbeddingException {
        validateConfig();

        // Build a config key from current settings so the client is recreated when
        // the Setup Wizard changes region, model, or credentials at runtime.
        String currentConfigKey = ragConfig.getBedrockRegion()
                + "|" + ragConfig.getBedrockModelId()
                + "|" + StringUtils.defaultString(ragConfig.getBedrockAccessKeyId())
                + "|" + StringUtils.defaultString(ragConfig.getBedrockSecretAccessKey());

        BedrockRuntimeClient existing = clientRef.get();
        if (existing != null && currentConfigKey.equals(clientConfigKey)) {
            return existing;
        }

        // Config changed or first call — create a new client
        ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(Duration.ofMillis(ragConfig.getBedrockTimeoutMs()))
                .build();

        BedrockRuntimeClientBuilder builder = BedrockRuntimeClient.builder()
                .region(Region.of(ragConfig.getBedrockRegion()))
                .overrideConfiguration(overrideConfiguration);

        // Use explicit credentials if configured, otherwise default credential chain
        String accessKey = ragConfig.getBedrockAccessKeyId();
        String secretKey = ragConfig.getBedrockSecretAccessKey();
        if (StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey)) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())));
        }

        BedrockRuntimeClient created = builder.build();

        // Swap out old client and close it
        BedrockRuntimeClient old = clientRef.getAndSet(created);
        clientConfigKey = currentConfigKey;
        if (old != null) {
            try {
                old.close();
            } catch (Exception e) {
                log.warn("Failed to close previous BedrockRuntimeClient", e);
            }
        }
        return created;
    }
}
