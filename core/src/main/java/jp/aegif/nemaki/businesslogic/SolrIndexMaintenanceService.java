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
 *     aegif - Solr index maintenance service
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic;

import java.util.List;
import java.util.Map;

/**
 * Service interface for Solr index maintenance operations.
 * Provides functionality for reindexing, health checks, and direct Solr query execution.
 */
public interface SolrIndexMaintenanceService {

    /**
     * Status of a reindexing operation
     */
    public static class ReindexStatus {
        private String repositoryId;
        private String status; // "idle", "running", "completed", "error"
        private long totalDocuments;
        private long indexedCount;
        private long errorCount;
        private long startTime;
        private long endTime;
        private String currentFolder;
        private String errorMessage;
        private List<String> errors;

        public ReindexStatus() {}

        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public long getTotalDocuments() { return totalDocuments; }
        public void setTotalDocuments(long totalDocuments) { this.totalDocuments = totalDocuments; }
        public long getIndexedCount() { return indexedCount; }
        public void setIndexedCount(long indexedCount) { this.indexedCount = indexedCount; }
        public long getErrorCount() { return errorCount; }
        public void setErrorCount(long errorCount) { this.errorCount = errorCount; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public String getCurrentFolder() { return currentFolder; }
        public void setCurrentFolder(String currentFolder) { this.currentFolder = currentFolder; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
    }

    /**
     * Health check result for Solr index
     */
    public static class IndexHealthStatus {
        private String repositoryId;
        private long solrDocumentCount;
        private long couchDbDocumentCount;
        private long missingInSolr;
        private long orphanedInSolr;
        private boolean healthy;
        private String message;
        private long checkTime;

        public IndexHealthStatus() {}

        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public long getSolrDocumentCount() { return solrDocumentCount; }
        public void setSolrDocumentCount(long solrDocumentCount) { this.solrDocumentCount = solrDocumentCount; }
        public long getCouchDbDocumentCount() { return couchDbDocumentCount; }
        public void setCouchDbDocumentCount(long couchDbDocumentCount) { this.couchDbDocumentCount = couchDbDocumentCount; }
        public long getMissingInSolr() { return missingInSolr; }
        public void setMissingInSolr(long missingInSolr) { this.missingInSolr = missingInSolr; }
        public long getOrphanedInSolr() { return orphanedInSolr; }
        public void setOrphanedInSolr(long orphanedInSolr) { this.orphanedInSolr = orphanedInSolr; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getCheckTime() { return checkTime; }
        public void setCheckTime(long checkTime) { this.checkTime = checkTime; }
    }

    /**
     * Result of a Solr query execution
     */
    public static class SolrQueryResult {
        private long numFound;
        private long start;
        private List<Map<String, Object>> docs;
        private String rawResponse;
        private long queryTime;
        private String errorMessage;

        public SolrQueryResult() {}

        public long getNumFound() { return numFound; }
        public void setNumFound(long numFound) { this.numFound = numFound; }
        public long getStart() { return start; }
        public void setStart(long start) { this.start = start; }
        public List<Map<String, Object>> getDocs() { return docs; }
        public void setDocs(List<Map<String, Object>> docs) { this.docs = docs; }
        public String getRawResponse() { return rawResponse; }
        public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }
        public long getQueryTime() { return queryTime; }
        public void setQueryTime(long queryTime) { this.queryTime = queryTime; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    /**
     * Start a full reindex of all documents in the repository.
     * This operation runs asynchronously.
     *
     * @param repositoryId the repository ID
     * @return true if reindex started successfully, false if already running
     */
    boolean startFullReindex(String repositoryId);

    /**
     * Start a folder-based reindex.
     * Reindexes all documents under the specified folder.
     *
     * @param repositoryId the repository ID
     * @param folderId the folder ID to start from
     * @param recursive whether to include subfolders
     * @return true if reindex started successfully, false if already running
     */
    boolean startFolderReindex(String repositoryId, String folderId, boolean recursive);

    /**
     * Get the current reindex status for a repository.
     *
     * @param repositoryId the repository ID
     * @return the current reindex status
     */
    ReindexStatus getReindexStatus(String repositoryId);

    /**
     * Cancel a running reindex operation.
     *
     * @param repositoryId the repository ID
     * @return true if cancelled successfully
     */
    boolean cancelReindex(String repositoryId);

    /**
     * Perform a health check on the Solr index.
     * Compares document counts between Solr and CouchDB.
     *
     * @param repositoryId the repository ID
     * @return the health check result
     */
    IndexHealthStatus checkIndexHealth(String repositoryId);

    /**
     * Execute a raw Solr query.
     *
     * @param repositoryId the repository ID
     * @param query the Solr query string (q parameter)
     * @param start the start offset for pagination
     * @param rows the number of rows to return
     * @param sort the sort parameter (optional)
     * @param fields the fields to return (optional, comma-separated)
     * @return the query result
     */
    SolrQueryResult executeSolrQuery(String repositoryId, String query, int start, int rows, String sort, String fields);

    /**
     * Reindex a single document by its object ID.
     *
     * @param repositoryId the repository ID
     * @param objectId the object ID to reindex
     * @return true if successful
     */
    boolean reindexDocument(String repositoryId, String objectId);

    /**
     * Delete a document from the Solr index.
     *
     * @param repositoryId the repository ID
     * @param objectId the object ID to delete
     * @return true if successful
     */
    boolean deleteFromIndex(String repositoryId, String objectId);

    /**
     * Clear the entire Solr index for a repository.
     *
     * @param repositoryId the repository ID
     * @return true if successful
     */
    boolean clearIndex(String repositoryId);

    /**
     * Optimize the Solr index.
     *
     * @param repositoryId the repository ID
     * @return true if successful
     */
    boolean optimizeIndex(String repositoryId);
}
