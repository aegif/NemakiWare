package jp.aegif.nemaki.rag.indexing;

import java.util.List;

import jp.aegif.nemaki.model.Document;

/**
 * Service interface for RAG (Retrieval-Augmented Generation) document indexing.
 *
 * Responsible for:
 * 1. Extracting text content from documents
 * 2. Chunking text into smaller pieces
 * 3. Generating embeddings for each chunk
 * 4. Indexing to Solr with parent-child (Block Join) structure
 * 5. Managing ACL pre-expansion for permission filtering
 *
 * The RAG index structure uses Solr's Block Join feature:
 * - Parent document: doc_type="document", contains document metadata
 * - Child documents: doc_type="chunk", contain chunk text and vector
 *
 * This enables efficient semantic search with ACL filtering.
 */
public interface RAGIndexingService {

    /**
     * Index a document for RAG semantic search.
     *
     * This method:
     * 1. Extracts text content from the document
     * 2. Splits text into chunks
     * 3. Generates embeddings for each chunk
     * 4. Indexes parent document and chunk children to Solr
     *
     * @param repositoryId Repository ID
     * @param document Document to index
     * @throws RAGIndexingException if indexing fails
     */
    void indexDocument(String repositoryId, Document document) throws RAGIndexingException;

    /**
     * Index multiple documents in batch.
     * More efficient than calling indexDocument() multiple times.
     *
     * @param repositoryId Repository ID
     * @param documents Documents to index
     * @throws RAGIndexingException if indexing fails
     */
    void indexDocumentsBatch(String repositoryId, List<Document> documents) throws RAGIndexingException;

    /**
     * Delete RAG index entries for a document.
     * Removes both parent and all chunk children.
     *
     * @param repositoryId Repository ID
     * @param documentId Document ID to delete
     * @throws RAGIndexingException if deletion fails
     */
    void deleteDocument(String repositoryId, String documentId) throws RAGIndexingException;

    /**
     * Whether ANY RAG doc (block parent or chunk) still exists for this document.
     * Used by the durable {@code RAG_PURGE} re-drive to VERIFY a delete actually
     * removed the block — {@link #deleteDocument} silently no-ops when RAG is
     * disabled, and a verify-less purge would complete its task with the block
     * still present. Deliberately does NOT check {@code isEnabled()}: a leftover
     * block must be detectable (and the purge retried/kept visible) even if RAG
     * was disabled after the block was written.
     *
     * @throws RAGIndexingException if Solr cannot be queried (caller must treat as
     *         "unknown" and retry, never as absent)
     */
    boolean isDocumentInRagIndex(String repositoryId, String documentId) throws RAGIndexingException;

    /**
     * Update RAG index when document ACL changes.
     * Re-indexes the readers field without re-embedding.
     *
     * @param repositoryId Repository ID
     * @param documentId Document ID
     * @param readers New list of readers (users and groups)
     * @throws RAGIndexingException if update fails
     */
    void updateDocumentACL(String repositoryId, String documentId, List<String> readers) throws RAGIndexingException;

    /**
     * As {@link #updateDocumentACL(String, String, List)}, but with a cooperative-fencing
     * lease guard checked before EACH chunk page is fetched. Rebuilding a large block
     * (thousands of chunks) can outlive the reconciliation lease; if {@code leaseStillHeld}
     * reports the lease lost mid-rebuild the method ABORTS with a {@link RAGIndexingException}
     * BEFORE the single block-replacing add, so a worker that lost its lease to a reclaimer
     * never lands a stale block. A {@code null} guard disables fencing (normal ACL refresh).
     *
     * @param leaseStillHeld returns {@code false} once the reconciliation lease is lost
     */
    void updateDocumentACL(String repositoryId, String documentId, List<String> readers,
            java.util.function.BooleanSupplier leaseStillHeld) throws RAGIndexingException;

    /**
     * Check if RAG indexing is enabled and available.
     *
     * @return true if RAG indexing can be performed
     */
    boolean isEnabled();

    /**
     * Check if a MIME type is supported for RAG indexing.
     *
     * @param mimeType MIME type to check
     * @return true if the MIME type can be indexed
     */
    boolean isMimeTypeSupported(String mimeType);
}
