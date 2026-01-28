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
package jp.aegif.nemaki.rest;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import jp.aegif.nemaki.rss.RssFeedService;
import jp.aegif.nemaki.rss.RssToken;
import jp.aegif.nemaki.rss.RssTokenService;
import jp.aegif.nemaki.util.spring.SpringContext;

@Path("/repo/{repositoryId}/rss")
public class RssFeedResource extends ResourceBase {
    
    private static final Log log = LogFactory.getLog(RssFeedResource.class);
    
    private static final String MEDIA_TYPE_RSS = "application/rss+xml";
    private static final String MEDIA_TYPE_ATOM = "application/atom+xml";
    
    private static final SimpleDateFormat ISO8601_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    
    static {
        ISO8601_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    private RssFeedService rssFeedService;
    private RssTokenService rssTokenService;
    private boolean enabled = true;
    
    public void setRssFeedService(RssFeedService rssFeedService) {
        this.rssFeedService = rssFeedService;
    }
    
    public void setRssTokenService(RssTokenService rssTokenService) {
        this.rssTokenService = rssTokenService;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    private RssFeedService getRssFeedService() {
        if (rssFeedService != null) {
            return rssFeedService;
        }
        try {
            RssFeedService service = SpringContext.getApplicationContext()
                    .getBean("rssFeedService", RssFeedService.class);
            if (service != null) {
                log.debug("RssFeedService retrieved from SpringContext successfully");
                return service;
            }
        } catch (Exception e) {
            log.debug("RssFeedService not available from SpringContext: " + e.getMessage());
        }
        return null;
    }
    
    private RssTokenService getRssTokenService() {
        if (rssTokenService != null) {
            return rssTokenService;
        }
        try {
            RssTokenService service = SpringContext.getApplicationContext()
                    .getBean("rssTokenService", RssTokenService.class);
            if (service != null) {
                log.debug("RssTokenService retrieved from SpringContext successfully");
                return service;
            }
        } catch (Exception e) {
            log.debug("RssTokenService not available from SpringContext: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Get RSS/Atom feed for a folder.
     * 
     * @param repositoryId The repository ID
     * @param folderId The folder ID
     * @param token The RSS access token
     * @param includeChildren Whether to include changes in child folders
     * @param maxDepth Maximum depth for child folder traversal
     * @param limit Maximum number of items to include
     * @param events Comma-separated list of event types to include
     * @param format Feed format (rss or atom)
     * @return RSS/Atom feed XML
     */
    @GET
    @Path("/folder/{folderId}")
    @Produces({MEDIA_TYPE_RSS, MEDIA_TYPE_ATOM, MediaType.APPLICATION_XML})
    public Response getFolderFeed(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("folderId") String folderId,
            @QueryParam("token") String token,
            @QueryParam("includeChildren") @DefaultValue("true") boolean includeChildren,
            @QueryParam("maxDepth") Integer maxDepth,
            @QueryParam("limit") Integer limit,
            @QueryParam("events") String events,
            @QueryParam("format") @DefaultValue("rss") String format) {
        
        if (!enabled) {
            return buildErrorResponse(Response.Status.SERVICE_UNAVAILABLE, "RSS feed functionality is disabled");
        }
        
        RssFeedService feedService = getRssFeedService();
        if (feedService == null) {
            return buildErrorResponse(Response.Status.SERVICE_UNAVAILABLE, "RSS feed service not available");
        }
        
        RssToken rssToken = validateToken(token);
        if (rssToken == null) {
            return buildErrorResponse(Response.Status.UNAUTHORIZED, "Invalid or expired token");
        }
        
        if (!rssToken.hasAccessToFolder(folderId)) {
            return buildErrorResponse(Response.Status.FORBIDDEN, "Token does not grant access to this folder");
        }
        
        Set<String> eventSet = parseEvents(events);
        
        try {
            String feedXml;
            String mediaType;
            
            if ("atom".equalsIgnoreCase(format)) {
                feedXml = feedService.generateFolderAtomFeed(
                    repositoryId, folderId, includeChildren, maxDepth, limit, eventSet, rssToken);
                mediaType = MEDIA_TYPE_ATOM;
            } else {
                feedXml = feedService.generateFolderRssFeed(
                    repositoryId, folderId, includeChildren, maxDepth, limit, eventSet, rssToken);
                mediaType = MEDIA_TYPE_RSS;
            }
            
            if (feedXml == null) {
                return buildErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Failed to generate feed");
            }
            
            return Response.ok(feedXml, mediaType)
                .header("Cache-Control", "max-age=60")
                .build();
                
        } catch (Exception e) {
            log.error("Error generating folder feed", e);
            return buildErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Error generating feed: " + e.getMessage());
        }
    }
    
    /**
     * Get RSS/Atom feed for a document.
     * 
     * @param repositoryId The repository ID
     * @param documentId The document ID
     * @param token The RSS access token
     * @param limit Maximum number of items to include
     * @param events Comma-separated list of event types to include
     * @param format Feed format (rss or atom)
     * @return RSS/Atom feed XML
     */
    @GET
    @Path("/document/{documentId}")
    @Produces({MEDIA_TYPE_RSS, MEDIA_TYPE_ATOM, MediaType.APPLICATION_XML})
    public Response getDocumentFeed(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("documentId") String documentId,
            @QueryParam("token") String token,
            @QueryParam("limit") Integer limit,
            @QueryParam("events") String events,
            @QueryParam("format") @DefaultValue("rss") String format) {
        
        if (!enabled) {
            return buildErrorResponse(Response.Status.SERVICE_UNAVAILABLE, "RSS feed functionality is disabled");
        }
        
        RssFeedService feedService = getRssFeedService();
        if (feedService == null) {
            return buildErrorResponse(Response.Status.SERVICE_UNAVAILABLE, "RSS feed service not available");
        }
        
        RssToken rssToken = validateToken(token);
        if (rssToken == null) {
            return buildErrorResponse(Response.Status.UNAUTHORIZED, "Invalid or expired token");
        }
        
        if (!rssToken.hasAccessToDocument(documentId)) {
            return buildErrorResponse(Response.Status.FORBIDDEN, "Token does not grant access to this document");
        }
        
        Set<String> eventSet = parseEvents(events);
        
        try {
            String feedXml;
            String mediaType;
            
            if ("atom".equalsIgnoreCase(format)) {
                feedXml = feedService.generateDocumentAtomFeed(
                    repositoryId, documentId, limit, eventSet, rssToken);
                mediaType = MEDIA_TYPE_ATOM;
            } else {
                feedXml = feedService.generateDocumentRssFeed(
                    repositoryId, documentId, limit, eventSet, rssToken);
                mediaType = MEDIA_TYPE_RSS;
            }
            
            if (feedXml == null) {
                return buildErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Failed to generate feed");
            }
            
            return Response.ok(feedXml, mediaType)
                .header("Cache-Control", "max-age=60")
                .build();
                
        } catch (Exception e) {
            log.error("Error generating document feed", e);
            return buildErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Error generating feed: " + e.getMessage());
        }
    }
    
    /**
     * Generate a new RSS access token.
     * 
     * @param repositoryId The repository ID
     * @param body JSON body containing token configuration
     * @return The generated token
     */
    @POST
    @Path("/tokens")
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public String generateToken(
            @PathParam("repositoryId") String repositoryId,
            String body,
            @Context HttpServletRequest request) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        if (!enabled) {
            status = false;
            addErrMsg(errMsg, "rss", "RSS feed functionality is disabled");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        if (!checkAdmin(errMsg, request)) {
            result = makeResult(false, result, errMsg);
            return result.toJSONString();
        }
        
        RssTokenService tokenService = getRssTokenService();
        if (tokenService == null) {
            status = false;
            addErrMsg(errMsg, "rssTokenService", "RSS token service not available");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        try {
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(body);
            
            String userId = (String) json.get("userId");
            String name = (String) json.get("name");
            
            if (userId == null || userId.isEmpty()) {
                status = false;
                addErrMsg(errMsg, "userId", "userId is required");
                result = makeResult(status, result, errMsg);
                return result.toJSONString();
            }
            
            List<String> folderIds = null;
            if (json.containsKey("folders")) {
                JSONArray foldersArray = (JSONArray) json.get("folders");
                folderIds = new java.util.ArrayList<>();
                for (Object folderId : foldersArray) {
                    folderIds.add((String) folderId);
                }
            }
            
            List<String> documentIds = null;
            if (json.containsKey("documents")) {
                JSONArray documentsArray = (JSONArray) json.get("documents");
                documentIds = new java.util.ArrayList<>();
                for (Object documentId : documentsArray) {
                    documentIds.add((String) documentId);
                }
            }
            
            Set<String> events = null;
            if (json.containsKey("events")) {
                JSONArray eventsArray = (JSONArray) json.get("events");
                events = new HashSet<>();
                for (Object event : eventsArray) {
                    events.add((String) event);
                }
            }
            
            Integer expiryDays = null;
            if (json.containsKey("expiryDays")) {
                expiryDays = ((Long) json.get("expiryDays")).intValue();
            }
            
            RssToken token = tokenService.generateToken(
                repositoryId, userId, name, folderIds, documentIds, events, expiryDays);
            
            result.put("id", token.getId());
            result.put("token", token.getToken());
            result.put("expiresAt", formatDate(token.getExpiresAt()));
            
        } catch (Exception e) {
            log.error("Error generating RSS token", e);
            status = false;
            addErrMsg(errMsg, "token", "Error generating token: " + e.getMessage());
        }
        
        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }
    
    @GET
    @Path("/tokens")
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public String getTokens(
            @PathParam("repositoryId") String repositoryId,
            @QueryParam("userId") String userId,
            @Context HttpServletRequest request) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        if (!enabled) {
            status = false;
            addErrMsg(errMsg, "rss", "RSS feed functionality is disabled");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        if (!checkAdmin(errMsg, request)) {
            result = makeResult(false, result, errMsg);
            return result.toJSONString();
        }
        
        RssTokenService tokenService = getRssTokenService();
        if (tokenService == null) {
            status = false;
            addErrMsg(errMsg, "rssTokenService", "RSS token service not available");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        if (userId == null || userId.isEmpty()) {
            status = false;
            addErrMsg(errMsg, "userId", "userId is required");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        try {
            List<RssToken> tokens = tokenService.getTokensForUser(repositoryId, userId);
            
            JSONArray tokensArray = new JSONArray();
            for (RssToken token : tokens) {
                JSONObject tokenJson = new JSONObject();
                tokenJson.put("id", token.getId());
                tokenJson.put("name", token.getName());
                tokenJson.put("enabled", token.isEnabled());
                tokenJson.put("createdAt", formatDate(token.getCreatedAt()));
                tokenJson.put("expiresAt", formatDate(token.getExpiresAt()));
                tokenJson.put("expired", token.isExpired());
                tokensArray.add(tokenJson);
            }
            result.put("tokens", tokensArray);
            
        } catch (Exception e) {
            log.error("Error getting RSS tokens", e);
            status = false;
            addErrMsg(errMsg, "tokens", "Error getting tokens: " + e.getMessage());
        }
        
        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }
    
    @PUT
    @Path("/tokens/{tokenId}/disable")
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public String disableToken(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("tokenId") String tokenId,
            @Context HttpServletRequest request) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        if (!enabled) {
            status = false;
            addErrMsg(errMsg, "rss", "RSS feed functionality is disabled");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        if (!checkAdmin(errMsg, request)) {
            result = makeResult(false, result, errMsg);
            return result.toJSONString();
        }
        
        RssTokenService tokenService = getRssTokenService();
        if (tokenService == null) {
            status = false;
            addErrMsg(errMsg, "rssTokenService", "RSS token service not available");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        try {
            boolean success = tokenService.disableToken(repositoryId, tokenId);
            
            if (!success) {
                status = false;
                addErrMsg(errMsg, "tokenId", "Token not found");
            } else {
                result.put("message", "Token disabled");
            }
            
        } catch (Exception e) {
            log.error("Error disabling RSS token", e);
            status = false;
            addErrMsg(errMsg, "token", "Error disabling token: " + e.getMessage());
        }
        
        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }
    
    @PUT
    @Path("/tokens/{tokenId}/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public String refreshToken(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("tokenId") String tokenId,
            String body,
            @Context HttpServletRequest request) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        if (!enabled) {
            status = false;
            addErrMsg(errMsg, "rss", "RSS feed functionality is disabled");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        if (!checkAdmin(errMsg, request)) {
            result = makeResult(false, result, errMsg);
            return result.toJSONString();
        }
        
        RssTokenService tokenService = getRssTokenService();
        if (tokenService == null) {
            status = false;
            addErrMsg(errMsg, "rssTokenService", "RSS token service not available");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        try {
            Integer expiryDays = null;
            if (body != null && !body.isEmpty()) {
                JSONParser parser = new JSONParser();
                JSONObject json = (JSONObject) parser.parse(body);
                if (json.containsKey("expiryDays")) {
                    expiryDays = ((Long) json.get("expiryDays")).intValue();
                }
            }
            
            RssToken token = tokenService.refreshToken(repositoryId, tokenId, expiryDays);
            
            if (token == null) {
                status = false;
                addErrMsg(errMsg, "tokenId", "Token not found");
            } else {
                result.put("id", token.getId());
                result.put("expiresAt", formatDate(token.getExpiresAt()));
            }
            
        } catch (Exception e) {
            log.error("Error refreshing RSS token", e);
            status = false;
            addErrMsg(errMsg, "token", "Error refreshing token: " + e.getMessage());
        }
        
        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }
    
    @DELETE
    @Path("/tokens/{tokenId}")
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public String deleteToken(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("tokenId") String tokenId,
            @Context HttpServletRequest request) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();
        
        if (!enabled) {
            status = false;
            addErrMsg(errMsg, "rss", "RSS feed functionality is disabled");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        if (!checkAdmin(errMsg, request)) {
            result = makeResult(false, result, errMsg);
            return result.toJSONString();
        }
        
        RssTokenService tokenService = getRssTokenService();
        if (tokenService == null) {
            status = false;
            addErrMsg(errMsg, "rssTokenService", "RSS token service not available");
            result = makeResult(status, result, errMsg);
            return result.toJSONString();
        }
        
        try {
            boolean success = tokenService.deleteToken(repositoryId, tokenId);
            
            if (!success) {
                status = false;
                addErrMsg(errMsg, "tokenId", "Token not found");
            } else {
                result.put("message", "Token deleted");
            }
            
        } catch (Exception e) {
            log.error("Error deleting RSS token", e);
            status = false;
            addErrMsg(errMsg, "token", "Error deleting token: " + e.getMessage());
        }
        
        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }
    
    private RssToken validateToken(String tokenValue) {
        RssTokenService tokenService = getRssTokenService();
        if (tokenService == null) {
            return null;
        }
        return tokenService.validateToken(tokenValue);
    }
    
    /**
     * Parse comma-separated events string into a Set.
     */
    private Set<String> parseEvents(String events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        return new HashSet<>(Arrays.asList(events.split(",")));
    }
    
    /**
     * Format a Calendar as ISO 8601 string.
     */
    private String formatDate(java.util.Calendar calendar) {
        if (calendar == null) {
            return null;
        }
        synchronized (ISO8601_FORMAT) {
            return ISO8601_FORMAT.format(calendar.getTime());
        }
    }
    
    /**
     * Build an error response.
     */
    @SuppressWarnings("unchecked")
    private Response buildErrorResponse(Response.Status status, String message) {
        JSONObject error = new JSONObject();
        error.put("error", message);
        error.put("status", status.getStatusCode());
        return Response.status(status)
            .entity(error.toJSONString())
            .type(MediaType.APPLICATION_JSON)
            .build();
    }
}
