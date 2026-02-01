package jp.aegif.nemaki.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import jp.aegif.nemaki.businesslogic.CloudDriveService;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.impl.CloudDriveServiceImpl;
import jp.aegif.nemaki.util.spring.SpringContext;
import jp.aegif.nemaki.model.Content;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.InputStream;

/**
 * REST API for Cloud Drive integration (Google Drive / OneDrive).
 *
 * Endpoints:
 * - POST /rest/repo/{repositoryId}/cloud-drive/push/{objectId}
 * - POST /rest/repo/{repositoryId}/cloud-drive/pull/{objectId}
 * - POST /rest/repo/{repositoryId}/cloud-drive/substitute/{objectId}
 * - GET  /rest/repo/{repositoryId}/cloud-drive/url/{objectId}
 */
@Path("/repo/{repositoryId}/cloud-drive")
public class CloudDriveResource extends ResourceBase {

	private static final Log log = LogFactory.getLog(CloudDriveResource.class);

	private CloudDriveService cloudDriveService;
	private ContentService contentService;

	public void setCloudDriveService(CloudDriveService cloudDriveService) {
		this.cloudDriveService = cloudDriveService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	private CloudDriveService getCloudDriveService() {
		if (cloudDriveService != null) {
			return cloudDriveService;
		}
		try {
			return SpringContext.getApplicationContext()
					.getBean("cloudDriveService", CloudDriveService.class);
		} catch (Exception e) {
			log.error("Failed to get CloudDriveService: " + e.getMessage());
			return null;
		}
	}

	private ContentService getContentService() {
		if (contentService != null) {
			return contentService;
		}
		try {
			return SpringContext.getApplicationContext()
					.getBean("ContentService", ContentService.class);
		} catch (Exception e) {
			log.debug("Could not find ContentService: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Push a document (typically a PWC) to a cloud drive.
	 *
	 * POST /rest/repo/{repositoryId}/cloud-drive/push/{objectId}
	 * Body: {"provider": "google"|"microsoft", "accessToken": "..."}
	 */
	@SuppressWarnings("unchecked")
	@POST
	@Path("/push/{objectId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public String pushToCloud(
			@PathParam("repositoryId") String repositoryId,
			@PathParam("objectId") String objectId,
			String requestBody,
			@Context HttpServletRequest request) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			JSONParser parser = new JSONParser();
			JSONObject body = (JSONObject) parser.parse(requestBody);

			String provider = (String) body.get("provider");
			String accessToken = (String) body.get("accessToken");

			if (provider == null || provider.isEmpty()) {
				addErrMsg(errMsg, "provider", "provider is required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
			if (accessToken == null || accessToken.isEmpty()) {
				addErrMsg(errMsg, "accessToken", "accessToken is required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			CloudDriveService service = getCloudDriveService();
			if (service == null) {
				addErrMsg(errMsg, "service", "CloudDriveService not available");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			String cloudFileId = service.pushToCloud(repositoryId, objectId, provider, accessToken);

			result.put("cloudFileId", cloudFileId);
			result.put("cloudFileUrl", service.getCloudFileUrl(provider, cloudFileId));
			result.put("provider", provider);

		} catch (Exception e) {
			log.error("Error pushing to cloud: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "push", "Failed to push to cloud: " + e.getMessage());
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 * Pull a document from cloud drive back into NemakiWare.
	 * Optionally check in the document after pull.
	 *
	 * POST /rest/repo/{repositoryId}/cloud-drive/pull/{objectId}
	 * Body: {"provider": "google"|"microsoft", "accessToken": "...", "cloudFileId": "..."}
	 */
	@SuppressWarnings("unchecked")
	@POST
	@Path("/pull/{objectId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public String pullFromCloud(
			@PathParam("repositoryId") String repositoryId,
			@PathParam("objectId") String objectId,
			String requestBody,
			@Context HttpServletRequest request) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			JSONParser parser = new JSONParser();
			JSONObject body = (JSONObject) parser.parse(requestBody);

			String provider = (String) body.get("provider");
			String accessToken = (String) body.get("accessToken");
			String cloudFileId = (String) body.get("cloudFileId");

			if (provider == null || provider.isEmpty()) {
				addErrMsg(errMsg, "provider", "provider is required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
			if (accessToken == null || accessToken.isEmpty()) {
				addErrMsg(errMsg, "accessToken", "accessToken is required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
			if (cloudFileId == null || cloudFileId.isEmpty()) {
				addErrMsg(errMsg, "cloudFileId", "cloudFileId is required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			CloudDriveService service = getCloudDriveService();
			if (service == null) {
				addErrMsg(errMsg, "service", "CloudDriveService not available");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// Pull content from cloud using the cloudFileId
			CloudDriveServiceImpl impl = (CloudDriveServiceImpl) service;
			InputStream cloudContent = impl.pullFromCloudByFileId(provider, cloudFileId, accessToken);

			// Read content into byte array for size info
			byte[] contentBytes = cloudContent.readAllBytes();
			cloudContent.close();

			// Use CMIS ObjectService to set the content stream
			org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl newStream =
				new org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl();
			newStream.setStream(new java.io.ByteArrayInputStream(contentBytes));
			newStream.setLength(java.math.BigInteger.valueOf(contentBytes.length));

			// Get the object service via Spring context
			jp.aegif.nemaki.cmis.service.ObjectService objectService =
				SpringContext.getApplicationContext().getBean("objectService",
					jp.aegif.nemaki.cmis.service.ObjectService.class);

			org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder =
				new org.apache.chemistry.opencmis.commons.spi.Holder<>(objectId);

			objectService.setContentStream(null, repositoryId, objectIdHolder, true, newStream, null, null);

			result.put("objectId", objectIdHolder.getValue());
			result.put("pulled", true);

		} catch (Exception e) {
			log.error("Error pulling from cloud: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "pull", "Failed to pull from cloud: " + e.getMessage());
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 * Get cloud file URL for a document that has cloud metadata.
	 *
	 * GET /rest/repo/{repositoryId}/cloud-drive/url/{objectId}
	 */
	@SuppressWarnings("unchecked")
	@GET
	@Path("/url/{objectId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String getCloudUrl(
			@PathParam("repositoryId") String repositoryId,
			@PathParam("objectId") String objectId,
			@Context HttpServletRequest request) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			ContentService cs = getContentService();
			if (cs == null) {
				addErrMsg(errMsg, "service", "ContentService not available");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			Content content = cs.getContent(repositoryId, objectId);
			if (content == null) {
				addErrMsg(errMsg, "objectId", "Object not found: " + objectId);
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// Read cloud metadata from secondary type properties
			String provider = getSecondaryProperty(content, "nemaki:cloudProvider");
			String cloudFileId = getSecondaryProperty(content, "nemaki:cloudFileId");

			if (provider == null || cloudFileId == null) {
				addErrMsg(errMsg, "metadata", "Document has no cloud drive metadata");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			CloudDriveService service = getCloudDriveService();
			if (service == null) {
				addErrMsg(errMsg, "service", "CloudDriveService not available");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			String url = service.getCloudFileUrl(provider, cloudFileId);
			result.put("cloudFileUrl", url);
			result.put("provider", provider);
			result.put("cloudFileId", cloudFileId);

		} catch (Exception e) {
			log.error("Error getting cloud URL: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "url", "Failed to get cloud URL: " + e.getMessage());
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 * Read a secondary type property from content.
	 */
	private String getSecondaryProperty(Content content, String propertyId) {
		if (content.getSubTypeProperties() == null) {
			return null;
		}
		for (jp.aegif.nemaki.model.Property prop : content.getSubTypeProperties()) {
			if (propertyId.equals(prop.getKey())) {
				Object value = prop.getValue();
				return value != null ? value.toString() : null;
			}
		}
		return null;
	}
}
