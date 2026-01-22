/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - RAG index maintenance service
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic;

import java.util.List;

/**
 * Service interface for RAG (Retrieval-Augmented Generation) index maintenance operations.
 * Provides functionality for reindexing documents with vector embeddings.
 */
public interface RAGIndexMaintenanceService {

    /**
     * Status of a RAG reindexing operation
     */
    public static class RAGReindexStatus {
        private String repositoryId;
        private String status; // "idle", "running", "completed", "error", "cancelled"
        private long totalDocuments;
        private long indexedCount;
        private long skippedCount;  // Documents skipped (unsupported MIME type, no content)
        private long errorCount;
        private long startTime;
        private long endTime;
        private String currentDocument;
        private String errorMessage;
        private List<String> errors;

        public RAGReindexStatus() {}

        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public long getTotalDocuments() { return totalDocuments; }
        public void setTotalDocuments(long totalDocuments) { this.totalDocuments = totalDocuments; }
        public long getIndexedCount() { return indexedCount; }
        public void setIndexedCount(long indexedCount) { this.indexedCount = indexedCount; }
        public long getSkippedCount() { return skippedCount; }
        public void setSkippedCount(long skippedCount) { this.skippedCount = skippedCount; }
        public long getErrorCount() { return errorCount; }
        public void setErrorCount(long errorCount) { this.errorCount = errorCount; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public String getCurrentDocument() { return currentDocument; }
        public void setCurrentDocument(String currentDocument) { this.currentDocument = currentDocument; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
    }

    /**
     * Health check result for RAG index
     */
    public static class RAGHealthStatus {
        private String repositoryId;
        private long ragDocumentCount;      // Documents with RAG vectors
        private long ragChunkCount;         // Total chunks in RAG index
        private long eligibleDocuments;     // Documents eligible for RAG indexing
        private boolean enabled;
        private boolean healthy;
        private String message;
        private long checkTime;

        public RAGHealthStatus() {}

        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public long getRagDocumentCount() { return ragDocumentCount; }
        public void setRagDocumentCount(long ragDocumentCount) { this.ragDocumentCount = ragDocumentCount; }
        public long getRagChunkCount() { return ragChunkCount; }
        public void setRagChunkCount(long ragChunkCount) { this.ragChunkCount = ragChunkCount; }
        public long getEligibleDocuments() { return eligibleDocuments; }
        public void setEligibleDocuments(long eligibleDocuments) { this.eligibleDocuments = eligibleDocuments; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getCheckTime() { return checkTime; }
        public void setCheckTime(long checkTime) { this.checkTime = checkTime; }
    }

    /**
     * Start a full RAG reindex of all documents in the repository.
     * This operation runs asynchronously.
     *
     * @param repositoryId the repository ID
     * @return true if reindex started successfully, false if already running or RAG is disabled
     */
    boolean startFullRAGReindex(String repositoryId);

    /**
     * Start a folder-based RAG reindex.
     * Reindexes all documents under the specified folder.
     *
     * @param repositoryId the repository ID
     * @param folderId the folder ID to start from
     * @param recursive whether to include subfolders
     * @return true if reindex started successfully, false if already running or RAG is disabled
     */
    boolean startFolderRAGReindex(String repositoryId, String folderId, boolean recursive);

    /**
     * Get the current RAG reindex status for a repository.
     *
     * @param repositoryId the repository ID
     * @return the current reindex status
     */
    RAGReindexStatus getRAGReindexStatus(String repositoryId);

    /**
     * Cancel a running RAG reindex operation.
     *
     * @param repositoryId the repository ID
     * @return true if cancelled successfully
     */
    boolean cancelRAGReindex(String repositoryId);

    /**
     * Perform a health check on the RAG index.
     * Checks document and chunk counts in Solr.
     *
     * @param repositoryId the repository ID
     * @return the health check result
     */
    RAGHealthStatus checkRAGHealth(String repositoryId);

    /**
     * RAG reindex a single document by its object ID.
     *
     * @param repositoryId the repository ID
     * @param objectId the object ID to reindex
     * @return true if successful
     */
    boolean reindexDocument(String repositoryId, String objectId);

    /**
     * Delete a document from the RAG index.
     *
     * @param repositoryId the repository ID
     * @param objectId the object ID to delete
     * @return true if successful
     */
    boolean deleteFromRAGIndex(String repositoryId, String objectId);

    /**
     * Clear the entire RAG index for a repository.
     *
     * @param repositoryId the repository ID
     * @return true if successful
     */
    boolean clearRAGIndex(String repositoryId);

    /**
     * Check if RAG indexing is enabled and available.
     *
     * @return true if RAG is enabled and the embedding service is healthy
     */
    boolean isRAGEnabled();
}
