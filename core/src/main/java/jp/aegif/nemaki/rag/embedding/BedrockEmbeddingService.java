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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.aegif.nemaki.rag.config.RAGConfig;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

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

    @Autowired
    public BedrockEmbeddingService(RAGConfig ragConfig) {
        this.ragConfig = ragConfig;
        this.objectMapper = new ObjectMapper();
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
        return embedSingle(text);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, boolean isQuery) throws EmbeddingException {
        if (texts == null || texts.isEmpty()) {
            throw EmbeddingException.invalidInput("Texts list cannot be null or empty");
        }

        int batchSize = ragConfig.getBedrockBatchSize();
        if (batchSize <= 0) {
            batchSize = 1;
        }
        List<float[]> results = new ArrayList<>();

        for (int i = 0; i < texts.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, endIndex);
            for (String text : batch) {
                results.add(embedSingle(text));
            }
        }

        return results;
    }

    @Override
    public boolean isHealthy() {
        try {
            validateConfig();
            return true;
        } catch (EmbeddingException e) {
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
        } catch (JsonProcessingException e) {
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
        } catch (JsonProcessingException e) {
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

    private BedrockRuntimeClient getClient() throws EmbeddingException {
        BedrockRuntimeClient existing = clientRef.get();
        if (existing != null) {
            return existing;
        }

        validateConfig();

        ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(Duration.ofMillis(ragConfig.getBedrockTimeoutMs()))
                .build();

        BedrockRuntimeClient created = BedrockRuntimeClient.builder()
                .region(Region.of(ragConfig.getBedrockRegion()))
                .overrideConfiguration(overrideConfiguration)
                .build();

        clientRef.compareAndSet(null, created);
        return clientRef.get();
    }
}
