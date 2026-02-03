/*******************************************************************************
 * Copyright (c) 2024 aegif.
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
 ******************************************************************************/
package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.ApiKeyService;
import jp.aegif.nemaki.model.ApiKey;
import jp.aegif.nemaki.model.UserItem;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API Resource for managing API keys.
 *
 * API keys provide persistent authentication for programmatic access,
 * especially useful for MCP clients and cloud-only users.
 */
@Path("/repositories/{repositoryId}/apikeys")
@Tag(name = "API Keys", description = "Manage API keys for programmatic authentication")
public class ApiKeyResource {

    private static final Log log = LogFactory.getLog(ApiKeyResource.class);

    @Autowired
    private ContentService contentService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Context
    private HttpServletRequest httpRequest;

    /**
     * List all API keys for a user.
     */
    @GET
    @Path("/user/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "List API keys for a user",
        description = "Returns all active API keys for the specified user. Only admins or the user themselves can access this."
    )
    @ApiResponse(responseCode = "200", description = "List of API keys")
    @ApiResponse(responseCode = "403", description = "Access denied")
    public Response listApiKeys(
            @Parameter(description = "Repository ID") @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID") @PathParam("userId") String userId) {

        log.info("listApiKeys called: repositoryId=" + repositoryId + ", userId=" + userId);
        log.info("apiKeyService is " + (apiKeyService != null ? "injected" : "NULL"));

        try {
            // Check authorization: must be admin or the user themselves
            String currentUser = getAuthenticatedUsername();
            if (!isAdmin(repositoryId, currentUser) && !userId.equals(currentUser)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Access denied"))
                        .build();
            }

            List<ApiKey> keys = apiKeyService.listApiKeys(repositoryId, userId);
            List<Map<String, Object>> result = new ArrayList<>();

            for (ApiKey key : keys) {
                result.add(convertToResponse(key));
            }

            return Response.ok(Map.of("apiKeys", result)).build();
        } catch (Exception e) {
            log.error("Error listing API keys for user " + userId, e);
            return Response.serverError()
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Create a new API key.
     */
    @POST
    @Path("/user/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Create a new API key",
        description = "Creates a new API key for the specified user. The plain text key is only returned once."
    )
    @ApiResponse(
        responseCode = "201",
        description = "API key created",
        content = @Content(schema = @Schema(implementation = Map.class))
    )
    @ApiResponse(responseCode = "403", description = "Access denied")
    public Response createApiKey(
            @Parameter(description = "Repository ID") @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID") @PathParam("userId") String userId,
            Map<String, String> request) {

        try {
            // Check authorization: must be admin or the user themselves
            String currentUser = getAuthenticatedUsername();
            if (!isAdmin(repositoryId, currentUser) && !userId.equals(currentUser)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Access denied"))
                        .build();
            }

            String name = request.get("name");
            String description = request.get("description");
            String expiresAtStr = request.get("expiresAt");

            if (name == null || name.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Name is required"))
                        .build();
            }

            // Parse expiration date if provided (epoch milliseconds)
            GregorianCalendar expiresAt = null;
            if (expiresAtStr != null && !expiresAtStr.trim().isEmpty()) {
                try {
                    long epochMs = Long.parseLong(expiresAtStr.trim());
                    expiresAt = new GregorianCalendar();
                    expiresAt.setTimeInMillis(epochMs);
                } catch (NumberFormatException e) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "Invalid expiresAt format. Expected epoch milliseconds."))
                            .build();
                }
            }

            ApiKeyService.ApiKeyCreationResult result = apiKeyService.createApiKey(
                    repositoryId, userId, name.trim(), description, expiresAt);

            Map<String, Object> response = convertToResponse(result.getApiKey());
            // Include the plain text key only on creation
            response.put("apiKey", result.getPlainTextKey());
            response.put("message", "Store this key securely. It will not be shown again.");

            return Response.status(Response.Status.CREATED).entity(response).build();
        } catch (Exception e) {
            log.error("Error creating API key for user " + userId, e);
            return Response.serverError()
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Revoke an API key.
     */
    @DELETE
    @Path("/{keyId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Revoke an API key",
        description = "Permanently revokes (deletes) an API key. This action cannot be undone."
    )
    @ApiResponse(responseCode = "200", description = "API key revoked")
    @ApiResponse(responseCode = "403", description = "Access denied")
    @ApiResponse(responseCode = "404", description = "API key not found")
    public Response revokeApiKey(
            @Parameter(description = "Repository ID") @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "API Key ID") @PathParam("keyId") String keyId) {

        try {
            // Get the key to check ownership
            ApiKey key = apiKeyService.getApiKey(repositoryId, keyId);
            if (key == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "API key not found"))
                        .build();
            }

            // Check authorization: must be admin or the key owner
            String currentUser = getAuthenticatedUsername();
            if (!isAdmin(repositoryId, currentUser) && !key.getUserId().equals(currentUser)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Access denied"))
                        .build();
            }

            boolean revoked = apiKeyService.revokeApiKey(repositoryId, keyId);

            if (revoked) {
                return Response.ok(Map.of("status", "success", "message", "API key revoked")).build();
            } else {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "API key not found"))
                        .build();
            }
        } catch (Exception e) {
            log.error("Error revoking API key " + keyId, e);
            return Response.serverError()
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Convert an ApiKey to a response map (without the key value).
     */
    private Map<String, Object> convertToResponse(ApiKey key) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", key.getId());
        map.put("userId", key.getUserId());
        map.put("name", key.getName());
        map.put("description", key.getDescription());
        map.put("keyPrefix", key.getKeyPrefix());
        map.put("active", key.isActive());
        map.put("expired", key.isExpired());
        if (key.getCreated() != null) {
            map.put("created", key.getCreated().getTimeInMillis());
        }
        if (key.getLastUsed() != null) {
            map.put("lastUsed", key.getLastUsed().getTimeInMillis());
        }
        if (key.getExpiresAt() != null) {
            map.put("expiresAt", key.getExpiresAt().getTimeInMillis());
        }
        return map;
    }

    /**
     * Get the authenticated username from the request.
     */
    private String getAuthenticatedUsername() {
        // First try from CallContext (set by ApiAuthenticationFilter)
        Object callContextObj = httpRequest.getAttribute("CallContext");
        if (callContextObj instanceof org.apache.chemistry.opencmis.commons.server.CallContext) {
            org.apache.chemistry.opencmis.commons.server.CallContext callContext =
                (org.apache.chemistry.opencmis.commons.server.CallContext) callContextObj;
            if (callContext.getUsername() != null) {
                return callContext.getUsername();
            }
        }

        // Fallback to UserPrincipal
        if (httpRequest.getUserPrincipal() != null) {
            return httpRequest.getUserPrincipal().getName();
        }

        // Try to get from request attribute (set by authentication filter)
        Object username = httpRequest.getAttribute("authenticatedUser");
        if (username != null) {
            return username.toString();
        }
        return null;
    }

    /**
     * Check if a user is an admin.
     */
    private boolean isAdmin(String repositoryId, String userId) {
        if (userId == null) {
            return false;
        }
        try {
            UserItem user = contentService.getUserItemById(repositoryId, userId);
            return user != null && user.isAdmin() != null && user.isAdmin();
        } catch (Exception e) {
            log.warn("Error checking admin status for user " + userId, e);
            return false;
        }
    }

}
