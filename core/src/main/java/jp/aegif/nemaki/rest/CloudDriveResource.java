package jp.aegif.nemaki.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

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
import java.util.List;

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
	 * STRICT VALIDATION: Compares scheme+host+port exactly.
	 * Port omission is only allowed for standard ports (HTTP:80, HTTPS:443).
	 *
	 * @param request The HTTP request
	 * @return Error message if CSRF check fails, null if validation passes
	 */
	private String validateCsrfProtection(HttpServletRequest request) {
		// Get the expected origin components from the request
		String serverHost = request.getServerName();
		int serverPort = request.getServerPort();
		String scheme = request.getScheme();

		// Check Origin header first (more reliable)
		String origin = request.getHeader("Origin");
		if (origin != null && !origin.isEmpty()) {
			if (isOriginValid(origin, scheme, serverHost, serverPort)) {
				return null; // Valid origin
			}
			log.warn("CSRF protection: Origin header mismatch. Expected: " + scheme + "://" + serverHost +
				":" + serverPort + ", Received: " + origin);
			return "CSRF protection: invalid origin";
		}

		// Fall back to Referer header if Origin is not present
		String referer = request.getHeader("Referer");
		if (referer != null && !referer.isEmpty()) {
			try {
				URI refererUri = new URI(referer);
				String refererScheme = refererUri.getScheme();
				String refererHost = refererUri.getHost();
				int refererPort = refererUri.getPort();

				// Normalize port: -1 means default port for the scheme
				int normalizedRefererPort = normalizePort(refererScheme, refererPort);
				int normalizedServerPort = normalizePort(scheme, serverPort);

				// STRICT: scheme, host, AND port must all match
				if (scheme.equals(refererScheme) &&
					serverHost.equals(refererHost) &&
					normalizedServerPort == normalizedRefererPort) {
					return null; // Valid referer
				}

				// For development: allow localhost variations but still require port match
				if (isLocalhostDev(serverHost, refererHost)) {
					if (normalizedServerPort == normalizedRefererPort) {
						return null; // Allow localhost with same port for development
					}
				}
			} catch (Exception e) {
				log.warn("CSRF protection: Invalid Referer header: " + referer);
			}
			log.warn("CSRF protection: Referer header mismatch. Expected: " + scheme + "://" + serverHost +
				":" + serverPort + ", Referer: " + referer);
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

	/**
	 * SECURITY: Validate origin string against expected scheme/host/port.
	 * Uses STRICT matching: scheme+host+port must all match exactly.
	 * Port omission in Origin header is only allowed for standard ports (HTTP:80, HTTPS:443).
	 */
	private boolean isOriginValid(String origin, String expectedScheme, String expectedHost, int expectedPort) {
		try {
			URI originUri = new URI(origin);
			String originScheme = originUri.getScheme();
			String originHost = originUri.getHost();
			int originPort = originUri.getPort();

			// Normalize ports for comparison
			int normalizedOriginPort = normalizePort(originScheme, originPort);
			int normalizedExpectedPort = normalizePort(expectedScheme, expectedPort);

			// STRICT: scheme must match
			if (!expectedScheme.equals(originScheme)) {
				return false;
			}

			// STRICT: host must match exactly
			if (!expectedHost.equals(originHost)) {
				// For development: allow localhost <-> 127.0.0.1 but still require port match
				if (!isLocalhostDev(expectedHost, originHost)) {
					return false;
				}
			}

			// STRICT: port must match (after normalization)
			return normalizedExpectedPort == normalizedOriginPort;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Normalize port number: return default port for scheme if port is -1 or matches standard port.
	 * HTTP: 80, HTTPS: 443
	 */
	private int normalizePort(String scheme, int port) {
		if ("https".equalsIgnoreCase(scheme)) {
			return (port == -1 || port == 443) ? 443 : port;
		} else if ("http".equalsIgnoreCase(scheme)) {
			return (port == -1 || port == 80) ? 80 : port;
		}
		// For other schemes, return port as-is (or -1)
		return port;
	}

	/**
	 * Check if both hosts are localhost variations (for development only).
	 */
	private boolean isLocalhostDev(String host1, String host2) {
		return (("localhost".equals(host1) || "127.0.0.1".equals(host1)) &&
				("localhost".equals(host2) || "127.0.0.1".equals(host2)));
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

			// Fetch and save comments from the cloud file
			log.debug("[pullFromCloud] Fetching comments for cloudFileId=" + cloudFileId);
			try {
				String comments = service.getCloudComments(provider, cloudFileId, accessToken);
				// Avoid logging comment content to prevent PII/sensitive data leakage
				log.debug("[pullFromCloud] Comments fetched: " + (comments != null ? "found" : "none"));
				if (comments != null && !comments.isEmpty()) {
					saveCloudComments(callContext, repositoryId, objectId, comments);
					result.put("commentsImported", true);
					log.debug("[pullFromCloud] Imported cloud comments to object " + objectId);
				} else {
					log.debug("[pullFromCloud] No comments found for cloud file");
				}
			} catch (Exception e) {
				// Don't fail the pull operation just because comments fetch failed
				log.warn("[pullFromCloud] Failed to fetch/save cloud comments: " + e.getClass().getSimpleName());
				log.debug("[pullFromCloud] Cloud comments fetch exception detail", e);
			}

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
	 * Import a document from cloud drive into NemakiWare.
	 * Creates a new document with cloud sync secondary type and metadata.
	 * Also fetches and stores comments from the cloud file.
	 *
	 * POST /rest/repo/{repositoryId}/cloud-drive/import/{folderId}
	 * Body (multipart/form-data):
	 *   - content: file content
	 *   - provider: "google" | "microsoft"
	 *   - cloudFileId: file ID in cloud provider
	 *   - accessToken: OAuth access token for fetching comments
	 *   - fileName: (optional) filename override
	 */
	@SuppressWarnings("unchecked")
	@POST
	@Path("/import/{folderId}")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces(MediaType.APPLICATION_JSON)
	public String importFromCloud(
			@PathParam("repositoryId") String repositoryId,
			@PathParam("folderId") String folderId,
			@FormDataParam("content") InputStream contentStream,
			@FormDataParam("content") FormDataContentDisposition contentDisposition,
			@FormDataParam("provider") String provider,
			@FormDataParam("cloudFileId") String cloudFileId,
			@FormDataParam("accessToken") String accessToken,
			@FormDataParam("fileName") String fileName,
			@Context HttpServletRequest request) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		log.info("Cloud Drive import request: repository=" + repositoryId + ", folder=" + folderId +
			", provider=" + provider + ", cloudFileId=" + cloudFileId);

		// SECURITY: CSRF protection for state-changing endpoint
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			log.warn("CSRF validation failed: " + csrfError);
			addErrMsg(errMsg, "csrf", csrfError);
			result = makeResult(false, result, errMsg);
			return result.toJSONString();
		}

		try {
			// SECURITY: Require authenticated user's CallContext
			org.apache.chemistry.opencmis.commons.server.CallContext callContext =
				(org.apache.chemistry.opencmis.commons.server.CallContext) request.getAttribute("CallContext");
			if (callContext == null) {
				log.warn("No CallContext found in request - authentication required");
				addErrMsg(errMsg, "authentication", "User authentication required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// Validate required parameters
			if (provider == null || provider.isEmpty()) {
				log.warn("Missing required parameter: provider");
				addErrMsg(errMsg, "provider", "provider is required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
			if (cloudFileId == null || cloudFileId.isEmpty()) {
				log.warn("Missing required parameter: cloudFileId");
				addErrMsg(errMsg, "cloudFileId", "cloudFileId is required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
			if (!isCloudDriveEnabled(provider)) {
				log.warn("Cloud drive not enabled for provider: " + provider);
				addErrMsg(errMsg, "provider", "Cloud drive is not enabled for provider: " + provider);
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// Check file content
			if (contentStream == null) {
				log.warn("Missing file content in multipart request");
				addErrMsg(errMsg, "content", "File content is required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			// Determine filename from FormDataParam or contentDisposition
			String finalFileName = fileName;
			if (finalFileName == null || finalFileName.isEmpty()) {
				if (contentDisposition != null) {
					finalFileName = contentDisposition.getFileName();
				}
			}
			if (finalFileName == null || finalFileName.isEmpty()) {
				finalFileName = "imported-document";
			}
			log.info("Using filename: " + finalFileName);

			// Create document using ObjectService
			jp.aegif.nemaki.cmis.service.ObjectService objectService =
				SpringContext.getApplicationContext().getBean("objectService",
					jp.aegif.nemaki.cmis.service.ObjectService.class);

			// Build properties for new document
			org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl props =
				new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl();
			props.addProperty(new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl(
				org.apache.chemistry.opencmis.commons.PropertyIds.OBJECT_TYPE_ID, "cmis:document"));
			props.addProperty(new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl(
				org.apache.chemistry.opencmis.commons.PropertyIds.NAME, finalFileName));

			// Determine MIME type from content disposition or fallback to binary
			String mimeType = "application/octet-stream";
			if (contentDisposition != null && contentDisposition.getType() != null) {
				mimeType = contentDisposition.getType();
			}
			// Try to infer from filename extension
			if (finalFileName != null) {
				if (finalFileName.endsWith(".docx")) mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
				else if (finalFileName.endsWith(".xlsx")) mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
				else if (finalFileName.endsWith(".pptx")) mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
				else if (finalFileName.endsWith(".pdf")) mimeType = "application/pdf";
				else if (finalFileName.endsWith(".txt")) mimeType = "text/plain";
			}

			// Build content stream for CMIS
			org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl cmisContentStream =
				new org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl();
			cmisContentStream.setStream(contentStream);
			cmisContentStream.setMimeType(mimeType);
			cmisContentStream.setFileName(finalFileName);
			// Note: Size is unknown when streaming from FormDataParam, set to -1 (unknown)
			cmisContentStream.setLength(java.math.BigInteger.valueOf(-1));

			// Check if a document with the same name already exists in the folder
			jp.aegif.nemaki.businesslogic.ContentService contentService =
				SpringContext.getApplicationContext().getBean("ContentService",
					jp.aegif.nemaki.businesslogic.ContentService.class);

			String existingObjectId = null;
			List<jp.aegif.nemaki.model.Content> children = contentService.getChildren(repositoryId, folderId);
			if (children != null) {
				for (jp.aegif.nemaki.model.Content child : children) {
					if (child != null && finalFileName.equals(child.getName())) {
						// Check if it's a document (not a folder)
						if (child instanceof jp.aegif.nemaki.model.Document) {
							existingObjectId = child.getId();
							log.info("Found existing document with same name: " + existingObjectId);
							break;
						}
					}
				}
			}

			String newObjectId;
			boolean isNewVersion = false;

			if (existingObjectId != null) {
				// Document exists - create new version via checkOut/checkIn
				log.info("Creating new version for existing document: " + existingObjectId);

				jp.aegif.nemaki.cmis.service.VersioningService versioningService =
					SpringContext.getApplicationContext().getBean("versioningService",
						jp.aegif.nemaki.cmis.service.VersioningService.class);

				// CheckOut the existing document
				org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder =
					new org.apache.chemistry.opencmis.commons.spi.Holder<>(existingObjectId);
				versioningService.checkOut(callContext, repositoryId, objectIdHolder, null, null);
				String pwcId = objectIdHolder.getValue();
				log.info("Checked out document, PWC ID: " + pwcId);

				// CheckIn with new content (major version)
				org.apache.chemistry.opencmis.commons.spi.Holder<String> pwcIdHolder =
					new org.apache.chemistry.opencmis.commons.spi.Holder<>(pwcId);

				// No property changes needed for version update
				org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl checkInProps =
					new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl();

				versioningService.checkIn(callContext, repositoryId, pwcIdHolder, true,
					checkInProps, cmisContentStream, "Imported from " + provider + " cloud drive", null, null, null, null);

				newObjectId = pwcIdHolder.getValue();
				isNewVersion = true;
				log.info("Checked in new version: " + newObjectId + " (cloudFileId: " + cloudFileId + ")");
			} else {
				// Create new document
				newObjectId = objectService.createDocument(callContext, repositoryId, props,
					folderId, cmisContentStream, null, null, null, null, null);
				log.info("Created new document from cloud import: " + newObjectId + " (cloudFileId: " + cloudFileId + ")");
			}

			// Save cloud metadata as secondary properties
			CloudDriveService service = getCloudDriveService();
			String cloudFileUrl = (service != null) ? service.getCloudFileUrl(provider, cloudFileId) : null;
			saveCloudMetadata(callContext, repositoryId, newObjectId, provider, cloudFileId, cloudFileUrl);

			// Fetch and save comments from cloud file (if access token provided)
			log.debug("Checking comments: accessToken=" + (accessToken != null ? "present" : "null") +
					", service=" + (service != null ? "present" : "null"));
			if (accessToken != null && !accessToken.isEmpty() && service != null) {
				try {
					log.debug("Fetching comments from cloud file: " + cloudFileId);
					String comments = service.getCloudComments(provider, cloudFileId, accessToken);
					// Log presence and size only - never log content (PII risk)
					log.debug("Comments result: present=" + (comments != null) + ", length=" + (comments != null ? comments.length() : 0));
					if (comments != null && !comments.isEmpty()) {
						saveCloudComments(callContext, repositoryId, newObjectId, comments);
						result.put("commentsImported", true);
						log.debug("Imported cloud comments to object " + newObjectId + " (length=" + comments.length() + ")");
					} else {
						log.debug("No comments found for cloud file: " + cloudFileId);
					}
				} catch (Exception e) {
					// Don't fail the import just because comments fetch failed
					log.warn("Failed to fetch/save cloud comments: " + e.getClass().getSimpleName());
					log.debug("Cloud comments fetch exception detail", e);
				}
			} else {
				log.debug("Skipping comments fetch: accessToken or service not available");
			}

			result.put("objectId", newObjectId);
			result.put("name", finalFileName);
			result.put("cloudFileId", cloudFileId);
			result.put("cloudFileUrl", cloudFileUrl);
			result.put("provider", provider);
			result.put("isNewVersion", isNewVersion);

		} catch (Exception e) {
			log.error("Error importing from cloud: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "import", "Failed to import from cloud: " + e.getMessage());
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

	// Maximum size limit for external context to prevent DB bloat (5000 chars for RAG compatibility)
	private static final int MAX_EXTERNAL_CONTEXT_SIZE = 5000;

	/**
	 * Truncate external context JSON to stay within size limits.
	 * Preserves the most recent comments/activities by removing older entries first.
	 */
	@SuppressWarnings("unchecked")
	private String truncateExternalContextIfNeeded(String contextJson) {
		if (contextJson == null || contextJson.length() <= MAX_EXTERNAL_CONTEXT_SIZE) {
			return contextJson;
		}

		try {
			org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
			org.json.simple.JSONObject json = (org.json.simple.JSONObject) parser.parse(contextJson);

			// Remove older entries from comments array (keep most recent)
			if (json.containsKey("comments")) {
				org.json.simple.JSONArray comments = (org.json.simple.JSONArray) json.get("comments");
				while (json.toJSONString().length() > MAX_EXTERNAL_CONTEXT_SIZE && comments.size() > 1) {
					comments.remove(0); // Remove oldest
				}
			}

			// Remove older entries from activities array
			if (json.containsKey("activities")) {
				org.json.simple.JSONArray activities = (org.json.simple.JSONArray) json.get("activities");
				while (json.toJSONString().length() > MAX_EXTERNAL_CONTEXT_SIZE && activities.size() > 1) {
					activities.remove(0); // Remove oldest
				}
			}

			String result = json.toJSONString();
			if (result.length() > MAX_EXTERNAL_CONTEXT_SIZE) {
				log.warn("External context still exceeds size limit after truncation, truncating raw JSON");
				result = result.substring(0, MAX_EXTERNAL_CONTEXT_SIZE);
			}
			log.info("Truncated external context from " + contextJson.length() + " to " + result.length() + " characters");
			return result;
		} catch (Exception e) {
			log.warn("Failed to parse external context for truncation, using raw truncation: " + e.getMessage());
			return contextJson.substring(0, MAX_EXTERNAL_CONTEXT_SIZE);
		}
	}

	/**
	 * Get cloud provider from content's cloudDriveMetadata aspect.
	 */
	private String getCloudProviderFromContent(Content content) {
		java.util.List<jp.aegif.nemaki.model.Aspect> aspects = content.getAspects();
		if (aspects == null) return null;

		for (jp.aegif.nemaki.model.Aspect a : aspects) {
			if ("nemaki:cloudDriveMetadata".equals(a.getName())) {
				java.util.List<jp.aegif.nemaki.model.Property> props = a.getProperties();
				if (props != null) {
					for (jp.aegif.nemaki.model.Property p : props) {
						if ("nemaki:cloudProvider".equals(p.getKey())) {
							return (String) p.getValue();
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Save external context as a secondary type property on the CMIS object.
	 * This method stores context data from external sources (cloud sync, CRM, ERP, chat, etc.)
	 * in the nemaki:externalIntegration secondary type for RAG search and display.
	 *
	 * SECURITY: Requires authenticated CallContext for proper permission checks.
	 *
	 * @param callContext The authenticated user's call context (must not be null)
	 * @param repositoryId Repository ID
	 * @param objectId CMIS object ID
	 * @param contextJson JSON string containing external context data
	 * @param sourceType Source type: "cloud_sync", "crm", "erp", "chat", etc.
	 * @param sourceId Source system identifier (e.g., "google", "microsoft", "salesforce")
	 */
	private void saveExternalContext(org.apache.chemistry.opencmis.commons.server.CallContext callContext,
			String repositoryId, String objectId, String contextJson, String sourceType, String sourceId) {
		// SECURITY: Require CallContext to enforce permissions
		if (callContext == null) {
			throw new IllegalArgumentException("CallContext is required for permission enforcement");
		}

		// Truncate to prevent DB bloat and ensure RAG compatibility
		contextJson = truncateExternalContextIfNeeded(contextJson);

		ContentService cs = getContentService();
		if (cs == null) return;

		Content content = cs.getContent(repositoryId, objectId);
		if (content == null) return;

		// Add nemaki:externalIntegration secondary type ID if not present
		java.util.List<String> secondaryTypeIds = content.getSecondaryIds();
		if (secondaryTypeIds == null) {
			secondaryTypeIds = new java.util.ArrayList<>();
		}
		if (!secondaryTypeIds.contains("nemaki:externalIntegration")) {
			secondaryTypeIds.add("nemaki:externalIntegration");
			content.setSecondaryIds(secondaryTypeIds);
		}

		// Build Aspect with external context properties
		java.util.List<jp.aegif.nemaki.model.Aspect> aspects = content.getAspects();
		if (aspects == null) {
			aspects = new java.util.ArrayList<>();
		}

		// Find or create the externalIntegration aspect
		jp.aegif.nemaki.model.Aspect extAspect = null;
		for (jp.aegif.nemaki.model.Aspect a : aspects) {
			if ("nemaki:externalIntegration".equals(a.getName())) {
				extAspect = a;
				break;
			}
		}
		if (extAspect == null) {
			extAspect = new jp.aegif.nemaki.model.Aspect();
			extAspect.setName("nemaki:externalIntegration");
			extAspect.setProperties(new java.util.ArrayList<>());
			aspects.add(extAspect);
		}

		// Update aspect properties
		java.util.List<jp.aegif.nemaki.model.Property> aspectProps = extAspect.getProperties();
		if (aspectProps == null) {
			aspectProps = new java.util.ArrayList<>();
			extAspect.setProperties(aspectProps);
		}
		setOrAddProperty(aspectProps, "nemaki:externalContext", contextJson);
		setOrAddProperty(aspectProps, "nemaki:externalContextUpdatedAt",
			new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new java.util.Date()));
		setOrAddProperty(aspectProps, "nemaki:externalSourceType", sourceType != null ? sourceType : "unknown");
		setOrAddProperty(aspectProps, "nemaki:externalSourceId", sourceId != null ? sourceId : "unknown");

		content.setAspects(aspects);

		// SECURITY: Use the user's CallContext for proper permission checks
		cs.update(callContext, repositoryId, content);

		// Invalidate CMIS and content caches
		try {
			jp.aegif.nemaki.util.cache.NemakiCachePool cachePool =
				SpringContext.getApplicationContext().getBean("nemakiCachePool",
					jp.aegif.nemaki.util.cache.NemakiCachePool.class);
			cachePool.get(repositoryId).removeCmisAndContentCache(objectId);
		} catch (Exception e) {
			log.warn("Failed to invalidate cache for object " + objectId + ": " + e.getMessage());
		}

		log.info("Saved external context for object " + objectId + " (source: " + sourceType + "/" + sourceId + ")");
	}

	/**
	 * Convenience method for saving cloud sync comments/activities.
	 * Automatically sets sourceType="cloud_sync" and determines cloudProvider from object metadata.
	 */
	private void saveCloudComments(org.apache.chemistry.opencmis.commons.server.CallContext callContext,
			String repositoryId, String objectId, String commentsJson) {
		ContentService cs = getContentService();
		if (cs == null) return;

		Content content = cs.getContent(repositoryId, objectId);
		String cloudProvider = (content != null) ? getCloudProviderFromContent(content) : null;

		saveExternalContext(callContext, repositoryId, objectId, commentsJson, "cloud_sync",
			cloudProvider != null ? cloudProvider : "unknown");
	}
}
