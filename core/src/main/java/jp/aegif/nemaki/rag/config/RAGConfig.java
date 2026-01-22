package jp.aegif.nemaki.rag.config;

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
    // Indexing Settings
    // ========================================

    @Value("${rag.indexing.batch.size:100}")
    private int indexingBatchSize;

    @Value("${rag.indexing.async:true}")
    private boolean indexingAsync;

    // ========================================
    // Supported MIME Types for RAG Indexing
    // ========================================

    @Value("${rag.supported.mimetypes:text/plain,text/html,text/xml,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document}")
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

    public int getIndexingBatchSize() {
        return indexingBatchSize;
    }

    public boolean isIndexingAsync() {
        return indexingAsync;
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
