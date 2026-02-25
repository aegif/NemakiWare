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
import jakarta.ws.rs.core.Response;

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
	public Response triggerDeltaSync(@PathParam("repositoryId") String repositoryId,
			@FormParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return Response.status(Response.Status.FORBIDDEN)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider is required");
			return Response.status(Response.Status.BAD_REQUEST)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		CloudSyncResult syncResult = service.startDeltaSync(repositoryId, provider.trim());
		populateResult(result, syncResult);
		return Response.ok(makeResult(true, result, errMsg).toString()).build();
	}

	@POST
	@Path("/full-reconciliation")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response triggerFullReconciliation(@PathParam("repositoryId") String repositoryId,
			@FormParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return Response.status(Response.Status.FORBIDDEN)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider is required");
			return Response.status(Response.Status.BAD_REQUEST)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		CloudSyncResult syncResult = service.startFullReconciliation(repositoryId, provider.trim());
		populateResult(result, syncResult);
		return Response.ok(makeResult(true, result, errMsg).toString()).build();
	}

	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response getStatus(@PathParam("repositoryId") String repositoryId,
			@QueryParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return Response.status(Response.Status.FORBIDDEN)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider query parameter is required");
			return Response.status(Response.Status.BAD_REQUEST)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		CloudSyncResult syncResult = service.getSyncStatus(repositoryId, provider.trim());
		populateResult(result, syncResult);
		return Response.ok(makeResult(true, result, errMsg).toString()).build();
	}

	@POST
	@Path("/cancel")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response cancelSync(@PathParam("repositoryId") String repositoryId,
			@FormParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return Response.status(Response.Status.FORBIDDEN)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider is required");
			return Response.status(Response.Status.BAD_REQUEST)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		service.cancelSync(repositoryId, provider.trim());
		result.put("cancelled", true);
		return Response.ok(makeResult(true, result, errMsg).toString()).build();
	}

	@GET
	@Path("/test-connection")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response testConnection(@PathParam("repositoryId") String repositoryId,
			@QueryParam("provider") String provider,
			@Context HttpServletRequest request) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return Response.status(Response.Status.FORBIDDEN)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		if (provider == null || provider.trim().isEmpty()) {
			errMsg.add("Provider query parameter is required");
			return Response.status(Response.Status.BAD_REQUEST)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		CloudDirectorySyncService service = getService();
		if (service == null) {
			errMsg.add("Cloud directory sync service is not available");
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(makeResult(false, result, errMsg).toString()).build();
		}

		boolean connected = service.testConnection(provider.trim());
		result.put("connected", connected);
		result.put("provider", provider.trim());
		return Response.ok(makeResult(true, result, errMsg).toString()).build();
	}

	@SuppressWarnings("unchecked")
	private void populateResult(JSONObject result, CloudSyncResult syncResult) {
		result.put("syncId", syncResult.getSyncId());
		// Use "syncStatus" to avoid collision with makeResult's "status" field ("success"/"failure")
		result.put("syncStatus", syncResult.getStatus().name());
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
