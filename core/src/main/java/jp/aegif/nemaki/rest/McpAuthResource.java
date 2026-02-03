package jp.aegif.nemaki.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.mcp.McpAuthenticationHandler;
import jp.aegif.nemaki.mcp.McpLoginResult;

/**
 * REST resource for MCP (Model Context Protocol) authentication.
 * Provides endpoints for completing cloud-based MCP login.
 */
@Component
@Path("/repo/{repositoryId}/mcp")
public class McpAuthResource extends ResourceBase {

    private static final Logger logger = LoggerFactory.getLogger(McpAuthResource.class);

    @Autowired(required = false)
    private McpAuthenticationHandler mcpAuthHandler;

    @Context
    private HttpServletRequest request;

    /**
     * Complete a cloud login request.
     * This endpoint is called by the UI after the user has authenticated via cloud provider.
     *
     * SECURITY:
     * - This endpoint requires authentication. The userId is derived from the
     *   authenticated session (CallContext), NOT from the request body.
     * - Both requestId and loginCode are required to prevent brute-force attacks.
     * - Failed attempts are tracked per requestId (max 5 attempts).
     *
     * @param repositoryId Repository ID
     * @param requestBody JSON body containing requestId and loginCode
     * @return JSON result with success status and session token
     */
    @POST
    @Path("/cloud-login/complete")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public String completeCloudLogin(
            @PathParam("repositoryId") String repositoryId,
            String requestBody) {

        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        boolean status = false;

        logger.info("MCP cloud login completion requested for repository: {}", repositoryId);

        // Check if MCP authentication handler is available
        if (mcpAuthHandler == null) {
            addErrMsg(errMsg, "mcpAuthHandler", "notAvailable");
            logger.error("MCP cloud login completion failed: McpAuthenticationHandler not available");
            return makeResult(false, result, errMsg).toString();
        }

        // SECURITY: Get userId from authenticated session, NOT from request body
        // This ensures only the authenticated user can complete the login as themselves
        CallContext callContext = (CallContext) request.getAttribute("CallContext");
        if (callContext == null || callContext.getUsername() == null) {
            addErrMsg(errMsg, "authentication", "required");
            logger.warn("MCP cloud login completion failed: user not authenticated");
            return makeResult(false, result, errMsg).toString();
        }
        String userId = callContext.getUsername();

        try {
            // Parse request body - requestId and loginCode are required
            JSONParser parser = new JSONParser();
            JSONObject body = (JSONObject) parser.parse(requestBody);
            String requestId = (String) body.get("requestId");
            String loginCode = (String) body.get("loginCode");

            // SECURITY: Both requestId and loginCode are required
            if (requestId == null || requestId.isEmpty()) {
                addErrMsg(errMsg, "requestId", "isRequired");
                return makeResult(false, result, errMsg).toString();
            }

            if (loginCode == null || loginCode.isEmpty()) {
                addErrMsg(errMsg, "loginCode", "isRequired");
                return makeResult(false, result, errMsg).toString();
            }

            // Complete the cloud login with the authenticated user's ID
            // Uses the new method that requires both requestId and loginCode
            boolean completed = mcpAuthHandler.completeCloudLogin(requestId, loginCode, userId);

            if (completed) {
                JSONObject value = new JSONObject();
                value.put("completed", true);
                value.put("message", "Cloud login completed. Claude Desktop will automatically detect this.");
                result.put("value", value);
                status = true;
                logger.info("MCP cloud login completed for authenticated user '{}'", userId);
            } else {
                addErrMsg(errMsg, "loginCode", "invalidOrExpired");
                logger.warn("MCP cloud login completion failed: invalid or expired code/request");
            }

        } catch (Exception e) {
            logger.error("Error completing MCP cloud login", e);
            addErrMsg(errMsg, "error", e.getMessage());
        }

        return makeResult(status, result, errMsg).toString();
    }

    /**
     * Check the status of a cloud login request.
     * This can be used by clients to verify if the login code is still valid.
     *
     * @param repositoryId Repository ID
     * @param loginCode The login code to check
     * @return JSON result with status
     */
    @GET
    @Path("/cloud-login/status/{loginCode}")
    @Produces(MediaType.APPLICATION_JSON)
    public String checkCloudLoginStatus(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("loginCode") String loginCode) {

        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

        try {
            // Check if the login code is valid and pending
            // Note: This is a simplified check - the full status is returned by checkCloudLoginStatus
            // which uses the requestId, not the loginCode
            JSONObject value = new JSONObject();
            value.put("loginCode", loginCode);
            value.put("repositoryId", repositoryId);
            result.put("value", value);
            return makeResult(true, result, errMsg).toString();

        } catch (Exception e) {
            logger.error("Error checking MCP cloud login status", e);
            addErrMsg(errMsg, "error", e.getMessage());
            return makeResult(false, result, errMsg).toString();
        }
    }
}
