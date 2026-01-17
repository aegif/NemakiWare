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
 *     aegif - Solr index maintenance service implementation
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation of SolrIndexMaintenanceService.
 * Provides Solr index maintenance operations including reindexing, health checks, and query execution.
 */
public class SolrIndexMaintenanceServiceImpl implements SolrIndexMaintenanceService {

    private static final Log log = LogFactory.getLog(SolrIndexMaintenanceServiceImpl.class);
    
    /** Batch size for bulk indexing operations - balances memory usage and performance */
    private static final int BATCH_SIZE = 100;
    
    /** Commit within milliseconds for batch operations */
    private static final int BATCH_COMMIT_WITHIN_MS = 5000;

    private ContentService contentService;
    private SolrUtil solrUtil;
    private RepositoryInfoMap repositoryInfoMap;

    private final Map<String, ReindexStatus> reindexStatuses = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setSolrUtil(SolrUtil solrUtil) {
        this.solrUtil = solrUtil;
    }

    public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SolrIndexMaintenanceService executor service");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean startFullReindex(String repositoryId) {
        ReindexStatus currentStatus = reindexStatuses.get(repositoryId);
        if (currentStatus != null && "running".equals(currentStatus.getStatus())) {
            log.warn("Reindex already running for repository: " + repositoryId);
            return false;
        }

        ReindexStatus status = new ReindexStatus();
        status.setRepositoryId(repositoryId);
        status.setStatus("running");
        status.setStartTime(System.currentTimeMillis());
        status.setErrors(new ArrayList<>());
        reindexStatuses.put(repositoryId, status);
        cancelFlags.put(repositoryId, new AtomicBoolean(false));

        executorService.submit(() -> {
            try {
                log.info("Starting full reindex for repository: " + repositoryId);
                
                // Get root folder
                Folder rootFolder = contentService.getFolder(repositoryId, 
                    repositoryInfoMap.getSingleRepositoryInfo(repositoryId).getRootFolderId());
                
                if (rootFolder == null) {
                    status.setStatus("error");
                    status.setErrorMessage("Root folder not found");
                    status.setEndTime(System.currentTimeMillis());
                    return;
                }

                // Count total documents first
                AtomicLong totalCount = new AtomicLong(0);
                countDocumentsRecursive(repositoryId, rootFolder.getId(), totalCount);
                status.setTotalDocuments(totalCount.get());

                // Clear existing index
                clearIndex(repositoryId);

                // Reindex all documents
                AtomicLong indexedCount = new AtomicLong(0);
                AtomicLong errorCount = new AtomicLong(0);
                List<String> errors = new ArrayList<>();

                reindexFolderRecursive(repositoryId, rootFolder.getId(), true, 
                    status, indexedCount, errorCount, errors);

                status.setIndexedCount(indexedCount.get());
                status.setErrorCount(errorCount.get());
                status.setErrors(errors);
                status.setStatus(cancelFlags.get(repositoryId).get() ? "cancelled" : "completed");
                status.setEndTime(System.currentTimeMillis());

                log.info("Full reindex completed for repository: " + repositoryId + 
                    ", indexed: " + indexedCount.get() + ", errors: " + errorCount.get());
                
                // Run health check after completion to verify index integrity
                if (!cancelFlags.get(repositoryId).get()) {
                    runPostReindexHealthCheck(repositoryId, status, errors);
                }

            } catch (Exception e) {
                log.error("Error during full reindex for repository: " + repositoryId, e);
                status.setStatus("error");
                status.setErrorMessage(e.getMessage());
                status.setEndTime(System.currentTimeMillis());
            }
        });

        return true;
    }

    @Override
    public boolean startFolderReindex(String repositoryId, String folderId, boolean recursive) {
        ReindexStatus currentStatus = reindexStatuses.get(repositoryId);
        if (currentStatus != null && "running".equals(currentStatus.getStatus())) {
            log.warn("Reindex already running for repository: " + repositoryId);
            return false;
        }

        ReindexStatus status = new ReindexStatus();
        status.setRepositoryId(repositoryId);
        status.setStatus("running");
        status.setStartTime(System.currentTimeMillis());
        status.setErrors(new ArrayList<>());
        reindexStatuses.put(repositoryId, status);
        cancelFlags.put(repositoryId, new AtomicBoolean(false));

        executorService.submit(() -> {
            try {
                log.info("Starting folder reindex for repository: " + repositoryId + 
                    ", folder: " + folderId + ", recursive: " + recursive);

                Folder folder = contentService.getFolder(repositoryId, folderId);
                if (folder == null) {
                    status.setStatus("error");
                    status.setErrorMessage("Folder not found: " + folderId);
                    status.setEndTime(System.currentTimeMillis());
                    return;
                }

                // Count total documents
                AtomicLong totalCount = new AtomicLong(0);
                if (recursive) {
                    countDocumentsRecursive(repositoryId, folderId, totalCount);
                } else {
                    List<Content> children = contentService.getChildren(repositoryId, folderId);
                    totalCount.set(children.size());
                }
                status.setTotalDocuments(totalCount.get());

                // Reindex
                AtomicLong indexedCount = new AtomicLong(0);
                AtomicLong errorCount = new AtomicLong(0);
                List<String> errors = new ArrayList<>();

                reindexFolderRecursive(repositoryId, folderId, recursive, 
                    status, indexedCount, errorCount, errors);

                status.setIndexedCount(indexedCount.get());
                status.setErrorCount(errorCount.get());
                status.setErrors(errors);
                status.setStatus(cancelFlags.get(repositoryId).get() ? "cancelled" : "completed");
                status.setEndTime(System.currentTimeMillis());

                log.info("Folder reindex completed for repository: " + repositoryId + 
                    ", folder: " + folderId + ", indexed: " + indexedCount.get() + 
                    ", errors: " + errorCount.get());
                
                // Run health check after completion to verify index integrity
                if (!cancelFlags.get(repositoryId).get()) {
                    runPostReindexHealthCheck(repositoryId, status, errors);
                }

            } catch (Exception e) {
                log.error("Error during folder reindex for repository: " + repositoryId, e);
                status.setStatus("error");
                status.setErrorMessage(e.getMessage());
                status.setEndTime(System.currentTimeMillis());
            }
        });

        return true;
    }
    
    /**
     * Run health check after reindex completion and log any discrepancies.
     */
    private void runPostReindexHealthCheck(String repositoryId, ReindexStatus status, List<String> errors) {
        try {
            log.info("Running post-reindex health check for repository: " + repositoryId);
            IndexHealthStatus health = checkIndexHealth(repositoryId);
            
            if (!health.isHealthy()) {
                String healthMessage = "Post-reindex health check: " + health.getMessage();
                log.warn(healthMessage);
                if (errors.size() < 100) {
                    errors.add(healthMessage);
                }
                status.setErrors(errors);
                
                // Log specific discrepancies
                if (health.getMissingInSolr() > 0) {
                    log.warn("Post-reindex: " + health.getMissingInSolr() + " documents missing in Solr");
                }
                if (health.getOrphanedInSolr() > 0) {
                    log.warn("Post-reindex: " + health.getOrphanedInSolr() + " orphaned documents in Solr");
                }
            } else {
                log.info("Post-reindex health check passed: " + health.getMessage());
            }
        } catch (Exception e) {
            log.warn("Error during post-reindex health check: " + e.getMessage());
            // Don't fail the reindex - health check is informational
        }
    }

    private void countDocumentsRecursive(String repositoryId, String folderId, AtomicLong count) {
        try {
            List<Content> children = contentService.getChildren(repositoryId, folderId);
            for (Content child : children) {
                count.incrementAndGet();
                if (child instanceof Folder) {
                    countDocumentsRecursive(repositoryId, child.getId(), count);
                }
            }
        } catch (Exception e) {
            log.warn("Error counting documents in folder: " + folderId, e);
        }
    }

    private void reindexFolderRecursive(String repositoryId, String folderId, boolean recursive,
            ReindexStatus status, AtomicLong indexedCount, AtomicLong errorCount, List<String> errors) {
        
        if (cancelFlags.get(repositoryId).get()) {
            return;
        }

        try {
            Folder folder = contentService.getFolder(repositoryId, folderId);
            if (folder != null) {
                status.setCurrentFolder(folder.getName());
            }

            List<Content> children = contentService.getChildren(repositoryId, folderId);
            
            // Collect documents for batch indexing
            List<Content> batchBuffer = new ArrayList<>();
            List<Folder> subFolders = new ArrayList<>();
            
            for (Content child : children) {
                if (cancelFlags.get(repositoryId).get()) {
                    // Flush remaining batch before cancellation
                    if (!batchBuffer.isEmpty()) {
                        flushBatch(repositoryId, batchBuffer, indexedCount, errorCount, errors, status);
                    }
                    return;
                }

                // Separate folders for recursive processing
                if (recursive && child instanceof Folder) {
                    subFolders.add((Folder) child);
                }
                
                // Add to batch buffer
                batchBuffer.add(child);
                
                // Flush batch when it reaches BATCH_SIZE
                if (batchBuffer.size() >= BATCH_SIZE) {
                    flushBatch(repositoryId, batchBuffer, indexedCount, errorCount, errors, status);
                    batchBuffer.clear();
                }
            }
            
            // Flush remaining documents in buffer
            if (!batchBuffer.isEmpty()) {
                flushBatch(repositoryId, batchBuffer, indexedCount, errorCount, errors, status);
            }
            
            // Process subfolders recursively
            for (Folder subFolder : subFolders) {
                if (cancelFlags.get(repositoryId).get()) {
                    return;
                }
                reindexFolderRecursive(repositoryId, subFolder.getId(), true, 
                    status, indexedCount, errorCount, errors);
            }
        } catch (Exception e) {
            log.error("Error reindexing folder: " + folderId, e);
            errorCount.incrementAndGet();
            if (errors.size() < 100) {
                errors.add("Error processing folder " + folderId + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Flush a batch of documents to Solr index.
     * Uses batch indexing for improved performance with verification for silent drops.
     */
    private void flushBatch(String repositoryId, List<Content> batch, 
            AtomicLong indexedCount, AtomicLong errorCount, List<String> errors, ReindexStatus status) {
        if (batch.isEmpty()) {
            return;
        }
        
        try {
            int successCount = solrUtil.indexDocumentsBatch(repositoryId, batch, BATCH_COMMIT_WITHIN_MS);
            indexedCount.addAndGet(successCount);
            status.setIndexedCount(indexedCount.get());
            
            // Track errors for documents that failed during document creation
            int failedCount = batch.size() - successCount;
            if (failedCount > 0) {
                errorCount.addAndGet(failedCount);
                if (errors.size() < 100) {
                    errors.add("Batch indexing: " + failedCount + " documents failed in batch of " + batch.size());
                }
            }
            
            log.info("Batch indexed " + successCount + "/" + batch.size() + " documents, total: " + indexedCount.get());
            
            // Verify batch indexing and re-index any silently dropped documents
            verifyAndReindexMissing(repositoryId, batch, indexedCount, errorCount, errors, status);
            
        } catch (Exception e) {
            // Fall back to individual indexing on batch failure
            log.warn("Batch indexing failed, falling back to individual indexing: " + e.getMessage());
            for (Content content : batch) {
                try {
                    solrUtil.indexDocument(repositoryId, content, true);
                    indexedCount.incrementAndGet();
                    status.setIndexedCount(indexedCount.get());
                } catch (Exception ex) {
                    errorCount.incrementAndGet();
                    String errorMsg = "Failed to index " + content.getId() + ": " + ex.getMessage();
                    if (errors.size() < 100) {
                        errors.add(errorMsg);
                    }
                    log.warn(errorMsg);
                }
            }
        }
    }
    
    /**
     * Verify batch indexing by checking Solr for indexed documents.
     * Re-indexes any documents that were silently dropped by Solr.
     */
    private void verifyAndReindexMissing(String repositoryId, List<Content> batch,
            AtomicLong indexedCount, AtomicLong errorCount, List<String> errors, ReindexStatus status) {
        if (batch.isEmpty()) {
            return;
        }
        
        SolrClient solrClient = null;
        try {
            solrClient = solrUtil.getSolrClient();
            if (solrClient == null) {
                log.warn("Solr client not available for batch verification");
                return;
            }
            
            // Build query to check which documents exist in Solr
            // Use object_id field which matches the content ID
            // Escape special characters in repositoryId and objectId to prevent query injection
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("repository_id:").append(ClientUtils.escapeQueryChars(repositoryId)).append(" AND object_id:(");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) {
                    queryBuilder.append(" OR ");
                }
                queryBuilder.append("\"").append(ClientUtils.escapeQueryChars(batch.get(i).getId())).append("\"");
            }
            queryBuilder.append(")");
            
            SolrQuery query = new SolrQuery(queryBuilder.toString());
            query.setRows(0); // We only need the count
            QueryResponse response = solrClient.query(query);
            long foundCount = response.getResults().getNumFound();
            
            if (foundCount < batch.size()) {
                // Some documents were silently dropped - identify and re-index them
                int missingCount = batch.size() - (int) foundCount;
                log.warn("Batch verification: " + missingCount + " documents missing in Solr, attempting re-index");
                
                // Get the IDs that exist in Solr
                query.setRows(batch.size());
                query.setFields("object_id");
                response = solrClient.query(query);
                
                java.util.Set<String> existingIds = new java.util.HashSet<>();
                for (org.apache.solr.common.SolrDocument doc : response.getResults()) {
                    Object objectId = doc.getFieldValue("object_id");
                    if (objectId != null) {
                        existingIds.add(objectId.toString());
                    }
                }
                
                // Re-index missing documents individually
                int reindexedCount = 0;
                for (Content content : batch) {
                    if (!existingIds.contains(content.getId())) {
                        try {
                            solrUtil.indexDocument(repositoryId, content, true);
                            reindexedCount++;
                            log.info("Re-indexed silently dropped document: " + content.getId());
                        } catch (Exception ex) {
                            // Decrement indexedCount since this document was counted as success in batch
                            // but actually failed (silent drop + re-index failure)
                            indexedCount.decrementAndGet();
                            errorCount.incrementAndGet();
                            String errorMsg = "Failed to re-index silently dropped document " + content.getId() + ": " + ex.getMessage();
                            if (errors.size() < 100) {
                                errors.add(errorMsg);
                            }
                            log.warn(errorMsg);
                        }
                    }
                }
                
                if (reindexedCount > 0) {
                    log.info("Re-indexed " + reindexedCount + " silently dropped documents");
                }
            }
        } catch (Exception e) {
            log.warn("Error during batch verification: " + e.getMessage());
            // Don't fail the batch - verification is best-effort
        } finally {
            if (solrClient != null) {
                try {
                    solrClient.close();
                } catch (Exception e) {
                    log.warn("Failed to close Solr client: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public ReindexStatus getReindexStatus(String repositoryId) {
        ReindexStatus status = reindexStatuses.get(repositoryId);
        if (status == null) {
            status = new ReindexStatus();
            status.setRepositoryId(repositoryId);
            status.setStatus("idle");
        }
        return status;
    }

    @Override
    public boolean cancelReindex(String repositoryId) {
        AtomicBoolean cancelFlag = cancelFlags.get(repositoryId);
        if (cancelFlag != null) {
            cancelFlag.set(true);
            log.info("Reindex cancellation requested for repository: " + repositoryId);
            return true;
        }
        return false;
    }

    @Override
    public IndexHealthStatus checkIndexHealth(String repositoryId) {
        IndexHealthStatus health = new IndexHealthStatus();
        health.setRepositoryId(repositoryId);
        health.setCheckTime(System.currentTimeMillis());

        SolrClient solrClient = null;
        try {
            // Get Solr document count
            // Escape repositoryId to prevent query injection with special characters
            solrClient = solrUtil.getSolrClient();
            if (solrClient != null) {
                SolrQuery query = new SolrQuery("repository_id:" + ClientUtils.escapeQueryChars(repositoryId));
                query.setRows(0);
                QueryResponse response = solrClient.query(query);
                health.setSolrDocumentCount(response.getResults().getNumFound());
            }

            // Get CouchDB document count by counting from root folder
            Folder rootFolder = contentService.getFolder(repositoryId, 
                repositoryInfoMap.getSingleRepositoryInfo(repositoryId).getRootFolderId());
            if (rootFolder != null) {
                AtomicLong couchCount = new AtomicLong(0);
                countDocumentsRecursive(repositoryId, rootFolder.getId(), couchCount);
                health.setCouchDbDocumentCount(couchCount.get());
            }

            // Calculate discrepancies
            long diff = health.getCouchDbDocumentCount() - health.getSolrDocumentCount();
            if (diff > 0) {
                health.setMissingInSolr(diff);
            } else if (diff < 0) {
                health.setOrphanedInSolr(-diff);
            }

            health.setHealthy(diff == 0);
            if (health.isHealthy()) {
                health.setMessage("Index is healthy. Document counts match.");
            } else {
                health.setMessage("Index mismatch detected. CouchDB: " + health.getCouchDbDocumentCount() + 
                    ", Solr: " + health.getSolrDocumentCount());
            }

        } catch (Exception e) {
            log.error("Error checking index health for repository: " + repositoryId, e);
            health.setHealthy(false);
            health.setMessage("Error checking health: " + e.getMessage());
        } finally {
            if (solrClient != null) {
                try {
                    solrClient.close();
                } catch (Exception e) {
                    log.warn("Failed to close Solr client: " + e.getMessage());
                }
            }
        }

        return health;
    }

    private static final int MAX_QUERY_ROWS = 1000;

    @Override
    public SolrQueryResult executeSolrQuery(String repositoryId, String query, int start, int rows, String sort, String fields) {
        SolrQueryResult result = new SolrQueryResult();
        long startTime = System.currentTimeMillis();

        SolrClient solrClient = null;
        try {
            solrClient = solrUtil.getSolrClient();
            if (solrClient == null) {
                result.setErrorMessage("Solr client is not available");
                return result;
            }

            // Build query with repository filter
            // Escape repositoryId to prevent query injection with special characters
            String escapedRepoId = ClientUtils.escapeQueryChars(repositoryId);
            SolrQuery solrQuery = new SolrQuery();
            if (query != null && !query.trim().isEmpty()) {
                // Add repository filter if not already present
                if (!query.contains("repository_id:")) {
                    solrQuery.setQuery("repository_id:" + escapedRepoId + " AND (" + query + ")");
                } else {
                    solrQuery.setQuery(query);
                }
            } else {
                solrQuery.setQuery("repository_id:" + escapedRepoId);
            }

            solrQuery.setStart(start >= 0 ? start : 0);
            // Enforce maximum rows limit to prevent high-load queries
            int effectiveRows = rows > 0 ? Math.min(rows, MAX_QUERY_ROWS) : 10;
            solrQuery.setRows(effectiveRows);

            if (sort != null && !sort.trim().isEmpty()) {
                solrQuery.set("sort", sort.trim());
            }

            if (fields != null && !fields.trim().isEmpty()) {
                // Trim each field name
                String[] fieldArray = fields.split(",");
                for (int i = 0; i < fieldArray.length; i++) {
                    fieldArray[i] = fieldArray[i].trim();
                }
                solrQuery.setFields(fieldArray);
            }

            QueryResponse response = solrClient.query(solrQuery);
            SolrDocumentList docs = response.getResults();

            result.setNumFound(docs.getNumFound());
            result.setStart(docs.getStart());

            List<Map<String, Object>> docList = new ArrayList<>();
            for (SolrDocument doc : docs) {
                Map<String, Object> docMap = new HashMap<>();
                for (String fieldName : doc.getFieldNames()) {
                    docMap.put(fieldName, doc.getFieldValue(fieldName));
                }
                docList.add(docMap);
            }
            result.setDocs(docList);
            result.setQueryTime(System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("Error executing Solr query for repository: " + repositoryId, e);
            result.setErrorMessage(e.getMessage());
        } finally {
            if (solrClient != null) {
                try {
                    solrClient.close();
                } catch (Exception e) {
                    log.warn("Failed to close Solr client: " + e.getMessage());
                }
            }
        }

        return result;
    }

    @Override
    public boolean reindexDocument(String repositoryId, String objectId) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                log.warn("Content not found for reindexing: " + objectId);
                return false;
            }
            solrUtil.indexDocument(repositoryId, content);
            log.info("Document reindexed: " + objectId);
            return true;
        } catch (Exception e) {
            log.error("Error reindexing document: " + objectId, e);
            return false;
        }
    }

    @Override
    public boolean deleteFromIndex(String repositoryId, String objectId) {
        try {
            solrUtil.deleteDocument(repositoryId, objectId);
            log.info("Document deleted from index: " + objectId);
            return true;
        } catch (Exception e) {
            log.error("Error deleting document from index: " + objectId, e);
            return false;
        }
    }

    @Override
    public boolean clearIndex(String repositoryId) {
        try {
            SolrClient solrClient = solrUtil.getSolrClient();
            if (solrClient == null) {
                log.error("Solr client is not available");
                return false;
            }

            // Escape repositoryId to prevent query injection with special characters
            UpdateResponse response = solrClient.deleteByQuery("repository_id:" + ClientUtils.escapeQueryChars(repositoryId));
            solrClient.commit();
            solrClient.close();

            log.info("Index cleared for repository: " + repositoryId);
            return response.getStatus() == 0;
        } catch (Exception e) {
            log.error("Error clearing index for repository: " + repositoryId, e);
            return false;
        }
    }

    @Override
    public boolean optimizeIndex(String repositoryId) {
        try {
            SolrClient solrClient = solrUtil.getSolrClient();
            if (solrClient == null) {
                log.error("Solr client is not available");
                return false;
            }

            UpdateResponse response = solrClient.optimize();
            solrClient.close();

            log.info("Index optimized for repository: " + repositoryId);
            return response.getStatus() == 0;
        } catch (Exception e) {
            log.error("Error optimizing index for repository: " + repositoryId, e);
            return false;
        }
    }
}
