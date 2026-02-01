package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.sync.service.CloudDirectorySyncService;
import jp.aegif.nemaki.sync.service.CloudSyncResult;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * REST API for cloud directory synchronization management.
 * Admin-only endpoints for triggering sync and monitoring progress.
 */
@Path("/repo/{repositoryId}/cloud-sync")
public class CloudDirectorySyncResource extends ResourceBase {

	private static final Log log = LogFactory.getLog(CloudDirectorySyncResource.class);

	private CloudDirectorySyncService cloudDirectorySyncService;

	public void setCloudDirectorySyncService(CloudDirectorySyncService cloudDirectorySyncService) {
		this.cloudDirectorySyncService = cloudDirectorySyncService;
	}

	private CloudDirectorySyncService getService() {
		if (cloudDirectorySyncService != null) {
			return cloudDirectorySyncService;
		}
		try {
			return SpringContext.getApplicationContext()
				.getBean("cloudDirectorySyncService", CloudDirectorySyncService.class);
		} catch (Exception e) {
			log.error("Failed to get CloudDirectorySyncService from SpringContext: " + e.getMessage());
			return null;
		}
	}

	@POST
	@Path("/trigger")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String triggerDeltaSync(@PathParam("repositoryId") String repositoryId,
			@FormParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(false, result, errMsg).toString();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider is required");
			return makeResult(false, result, errMsg).toString();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		CloudSyncResult syncResult = service.startDeltaSync(repositoryId, provider.trim());
		populateResult(result, syncResult);
		return makeResult(true, result, errMsg).toString();
	}

	@POST
	@Path("/full-reconciliation")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String triggerFullReconciliation(@PathParam("repositoryId") String repositoryId,
			@FormParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(false, result, errMsg).toString();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider is required");
			return makeResult(false, result, errMsg).toString();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		CloudSyncResult syncResult = service.startFullReconciliation(repositoryId, provider.trim());
		populateResult(result, syncResult);
		return makeResult(true, result, errMsg).toString();
	}

	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String getStatus(@PathParam("repositoryId") String repositoryId,
			@QueryParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(false, result, errMsg).toString();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider query parameter is required");
			return makeResult(false, result, errMsg).toString();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		CloudSyncResult syncResult = service.getSyncStatus(repositoryId, provider.trim());
		populateResult(result, syncResult);
		return makeResult(true, result, errMsg).toString();
	}

	@POST
	@Path("/cancel")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String cancelSync(@PathParam("repositoryId") String repositoryId,
			@FormParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(false, result, errMsg).toString();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider is required");
			return makeResult(false, result, errMsg).toString();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		service.cancelSync(repositoryId, provider.trim());
		result.put("cancelled", true);
		return makeResult(true, result, errMsg).toString();
	}

	@GET
	@Path("/test-connection")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String testConnection(@PathParam("repositoryId") String repositoryId,
			@QueryParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(false, result, errMsg).toString();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider query parameter is required");
			return makeResult(false, result, errMsg).toString();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean connected = service.testConnection(provider.trim());
		result.put("connected", connected);
		result.put("provider", provider.trim());
		return makeResult(true, result, errMsg).toString();
	}

	@SuppressWarnings("unchecked")
	private void populateResult(JSONObject result, CloudSyncResult syncResult) {
		result.put("syncId", syncResult.getSyncId());
		result.put("status", syncResult.getStatus().name());
		result.put("syncMode", syncResult.getSyncMode() != null ? syncResult.getSyncMode().name() : null);
		result.put("provider", syncResult.getProvider());
		result.put("repositoryId", syncResult.getRepositoryId());
		result.put("startTime", syncResult.getStartTime());
		result.put("endTime", syncResult.getEndTime());
		result.put("usersCreated", syncResult.getUsersCreated());
		result.put("usersUpdated", syncResult.getUsersUpdated());
		result.put("usersDeleted", syncResult.getUsersDeleted());
		result.put("usersSkipped", syncResult.getUsersSkipped());
		result.put("groupsCreated", syncResult.getGroupsCreated());
		result.put("groupsUpdated", syncResult.getGroupsUpdated());
		result.put("groupsDeleted", syncResult.getGroupsDeleted());
		result.put("groupsSkipped", syncResult.getGroupsSkipped());
		result.put("currentPage", syncResult.getCurrentPage());
		result.put("totalPages", syncResult.getTotalPages());

		JSONArray errors = new JSONArray();
		List<String> errorList = syncResult.getErrors();
		if (errorList != null) {
			errors.addAll(errorList);
		}
		result.put("errors", errors);

		JSONArray warnings = new JSONArray();
		List<String> warningList = syncResult.getWarnings();
		if (warningList != null) {
			warnings.addAll(warningList);
		}
		result.put("warnings", warnings);
	}
}
