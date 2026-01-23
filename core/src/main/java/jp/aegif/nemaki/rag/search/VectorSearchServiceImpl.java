package jp.aegif.nemaki.rag.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.rag.config.SolrClientProvider;
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

    private static final Log log = LogFactory.getLog(VectorSearchServiceImpl.class);

    /**
     * Multiplier for chunk vector search topK.
     * We fetch more chunks than final topK because:
     * - Multiple chunks may belong to the same document (only best one is kept)
     * - We need candidates for score combination with property search
     * - Higher multiplier = better recall but more processing
     * Value of 3 provides good balance between recall and performance.
     */
    private static final int CHUNK_SEARCH_TOPK_MULTIPLIER = 3;

    /**
     * Multiplier for property vector search topK.
     * Lower than chunk multiplier because:
     * - Property search returns document-level results (no deduplication needed)
     * - Used primarily for boosting relevance, not primary recall
     * Value of 2 provides enough candidates for score combination.
     */
    private static final int PROPERTY_SEARCH_TOPK_MULTIPLIER = 2;

    private final RAGConfig ragConfig;
    private final EmbeddingService embeddingService;
    private final ACLExpander aclExpander;
    private final SolrClientProvider solrClientProvider;

    @Autowired
    public VectorSearchServiceImpl(RAGConfig ragConfig, EmbeddingService embeddingService,
                                   ACLExpander aclExpander, SolrClientProvider solrClientProvider) {
        this.ragConfig = ragConfig;
        this.embeddingService = embeddingService;
        this.aclExpander = aclExpander;
        this.solrClientProvider = solrClientProvider;
        if (log.isDebugEnabled()) {
            log.debug("VectorSearchServiceImpl initialized");
        }
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
        if (log.isDebugEnabled()) {
            // Don't log query content at INFO/DEBUG to avoid PII exposure
            log.debug(String.format("searchWithBoost called: repo=%s, topK=%d, minScore=%.2f",
                    repositoryId, topK, minScore));
        }

        if (!isEnabled()) {
            log.warn("Vector search is not enabled");
            throw new VectorSearchException("Vector search is not enabled");
        }

        // Validate API-provided boost values
        if (propertyBoost < 0.0f || propertyBoost > 1.0f) {
            throw new VectorSearchException("propertyBoost must be between 0.0 and 1.0, got: " + propertyBoost);
        }
        if (contentBoost < 0.0f || contentBoost > 1.0f) {
            throw new VectorSearchException("contentBoost must be between 0.0 and 1.0, got: " + contentBoost);
        }
        if (propertyBoost == 0.0f && contentBoost == 0.0f) {
            throw new VectorSearchException("At least one of propertyBoost or contentBoost must be greater than 0");
        }

        try {
            // Generate query embedding
            float[] queryVector = embeddingService.embedQuery(query);

            // Build ACL filter
            String aclFilter = aclExpander.buildReaderFilterQuery(repositoryId, userId);

            // Execute weighted KNN search
            return executeWeightedKnnSearch(repositoryId, queryVector, aclFilter, null, topK, minScore,
                    propertyBoost, contentBoost);

        } catch (EmbeddingException e) {
            log.error("Failed to generate query embedding", e);
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


    @Override
    public List<VectorSearchResult> findSimilarDocuments(String repositoryId, String userId,
                                                          String documentId, int topK, float minScore)
            throws VectorSearchException {
        if (log.isDebugEnabled()) {
            log.debug(String.format("findSimilarDocuments called: repo=%s, docId=%s, topK=%d, minScore=%.2f",
                    repositoryId, documentId, topK, minScore));
        }

        if (!isEnabled()) {
            log.warn("Vector search is not enabled");
            throw new VectorSearchException("Vector search is not enabled");
        }

        try {
            SolrClient solrClient = solrClientProvider.getClient();

            // 1. Retrieve the source document's vector from Solr
            float[] documentVector = getDocumentVector(solrClient, documentId);
            if (documentVector == null) {
                throw new VectorSearchException("Document not found in RAG index: " + documentId);
            }

            // 2. Build ACL filter
            String aclFilter = aclExpander.buildReaderFilterQuery(repositoryId, userId);

            // 3. Execute KNN search using the document vector
            // Request topK + 1 to account for the source document
            List<VectorSearchResult> results = executeDocumentVectorSearch(
                    solrClient, repositoryId, documentVector, aclFilter, topK + 1, minScore);

            // 4. Filter out the source document from results
            results.removeIf(r -> documentId.equals(r.getDocumentId()));

            // 5. Limit to topK after filtering
            if (results.size() > topK) {
                results = new ArrayList<>(results.subList(0, topK));
            }

            if (log.isDebugEnabled()) {
                log.debug(String.format("findSimilarDocuments returned %d results for document %s",
                        results.size(), documentId));
            }
            return results;

        } catch (VectorSearchException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to find similar documents for: " + documentId, e);
            throw new VectorSearchException("Failed to find similar documents", e);
        }
    }

    /**
     * Retrieve the document_vector for a specific document from Solr.
     *
     * @param solrClient Solr client
     * @param documentId Document ID
     * @return document_vector as float array, or null if not found
     */
    private float[] getDocumentVector(SolrClient solrClient, String documentId) throws Exception {
        SolrQuery query = new SolrQuery();
        query.setQuery("id:" + documentId);
        query.addFilterQuery("doc_type:document");
        query.setFields("id", "document_vector");
        query.setRows(1);

        QueryResponse response = solrClient.query("nemaki", query);
        SolrDocumentList docs = response.getResults();

        if (docs == null || docs.isEmpty()) {
            return null;
        }

        SolrDocument doc = docs.get(0);
        Object vectorObj = doc.getFieldValue("document_vector");

        if (vectorObj == null) {
            return null;
        }

        // document_vector is stored as List<Float> in Solr
        if (vectorObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Number> vectorList = (List<Number>) vectorObj;
            float[] vector = new float[vectorList.size()];
            for (int i = 0; i < vectorList.size(); i++) {
                vector[i] = vectorList.get(i).floatValue();
            }
            return vector;
        }

        return null;
    }

    /**
     * Execute KNN search using document_vector to find similar documents.
     *
     * @param solrClient Solr client
     * @param repositoryId Repository ID
     * @param documentVector Vector to search with
     * @param aclFilter ACL filter query
     * @param topK Maximum results
     * @param minScore Minimum similarity score
     * @return List of search results
     */
    private List<VectorSearchResult> executeDocumentVectorSearch(SolrClient solrClient, String repositoryId,
                                                                  float[] documentVector, String aclFilter,
                                                                  int topK, float minScore) throws Exception {
        String vectorStr = floatArrayToString(documentVector);

        SolrQuery solrQuery = new SolrQuery();
        // Search on document_vector (parent documents)
        solrQuery.setQuery("{!knn f=document_vector topK=" + topK + "}" + vectorStr);
        solrQuery.addFilterQuery("doc_type:document");
        solrQuery.addFilterQuery("repository_id:" + repositoryId);
        solrQuery.addFilterQuery(aclFilter);
        solrQuery.setFields("id", "name", "path", "objecttype", "score");
        solrQuery.setRows(topK);

        QueryResponse response;
        try {
            response = solrClient.query("nemaki", solrQuery, org.apache.solr.client.solrj.SolrRequest.METHOD.POST);
        } catch (Exception e) {
            if (e.getMessage() != null && (e.getMessage().contains("undefined field") ||
                    e.getMessage().contains("no indexed vectors") ||
                    e.getMessage().contains("Cannot parse"))) {
                if (log.isDebugEnabled()) {
                    log.debug("No RAG document vectors indexed yet - returning empty results");
                }
                return new ArrayList<>();
            }
            throw e;
        }

        SolrDocumentList docs = response.getResults();
        List<VectorSearchResult> results = new ArrayList<>();

        for (SolrDocument doc : docs) {
            float score = doc.getFieldValue("score") != null ?
                    ((Number) doc.getFieldValue("score")).floatValue() : 0f;

            // Filter by minimum score
            if (score < minScore) {
                continue;
            }

            VectorSearchResult result = new VectorSearchResult();
            result.setDocumentId(getStringField(doc, "id"));
            result.setDocumentName(getStringField(doc, "name"));
            result.setPath(getStringField(doc, "path"));
            result.setObjectType(getStringField(doc, "objecttype"));
            result.setScore(score);

            // For similar documents, we use the first chunk as representative text
            enrichWithFirstChunk(solrClient, result);

            results.add(result);
        }

        return results;
    }

    /**
     * Enrich a result with the first chunk's information.
     * This provides context text for display in similar documents list.
     */
    private void enrichWithFirstChunk(SolrClient solrClient, VectorSearchResult result) {
        try {
            SolrQuery query = new SolrQuery();
            query.setQuery("parent_document_id:" + result.getDocumentId());
            query.addFilterQuery("doc_type:chunk");
            query.setFields("chunk_id", "chunk_index", "chunk_text");
            query.setSort("chunk_index", SolrQuery.ORDER.asc);
            query.setRows(1);

            QueryResponse response = solrClient.query("nemaki", query);
            SolrDocumentList docs = response.getResults();

            if (docs != null && !docs.isEmpty()) {
                SolrDocument chunkDoc = docs.get(0);
                result.setChunkId(getStringField(chunkDoc, "chunk_id"));
                result.setChunkIndex(getIntField(chunkDoc, "chunk_index"));
                result.setChunkText(getStringField(chunkDoc, "chunk_text"));
            }
        } catch (Exception e) {
            log.warn("Failed to enrich similar document with chunk info: " + result.getDocumentId(), e);
        }
    }

    /**
     * Execute weighted KNN search combining property and content similarity.
     * Final score = (propertyBoost × property_score) + (contentBoost × content_score)
     *
     * KNN queries are executed in parallel for better performance.
     */
    private List<VectorSearchResult> executeWeightedKnnSearch(String repositoryId, float[] queryVector,
                                                               String aclFilter, String additionalFilter,
                                                               int topK, float minScore,
                                                               float propertyBoost, float contentBoost)
            throws VectorSearchException {

        try {
            SolrClient solrClient = solrClientProvider.getClient();
            String vectorStr = floatArrayToString(queryVector);

            // Use ConcurrentHashMap for thread-safe parallel access
            Map<String, ScoredDocument> documentScores = new ConcurrentHashMap<>();

            // Exception holders for parallel search error handling
            AtomicReference<Exception> chunkSearchException = new AtomicReference<>();
            AtomicReference<Exception> propertySearchException = new AtomicReference<>();
            boolean chunkSearchEnabled = contentBoost > 0;
            boolean propertySearchEnabled = propertyBoost > 0 && ragConfig.isPropertySearchEnabled();

            // Execute KNN searches in parallel for better performance
            CompletableFuture<Void> chunkSearchFuture = CompletableFuture.completedFuture(null);
            CompletableFuture<Void> propertySearchFuture = CompletableFuture.completedFuture(null);

            // 1. Search chunk_vector for content similarity (async)
            if (chunkSearchEnabled) {
                chunkSearchFuture = CompletableFuture.runAsync(() -> {
                    try {
                        searchChunkVectors(solrClient, repositoryId, vectorStr, aclFilter, additionalFilter,
                                topK * CHUNK_SEARCH_TOPK_MULTIPLIER, documentScores, contentBoost);
                    } catch (Exception e) {
                        log.error("Chunk vector search failed", e);
                        chunkSearchException.set(e);
                    }
                });
            }

            // 2. Search property_vector for property similarity (async, if enabled)
            if (propertySearchEnabled) {
                propertySearchFuture = CompletableFuture.runAsync(() -> {
                    try {
                        searchPropertyVectors(solrClient, repositoryId, vectorStr, aclFilter, additionalFilter,
                                topK * PROPERTY_SEARCH_TOPK_MULTIPLIER, documentScores, propertyBoost);
                    } catch (Exception e) {
                        log.error("Property vector search failed", e);
                        propertySearchException.set(e);
                    }
                });
            }

            // Wait for both searches to complete
            CompletableFuture.allOf(chunkSearchFuture, propertySearchFuture).join();

            // Check if all enabled searches failed - if so, propagate exception
            boolean chunkFailed = chunkSearchEnabled && chunkSearchException.get() != null;
            boolean propertyFailed = propertySearchEnabled && propertySearchException.get() != null;

            if (chunkSearchEnabled && propertySearchEnabled && chunkFailed && propertyFailed) {
                // Both searches were enabled and both failed
                throw new VectorSearchException("Both chunk and property vector searches failed",
                        chunkSearchException.get());
            } else if (chunkSearchEnabled && !propertySearchEnabled && chunkFailed) {
                // Only chunk search was enabled and it failed
                throw new VectorSearchException("Chunk vector search failed", chunkSearchException.get());
            } else if (!chunkSearchEnabled && propertySearchEnabled && propertyFailed) {
                // Only property search was enabled and it failed
                throw new VectorSearchException("Property vector search failed", propertySearchException.get());
            }
            // If at least one search succeeded, continue with partial results

            // 3. Calculate combined scores and create results
            List<VectorSearchResult> results = new ArrayList<>();
            for (Map.Entry<String, ScoredDocument> entry : documentScores.entrySet()) {
                ScoredDocument scoredDoc = entry.getValue();
                float combinedScore = scoredDoc.getCombinedScore();
                float maxRawScore = scoredDoc.getMaxRawScore();

                // Filter by minimum score using raw (unweighted) score
                // This ensures minScore represents actual similarity threshold
                if (maxRawScore < minScore) {
                    if (log.isTraceEnabled()) {
                        log.trace(String.format("Filtering out document %s: maxRawScore=%.4f < minScore=%.4f",
                                entry.getKey(), maxRawScore, minScore));
                    }
                    continue;
                }

                VectorSearchResult result = scoredDoc.toResult();
                result.setScore(combinedScore);
                results.add(result);
            }

            // Sort by combined score descending
            results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

            // Limit to topK
            if (results.size() > topK) {
                results = results.subList(0, topK);
            }

            // Batch enrich with parent document info (fixes N+1 query problem)
            enrichResultsWithParentInfo(solrClient, repositoryId, results);

            if (log.isDebugEnabled()) {
                log.debug(String.format("Weighted vector search returned %d results (minScore=%.2f, propertyBoost=%.2f, contentBoost=%.2f, totalCandidates=%d)",
                        results.size(), minScore, propertyBoost, contentBoost, documentScores.size()));
            }
            return results;

        } catch (Exception e) {
            log.error("Failed to execute weighted vector search", e);
            throw new VectorSearchException("Failed to execute weighted vector search", e);
        }
    }

    /**
     * Search chunk_vector field for content similarity.
     */
    private void searchChunkVectors(SolrClient solrClient, String repositoryId, String vectorStr,
                                    String aclFilter, String additionalFilter, int topK,
                                    Map<String, ScoredDocument> documentScores, float contentBoost) throws Exception {

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
            if (e.getMessage() != null && (e.getMessage().contains("undefined field") ||
                    e.getMessage().contains("no indexed vectors") ||
                    e.getMessage().contains("Cannot parse"))) {
                if (log.isDebugEnabled()) {
                    log.debug("No RAG chunk vectors indexed yet - returning empty results for chunk search");
                }
                return;
            }
            throw e;
        }
        SolrDocumentList docs = response.getResults();
        if (log.isDebugEnabled()) {
            log.debug(String.format("Chunk search returned %d results", docs.getNumFound()));
        }

        for (SolrDocument doc : docs) {
            String documentId = getStringField(doc, "parent_document_id");
            if (documentId == null) continue;

            float score = doc.getFieldValue("score") != null ?
                    ((Number) doc.getFieldValue("score")).floatValue() : 0f;

            // Weight by contentBoost for ranking
            float weightedScore = score * contentBoost;

            ScoredDocument scoredDoc = documentScores.computeIfAbsent(documentId, k -> new ScoredDocument(documentId));

            // Keep track of best chunk for this document (by weighted score for ranking)
            // Synchronized to prevent race condition in compare-then-update pattern
            synchronized (scoredDoc) {
                if (scoredDoc.getContentScore() < weightedScore) {
                    scoredDoc.setContentScore(weightedScore);
                    scoredDoc.setRawContentScore(score);  // Store raw score for filtering
                    scoredDoc.setChunkId(getStringField(doc, "chunk_id"));
                    scoredDoc.setChunkIndex(getIntField(doc, "chunk_index"));
                    scoredDoc.setChunkText(getStringField(doc, "chunk_text"));
                }
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
            if (e.getMessage() != null && (e.getMessage().contains("undefined field") ||
                    e.getMessage().contains("no indexed vectors") ||
                    e.getMessage().contains("Cannot parse") ||
                    e.getMessage().contains("Field Queries are not supported"))) {
                if (log.isDebugEnabled()) {
                    log.debug("No RAG property vectors indexed yet - returning empty results for property search");
                }
                return;
            }
            throw e;
        }
        SolrDocumentList docs = response.getResults();
        if (log.isDebugEnabled()) {
            log.debug(String.format("Property search returned %d results", docs.getNumFound()));
        }

        for (SolrDocument doc : docs) {
            String documentId = getStringField(doc, "id");
            if (documentId == null) continue;

            float score = doc.getFieldValue("score") != null ?
                    ((Number) doc.getFieldValue("score")).floatValue() : 0f;

            // Weight by propertyBoost for ranking
            float weightedScore = score * propertyBoost;

            ScoredDocument scoredDoc = documentScores.computeIfAbsent(documentId, k -> new ScoredDocument(documentId));
            // Synchronized to ensure atomic update of all property-related fields
            synchronized (scoredDoc) {
                scoredDoc.setPropertyScore(weightedScore);
                scoredDoc.setRawPropertyScore(score);  // Store raw score for filtering
                scoredDoc.setDocumentName(getStringField(doc, "name"));
                scoredDoc.setPropertyText(getStringField(doc, "property_text"));
            }
        }
    }

    /**
     * Batch enrich results with parent document info.
     * Fetches all parent documents in a single Solr query to avoid N+1 problem.
     *
     * @param solrClient Solr client
     * @param repositoryId Repository ID
     * @param results List of results to enrich
     */
    private void enrichResultsWithParentInfo(SolrClient solrClient, String repositoryId,
                                              List<VectorSearchResult> results) {
        if (results.isEmpty()) {
            return;
        }

        try {
            // Collect all document IDs that need enrichment
            List<String> documentIds = new ArrayList<>();
            for (VectorSearchResult result : results) {
                if (result.getDocumentId() != null) {
                    documentIds.add(result.getDocumentId());
                }
            }

            if (documentIds.isEmpty()) {
                return;
            }

            // Build a single query for all documents
            StringBuilder queryBuilder = new StringBuilder("id:(");
            for (int i = 0; i < documentIds.size(); i++) {
                if (i > 0) {
                    queryBuilder.append(" OR ");
                }
                queryBuilder.append(documentIds.get(i));
            }
            queryBuilder.append(")");

            SolrQuery batchQuery = new SolrQuery();
            batchQuery.setQuery(queryBuilder.toString());
            batchQuery.addFilterQuery("doc_type:document");
            batchQuery.setFields("id", "name", "path", "objecttype");
            batchQuery.setRows(documentIds.size());

            QueryResponse response = solrClient.query("nemaki", batchQuery);
            SolrDocumentList docs = response.getResults();

            // Build a map for quick lookup
            Map<String, SolrDocument> parentDocs = new HashMap<>();
            if (docs != null) {
                for (SolrDocument doc : docs) {
                    String id = getStringField(doc, "id");
                    if (id != null) {
                        parentDocs.put(id, doc);
                    }
                }
            }

            // Enrich each result
            for (VectorSearchResult result : results) {
                SolrDocument parentDoc = parentDocs.get(result.getDocumentId());
                if (parentDoc != null) {
                    if (result.getDocumentName() == null) {
                        result.setDocumentName(getStringField(parentDoc, "name"));
                    }
                    result.setPath(getStringField(parentDoc, "path"));
                    result.setObjectType(getStringField(parentDoc, "objecttype"));
                }
            }

            if (log.isDebugEnabled()) {
                log.debug(String.format("Batch enriched %d results with parent info (fetched %d parent docs)",
                        results.size(), parentDocs.size()));
            }

        } catch (Exception e) {
            log.warn("Failed to batch enrich parent info for " + results.size() + " results", e);
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

}
