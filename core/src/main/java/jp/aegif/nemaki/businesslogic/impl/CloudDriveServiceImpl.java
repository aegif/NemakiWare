package jp.aegif.nemaki.businesslogic.impl;

import jp.aegif.nemaki.businesslogic.CloudDriveService;
import jp.aegif.nemaki.cmis.service.ObjectService;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.driveactivity.v2.DriveActivity;
import com.google.api.services.driveactivity.v2.model.ActionDetail;
import com.google.api.services.driveactivity.v2.model.Actor;
import com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest;
import com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.model.Document;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cloud drive integration implementation supporting Google Drive and OneDrive.
 */
public class CloudDriveServiceImpl implements CloudDriveService {

	private static final Log log = LogFactory.getLog(CloudDriveServiceImpl.class);

	/** Maximum concurrent downloads */
	private static final int MAX_DOWNLOAD_THREADS = 4;
	/** Maximum queued download tasks (prevents memory exhaustion from request floods) */
	private static final int MAX_DOWNLOAD_QUEUE = 8;
	/** Timeout for download operations (prevents thread starvation from blocked pipes) */
	private static final long DOWNLOAD_TIMEOUT_SECONDS = 300; // 5 minutes

	/**
	 * SECURITY: Bounded thread pool with bounded queue for cloud download operations.
	 * - Fixed pool size: 4 concurrent downloads max
	 * - Bounded queue: 8 pending tasks max (prevents memory exhaustion)
	 * - AbortPolicy: Rejects with RejectedExecutionException when queue is full
	 *   (caller should return HTTP 503 Service Unavailable)
	 */
	private static final ThreadPoolExecutor downloadExecutor = new ThreadPoolExecutor(
		MAX_DOWNLOAD_THREADS,               // core pool size
		MAX_DOWNLOAD_THREADS,               // max pool size (same as core for fixed pool behavior)
		60L, TimeUnit.SECONDS,              // keep-alive time for idle threads
		new ArrayBlockingQueue<>(MAX_DOWNLOAD_QUEUE),  // BOUNDED queue
		new ThreadFactory() {
			private final AtomicInteger counter = new AtomicInteger(0);
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "cloud-download-" + counter.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		},
		new ThreadPoolExecutor.AbortPolicy()  // Reject when queue is full
	);

	private ObjectService objectService;

	/** Cached webUrl from the last OneDrive push (used by getCloudFileUrl) */
	private final java.util.Map<String, String> oneDriveWebUrlCache = new java.util.concurrent.ConcurrentHashMap<>();

	public void setObjectService(ObjectService objectService) {
		this.objectService = objectService;
	}

	@Override
	public String pushToCloud(CallContext callContext, String repositoryId, String objectId, String provider, String accessToken) {
		return pushToCloud(callContext, repositoryId, objectId, provider, accessToken, null);
	}

	@Override
	public String pushToCloud(CallContext callContext, String repositoryId, String objectId, String provider, String accessToken, String existingCloudFileId) {
		// SECURITY: Require CallContext for ACL enforcement
		if (callContext == null) {
			throw new IllegalArgumentException("CallContext is required for ACL enforcement");
		}

		log.info("pushToCloud: provider=" + provider + ", objectId=" + objectId +
			", user=" + callContext.getUsername() + ", existingCloudFileId=" + existingCloudFileId);

		// Use the provided CallContext for proper ACL checks
		ContentStream contentStream = objectService.getContentStream(
			callContext, repositoryId, objectId, null, null, null);
		if (contentStream == null || contentStream.getStream() == null) {
			throw new RuntimeException("Document has no content stream: " + objectId);
		}

		switch (provider) {
			case "google":
				return pushToGoogleDrive(contentStream, accessToken, existingCloudFileId);
			case "microsoft":
				return pushToOneDrive(contentStream, accessToken);
			default:
				throw new IllegalArgumentException("Unknown cloud provider: " + provider);
		}
	}

	@Override
	public InputStream pullFromCloud(String repositoryId, String objectId, String provider, String accessToken) {
		log.info("pullFromCloud: provider=" + provider + ", objectId=" + objectId);
		throw new UnsupportedOperationException("pullFromCloud requires cloudFileId - use REST endpoint with objectId lookup");
	}

	/**
	 * Pull file content from cloud by cloud file ID.
	 */
	@Override
	public InputStream pullFromCloudByFileId(String provider, String cloudFileId, String accessToken) {
		switch (provider) {
			case "google":
				return pullFromGoogleDrive(cloudFileId, accessToken);
			case "microsoft":
				return pullFromOneDrive(cloudFileId, accessToken);
			default:
				throw new IllegalArgumentException("Unknown cloud provider: " + provider);
		}
	}

	@Override
	public String getCloudFileUrl(String provider, String cloudFileId) {
		if (cloudFileId == null || cloudFileId.isEmpty()) {
			return null;
		}
		switch (provider) {
			case "google":
				// Use docs.google.com/open which auto-redirects to the correct editor
				// (Docs, Sheets, Slides) based on the file's MIME type
				return "https://docs.google.com/open?id=" + cloudFileId;
			case "microsoft":
				// Use cached webUrl from Graph API (works for both personal and org accounts)
				String cachedUrl = oneDriveWebUrlCache.get(cloudFileId);
				if (cachedUrl != null) {
					return cachedUrl;
				}
				// Fallback for previously uploaded files
				return "https://onedrive.live.com/edit?id=" + cloudFileId;
			default:
				return null;
		}
	}

	@Override
	public void deleteFromCloud(String provider, String cloudFileId, String accessToken) {
		log.info("deleteFromCloud: provider=" + provider + ", cloudFileId=" + cloudFileId);
		try {
			switch (provider) {
				case "google":
					Drive driveService = buildGoogleDriveService(accessToken);
					driveService.files().delete(cloudFileId).execute();
					break;
				case "microsoft":
					deleteFromOneDrive(cloudFileId, accessToken);
					break;
				default:
					throw new IllegalArgumentException("Unknown cloud provider: " + provider);
			}
		} catch (Exception e) {
			log.error("Failed to delete cloud file: " + cloudFileId, e);
		}
	}

	// ---- Google Drive operations ----

	// Mapping from common MIME types to Google Workspace MIME types for conversion
	private static final java.util.Map<String, String> GOOGLE_DOCS_MIME_MAP = java.util.Map.of(
		"application/msword", "application/vnd.google-apps.document",
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.google-apps.document",
		"application/vnd.oasis.opendocument.text", "application/vnd.google-apps.document",
		"text/plain", "application/vnd.google-apps.document",
		"application/vnd.ms-excel", "application/vnd.google-apps.spreadsheet",
		"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.google-apps.spreadsheet",
		"application/vnd.oasis.opendocument.spreadsheet", "application/vnd.google-apps.spreadsheet",
		"application/vnd.ms-powerpoint", "application/vnd.google-apps.presentation",
		"application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.google-apps.presentation",
		"application/vnd.oasis.opendocument.presentation", "application/vnd.google-apps.presentation"
	);

	private String pushToGoogleDrive(ContentStream contentStream, String accessToken, String existingCloudFileId) {
		try {
			Drive driveService = buildGoogleDriveService(accessToken);

			String mimeType = contentStream.getMimeType();
			String googleMimeType = GOOGLE_DOCS_MIME_MAP.get(mimeType);

			InputStreamContent mediaContent = new InputStreamContent(
				contentStream.getMimeType(), contentStream.getStream());

			if (existingCloudFileId != null && !existingCloudFileId.isEmpty()) {
				// Update existing file
				File fileMetadata = new File();
				// Don't set name on update to preserve original name in Drive
				if (googleMimeType != null) {
					fileMetadata.setMimeType(googleMimeType);
				}

				File updatedFile = driveService.files().update(existingCloudFileId, fileMetadata, mediaContent)
					.setFields("id, webViewLink, mimeType")
					.execute();

				log.info("Updated Google Drive file: fileId=" + updatedFile.getId() + ", mimeType=" + updatedFile.getMimeType());
				return updatedFile.getId();

			} else {
				// Create new file
				File fileMetadata = new File();
				fileMetadata.setName(contentStream.getFileName());

				if (googleMimeType != null) {
					fileMetadata.setMimeType(googleMimeType);
					log.info("Converting to Google Docs format: " + mimeType + " -> " + googleMimeType);
				}

				File uploadedFile = driveService.files().create(fileMetadata, mediaContent)
					.setFields("id, webViewLink, mimeType")
					.execute();

				log.info("Pushed to Google Drive: fileId=" + uploadedFile.getId() + ", mimeType=" + uploadedFile.getMimeType());
				return uploadedFile.getId();
			}

		} catch (com.google.api.client.auth.oauth2.TokenResponseException e) {
			log.error("Google Drive authentication failed: token expired or invalid", e);
			throw new RuntimeException("Google Drive authentication failed. Please re-authenticate.", e);
		} catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
			int statusCode = e.getStatusCode();
			String message = e.getDetails() != null ? e.getDetails().getMessage() : e.getMessage();
			log.error("Google Drive API error (HTTP " + statusCode + "): " + message, e);
			if (statusCode == 404) {
				throw new RuntimeException("Cloud file not found. It may have been deleted.", e);
			} else if (statusCode == 403) {
				throw new RuntimeException("Access denied to Google Drive. Please check permissions.", e);
			} else if (statusCode == 429) {
				throw new RuntimeException("Google Drive rate limit exceeded. Please try again later.", e);
			}
			throw new RuntimeException("Google Drive API error: " + message, e);
		} catch (java.io.IOException e) {
			log.error("Network error while communicating with Google Drive", e);
			throw new RuntimeException("Network error while uploading to Google Drive. Please check your connection.", e);
		} catch (Exception e) {
			log.error("Unexpected error while pushing to Google Drive", e);
			throw new RuntimeException("Failed to push to Google Drive: " + e.getMessage(), e);
		}
	}

	private InputStream pullFromGoogleDrive(String cloudFileId, String accessToken) {
		try {
			Drive driveService = buildGoogleDriveService(accessToken);

			// First check if the file is a Google Docs format (requires export instead of download)
			File fileMeta = driveService.files().get(cloudFileId).setFields("mimeType").execute();
			String mimeType = fileMeta.getMimeType();

			// SECURITY: Use piped streams to avoid buffering entire file in memory
			// This prevents DoS attacks via large file downloads
			PipedInputStream pipedIn = new PipedInputStream(65536); // 64KB buffer
			PipedOutputStream pipedOut = new PipedOutputStream(pipedIn);

			final String exportMimeType;
			final boolean isGoogleDocsFormat = mimeType != null && mimeType.startsWith("application/vnd.google-apps.");

			if (isGoogleDocsFormat) {
				// Google Docs format: must use export
				switch (mimeType) {
					case "application/vnd.google-apps.document":
						exportMimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
						break;
					case "application/vnd.google-apps.spreadsheet":
						exportMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
						break;
					case "application/vnd.google-apps.presentation":
						exportMimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
						break;
					default:
						exportMimeType = "application/pdf";
						break;
				}
			} else {
				exportMimeType = null;
			}

			// SECURITY: Use bounded thread pool with rejection handling
			// AbortPolicy will throw RejectedExecutionException when queue is full
			Future<?> downloadFuture;
			try {
				downloadFuture = downloadExecutor.submit(() -> {
					try {
						if (isGoogleDocsFormat) {
							driveService.files().export(cloudFileId, exportMimeType).executeMediaAndDownloadTo(pipedOut);
							log.info("Exported Google Docs file " + cloudFileId + " as " + exportMimeType);
						} else {
							driveService.files().get(cloudFileId).executeMediaAndDownloadTo(pipedOut);
						}
					} catch (java.io.IOException e) {
						// Check if this is due to pipe being closed (client disconnected)
						if (e.getMessage() != null && e.getMessage().contains("Pipe")) {
							log.warn("Download cancelled - client disconnected: " + cloudFileId);
						} else {
							log.error("Error downloading from Google Drive: " + e.getMessage(), e);
						}
					} catch (Exception e) {
						log.error("Error downloading from Google Drive: " + e.getMessage(), e);
					} finally {
						try { pipedOut.close(); } catch (Exception ignored) {}
					}
				});
			} catch (RejectedExecutionException e) {
				// SECURITY: Queue is full - too many concurrent requests
				log.warn("Download queue full - rejecting request for file: " + cloudFileId);
				try { pipedOut.close(); } catch (Exception ignored) {}
				try { pipedIn.close(); } catch (Exception ignored) {}
				throw new RuntimeException("Service temporarily unavailable - too many concurrent downloads. Please try again later.");
			}

			// SECURITY: Return a wrapper InputStream that handles timeout and cancellation
			// This prevents thread starvation if client doesn't read the stream
			return new TimeoutPipedInputStream(pipedIn, downloadFuture, cloudFileId);

		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed to pull from Google Drive: " + cloudFileId, e);
		}
	}

	/**
	 * SECURITY: Wrapper InputStream that cancels the download task if the stream is closed
	 * without being fully read, and enforces read timeout to prevent thread starvation.
	 */
	private class TimeoutPipedInputStream extends InputStream {
		private final PipedInputStream delegate;
		private final Future<?> downloadFuture;
		private final String fileId;
		private volatile boolean closed = false;

		TimeoutPipedInputStream(PipedInputStream delegate, Future<?> downloadFuture, String fileId) {
			this.delegate = delegate;
			this.downloadFuture = downloadFuture;
			this.fileId = fileId;
		}

		@Override
		public int read() throws java.io.IOException {
			if (closed) return -1;
			return delegate.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws java.io.IOException {
			if (closed) return -1;
			return delegate.read(b, off, len);
		}

		@Override
		public int available() throws java.io.IOException {
			return delegate.available();
		}

		@Override
		public void close() throws java.io.IOException {
			if (closed) return;
			closed = true;

			// Cancel the download task if it's still running
			if (!downloadFuture.isDone()) {
				log.info("Cancelling download task for file: " + fileId);
				downloadFuture.cancel(true); // Interrupt the thread
			}

			try {
				delegate.close();
			} catch (java.io.IOException e) {
				// Ignore close errors on pipe
			}
		}
	}

	private Drive buildGoogleDriveService(String accessToken) {
		try {
			GoogleCredentials credentials = GoogleCredentials.create(
				new AccessToken(accessToken, new Date(System.currentTimeMillis() + 3600 * 1000)));

			return new Drive.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				new HttpCredentialsAdapter(credentials))
				.setApplicationName("NemakiWare")
				.build();
		} catch (Exception e) {
			throw new RuntimeException("Failed to build Google Drive service", e);
		}
	}

	/**
	 * Build a DriveActivity service for fetching activity history (approvals, etc.)
	 */
	private DriveActivity buildDriveActivityService(String accessToken) {
		try {
			GoogleCredentials credentials = GoogleCredentials.create(
				new AccessToken(accessToken, new Date(System.currentTimeMillis() + 3600 * 1000)));

			return new DriveActivity.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				new HttpCredentialsAdapter(credentials))
				.setApplicationName("NemakiWare")
				.build();
		} catch (Exception e) {
			throw new RuntimeException("Failed to build DriveActivity service", e);
		}
	}

	/**
	 * Build a Google Docs service for fetching document metadata including approvals.
	 */
	private Docs buildDocsService(String accessToken) {
		try {
			GoogleCredentials credentials = GoogleCredentials.create(
				new AccessToken(accessToken, new Date(System.currentTimeMillis() + 3600 * 1000)));

			return new Docs.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				new HttpCredentialsAdapter(credentials))
				.setApplicationName("NemakiWare")
				.build();
		} catch (Exception e) {
			throw new RuntimeException("Failed to build Google Docs service", e);
		}
	}

	/**
	 * Fetch Google Docs approvals using the Docs API.
	 * Note: This uses direct REST API calls since the Java client library may not expose approvals.
	 */
	@SuppressWarnings("unchecked")
	private org.json.simple.JSONArray getGoogleDocsApprovals(String cloudFileId, String accessToken) {
		try {
			log.info("[DocsApprovals] Fetching approvals for file: " + cloudFileId);

			// Try using the Docs API to get document metadata
			// Note: Approvals may not be directly exposed in the standard Docs API
			// We'll try to fetch what metadata is available
			Docs docsService = buildDocsService(accessToken);

			// First, try to get document revisions which might contain approval info
			// The Docs API doesn't directly expose "approvals" but we can check metadata
			Document doc = docsService.documents().get(cloudFileId).execute();

			// Avoid logging document title/metadata to prevent sensitive data leakage
			log.debug("[DocsApprovals] Document retrieved successfully");

			// Check for any suggestion changes which might relate to approvals
			if (doc.getSuggestedDocumentStyleChanges() != null) {
				log.debug("[DocsApprovals] Has suggested document style changes: " +
					doc.getSuggestedDocumentStyleChanges().size());
			}

			// Since the standard Docs API doesn't expose approvals directly,
			// let's try a direct REST API call to the approvals endpoint
			return fetchDocsApprovalsViaRest(cloudFileId, accessToken);

		} catch (Exception e) {
			log.warn("[DocsApprovals] Failed to fetch Google Docs approvals: " + e.getMessage());
			// Check if it's a "not a Google Docs" error (e.g., uploaded file not converted)
			if (e.getMessage() != null && e.getMessage().contains("Invalid document")) {
				log.info("[DocsApprovals] File is not a Google Docs document, skipping approvals");
			}
			return null;
		}
	}

	/**
	 * Fetch approvals via direct REST API call.
	 * Google Docs Approvals API endpoint: https://docs.googleapis.com/v1/documents/{documentId}/approvals
	 */
	@SuppressWarnings("unchecked")
	private org.json.simple.JSONArray fetchDocsApprovalsViaRest(String cloudFileId, String accessToken) {
		try {
			// Try the approvals endpoint (this may not exist in the public API)
			String approvalsUrl = "https://docs.googleapis.com/v1/documents/" + cloudFileId + "?fields=documentStyle";

			java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
				new java.net.URL(approvalsUrl).openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Authorization", "Bearer " + accessToken);
			conn.setConnectTimeout(30000);
			conn.setReadTimeout(30000);

			int responseCode = conn.getResponseCode();
			log.debug("[DocsApprovals] REST API response code: " + responseCode);

			if (responseCode == 200) {
				java.io.BufferedReader reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
				StringBuilder response = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					response.append(line);
				}
				reader.close();
				// Avoid logging response content to prevent sensitive data leakage
				log.debug("[DocsApprovals] REST API response received");
			}

			// Try Drive API to get file revisions which may contain approval information
			return fetchDriveRevisionsForApprovals(cloudFileId, accessToken);

		} catch (Exception e) {
			log.warn("[DocsApprovals] REST API call failed: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Fetch file revisions from Drive API to look for approval-related changes.
	 */
	@SuppressWarnings("unchecked")
	private org.json.simple.JSONArray fetchDriveRevisionsForApprovals(String cloudFileId, String accessToken) {
		try {
			Drive driveService = buildGoogleDriveService(accessToken);

			// Get file revisions
			com.google.api.services.drive.model.RevisionList revisions = driveService.revisions()
				.list(cloudFileId)
				.setFields("revisions(id,modifiedTime,lastModifyingUser(displayName,emailAddress),kind)")
				.execute();

			if (revisions.getRevisions() == null || revisions.getRevisions().isEmpty()) {
				log.info("[DocsApprovals] No revisions found");
				return null;
			}

			log.info("[DocsApprovals] Found " + revisions.getRevisions().size() + " revisions");

			org.json.simple.JSONArray approvalsArray = new org.json.simple.JSONArray();

			// Look for revisions that might represent approvals
			// (In practice, approvals are stored separately, but revisions give us history)
			for (com.google.api.services.drive.model.Revision rev : revisions.getRevisions()) {
				org.json.simple.JSONObject revObj = new org.json.simple.JSONObject();
				revObj.put("type", "revision");
				revObj.put("id", rev.getId());
				if (rev.getModifiedTime() != null) {
					revObj.put("timestamp", rev.getModifiedTime().toStringRfc3339());
				}
				if (rev.getLastModifyingUser() != null) {
					org.json.simple.JSONObject userObj = new org.json.simple.JSONObject();
					userObj.put("displayName", rev.getLastModifyingUser().getDisplayName());
					userObj.put("email", rev.getLastModifyingUser().getEmailAddress());
					revObj.put("actor", userObj);
				}
				approvalsArray.add(revObj);
			}

			return approvalsArray;

		} catch (Exception e) {
			log.warn("[DocsApprovals] Failed to fetch revisions: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Fetch activity history from Google Drive Activity API.
	 * Returns a JSON array of significant activities including:
	 * - Permission changes (share, unshare)
	 * - Label changes (approval labels if configured)
	 * - Settings changes
	 *
	 * Note: Google Docs Approvals are typically captured via the Comments API
	 * as resolved comments, which we handle separately.
	 */
	@SuppressWarnings("unchecked")
	private org.json.simple.JSONArray getGoogleDriveApprovalHistory(String cloudFileId, String accessToken) {
		try {
			log.info("[DriveActivity] Fetching activity for file: " + cloudFileId);
			DriveActivity activityService = buildDriveActivityService(accessToken);

			// Query activity for this specific file
			QueryDriveActivityRequest request = new QueryDriveActivityRequest()
				.setItemName("items/" + cloudFileId)
				.setPageSize(50);  // Get last 50 activities

			QueryDriveActivityResponse response = activityService.activity()
				.query(request)
				.execute();

			log.info("[DriveActivity] Response received, activities count: " +
				(response.getActivities() != null ? response.getActivities().size() : "null"));

			if (response.getActivities() == null || response.getActivities().isEmpty()) {
				log.info("[DriveActivity] No activities found for file: " + cloudFileId);
				return null;
			}

			org.json.simple.JSONArray activitiesArray = new org.json.simple.JSONArray();

			for (com.google.api.services.driveactivity.v2.model.DriveActivity activity : response.getActivities()) {
				// Get primary action
				ActionDetail primaryAction = activity.getPrimaryActionDetail();
				if (primaryAction == null) {
					log.debug("[DriveActivity] Activity has no primary action, skipping");
					continue;
				}

				// Log the activity type for debugging
				String detectedType = "unknown";
				if (primaryAction.getCreate() != null) detectedType = "create";
				else if (primaryAction.getEdit() != null) detectedType = "edit";
				else if (primaryAction.getMove() != null) detectedType = "move";
				else if (primaryAction.getRename() != null) detectedType = "rename";
				else if (primaryAction.getDelete() != null) detectedType = "delete";
				else if (primaryAction.getRestore() != null) detectedType = "restore";
				else if (primaryAction.getPermissionChange() != null) detectedType = "permission";
				else if (primaryAction.getComment() != null) detectedType = "comment";
				else if (primaryAction.getAppliedLabelChange() != null) detectedType = "label";
				else if (primaryAction.getSettingsChange() != null) detectedType = "settings";
				log.info("[DriveActivity] Activity type detected: " + detectedType + " at " + activity.getTimestamp());

				// Focus on permission and label changes (which may indicate approval workflows)
				String actionType = null;
				String actionDescription = null;

				if (primaryAction.getPermissionChange() != null) {
					actionType = "permission";
					var permChange = primaryAction.getPermissionChange();
					StringBuilder desc = new StringBuilder("Permission changed");
					if (permChange.getAddedPermissions() != null && !permChange.getAddedPermissions().isEmpty()) {
						desc.append(" - Added: ").append(permChange.getAddedPermissions().size());
					}
					if (permChange.getRemovedPermissions() != null && !permChange.getRemovedPermissions().isEmpty()) {
						desc.append(" - Removed: ").append(permChange.getRemovedPermissions().size());
					}
					actionDescription = desc.toString();
				} else if (primaryAction.getAppliedLabelChange() != null) {
					// Applied labels can be used for approval workflows
					actionType = "label";
					var labelChange = primaryAction.getAppliedLabelChange();
					StringBuilder desc = new StringBuilder("Label changed");
					if (labelChange.getChanges() != null) {
						desc.append(" (").append(labelChange.getChanges().size()).append(" changes)");
					}
					actionDescription = desc.toString();
				} else if (primaryAction.getSettingsChange() != null) {
					actionType = "settings";
					actionDescription = "Settings changed";
				} else if (primaryAction.getComment() != null) {
					// Skip comments here - we handle them via Comments API
					continue;
				} else if (primaryAction.getEdit() != null) {
					// Track edits for history
					actionType = "edit";
					actionDescription = "Document edited";
				} else {
					// Skip other activity types (create, move, rename, etc.)
					continue;
				}

				org.json.simple.JSONObject activityObj = new org.json.simple.JSONObject();
				activityObj.put("type", actionType);
				activityObj.put("description", actionDescription);

				// Add timestamp
				if (activity.getTimestamp() != null) {
					activityObj.put("timestamp", activity.getTimestamp());
				}

				// Add actor information
				if (activity.getActors() != null && !activity.getActors().isEmpty()) {
					Actor actor = activity.getActors().get(0);
					org.json.simple.JSONObject actorObj = new org.json.simple.JSONObject();

					if (actor.getUser() != null && actor.getUser().getKnownUser() != null) {
						actorObj.put("displayName", actor.getUser().getKnownUser().getPersonName());
						if (actor.getUser().getKnownUser().getIsCurrentUser() != null &&
							actor.getUser().getKnownUser().getIsCurrentUser()) {
							actorObj.put("isCurrentUser", true);
						}
					} else if (actor.getAdministrator() != null) {
						actorObj.put("displayName", "Administrator");
					} else if (actor.getSystem() != null) {
						actorObj.put("displayName", "System");
					}

					activityObj.put("actor", actorObj);
				}

				activitiesArray.add(activityObj);
			}

			if (activitiesArray.isEmpty()) {
				return null;
			}

			log.info("Fetched " + activitiesArray.size() + " activity records from Google Drive file: " + cloudFileId);
			return activitiesArray;

		} catch (Exception e) {
			// Activity API might not be enabled or the scope might be missing
			log.warn("Failed to fetch Google Drive activity history (API might not be enabled): " + e.getMessage());
			return null;
		}
	}

	// ---- OneDrive operations (via Microsoft Graph REST API) ----

	private String pushToOneDrive(ContentStream contentStream, String accessToken) {
		try {
			String fileName = contentStream.getFileName();

			// SECURITY: URL-encode the filename to prevent path traversal and injection attacks
			String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
				.replace("+", "%20"); // Space should be %20, not +

			java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("https://graph.microsoft.com/v1.0/me/drive/root:/" + encodedFileName + ":/content"))
				.header("Authorization", "Bearer " + accessToken)
				.header("Content-Type", contentStream.getMimeType())
				.PUT(java.net.http.HttpRequest.BodyPublishers.ofInputStream(contentStream::getStream))
				.build();

			java.net.http.HttpResponse<String> response = httpClient.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofString());

			int statusCode = response.statusCode();
			if (statusCode >= 200 && statusCode < 300) {
				org.json.simple.JSONObject json = (org.json.simple.JSONObject)
					new org.json.simple.parser.JSONParser().parse(response.body());
				String fileId = (String) json.get("id");
				String webUrl = (String) json.get("webUrl");
				if (webUrl != null && fileId != null) {
					oneDriveWebUrlCache.put(fileId, webUrl);
				}
				log.info("Pushed to OneDrive: fileId=" + fileId + ", webUrl=" + webUrl);
				return fileId;
			} else {
				log.error("OneDrive upload failed: HTTP " + statusCode + " - " + response.body());
				if (statusCode == 401) {
					throw new RuntimeException("OneDrive authentication failed. Please re-authenticate.");
				} else if (statusCode == 403) {
					throw new RuntimeException("Access denied to OneDrive. Please check permissions.");
				} else if (statusCode == 404) {
					throw new RuntimeException("OneDrive path not found.");
				} else if (statusCode == 429) {
					throw new RuntimeException("OneDrive rate limit exceeded. Please try again later.");
				} else if (statusCode == 507) {
					throw new RuntimeException("OneDrive storage quota exceeded.");
				}
				throw new RuntimeException("OneDrive upload failed (HTTP " + statusCode + ")");
			}

		} catch (RuntimeException e) {
			throw e;
		} catch (java.net.http.HttpTimeoutException e) {
			log.error("OneDrive upload timeout", e);
			throw new RuntimeException("OneDrive upload timed out. Please try again.", e);
		} catch (java.io.IOException e) {
			log.error("Network error while communicating with OneDrive", e);
			throw new RuntimeException("Network error while uploading to OneDrive. Please check your connection.", e);
		} catch (Exception e) {
			log.error("Unexpected error while pushing to OneDrive", e);
			throw new RuntimeException("Failed to push to OneDrive: " + e.getMessage(), e);
		}
	}

	private InputStream pullFromOneDrive(String cloudFileId, String accessToken) {
		try {
			// SECURITY: Use streaming download to avoid buffering entire file in memory
			// This prevents DoS attacks via large file downloads
			java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
				.followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
				.build();
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("https://graph.microsoft.com/v1.0/me/drive/items/" + cloudFileId + "/content"))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();

			// Use InputStream body handler for streaming (no memory buffering)
			java.net.http.HttpResponse<InputStream> response = httpClient.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofInputStream());

			log.info("OneDrive download response: HTTP " + response.statusCode());
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				return response.body();
			} else {
				// For error responses, we need to read the body for the error message
				try (InputStream errorStream = response.body()) {
					throw new RuntimeException("OneDrive download failed: HTTP " + response.statusCode());
				}
			}

		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed to pull from OneDrive: " + cloudFileId, e);
		}
	}

	private void deleteFromOneDrive(String cloudFileId, String accessToken) {
		try {
			java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("https://graph.microsoft.com/v1.0/me/drive/items/" + cloudFileId))
				.header("Authorization", "Bearer " + accessToken)
				.DELETE()
				.build();

			httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
		} catch (Exception e) {
			log.error("Failed to delete from OneDrive: " + cloudFileId, e);
		}
	}

	// ---- Comments API ----

	@Override
	public String getCloudComments(String provider, String cloudFileId, String accessToken) {
		if (cloudFileId == null || cloudFileId.isEmpty()) {
			return null;
		}

		try {
			switch (provider) {
				case "google":
					return getGoogleDriveComments(cloudFileId, accessToken);
				case "microsoft":
					return getOneDriveComments(cloudFileId, accessToken);
				default:
					log.warn("Unknown cloud provider for comments: " + provider);
					return null;
			}
		} catch (Exception e) {
			log.error("Failed to fetch cloud comments: " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Fetch comments and approval history from Google Drive.
	 * Returns a JSON object with "comments", "activities", and "approvals".
	 */
	@SuppressWarnings("unchecked")
	private String getGoogleDriveComments(String cloudFileId, String accessToken) {
		org.json.simple.JSONObject result = new org.json.simple.JSONObject();
		org.json.simple.JSONArray commentsArray = new org.json.simple.JSONArray();
		boolean hasContent = false;

		// Fetch comments from Comments API
		try {
			Drive driveService = buildGoogleDriveService(accessToken);

			com.google.api.services.drive.model.CommentList commentList = driveService.comments()
				.list(cloudFileId)
				.setFields("comments(id,author(displayName,emailAddress),content,createdTime,modifiedTime,resolved,replies(id,author(displayName,emailAddress),content,createdTime,modifiedTime))")
				.execute();

			if (commentList.getComments() != null && !commentList.getComments().isEmpty()) {
				for (com.google.api.services.drive.model.Comment comment : commentList.getComments()) {
					org.json.simple.JSONObject commentObj = new org.json.simple.JSONObject();
					commentObj.put("id", comment.getId());
					commentObj.put("content", comment.getContent());
					commentObj.put("createdTime", comment.getCreatedTime() != null ? comment.getCreatedTime().toStringRfc3339() : null);
					commentObj.put("modifiedTime", comment.getModifiedTime() != null ? comment.getModifiedTime().toStringRfc3339() : null);
					commentObj.put("resolved", comment.getResolved());

					if (comment.getAuthor() != null) {
						org.json.simple.JSONObject authorObj = new org.json.simple.JSONObject();
						authorObj.put("displayName", comment.getAuthor().getDisplayName());
						authorObj.put("email", comment.getAuthor().getEmailAddress());
						commentObj.put("author", authorObj);
					}

					// Add replies if present
					if (comment.getReplies() != null && !comment.getReplies().isEmpty()) {
						org.json.simple.JSONArray repliesArray = new org.json.simple.JSONArray();
						for (com.google.api.services.drive.model.Reply reply : comment.getReplies()) {
							org.json.simple.JSONObject replyObj = new org.json.simple.JSONObject();
							replyObj.put("id", reply.getId());
							replyObj.put("content", reply.getContent());
							replyObj.put("createdTime", reply.getCreatedTime() != null ? reply.getCreatedTime().toStringRfc3339() : null);
							if (reply.getAuthor() != null) {
								org.json.simple.JSONObject replyAuthorObj = new org.json.simple.JSONObject();
								replyAuthorObj.put("displayName", reply.getAuthor().getDisplayName());
								replyAuthorObj.put("email", reply.getAuthor().getEmailAddress());
								replyObj.put("author", replyAuthorObj);
							}
							repliesArray.add(replyObj);
						}
						commentObj.put("replies", repliesArray);
					}

					commentsArray.add(commentObj);
				}

				log.debug("Fetched " + commentsArray.size() + " comments from Google Drive file: " + cloudFileId);
				hasContent = true;
			}
		} catch (Exception e) {
			log.error("Failed to fetch Google Drive comments: " + e.getMessage(), e);
		}

		result.put("comments", commentsArray);

		// Fetch approval/activity history from Drive Activity API
		org.json.simple.JSONArray activitiesArray = getGoogleDriveApprovalHistory(cloudFileId, accessToken);
		if (activitiesArray != null && !activitiesArray.isEmpty()) {
			result.put("activities", activitiesArray);
			hasContent = true;
		} else {
			result.put("activities", new org.json.simple.JSONArray());
		}

		// Fetch Google Docs approvals (revisions and document metadata)
		org.json.simple.JSONArray approvalsArray = getGoogleDocsApprovals(cloudFileId, accessToken);
		if (approvalsArray != null && !approvalsArray.isEmpty()) {
			result.put("approvals", approvalsArray);
			hasContent = true;
		} else {
			result.put("approvals", new org.json.simple.JSONArray());
		}

		return hasContent ? result.toJSONString() : null;
	}

	/**
	 * Fetch comments and activities from OneDrive using the Microsoft Graph API.
	 * Returns a JSON object with "comments" and "activities".
	 */
	@SuppressWarnings("unchecked")
	private String getOneDriveComments(String cloudFileId, String accessToken) {
		org.json.simple.JSONObject result = new org.json.simple.JSONObject();
		org.json.simple.JSONArray commentsArray = new org.json.simple.JSONArray();
		boolean hasContent = false;

		try {
			java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

			// Try to fetch workbook comments (Excel files)
			try {
				java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
					.uri(java.net.URI.create("https://graph.microsoft.com/v1.0/me/drive/items/" + cloudFileId + "/workbook/comments"))
					.header("Authorization", "Bearer " + accessToken)
					.GET()
					.build();

				java.net.http.HttpResponse<String> response = httpClient.send(request,
					java.net.http.HttpResponse.BodyHandlers.ofString());

				if (response.statusCode() >= 200 && response.statusCode() < 300) {
					org.json.simple.JSONObject json = (org.json.simple.JSONObject)
						new org.json.simple.parser.JSONParser().parse(response.body());

					org.json.simple.JSONArray valueArray = (org.json.simple.JSONArray) json.get("value");
					if (valueArray != null && !valueArray.isEmpty()) {
						for (Object item : valueArray) {
							org.json.simple.JSONObject msComment = (org.json.simple.JSONObject) item;
							org.json.simple.JSONObject commentObj = new org.json.simple.JSONObject();

							commentObj.put("id", msComment.get("id"));
							commentObj.put("content", msComment.get("content"));
							commentObj.put("createdTime", msComment.get("createdDateTime"));

							Object authorInfo = msComment.get("authorEmail");
							if (authorInfo != null) {
								org.json.simple.JSONObject authorObj = new org.json.simple.JSONObject();
								authorObj.put("email", authorInfo.toString());
								commentObj.put("author", authorObj);
							}

							commentsArray.add(commentObj);
						}
						log.debug("Fetched " + commentsArray.size() + " comments from OneDrive file: " + cloudFileId);
						hasContent = true;
					}
				}
			} catch (Exception e) {
				log.debug("OneDrive workbook comments not available: " + e.getMessage());
			}

		} catch (Exception e) {
			log.error("Failed to fetch OneDrive comments: " + e.getMessage(), e);
		}

		result.put("comments", commentsArray);

		// Fetch activities from Microsoft Graph API
		org.json.simple.JSONArray activitiesArray = getOneDriveActivities(cloudFileId, accessToken);
		if (activitiesArray != null && !activitiesArray.isEmpty()) {
			result.put("activities", activitiesArray);
			hasContent = true;
		} else {
			result.put("activities", new org.json.simple.JSONArray());
		}

		return hasContent ? result.toJSONString() : null;
	}

	/**
	 * Fetch activity history from OneDrive using Microsoft Graph API.
	 * Returns activities like edits, shares, comments, etc.
	 */
	@SuppressWarnings("unchecked")
	private org.json.simple.JSONArray getOneDriveActivities(String cloudFileId, String accessToken) {
		try {
			log.info("[OneDriveActivity] Fetching activities for file: " + cloudFileId);
			java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

			// Microsoft Graph API endpoint for item activities
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("https://graph.microsoft.com/v1.0/me/drive/items/" + cloudFileId + "/activities"))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();

			java.net.http.HttpResponse<String> response = httpClient.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofString());

			log.info("[OneDriveActivity] Response status: " + response.statusCode());

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				org.json.simple.JSONObject json = (org.json.simple.JSONObject)
					new org.json.simple.parser.JSONParser().parse(response.body());

				org.json.simple.JSONArray valueArray = (org.json.simple.JSONArray) json.get("value");
				if (valueArray == null || valueArray.isEmpty()) {
					log.info("[OneDriveActivity] No activities found");
					return null;
				}

				log.info("[OneDriveActivity] Found " + valueArray.size() + " activities");

				org.json.simple.JSONArray activitiesArray = new org.json.simple.JSONArray();
				for (Object item : valueArray) {
					org.json.simple.JSONObject msActivity = (org.json.simple.JSONObject) item;
					org.json.simple.JSONObject activityObj = new org.json.simple.JSONObject();

					// Determine activity type from action object
					org.json.simple.JSONObject action = (org.json.simple.JSONObject) msActivity.get("action");
					if (action != null) {
						// Action types: comment, create, delete, edit, mention, move, rename, restore, share, version
						if (action.containsKey("comment")) {
							activityObj.put("type", "comment");
							activityObj.put("description", "Comment added");
						} else if (action.containsKey("edit")) {
							activityObj.put("type", "edit");
							activityObj.put("description", "Document edited");
						} else if (action.containsKey("share")) {
							activityObj.put("type", "share");
							activityObj.put("description", "Document shared");
						} else if (action.containsKey("version")) {
							activityObj.put("type", "version");
							activityObj.put("description", "New version created");
						} else if (action.containsKey("create")) {
							activityObj.put("type", "create");
							activityObj.put("description", "Document created");
						} else if (action.containsKey("rename")) {
							activityObj.put("type", "rename");
							activityObj.put("description", "Document renamed");
						} else if (action.containsKey("move")) {
							activityObj.put("type", "move");
							activityObj.put("description", "Document moved");
						} else {
							activityObj.put("type", "unknown");
							activityObj.put("description", "Activity: " + action.keySet());
						}
					}

					// Add timestamp
					org.json.simple.JSONObject times = (org.json.simple.JSONObject) msActivity.get("times");
					if (times != null && times.get("recordedDateTime") != null) {
						activityObj.put("timestamp", times.get("recordedDateTime"));
					}

					// Add actor information
					org.json.simple.JSONObject actor = (org.json.simple.JSONObject) msActivity.get("actor");
					if (actor != null) {
						org.json.simple.JSONObject user = (org.json.simple.JSONObject) actor.get("user");
						if (user != null) {
							org.json.simple.JSONObject actorObj = new org.json.simple.JSONObject();
							actorObj.put("displayName", user.get("displayName"));
							actorObj.put("email", user.get("email"));
							activityObj.put("actor", actorObj);
						}
					}

					activitiesArray.add(activityObj);
				}

				return activitiesArray;

			} else if (response.statusCode() == 404) {
				log.info("[OneDriveActivity] Activities endpoint not available for this file");
				return null;
			} else {
				log.warn("[OneDriveActivity] Request failed: HTTP " + response.statusCode() + " - " + response.body());
				return null;
			}

		} catch (Exception e) {
			log.warn("[OneDriveActivity] Failed to fetch activities: " + e.getMessage());
			return null;
		}
	}
}
