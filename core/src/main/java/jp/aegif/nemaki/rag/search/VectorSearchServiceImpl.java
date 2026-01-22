package jp.aegif.nemaki.rag.search;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
 * 2. Filter by ACL using readers field
 * 3. Join back to parent documents for metadata
 */
@Service
public class VectorSearchServiceImpl implements VectorSearchService {

    private static final Log log = LogFactory.getLog(VectorSearchServiceImpl.class);

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
    }

    @Override
    public List<VectorSearchResult> search(String repositoryId, String userId, String query, int topK)
            throws VectorSearchException {
        return search(repositoryId, userId, query, topK, ragConfig.getSearchSimilarityThreshold());
    }

    @Override
    public List<VectorSearchResult> search(String repositoryId, String userId, String query,
                                           int topK, float minScore) throws VectorSearchException {
        if (!isEnabled()) {
            throw new VectorSearchException("Vector search is not enabled");
        }

        try {
            // Generate query embedding
            float[] queryVector = embeddingService.embedQuery(query);

            // Build ACL filter
            String aclFilter = aclExpander.buildReaderFilterQuery(repositoryId, userId);

            // Execute KNN search
            return executeKnnSearch(repositoryId, queryVector, aclFilter, null, topK, minScore);

        } catch (EmbeddingException e) {
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

            // Execute KNN search
            return executeKnnSearch(repositoryId, queryVector, aclFilter, folderFilter, topK,
                    ragConfig.getSearchSimilarityThreshold());

        } catch (EmbeddingException e) {
            throw new VectorSearchException("Failed to generate query embedding", e);
        }
    }

    @Override
    public boolean isEnabled() {
        return ragConfig.isEnabled() && embeddingService.isHealthy();
    }

    private List<VectorSearchResult> executeKnnSearch(String repositoryId, float[] queryVector,
                                                      String aclFilter, String additionalFilter,
                                                      int topK, float minScore) throws VectorSearchException {
        try (SolrClient solrClient = getSolrClient()) {
            // Build KNN query for chunk vectors
            String vectorStr = floatArrayToString(queryVector);

            // Solr 9 KNN query syntax
            // Search chunks, then use Block Join to get parent info
            SolrQuery solrQuery = new SolrQuery();

            // KNN query on chunk_vector field
            solrQuery.setQuery("{!knn f=chunk_vector topK=" + (topK * 2) + "}" + vectorStr);

            // Filter to only chunk documents
            solrQuery.addFilterQuery("doc_type:chunk");
            solrQuery.addFilterQuery("repository_id:" + repositoryId);

            // Apply ACL filter
            solrQuery.addFilterQuery(aclFilter);

            // Apply additional filter if provided
            if (additionalFilter != null && !additionalFilter.isEmpty()) {
                solrQuery.addFilterQuery(additionalFilter);
            }

            // Return fields
            solrQuery.setFields("id", "chunk_id", "chunk_index", "chunk_text", "parent_document_id",
                    "score", "[child parentFilter=doc_type:document childFilter=doc_type:chunk limit=1]");

            solrQuery.setRows(topK * 2);  // Get more than needed to filter by score

            // Execute query
            QueryResponse response = solrClient.query("nemaki", solrQuery);
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
                result.setChunkId(getStringField(doc, "chunk_id"));
                result.setChunkIndex(getIntField(doc, "chunk_index"));
                result.setChunkText(getStringField(doc, "chunk_text"));
                result.setDocumentId(getStringField(doc, "parent_document_id"));
                result.setScore(score);

                // Get parent document info via separate query
                enrichWithParentInfo(solrClient, repositoryId, result);

                results.add(result);

                if (results.size() >= topK) {
                    break;
                }
            }

            log.info(String.format("Vector search returned %d results for query", results.size()));
            return results;

        } catch (Exception e) {
            throw new VectorSearchException("Failed to execute vector search", e);
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
                result.setDocumentName(getStringField(parentDoc, "name"));
                result.setPath(getStringField(parentDoc, "path"));
                result.setObjectType(getStringField(parentDoc, "objecttype"));
            }
        } catch (Exception e) {
            log.warn("Failed to enrich parent info for document: " + result.getDocumentId(), e);
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
}
