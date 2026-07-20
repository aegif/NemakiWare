package jp.aegif.nemaki.rag.indexing;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TextExtractionService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.rag.chunking.ChunkingService;
import jp.aegif.nemaki.rag.chunking.TextChunk;
import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.rag.config.SolrClientProvider;
import jp.aegif.nemaki.rag.embedding.EmbeddingException;
import jp.aegif.nemaki.rag.embedding.EmbeddingService;
import jp.aegif.nemaki.rag.util.SolrQuerySanitizer;

/**
 * Implementation of RAGIndexingService.
 *
 * Indexes documents with Block Join structure for semantic search:
 * - Parent document (doc_type="document"): Contains metadata, document-level vector, and property vector
 * - Child documents (doc_type="chunk"): Contains chunk text and chunk vector
 *
 * Uses Solr's Block Join to efficiently query chunks while filtering by parent ACL.
 * Supports property-based weighted search with separate property_vector field.
 */
@Service
public class RAGIndexingServiceImpl implements RAGIndexingService {

    private static final Logger log = LoggerFactory.getLogger(RAGIndexingServiceImpl.class);

    private static final String DOC_TYPE_DOCUMENT = "document";
    private static final String DOC_TYPE_CHUNK = "chunk";

    /** Prefix for RAG document IDs to avoid collision with normal Solr index documents */
    public static final String RAG_ID_PREFIX = "rag:";

    /** Convert a CMIS object ID to a RAG Solr document ID */
    public static String toRagId(String objectId) {
        return RAG_ID_PREFIX + objectId;
    }

    /** Extract the original CMIS object ID from a RAG Solr document ID */
    public static String fromRagId(String ragId) {
        if (ragId != null && ragId.startsWith(RAG_ID_PREFIX)) {
            return ragId.substring(RAG_ID_PREFIX.length());
        }
        return ragId;
    }

    /**
     * Striped per-document locks serializing Solr block writes (indexToSolr and
     * updateDocumentACL read-rebuild-write) for the same ragId within this JVM.
     * 64 stripes bound memory; hash collisions only over-serialize, never under.
     */
    private final com.google.common.util.concurrent.Striped<java.util.concurrent.locks.Lock> ragBlockLocks =
            com.google.common.util.concurrent.Striped.lock(64);

    private final RAGConfig ragConfig;
    private final EmbeddingService embeddingService;
    private final ChunkingService chunkingService;
    private final TextExtractionService textExtractionService;
    private final ContentService contentService;
    private final ACLExpander aclExpander;
    private final SolrClientProvider solrClientProvider;

    @Autowired
    public RAGIndexingServiceImpl(
            RAGConfig ragConfig,
            EmbeddingService embeddingService,
            ChunkingService chunkingService,
            TextExtractionService textExtractionService,
            ContentService contentService,
            ACLExpander aclExpander,
            SolrClientProvider solrClientProvider) {
        this.ragConfig = ragConfig;
        this.embeddingService = embeddingService;
        this.chunkingService = chunkingService;
        this.textExtractionService = textExtractionService;
        this.contentService = contentService;
        this.aclExpander = aclExpander;
        this.solrClientProvider = solrClientProvider;
    }

    @Override
    public void indexDocument(String repositoryId, Document document) throws RAGIndexingException {
        if (log.isDebugEnabled()) {
            log.debug("RAG indexDocument called for: " + document.getId() + " (" + document.getName() + ")");
        }

        if (!isEnabled()) {
            throw RAGIndexingException.serviceDisabled("RAG indexing is disabled");
        }

        String mimeType = getMimeType(repositoryId, document);
        if (!isMimeTypeSupported(mimeType)) {
            throw RAGIndexingException.unsupportedMimeType(mimeType);
        }

        try {
            // Extract text content
            String textContent = extractText(repositoryId, document);
            if (textContent == null || textContent.trim().isEmpty()) {
                throw RAGIndexingException.noContent("No text content extracted");
            }

            // Chunk the text
            List<TextChunk> chunks = chunkingService.chunk(textContent);
            if (chunks.isEmpty()) {
                throw RAGIndexingException.noContent("chunking produced no results");
            }

            // Generate embeddings for chunks
            List<String> chunkTexts = chunks.stream()
                    .map(TextChunk::getText)
                    .collect(Collectors.toList());
            List<float[]> chunkEmbeddings = embeddingService.embedBatch(chunkTexts, false);

            // Generate document-level embedding (average of first few chunks or title)
            float[] documentEmbedding = generateDocumentEmbedding(document, chunks, chunkEmbeddings);

            // Generate property embedding for weighted search
            String propertyText = extractPropertyText(document);
            float[] propertyEmbedding = null;
            if (ragConfig.isPropertySearchEnabled() && propertyText != null && !propertyText.trim().isEmpty()) {
                propertyEmbedding = embeddingService.embed(propertyText, false);
                if (log.isDebugEnabled()) {
                    log.debug("Generated property embedding for document: " + document.getId());
                }
            }

            // Get readers for ACL pre-expansion
            List<String> readers = getReaders(repositoryId, document);

            // Create and index Solr documents with Block Join structure
            indexToSolr(repositoryId, document, chunks, chunkEmbeddings, documentEmbedding,
                       propertyEmbedding, propertyText, readers);

            log.info(String.format("RAG indexed document %s with %d chunks", document.getId(), chunks.size()));

        } catch (EmbeddingException e) {
            throw RAGIndexingException.embeddingFailed(document.getId(), e);
        } catch (RAGIndexingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to index document: " + document.getId(), e);
            throw RAGIndexingException.solrError(document.getId(), e);
        }
    }

    @Override
    public void indexDocumentsBatch(String repositoryId, List<Document> documents) throws RAGIndexingException {
        for (Document document : documents) {
            try {
                indexDocument(repositoryId, document);
            } catch (RAGIndexingException e) {
                log.error("Failed to index document in batch: " + document.getId(), e);
                // Continue with other documents
            }
        }
    }

    @Override
    public void deleteDocument(String repositoryId, String documentId) throws RAGIndexingException {
        if (!isEnabled()) {
            return;
        }

        try {
            SolrClient solrClient = solrClientProvider.getClient();
            String ragId = toRagId(documentId);
            String sanitizedRagId = SolrQuerySanitizer.escape(ragId);
            // Delete RAG parent document and all children using Block Join delete
            solrClient.deleteByQuery("nemaki", "_root_:" + sanitizedRagId);
            solrClient.commit("nemaki");
            log.info("RAG deleted document and chunks: {} (ragId={})", documentId, ragId);
        } catch (Exception e) {
            throw new RAGIndexingException("Failed to delete document from RAG index: " + documentId, e);
        }
    }

    @Override
    public void updateDocumentACL(String repositoryId, String documentId, List<String> readers) throws RAGIndexingException {
        if (!isEnabled()) {
            return;
        }

        try {
            SolrClient solrClient = solrClientProvider.getClient();
            String ragId = toRagId(documentId);
            String sanitizedRagId = SolrQuerySanitizer.escape(ragId);

            // Block Join constraint: an add (including an atomic update) whose id matches
            // an existing block parent replaces the WHOLE block, deleting all child chunks.
            // Chunks cannot be atomically updated in isolation either (_root_ is not stored,
            // so their block linkage cannot survive a partial re-add). The only safe way to
            // change readers is to rebuild the entire block from stored fields (text and
            // vectors are stored=true, so no re-embedding is needed) and re-add it.
            //
            // The per-ragId lock serializes this read-rebuild-write against concurrent
            // indexToSolr / updateDocumentACL runs for the same document, so a stale
            // snapshot cannot clobber a newer block (single-replica posture; multi-replica
            // RAG limits are documented in MULTI-REPLICA-DEPLOYMENT.md).
            java.util.concurrent.locks.Lock lock = ragBlockLocks.get(ragId);
            lock.lock();
            try {
                // 1. Fetch the parent document with all stored fields
                SolrQuery parentQuery = new SolrQuery();
                parentQuery.setQuery("id:" + sanitizedRagId);
                parentQuery.setFields("*");
                parentQuery.setRows(1);
                SolrDocumentList parentDocs = solrClient.query("nemaki", parentQuery).getResults();
                if (parentDocs == null || parentDocs.isEmpty()) {
                    // Not indexed (yet) — nothing to update; the next indexing run embeds fresh ACL
                    log.debug("RAG ACL update skipped, document not in RAG index: {} (ragId={})",
                            documentId, ragId);
                    return;
                }

                // 2. Fetch ALL chunk documents (paged) and convert page-by-page. A partial
                //    fetch would silently drop the tail chunks on rebuild, so unlike the
                //    previous partial-update strategy the limit is used as a page size only.
                //    Peak memory matches initial indexing (which materializes the same block).
                int pageSize = Math.max(1, ragConfig.getAclChunkUpdateLimit());
                List<SolrInputDocument> children = new ArrayList<>();
                long totalChunks;
                int start = 0;
                do {
                    SolrQuery chunkQuery = new SolrQuery();
                    chunkQuery.setQuery("_root_:" + sanitizedRagId);
                    chunkQuery.addFilterQuery("doc_type:" + DOC_TYPE_CHUNK);
                    chunkQuery.setFields("*");
                    chunkQuery.setSort("chunk_index", SolrQuery.ORDER.asc);
                    chunkQuery.setStart(start);
                    chunkQuery.setRows(pageSize);
                    SolrDocumentList page = solrClient.query("nemaki", chunkQuery).getResults();
                    totalChunks = page == null ? 0 : page.getNumFound();
                    if (page != null) {
                        for (SolrDocument chunkDoc : page) {
                            children.add(copyForReindex(chunkDoc, ragId, readers));
                        }
                    }
                    start += pageSize;
                } while (start < totalChunks);

                // 3. Rebuild the block with the new readers list
                SolrInputDocument parentDoc = copyForReindex(parentDocs.get(0), ragId, readers);
                parentDoc.addChildDocuments(children);

                // 4. Replace the block with a SINGLE add request. Re-adding a root document
                //    cascades deletion of the previous block (delete-by-_root_ on update —
                //    the very behavior that caused the original chunk-loss bug), so no
                //    explicit delete is needed and there is no delete-without-add window
                //    that could lose the document on a mid-operation failure.
                addBlock(solrClient, parentDoc);

                log.info("RAG updated ACL for document and {} chunks: {} (ragId={})",
                        children.size(), documentId, ragId);
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            throw new RAGIndexingException("Failed to update ACL for document: " + documentId, e);
        }
    }

    /**
     * Add a block parent (with child documents) honoring the configured commit
     * policy: commitWithin when configured, otherwise an explicit hard commit.
     * Shared by indexToSolr and updateDocumentACL so their visibility semantics
     * cannot drift.
     */
    private org.apache.solr.client.solrj.response.UpdateResponse addBlock(
            SolrClient solrClient, SolrInputDocument parentDoc) throws Exception {
        UpdateRequest addRequest = new UpdateRequest();
        addRequest.add(parentDoc);
        int commitWithinMs = ragConfig.getSolrCommitWithinMs();
        if (commitWithinMs > 0) {
            addRequest.setCommitWithin(commitWithinMs);
        }
        org.apache.solr.client.solrj.response.UpdateResponse response =
                addRequest.process(solrClient, "nemaki");
        if (commitWithinMs <= 0) {
            solrClient.commit("nemaki");
        }
        return response;
    }

    /**
     * Copy a stored Solr document into a SolrInputDocument for block re-add,
     * replacing the readers field with the given list.
     *
     * Internal fields are excluded: _version_ (optimistic locking token) and
     * _root_ (not stored; re-set explicitly to keep the block linkage).
     */
    private SolrInputDocument copyForReindex(SolrDocument source, String ragId, List<String> readers) {
        SolrInputDocument doc = new SolrInputDocument();
        for (String fieldName : source.getFieldNames()) {
            if ("_version_".equals(fieldName) || "_root_".equals(fieldName)
                    || "readers".equals(fieldName) || "score".equals(fieldName)) {
                continue;
            }
            java.util.Collection<Object> values = source.getFieldValues(fieldName);
            if (values == null || values.isEmpty()) {
                continue;
            }
            if (values.size() == 1) {
                doc.addField(fieldName, values.iterator().next());
            } else {
                // Multi-valued field, or a dense vector returned as element values —
                // re-adding as a List matches the original indexing format
                doc.addField(fieldName, new ArrayList<>(values));
            }
        }
        doc.addField("_root_", ragId);
        for (String reader : readers) {
            doc.addField("readers", reader);
        }
        return doc;
    }

    @Override
    public boolean isEnabled() {
        return ragConfig.isEnabled() && embeddingService.isHealthy();
    }

    @Override
    public boolean isMimeTypeSupported(String mimeType) {
        return ragConfig.isMimeTypeSupported(mimeType);
    }

    /**
     * Extract property text from document for property embedding.
     * Combines configured property fields into a single text for embedding.
     *
     * @param document Document to extract properties from
     * @return Combined property text, or null if no properties available
     */
    private String extractPropertyText(Document document) {
        StringBuilder sb = new StringBuilder();

        String[] propertyFields = ragConfig.getPropertyFieldsArray();

        for (String field : propertyFields) {
            String fieldValue = getPropertyValue(document, field.trim());
            if (fieldValue != null && !fieldValue.trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(fieldValue.trim());
            }
        }

        // Include custom properties if enabled
        if (ragConfig.isIncludeCustomProperties()) {
            String customProps = extractCustomProperties(document);
            if (customProps != null && !customProps.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(customProps);
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Get a property value from document by CMIS property ID.
     */
    private String getPropertyValue(Document document, String propertyId) {
        switch (propertyId) {
            case "cmis:name":
                return document.getName();
            case "cmis:description":
                return document.getDescription();
            case "cmis:createdBy":
                return document.getCreator();
            case "cmis:lastModifiedBy":
                return document.getModifier();
            case "cmis:objectTypeId":
                return document.getObjectType();
            default:
                // Try to get from custom properties
                return getSubTypePropertyValue(document, propertyId);
        }
    }

    /**
     * Extract text from custom (non-standard CMIS) properties.
     */
    private String extractCustomProperties(Document document) {
        List<Property> subTypeProperties = document.getSubTypeProperties();
        if (subTypeProperties == null || subTypeProperties.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (Property prop : subTypeProperties) {
            if (prop != null && prop.getValue() != null) {
                Object value = prop.getValue();
                if (value instanceof String) {
                    String strValue = (String) value;
                    if (!strValue.trim().isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append(" ");
                        }
                        sb.append(strValue.trim());
                    }
                } else if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) value;
                    for (Object item : list) {
                        if (item instanceof String) {
                            String strItem = (String) item;
                            if (!strItem.trim().isEmpty()) {
                                if (sb.length() > 0) {
                                    sb.append(" ");
                                }
                                sb.append(strItem.trim());
                            }
                        }
                    }
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Get property value from subtype properties by property ID.
     */
    private String getSubTypePropertyValue(Document document, String propertyId) {
        List<Property> subTypeProperties = document.getSubTypeProperties();
        if (subTypeProperties == null) {
            return null;
        }

        for (Property prop : subTypeProperties) {
            if (prop != null && propertyId.equals(prop.getKey())) {
                Object value = prop.getValue();
                if (value instanceof String) {
                    return (String) value;
                } else if (value != null) {
                    return value.toString();
                }
            }
        }
        return null;
    }

    private String extractText(String repositoryId, Document document) {
        String attachmentId = document.getAttachmentNodeId();
        if (attachmentId == null) {
            return null;
        }

        try {
            AttachmentNode attachment = contentService.getAttachment(repositoryId, attachmentId);
            if (attachment == null) {
                return null;
            }

            InputStream contentStream = attachment.getInputStream();
            if (contentStream == null) {
                return null;
            }

            try {
                String mimeType = attachment.getMimeType();
                String fileName = attachment.getName();
                return textExtractionService.extractText(contentStream, mimeType, fileName);
            } finally {
                try {
                    contentStream.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract text for document: " + document.getId(), e);
            return null;
        }
    }

    private String getMimeType(String repositoryId, Document document) {
        String attachmentId = document.getAttachmentNodeId();
        if (attachmentId == null) {
            return null;
        }

        try {
            AttachmentNode attachment = contentService.getAttachment(repositoryId, attachmentId);
            return attachment != null ? attachment.getMimeType() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private float[] generateDocumentEmbedding(Document document, List<TextChunk> chunks, List<float[]> chunkEmbeddings) {
        // Use average of first 3 chunk embeddings as document embedding
        int numChunks = Math.min(3, chunkEmbeddings.size());
        int dimension = embeddingService.getVectorDimension();
        float[] avgEmbedding = new float[dimension];

        for (int i = 0; i < numChunks; i++) {
            float[] chunkEmb = chunkEmbeddings.get(i);
            for (int j = 0; j < dimension; j++) {
                avgEmbedding[j] += chunkEmb[j];
            }
        }

        for (int j = 0; j < dimension; j++) {
            avgEmbedding[j] /= numChunks;
        }

        return avgEmbedding;
    }

    private List<String> getReaders(String repositoryId, Document document) {
        return aclExpander.expandToReaders(repositoryId, document);
    }

    private void indexToSolr(String repositoryId, Document document, List<TextChunk> chunks,
                             List<float[]> chunkEmbeddings, float[] documentEmbedding,
                             float[] propertyEmbedding, String propertyText,
                             List<String> readers) throws Exception {

        SolrClient solrClient = solrClientProvider.getClient();
        int commitWithinMs = ragConfig.getSolrCommitWithinMs();

        String ragId = toRagId(document.getId());

        // Create chunk (child) documents
        List<SolrInputDocument> childDocs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            float[] embedding = chunkEmbeddings.get(i);

            SolrInputDocument chunkDoc = new SolrInputDocument();
            String chunkId = chunk.generateChunkId(document.getId());

            chunkDoc.addField("id", chunkId);
            chunkDoc.addField("object_id", chunkId);  // Required field
            chunkDoc.addField("doc_type", DOC_TYPE_CHUNK);
            chunkDoc.addField("parent_document_id", document.getId());
            chunkDoc.addField("chunk_id", chunkId);
            chunkDoc.addField("chunk_index", chunk.getIndex());
            chunkDoc.addField("chunk_text", chunk.getText());
            chunkDoc.addField("chunk_vector", floatArrayToList(embedding));
            chunkDoc.addField("repository_id", repositoryId);
            chunkDoc.addField("_root_", ragId);

            // Copy readers to chunk for filtering
            for (String reader : readers) {
                chunkDoc.addField("readers", reader);
            }

            childDocs.add(chunkDoc);
        }

        // Create parent document
        SolrInputDocument parentDoc = new SolrInputDocument();
        parentDoc.addField("id", ragId);
        parentDoc.addField("doc_type", DOC_TYPE_DOCUMENT);
        parentDoc.addField("repository_id", repositoryId);
        parentDoc.addField("object_id", document.getId());
        parentDoc.addField("name", document.getName());
        parentDoc.addField("document_vector", floatArrayToList(documentEmbedding));
        parentDoc.addField("_root_", ragId);

        // Add document path for navigation/display
        String path = contentService.calculatePath(repositoryId, document);
        if (path != null && !path.isEmpty()) {
            parentDoc.addField("path", path);
        }

        // Add property embedding and text for weighted search
        if (propertyEmbedding != null) {
            parentDoc.addField("property_vector", floatArrayToList(propertyEmbedding));
        }
        if (propertyText != null && !propertyText.isEmpty()) {
            parentDoc.addField("property_text", propertyText);
        }

        if (document.getObjectType() != null) {
            parentDoc.addField("objecttype", document.getObjectType());
        }
        if (document.getParentId() != null) {
            parentDoc.addField("parent_id", document.getParentId());
        }

        // Add readers for ACL filtering
        for (String reader : readers) {
            parentDoc.addField("readers", reader);
        }

        // Add children to parent for Block Join
        parentDoc.addChildDocuments(childDocs);

        // Serialize block writes per ragId against concurrent updateDocumentACL /
        // indexToSolr runs for the same document (see ragBlockLocks)
        java.util.concurrent.locks.Lock lock = ragBlockLocks.get(ragId);
        lock.lock();
        try {
            // Step 1: Delete existing RAG document and its chunks
            String sanitizedRagId = SolrQuerySanitizer.escape(ragId);
            UpdateRequest deleteRequest = new UpdateRequest();
            deleteRequest.deleteByQuery("_root_:" + sanitizedRagId);
            deleteRequest.process(solrClient, "nemaki");

            // Step 2: Add parent document with child chunks (Block Join)
            // Separated from delete because SolrJ's UpdateRequest may not correctly
            // serialize Block Join (addChildDocuments) when combined with deleteByQuery
            // in the same request.
            log.info("[RAG SOLR] Sending add request for document: {} (ragId={}) with {} chunks, commitWithin={}",
                    document.getId(), ragId, childDocs.size(), commitWithinMs);
            var response = addBlock(solrClient, parentDoc);
            log.info("[RAG SOLR] Add response status: {} for document: {}",
                    response.getStatus(), document.getId());
        } catch (Exception e) {
            log.error("[RAG SOLR] Index operation failed for document: " + document.getId() +
                      ". The document may need to be re-indexed.", e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    private List<Float> floatArrayToList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }
}
