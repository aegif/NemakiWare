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
 *     aegif - Webhook REST API implementation
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.WebhookService;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.spring.SpringContext;
import jp.aegif.nemaki.webhook.WebhookConfig;
import jp.aegif.nemaki.webhook.WebhookDeliveryLog;

/**
 * REST API for Webhook management.
 * 
 * Endpoints:
 * - GET /rest/repo/{repositoryId}/webhooks - List all webhooks in repository
 * - GET /rest/repo/{repositoryId}/webhook/deliveries - Get delivery logs
 * - POST /rest/repo/{repositoryId}/webhook/deliveries/{deliveryId}/retry - Retry delivery
 * - POST /rest/repo/{repositoryId}/webhook/test - Test webhook endpoint
 */
@Path("/repo/{repositoryId}/webhook")
public class WebhookResource extends ResourceBase {

    private static final Log log = LogFactory.getLog(WebhookResource.class);

    private WebhookService webhookService;
    private ContentService contentService;

    public void setWebhookService(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    private WebhookService getWebhookService() {
        if (webhookService != null) {
            return webhookService;
        }
        try {
            WebhookService service = SpringContext.getApplicationContext()
                    .getBean("webhookService", WebhookService.class);
            if (service != null) {
                log.debug("WebhookService retrieved from SpringContext successfully");
                return service;
            }
        } catch (Exception e) {
            log.error("Failed to get WebhookService from SpringContext: " + e.getMessage(), e);
        }
        log.error("WebhookService is null and SpringContext fallback failed");
        return null;
    }

    private ContentService getContentService() {
        if (contentService != null) {
            return contentService;
        }
        try {
            ContentService service = SpringContext.getApplicationContext()
                    .getBean("ContentService", ContentService.class);
            if (service != null) {
                return service;
            }
        } catch (Exception e) {
            log.debug("Could not find ContentService: " + e.getMessage());
        }
        try {
            ContentService service = SpringContext.getApplicationContext()
                    .getBean("contentService", ContentService.class);
            if (service != null) {
                return service;
            }
        } catch (Exception e) {
            log.debug("Could not find contentService: " + e.getMessage());
        }
        return null;
    }

    /**
     * Get delivery logs for a specific object or all objects.
     * Requires admin authorization to prevent information disclosure.
     * 
     * GET /rest/repo/{repositoryId}/webhook/deliveries?objectId={objectId}&limit={limit}
     */
    @SuppressWarnings("unchecked")
    @GET
    @Path("/deliveries")
    @Produces(MediaType.APPLICATION_JSON)
    public String getDeliveryLogs(
            @PathParam("repositoryId") String repositoryId,
            @QueryParam("objectId") String objectId,
            @QueryParam("limit") Integer limit,
            @Context HttpServletRequest request) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        // Admin authorization required to view delivery logs
        if (!checkAdmin(errMsg, request)) {
            result = makeResult(false, result, errMsg);
            return result.toJSONString();
        }
        
        try {
            WebhookService service = getWebhookService();
            if (service == null) {
                status = false;
                addErrMsg(errMsg, "webhookService", "WebhookService not available");
            } else {
                int actualLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
                List<WebhookDeliveryLog> logs = service.getDeliveryLogs(repositoryId, objectId, actualLimit);
                
                JSONArray deliveries = new JSONArray();
                for (WebhookDeliveryLog deliveryLog : logs) {
                    deliveries.add(buildDeliveryLogJson(deliveryLog));
                }
                result.put("deliveries", deliveries);
            }
        } catch (Exception e) {
            log.error("Error getting delivery logs: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "deliveries", "Failed to get delivery logs: " + e.getMessage());
        }
        
        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }

    /**
     * Retry a failed webhook delivery.
     * 
     * POST /rest/repo/{repositoryId}/webhook/deliveries/{deliveryId}/retry
     * 
     * Note: This endpoint requires DAO layer implementation (Phase 3 continued).
     * Currently returns 501 Not Implemented.
     */
    @SuppressWarnings("unchecked")
    @POST
    @Path("/deliveries/{deliveryId}/retry")
    @Produces(MediaType.APPLICATION_JSON)
    public String retryDelivery(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("deliveryId") String deliveryId,
            @Context HttpServletRequest request) {
        
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        if (!checkAdmin(errMsg, request)) {
            result = makeResult(false, result, errMsg);
            return result.toJSONString();
        }
        
        // Return 501 Not Implemented until DAO layer is available
        // This prevents clients from thinking retry succeeded when it didn't
        addErrMsg(errMsg, "retry", "Not implemented: Retry functionality requires DAO layer (Phase 3 continued)");
        result.put("deliveryId", deliveryId);
        result.put("retryStatus", "not_implemented");
        result = makeResult(false, result, errMsg);
        return result.toJSONString();
    }

    /**
     * Test a webhook endpoint by sending a test payload.
     * 
     * POST /rest/repo/{repositoryId}/webhook/test
     * Body: {"url": "https://...", "secret": "..."}
     */
    @SuppressWarnings("unchecked")
    @POST
    @Path("/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String testWebhook(
            @PathParam("repositoryId") String repositoryId,
            String requestBody,
            @Context HttpServletRequest request) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        if (!checkAdmin(errMsg, request)) {
            result = makeResult(false, result, errMsg);
            return result.toJSONString();
        }
        
        try {
            org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
            JSONObject body = (JSONObject) parser.parse(requestBody);
            
            String url = (String) body.get("url");
            String secret = (String) body.get("secret");
            
            if (url == null || url.isEmpty()) {
                status = false;
                addErrMsg(errMsg, "url", "URL is required");
            } else {
                WebhookService service = getWebhookService();
                if (service == null) {
                    status = false;
                    addErrMsg(errMsg, "webhookService", "WebhookService not available");
                } else {
                    long startTime = System.currentTimeMillis();
                    WebhookDeliveryLog testResult = service.testWebhook(repositoryId, url, secret);
                    long responseTime = System.currentTimeMillis() - startTime;
                    
                    result.put("success", testResult.isSuccess());
                    result.put("statusCode", testResult.getStatusCode());
                    result.put("responseTime", responseTime);
                    if (testResult.getResponseBody() != null) {
                        result.put("responseBody", testResult.getResponseBody());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error testing webhook: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "test", "Failed to test webhook: " + e.getMessage());
        }
        
        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }

    /**
     * Get webhook configuration for a specific object.
     * Requires admin authorization to prevent information disclosure.
     * 
     * GET /rest/repo/{repositoryId}/webhook/config/{objectId}
     */
    @SuppressWarnings("unchecked")
    @GET
    @Path("/config/{objectId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getWebhookConfig(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("objectId") String objectId,
            @Context HttpServletRequest request) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        // Admin authorization required to view webhook configurations
        if (!checkAdmin(errMsg, request)) {
            result = makeResult(false, result, errMsg);
            return result.toJSONString();
        }
        
        try {
            ContentService cs = getContentService();
            WebhookService ws = getWebhookService();
            
            if (cs == null || ws == null) {
                status = false;
                addErrMsg(errMsg, "service", "Required services not available");
            } else {
                Content content = cs.getContent(repositoryId, objectId);
                if (content == null) {
                    status = false;
                    addErrMsg(errMsg, "objectId", ErrorCode.ERR_NOTFOUND);
                } else {
                    List<WebhookConfig> configs = ws.getWebhookConfigs(repositoryId, content);
                    JSONArray configsArray = new JSONArray();
                    for (WebhookConfig config : configs) {
                        configsArray.add(buildWebhookConfigJson(config));
                    }
                    result.put("objectId", objectId);
                    result.put("webhookConfigs", configsArray);
                }
            }
        } catch (Exception e) {
            log.error("Error getting webhook config: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "config", "Failed to get webhook config: " + e.getMessage());
        }
        
        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildDeliveryLogJson(WebhookDeliveryLog deliveryLog) {
        JSONObject json = new JSONObject();
        json.put("deliveryId", deliveryLog.getDeliveryId());
        json.put("objectId", deliveryLog.getObjectId());
        json.put("eventType", deliveryLog.getEventType());
        json.put("webhookUrl", deliveryLog.getWebhookUrl());
        json.put("statusCode", deliveryLog.getStatusCode());
        json.put("success", deliveryLog.isSuccess());
        json.put("attemptCount", deliveryLog.getAttemptNumber());
        if (deliveryLog.getTimestamp() != null) {
            json.put("deliveredAt", deliveryLog.getTimestamp().toInstant().toString());
        }
        if (deliveryLog.getResponseBody() != null) {
            json.put("responseBody", deliveryLog.getResponseBody());
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildWebhookConfigJson(WebhookConfig config) {
        JSONObject json = new JSONObject();
        json.put("id", config.getId());
        json.put("enabled", config.isEnabled());
        json.put("url", config.getUrl());
        
        JSONArray events = new JSONArray();
        if (config.getEvents() != null) {
            for (String event : config.getEvents()) {
                events.add(event);
            }
        }
        json.put("events", events);
        
        json.put("authType", config.getAuthType());
        json.put("includeChildren", config.isIncludeChildren());
        json.put("maxDepth", config.getMaxDepth());
        json.put("retryCount", config.getRetryCount());
        json.put("sourceObjectId", config.getSourceObjectId());
        
        return json;
    }
}
