package jp.aegif.nemaki.rag.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration class for RAG (Retrieval-Augmented Generation) features.
 *
 * All RAG-related settings are controlled through this class.
 * Settings can be configured via system properties or nemakiware.properties.
 */
@Component
public class RAGConfig {

    private static final Logger log = LoggerFactory.getLogger(RAGConfig.class);

    @PostConstruct
    public void init() {
        log.info("=== RAGConfig initialized ===");
        log.info("RAG enabled: {}", enabled);
        log.info("TEI URL: {}", teiUrl);
        log.info("TEI connect timeout: {}", teiConnectTimeout);
        log.info("TEI read timeout: {}", teiReadTimeout);

        // Validate boost values (0.0 to 1.0 range)
        if (propertyBoost < 0.0f || propertyBoost > 1.0f) {
            log.warn("Invalid rag.search.property.boost value: {}. Must be between 0.0 and 1.0. Using default 0.3", propertyBoost);
            propertyBoost = 0.3f;
        }
        if (contentBoost < 0.0f || contentBoost > 1.0f) {
            log.warn("Invalid rag.search.content.boost value: {}. Must be between 0.0 and 1.0. Using default 0.7", contentBoost);
            contentBoost = 0.7f;
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
    // Getters
    // ========================================

    public boolean isEnabled() {
        return enabled;
    }

    public String getTeiUrl() {
        return teiUrl;
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
}
