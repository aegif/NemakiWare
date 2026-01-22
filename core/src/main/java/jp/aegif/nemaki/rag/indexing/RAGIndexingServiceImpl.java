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
 * - Parent document (doc_type="document"): Contains metadata and document-level vector
 * - Child documents (doc_type="chunk"): Contains chunk text and chunk vector
 *
 * Uses Solr's Block Join to efficiently query chunks while filtering by parent ACL.
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
        if (!isEnabled()) {
            log.debug("RAG indexing is disabled, skipping document: " + document.getId());
            return;
        }

        String mimeType = getMimeType(repositoryId, document);
        if (!isMimeTypeSupported(mimeType)) {
            log.debug("MIME type not supported for RAG indexing: " + mimeType);
            return;
        }

        try {
            // Extract text content
            String textContent = extractText(repositoryId, document);
            if (textContent == null || textContent.trim().isEmpty()) {
                log.debug("No text content extracted for document: " + document.getId());
                return;
            }

            // Chunk the text
            List<TextChunk> chunks = chunkingService.chunk(textContent);
            if (chunks.isEmpty()) {
                log.debug("No chunks generated for document: " + document.getId());
                return;
            }

            // Generate embeddings for chunks
            List<String> chunkTexts = chunks.stream()
                    .map(TextChunk::getText)
                    .collect(Collectors.toList());
            List<float[]> chunkEmbeddings = embeddingService.embedBatch(chunkTexts, false);

            // Generate document-level embedding (average of first few chunks or title)
            float[] documentEmbedding = generateDocumentEmbedding(document, chunks, chunkEmbeddings);

            // Get readers for ACL pre-expansion
            List<String> readers = getReaders(repositoryId, document);

            // Create and index Solr documents with Block Join structure
            indexToSolr(repositoryId, document, chunks, chunkEmbeddings, documentEmbedding, readers);

            log.info(String.format("RAG indexed document %s with %d chunks", document.getId(), chunks.size()));

        } catch (EmbeddingException e) {
            throw new RAGIndexingException("Failed to generate embeddings for document: " + document.getId(), e);
        } catch (Exception e) {
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
