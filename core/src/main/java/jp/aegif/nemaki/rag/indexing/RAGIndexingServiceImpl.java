package jp.aegif.nemaki.rag.indexing;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import jp.aegif.nemaki.rag.embedding.EmbeddingException;
import jp.aegif.nemaki.rag.embedding.EmbeddingService;

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

    private static final Log log = LogFactory.getLog(RAGIndexingServiceImpl.class);

    private static final String DOC_TYPE_DOCUMENT = "document";
    private static final String DOC_TYPE_CHUNK = "chunk";

    private final RAGConfig ragConfig;
    private final EmbeddingService embeddingService;
    private final ChunkingService chunkingService;
    private final TextExtractionService textExtractionService;
    private final ContentService contentService;
    private final ACLExpander aclExpander;

    @Value("${solr.host:solr}")
    private String solrHost;

    @Value("${solr.port:8983}")
    private int solrPort;

    @Value("${solr.protocol:http}")
    private String solrProtocol;

    @Autowired
    public RAGIndexingServiceImpl(
            RAGConfig ragConfig,
            EmbeddingService embeddingService,
            ChunkingService chunkingService,
            TextExtractionService textExtractionService,
            ContentService contentService,
            ACLExpander aclExpander) {
        this.ragConfig = ragConfig;
        this.embeddingService = embeddingService;
        this.chunkingService = chunkingService;
        this.textExtractionService = textExtractionService;
        this.contentService = contentService;
        this.aclExpander = aclExpander;
    }

    @Override
    public void indexDocument(String repositoryId, Document document) throws RAGIndexingException {
        System.out.println("=== RAG indexDocument CALLED for: " + document.getId() + " (" + document.getName() + ") ===");
        log.info("RAG indexDocument called for: " + document.getId() + " (" + document.getName() + ")");

        if (!isEnabled()) {
            log.info("RAG indexing is disabled, skipping document: " + document.getId());
            throw new RAGIndexingException("RAG indexing is disabled");
        }

        String mimeType = getMimeType(repositoryId, document);
        log.info("Document MIME type: " + mimeType);
        if (!isMimeTypeSupported(mimeType)) {
            log.info("MIME type not supported for RAG indexing: " + mimeType);
            throw new RAGIndexingException("MIME type not supported: " + mimeType);
        }

        try {
            // Extract text content
            System.out.println("=== Starting text extraction for: " + document.getId() + " ===");
            String textContent = extractText(repositoryId, document);
            System.out.println("=== Text extraction completed, length: " + (textContent != null ? textContent.length() : "null") + " ===");
            if (textContent == null || textContent.trim().isEmpty()) {
                System.out.println("=== No text content extracted for: " + document.getId() + " ===");
                throw new RAGIndexingException("No text content extracted");
            }

            // Chunk the text
            System.out.println("=== Starting chunking for: " + document.getId() + " ===");
            System.out.println("=== Text length: " + textContent.length() + " chars ===");
            System.out.println("=== ChunkingService class: " + chunkingService.getClass().getName() + " ===");
            List<TextChunk> chunks;
            try {
                long start = System.currentTimeMillis();
                chunks = chunkingService.chunk(textContent);
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("=== Chunking completed in " + elapsed + "ms, chunks: " + chunks.size() + " ===");
            } catch (Exception e) {
                System.out.println("=== CHUNKING EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage() + " ===");
                e.printStackTrace();
                throw e;
            }
            if (chunks.isEmpty()) {
                System.out.println("=== No chunks generated for: " + document.getId() + " ===");
                throw new RAGIndexingException("No text content: chunking produced no results");
            }

            // Generate embeddings for chunks
            System.out.println("=== Starting embedding generation for " + chunks.size() + " chunks ===");
            List<String> chunkTexts = chunks.stream()
                    .map(TextChunk::getText)
                    .collect(Collectors.toList());
            System.out.println("=== Chunk texts prepared, calling embeddingService.embedBatch ===");
            long embedStart = System.currentTimeMillis();
            List<float[]> chunkEmbeddings = embeddingService.embedBatch(chunkTexts, false);
            long embedElapsed = System.currentTimeMillis() - embedStart;
            System.out.println("=== Embedding generation completed in " + embedElapsed + "ms, embeddings: " + chunkEmbeddings.size() + " ===");

            // Generate document-level embedding (average of first few chunks or title)
            System.out.println("=== Generating document-level embedding ===");
            float[] documentEmbedding = generateDocumentEmbedding(document, chunks, chunkEmbeddings);
            System.out.println("=== Document embedding generated ===");

            // Generate property embedding for weighted search
            System.out.println("=== Extracting property text ===");
            String propertyText = extractPropertyText(document);
            float[] propertyEmbedding = null;
            if (ragConfig.isPropertySearchEnabled() && propertyText != null && !propertyText.trim().isEmpty()) {
                propertyEmbedding = embeddingService.embed(propertyText, false);
                log.debug("Generated property embedding for document: " + document.getId());
            }
            System.out.println("=== Property processing complete ===");

            // Get readers for ACL pre-expansion
            System.out.println("=== Getting readers for ACL ===");
            List<String> readers = getReaders(repositoryId, document);
            System.out.println("=== Readers count: " + readers.size() + " ===");

            // Create and index Solr documents with Block Join structure
            System.out.println("=== Starting Solr indexing ===");
            indexToSolr(repositoryId, document, chunks, chunkEmbeddings, documentEmbedding,
                       propertyEmbedding, propertyText, readers);
            System.out.println("=== Solr indexing complete ===");

            log.info(String.format("RAG indexed document %s with %d chunks", document.getId(), chunks.size()));
            System.out.println("=== RAG indexDocument SUCCESS for: " + document.getId() + " ===");

        } catch (EmbeddingException e) {
            System.out.println("=== EmbeddingException: " + e.getMessage() + " ===");
            throw new RAGIndexingException("Failed to generate embeddings for document: " + document.getId(), e);
        } catch (Exception e) {
            System.out.println("=== Exception in indexDocument: " + e.getClass().getName() + ": " + e.getMessage() + " ===");
            e.printStackTrace();
            throw new RAGIndexingException("Failed to index document: " + document.getId(), e);
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

        try (SolrClient solrClient = getSolrClient()) {
            // Delete parent document and all children using Block Join delete
            // Delete by query: delete all documents where _root_ = documentId (parent and children)
            solrClient.deleteByQuery("nemaki", "_root_:" + documentId);
            solrClient.commit("nemaki");
            log.info("RAG deleted document and chunks: " + documentId);
        } catch (Exception e) {
            throw new RAGIndexingException("Failed to delete document from RAG index: " + documentId, e);
        }
    }

    @Override
    public void updateDocumentACL(String repositoryId, String documentId, List<String> readers) throws RAGIndexingException {
        if (!isEnabled()) {
            return;
        }

        try (SolrClient solrClient = getSolrClient()) {
            // Update readers field using atomic update
            SolrInputDocument updateDoc = new SolrInputDocument();
            updateDoc.addField("id", documentId);
            updateDoc.addField("readers", java.util.Collections.singletonMap("set", readers));

            solrClient.add("nemaki", updateDoc);
            solrClient.commit("nemaki");
            log.info("RAG updated ACL for document: " + documentId);
        } catch (Exception e) {
            throw new RAGIndexingException("Failed to update ACL for document: " + documentId, e);
        }
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
        System.out.println("=== extractText called for: " + document.getId() + " ===");
        String attachmentId = document.getAttachmentNodeId();
        if (attachmentId == null) {
            System.out.println("=== extractText: attachmentId is null ===");
            return null;
        }
        System.out.println("=== extractText: attachmentId = " + attachmentId + " ===");

        try {
            AttachmentNode attachment = contentService.getAttachment(repositoryId, attachmentId);
            if (attachment == null) {
                System.out.println("=== extractText: attachment is null ===");
                return null;
            }
            System.out.println("=== extractText: got attachment, name = " + attachment.getName() + " ===");

            InputStream contentStream = attachment.getInputStream();
            if (contentStream == null) {
                System.out.println("=== extractText: contentStream is null ===");
                return null;
            }
            System.out.println("=== extractText: got contentStream ===");

            try {
                String mimeType = attachment.getMimeType();
                String fileName = attachment.getName();
                System.out.println("=== extractText: calling textExtractionService.extractText for " + fileName + " (" + mimeType + ") ===");
                String result = textExtractionService.extractText(contentStream, mimeType, fileName);
                System.out.println("=== extractText: result length = " + (result != null ? result.length() : "null") + " ===");
                return result;
            } finally {
                try {
                    contentStream.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        } catch (Exception e) {
            System.out.println("=== extractText: exception: " + e.getMessage() + " ===");
            e.printStackTrace();
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
        int dimension = EmbeddingService.VECTOR_DIMENSION;
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

        try (SolrClient solrClient = getSolrClient()) {
            // First, delete any existing entries for this document
            solrClient.deleteByQuery("nemaki", "_root_:" + document.getId());

            // Create parent document (must come AFTER children in Block Join)
            List<SolrInputDocument> childDocs = new ArrayList<>();

            // Create chunk (child) documents
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
                chunkDoc.addField("_root_", document.getId());

                // Copy readers to chunk for filtering
                for (String reader : readers) {
                    chunkDoc.addField("readers", reader);
                }

                childDocs.add(chunkDoc);
            }

            // Create parent document
            SolrInputDocument parentDoc = new SolrInputDocument();
            parentDoc.addField("id", document.getId());
            parentDoc.addField("doc_type", DOC_TYPE_DOCUMENT);
            parentDoc.addField("repository_id", repositoryId);
            parentDoc.addField("object_id", document.getId());
            parentDoc.addField("name", document.getName());
            parentDoc.addField("document_vector", floatArrayToList(documentEmbedding));
            parentDoc.addField("_root_", document.getId());

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

            // Index the parent document with children
            solrClient.add("nemaki", parentDoc);
            solrClient.commit("nemaki");
        }
    }

    private List<Float> floatArrayToList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
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
