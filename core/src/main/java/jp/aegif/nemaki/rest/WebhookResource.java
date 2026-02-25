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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.WebhookService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.model.Acl;
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
     */
    @SuppressWarnings("unchecked")
    @POST
    @Path("/deliveries/{deliveryId}/retry")
    @Produces(MediaType.APPLICATION_JSON)
    public String retryDelivery(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("deliveryId") String deliveryId,
            @Context HttpServletRequest request) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
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
                WebhookDeliveryLog retryLog = service.retryDelivery(repositoryId, deliveryId);
                if (retryLog != null) {
                    result.put("deliveryId", deliveryId);
                    result.put("retryStatus", "queued");
                    result.put("attemptNumber", retryLog.getAttemptNumber());
                    if (retryLog.getAttemptId() != null) {
                        result.put("attemptId", retryLog.getAttemptId());
                    }
                } else {
                    status = false;
                    addErrMsg(errMsg, "retry", "Delivery log not found or retry not available");
                }
            }
        } catch (Exception e) {
            log.error("Error retrying delivery: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "retry", "Failed to retry delivery: " + e.getMessage());
        }
        
        result = makeResult(status, result, errMsg);
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
     * Returns all configs including disabled and invalid ones (for management UI).
     * Requires CAN_GET_PROPERTIES_OBJECT permission (cmis:read equivalent).
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
                    // CMIS permission check: CAN_GET_PROPERTIES_OBJECT (cmis:read equivalent)
                    if (!checkCmisPermission(request, repositoryId, cs, content,
                            PermissionMapping.CAN_GET_PROPERTIES_OBJECT, errMsg)) {
                        result = makeResult(false, result, errMsg);
                        return result.toJSONString();
                    }

                    List<WebhookConfig> configs = ws.getAllWebhookConfigs(repositoryId, content);
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

    /**
     * Add a new webhook configuration to a specific object.
     * Requires CAN_UPDATE_PROPERTIES_OBJECT permission (cmis:write equivalent).
     *
     * POST /rest/repo/{repositoryId}/webhook/config/{objectId}
     * Body: {"url": "https://...", "events": ["created","updated"], "enabled": true, ...}
     */
    @SuppressWarnings("unchecked")
    @POST
    @Path("/config/{objectId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String addWebhookConfig(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("objectId") String objectId,
            String requestBody,
            @Context HttpServletRequest request) {

        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

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
                    // CMIS permission check: CAN_UPDATE_PROPERTIES_OBJECT (cmis:write equivalent)
                    if (!checkCmisPermission(request, repositoryId, cs, content,
                            PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT, errMsg)) {
                        result = makeResult(false, result, errMsg);
                        return result.toJSONString();
                    }

                    // Parse request body as a single config
                    org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
                    JSONObject body = (JSONObject) parser.parse(requestBody);

                    String url = (String) body.get("url");
                    if (url == null || url.isEmpty()) {
                        status = false;
                        addErrMsg(errMsg, "url", "URL is required");
                    } else {
                        // Get existing configs
                        List<WebhookConfig> configs = ws.getAllWebhookConfigs(repositoryId, content);
                        configs = new ArrayList<>(configs);

                        // Build new config
                        WebhookConfig newConfig = buildWebhookConfigFromJson(body);
                        // Generate ID if not provided
                        if (newConfig.getId() == null || newConfig.getId().isEmpty()) {
                            newConfig.setId("webhook-" + java.util.UUID.randomUUID().toString().substring(0, 8));
                        }

                        configs.add(newConfig);

                        // Save
                        ws.saveWebhookConfigs(repositoryId, objectId, configs);

                        result.put("webhookId", newConfig.getId());
                        result.put("objectId", objectId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error adding webhook config: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "config", "Failed to add webhook config: " + e.getMessage());
        }

        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }

    /**
     * Update an existing webhook configuration.
     * Requires CAN_UPDATE_PROPERTIES_OBJECT permission (cmis:write equivalent).
     *
     * PUT /rest/repo/{repositoryId}/webhook/config/{objectId}/{webhookId}
     * Body: {"url": "https://...", "events": ["created","updated"], "enabled": true, ...}
     */
    @SuppressWarnings("unchecked")
    @PUT
    @Path("/config/{objectId}/{webhookId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String updateWebhookConfig(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("objectId") String objectId,
            @PathParam("webhookId") String webhookId,
            String requestBody,
            @Context HttpServletRequest request) {

        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

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
                    // CMIS permission check: CAN_UPDATE_PROPERTIES_OBJECT (cmis:write equivalent)
                    if (!checkCmisPermission(request, repositoryId, cs, content,
                            PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT, errMsg)) {
                        result = makeResult(false, result, errMsg);
                        return result.toJSONString();
                    }

                    List<WebhookConfig> configs = ws.getAllWebhookConfigs(repositoryId, content);
                    configs = new ArrayList<>(configs);

                    // Find and merge-update the config with matching ID
                    boolean found = false;
                    for (int i = 0; i < configs.size(); i++) {
                        if (webhookId.equals(configs.get(i).getId())) {
                            org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
                            JSONObject body = (JSONObject) parser.parse(requestBody);

                            WebhookConfig existing = configs.get(i);
                            mergeWebhookConfigFromJson(existing, body);
                            configs.set(i, existing);
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        status = false;
                        addErrMsg(errMsg, "webhookId", "Webhook config not found: " + webhookId);
                    } else {
                        ws.saveWebhookConfigs(repositoryId, objectId, configs);
                        result.put("webhookId", webhookId);
                        result.put("objectId", objectId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error updating webhook config: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "config", "Failed to update webhook config: " + e.getMessage());
        }

        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }

    /**
     * Delete a webhook configuration.
     * Requires CAN_UPDATE_PROPERTIES_OBJECT permission (cmis:write equivalent).
     *
     * DELETE /rest/repo/{repositoryId}/webhook/config/{objectId}/{webhookId}
     */
    @SuppressWarnings("unchecked")
    @DELETE
    @Path("/config/{objectId}/{webhookId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String deleteWebhookConfig(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("objectId") String objectId,
            @PathParam("webhookId") String webhookId,
            @Context HttpServletRequest request) {

        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

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
                    // CMIS permission check: CAN_UPDATE_PROPERTIES_OBJECT (cmis:write equivalent)
                    if (!checkCmisPermission(request, repositoryId, cs, content,
                            PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT, errMsg)) {
                        result = makeResult(false, result, errMsg);
                        return result.toJSONString();
                    }

                    List<WebhookConfig> configs = ws.getAllWebhookConfigs(repositoryId, content);
                    configs = new ArrayList<>(configs);

                    boolean removed = configs.removeIf(c -> webhookId.equals(c.getId()));

                    if (!removed) {
                        status = false;
                        addErrMsg(errMsg, "webhookId", "Webhook config not found: " + webhookId);
                    } else {
                        ws.saveWebhookConfigs(repositoryId, objectId, configs);
                        result.put("webhookId", webhookId);
                        result.put("objectId", objectId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error deleting webhook config: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "config", "Failed to delete webhook config: " + e.getMessage());
        }

        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }

    /**
     * Check CMIS permission on a content object for the current user.
     * Returns true if permission is granted, false otherwise (with error message added).
     */
    @SuppressWarnings("unchecked")
    private boolean checkCmisPermission(HttpServletRequest request, String repositoryId,
                                        ContentService cs, Content content,
                                        String permissionKey, JSONArray errMsg) {
        CallContext callContext = (CallContext) request.getAttribute("CallContext");
        if (callContext == null) {
            addErrMsg(errMsg, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN, "unknown");
            return false;
        }
        try {
            PermissionService permService = getPermissionService();
            if (permService == null) {
                // Fallback to admin check if PermissionService is unavailable
                Boolean _isAdmin = (Boolean) callContext.get(
                        jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN);
                boolean isAdmin = _isAdmin != null && _isAdmin;
                if (!isAdmin) {
                    addErrMsg(errMsg, "permission", "Permission denied");
                }
                return isAdmin;
            }
            Acl acl = cs.calculateAcl(repositoryId, content);
            Boolean result = permService.checkPermission(
                    callContext, repositoryId, permissionKey, acl, content.getType(), content);
            if (result == null || !result) {
                addErrMsg(errMsg, "permission", "Permission denied");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.debug("Permission check failed for " + content.getName() + ": " + e.getMessage());
            addErrMsg(errMsg, "permission", "Permission denied");
            return false;
        }
    }

    private PermissionService getPermissionService() {
        try {
            return SpringContext.getApplicationContext()
                    .getBean("PermissionService", PermissionService.class);
        } catch (Exception e) {
            log.debug("Failed to get PermissionService: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build a WebhookConfig from a JSON request body.
     */
    @SuppressWarnings("unchecked")
    private WebhookConfig buildWebhookConfigFromJson(JSONObject body) {
        WebhookConfig config = new WebhookConfig();

        config.setId((String) body.get("id"));
        config.setUrl((String) body.get("url"));
        config.setEnabled(body.get("enabled") != null ? (Boolean) body.get("enabled") : true);
        config.setAuthType((String) body.get("authType"));
        config.setAuthCredential((String) body.get("authCredential"));
        config.setSecret((String) body.get("secret"));
        config.setIncludeChildren(body.get("includeChildren") != null ? (Boolean) body.get("includeChildren") : false);

        if (body.get("maxDepth") != null) {
            config.setMaxDepth(((Number) body.get("maxDepth")).intValue());
        }
        if (body.get("retryCount") != null) {
            config.setRetryCount(((Number) body.get("retryCount")).intValue());
        }

        // Parse events array
        List<String> events = new ArrayList<>();
        Object eventsObj = body.get("events");
        if (eventsObj instanceof org.json.simple.JSONArray) {
            for (Object e : (org.json.simple.JSONArray) eventsObj) {
                if (e != null) {
                    events.add(e.toString());
                }
            }
        }
        config.setEvents(events);

        // Parse headers
        Object headersObj = body.get("headers");
        if (headersObj instanceof JSONObject) {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            for (Object entry : ((JSONObject) headersObj).entrySet()) {
                java.util.Map.Entry<?, ?> e = (java.util.Map.Entry<?, ?>) entry;
                if (e.getKey() != null && e.getValue() != null) {
                    headers.put(e.getKey().toString(), e.getValue().toString());
                }
            }
            config.setHeaders(headers);
        }

        return config;
    }

    /**
     * Merge-update an existing WebhookConfig from JSON request body.
     * Only fields present in the JSON body are updated; absent fields retain their existing values.
     * This prevents loss of sensitive fields (authCredential, secret, headers) that are
     * masked in GET responses and therefore not included in UI edit requests.
     */
    @SuppressWarnings("unchecked")
    private void mergeWebhookConfigFromJson(WebhookConfig existing, JSONObject body) {
        if (body.containsKey("url")) {
            existing.setUrl((String) body.get("url"));
        }
        if (body.containsKey("enabled")) {
            existing.setEnabled(body.get("enabled") != null ? (Boolean) body.get("enabled") : existing.isEnabled());
        }
        if (body.containsKey("events")) {
            List<String> events = new ArrayList<>();
            Object eventsObj = body.get("events");
            if (eventsObj instanceof org.json.simple.JSONArray) {
                for (Object e : (org.json.simple.JSONArray) eventsObj) {
                    if (e != null) {
                        events.add(e.toString());
                    }
                }
            }
            existing.setEvents(events);
        }
        if (body.containsKey("authType")) {
            existing.setAuthType((String) body.get("authType"));
        }
        // Update authCredential/secret when key is present in body.
        // Empty string or null explicitly clears the value.
        if (body.containsKey("authCredential")) {
            String cred = (String) body.get("authCredential");
            existing.setAuthCredential((cred != null && !cred.isEmpty()) ? cred : null);
        }
        if (body.containsKey("secret")) {
            String secret = (String) body.get("secret");
            existing.setSecret((secret != null && !secret.isEmpty()) ? secret : null);
        }
        if (body.containsKey("headers")) {
            Object headersObj = body.get("headers");
            if (headersObj instanceof JSONObject) {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                for (Object entry : ((JSONObject) headersObj).entrySet()) {
                    java.util.Map.Entry<?, ?> e = (java.util.Map.Entry<?, ?>) entry;
                    if (e.getKey() != null && e.getValue() != null) {
                        headers.put(e.getKey().toString(), e.getValue().toString());
                    }
                }
                existing.setHeaders(headers);
            }
        }
        if (body.containsKey("includeChildren")) {
            existing.setIncludeChildren(body.get("includeChildren") != null ? (Boolean) body.get("includeChildren") : existing.isIncludeChildren());
        }
        if (body.containsKey("maxDepth")) {
            if (body.get("maxDepth") != null) {
                existing.setMaxDepth(((Number) body.get("maxDepth")).intValue());
            } else {
                existing.setMaxDepth(null);
            }
        }
        if (body.containsKey("retryCount")) {
            if (body.get("retryCount") != null) {
                existing.setRetryCount(((Number) body.get("retryCount")).intValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildDeliveryLogJson(WebhookDeliveryLog deliveryLog) {
        JSONObject json = new JSONObject();
        json.put("deliveryId", deliveryLog.getDeliveryId());
        if (deliveryLog.getAttemptId() != null) {
            json.put("attemptId", deliveryLog.getAttemptId());
        }
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
        // Mask sensitive credentials in response
        json.put("hasAuthCredential", config.getAuthCredential() != null && !config.getAuthCredential().isEmpty());
        json.put("hasSecret", config.getSecret() != null && !config.getSecret().isEmpty());
        json.put("includeChildren", config.isIncludeChildren());
        json.put("maxDepth", config.getMaxDepth());
        json.put("retryCount", config.getRetryCount());
        json.put("sourceObjectId", config.getSourceObjectId());

        // Include custom headers (keys only for security)
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            JSONArray headerKeys = new JSONArray();
            for (String key : config.getHeaders().keySet()) {
                headerKeys.add(key);
            }
            json.put("headerKeys", headerKeys);
        }

        return json;
    }
}
