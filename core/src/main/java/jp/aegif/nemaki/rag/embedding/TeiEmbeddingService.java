package jp.aegif.nemaki.rag.embedding;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.aegif.nemaki.rag.config.RAGConfig;

/**
 * TEI (Text Embeddings Inference) implementation of EmbeddingService.
 *
 * Connects to Hugging Face's Text Embeddings Inference server to generate
 * embeddings using the multilingual E5 model.
 *
 * TEI API endpoints:
 * - POST /embed: Generate embeddings for input texts
 * - GET /health: Check service health
 *
 * E5 Model Notes:
 * - Requires "query: " prefix for queries, "passage: " for documents
 * - Produces 1024-dimensional vectors
 * - Cosine similarity recommended
 */
@Service
public class TeiEmbeddingService implements EmbeddingService {

    private static final Log log = LogFactory.getLog(TeiEmbeddingService.class);

    // Health check cache TTL settings:
    // - On success: longer TTL to reduce healthy service checks
    // - On failure: shorter TTL to detect recovery faster
    private static final long HEALTH_CACHE_TTL_SUCCESS_MS = 30_000;  // 30 seconds when healthy
    private static final long HEALTH_CACHE_TTL_FAILURE_MS = 5_000;   // 5 seconds when unhealthy

    private final RAGConfig ragConfig;
    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;

    /**
     * Immutable holder for health check cache state.
     * Using AtomicReference with immutable holder ensures atomic reads/writes
     * and prevents race conditions in the check-then-act pattern.
     */
    private static final class HealthCacheState {
        final boolean healthy;
        final long timestamp;

        HealthCacheState(boolean healthy, long timestamp) {
            this.healthy = healthy;
            this.timestamp = timestamp;
        }
    }

    // Health check caching to avoid excessive HTTP calls
    // Using AtomicReference with immutable holder for thread-safe atomic updates
    private final AtomicReference<HealthCacheState> healthCache =
            new AtomicReference<>(new HealthCacheState(false, 0));

    @Autowired
    public TeiEmbeddingService(RAGConfig ragConfig) {
        this.ragConfig = ragConfig;
        this.objectMapper = new ObjectMapper();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.of(ragConfig.getTeiConnectTimeout(), TimeUnit.MILLISECONDS))
                .setResponseTimeout(Timeout.of(ragConfig.getTeiReadTimeout(), TimeUnit.MILLISECONDS))
                .build();

        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (httpClient != null) {
                httpClient.close();
                log.info("TeiEmbeddingService HTTP client closed");
            }
        } catch (IOException e) {
            log.warn("Error closing HTTP client", e);
        }
    }

    @Override
    public float[] embed(String text, boolean isQuery) throws EmbeddingException {
        if (text == null || text.trim().isEmpty()) {
            throw EmbeddingException.invalidInput("Text cannot be null or empty");
        }

        List<float[]> results = embedBatch(List.of(text), isQuery);
        return results.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, boolean isQuery) throws EmbeddingException {
        if (texts == null || texts.isEmpty()) {
            throw EmbeddingException.invalidInput("Texts list cannot be null or empty");
        }

        // Apply E5 prefix
        String prefix = isQuery ? QUERY_PREFIX : PASSAGE_PREFIX;
        List<String> prefixedTexts = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.trim().isEmpty()) {
                throw EmbeddingException.invalidInput("Text in batch cannot be null or empty");
            }
            prefixedTexts.add(prefix + text);
        }

        // Process in batches if needed
        int batchSize = ragConfig.getTeiBatchSize();
        List<float[]> allEmbeddings = new ArrayList<>();

        for (int i = 0; i < prefixedTexts.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, prefixedTexts.size());
            List<String> batch = prefixedTexts.subList(i, endIndex);
            List<float[]> batchEmbeddings = embedBatchInternal(batch);
            allEmbeddings.addAll(batchEmbeddings);
        }

        return allEmbeddings;
    }

    private List<float[]> embedBatchInternal(List<String> texts) throws EmbeddingException {
        int retries = 0;
        int maxRetries = ragConfig.getTeiMaxRetries();
        int retryDelay = ragConfig.getTeiRetryDelay();

        while (true) {
            try {
                return doEmbedRequest(texts);
            } catch (EmbeddingException e) {
                if (!e.isRetryable() || retries >= maxRetries) {
                    throw e;
                }
                retries++;
                log.warn(String.format("TEI request failed, retrying (%d/%d): %s",
                        retries, maxRetries, e.getMessage()));
                try {
                    Thread.sleep(retryDelay * retries);  // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new EmbeddingException("Retry interrupted", ie);
                }
            }
        }
    }

    private List<float[]> doEmbedRequest(List<String> texts) throws EmbeddingException {
        String url = ragConfig.getTeiUrl() + "/embed";

        try {
            // Build request body
            ObjectNode requestBody = objectMapper.createObjectNode();
            ArrayNode inputsArray = requestBody.putArray("inputs");
            for (String text : texts) {
                inputsArray.add(text);
            }

            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setEntity(new StringEntity(
                    objectMapper.writeValueAsString(requestBody),
                    ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getCode();
                HttpEntity entity = response.getEntity();
                String responseBody = entity != null ? EntityUtils.toString(entity) : "";

                if (statusCode == 200) {
                    return parseEmbeddingResponse(responseBody);
                } else if (statusCode == 503 || statusCode == 429) {
                    throw EmbeddingException.serviceUnavailable(
                            "TEI service unavailable (HTTP " + statusCode + "): " + responseBody);
                } else {
                    throw new EmbeddingException(
                            "TEI request failed (HTTP " + statusCode + "): " + responseBody);
                }
            }

        } catch (SocketTimeoutException e) {
            throw EmbeddingException.timeout("TEI request timed out", e);
        } catch (IOException | ParseException e) {
            throw EmbeddingException.connectionError("Failed to connect to TEI service", e);
        }
    }

    private List<float[]> parseEmbeddingResponse(String responseBody) throws EmbeddingException {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            List<float[]> embeddings = new ArrayList<>();

            if (root.isArray()) {
                for (JsonNode embeddingNode : root) {
                    float[] embedding = parseEmbeddingArray(embeddingNode);
                    embeddings.add(embedding);
                }
            } else {
                throw new EmbeddingException("Unexpected TEI response format: " + responseBody);
            }

            return embeddings;
        } catch (JsonProcessingException e) {
            throw new EmbeddingException("Failed to parse TEI response: " + responseBody, e);
        }
    }

    private float[] parseEmbeddingArray(JsonNode embeddingNode) throws EmbeddingException {
        if (!embeddingNode.isArray()) {
            throw new EmbeddingException("Expected array for embedding, got: " + embeddingNode);
        }

        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }

        if (embedding.length != VECTOR_DIMENSION) {
            log.warn(String.format("Unexpected embedding dimension: expected %d, got %d",
                    VECTOR_DIMENSION, embedding.length));
        }

        return embedding;
    }

    @Override
    public boolean isHealthy() {
        if (!ragConfig.isEnabled()) {
            return false;
        }

        // Read current cache state atomically
        HealthCacheState currentState = healthCache.get();
        long now = System.currentTimeMillis();
        
        // Calculate TTL based on last health status
        // Use shorter TTL when unhealthy to detect recovery faster
        long ttl = currentState.healthy ? HEALTH_CACHE_TTL_SUCCESS_MS : HEALTH_CACHE_TTL_FAILURE_MS;

        if (now - currentState.timestamp < ttl) {
            return currentState.healthy;
        }

        // Perform actual health check
        boolean healthy = doHealthCheck();
        
        // Update cache atomically with new state
        // Note: We don't use compareAndSet here because it's acceptable for concurrent
        // health checks to both update the cache - they'll both have valid recent results
        healthCache.set(new HealthCacheState(healthy, now));
        
        return healthy;
    }

    /**
     * Perform actual HTTP health check to TEI service.
     */
    private boolean doHealthCheck() {
        String url = ragConfig.getTeiUrl() + "/health";

        try {
            HttpGet httpGet = new HttpGet(url);
            boolean result = httpClient.execute(httpGet, response -> {
                int statusCode = response.getCode();
                if (log.isDebugEnabled()) {
                    log.debug("TEI health check response code: " + statusCode);
                }
                return statusCode == 200;
            });
            if (log.isDebugEnabled()) {
                log.debug("TEI health check result: " + result);
            }
            return result;
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("TEI health check failed: " + e.getMessage());
            }
            return false;
        }
    }
}
