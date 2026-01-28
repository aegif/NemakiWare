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
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.WebhookService;
import jp.aegif.nemaki.dao.WebhookDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.webhook.ChildEvent;
import jp.aegif.nemaki.webhook.ChildEventBatch;
import jp.aegif.nemaki.webhook.ChildEventBatchProcessor;
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
    
    /**
     * Set of CMIS property keys that are protected from being overwritten by additionalProperties.
     * These core metadata properties should always reflect the actual content state.
     * Includes versioning properties to prevent confusion in version-aware webhook payloads.
     */
    private static final Set<String> PROTECTED_CMIS_PROPERTIES = Set.of(
        "cmis:name",
        "cmis:objectId",
        "cmis:objectTypeId",
        "cmis:baseTypeId",
        "cmis:createdBy",
        "cmis:creationDate",
        "cmis:lastModifiedBy",
        "cmis:lastModificationDate",
        "cmis:changeToken",
        "cmis:parentId",
        // Versioning properties
        "cmis:versionSeriesId",
        "cmis:versionLabel",
        "cmis:isLatestVersion",
        "cmis:checkinComment"
    );
    
    private static final int THREAD_POOL_CORE_SIZE = 5;
    private static final int THREAD_POOL_MAX_SIZE = 10;
    private static final int THREAD_POOL_QUEUE_SIZE = 100;
    private static final long THREAD_POOL_KEEP_ALIVE_SECONDS = 60;
    
    private ContentService contentService;
    private WebhookConfigParser configParser;
    private WebhookEventMatcher eventMatcher;
    private WebhookDeliveryService deliveryService;
    private WebhookDispatcher dispatcher;
    private WebhookDaoService webhookDaoService;
    
    private ExecutorService executorService;
    private ChildEventBatchProcessor childEventBatchProcessor;
    
    public WebhookServiceImpl() {
        this.configParser = new WebhookConfigParser();
        this.eventMatcher = new WebhookEventMatcher();
        this.deliveryService = new WebhookDeliveryService();
        
        // Use bounded ThreadPoolExecutor to prevent memory exhaustion
        // when external webhook endpoints are slow or unresponsive
        this.executorService = new ThreadPoolExecutor(
            THREAD_POOL_CORE_SIZE,
            THREAD_POOL_MAX_SIZE,
            THREAD_POOL_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(THREAD_POOL_QUEUE_SIZE),
            new WebhookRejectedExecutionHandler()
        );
        
        // Initialize CHILD_* event batch processor
        this.childEventBatchProcessor = new ChildEventBatchProcessor(this::deliverChildEventBatch);
        
        log.info("WebhookServiceImpl initialized with bounded thread pool: " +
                 "core=" + THREAD_POOL_CORE_SIZE + ", max=" + THREAD_POOL_MAX_SIZE + 
                 ", queue=" + THREAD_POOL_QUEUE_SIZE);
    }
    
    /**
     * Handler for rejected webhook tasks when the queue is full.
     * Logs a warning and discards the task to prevent blocking the main thread.
     */
    private static class WebhookRejectedExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("Webhook task rejected due to queue overflow. " +
                     "Queue size: " + executor.getQueue().size() + 
                     ", Active threads: " + executor.getActiveCount() +
                     ". Consider increasing queue size or reducing webhook load.");
        }
    }
    
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }
    
    public void setDispatcher(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }
    
    public void setWebhookDaoService(WebhookDaoService webhookDaoService) {
        this.webhookDaoService = webhookDaoService;
    }
    
    @Override
    public void triggerWebhook(CallContext callContext, String repositoryId, 
                               Content content, ChangeType changeType, 
                               Map<String, Object> additionalProperties) {
        
        if (content == null) {
            log.debug("triggerWebhook: content is null, skipping");
            return;
        }
        
        if (repositoryId == null || repositoryId.isEmpty()) {
            log.debug("triggerWebhook: repositoryId is null or empty, skipping");
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
        
        // Trigger CHILD_* events on parent folder if applicable
        triggerChildEventOnParent(callContext, repositoryId, content, eventType, additionalProperties);
    }
    
    /**
     * Trigger CHILD_* event on parent folder when content is created, updated, or deleted.
     * The event is queued for batch processing to reduce webhook traffic.
     */
    private void triggerChildEventOnParent(CallContext callContext, String repositoryId,
                                            Content content, String eventType,
                                            Map<String, Object> additionalProperties) {
        
        String parentId = content.getParentId();
        if (parentId == null || parentId.isEmpty()) {
            log.debug("triggerChildEventOnParent: no parent folder, skipping");
            return;
        }
        
        // Convert standard event type to CHILD_* event type
        String childEventType = eventMatcher.toChildEventType(eventType);
        if (childEventType == null) {
            log.debug("triggerChildEventOnParent: no CHILD_* mapping for event: " + eventType);
            return;
        }
        
        // Get parent folder to check for CHILD_* webhook configs
        Content parentFolder = contentService != null ? contentService.getContent(repositoryId, parentId) : null;
        if (parentFolder == null) {
            log.debug("triggerChildEventOnParent: parent folder not found: " + parentId);
            return;
        }
        
        // Get webhook configs from parent folder that listen for CHILD_* events
        List<WebhookConfig> parentConfigs = getWebhookConfigs(repositoryId, parentFolder);
        List<WebhookConfig> matchingChildConfigs = eventMatcher.findMatchingConfigs(parentConfigs, childEventType);
        
        if (matchingChildConfigs.isEmpty()) {
            log.debug("triggerChildEventOnParent: no CHILD_* configs on parent folder: " + parentId);
            return;
        }
        
        // Create child event and queue for batch processing
        ChildEvent childEvent = new ChildEvent();
        childEvent.setParentFolderId(parentId);
        childEvent.setParentFolderPath(parentFolder.getName());
        childEvent.setObjectId(content.getId());
        childEvent.setObjectName(content.getName());
        childEvent.setObjectType(content.getObjectType());
        childEvent.setEventType(childEventType);
        childEvent.setChangeToken(content.getChangeToken());
        if (callContext != null && callContext.getUsername() != null) {
            childEvent.setUserId(callContext.getUsername());
        }
        
        // Add properties
        Map<String, Object> properties = buildPropertiesMap(content, additionalProperties);
        childEvent.setProperties(properties);
        
        // Queue event for batch processing
        boolean queued = childEventBatchProcessor.queueEvent(repositoryId, childEvent);
        if (queued) {
            log.debug("triggerChildEventOnParent: queued " + childEventType + 
                      " event for object " + content.getId() + " in folder " + parentId);
        } else {
            log.warn("triggerChildEventOnParent: failed to queue " + childEventType + 
                     " event (circuit breaker may be open)");
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
        
        // Guard against null/empty repositoryId to prevent errors in contentService.getContent()
        if (repositoryId == null || repositoryId.isEmpty()) {
            return result;
        }
        
        // Traverse up the folder hierarchy
        // Distance is measured from the webhook config folder to the event object:
        // - Parent folder (1 level up) = distance 1 from parent to event object
        // - Grandparent folder (2 levels up) = distance 2 from grandparent to event object
        // maxDepth=1 means "direct children only" (distance 1)
        // maxDepth=2 means "children and grandchildren" (distance 1-2)
        String parentId = content.getParentId();
        int distance = 1; // Start at 1: parent folder is distance 1 from event object
        int absoluteMaxDepth = 50; // Absolute max to prevent infinite loops
        
        while (parentId != null && distance <= absoluteMaxDepth) {
            Content parent = contentService.getContent(repositoryId, parentId);
            if (parent == null) {
                break;
            }
            
            // Get configs from parent
            List<WebhookConfig> parentConfigs = getWebhookConfigs(repositoryId, parent);
            
            // Filter to only configs with includeChildren=true
            for (WebhookConfig config : parentConfigs) {
                if (config.isIncludeChildren()) {
                    // Check maxDepth (null = unlimited, N = up to N levels deep)
                    // maxDepth=1 means direct children only (distance=1)
                    // maxDepth=2 means children and grandchildren (distance=1,2)
                    Integer configMaxDepth = config.getMaxDepth();
                    if (configMaxDepth == null || distance <= configMaxDepth) {
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
            distance++;
        }
        
        return result;
    }
    
    @Override
    public List<WebhookDeliveryLog> getDeliveryLogs(String repositoryId, 
                                                     String objectId, int limit) {
        if (webhookDaoService == null) {
            log.debug("getDeliveryLogs: WebhookDaoService not available");
            return new ArrayList<>();
        }
        return webhookDaoService.getDeliveryLogs(repositoryId, objectId, limit);
    }
    
    @Override
    public List<WebhookDeliveryLog> getDeliveryLogsByWebhookId(String repositoryId,
                                                                String webhookId, int limit) {
        if (webhookDaoService == null) {
            log.debug("getDeliveryLogsByWebhookId: WebhookDaoService not available");
            return new ArrayList<>();
        }
        return webhookDaoService.getDeliveryLogsByWebhookId(repositoryId, webhookId, limit);
    }
    
    @Override
    public WebhookDeliveryLog retryDelivery(String repositoryId, String deliveryId) {
        if (webhookDaoService == null) {
            log.warn("retryDelivery: WebhookDaoService not available");
            return null;
        }
        
        // Get the original delivery log
        WebhookDeliveryLog originalLog = webhookDaoService.getDeliveryLogByDeliveryId(repositoryId, deliveryId);
        if (originalLog == null) {
            log.warn("retryDelivery: Delivery log not found for deliveryId: " + deliveryId);
            return null;
        }
        
        // Create a new attempt
        WebhookDeliveryLog retryLog = new WebhookDeliveryLog();
        retryLog.setDeliveryId(deliveryId);
        retryLog.setWebhookId(originalLog.getWebhookId());
        retryLog.setObjectId(originalLog.getObjectId());
        retryLog.setRepositoryId(repositoryId);
        retryLog.setWebhookUrl(originalLog.getWebhookUrl());
        retryLog.setEventType(originalLog.getEventType());
        retryLog.setAttemptNumber(originalLog.getAttemptNumber() + 1);
        retryLog.setChangeToken(originalLog.getChangeToken());
        retryLog.setStatus(WebhookDeliveryLog.DeliveryStatus.PENDING);
        retryLog.generateAttemptId();
        
        // Save the new attempt log
        WebhookDeliveryLog savedLog = webhookDaoService.createDeliveryLog(repositoryId, retryLog);
        
        // Queue for async delivery (actual dispatch would happen here)
        log.info("Retry queued for deliveryId: " + deliveryId + ", attemptNumber: " + retryLog.getAttemptNumber());
        
        return savedLog;
    }
    
    @Override
    public Map<String, Object> getDeliveryStatistics(String repositoryId, String webhookId) {
        Map<String, Object> stats = new HashMap<>();
        
        if (webhookDaoService == null) {
            log.debug("getDeliveryStatistics: WebhookDaoService not available");
            stats.put("totalDeliveries", 0);
            stats.put("successCount", 0);
            stats.put("failureCount", 0);
            return stats;
        }
        
        WebhookDaoService.WebhookDeliveryStats daoStats = webhookDaoService.getDeliveryStats(repositoryId, webhookId);
        stats.put("totalDeliveries", daoStats.getTotalDeliveries());
        stats.put("successCount", daoStats.getSuccessCount());
        stats.put("failureCount", daoStats.getFailureCount());
        if (daoStats.getAverageResponseTimeMs() != null) {
            stats.put("averageResponseTimeMs", daoStats.getAverageResponseTimeMs());
        }
        if (daoStats.getLastDeliveryTimestamp() != null) {
            stats.put("lastDeliveryTimestamp", daoStats.getLastDeliveryTimestamp());
        }
        return stats;
    }
    
    @Override
    public WebhookDeliveryLog testWebhook(String repositoryId, String url, String secret) {
        WebhookDeliveryLog result = new WebhookDeliveryLog();
        result.setDeliveryId(java.util.UUID.randomUUID().toString());
        result.setWebhookUrl(url);
        result.setEventType("TEST");
        result.setAttemptNumber(1);
        result.setTimestamp(new java.util.GregorianCalendar());
        
        try {
            // Build test payload
            WebhookPayload testPayload = deliveryService.buildPayload(
                "TEST", "test-object-id", repositoryId, 
                new HashMap<>(), "test-change-token"
            );
            String payloadJson = deliveryService.serializePayload(testPayload);
            
            // Generate headers
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("X-NemakiWare-Event", "TEST");
            headers.put("X-NemakiWare-Delivery", testPayload.getDeliveryId());
            headers.put("X-NemakiWare-Timestamp", String.valueOf(testPayload.getTimestamp()));
            
            // Add signature if secret provided
            if (secret != null && !secret.isEmpty()) {
                String signature = deliveryService.computeHmacSignature(payloadJson, secret);
                headers.put("X-NemakiWare-Signature", "sha256=" + signature);
            }
            
            // Dispatch test webhook synchronously using dispatchSync
            if (dispatcher != null) {
                // Create a temporary config for the test
                WebhookConfig testConfig = new WebhookConfig.Builder()
                    .id("test-" + testPayload.getDeliveryId())
                    .url(url)
                    .enabled(true)
                    .events(java.util.Arrays.asList("TEST"))
                    .secret(secret)
                    .retryCount(0)
                    .build();
                
                // Use synchronous dispatch to get actual HTTP result
                WebhookDeliveryLog dispatchResult = dispatcher.dispatchSync(url, payloadJson, headers, testConfig);
                result.setSuccess(dispatchResult.isSuccess());
                result.setStatusCode(dispatchResult.getStatusCode());
                result.setResponseBody(dispatchResult.getResponseBody());
            } else {
                log.warn("testWebhook: No dispatcher configured");
                result.setSuccess(false);
                result.setStatusCode(0);
                result.setResponseBody("Error: No dispatcher configured");
            }
        } catch (Exception e) {
            log.error("testWebhook failed: " + e.getMessage(), e);
            result.setSuccess(false);
            result.setStatusCode(0);
            result.setResponseBody("Error: " + e.getMessage());
        }
        
        return result;
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
     * Build properties map from content for payload.
     * 
     * Basic CMIS properties are protected from being overwritten by additionalProperties
     * to prevent accidental or malicious modification of core metadata.
     */
    private Map<String, Object> buildPropertiesMap(Content content, 
                                                    Map<String, Object> additionalProperties) {
        Map<String, Object> properties = new HashMap<>();
        
        // Add additional properties first (if provided)
        // These can be overwritten by basic CMIS properties below
        if (additionalProperties != null) {
            for (Map.Entry<String, Object> entry : additionalProperties.entrySet()) {
                // Only add if not a protected CMIS property
                if (!isProtectedProperty(entry.getKey())) {
                    properties.put(entry.getKey(), entry.getValue());
                }
            }
        }
        
        // Add basic CMIS properties (these take precedence and cannot be overwritten)
        // Include all essential properties that webhook consumers typically need
        
        // Core identification properties
        if (content.getId() != null) {
            properties.put("cmis:objectId", content.getId());
        }
        if (content.getName() != null) {
            properties.put("cmis:name", content.getName());
        }
        if (content.getObjectType() != null) {
            properties.put("cmis:objectTypeId", content.getObjectType());
        }
        if (content.getType() != null) {
            properties.put("cmis:baseTypeId", content.getType());
        }
        if (content.getParentId() != null) {
            properties.put("cmis:parentId", content.getParentId());
        }
        
        // Audit properties
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
        if (content.getChangeToken() != null) {
            properties.put("cmis:changeToken", content.getChangeToken());
        }
        
        return properties;
    }
    
    /**
     * Check if a property key is a protected CMIS property that should not be overwritten.
     * Uses PROTECTED_CMIS_PROPERTIES set for O(1) lookup and easy maintenance.
     */
    private boolean isProtectedProperty(String key) {
        return key != null && PROTECTED_CMIS_PROPERTIES.contains(key);
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
     * Deliver a batch of CHILD_* events as a single webhook payload.
     * Called by ChildEventBatchProcessor when a batch is ready.
     */
    private void deliverChildEventBatch(ChildEventBatch batch) {
        if (batch == null || batch.isEmpty()) {
            log.debug("deliverChildEventBatch: batch is null or empty, skipping");
            return;
        }
        
        String repositoryId = batch.getRepositoryId();
        String parentFolderId = batch.getParentFolderId();
        
        log.info("deliverChildEventBatch: delivering batch with " + batch.getEventCount() + 
                 " events for folder: " + parentFolderId);
        
        // Get parent folder to retrieve webhook configs
        Content parentFolder = contentService != null ? contentService.getContent(repositoryId, parentFolderId) : null;
        if (parentFolder == null) {
            log.warn("deliverChildEventBatch: parent folder not found: " + parentFolderId);
            return;
        }
        
        // Get webhook configs that listen for CHILD_* events
        List<WebhookConfig> parentConfigs = getWebhookConfigs(repositoryId, parentFolder);
        
        // Find configs that match any of the CHILD_* event types in the batch
        List<WebhookConfig> matchingConfigs = new ArrayList<>();
        for (ChildEvent event : batch.getEvents()) {
            List<WebhookConfig> configs = eventMatcher.findMatchingConfigs(parentConfigs, event.getEventType());
            for (WebhookConfig config : configs) {
                if (!matchingConfigs.contains(config)) {
                    matchingConfigs.add(config);
                }
            }
        }
        
        // Also check for CHILD_BATCH event type
        List<WebhookConfig> batchConfigs = eventMatcher.findMatchingConfigs(parentConfigs, "CHILD_BATCH");
        for (WebhookConfig config : batchConfigs) {
            if (!matchingConfigs.contains(config)) {
                matchingConfigs.add(config);
            }
        }
        
        if (matchingConfigs.isEmpty()) {
            log.debug("deliverChildEventBatch: no matching configs for batch");
            return;
        }
        
        // Build batch payload
        Map<String, Object> batchPayload = buildChildBatchPayload(batch);
        
        // Dispatch to each matching config
        for (WebhookConfig config : matchingConfigs) {
            dispatchChildBatchAsync(repositoryId, batch, config, batchPayload);
        }
    }
    
    /**
     * Build the payload for a CHILD_BATCH webhook delivery.
     */
    private Map<String, Object> buildChildBatchPayload(ChildEventBatch batch) {
        Map<String, Object> payload = new HashMap<>();
        
        // Event metadata
        Map<String, Object> eventInfo = new HashMap<>();
        eventInfo.put("type", "CHILD_BATCH");
        eventInfo.put("timestamp", java.time.Instant.now().toString());
        eventInfo.put("deliveryId", batch.getBatchId());
        payload.put("event", eventInfo);
        
        // Repository info
        Map<String, Object> repoInfo = new HashMap<>();
        repoInfo.put("id", batch.getRepositoryId());
        payload.put("repository", repoInfo);
        
        // Parent folder info
        Map<String, Object> parentInfo = new HashMap<>();
        parentInfo.put("id", batch.getParentFolderId());
        parentInfo.put("path", batch.getParentFolderPath());
        payload.put("parentFolder", parentInfo);
        
        // Changes list
        List<Map<String, Object>> changes = new ArrayList<>();
        for (ChildEvent event : batch.getEvents()) {
            Map<String, Object> change = new HashMap<>();
            change.put("type", event.getEventType());
            change.put("objectId", event.getObjectId());
            change.put("name", event.getObjectName());
            change.put("objectType", event.getObjectType());
            change.put("timestamp", event.getTimestamp());
            if (event.getUserId() != null) {
                change.put("userId", event.getUserId());
            }
            changes.add(change);
        }
        payload.put("changes", changes);
        
        // Batch info
        Map<String, Object> batchInfo = new HashMap<>();
        batchInfo.put("windowStart", java.time.Instant.ofEpochMilli(batch.getWindowStart()).toString());
        batchInfo.put("windowEnd", java.time.Instant.ofEpochMilli(batch.getWindowEnd()).toString());
        batchInfo.put("eventCount", batch.getEventCount());
        payload.put("batchInfo", batchInfo);
        
        return payload;
    }
    
    /**
     * Dispatch a CHILD_BATCH webhook asynchronously.
     */
    private void dispatchChildBatchAsync(String repositoryId, ChildEventBatch batch,
                                          WebhookConfig config, Map<String, Object> batchPayload) {
        
        executorService.submit(() -> {
            try {
                // Serialize payload to JSON
                String payloadJson = deliveryService.serializePayload(batchPayload);
                
                // Generate auth headers
                Map<String, String> headers = deliveryService.generateAuthHeaders(config);
                
                // Compute HMAC signature if secret is configured
                String signature = null;
                if (config.getSecret() != null && !config.getSecret().isEmpty()) {
                    signature = deliveryService.computeHmacSignature(payloadJson, config.getSecret());
                }
                
                // Add standard webhook headers
                headers.put("Content-Type", "application/json");
                headers.put("X-NemakiWare-Event", "CHILD_BATCH");
                headers.put("X-NemakiWare-Delivery", batch.getBatchId());
                headers.put("X-NemakiWare-Timestamp", String.valueOf(System.currentTimeMillis()));
                if (signature != null) {
                    headers.put("X-NemakiWare-Signature", "sha256=" + signature);
                }
                
                // Dispatch via dispatcher if available
                if (dispatcher != null) {
                    dispatcher.dispatch(config.getUrl(), payloadJson, headers, config);
                    log.info("CHILD_BATCH webhook dispatched: url=" + config.getUrl() + 
                             ", batchId=" + batch.getBatchId() + ", eventCount=" + batch.getEventCount());
                } else {
                    log.info("CHILD_BATCH webhook (no dispatcher): url=" + config.getUrl() + 
                             ", batchId=" + batch.getBatchId());
                }
                
            } catch (Exception e) {
                log.error("Failed to dispatch CHILD_BATCH webhook for config: " + config.getId(), e);
            }
        });
    }
    
    /**
     * Shutdown the executor service and batch processor.
     * Called automatically by Spring container on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        // Shutdown child event batch processor first to flush pending batches
        if (childEventBatchProcessor != null) {
            log.info("WebhookServiceImpl child event batch processor shutting down...");
            childEventBatchProcessor.shutdown();
        }
        
        if (executorService != null) {
            log.info("WebhookServiceImpl executor service shutting down...");
            executorService.shutdown();
            try {
                // Wait for existing tasks to complete
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                    log.warn("WebhookServiceImpl executor service forced shutdown after timeout");
                } else {
                    log.info("WebhookServiceImpl executor service shutdown complete");
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("WebhookServiceImpl executor service shutdown interrupted");
            }
        }
    }
    
    /**
     * Interface for webhook dispatcher (to be implemented with HTTP client)
     */
    public interface WebhookDispatcher {
        void dispatch(String url, String payload, Map<String, String> headers, WebhookConfig config);
        
        /**
         * Synchronous dispatch for testing webhooks.
         * Returns the delivery result including status code and response body.
         * 
         * @param url The webhook URL
         * @param payload The JSON payload
         * @param headers HTTP headers
         * @param config Webhook configuration
         * @return WebhookDeliveryLog with the result
         */
        default WebhookDeliveryLog dispatchSync(String url, String payload, Map<String, String> headers, WebhookConfig config) {
            // Default implementation for backward compatibility
            WebhookDeliveryLog result = new WebhookDeliveryLog();
            result.setDeliveryId(java.util.UUID.randomUUID().toString());
            result.setWebhookUrl(url);
            result.setEventType("TEST");
            result.setAttemptNumber(1);
            result.setTimestamp(new java.util.GregorianCalendar());
            
            try {
                dispatch(url, payload, headers, config);
                result.setSuccess(true);
                result.setStatusCode(200);
                result.setResponseBody("Dispatch completed (async mode - actual result unknown)");
            } catch (Exception e) {
                result.setSuccess(false);
                result.setStatusCode(0);
                result.setResponseBody("Error: " + e.getMessage());
            }
            return result;
        }
    }
}
