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
package jp.aegif.nemaki.businesslogic.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.WebhookService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.webhook.WebhookConfig;
import jp.aegif.nemaki.webhook.WebhookConfigParser;
import jp.aegif.nemaki.webhook.WebhookDeliveryLog;
import jp.aegif.nemaki.webhook.WebhookDeliveryService;
import jp.aegif.nemaki.webhook.WebhookEventMatcher;
import jp.aegif.nemaki.webhook.WebhookPayload;

/**
 * Implementation of WebhookService for managing webhook notifications.
 * 
 * This service integrates with ContentService to trigger webhooks when
 * content events occur. It handles:
 * - Checking for webhook configurations on content objects
 * - Building and dispatching webhook payloads asynchronously
 * - Managing delivery logs and retries
 * 
 * Phase 2 implementation focuses on ContentService integration.
 */
public class WebhookServiceImpl implements WebhookService {
    
    private static final Log log = LogFactory.getLog(WebhookServiceImpl.class);
    
    private static final String WEBHOOKABLE_SECONDARY_TYPE = "nemaki:webhookable";
    private static final String WEBHOOK_CONFIGS_PROPERTY = "nemaki:webhookConfigs";
    
    private ContentService contentService;
    private WebhookConfigParser configParser;
    private WebhookEventMatcher eventMatcher;
    private WebhookDeliveryService deliveryService;
    private WebhookDispatcher dispatcher;
    
    private ExecutorService executorService;
    
    public WebhookServiceImpl() {
        this.configParser = new WebhookConfigParser();
        this.eventMatcher = new WebhookEventMatcher();
        this.deliveryService = new WebhookDeliveryService();
        this.executorService = Executors.newFixedThreadPool(10);
        log.info("WebhookServiceImpl initialized with thread pool size: 10");
    }
    
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }
    
    public void setDispatcher(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }
    
    @Override
    public void triggerWebhook(CallContext callContext, String repositoryId, 
                               Content content, ChangeType changeType, 
                               Map<String, Object> additionalProperties) {
        
        if (content == null) {
            log.debug("triggerWebhook: content is null, skipping");
            return;
        }
        
        String eventType = convertChangeTypeToEventType(changeType);
        if (eventType == null) {
            log.debug("triggerWebhook: unsupported change type: " + changeType);
            return;
        }
        
        // Check if event type is supported in Phase 1
        if (!eventMatcher.isValidEventType(eventType)) {
            log.debug("triggerWebhook: event type not supported in Phase 1: " + eventType);
            return;
        }
        
        // Get webhook configurations for this content
        List<WebhookConfig> configs = getWebhookConfigs(repositoryId, content);
        
        // Also get inherited configurations from parent folders
        List<WebhookConfig> inheritedConfigs = getInheritedWebhookConfigs(repositoryId, content);
        configs.addAll(inheritedConfigs);
        
        if (configs.isEmpty()) {
            log.debug("triggerWebhook: no webhook configs found for object: " + content.getId());
            return;
        }
        
        // Find matching configurations for this event type
        List<WebhookConfig> matchingConfigs = eventMatcher.findMatchingConfigs(configs, eventType);
        
        if (matchingConfigs.isEmpty()) {
            log.debug("triggerWebhook: no matching configs for event: " + eventType);
            return;
        }
        
        log.info("triggerWebhook: found " + matchingConfigs.size() + 
                 " matching configs for event: " + eventType + " on object: " + content.getId());
        
        // Build properties map for payload
        Map<String, Object> properties = buildPropertiesMap(content, additionalProperties);
        
        // Get change token
        String changeToken = content.getChangeToken();
        
        // Dispatch webhooks asynchronously
        for (WebhookConfig config : matchingConfigs) {
            dispatchWebhookAsync(callContext, repositoryId, content, eventType, 
                                 config, properties, changeToken);
        }
    }
    
    /**
     * Dispatch a webhook asynchronously
     */
    private void dispatchWebhookAsync(CallContext callContext, String repositoryId,
                                       Content content, String eventType,
                                       WebhookConfig config, Map<String, Object> properties,
                                       String changeToken) {
        
        executorService.submit(() -> {
            try {
                // Build payload
                WebhookPayload payload = deliveryService.buildPayload(
                    eventType, content.getId(), repositoryId, properties, changeToken
                );
                
                // Add additional context
                if (content.getParentId() != null) {
                    payload.setParentId(content.getParentId());
                }
                if (callContext != null && callContext.getUsername() != null) {
                    payload.setUserId(callContext.getUsername());
                }
                
                // Serialize payload
                String payloadJson = deliveryService.serializePayload(payload);
                
                // Generate auth headers
                Map<String, String> headers = deliveryService.generateAuthHeaders(config);
                
                // Compute HMAC signature if secret is configured
                String signature = null;
                if (config.getSecret() != null && !config.getSecret().isEmpty()) {
                    signature = deliveryService.computeHmacSignature(payloadJson, config.getSecret());
                }
                
                // Add standard webhook headers
                headers.put("Content-Type", "application/json");
                headers.put("X-NemakiWare-Event", eventType);
                headers.put("X-NemakiWare-Delivery", payload.getDeliveryId());
                headers.put("X-NemakiWare-Timestamp", String.valueOf(payload.getTimestamp()));
                if (signature != null) {
                    headers.put("X-NemakiWare-Signature", "sha256=" + signature);
                }
                
                // Dispatch via dispatcher if available, otherwise log
                if (dispatcher != null) {
                    dispatcher.dispatch(config.getUrl(), payloadJson, headers, config);
                } else {
                    log.info("Webhook dispatch (no dispatcher configured): " +
                             "url=" + config.getUrl() + ", event=" + eventType + 
                             ", deliveryId=" + payload.getDeliveryId());
                }
                
            } catch (Exception e) {
                log.error("Failed to dispatch webhook for config: " + config.getId(), e);
            }
        });
    }
    
    @Override
    public boolean hasWebhookConfig(String repositoryId, Content content) {
        if (content == null) {
            return false;
        }
        
        // Check if content has nemaki:webhookable secondary type
        List<String> secondaryTypes = content.getSecondaryIds();
        if (secondaryTypes == null || !secondaryTypes.contains(WEBHOOKABLE_SECONDARY_TYPE)) {
            return false;
        }
        
        // Check if webhookConfigs property exists and has valid configs
        List<WebhookConfig> configs = getWebhookConfigs(repositoryId, content);
        return !configs.isEmpty();
    }
    
    @Override
    public List<WebhookConfig> getWebhookConfigs(String repositoryId, Content content) {
        List<WebhookConfig> result = new ArrayList<>();
        
        if (content == null) {
            return result;
        }
        
        // Check for secondary type
        List<String> secondaryTypes = content.getSecondaryIds();
        if (secondaryTypes == null || !secondaryTypes.contains(WEBHOOKABLE_SECONDARY_TYPE)) {
            return result;
        }
        
        // Get webhookConfigs property
        Object configsValue = getSubTypePropertyValue(content, WEBHOOK_CONFIGS_PROPERTY);
        
        if (configsValue == null) {
            return result;
        }
        
        // Parse JSON configs
        String configsJson = configsValue.toString();
        try {
            List<WebhookConfig> configs = configParser.parse(configsJson);
            // Filter to only enabled and valid configs
            for (WebhookConfig config : configs) {
                if (config.isEnabled() && config.isValid()) {
                    // Set source object ID for tracking
                    config.setSourceObjectId(content.getId());
                    result.add(config);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse webhook configs for object: " + content.getId(), e);
        }
        
        return result;
    }
    
    @Override
    public List<WebhookConfig> getInheritedWebhookConfigs(String repositoryId, Content content) {
        List<WebhookConfig> result = new ArrayList<>();
        
        if (content == null || contentService == null) {
            return result;
        }
        
        // Traverse up the folder hierarchy
        String parentId = content.getParentId();
        int depth = 0;
        int maxDepth = 50; // Absolute max depth to prevent infinite loops
        
        while (parentId != null && depth < maxDepth) {
            Content parent = contentService.getContent(repositoryId, parentId);
            if (parent == null) {
                break;
            }
            
            // Get configs from parent
            List<WebhookConfig> parentConfigs = getWebhookConfigs(repositoryId, parent);
            
            // Filter to only configs with includeChildren=true
            for (WebhookConfig config : parentConfigs) {
                if (config.isIncludeChildren()) {
                    // Check maxDepth
                    Integer configMaxDepth = config.getMaxDepth();
                    if (configMaxDepth == null || depth < configMaxDepth) {
                        result.add(config);
                    }
                }
            }
            
            // Move to next parent
            if (parent instanceof Folder) {
                parentId = ((Folder) parent).getParentId();
            } else {
                parentId = parent.getParentId();
            }
            depth++;
        }
        
        return result;
    }
    
    @Override
    public List<WebhookDeliveryLog> getDeliveryLogs(String repositoryId, 
                                                     String objectId, int limit) {
        // TODO: Implement when WebhookDeliveryLogDao is available
        log.debug("getDeliveryLogs: not yet implemented");
        return new ArrayList<>();
    }
    
    @Override
    public List<WebhookDeliveryLog> getDeliveryLogsByWebhookId(String repositoryId,
                                                                String webhookId, int limit) {
        // TODO: Implement when WebhookDeliveryLogDao is available
        log.debug("getDeliveryLogsByWebhookId: not yet implemented");
        return new ArrayList<>();
    }
    
    @Override
    public WebhookDeliveryLog retryDelivery(String repositoryId, String deliveryId) {
        // TODO: Implement when WebhookDeliveryLogDao is available
        log.debug("retryDelivery: not yet implemented");
        return null;
    }
    
    @Override
    public Map<String, Object> getDeliveryStatistics(String repositoryId, String webhookId) {
        // TODO: Implement when WebhookDeliveryLogDao is available
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDeliveries", 0);
        stats.put("successCount", 0);
        stats.put("failureCount", 0);
        return stats;
    }
    
    /**
     * Convert CMIS ChangeType to webhook event type string
     */
    private String convertChangeTypeToEventType(ChangeType changeType) {
        if (changeType == null) {
            return null;
        }
        switch (changeType) {
            case CREATED:
                return "CREATED";
            case UPDATED:
                return "UPDATED";
            case DELETED:
                return "DELETED";
            case SECURITY:
                return "SECURITY";
            default:
                return null;
        }
    }
    
    /**
     * Build properties map from content for payload
     */
    private Map<String, Object> buildPropertiesMap(Content content, 
                                                    Map<String, Object> additionalProperties) {
        Map<String, Object> properties = new HashMap<>();
        
        // Add basic properties
        if (content.getName() != null) {
            properties.put("cmis:name", content.getName());
        }
        if (content.getObjectType() != null) {
            properties.put("cmis:objectTypeId", content.getObjectType());
        }
        if (content.getCreator() != null) {
            properties.put("cmis:createdBy", content.getCreator());
        }
        if (content.getModifier() != null) {
            properties.put("cmis:lastModifiedBy", content.getModifier());
        }
        if (content.getCreated() != null) {
            properties.put("cmis:creationDate", content.getCreated().getTimeInMillis());
        }
        if (content.getModified() != null) {
            properties.put("cmis:lastModificationDate", content.getModified().getTimeInMillis());
        }
        
        // Add additional properties if provided
        if (additionalProperties != null) {
            properties.putAll(additionalProperties);
        }
        
        return properties;
    }
    
    /**
     * Get a property value from content's subTypeProperties by key.
     * 
     * @param content The content object
     * @param propertyKey The property key to search for
     * @return The property value, or null if not found
     */
    private Object getSubTypePropertyValue(Content content, String propertyKey) {
        if (content == null || propertyKey == null) {
            return null;
        }
        
        List<Property> subTypeProperties = content.getSubTypeProperties();
        if (subTypeProperties == null) {
            return null;
        }
        
        for (Property property : subTypeProperties) {
            if (property != null && propertyKey.equals(property.getKey())) {
                return property.getValue();
            }
        }
        
        return null;
    }
    
    /**
     * Shutdown the executor service
     */
    public void shutdown() {
        if (executorService != null) {
            executorService.shutdown();
            log.info("WebhookServiceImpl executor service shutdown");
        }
    }
    
    /**
     * Interface for webhook dispatcher (to be implemented with HTTP client)
     */
    public interface WebhookDispatcher {
        void dispatch(String url, String payload, Map<String, String> headers, WebhookConfig config);
    }
}
