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
	 * Fetch comments from Google Drive using the Comments API.
	 * Returns a JSON array of comments with author, content, and timestamp.
	 */
	private String getGoogleDriveComments(String cloudFileId, String accessToken) {
		try {
			Drive driveService = buildGoogleDriveService(accessToken);

			// Fetch all comments for the file
			com.google.api.services.drive.model.CommentList commentList = driveService.comments()
				.list(cloudFileId)
				.setFields("comments(id,author(displayName,emailAddress),content,createdTime,modifiedTime,resolved,replies(id,author(displayName,emailAddress),content,createdTime,modifiedTime))")
				.execute();

			if (commentList.getComments() == null || commentList.getComments().isEmpty()) {
				return null;
			}

			// Build JSON array of comments
			org.json.simple.JSONArray commentsArray = new org.json.simple.JSONArray();
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

			log.info("Fetched " + commentsArray.size() + " comments from Google Drive file: " + cloudFileId);
			return commentsArray.toJSONString();

		} catch (Exception e) {
			log.error("Failed to fetch Google Drive comments: " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Fetch comments from OneDrive using the Microsoft Graph API.
	 * Note: Microsoft Graph API comments endpoint is available for specific file types.
	 */
	@SuppressWarnings("unchecked")
	private String getOneDriveComments(String cloudFileId, String accessToken) {
		try {
			java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

			// Microsoft Graph API endpoint for file comments
			// Note: Comments API is available for specific file types like Word, Excel, PowerPoint
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("https://graph.microsoft.com/v1.0/me/drive/items/" + cloudFileId + "/workbook/comments"))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();

			java.net.http.HttpResponse<String> response = httpClient.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofString());

			// If the file doesn't support comments (not a workbook), try the generic endpoint
			if (response.statusCode() == 400 || response.statusCode() == 404) {
				// Comments not supported for this file type
				log.info("OneDrive comments not available for file: " + cloudFileId + " (status: " + response.statusCode() + ")");
				return null;
			}

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				org.json.simple.JSONObject json = (org.json.simple.JSONObject)
					new org.json.simple.parser.JSONParser().parse(response.body());

				org.json.simple.JSONArray valueArray = (org.json.simple.JSONArray) json.get("value");
				if (valueArray == null || valueArray.isEmpty()) {
					return null;
				}

				// Transform to standard comment format
				org.json.simple.JSONArray commentsArray = new org.json.simple.JSONArray();
				for (Object item : valueArray) {
					org.json.simple.JSONObject msComment = (org.json.simple.JSONObject) item;
					org.json.simple.JSONObject commentObj = new org.json.simple.JSONObject();

					commentObj.put("id", msComment.get("id"));
					commentObj.put("content", msComment.get("content"));
					commentObj.put("createdTime", msComment.get("createdDateTime"));

					org.json.simple.JSONObject authorInfo = (org.json.simple.JSONObject) msComment.get("authorEmail");
					if (authorInfo != null) {
						org.json.simple.JSONObject authorObj = new org.json.simple.JSONObject();
						authorObj.put("email", authorInfo);
						commentObj.put("author", authorObj);
					}

					commentsArray.add(commentObj);
				}

				log.info("Fetched " + commentsArray.size() + " comments from OneDrive file: " + cloudFileId);
				return commentsArray.toJSONString();
			} else {
				log.warn("OneDrive comments request failed: HTTP " + response.statusCode());
				return null;
			}

		} catch (Exception e) {
			log.error("Failed to fetch OneDrive comments: " + e.getMessage(), e);
			return null;
		}
	}
}
