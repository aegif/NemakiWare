package jp.aegif.nemaki.rag.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.util.PropertyManager;

/**
 * Configuration class for RAG (Retrieval-Augmented Generation) features.
 *
 * All RAG-related settings are controlled through this class.
 * Settings can be configured via system properties or nemakiware.properties.
 */
@Component
public class RAGConfig {

    private static final Logger log = LoggerFactory.getLogger(RAGConfig.class);

    /**
     * PropertyManager provides runtime reads from System properties, environment
     * variables, CouchDB (nemaki_conf), and nemakiware.properties in priority order.
     * This allows the Setup Wizard's dynamic writes to be picked up without restart.
     * Optional to avoid circular dependency during early initialization.
     */
    @Autowired(required = false)
    private PropertyManager propertyManager;

    /**
     * Read a value at runtime via PropertyManager (System props > env > CouchDB > file),
     * falling back to the @Value-injected default if PropertyManager is not available.
     */
    private String readDynamic(String key, String startupDefault) {
        if (propertyManager != null) {
            String val = propertyManager.readValue(key);
            if (val != null) {
                return val;
            }
        }
        return startupDefault;
    }

    private int readDynamicInt(String key, int startupDefault) {
        String val = readDynamic(key, null);
        if (val != null) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return startupDefault;
    }

    @PostConstruct
    public void init() {
        log.info("=== RAGConfig initialized ===");
        log.info("RAG enabled: {}", enabled);
        log.info("Embedding provider: {}", embeddingProvider);
        log.info("TEI URL: {}", teiUrl);
        log.info("TEI connect timeout: {}", teiConnectTimeout);
        log.info("TEI read timeout: {}", teiReadTimeout);
        if ("bedrock".equalsIgnoreCase(embeddingProvider)) {
            log.warn("[BETA] Bedrock embedding provider is beta - requires AWS subscription");
            log.info("Bedrock region: {}", bedrockRegion);
            log.info("Bedrock model id: {}", bedrockModelId);
            log.info("Bedrock batch size: {}", bedrockBatchSize);
            log.info("Bedrock vector dimension: {}", bedrockVectorDimension);
            if (bedrockVectorDimension != 1024) {
                log.warn("Bedrock vector dimension ({}) does not match Solr schema knn_vector_1024 (1024). " +
                        "Ensure a matching fieldType is defined in schema.xml or indexing will fail.",
                        bedrockVectorDimension);
            }
        }

        // Validate boost values (0.0 to 1.0 range)
        if (propertyBoost < 0.0f || propertyBoost > 1.0f) {
            log.warn("Invalid rag.search.property.boost value: {}. Must be between 0.0 and 1.0. Using default 0.3", propertyBoost);
            propertyBoost = 0.3f;
        }
        if (contentBoost < 0.0f || contentBoost > 1.0f) {
            log.warn("Invalid rag.search.content.boost value: {}. Must be between 0.0 and 1.0. Using default 0.7", contentBoost);
            contentBoost = 0.7f;
        }

        // Validate that at least one boost is positive (otherwise search would return no results)
        if (propertyBoost == 0.0f && contentBoost == 0.0f) {
            log.warn("Both propertyBoost and contentBoost are 0. Setting contentBoost to 1.0 to enable content-only search.");
            contentBoost = 1.0f;
        }

        // Log warning if boost values don't sum to 1.0 (affects score normalization)
        float boostSum = propertyBoost + contentBoost;
        if (Math.abs(boostSum - 1.0f) > 0.001f) {
            log.info("Boost values sum to {} (propertyBoost={}, contentBoost={}). " +
                    "For normalized scores, consider using values that sum to 1.0.",
                    boostSum, propertyBoost, contentBoost);
        }

        if (searchSimilarityThreshold < 0.0f || searchSimilarityThreshold > 1.0f) {
            log.warn("Invalid rag.search.similarity.threshold value: {}. Must be between 0.0 and 1.0. Using default 0.7", searchSimilarityThreshold);
            searchSimilarityThreshold = 0.7f;
        }
    }

    // ========================================
    // Feature Toggle
    // ========================================

    @Value("${rag.enabled:false}")
    private boolean enabled;

    // ========================================
    // Embedding Provider Settings
    // ========================================

    /**
     * Embedding provider selection.
     * Supported: tei, bedrock
     */
    @Value("${rag.embedding.provider:tei}")
    private String embeddingProvider;

    // ========================================
    // Bedrock Embedding Settings
    // ========================================

    @Value("${rag.bedrock.region:}")
    private String bedrockRegion;

    @Value("${rag.bedrock.model.id:}")
    private String bedrockModelId;

    @Value("${rag.bedrock.batch.size:32}")
    private int bedrockBatchSize;

    @Value("${rag.bedrock.max.input.chars:8000}")
    private int bedrockMaxInputChars;

    @Value("${rag.bedrock.timeout.ms:30000}")
    private int bedrockTimeoutMs;

    @Value("${rag.bedrock.vector.dimension:1024}")
    private int bedrockVectorDimension;

    /**
     * Explicit AWS access key for Bedrock (optional).
     * If not set, falls back to the default AWS credential chain
     * (environment variables, instance profile, etc.).
     */
    @Value("${rag.bedrock.access.key.id:}")
    private String bedrockAccessKeyId;

    /**
     * Explicit AWS secret key for Bedrock (optional).
     * If not set, falls back to the default AWS credential chain.
     */
    @Value("${rag.bedrock.secret.access.key:}")
    private String bedrockSecretAccessKey;

    // ========================================
    // TEI (Text Embeddings Inference) Settings
    // ========================================

    @Value("${rag.tei.url:http://tei:80}")
    private String teiUrl;

    @Value("${rag.tei.timeout.connect:5000}")
    private int teiConnectTimeout;

    @Value("${rag.tei.timeout.read:30000}")
    private int teiReadTimeout;

    @Value("${rag.tei.batch.size:32}")
    private int teiBatchSize;

    @Value("${rag.tei.retry.max:3}")
    private int teiMaxRetries;

    @Value("${rag.tei.retry.delay:1000}")
    private int teiRetryDelay;

    // ========================================
    // Chunking Settings
    // ========================================

    @Value("${rag.chunking.max.tokens:512}")
    private int chunkingMaxTokens;

    @Value("${rag.chunking.overlap.tokens:50}")
    private int chunkingOverlapTokens;

    @Value("${rag.chunking.min.tokens:50}")
    private int chunkingMinTokens;

    // ========================================
    // Vector Search Settings
    // ========================================

    @Value("${rag.search.topK:10}")
    private int searchTopK;

    @Value("${rag.search.similarity.threshold:0.7}")
    private float searchSimilarityThreshold;

    // ========================================
    // Property Boost Settings (for weighted search)
    // ========================================

    /**
     * Boost factor for property-based similarity (0.0 to 1.0).
     * Higher values give more weight to metadata (name, description, etc.).
     * Default: 0.3 (30% weight to properties)
     */
    @Value("${rag.search.property.boost:0.3}")
    private float propertyBoost;

    /**
     * Boost factor for content-based similarity (0.0 to 1.0).
     * Higher values give more weight to document body content.
     * Default: 0.7 (70% weight to content)
     */
    @Value("${rag.search.content.boost:0.7}")
    private float contentBoost;

    /**
     * Whether to enable property-based similarity search.
     * When disabled, only content vectors are used for search.
     */
    @Value("${rag.search.property.enabled:true}")
    private boolean propertySearchEnabled;

    // ========================================
    // Property Indexing Settings
    // ========================================

    /**
     * Comma-separated list of CMIS property IDs to include in property embedding.
     * Default: cmis:name (document name) and cmis:description (description).
     * Custom properties can be added (e.g., nemaki:keywords, nemaki:category).
     */
    @Value("${rag.indexing.property.fields:cmis:name,cmis:description}")
    private String propertyFields;

    /**
     * Whether to include custom (non-CMIS standard) properties in the property embedding.
     * When true, all custom string/text properties will be included.
     */
    @Value("${rag.indexing.property.include.custom:false}")
    private boolean includeCustomProperties;

    // ========================================
    // Indexing Settings
    // ========================================

    @Value("${rag.indexing.batch.size:100}")
    private int indexingBatchSize;

    @Value("${rag.indexing.async:true}")
    private boolean indexingAsync;

    /**
     * Solr commitWithin time in milliseconds.
     * Documents will be committed within this time window, allowing Solr to batch commits.
     * Set to 0 or negative for immediate hard commit.
     * Default: 10000ms (10 seconds)
     */
    @Value("${rag.indexing.solr.commitWithin:10000}")
    private int solrCommitWithinMs;

    // ========================================
    // Supported MIME Types for RAG Indexing
    // ========================================

    @Value("${rag.supported.mimetypes:text/plain,text/html,text/xml,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation}")
    private String supportedMimeTypes;

    // ========================================
    // Rate Limiting Settings
    // ========================================

    /**
     * Whether rate limiting is enabled for RAG search API.
     * Default: true (enabled for production safety)
     */
    @Value("${rag.ratelimit.enabled:true}")
    private boolean rateLimitEnabled;

    /**
     * Maximum requests per second allowed per user.
     * Default: 2.0 (allows 2 requests per second)
     */
    @Value("${rag.ratelimit.requests.per.second:2.0}")
    private double rateLimitRequestsPerSecond;

    /**
     * Maximum burst size - how many requests can be made in quick succession.
     * Default: 5 (allows bursting up to 5 requests)
     */
    @Value("${rag.ratelimit.burst.size:5}")
    private int rateLimitBurstSize;

    /**
     * Maximum number of user rate limiters to keep in memory.
     * When exceeded, stale entries are evicted based on last access time.
     * Default: 10000 (enough for most deployments)
     */
    @Value("${rag.ratelimit.max.limiters:10000}")
    private int rateLimitMaxLimiters;

    /**
     * Time-to-live in seconds for idle rate limiter entries.
     * Entries not accessed within this time will be evicted during cleanup.
     * Default: 300 seconds (5 minutes)
     */
    @Value("${rag.ratelimit.cleanup.ttl.seconds:300}")
    private int rateLimitCleanupTtlSeconds;

    // ========================================
    // Search Algorithm Settings
    // ========================================

    /**
     * Multiplier for chunk search topK.
     * When searching for topK documents, search topK * this multiplier chunks.
     * Higher values improve recall but increase processing time.
     * Default: 3 (search 3x more chunks than requested documents)
     */
    @Value("${rag.search.chunk.topk.multiplier:3}")
    private int chunkSearchTopKMultiplier;

    /**
     * Multiplier for property search topK.
     * When doing weighted property search, search topK * this multiplier property vectors.
     * Default: 2
     */
    @Value("${rag.search.property.topk.multiplier:2}")
    private int propertySearchTopKMultiplier;

    /**
     * Maximum number of chunks to update in a single ACL update operation.
     * Documents exceeding this limit will have partial ACL updates with a WARN log.
     * Default 10000 (~5M tokens at 512 tokens/chunk).
     */
    @Value("${rag.acl.chunk.update.limit:10000}")
    private int aclChunkUpdateLimit;

    // ========================================
    // Getters
    // ========================================

    public boolean isEnabled() {
        String val = readDynamic("rag.enabled", null);
        if (val != null) {
            return "true".equalsIgnoreCase(val.trim());
        }
        return enabled;
    }

    public String getEmbeddingProvider() {
        return readDynamic("rag.embedding.provider", embeddingProvider);
    }

    public String getTeiUrl() {
        return readDynamic("rag.tei.url", teiUrl);
    }

    public int getTeiConnectTimeout() {
        return teiConnectTimeout;
    }

    public int getTeiReadTimeout() {
        return teiReadTimeout;
    }

    public int getTeiBatchSize() {
        return teiBatchSize;
    }

    public int getTeiMaxRetries() {
        return teiMaxRetries;
    }

    public int getTeiRetryDelay() {
        return teiRetryDelay;
    }

    public String getBedrockRegion() {
        return readDynamic("rag.bedrock.region", bedrockRegion);
    }

    public String getBedrockModelId() {
        return readDynamic("rag.bedrock.model.id", bedrockModelId);
    }

    public int getBedrockBatchSize() {
        return readDynamicInt("rag.bedrock.batch.size", bedrockBatchSize);
    }

    public int getBedrockMaxInputChars() {
        return readDynamicInt("rag.bedrock.max.input.chars", bedrockMaxInputChars);
    }

    public int getBedrockTimeoutMs() {
        return readDynamicInt("rag.bedrock.timeout.ms", bedrockTimeoutMs);
    }

    public int getBedrockVectorDimension() {
        return readDynamicInt("rag.bedrock.vector.dimension", bedrockVectorDimension);
    }

    public String getBedrockAccessKeyId() {
        return readDynamic("rag.bedrock.access.key.id", bedrockAccessKeyId);
    }

    public String getBedrockSecretAccessKey() {
        return readDynamic("rag.bedrock.secret.access.key", bedrockSecretAccessKey);
    }

    public int getChunkingMaxTokens() {
        return chunkingMaxTokens;
    }

    public int getChunkingOverlapTokens() {
        return chunkingOverlapTokens;
    }

    public int getChunkingMinTokens() {
        return chunkingMinTokens;
    }

    public int getSearchTopK() {
        return searchTopK;
    }

    public float getSearchSimilarityThreshold() {
        return searchSimilarityThreshold;
    }

    public float getPropertyBoost() {
        return propertyBoost;
    }

    public float getContentBoost() {
        return contentBoost;
    }

    public boolean isPropertySearchEnabled() {
        return propertySearchEnabled;
    }

    public String getPropertyFields() {
        return propertyFields;
    }

    public String[] getPropertyFieldsArray() {
        if (propertyFields == null || propertyFields.trim().isEmpty()) {
            return new String[0];
        }
        return propertyFields.split(",");
    }

    public boolean isIncludeCustomProperties() {
        return includeCustomProperties;
    }

    public int getIndexingBatchSize() {
        return indexingBatchSize;
    }

    public boolean isIndexingAsync() {
        return indexingAsync;
    }

    public int getSolrCommitWithinMs() {
        return solrCommitWithinMs;
    }

    public String getSupportedMimeTypes() {
        return supportedMimeTypes;
    }

    public boolean isMimeTypeSupported(String mimeType) {
        if (mimeType == null || supportedMimeTypes == null) {
            return false;
        }
        String[] types = supportedMimeTypes.split(",");
        for (String type : types) {
            if (type.trim().equalsIgnoreCase(mimeType.trim())) {
                return true;
            }
        }
        return false;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public double getRateLimitRequestsPerSecond() {
        return rateLimitRequestsPerSecond;
    }

    public int getRateLimitBurstSize() {
        return rateLimitBurstSize;
    }

    public int getRateLimitMaxLimiters() {
        return rateLimitMaxLimiters;
    }

    public int getRateLimitCleanupTtlSeconds() {
        return rateLimitCleanupTtlSeconds;
    }

    public int getChunkSearchTopKMultiplier() {
        return chunkSearchTopKMultiplier;
    }

    public int getPropertySearchTopKMultiplier() {
        return propertySearchTopKMultiplier;
    }

    public int getAclChunkUpdateLimit() {
        return aclChunkUpdateLimit;
    }
}
