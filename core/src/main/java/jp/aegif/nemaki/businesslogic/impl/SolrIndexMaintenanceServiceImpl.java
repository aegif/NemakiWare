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

            } catch (Exception e) {
                log.error("Error during folder reindex for repository: " + repositoryId, e);
                status.setStatus("error");
                status.setErrorMessage(e.getMessage());
                status.setEndTime(System.currentTimeMillis());
            }
        });

        return true;
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
            for (Content child : children) {
                if (cancelFlags.get(repositoryId).get()) {
                    return;
                }

                try {
                    // Use synchronous indexing (forceSync=true) for maintenance operations
                    // This ensures progress tracking is accurate and bypasses solr.indexing.force setting
                    solrUtil.indexDocument(repositoryId, child, true);
                    indexedCount.incrementAndGet();
                    status.setIndexedCount(indexedCount.get());
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    String errorMsg = "Failed to index " + child.getId() + ": " + e.getMessage();
                    if (errors.size() < 100) {
                        errors.add(errorMsg);
                    }
                    log.warn(errorMsg);
                }

                if (recursive && child instanceof Folder) {
                    reindexFolderRecursive(repositoryId, child.getId(), true, 
                        status, indexedCount, errorCount, errors);
                }
            }
        } catch (Exception e) {
            log.error("Error reindexing folder: " + folderId, e);
            errorCount.incrementAndGet();
            if (errors.size() < 100) {
                errors.add("Error processing folder " + folderId + ": " + e.getMessage());
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
            solrClient = solrUtil.getSolrClient();
            if (solrClient != null) {
                SolrQuery query = new SolrQuery("repository_id:" + repositoryId);
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
            SolrQuery solrQuery = new SolrQuery();
            if (query != null && !query.trim().isEmpty()) {
                // Add repository filter if not already present
                if (!query.contains("repository_id:")) {
                    solrQuery.setQuery("repository_id:" + repositoryId + " AND (" + query + ")");
                } else {
                    solrQuery.setQuery(query);
                }
            } else {
                solrQuery.setQuery("repository_id:" + repositoryId);
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

            UpdateResponse response = solrClient.deleteByQuery("repository_id:" + repositoryId);
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
