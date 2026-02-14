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
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - Webhook DAO service implementation
 ******************************************************************************/
package jp.aegif.nemaki.dao.impl.couch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.WebhookDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.model.couch.CouchWebhookDeliveryLog;
import jp.aegif.nemaki.webhook.WebhookDeliveryLog;

/**
 * CouchDB implementation of WebhookDaoService.
 * 
 * Uses CloudantClientPool for database operations.
 */
@Component
public class WebhookDaoServiceImpl implements WebhookDaoService {
    
    private static final Log log = LogFactory.getLog(WebhookDaoServiceImpl.class);
    
    private CloudantClientPool connectorPool;
    
    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }
    
    @Override
    public WebhookDeliveryLog createDeliveryLog(String repositoryId, WebhookDeliveryLog deliveryLog) {
        if (deliveryLog == null) {
            return null;
        }
        
        try {
            CouchWebhookDeliveryLog couchLog = new CouchWebhookDeliveryLog(deliveryLog);
            connectorPool.getClient(repositoryId).create(couchLog);
            
            log.debug("Created delivery log: " + couchLog.getId() + " for deliveryId: " + deliveryLog.getDeliveryId());
            return couchLog.convertToDeliveryLog();
        } catch (Exception e) {
            log.error("Failed to create delivery log: " + e.getMessage(), e);
            throw new RuntimeException("Failed to create delivery log", e);
        }
    }
    
    @Override
    public WebhookDeliveryLog updateDeliveryLog(String repositoryId, WebhookDeliveryLog deliveryLog) {
        if (deliveryLog == null || deliveryLog.getId() == null) {
            return null;
        }
        
        try {
            // Get existing document to get revision
            CouchWebhookDeliveryLog existing = connectorPool.getClient(repositoryId)
                .get(CouchWebhookDeliveryLog.class, deliveryLog.getId());
            
            if (existing == null) {
                log.warn("Delivery log not found for update: " + deliveryLog.getId());
                return null;
            }
            
            CouchWebhookDeliveryLog couchLog = new CouchWebhookDeliveryLog(deliveryLog);
            couchLog.setId(existing.getId());
            couchLog.setRevision(existing.getRevision());
            
            connectorPool.getClient(repositoryId).update(couchLog);
            
            log.debug("Updated delivery log: " + couchLog.getId());
            return couchLog.convertToDeliveryLog();
        } catch (NotFoundException e) {
            log.warn("Delivery log not found for update: " + deliveryLog.getId());
            return null;
        } catch (Exception e) {
            log.error("Failed to update delivery log: " + e.getMessage(), e);
            throw new RuntimeException("Failed to update delivery log", e);
        }
    }
    
    @Override
    public WebhookDeliveryLog getDeliveryLog(String repositoryId, String logId) {
        if (logId == null) {
            return null;
        }
        
        try {
            CouchWebhookDeliveryLog couchLog = connectorPool.getClient(repositoryId)
                .get(CouchWebhookDeliveryLog.class, logId);
            
            if (couchLog != null) {
                return couchLog.convertToDeliveryLog();
            }
            return null;
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            log.error("Failed to get delivery log: " + e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public WebhookDeliveryLog getDeliveryLogByDeliveryId(String repositoryId, String deliveryId) {
        if (deliveryId == null) {
            return null;
        }
        
        try {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("key", deliveryId);
            queryParams.put("include_docs", true);
            queryParams.put("limit", 1);
            
            ViewResult result = connectorPool.getClient(repositoryId)
                .queryView("_repo", "webhookDeliveryLogsByDeliveryId", queryParams);
            
            if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
                ViewResultRow row = result.getRows().get(0);
                if (row.getDoc() != null) {
                    Map<String, Object> docMap = extractDocMap(row.getDoc());
                    if (docMap != null) {
                        CouchWebhookDeliveryLog couchLog = new CouchWebhookDeliveryLog(docMap);
                        return couchLog.convertToDeliveryLog();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get delivery log by deliveryId: " + e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public List<WebhookDeliveryLog> getDeliveryLogs(String repositoryId, String objectId, int limit) {
        List<WebhookDeliveryLog> logs = new ArrayList<>();
        
        try {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("include_docs", true);
            queryParams.put("limit", Math.min(limit, 100));
            queryParams.put("descending", true);
            
            String viewName;
            if (objectId != null && !objectId.isEmpty()) {
                queryParams.put("key", objectId);
                viewName = "webhookDeliveryLogsByObjectId";
            } else {
                viewName = "webhookDeliveryLogsByTimestamp";
            }
            
            ViewResult result = connectorPool.getClient(repositoryId)
                .queryView("_repo", viewName, queryParams);
            
            if (result != null && result.getRows() != null) {
                for (ViewResultRow row : result.getRows()) {
                    if (row.getDoc() != null) {
                        Map<String, Object> docMap = extractDocMap(row.getDoc());
                        if (docMap != null) {
                            CouchWebhookDeliveryLog couchLog = new CouchWebhookDeliveryLog(docMap);
                            logs.add(couchLog.convertToDeliveryLog());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to get delivery logs: " + e.getMessage(), e);
        }
        
        return logs;
    }
    
    @Override
    public List<WebhookDeliveryLog> getDeliveryLogsByWebhookId(String repositoryId, String webhookId, int limit) {
        List<WebhookDeliveryLog> logs = new ArrayList<>();
        
        if (webhookId == null) {
            return logs;
        }
        
        try {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("key", webhookId);
            queryParams.put("include_docs", true);
            queryParams.put("limit", Math.min(limit, 100));
            queryParams.put("descending", true);
            
            ViewResult result = connectorPool.getClient(repositoryId)
                .queryView("_repo", "webhookDeliveryLogsByWebhookId", queryParams);
            
            if (result != null && result.getRows() != null) {
                for (ViewResultRow row : result.getRows()) {
                    if (row.getDoc() != null) {
                        Map<String, Object> docMap = extractDocMap(row.getDoc());
                        if (docMap != null) {
                            CouchWebhookDeliveryLog couchLog = new CouchWebhookDeliveryLog(docMap);
                            logs.add(couchLog.convertToDeliveryLog());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to get delivery logs by webhookId: " + e.getMessage(), e);
        }
        
        return logs;
    }
    
    @Override
    public List<WebhookDeliveryLog> getFailedDeliveryLogs(String repositoryId, int limit) {
        List<WebhookDeliveryLog> logs = new ArrayList<>();
        
        try {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("key", "FAILED");
            queryParams.put("include_docs", true);
            queryParams.put("limit", Math.min(limit, 100));
            
            ViewResult result = connectorPool.getClient(repositoryId)
                .queryView("_repo", "webhookDeliveryLogsByStatus", queryParams);
            
            if (result != null && result.getRows() != null) {
                for (ViewResultRow row : result.getRows()) {
                    if (row.getDoc() != null) {
                        Map<String, Object> docMap = extractDocMap(row.getDoc());
                        if (docMap != null) {
                            CouchWebhookDeliveryLog couchLog = new CouchWebhookDeliveryLog(docMap);
                            logs.add(couchLog.convertToDeliveryLog());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to get failed delivery logs: " + e.getMessage(), e);
        }
        
        return logs;
    }
    
    @Override
    public void deleteDeliveryLog(String repositoryId, String logId) {
        if (logId == null) {
            return;
        }
        
        try {
            CouchWebhookDeliveryLog existing = connectorPool.getClient(repositoryId)
                .get(CouchWebhookDeliveryLog.class, logId);
            
            if (existing != null) {
                connectorPool.getClient(repositoryId).delete(logId, existing.getRevision());
                log.debug("Deleted delivery log: " + logId);
            }
        } catch (NotFoundException e) {
            log.debug("Delivery log not found for deletion: " + logId);
        } catch (Exception e) {
            log.error("Failed to delete delivery log: " + e.getMessage(), e);
        }
    }
    
    @Override
    public int deleteOldDeliveryLogs(String repositoryId, long olderThanTimestamp) {
        int deletedCount = 0;
        
        try {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("endkey", olderThanTimestamp);
            queryParams.put("include_docs", true);
            queryParams.put("limit", 1000);
            
            ViewResult result = connectorPool.getClient(repositoryId)
                .queryView("_repo", "webhookDeliveryLogsByTimestamp", queryParams);
            
            if (result != null && result.getRows() != null) {
                for (ViewResultRow row : result.getRows()) {
                    if (row.getDoc() != null) {
                        Map<String, Object> docMap = extractDocMap(row.getDoc());
                        if (docMap != null) {
                            String id = (String) docMap.get("_id");
                            String rev = (String) docMap.get("_rev");
                            if (id != null && rev != null) {
                                try {
                                    connectorPool.getClient(repositoryId).delete(id, rev);
                                    deletedCount++;
                                } catch (Exception e) {
                                    log.warn("Failed to delete old delivery log: " + id);
                                }
                            }
                        }
                    }
                }
            }
            
            log.info("Deleted " + deletedCount + " old delivery logs older than " + olderThanTimestamp);
        } catch (Exception e) {
            log.error("Failed to delete old delivery logs: " + e.getMessage(), e);
        }
        
        return deletedCount;
    }
    
    @Override
    public WebhookDeliveryStats getDeliveryStats(String repositoryId, String webhookId) {
        WebhookDeliveryStats stats = new WebhookDeliveryStats();
        
        if (webhookId == null) {
            return stats;
        }
        
        try {
            List<WebhookDeliveryLog> logs = getDeliveryLogsByWebhookId(repositoryId, webhookId, 1000);
            
            long totalDeliveries = logs.size();
            long successCount = 0;
            long failureCount = 0;
            long totalResponseTime = 0;
            int responseTimeCount = 0;
            Long lastDeliveryTimestamp = null;
            
            for (WebhookDeliveryLog log : logs) {
                if (log.isSuccess()) {
                    successCount++;
                } else {
                    failureCount++;
                }
                
                if (log.getResponseTimeMs() != null) {
                    totalResponseTime += log.getResponseTimeMs();
                    responseTimeCount++;
                }
                
                if (log.getTimestamp() != null) {
                    long timestamp = log.getTimestamp().getTimeInMillis();
                    if (lastDeliveryTimestamp == null || timestamp > lastDeliveryTimestamp) {
                        lastDeliveryTimestamp = timestamp;
                    }
                }
            }
            
            stats.setTotalDeliveries(totalDeliveries);
            stats.setSuccessCount(successCount);
            stats.setFailureCount(failureCount);
            stats.setLastDeliveryTimestamp(lastDeliveryTimestamp);
            
            if (responseTimeCount > 0) {
                stats.setAverageResponseTimeMs(totalResponseTime / responseTimeCount);
            }
        } catch (Exception e) {
            log.error("Failed to get delivery stats: " + e.getMessage(), e);
        }
        
        return stats;
    }
    
    /**
     * Extract document map from Cloudant response.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDocMap(Object docObj) {
        if (docObj instanceof Map) {
            return (Map<String, Object>) docObj;
        } else if (docObj instanceof com.ibm.cloud.cloudant.v1.model.Document) {
            com.ibm.cloud.cloudant.v1.model.Document doc = (com.ibm.cloud.cloudant.v1.model.Document) docObj;
            Map<String, Object> docMap = new HashMap<>(doc.getProperties());
            if (doc.getId() != null) {
                docMap.put("_id", doc.getId());
            }
            if (doc.getRev() != null) {
                docMap.put("_rev", doc.getRev());
            }
            return docMap;
        }
        return null;
    }
}
