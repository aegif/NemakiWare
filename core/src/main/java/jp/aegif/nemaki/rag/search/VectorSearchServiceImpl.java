package jp.aegif.nemaki.rag.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.rag.embedding.EmbeddingException;
import jp.aegif.nemaki.rag.embedding.EmbeddingService;

/**
 * Implementation of VectorSearchService using Solr KNN search.
 *
 * Uses Solr 9's Dense Vector Search with Block Join queries to:
 * 1. Find semantically similar chunks via KNN on chunk_vector
 * 2. Find semantically similar documents via KNN on property_vector (weighted)
 * 3. Combine scores with configurable weights
 * 4. Filter by ACL using readers field
 * 5. Join back to parent documents for metadata
 */
@Service
public class VectorSearchServiceImpl implements VectorSearchService {

    private static final Logger log = Logger.getLogger(VectorSearchServiceImpl.class.getName());

    private final RAGConfig ragConfig;
    private final EmbeddingService embeddingService;
    private final ACLExpander aclExpander;

    @Value("${solr.host:solr}")
    private String solrHost;

    @Value("${solr.port:8983}")
    private int solrPort;

    @Value("${solr.protocol:http}")
    private String solrProtocol;

    @Autowired
    public VectorSearchServiceImpl(RAGConfig ragConfig, EmbeddingService embeddingService,
                                   ACLExpander aclExpander) {
        this.ragConfig = ragConfig;
        this.embeddingService = embeddingService;
        this.aclExpander = aclExpander;
        log.info("=== VectorSearchServiceImpl initialized ===");
        log.info("RAGConfig: " + (ragConfig != null ? "present" : "null"));
        log.info("EmbeddingService: " + (embeddingService != null ? "present" : "null"));
        log.info("ACLExpander: " + (aclExpander != null ? "present" : "null"));
    }

    @Override
    public List<VectorSearchResult> search(String repositoryId, String userId, String query, int topK)
            throws VectorSearchException {
        return search(repositoryId, userId, query, topK, ragConfig.getSearchSimilarityThreshold());
    }

    @Override
    public List<VectorSearchResult> search(String repositoryId, String userId, String query,
                                           int topK, float minScore) throws VectorSearchException {
        // Use config defaults for boost factors
        return searchWithBoost(repositoryId, userId, query, topK, minScore,
                ragConfig.getPropertyBoost(), ragConfig.getContentBoost());
    }

    @Override
    public List<VectorSearchResult> searchWithBoost(String repositoryId, String userId, String query,
                                                    int topK, float minScore,
                                                    float propertyBoost, float contentBoost)
            throws VectorSearchException {
        log.info(String.format("searchWithBoost called: repo=%s, user=%s, query=%s, topK=%d, minScore=%.2f",
                repositoryId, userId, query, topK, minScore));

        if (!isEnabled()) {
            log.warning("Vector search is not enabled");
            throw new VectorSearchException("Vector search is not enabled");
        }

        try {
            // Generate query embedding
            log.info("Generating query embedding...");
            float[] queryVector = embeddingService.embedQuery(query);
            log.info(String.format("Query embedding generated: dimension=%d", queryVector.length));

            // Build ACL filter
            String aclFilter = aclExpander.buildReaderFilterQuery(repositoryId, userId);
            log.info(String.format("ACL filter: %s", aclFilter));

            // Execute weighted KNN search
            return executeWeightedKnnSearch(repositoryId, queryVector, aclFilter, null, topK, minScore,
                    propertyBoost, contentBoost);

        } catch (EmbeddingException e) {
            log.log(Level.SEVERE, "Failed to generate query embedding", e);
            throw new VectorSearchException("Failed to generate query embedding", e);
        }
    }

    @Override
    public List<VectorSearchResult> searchInFolder(String repositoryId, String userId, String query,
                                                   String folderId, int topK) throws VectorSearchException {
        if (!isEnabled()) {
            throw new VectorSearchException("Vector search is not enabled");
        }

        try {
            // Generate query embedding
            float[] queryVector = embeddingService.embedQuery(query);

            // Build ACL filter
            String aclFilter = aclExpander.buildReaderFilterQuery(repositoryId, userId);

            // Build folder filter (using Block Join to parent)
            String folderFilter = "{!parent which='doc_type:document'}parent_id:" + folderId;

            // Execute weighted KNN search
            return executeWeightedKnnSearch(repositoryId, queryVector, aclFilter, folderFilter, topK,
                    ragConfig.getSearchSimilarityThreshold(),
                    ragConfig.getPropertyBoost(), ragConfig.getContentBoost());

        } catch (EmbeddingException e) {
            throw new VectorSearchException("Failed to generate query embedding", e);
        }
    }

    @Override
    public boolean isEnabled() {
        return ragConfig.isEnabled() && embeddingService.isHealthy();
    }

    /**
     * Execute weighted KNN search combining property and content similarity.
     * Final score = (propertyBoost × property_score) + (contentBoost × content_score)
     */
    private List<VectorSearchResult> executeWeightedKnnSearch(String repositoryId, float[] queryVector,
                                                               String aclFilter, String additionalFilter,
                                                               int topK, float minScore,
                                                               float propertyBoost, float contentBoost)
            throws VectorSearchException {

        try (SolrClient solrClient = getSolrClient()) {
            String vectorStr = floatArrayToString(queryVector);

            // Map to store document scores: documentId -> {propertyScore, contentScore, bestChunk}
            Map<String, ScoredDocument> documentScores = new HashMap<>();

            // 1. Search chunk_vector for content similarity
            if (contentBoost > 0) {
                searchChunkVectors(solrClient, repositoryId, vectorStr, aclFilter, additionalFilter,
                        topK * 3, documentScores, contentBoost);
            }

            // 2. Search property_vector for property similarity (if enabled)
            if (propertyBoost > 0 && ragConfig.isPropertySearchEnabled()) {
                searchPropertyVectors(solrClient, repositoryId, vectorStr, aclFilter, additionalFilter,
                        topK * 2, documentScores, propertyBoost);
            }

            // 3. Calculate combined scores and create results
            List<VectorSearchResult> results = new ArrayList<>();
            for (Map.Entry<String, ScoredDocument> entry : documentScores.entrySet()) {
                ScoredDocument scoredDoc = entry.getValue();
                float combinedScore = scoredDoc.getCombinedScore();
                float maxRawScore = scoredDoc.getMaxRawScore();

                // Filter by minimum score using raw (unweighted) score
                // This ensures minScore represents actual similarity threshold
                if (maxRawScore < minScore) {
                    log.fine(String.format("Filtering out document %s: maxRawScore=%.4f < minScore=%.4f",
                            entry.getKey(), maxRawScore, minScore));
                    continue;
                }

                VectorSearchResult result = scoredDoc.toResult();
                result.setScore(combinedScore);

                // Enrich with parent document info
                enrichWithParentInfo(solrClient, repositoryId, result);

                results.add(result);
            }

            // Sort by combined score descending
            results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

            // Limit to topK
            if (results.size() > topK) {
                results = results.subList(0, topK);
            }

            log.info(String.format("Weighted vector search returned %d results (minScore=%.2f, propertyBoost=%.2f, contentBoost=%.2f, totalCandidates=%d)",
                    results.size(), minScore, propertyBoost, contentBoost, documentScores.size()));
            return results;

        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to execute weighted vector search", e);
            throw new VectorSearchException("Failed to execute weighted vector search", e);
        }
    }

    /**
     * Search chunk_vector field for content similarity.
     */
    private void searchChunkVectors(SolrClient solrClient, String repositoryId, String vectorStr,
                                    String aclFilter, String additionalFilter, int topK,
                                    Map<String, ScoredDocument> documentScores, float contentBoost) throws Exception {

        log.info(String.format("searchChunkVectors: repo=%s, topK=%d, contentBoost=%.2f", repositoryId, topK, contentBoost));
        log.info(String.format("ACL filter for chunk search: %s", aclFilter));

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery("{!knn f=chunk_vector topK=" + topK + "}" + vectorStr);
        solrQuery.addFilterQuery("doc_type:chunk");
        solrQuery.addFilterQuery("repository_id:" + repositoryId);
        solrQuery.addFilterQuery(aclFilter);

        if (additionalFilter != null && !additionalFilter.isEmpty()) {
            solrQuery.addFilterQuery(additionalFilter);
        }

        solrQuery.setFields("id", "chunk_id", "chunk_index", "chunk_text", "parent_document_id", "score");
        solrQuery.setRows(topK);

        // Use POST to avoid URI Too Long error with large vectors
        QueryResponse response;
        try {
            response = solrClient.query("nemaki", solrQuery, org.apache.solr.client.solrj.SolrRequest.METHOD.POST);
        } catch (Exception e) {
            // Handle case where there are no indexed vectors yet
            log.info("Solr query exception: " + e.getMessage());
            if (e.getMessage() != null && (e.getMessage().contains("undefined field") ||
                    e.getMessage().contains("no indexed vectors") ||
                    e.getMessage().contains("Cannot parse"))) {
                log.info("No RAG chunk vectors indexed yet - returning empty results for chunk search");
                return;
            }
            throw e;
        }
        SolrDocumentList docs = response.getResults();
        log.info(String.format("Chunk search returned %d results", docs.getNumFound()));

        for (SolrDocument doc : docs) {
            String documentId = getStringField(doc, "parent_document_id");
            log.fine(String.format("Processing chunk: parent_document_id=%s", documentId));
            if (documentId == null) continue;

            float score = doc.getFieldValue("score") != null ?
                    ((Number) doc.getFieldValue("score")).floatValue() : 0f;

            // Weight by contentBoost for ranking
            float weightedScore = score * contentBoost;

            ScoredDocument scoredDoc = documentScores.computeIfAbsent(documentId, k -> new ScoredDocument(documentId));

            // Keep track of best chunk for this document (by weighted score for ranking)
            if (scoredDoc.getContentScore() < weightedScore) {
                scoredDoc.setContentScore(weightedScore);
                scoredDoc.setRawContentScore(score);  // Store raw score for filtering
                scoredDoc.setChunkId(getStringField(doc, "chunk_id"));
                scoredDoc.setChunkIndex(getIntField(doc, "chunk_index"));
                scoredDoc.setChunkText(getStringField(doc, "chunk_text"));
            }
        }
    }

    /**
     * Search property_vector field for property similarity.
     */
    private void searchPropertyVectors(SolrClient solrClient, String repositoryId, String vectorStr,
                                       String aclFilter, String additionalFilter, int topK,
                                       Map<String, ScoredDocument> documentScores, float propertyBoost) throws Exception {

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery("{!knn f=property_vector topK=" + topK + "}" + vectorStr);
        solrQuery.addFilterQuery("doc_type:document");
        solrQuery.addFilterQuery("repository_id:" + repositoryId);
        solrQuery.addFilterQuery(aclFilter);
        // Note: Range queries not supported for dense vector fields, KNN will only return docs with vectors

        if (additionalFilter != null && !additionalFilter.isEmpty()) {
            solrQuery.addFilterQuery(additionalFilter);
        }

        solrQuery.setFields("id", "name", "property_text", "score");
        solrQuery.setRows(topK);

        // Use POST to avoid URI Too Long error with large vectors
        QueryResponse response;
        try {
            response = solrClient.query("nemaki", solrQuery, org.apache.solr.client.solrj.SolrRequest.METHOD.POST);
        } catch (Exception e) {
            // Handle case where there are no indexed vectors yet
            log.info("Property Solr query exception: " + e.getMessage());
            if (e.getMessage() != null && (e.getMessage().contains("undefined field") ||
                    e.getMessage().contains("no indexed vectors") ||
                    e.getMessage().contains("Cannot parse") ||
                    e.getMessage().contains("Field Queries are not supported"))) {
                log.info("No RAG property vectors indexed yet - returning empty results for property search");
                return;
            }
            throw e;
        }
        SolrDocumentList docs = response.getResults();
        log.info(String.format("Property search returned %d results", docs.getNumFound()));

        for (SolrDocument doc : docs) {
            String documentId = getStringField(doc, "id");
            if (documentId == null) continue;

            float score = doc.getFieldValue("score") != null ?
                    ((Number) doc.getFieldValue("score")).floatValue() : 0f;

            // Weight by propertyBoost for ranking
            float weightedScore = score * propertyBoost;

            ScoredDocument scoredDoc = documentScores.computeIfAbsent(documentId, k -> new ScoredDocument(documentId));
            scoredDoc.setPropertyScore(weightedScore);
            scoredDoc.setRawPropertyScore(score);  // Store raw score for filtering
            scoredDoc.setDocumentName(getStringField(doc, "name"));
            scoredDoc.setPropertyText(getStringField(doc, "property_text"));
        }
    }

    private void enrichWithParentInfo(SolrClient solrClient, String repositoryId,
                                      VectorSearchResult result) {
        try {
            SolrQuery parentQuery = new SolrQuery();
            parentQuery.setQuery("id:" + result.getDocumentId());
            parentQuery.addFilterQuery("doc_type:document");
            parentQuery.setFields("name", "path", "objecttype");
            parentQuery.setRows(1);

            QueryResponse response = solrClient.query("nemaki", parentQuery);
            SolrDocumentList docs = response.getResults();

            if (docs != null && !docs.isEmpty()) {
                SolrDocument parentDoc = docs.get(0);
                if (result.getDocumentName() == null) {
                    result.setDocumentName(getStringField(parentDoc, "name"));
                }
                result.setPath(getStringField(parentDoc, "path"));
                result.setObjectType(getStringField(parentDoc, "objecttype"));
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to enrich parent info for document: " + result.getDocumentId(), e);
        }
    }

    private String getStringField(SolrDocument doc, String field) {
        Object value = doc.getFieldValue(field);
        return value != null ? value.toString() : null;
    }

    private int getIntField(SolrDocument doc, String field) {
        Object value = doc.getFieldValue(field);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private String floatArrayToString(float[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(array[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private SolrClient getSolrClient() {
        String url = String.format("%s://%s:%d/solr", solrProtocol, solrHost, solrPort);
        return new HttpSolrClient.Builder(url)
                .withConnectionTimeout(30000)
                .withSocketTimeout(30000)
                .build();
    }

    /**
     * Internal class to track document scores during weighted search.
     * Tracks both raw (unweighted) scores for filtering and weighted scores for ranking.
     */
    private static class ScoredDocument {
        private final String documentId;
        private float propertyScore = 0f;      // Weighted property score for ranking
        private float contentScore = 0f;       // Weighted content score for ranking
        private float rawPropertyScore = 0f;   // Raw property score for filtering
        private float rawContentScore = 0f;    // Raw content score for filtering
        private String chunkId;
        private int chunkIndex;
        private String chunkText;
        private String documentName;
        private String propertyText;

        public ScoredDocument(String documentId) {
            this.documentId = documentId;
        }

        public float getPropertyScore() {
            return propertyScore;
        }

        public void setPropertyScore(float propertyScore) {
            this.propertyScore = propertyScore;
        }

        public float getContentScore() {
            return contentScore;
        }

        public void setContentScore(float contentScore) {
            this.contentScore = contentScore;
        }

        public float getRawPropertyScore() {
            return rawPropertyScore;
        }

        public void setRawPropertyScore(float rawPropertyScore) {
            this.rawPropertyScore = rawPropertyScore;
        }

        public float getRawContentScore() {
            return rawContentScore;
        }

        public void setRawContentScore(float rawContentScore) {
            this.rawContentScore = rawContentScore;
        }

        /**
         * Get the combined weighted score for ranking.
         */
        public float getCombinedScore() {
            return propertyScore + contentScore;
        }

        /**
         * Get the maximum raw (unweighted) score for filtering.
         * This represents the best similarity match before boost weighting.
         */
        public float getMaxRawScore() {
            return Math.max(rawPropertyScore, rawContentScore);
        }

        public void setChunkId(String chunkId) {
            this.chunkId = chunkId;
        }

        public void setChunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        public void setChunkText(String chunkText) {
            this.chunkText = chunkText;
        }

        public void setDocumentName(String documentName) {
            this.documentName = documentName;
        }

        public void setPropertyText(String propertyText) {
            this.propertyText = propertyText;
        }

        public VectorSearchResult toResult() {
            VectorSearchResult result = new VectorSearchResult();
            result.setDocumentId(documentId);
            result.setChunkId(chunkId);
            result.setChunkIndex(chunkIndex);
            result.setChunkText(chunkText);
            result.setDocumentName(documentName);
            return result;
        }
    }
}
