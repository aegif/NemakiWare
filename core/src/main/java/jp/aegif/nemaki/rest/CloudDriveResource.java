package jp.aegif.nemaki.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import jp.aegif.nemaki.businesslogic.CloudDriveService;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.impl.CloudDriveServiceImpl;
import jp.aegif.nemaki.util.spring.SpringContext;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.rest.purview.journal.LineageMode;
import jp.aegif.nemaki.rest.purview.journal.LineageEmitter;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageEventBuilder;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalStore;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.rest.purview.journal.LineageTargetSink;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import jp.aegif.nemaki.rest.importexport.ImportExportUtils;
import jp.aegif.nemaki.rest.ingest.CanonicalImportService;
import jp.aegif.nemaki.rest.ingest.ExternalIngestRequest;
import jp.aegif.nemaki.rest.ingest.ExternalIngestResult;
import jp.aegif.nemaki.rest.ingest.SourceArchetype;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

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
	 * Validate that a client-provided cloud file URL belongs to an allowed host for the given provider.
	 * Only HTTPS URLs from known cloud provider domains are accepted.
	 */
	/**
	 * Infer MIME type from filename for cloud imports.
	 * Delegates to {@link ImportExportUtils#guessMimeType} (legacy Office .doc/.xls/.ppt, OOXML, zip, etc.);
	 * adds CSV/SVG fallbacks not covered by that helper.
	 * Package-visible for unit tests in {@code jp.aegif.nemaki.rest}.
	 */
	static String inferMimeType(String fileName) {
		String g = ImportExportUtils.guessMimeType(fileName);
		if (!"application/octet-stream".equals(g)) {
			return g;
		}
		if (fileName == null) {
			return "application/octet-stream";
		}
		String lower = fileName.toLowerCase();
		if (lower.endsWith(".csv")) {
			return "text/csv";
		}
		if (lower.endsWith(".svg")) {
			return "image/svg+xml";
		}
		return "application/octet-stream";
	}

	/**
	 * MIME for legacy cloud import when no connector/profile is configured.
	 * Uses {@link #inferMimeType} from the filename; if still {@code application/octet-stream},
	 * uses the multipart part's {@code Content-Type} when it is specific (not wildcard/octet-stream).
	 * <p>
	 * Note: {@link FormDataContentDisposition#getType()} is the disposition type ({@code form-data}),
	 * not the part MIME type — use {@link FormDataBodyPart#getMediaType()} for the latter.
	 */
	static String resolveLegacyCloudImportMimeType(String finalFileName, FormDataBodyPart contentPart) {
		String mimeType = inferMimeType(finalFileName);
		if (!"application/octet-stream".equals(mimeType)) {
			return mimeType;
		}
		if (contentPart == null || contentPart.getMediaType() == null) {
			return mimeType;
		}
		jakarta.ws.rs.core.MediaType mt = contentPart.getMediaType();
		if (mt.isWildcardType() || mt.isWildcardSubtype()) {
			return mimeType;
		}
		String base = mt.getType() + "/" + mt.getSubtype();
		if ("application/octet-stream".equalsIgnoreCase(base)) {
			return mimeType;
		}
		return base;
	}

	/** Package-visible for unit tests ({@code jp.aegif.nemaki.rest}). */
	static boolean isAllowedCloudUrl(String provider, String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			java.net.URI uri = new java.net.URI(url);
			String scheme = uri.getScheme();
			if (!"https".equalsIgnoreCase(scheme)) return false;
			String host = uri.getHost();
			if (host == null) return false;
			host = host.toLowerCase(Locale.ROOT);
			if ("google".equals(provider)) {
				return isAllowedGoogleDriveDocumentUrl(host, uri);
			} else if ("microsoft".equals(provider)) {
				return isAllowedMicrosoftCloudDocumentUrl(host, uri);
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Allow only HTTPS URLs that point at a specific Drive/Docs file or open dialog — not arbitrary *.google.com pages.
	 */
	static boolean isAllowedGoogleDriveDocumentUrl(String host, java.net.URI uri) {
		String path = uri.getPath() != null ? uri.getPath() : "";
		if ("docs.google.com".equals(host)) {
			return path.matches("/(document|spreadsheets|presentation|drawings|forms)/d/[^/]+(/.*)?");
		}
		if ("drive.google.com".equals(host)) {
			if (path.startsWith("/file/d/")) {
				return true;
			}
			if ("/open".equals(path) || path.startsWith("/open/")) {
				String q = uri.getQuery();
				return q != null && q.contains("id=");
			}
			return false;
		}
		return false;
	}

	/**
	 * Allow file-like cloud URLs for Microsoft (SharePoint/Teams/OneDrive/Office Online) — not generic *.microsoft.com sites.
	 * SharePoint site home, list views, and folder browsers are rejected (no file-shaped path or open query).
	 */
	static boolean isAllowedMicrosoftCloudDocumentUrl(String host, java.net.URI uri) {
		String path = uri.getPath() != null ? uri.getPath() : "";
		String query = uri.getQuery();

		if (host.endsWith(".sharepoint.com") || host.endsWith(".sharepoint.de")) {
			return isLikelySharePointFileOrOpenUrl(path, query);
		}
		if ("teams.microsoft.com".equals(host) || host.endsWith(".teams.microsoft.com")) {
			// File tab deep links; /l/channel/ etc. are not document URLs
			return path.startsWith("/l/file/");
		}
		if ("onedrive.live.com".equals(host)) {
			return path.contains("/edit") || (query != null && query.contains("id="));
		}
		if (host.endsWith(".officeapps.live.com")) {
			return path.contains("/we/") || path.contains("/op/") || path.contains("/wv/") || path.endsWith(".aspx");
		}
		if (host.endsWith(".onedrive.com")) {
			if (query != null && (query.contains("id=") || query.contains("resid="))) {
				return true;
			}
			String last = lastDecodedPathSegment(path);
			return last != null && isOfficeLikeFileName(last);
		}
		return false;
	}

	/**
	 * True for modern sharing links, WOPI/doc viewer, file GUID query, or a last path segment that looks like an office file.
	 */
	static boolean isLikelySharePointFileOrOpenUrl(String path, String query) {
		if (path == null) {
			path = "";
		}
		if (path.contains("/:f:/") || path.contains("/:x:/") || path.contains("/:w:/")
				|| path.contains("/:u:/") || path.contains("/:p:/") || path.contains("/:h:/")) {
			return true;
		}
		// Site chrome, lists, wiki — not a file deep link for cloud metadata
		if (path.contains("/SitePages/") || path.contains("/Forms/") || path.contains("/_catalogs/")) {
			return false;
		}
		if (path.contains("AllItems.aspx") || path.contains("Thumbnails.aspx")) {
			return false;
		}
		String lowerPath = path.toLowerCase(Locale.ROOT);
		if (lowerPath.contains("/_layouts/")) {
			return lowerPath.contains("doc.aspx") || lowerPath.contains("doc2.aspx") || lowerPath.contains("wopiframe")
					|| lowerPath.contains("download.aspx") || lowerPath.contains("wordframe.aspx")
					|| lowerPath.contains("excelframe.aspx") || lowerPath.contains("powerpointframe.aspx");
		}
		if (hasSharePointFileQuery(query)) {
			return true;
		}
		String last = lastDecodedPathSegment(path);
		return last != null && isOfficeLikeFileName(last);
	}

	private static boolean hasSharePointFileQuery(String query) {
		if (query == null || query.isBlank()) {
			return false;
		}
		if (query.contains("sourcedoc=")) {
			return true;
		}
		if (!query.contains("id=")) {
			return false;
		}
		// File GUID in id= (raw or URL-encoded braces)
		return query.contains("%7B") || query.contains("{")
				|| query.matches("(?i).*id=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*");
	}

	static String lastDecodedPathSegment(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}
		String trimmed = path;
		while (trimmed.endsWith("/") && trimmed.length() > 1) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		int lastSlash = trimmed.lastIndexOf('/');
		String raw = lastSlash < 0 ? trimmed : trimmed.substring(lastSlash + 1);
		if (raw.isEmpty()) {
			return null;
		}
		try {
			return URLDecoder.decode(raw, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return raw;
		}
	}

	/**
	 * File-like name (not .aspx site pages except already handled under _layouts).
	 */
	static boolean isOfficeLikeFileName(String name) {
		if (name == null || name.isEmpty()) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".aspx") || lower.endsWith(".html") || lower.endsWith(".htm")) {
			return false;
		}
		return name.matches("(?i).+\\.(docx?|xlsx?|pptx?|pdf|txt|csv|zip|msg|eml|rtf|odt|ods|md|png|jpe?g|gif|webp|mp4|m4v|mov|mp3|wav|aac|vsdx|dwg|one|ics|vcf|m4a|heic|tif|tiff|bmp|svg|json|xml|log|yaml|yml)$");
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

	private LineageConfig getLineageConfig() {
		try {
			return SpringContext.getApplicationContext()
					.getBean(LineageConfig.class);
		} catch (Exception e) {
			return null;
		}
	}

	/** The active emitter for this repository, or {@code null} when lineage is off. */
	private LineageEmitter resolveLineageEmitter(String repositoryId) {
		try {
			LineageConfig config = getLineageConfig();
			if (config == null) return null;
			LineageMode mode = config.getModeForRepository(repositoryId);
			if (mode == LineageMode.DISABLED) return null;
			LineageJournalStore store = SpringContext.getApplicationContext()
					.getBean(LineageJournalStore.class);
			@SuppressWarnings("unchecked")
			java.util.List<LineageTargetSink> sinks = (java.util.List<LineageTargetSink>)
					(java.util.List<?>) SpringContext.getApplicationContext()
					.getBeansOfType(LineageTargetSink.class).values().stream().toList();
			return config.createEmitterForMode(mode, store, sinks);
		} catch (Exception e) {
			log.warn("Lineage emitter resolution failed (non-fatal): " + e.getMessage());
			return null;
		}
	}

	/** Lineage-only config read; never throws (fail-open — a producer must not fail the op). */
	private java.util.List<String> lineageTargets() {
		try {
			LineageConfig lc = getLineageConfig();
			return lc != null ? lc.getTargets() : java.util.List.of();
		} catch (Exception e) {
			return java.util.List.of();
		}
	}

	/** Lineage-only name read; never throws — a lost name loses one fact, never the operation. */
	private String readDocumentNameQuietly(String repositoryId, String objectId) {
		try {
			Content c = getContentService().getContent(repositoryId, objectId);
			return c != null ? c.getName() : null;
		} catch (Exception e) {
			log.warn("Could not read document name for lineage: " + e.getMessage());
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

			// forceNew=true の場合は既存のcloudFileIdを無視して新規ファイルとして登録
			Boolean forceNew = (Boolean) body.get("forceNew");

			// Check for existing cloud file ID to update instead of creating a new file
			String existingCloudFileId = null;
			if (forceNew == null || !forceNew) {
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
			}

			// §3: the lineage operation id is issued when the business operation starts —
			// before the mutation, so retries of the emission reuse one identity.
			final String lineageOperationId = java.util.UUID.randomUUID().toString();

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

			// Lineage: one version-free fact. Everything lineage-only — config read, name read,
			// fact construction — runs inside the fail-open boundary; the business path holds
			// nothing but the UUID issued above.
			jp.aegif.nemaki.rest.purview.journal.LineageFactEmission.emitSafely(
					resolveLineageEmitter(repositoryId), () -> {
				String documentName = readDocumentNameQuietly(repositoryId, objectId);
				String occurredAt = java.time.Instant.now().toString();
				return new jp.aegif.nemaki.rest.purview.journal.LineageFact(
						repositoryId,
						LineageProcessType.CLOUD_SYNC_UPLOAD,
						lineageOperationId,
						occurredAt,
						java.util.List.of(jp.aegif.nemaki.rest.purview.journal.LineageEndpoint
								.document(repositoryId, objectId, documentName)),
						java.util.List.of(jp.aegif.nemaki.rest.purview.journal.LineageEndpoint
								.cloudObject(repositoryId, provider, cloudFileId)),
						lineageTargets(),
						null,
						new jp.aegif.nemaki.rest.purview.journal.LineageFact.LegacyV1Projection(
								LineageProcessType.CLOUD_SYNC_UPLOAD,
								java.util.List.of(LineageEvent.qualifiedName(repositoryId, objectId)),
								java.util.List.of("cloud://" + provider + "/" + cloudFileId),
								java.util.Map.of("provider", provider)));
			}, "repo=" + repositoryId + " op=" + lineageOperationId + " type=CLOUD_SYNC_UPLOAD");

			result.put("cloudFileId", cloudFileId);
			result.put("cloudFileUrl", service.getCloudFileUrl(provider, cloudFileId));
			result.put("provider", provider);

		} catch (Exception e) {
			log.error("Error pushing to cloud: " + e.getMessage(), e);
			status = false;
			if (e.getMessage() != null && e.getMessage().contains("Cloud file not found")) {
				addErrMsg(errMsg, "push", "CLOUD_FILE_NOT_FOUND:" + e.getMessage());
			} else {
				addErrMsg(errMsg, "push", "Failed to push to cloud: " + e.getMessage());
			}
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

			// §3: issued before the mutation this operation performs.
			final String lineageOperationId = java.util.UUID.randomUUID().toString();
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

			// Lineage: one version-free fact (pull into an existing document). All lineage-only
			// work runs inside the fail-open boundary; the holder is read there because checkin
			// may have moved it to a new version id.
			jp.aegif.nemaki.rest.purview.journal.LineageFactEmission.emitSafely(
					resolveLineageEmitter(repositoryId), () -> {
				String pulledObjectId = objectIdHolder.getValue();
				String documentName = readDocumentNameQuietly(repositoryId, pulledObjectId);
				String occurredAt = java.time.Instant.now().toString();
				return new jp.aegif.nemaki.rest.purview.journal.LineageFact(
						repositoryId,
						LineageProcessType.CLOUD_SYNC_DOWNLOAD,
						lineageOperationId,
						occurredAt,
						java.util.List.of(jp.aegif.nemaki.rest.purview.journal.LineageEndpoint
								.cloudObject(repositoryId, provider, cloudFileId)),
						java.util.List.of(jp.aegif.nemaki.rest.purview.journal.LineageEndpoint
								.document(repositoryId, pulledObjectId, documentName)),
						lineageTargets(),
						null,
						new jp.aegif.nemaki.rest.purview.journal.LineageFact.LegacyV1Projection(
								LineageProcessType.CLOUD_SYNC_DOWNLOAD,
								java.util.List.of("cloud://" + provider + "/" + cloudFileId),
								java.util.List.of(LineageEvent.qualifiedName(repositoryId, pulledObjectId)),
								java.util.Map.of("provider", provider)));
			}, "repo=" + repositoryId + " op=" + lineageOperationId + " type=CLOUD_SYNC_DOWNLOAD(pull)");

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
	 *   - cloudFileUrl: (optional) web URL from cloud provider (e.g. Graph API webUrl)
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
			@FormDataParam("content") FormDataBodyPart contentPart,
			@FormDataParam("provider") String provider,
			@FormDataParam("cloudFileId") String cloudFileId,
			@FormDataParam("accessToken") String accessToken,
			@FormDataParam("fileName") String fileName,
			@FormDataParam("cloudFileUrl") String clientCloudFileUrl,
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

			// Try canonical import pipeline if connector/profile are configured
			String canonicalResult = tryCanonicalImport(callContext, repositoryId, folderId, provider,
					cloudFileId, accessToken, finalFileName, contentStream, clientCloudFileUrl, result, errMsg);
			if (canonicalResult != null) {
				return canonicalResult;
			}

			// Legacy path: direct import (connector/profile not configured)
			// DEPRECATION: This path will be removed in a future release.
			// Create connector/profile definitions to use the canonical pipeline.
			log.warn("DEPRECATED: Cloud import using legacy path for provider '" + provider
					+ "'. Create connector and import profile definitions to use the canonical pipeline.");
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

			String mimeType = resolveLegacyCloudImportMimeType(finalFileName, contentPart);

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

			// §3: issued before the create/checkin mutation below.
			final String lineageOperationId = java.util.UUID.randomUUID().toString();
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
			// Prefer client-provided webUrl (from Graph API) over server-generated fallback URL
			// The server-side fallback (onedrive.live.com/edit?id=...) only works for personal OneDrive,
			// not for Microsoft 365/Entra ID org accounts
			CloudDriveService service = getCloudDriveService();
			String cloudFileUrl;
			if (clientCloudFileUrl != null && !clientCloudFileUrl.isEmpty()
					&& isAllowedCloudUrl(provider, clientCloudFileUrl)) {
				cloudFileUrl = clientCloudFileUrl;
			} else {
				if (clientCloudFileUrl != null && !clientCloudFileUrl.isEmpty()) {
					// Log host only - URL query may contain tokens or shared keys
					String rejectedHost = "(unparseable)";
					try {
						java.net.URI u = new java.net.URI(clientCloudFileUrl);
						rejectedHost = u.getScheme() + "://" + u.getHost();
					} catch (Exception ignored) {}
					log.warn("Rejected client-provided cloudFileUrl (not in allowed hosts for provider '"
						+ provider + "'): " + rejectedHost);
				}
				cloudFileUrl = (service != null) ? service.getCloudFileUrl(provider, cloudFileId) : null;
			}
			saveCloudMetadata(callContext, repositoryId, newObjectId, provider, cloudFileId, cloudFileUrl);

			// Fetch and save comments from cloud file (if access token provided)
			log.debug("Checking comments: accessToken=" + (accessToken != null ? "present" : "null") +
					", service=" + (service != null ? "present" : "null"));
			String commentsJson = null;
			if (accessToken != null && !accessToken.isEmpty() && service != null) {
				try {
					log.debug("Fetching comments from cloud file: " + cloudFileId);
					commentsJson = service.getCloudComments(provider, cloudFileId, accessToken);
					// Log presence and size only - never log content (PII risk)
					log.debug("Comments result: present=" + (commentsJson != null) + ", length=" + (commentsJson != null ? commentsJson.length() : 0));
				} catch (Exception e) {
					// Don't fail the import just because comments fetch failed
					log.warn("Failed to fetch/save cloud comments: " + e.getClass().getSimpleName());
					log.debug("Cloud comments fetch exception detail", e);
				}
			} else {
				log.debug("Skipping comments fetch: accessToken or service not available");
			}

			// Always save external context for cloud-imported files (both Google and OneDrive)
			// This ensures nemaki:externalIntegration secondary type is consistently applied
			if (commentsJson != null && !commentsJson.isEmpty()) {
				saveCloudComments(callContext, repositoryId, newObjectId, commentsJson);
				result.put("commentsImported", true);
				log.debug("Imported cloud comments to object " + newObjectId + " (length=" + commentsJson.length() + ")");
			} else {
				// No comments available, but still set externalIntegration with empty context
				saveExternalContext(callContext, repositoryId, newObjectId, "{}", "cloud_sync", provider);
				log.debug("No comments found, set empty externalIntegration for cloud file: " + cloudFileId);
			}

			result.put("objectId", newObjectId);
			result.put("name", finalFileName);
			result.put("cloudFileId", cloudFileId);
			result.put("cloudFileUrl", cloudFileUrl);
			result.put("provider", provider);
			result.put("isNewVersion", isNewVersion);

			// Lineage: one version-free fact (import created or versioned a document). The name
			// is read back from the object that actually exists — creation may have adjusted it —
			// and every lineage-only step runs inside the fail-open boundary.
			jp.aegif.nemaki.rest.purview.journal.LineageFactEmission.emitSafely(
					resolveLineageEmitter(repositoryId), () -> {
				String documentName = readDocumentNameQuietly(repositoryId, newObjectId);
				String occurredAt = java.time.Instant.now().toString();
				return new jp.aegif.nemaki.rest.purview.journal.LineageFact(
						repositoryId,
						LineageProcessType.CLOUD_SYNC_DOWNLOAD,
						lineageOperationId,
						occurredAt,
						java.util.List.of(jp.aegif.nemaki.rest.purview.journal.LineageEndpoint
								.cloudObject(repositoryId, provider, cloudFileId)),
						java.util.List.of(jp.aegif.nemaki.rest.purview.journal.LineageEndpoint
								.document(repositoryId, newObjectId, documentName)),
						lineageTargets(),
						null,
						new jp.aegif.nemaki.rest.purview.journal.LineageFact.LegacyV1Projection(
								LineageProcessType.CLOUD_SYNC_DOWNLOAD,
								java.util.List.of("cloud://" + provider + "/" + cloudFileId),
								java.util.List.of(LineageEvent.qualifiedName(repositoryId, newObjectId)),
								java.util.Map.of("provider", provider)));
			}, "repo=" + repositoryId + " op=" + lineageOperationId + " type=CLOUD_SYNC_DOWNLOAD(import)");

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

			// Prefer the stored cloudFileUrl (from Graph API webUrl during import/push)
			// over the dynamically generated fallback URL, which only works for personal OneDrive
			// Re-validate stored URL against allowed hosts (may contain legacy/tampered data)
			String storedCloudFileUrl = getSecondaryProperty(content, "nemaki:cloudFileUrl");
			String url;
			if (storedCloudFileUrl != null && !storedCloudFileUrl.isEmpty()
					&& isAllowedCloudUrl(provider, storedCloudFileUrl)) {
				url = storedCloudFileUrl;
			} else {
				CloudDriveService service = getCloudDriveService();
				if (service == null) {
					addErrMsg(errMsg, "service", "CloudDriveService not available");
					result = makeResult(false, result, errMsg);
					return result.toJSONString();
				}
				url = service.getCloudFileUrl(provider, cloudFileId);
			}
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
	/**
	 * Attempts to use the canonical import pipeline. Returns the JSON response string
	 * if successful, or {@code null} only when canonical import is not set up (no Spring bean /
	 * request preparation failure) or when auto-resolve reports no connector/profile (explicit
	 * {@link ExternalIngestResult} errors). Runtime failures from
	 * {@link CanonicalImportService#executeWithAutoResolve} return an error JSON — they must not
	 * fall through to the legacy path after partial canonical side effects.
	 */
	@SuppressWarnings("unchecked")
	private String tryCanonicalImport(
			org.apache.chemistry.opencmis.commons.server.CallContext callContext,
			String repositoryId, String folderId, String provider,
			String cloudFileId, String accessToken, String fileName,
			InputStream content, String clientCloudFileUrl,
			JSONObject result, JSONArray errMsg) {
		org.springframework.context.ApplicationContext ctx = SpringContext.getApplicationContext();
		if (ctx == null) {
			return null;
		}
		CanonicalImportService importService;
		try {
			importService = ctx.getBean(CanonicalImportService.class);
		} catch (Exception e) {
			log.debug("CanonicalImportService unavailable, falling back to legacy: " + e.getMessage());
			return null;
		}
		if (importService == null) {
			return null;
		}

		final ExternalIngestRequest req;
		final String resolvedCloudUrl;
		try {
			// Build request
			req = new ExternalIngestRequest();
			req.setRepositoryId(repositoryId);
			req.setTargetFolderOverride(folderId); // Preserve caller-selected folder
			req.setSourceObjectId(cloudFileId);
			req.setSourceObjectType("file");
			req.setFileName(fileName);
			req.setMimeType(inferMimeType(fileName));
			req.setContentStream(content);
			req.setExecutionMode("manual");

			// Pass cloud context as metadata — canonical pipeline applies cloudDriveMetadata
			java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
			metadata.put("cloudProvider", provider);
			metadata.put("cloudFileId", cloudFileId);
			// Resolve cloudFileUrl: prefer validated client-provided, fallback to server-generated
			String url = null;
			if (clientCloudFileUrl != null && !clientCloudFileUrl.isEmpty()
					&& isAllowedCloudUrl(provider, clientCloudFileUrl)) {
				url = clientCloudFileUrl;
			} else {
				CloudDriveService svc = getCloudDriveService();
				if (svc != null) {
					try { url = svc.getCloudFileUrl(provider, cloudFileId); }
					catch (Exception e) { /* best effort */ }
				}
			}
			resolvedCloudUrl = url;
			if (resolvedCloudUrl != null) {
				metadata.put("cloudFileUrl", resolvedCloudUrl);
			}
			// Fetch comments if access token available
			CloudDriveService service = getCloudDriveService();
			if (accessToken != null && !accessToken.isEmpty() && service != null) {
				try {
					String commentsJson = service.getCloudComments(provider, cloudFileId, accessToken);
					if (commentsJson != null && !commentsJson.isEmpty()) {
						metadata.put("comments", commentsJson);
					}
				} catch (Exception e) {
					log.debug("Failed to fetch cloud comments for canonical import: " + e.getMessage());
				}
			}
			if (!metadata.isEmpty()) {
				req.setMetadata(metadata);
			}
		} catch (Exception e) {
			log.debug("Canonical import request preparation failed, falling back to legacy: " + e.getMessage(), e);
			return null;
		}

		final ExternalIngestResult ingestResult;
		try {
			ingestResult = importService.executeWithAutoResolve(
					callContext, req, provider, SourceArchetype.FILE_SHARE);
		} catch (Exception e) {
			log.error("Canonical import failed unexpectedly (no legacy fallback): " + e.getMessage(), e);
			addErrMsg(errMsg, "import", "Canonical import failed: " + e.getMessage());
			result = makeResult(false, result, errMsg);
			return result.toJSONString();
		}

		log.info("Canonical import result: success=" + ingestResult.isSuccess()
				+ ", skipped=" + ingestResult.skipped() + ", objectId=" + ingestResult.objectId()
				+ ", errors=" + ingestResult.errors());

		if (!ingestResult.isSuccess() && !ingestResult.skipped()) {
			// Check if auto-resolve failed (no connector/profile) → fall back to legacy
			if (ingestResult.errors() != null && !ingestResult.errors().isEmpty()) {
				String firstError = ingestResult.errors().get(0);
				if (firstError.contains("Ambiguous auto-resolve")) {
					// Config error: multiple profiles match — fail closed, do NOT fall back
					for (String err : ingestResult.errors()) {
						addErrMsg(errMsg, "import", err);
					}
					result = makeResult(false, result, errMsg);
					return result.toJSONString();
				}
				if (firstError.contains("No enabled connector") || firstError.contains("No enabled import profile")) {
					log.debug("Canonical import not available (" + firstError + "), falling back to legacy path");
					return null; // Fall back to legacy
				}
			}
			// Real error from canonical import — do NOT fall back (side effects may exist)
			for (String err : ingestResult.errors()) {
				addErrMsg(errMsg, "import", err);
			}
			result = makeResult(false, result, errMsg);
			return result.toJSONString();
		}

		if (ingestResult.skipped()) {
			result.put("status", "skipped");
			result.put("skipped", true);
			result.put("skipReason", ingestResult.skipReason());
			if (ingestResult.objectId() != null) {
				result.put("existingObjectId", ingestResult.objectId());
			}
			return result.toJSONString();
		}

		// Canonical import succeeded — cloudDriveMetadata was applied by the pipeline
		String newObjectId = ingestResult.objectId();
		result.put("status", "success");
		result.put("objectId", newObjectId);
		result.put("name", fileName);
		result.put("cloudFileId", cloudFileId);
		result.put("cloudFileUrl", resolvedCloudUrl);
		result.put("provider", provider);
		result.put("isNewVersion", ingestResult.isNewVersion());
		result.put("canonicalImport", true);
		if (ingestResult.lineageEventId() != null) {
			result.put("lineageEventId", ingestResult.lineageEventId());
		}
		return result.toJSONString();
	}

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

	/**
	 * Remove cloud drive metadata from a CMIS object (unlink from cloud).
	 * Removes the nemaki:cloudDriveMetadata secondary type and its properties.
	 * SECURITY: Requires authenticated CallContext for proper permission checks.
	 */
	private void removeCloudMetadata(org.apache.chemistry.opencmis.commons.server.CallContext callContext,
			String repositoryId, String objectId) {
		if (callContext == null) {
			throw new IllegalArgumentException("CallContext is required for permission enforcement");
		}

		ContentService cs = getContentService();
		if (cs == null) return;

		Content content = cs.getContent(repositoryId, objectId);
		if (content == null) return;

		// Remove secondary type ID
		java.util.List<String> secondaryTypeIds = content.getSecondaryIds();
		if (secondaryTypeIds != null) {
			secondaryTypeIds.remove("nemaki:cloudDriveMetadata");
			content.setSecondaryIds(secondaryTypeIds);
		}

		// Remove the cloudDriveMetadata aspect
		java.util.List<jp.aegif.nemaki.model.Aspect> aspects = content.getAspects();
		if (aspects != null) {
			aspects.removeIf(a -> "nemaki:cloudDriveMetadata".equals(a.getName()));
			content.setAspects(aspects);
		}

		// Remove legacy subTypeProperties for cloud metadata
		java.util.List<jp.aegif.nemaki.model.Property> subTypeProps = content.getSubTypeProperties();
		if (subTypeProps != null) {
			java.util.Set<String> cloudPropKeys = java.util.Set.of(
				"nemaki:cloudProvider", "nemaki:cloudFileId",
				"nemaki:cloudFileUrl", "nemaki:cloudLastSyncedAt");
			subTypeProps.removeIf(p -> cloudPropKeys.contains(p.getKey()));
			content.setSubTypeProperties(subTypeProps);
		}

		cs.update(callContext, repositoryId, content);

		// Invalidate caches
		try {
			jp.aegif.nemaki.util.cache.NemakiCachePool cachePool =
				SpringContext.getApplicationContext().getBean("nemakiCachePool",
					jp.aegif.nemaki.util.cache.NemakiCachePool.class);
			cachePool.get(repositoryId).removeCmisAndContentCache(objectId);
		} catch (Exception e) {
			log.warn("Failed to invalidate cache for object " + objectId + ": " + e.getMessage());
		}
		log.info("Removed cloud metadata for object " + objectId);
	}

	/**
	 * Unlink a document from cloud drive (remove cloud metadata).
	 * The document remains in NemakiWare but is no longer associated with any cloud file.
	 *
	 * POST /rest/repo/{repositoryId}/cloud-drive/unlink/{objectId}
	 */
	@SuppressWarnings("unchecked")
	@POST
	@Path("/unlink/{objectId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String unlinkCloud(
			@PathParam("repositoryId") String repositoryId,
			@PathParam("objectId") String objectId,
			@Context HttpServletRequest request) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// SECURITY: CSRF protection
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			result = makeResult(false, result, errMsg);
			return result.toJSONString();
		}

		try {
			org.apache.chemistry.opencmis.commons.server.CallContext callContext =
				(org.apache.chemistry.opencmis.commons.server.CallContext) request.getAttribute("CallContext");
			if (callContext == null) {
				addErrMsg(errMsg, "authentication", "User authentication required");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			removeCloudMetadata(callContext, repositoryId, objectId);
			result.put("unlinked", true);

		} catch (Exception e) {
			log.error("Error unlinking from cloud: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "unlink", "Failed to unlink from cloud: " + e.getMessage());
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}
}
