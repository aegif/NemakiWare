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
import java.net.URI;

/**
 * REST API for Cloud Drive integration (Google Drive / OneDrive).
 *
 * SECURITY: All state-changing endpoints (push, pull) require CSRF protection
 * via Origin/Referer header validation when cookie-based authentication is used.
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

	/**
	 * SECURITY: Validate Origin/Referer header to prevent CSRF attacks.
	 * This is required for state-changing endpoints that accept cookie-based authentication.
	 *
	 * @param request The HTTP request
	 * @return Error message if CSRF check fails, null if validation passes
	 */
	private String validateCsrfProtection(HttpServletRequest request) {
		// Get the expected host from the request
		String serverHost = request.getServerName();
		int serverPort = request.getServerPort();
		String scheme = request.getScheme();

		// Build expected origin patterns
		String expectedOriginWithPort = scheme + "://" + serverHost + ":" + serverPort;
		String expectedOriginWithoutPort = scheme + "://" + serverHost;

		// Check Origin header first (more reliable)
		String origin = request.getHeader("Origin");
		if (origin != null && !origin.isEmpty()) {
			// Validate origin matches the server
			if (origin.equals(expectedOriginWithPort) ||
				origin.equals(expectedOriginWithoutPort) ||
				origin.equals(scheme + "://" + serverHost)) {
				return null; // Valid origin
			}
			// For development: allow localhost variations
			if (serverHost.equals("localhost") || serverHost.equals("127.0.0.1")) {
				try {
					URI originUri = new URI(origin);
					String originHost = originUri.getHost();
					if ("localhost".equals(originHost) || "127.0.0.1".equals(originHost)) {
						return null; // Allow localhost for development
					}
				} catch (Exception e) {
					// Invalid URI, continue to reject
				}
			}
			log.warn("CSRF protection: Origin header mismatch. Expected: " + expectedOriginWithPort +
				", Received: " + origin);
			return "CSRF protection: invalid origin";
		}

		// Fall back to Referer header if Origin is not present
		String referer = request.getHeader("Referer");
		if (referer != null && !referer.isEmpty()) {
			try {
				URI refererUri = new URI(referer);
				String refererHost = refererUri.getHost();
				int refererPort = refererUri.getPort();
				String refererScheme = refererUri.getScheme();

				// Check if referer matches server
				if (serverHost.equals(refererHost)) {
					return null; // Valid referer
				}
				// For development: allow localhost variations
				if ((serverHost.equals("localhost") || serverHost.equals("127.0.0.1")) &&
					("localhost".equals(refererHost) || "127.0.0.1".equals(refererHost))) {
					return null; // Allow localhost for development
				}
			} catch (Exception e) {
				log.warn("CSRF protection: Invalid Referer header: " + referer);
			}
			log.warn("CSRF protection: Referer header mismatch. Expected host: " + serverHost +
				", Referer: " + referer);
			return "CSRF protection: invalid referer";
		}

		// If neither Origin nor Referer is present, check if request is from same origin
		// by checking for X-Requested-With header (set by XMLHttpRequest/fetch with credentials)
		String xRequestedWith = request.getHeader("X-Requested-With");
		if ("XMLHttpRequest".equals(xRequestedWith)) {
			return null; // Likely same-origin AJAX request
		}

		// SECURITY: For state-changing endpoints, require at least one of these headers
		// This prevents simple form-based CSRF attacks
		log.warn("CSRF protection: No Origin, Referer, or X-Requested-With header found");
		return "CSRF protection: missing origin verification headers";
	}

	private CloudDriveService cloudDriveService;
	private ContentService contentService;
	private jp.aegif.nemaki.util.PropertyManager propertyManager;

	public void setPropertyManager(jp.aegif.nemaki.util.PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	private jp.aegif.nemaki.util.PropertyManager getPropertyManager() {
		if (propertyManager != null) {
			return propertyManager;
		}
		try {
			return SpringContext.getApplicationContext()
					.getBean("propertyManager", jp.aegif.nemaki.util.PropertyManager.class);
		} catch (Exception e) {
			return null;
		}
	}

	private boolean isCloudDriveEnabled(String provider) {
		jp.aegif.nemaki.util.PropertyManager pm = getPropertyManager();
		if (pm == null) return false;
		String propKey;
		switch (provider) {
			case "google":
				propKey = jp.aegif.nemaki.util.constant.PropertyKey.CLOUD_DRIVE_GOOGLE_ENABLED;
				break;
			case "microsoft":
				propKey = jp.aegif.nemaki.util.constant.PropertyKey.CLOUD_DRIVE_MICROSOFT_ENABLED;
				break;
			default:
				return false;
		}
		return "true".equalsIgnoreCase(pm.readValue(propKey));
	}

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
	 * If the document already has cloud metadata (nemaki:cloudFileId), updates the existing cloud file.
	 * Otherwise creates a new cloud file. After push, saves cloud metadata as secondary properties.
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

		// SECURITY: CSRF protection for state-changing endpoint
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			result = makeResult(false, result, errMsg);
			return result.toJSONString();
		}

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
			if (!isCloudDriveEnabled(provider)) {
				addErrMsg(errMsg, "provider", "Cloud drive is not enabled for provider: " + provider);
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			CloudDriveService service = getCloudDriveService();
			if (service == null) {
				addErrMsg(errMsg, "service", "CloudDriveService not available");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// SECURITY: Get the authenticated user's CallContext for ACL enforcement
			org.apache.chemistry.opencmis.commons.server.CallContext callContext =
				(org.apache.chemistry.opencmis.commons.server.CallContext) request.getAttribute("CallContext");
			if (callContext == null) {
				addErrMsg(errMsg, "authentication", "User authentication required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// Check for existing cloud file ID to update instead of creating a new file
			String existingCloudFileId = null;
			try {
				ContentService cs = getContentService();
				if (cs != null) {
					Content content = cs.getContent(repositoryId, objectId);
					if (content != null) {
						existingCloudFileId = getSecondaryProperty(content, "nemaki:cloudFileId");
						String existingProvider = getSecondaryProperty(content, "nemaki:cloudProvider");
						// Only reuse if same provider
						if (existingCloudFileId != null && !provider.equals(existingProvider)) {
							existingCloudFileId = null;
						}
					}
				}
			} catch (Exception e) {
				log.warn("Could not check existing cloud metadata: " + e.getMessage());
			}

			// SECURITY: Pass user's CallContext to enforce ACL checks
			String cloudFileId = service.pushToCloud(callContext, repositoryId, objectId, provider, accessToken, existingCloudFileId);

			// Save cloud metadata as secondary properties on the CMIS object
			// SECURITY: Pass user's CallContext for proper permission checks
			try {
				saveCloudMetadata(callContext, repositoryId, objectId, provider, cloudFileId,
					service.getCloudFileUrl(provider, cloudFileId));
			} catch (Exception e) {
				log.warn("Failed to save cloud metadata to object properties: " + e.getMessage(), e);
				// Don't fail the push operation just because metadata save failed
			}

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
	 * Updates the content stream of the document (typically a PWC) with content from cloud.
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

		// SECURITY: CSRF protection for state-changing endpoint
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			result = makeResult(false, result, errMsg);
			return result.toJSONString();
		}

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
			if (!isCloudDriveEnabled(provider)) {
				addErrMsg(errMsg, "provider", "Cloud drive is not enabled for provider: " + provider);
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			CloudDriveService service = getCloudDriveService();
			if (service == null) {
				addErrMsg(errMsg, "service", "CloudDriveService not available");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// Pull content from cloud
			InputStream cloudContent = service.pullFromCloudByFileId(provider, cloudFileId, accessToken);

			// Get current document to retrieve MIME type and change token
			ContentService cs = getContentService();
			Content content = cs.getContent(repositoryId, objectId);
			if (content == null) {
				addErrMsg(errMsg, "objectId", "Object not found: " + objectId);
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// SECURITY: Require authenticated user's CallContext
			// This ensures proper ACL enforcement and lastModifiedBy reflects the actual user
			org.apache.chemistry.opencmis.commons.server.CallContext callContext =
				(org.apache.chemistry.opencmis.commons.server.CallContext) request.getAttribute("CallContext");
			if (callContext == null) {
				// SECURITY: Do NOT fall back to SystemCallContext - require authentication
				addErrMsg(errMsg, "authentication", "User authentication required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// Build content stream with proper MIME type
			org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl newStream =
				new org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl();
			newStream.setStream(cloudContent);
			newStream.setLength(java.math.BigInteger.valueOf(-1));
			// Get MIME type from existing content stream of the document
			try {
				jp.aegif.nemaki.cmis.service.ObjectService objSvc =
					SpringContext.getApplicationContext().getBean("objectService",
						jp.aegif.nemaki.cmis.service.ObjectService.class);
				org.apache.chemistry.opencmis.commons.data.ContentStream existingStream =
					objSvc.getContentStream(callContext, repositoryId, objectId, null, null, null);
				if (existingStream != null) {
					if (existingStream.getMimeType() != null) {
						newStream.setMimeType(existingStream.getMimeType());
					}
					if (existingStream.getFileName() != null) {
						newStream.setFileName(existingStream.getFileName());
					}
				}
			} catch (Exception e) {
				log.warn("Could not determine MIME type from existing content: " + e.getMessage());
				// Fall back to docx MIME type for cloud-exported documents
				newStream.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
			}
			String changeToken = content.getChangeToken();

			jp.aegif.nemaki.cmis.service.ObjectService objectService =
				SpringContext.getApplicationContext().getBean("objectService",
					jp.aegif.nemaki.cmis.service.ObjectService.class);

			org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder =
				new org.apache.chemistry.opencmis.commons.spi.Holder<>(objectId);
			org.apache.chemistry.opencmis.commons.spi.Holder<String> changeTokenHolder =
				(changeToken != null) ? new org.apache.chemistry.opencmis.commons.spi.Holder<>(changeToken) : null;

			objectService.setContentStream(callContext, repositoryId, objectIdHolder, true, newStream, changeTokenHolder, null);

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
	 * SECURITY: This endpoint enforces ACL via ObjectService.getObject() with user's CallContext.
	 * Users can only retrieve cloud URLs for objects they have READ permission on.
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
			// SECURITY: Get the authenticated user's CallContext for ACL enforcement
			org.apache.chemistry.opencmis.commons.server.CallContext callContext =
				(org.apache.chemistry.opencmis.commons.server.CallContext) request.getAttribute("CallContext");
			if (callContext == null) {
				addErrMsg(errMsg, "authentication", "User authentication required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// SECURITY: Use ObjectService.getObject to enforce ACL (READ permission check)
			// This will throw CmisPermissionDeniedException if user lacks access
			jp.aegif.nemaki.cmis.service.ObjectService objectService;
			try {
				objectService = SpringContext.getApplicationContext().getBean("objectService",
					jp.aegif.nemaki.cmis.service.ObjectService.class);
				// This call enforces ACL - throws exception if user lacks READ permission
				// Parameters: callContext, repositoryId, objectId, filter, includeAllowableActions,
				//             includeRelationships, renditionFilter, includePolicyIds, includeAcl, extension
				objectService.getObject(callContext, repositoryId, objectId, null, Boolean.FALSE,
					org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.NONE,
					null, Boolean.FALSE, Boolean.FALSE, null);
			} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException e) {
				addErrMsg(errMsg, "permission", "Access denied: " + e.getMessage());
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException e) {
				addErrMsg(errMsg, "objectId", "Object not found: " + objectId);
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// Now fetch the content (ACL already verified above)
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
		// Search in aspects (the canonical location for secondary type properties)
		if (content.getAspects() != null) {
			for (jp.aegif.nemaki.model.Aspect aspect : content.getAspects()) {
				if (aspect.getProperties() != null) {
					for (jp.aegif.nemaki.model.Property prop : aspect.getProperties()) {
						if (propertyId.equals(prop.getKey())) {
							Object value = prop.getValue();
							return value != null ? value.toString() : null;
						}
					}
				}
			}
		}
		// Fallback: search in subTypeProperties (legacy)
		if (content.getSubTypeProperties() != null) {
			for (jp.aegif.nemaki.model.Property prop : content.getSubTypeProperties()) {
				if (propertyId.equals(prop.getKey())) {
					Object value = prop.getValue();
					return value != null ? value.toString() : null;
				}
			}
		}
		return null;
	}

	/**
	 * Save cloud drive metadata as secondary properties on a CMIS object.
	 * SECURITY: Requires authenticated CallContext for proper permission checks.
	 *
	 * @param callContext The authenticated user's call context (must not be null)
	 */
	private void saveCloudMetadata(org.apache.chemistry.opencmis.commons.server.CallContext callContext,
			String repositoryId, String objectId,
			String provider, String cloudFileId, String cloudFileUrl) {
		// SECURITY: Require CallContext to enforce permissions
		if (callContext == null) {
			throw new IllegalArgumentException("CallContext is required for permission enforcement");
		}

		ContentService cs = getContentService();
		if (cs == null) return;

		Content content = cs.getContent(repositoryId, objectId);
		if (content == null) return;

		// Add secondary type ID if not present
		java.util.List<String> secondaryTypeIds = content.getSecondaryIds();
		if (secondaryTypeIds == null) {
			secondaryTypeIds = new java.util.ArrayList<>();
		}
		if (!secondaryTypeIds.contains("nemaki:cloudDriveMetadata")) {
			secondaryTypeIds.add("nemaki:cloudDriveMetadata");
			content.setSecondaryIds(secondaryTypeIds);
		}

		// Build Aspect with cloud metadata properties
		// NemakiWare stores secondary type properties in the "aspects" field
		java.util.List<jp.aegif.nemaki.model.Aspect> aspects = content.getAspects();
		if (aspects == null) {
			aspects = new java.util.ArrayList<>();
		}

		// Find or create the cloudDriveMetadata aspect
		jp.aegif.nemaki.model.Aspect cloudAspect = null;
		for (jp.aegif.nemaki.model.Aspect a : aspects) {
			if ("nemaki:cloudDriveMetadata".equals(a.getName())) {
				cloudAspect = a;
				break;
			}
		}
		if (cloudAspect == null) {
			cloudAspect = new jp.aegif.nemaki.model.Aspect();
			cloudAspect.setName("nemaki:cloudDriveMetadata");
			cloudAspect.setProperties(new java.util.ArrayList<>());
			aspects.add(cloudAspect);
		}

		// Update aspect properties
		java.util.List<jp.aegif.nemaki.model.Property> aspectProps = cloudAspect.getProperties();
		if (aspectProps == null) {
			aspectProps = new java.util.ArrayList<>();
			cloudAspect.setProperties(aspectProps);
		}
		setOrAddProperty(aspectProps, "nemaki:cloudProvider", provider);
		setOrAddProperty(aspectProps, "nemaki:cloudFileId", cloudFileId);
		setOrAddProperty(aspectProps, "nemaki:cloudFileUrl", cloudFileUrl);
		setOrAddProperty(aspectProps, "nemaki:cloudLastSyncedAt",
			new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new java.util.Date()));

		content.setAspects(aspects);

		// SECURITY: Use the user's CallContext for proper permission checks
		cs.update(callContext, repositoryId, content);

		// Invalidate CMIS and content caches so the updated secondary properties are visible
		try {
			jp.aegif.nemaki.util.cache.NemakiCachePool cachePool =
				SpringContext.getApplicationContext().getBean("nemakiCachePool",
					jp.aegif.nemaki.util.cache.NemakiCachePool.class);
			cachePool.get(repositoryId).removeCmisAndContentCache(objectId);
		} catch (Exception e) {
			log.warn("Failed to invalidate cache for object " + objectId + ": " + e.getMessage());
		}
		log.info("Saved cloud metadata for object " + objectId + ": provider=" + provider + ", cloudFileId=" + cloudFileId);
	}

	/**
	 * Set or add a property in the subtype properties list.
	 */
	private void setOrAddProperty(java.util.List<jp.aegif.nemaki.model.Property> props,
			String key, Object value) {
		for (jp.aegif.nemaki.model.Property prop : props) {
			if (key.equals(prop.getKey())) {
				prop.setValue(value);
				return;
			}
		}
		jp.aegif.nemaki.model.Property newProp = new jp.aegif.nemaki.model.Property();
		newProp.setKey(key);
		newProp.setValue(value);
		props.add(newProp);
	}
}
