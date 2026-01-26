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
 *     aegif - Directory Sync feature implementation
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.sync.model.DirectorySyncConfig;
import jp.aegif.nemaki.sync.model.DirectorySyncResult;
import jp.aegif.nemaki.sync.service.DirectorySyncService;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

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

@Path("/repo/{repositoryId}/sync")
public class DirectorySyncResource extends ResourceBase {

    private static final Log log = LogFactory.getLog(DirectorySyncResource.class);

    private DirectorySyncService directorySyncService;

    private DirectorySyncService getDirectorySyncService() {
        if (directorySyncService != null) {
            return directorySyncService;
        }
        return SpringContext.getApplicationContext()
                .getBean("DirectorySyncService", DirectorySyncService.class);
    }

    @SuppressWarnings("unchecked")
    @POST
    @Path("/trigger")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public String triggerSync(@PathParam("repositoryId") String repositoryId,
                              @QueryParam("dryRun") Boolean dryRun,
                              @Context HttpServletRequest httpRequest) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

        if (!checkAdminAuthorization(httpRequest, errMsg)) {
            status = false;
            result = makeResult(status, result, errMsg);
            return result.toString();
        }

        try {
            boolean isDryRun = dryRun != null && dryRun;
            log.info("Directory sync triggered for repository: " + repositoryId + " (dryRun=" + isDryRun + ")");
            
            DirectorySyncResult syncResult = getDirectorySyncService().syncGroups(repositoryId, isDryRun);
            result.put("syncResult", convertSyncResultToJson(syncResult));
            
        } catch (Exception e) {
            log.error("Error triggering directory sync: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "sync", "ERR_SYNC_FAILED: " + sanitizeErrorMessage(e.getMessage()));
        }

        result = makeResult(status, result, errMsg);
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    @GET
    @Path("/preview")
    @Produces(MediaType.APPLICATION_JSON)
    public String previewSync(@PathParam("repositoryId") String repositoryId,
                              @Context HttpServletRequest httpRequest) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

        if (!checkAdminAuthorization(httpRequest, errMsg)) {
            status = false;
            result = makeResult(status, result, errMsg);
            return result.toString();
        }

        try {
            log.info("Directory sync preview requested for repository: " + repositoryId);
            
            DirectorySyncResult syncResult = getDirectorySyncService().previewSync(repositoryId);
            result.put("syncResult", convertSyncResultToJson(syncResult));
            
        } catch (Exception e) {
            log.error("Error previewing directory sync: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "sync", "ERR_PREVIEW_FAILED: " + sanitizeErrorMessage(e.getMessage()));
        }

        result = makeResult(status, result, errMsg);
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public String getStatus(@PathParam("repositoryId") String repositoryId,
                            @Context HttpServletRequest httpRequest) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

        if (!checkAdminAuthorization(httpRequest, errMsg)) {
            status = false;
            result = makeResult(status, result, errMsg);
            return result.toString();
        }

        try {
            DirectorySyncResult lastResult = getDirectorySyncService().getLastSyncResult(repositoryId);
            if (lastResult != null) {
                result.put("lastSyncResult", convertSyncResultToJson(lastResult));
            } else {
                result.put("lastSyncResult", null);
                result.put("message", "No sync has been performed yet");
            }
            
        } catch (Exception e) {
            log.error("Error getting sync status: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "sync", "ERR_STATUS_FAILED: " + sanitizeErrorMessage(e.getMessage()));
        }

        result = makeResult(status, result, errMsg);
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    @GET
    @Path("/config")
    @Produces(MediaType.APPLICATION_JSON)
    public String getConfig(@PathParam("repositoryId") String repositoryId,
                            @Context HttpServletRequest httpRequest) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

        if (!checkAdminAuthorization(httpRequest, errMsg)) {
            status = false;
            result = makeResult(status, result, errMsg);
            return result.toString();
        }

        try {
            DirectorySyncConfig config = getDirectorySyncService().getConfig(repositoryId);
            result.put("config", convertConfigToJson(config));
            
        } catch (Exception e) {
            log.error("Error getting sync config: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "sync", "ERR_CONFIG_FAILED: " + sanitizeErrorMessage(e.getMessage()));
        }

        result = makeResult(status, result, errMsg);
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    @GET
    @Path("/test-connection")
    @Produces(MediaType.APPLICATION_JSON)
    public String testConnection(@PathParam("repositoryId") String repositoryId,
                                 @Context HttpServletRequest httpRequest) {
        
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

        if (!checkAdminAuthorization(httpRequest, errMsg)) {
            status = false;
            result = makeResult(status, result, errMsg);
            return result.toString();
        }

        try {
            log.info("Testing LDAP connection for repository: " + repositoryId);
            
            boolean connectionSuccess = getDirectorySyncService().testConnection(repositoryId);
            result.put("connectionSuccess", connectionSuccess);
            
            if (!connectionSuccess) {
                result.put("message", "Failed to connect to LDAP server. Check configuration.");
            } else {
                result.put("message", "Successfully connected to LDAP server.");
            }
            
        } catch (Exception e) {
            log.error("Error testing LDAP connection: " + e.getMessage(), e);
            status = false;
            addErrMsg(errMsg, "sync", "ERR_CONNECTION_TEST_FAILED: " + sanitizeErrorMessage(e.getMessage()));
        }

        result = makeResult(status, result, errMsg);
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private JSONObject convertSyncResultToJson(DirectorySyncResult syncResult) {
        JSONObject json = new JSONObject();
        json.put("syncId", syncResult.getSyncId());
        json.put("repositoryId", syncResult.getRepositoryId());
        json.put("status", syncResult.getStatus() != null ? syncResult.getStatus().name() : null);
        json.put("dryRun", syncResult.isDryRun());
        json.put("startTime", syncResult.getStartTime() != null ? syncResult.getStartTime().getTimeInMillis() : null);
        json.put("endTime", syncResult.getEndTime() != null ? syncResult.getEndTime().getTimeInMillis() : null);
        json.put("groupsCreated", syncResult.getGroupsCreated());
        json.put("groupsUpdated", syncResult.getGroupsUpdated());
        json.put("groupsDeleted", syncResult.getGroupsDeleted());
        json.put("groupsSkipped", syncResult.getGroupsSkipped());
        json.put("usersAdded", syncResult.getUsersAdded());
        json.put("usersUpdated", syncResult.getUsersUpdated());
        json.put("usersRemoved", syncResult.getUsersRemoved());
        json.put("usersSkipped", syncResult.getUsersSkipped());
        
        JSONArray errors = new JSONArray();
        if (syncResult.getErrors() != null) {
            for (DirectorySyncResult.SyncError error : syncResult.getErrors()) {
                JSONObject errorJson = new JSONObject();
                errorJson.put("groupId", error.getGroupId());
                errorJson.put("message", error.getMessage());
                errors.add(errorJson);
            }
        }
        json.put("errors", errors);
        
        JSONArray warnings = new JSONArray();
        if (syncResult.getWarnings() != null) {
            for (DirectorySyncResult.SyncWarning warning : syncResult.getWarnings()) {
                JSONObject warningJson = new JSONObject();
                warningJson.put("groupId", warning.getGroupId());
                warningJson.put("message", warning.getMessage());
                warnings.add(warningJson);
            }
        }
        json.put("warnings", warnings);
        
        return json;
    }

    @SuppressWarnings("unchecked")
    private JSONObject convertConfigToJson(DirectorySyncConfig config) {
        JSONObject json = new JSONObject();
        json.put("repositoryId", config.getRepositoryId());
        json.put("enabled", config.isEnabled());
        json.put("ldapUrl", config.getLdapUrl());
        json.put("ldapBaseDn", config.getLdapBaseDn());
        json.put("ldapBindDn", config.getLdapBindDn());
        json.put("useTls", config.isUseTls());
        json.put("useStartTls", config.isUseStartTls());
        json.put("connectionTimeout", config.getConnectionTimeout());
        json.put("readTimeout", config.getReadTimeout());
        json.put("groupSearchBase", config.getGroupSearchBase());
        json.put("groupSearchFilter", config.getGroupSearchFilter());
        json.put("userSearchBase", config.getUserSearchBase());
        json.put("userSearchFilter", config.getUserSearchFilter());
        json.put("groupIdAttribute", config.getGroupIdAttribute());
        json.put("groupNameAttribute", config.getGroupNameAttribute());
        json.put("groupMemberAttribute", config.getGroupMemberAttribute());
        json.put("userIdAttribute", config.getUserIdAttribute());
        json.put("syncNestedGroups", config.isSyncNestedGroups());
        json.put("createMissingUsers", config.isCreateMissingUsers());
        json.put("updateExistingUsers", config.isUpdateExistingUsers());
        json.put("deleteOrphanGroups", config.isDeleteOrphanGroups());
        json.put("deleteOrphanUsers", config.isDeleteOrphanUsers());
        json.put("groupPrefix", config.getGroupPrefix());
        json.put("userPrefix", config.getUserPrefix());
        json.put("scheduleEnabled", config.isScheduleEnabled());
        json.put("cronExpression", config.getCronExpression());
        json.put("lastSyncTime", config.getLastSyncTime() != null ? config.getLastSyncTime().getTimeInMillis() : null);
        json.put("lastSyncStatus", config.getLastSyncStatus());
        return json;
    }

    /**
     * Check if the current user has admin authorization.
     * Uses the parent class's checkAdmin method which correctly retrieves
     * the admin status from the CallContext set by AuthenticationFilter.
     */
    private boolean checkAdminAuthorization(HttpServletRequest request, JSONArray errMsg) {
        return checkAdmin(errMsg, request);
    }

    public void setDirectorySyncService(DirectorySyncService directorySyncService) {
        this.directorySyncService = directorySyncService;
    }
    
    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "An unexpected error occurred";
        }
        
        String sanitized = message;
        
        // Redact passwords and credentials
        sanitized = sanitized.replaceAll("(?i)(password|credential|secret|key|token|auth)\\s*[=:]\\s*\\S+", "$1=[REDACTED]");
        
        // Redact IP addresses
        sanitized = sanitized.replaceAll("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b", "[IP_REDACTED]");
        
        // Redact LDAP/LDAPS URLs (keep protocol, redact host)
        sanitized = sanitized.replaceAll("(?i)(ldap://|ldaps://)([^/\\s:]+)(:\\d+)?", "$1[HOST_REDACTED]$3");
        
        // Redact LDAP DN components
        sanitized = sanitized.replaceAll("(?i)(cn|ou|dc|uid|dn|o|c)=[^,\\s]+", "$1=[REDACTED]");
        
        // Remove stack trace indicators
        sanitized = sanitized.replaceAll("\\s*at\\s+[a-zA-Z0-9.$_]+\\.[a-zA-Z0-9$_]+\\([^)]*\\)", "");
        sanitized = sanitized.replaceAll("(?i)\\s*caused by:\\s*[^\\n]+", "");
        sanitized = sanitized.replaceAll("(?i)\\s*nested exception is\\s*[^\\n]+", "");
        
        // Remove Java package names that might reveal internal structure
        sanitized = sanitized.replaceAll("\\b(java|javax|org|com|jp)\\.[a-zA-Z0-9.$_]+\\b", "[CLASS]");
        
        // Redact file paths
        sanitized = sanitized.replaceAll("(?i)(/[a-zA-Z0-9._-]+)+", "[PATH]");
        sanitized = sanitized.replaceAll("(?i)([a-zA-Z]:\\\\[^\\s]+)", "[PATH]");
        
        // Remove multiple spaces and newlines
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        
        // Truncate to reasonable length
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200) + "...";
        }
        
        return sanitized;
    }
}
