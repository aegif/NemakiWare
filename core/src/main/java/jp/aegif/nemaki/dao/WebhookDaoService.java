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
 *     aegif - Webhook DAO service interface
 ******************************************************************************/
package jp.aegif.nemaki.dao;

import java.util.List;

import jp.aegif.nemaki.webhook.WebhookDeliveryLog;

/**
 * DAO Service interface for Webhook delivery logs.
 * 
 * Provides persistence operations for WebhookDeliveryLog entities.
 */
public interface WebhookDaoService {
    
    /**
     * Create a new delivery log entry.
     * 
     * @param repositoryId The repository ID
     * @param log The delivery log to create
     * @return The created delivery log with ID assigned
     */
    WebhookDeliveryLog createDeliveryLog(String repositoryId, WebhookDeliveryLog log);
    
    /**
     * Update an existing delivery log entry.
     * 
     * @param repositoryId The repository ID
     * @param log The delivery log to update
     * @return The updated delivery log
     */
    WebhookDeliveryLog updateDeliveryLog(String repositoryId, WebhookDeliveryLog log);
    
    /**
     * Get a delivery log by its ID.
     * 
     * @param repositoryId The repository ID
     * @param logId The delivery log ID
     * @return The delivery log, or null if not found
     */
    WebhookDeliveryLog getDeliveryLog(String repositoryId, String logId);
    
    /**
     * Get a delivery log by delivery ID.
     * 
     * @param repositoryId The repository ID
     * @param deliveryId The delivery ID
     * @return The delivery log, or null if not found
     */
    WebhookDeliveryLog getDeliveryLogByDeliveryId(String repositoryId, String deliveryId);
    
    /**
     * Get delivery logs for a specific object.
     * 
     * @param repositoryId The repository ID
     * @param objectId The object ID (optional, null for all objects)
     * @param limit Maximum number of logs to return
     * @return List of delivery logs, ordered by timestamp descending
     */
    List<WebhookDeliveryLog> getDeliveryLogs(String repositoryId, String objectId, int limit);
    
    /**
     * Get delivery logs for a specific webhook configuration.
     * 
     * @param repositoryId The repository ID
     * @param webhookId The webhook configuration ID
     * @param limit Maximum number of logs to return
     * @return List of delivery logs, ordered by timestamp descending
     */
    List<WebhookDeliveryLog> getDeliveryLogsByWebhookId(String repositoryId, String webhookId, int limit);
    
    /**
     * Get failed delivery logs that are eligible for retry.
     * 
     * @param repositoryId The repository ID
     * @param limit Maximum number of logs to return
     * @return List of failed delivery logs
     */
    List<WebhookDeliveryLog> getFailedDeliveryLogs(String repositoryId, int limit);
    
    /**
     * Delete a delivery log.
     * 
     * @param repositoryId The repository ID
     * @param logId The delivery log ID
     */
    void deleteDeliveryLog(String repositoryId, String logId);
    
    /**
     * Delete old delivery logs (for cleanup).
     * 
     * @param repositoryId The repository ID
     * @param olderThanTimestamp Delete logs older than this timestamp (milliseconds)
     * @return Number of deleted logs
     */
    int deleteOldDeliveryLogs(String repositoryId, long olderThanTimestamp);
    
    /**
     * Get delivery statistics for a webhook.
     * 
     * @param repositoryId The repository ID
     * @param webhookId The webhook configuration ID
     * @return Statistics including total, success, and failure counts
     */
    WebhookDeliveryStats getDeliveryStats(String repositoryId, String webhookId);
    
    /**
     * Statistics for webhook deliveries.
     */
    public static class WebhookDeliveryStats {
        private long totalDeliveries;
        private long successCount;
        private long failureCount;
        private Long averageResponseTimeMs;
        private Long lastDeliveryTimestamp;
        
        public long getTotalDeliveries() {
            return totalDeliveries;
        }
        
        public void setTotalDeliveries(long totalDeliveries) {
            this.totalDeliveries = totalDeliveries;
        }
        
        public long getSuccessCount() {
            return successCount;
        }
        
        public void setSuccessCount(long successCount) {
            this.successCount = successCount;
        }
        
        public long getFailureCount() {
            return failureCount;
        }
        
        public void setFailureCount(long failureCount) {
            this.failureCount = failureCount;
        }
        
        public Long getAverageResponseTimeMs() {
            return averageResponseTimeMs;
        }
        
        public void setAverageResponseTimeMs(Long averageResponseTimeMs) {
            this.averageResponseTimeMs = averageResponseTimeMs;
        }
        
        public Long getLastDeliveryTimestamp() {
            return lastDeliveryTimestamp;
        }
        
        public void setLastDeliveryTimestamp(Long lastDeliveryTimestamp) {
            this.lastDeliveryTimestamp = lastDeliveryTimestamp;
        }
    }
}
