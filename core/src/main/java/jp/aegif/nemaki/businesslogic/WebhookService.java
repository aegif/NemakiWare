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
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic;

import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.webhook.WebhookConfig;
import jp.aegif.nemaki.webhook.WebhookDeliveryLog;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Service for managing webhook notifications.
 * 
 * This service handles:
 * - Triggering webhooks when content events occur
 * - Managing webhook configurations
 * - Tracking delivery logs
 * - Retrying failed deliveries
 * 
 * Phase 1 supports: CREATED, UPDATED, DELETED, SECURITY events
 */
public interface WebhookService {
    
    /**
     * Trigger webhook notifications for a content event.
     * 
     * This method is called from ContentService when events occur.
     * It checks if the content has webhook configurations (via nemaki:webhookable
     * secondary type) and dispatches notifications asynchronously.
     * 
     * @param callContext The call context
     * @param repositoryId The repository ID
     * @param content The content that triggered the event
     * @param changeType The type of change (CREATED, UPDATED, DELETED, SECURITY)
     * @param additionalProperties Optional additional properties to include in payload
     */
    void triggerWebhook(CallContext callContext, String repositoryId, 
                        Content content, ChangeType changeType, 
                        Map<String, Object> additionalProperties);
    
    /**
     * Check if a content object has webhook configurations.
     * 
     * @param repositoryId The repository ID
     * @param content The content to check
     * @return true if the content has the nemaki:webhookable secondary type
     *         and at least one enabled webhook configuration
     */
    boolean hasWebhookConfig(String repositoryId, Content content);
    
    /**
     * Get webhook configurations for a content object.
     * 
     * @param repositoryId The repository ID
     * @param content The content
     * @return List of webhook configurations, or empty list if none
     */
    List<WebhookConfig> getWebhookConfigs(String repositoryId, Content content);
    
    /**
     * Get inherited webhook configurations from parent folders.
     * 
     * This method traverses up the folder hierarchy to find webhook
     * configurations with includeChildren=true that apply to this content.
     * 
     * @param repositoryId The repository ID
     * @param content The content
     * @return List of inherited webhook configurations
     */
    List<WebhookConfig> getInheritedWebhookConfigs(String repositoryId, Content content);
    
    /**
     * Get delivery logs for a specific object.
     * 
     * @param repositoryId The repository ID
     * @param objectId The object ID
     * @param limit Maximum number of logs to return
     * @return List of delivery logs, ordered by timestamp descending
     */
    List<WebhookDeliveryLog> getDeliveryLogs(String repositoryId, 
                                              String objectId, int limit);
    
    /**
     * Get delivery logs for a specific webhook configuration.
     * 
     * @param repositoryId The repository ID
     * @param webhookId The webhook configuration ID
     * @param limit Maximum number of logs to return
     * @return List of delivery logs, ordered by timestamp descending
     */
    List<WebhookDeliveryLog> getDeliveryLogsByWebhookId(String repositoryId,
                                                         String webhookId, int limit);
    
    /**
     * Manually retry a failed delivery.
     * 
     * This creates a new delivery attempt with the same deliveryId
     * but a new attemptId for idempotency tracking.
     * 
     * @param repositoryId The repository ID
     * @param deliveryId The original delivery ID to retry
     * @return The new delivery log entry
     */
    WebhookDeliveryLog retryDelivery(String repositoryId, String deliveryId);
    
    /**
     * Get statistics for webhook deliveries.
     * 
     * @param repositoryId The repository ID
     * @param webhookId Optional webhook ID to filter (null for all)
     * @return Map containing statistics (totalDeliveries, successCount, failureCount, etc.)
     */
    Map<String, Object> getDeliveryStatistics(String repositoryId, String webhookId);
}
