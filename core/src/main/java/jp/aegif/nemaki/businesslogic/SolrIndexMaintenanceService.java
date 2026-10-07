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
        /**
         * One of {@code idle}, {@code running}, {@code completed},
         * {@code completed_with_errors}, {@code error}, {@code cancelled}.
         *
         * <p>This list is read by people writing consumers, so it being short is how consumers
         * end up with short accept-lists. It has been wrong twice: {@code cancelled} was emitted
         * and unlisted, and {@code completed_with_errors} was added to the service while four
         * polling scripts under {@code tools/acl-probe} still waited for {@code completed}
         * alone — one of them the connection-leak probe whose numbers CLAUDE.md quotes, which
         * would have gone on sampling past the end of the run it was measuring.
         *
         * <p>{@code ReindexTerminalWordsHaveConsumersTest} derives the terminal words from the
         * implementation and checks those scripts against them, so adding a sixth here without
         * its consumers fails on the day it is added.
         */
        private String status;
        private long totalDocuments;
        private long indexedCount;
        private long errorCount;
        private long silentDropCount;  // Number of documents detected as silently dropped by Solr
        private long reindexedCount;   // Number of silently dropped documents successfully re-indexed
        private long verificationSkippedCount;  // Number of documents skipped from verification due to query length limits
        /**
         * Where the wall clock went, in milliseconds, split by phase.
         *
         * <p>Reindex throughput falls from ~70 documents/second at 5,615 objects to 2.6 at 97,693,
         * and the cause is NOT the double-indexing that was removed (measured A/B at the same
         * scale: 77s before, 84s after). Without a split, the next large run would again leave
         * only "it is slow" to reason from — this records which phase actually consumes the time,
         * so the answer comes from one production-scale run rather than from argument.
         *
         * <p>enumerationMs: walking the folder tree (CouchDB view queries).
         * solrWriteMs: the whole indexing phase — everything below plus loop overhead.
         * verificationMs: the post-batch existence check.
         *
         * <p>The indexing phase turned out to be ~90% of a full reindex, so it is split again.
         * These four sum to just under {@code solrWriteMs}:
         *
         * <p>solrRtgMs: the content fence's realtime GET — <b>one Solr round trip per DOCUMENT</b>,
         * added by this project in C8. The ledger lists it as hypothesis ③, "a regression we
         * introduced ourselves", so it is measured on its own rather than folded into Solr time.
         * incarnationMs: resolving the authoritative content incarnation (CouchDB), per document.
         * buildDocMs: building the Solr document — attachment metadata and body reads plus text
         * extraction. This is where the attachment-ratio effect should appear if it is real.
         * solrAddMs: the single Solr round trip that submits the batch.
         *
         * <p>couchReadMs is declared but never populated; the CouchDB cost now lives in
         * incarnationMs and buildDocMs, which say which read.
         */
        private long enumerationMs;
        private long couchReadMs;
        private long solrWriteMs;
        private long verificationMs;
        private long solrRtgMs;
        private long incarnationMs;
        private long buildDocMs;
        private long solrAddMs;
        private long startTime;
        private long endTime;
        private String currentFolder;
        private String errorMessage;
        private List<String> errors;
        private List<String> warnings;  // Warnings (non-fatal issues like verification skipped)

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
        public long getSilentDropCount() { return silentDropCount; }
        public void setSilentDropCount(long silentDropCount) { this.silentDropCount = silentDropCount; }
        public long getReindexedCount() { return reindexedCount; }
        public void setReindexedCount(long reindexedCount) { this.reindexedCount = reindexedCount; }
        public long getVerificationSkippedCount() { return verificationSkippedCount; }
        public void setVerificationSkippedCount(long verificationSkippedCount) { this.verificationSkippedCount = verificationSkippedCount; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public String getCurrentFolder() { return currentFolder; }
        public void setCurrentFolder(String currentFolder) { this.currentFolder = currentFolder; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public List<String> getErrors() { return errors; }
        public long getEnumerationMs() { return enumerationMs; }
        public void addEnumerationMs(long ms) { this.enumerationMs += ms; }
        public long getCouchReadMs() { return couchReadMs; }
        public void addCouchReadMs(long ms) { this.couchReadMs += ms; }
        public long getSolrWriteMs() { return solrWriteMs; }
        public void addSolrWriteMs(long ms) { this.solrWriteMs += ms; }
        public long getVerificationMs() { return verificationMs; }
        public void addVerificationMs(long ms) { this.verificationMs += ms; }
        public long getSolrRtgMs() { return solrRtgMs; }
        public void addSolrRtgMs(long ms) { this.solrRtgMs += ms; }
        public long getIncarnationMs() { return incarnationMs; }
        public void addIncarnationMs(long ms) { this.incarnationMs += ms; }
        public long getBuildDocMs() { return buildDocMs; }
        public void addBuildDocMs(long ms) { this.buildDocMs += ms; }
        public long getSolrAddMs() { return solrAddMs; }
        public void addSolrAddMs(long ms) { this.solrAddMs += ms; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        public List<String> getWarnings() { return warnings; }
        public void setWarnings(List<String> warnings) { this.warnings = warnings; }
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
     * A single document entry in the discrepancy result
     */
    public static class DiscrepancyDocumentInfo {
        private String objectId;
        private String name;
        private String objectType;

        public DiscrepancyDocumentInfo() {}
        public DiscrepancyDocumentInfo(String objectId, String name, String objectType) {
            this.objectId = objectId;
            this.name = name;
            this.objectType = objectType;
        }

        public String getObjectId() { return objectId; }
        public void setObjectId(String objectId) { this.objectId = objectId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getObjectType() { return objectType; }
        public void setObjectType(String objectType) { this.objectType = objectType; }
    }

    /**
     * Result of index discrepancy analysis
     */
    public static class IndexDiscrepancyResult {
        private String repositoryId;
        private List<DiscrepancyDocumentInfo> missingInSolr;
        private List<DiscrepancyDocumentInfo> orphanedInSolr;
        private long checkTime;

        public IndexDiscrepancyResult() {
            this.missingInSolr = new java.util.ArrayList<>();
            this.orphanedInSolr = new java.util.ArrayList<>();
        }

        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public List<DiscrepancyDocumentInfo> getMissingInSolr() { return missingInSolr; }
        public void setMissingInSolr(List<DiscrepancyDocumentInfo> missingInSolr) { this.missingInSolr = missingInSolr; }
        public List<DiscrepancyDocumentInfo> getOrphanedInSolr() { return orphanedInSolr; }
        public void setOrphanedInSolr(List<DiscrepancyDocumentInfo> orphanedInSolr) { this.orphanedInSolr = orphanedInSolr; }
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
     * Get detailed discrepancy information between Solr and CouchDB.
     * Returns lists of specific documents that are missing from Solr or orphaned in Solr.
     *
     * @param repositoryId the repository ID
     * @return the discrepancy details
     */
    IndexDiscrepancyResult getIndexDiscrepancies(String repositoryId);

    /**
     * Remove index entries whose object no longer exists in CouchDB.
     *
     * <p>Refuses (returning -1) when the discrepancy looks like an unreadable repository rather
     * than a handful of stragglers — the same guard the full reindex uses, for the same reason.
     *
     * @return entries removed, or -1 if refused
     */
    long purgeOrphanedIndexEntries(String repositoryId);

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
