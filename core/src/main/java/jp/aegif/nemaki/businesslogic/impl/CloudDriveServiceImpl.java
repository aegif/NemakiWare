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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cloud drive integration implementation supporting Google Drive and OneDrive.
 */
public class CloudDriveServiceImpl implements CloudDriveService {

	private static final Log log = LogFactory.getLog(CloudDriveServiceImpl.class);

	/**
	 * SECURITY: Bounded thread pool for cloud download operations.
	 * Prevents DoS via unbounded thread creation from many concurrent pull requests.
	 * Max 4 concurrent downloads to balance throughput and resource usage.
	 */
	private static final ExecutorService downloadExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
		private final AtomicInteger counter = new AtomicInteger(0);
		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "cloud-download-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});

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

		} catch (Exception e) {
			throw new RuntimeException("Failed to push to Google Drive", e);
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

			// SECURITY: Use bounded thread pool to prevent DoS via unbounded thread creation
			// The downloadExecutor limits concurrent downloads to prevent resource exhaustion
			downloadExecutor.submit(() -> {
				try {
					if (isGoogleDocsFormat) {
						driveService.files().export(cloudFileId, exportMimeType).executeMediaAndDownloadTo(pipedOut);
						log.info("Exported Google Docs file " + cloudFileId + " as " + exportMimeType);
					} else {
						driveService.files().get(cloudFileId).executeMediaAndDownloadTo(pipedOut);
					}
					pipedOut.close();
				} catch (Exception e) {
					log.error("Error downloading from Google Drive: " + e.getMessage(), e);
					try { pipedOut.close(); } catch (Exception ignored) {}
				}
			});

			return pipedIn;

		} catch (Exception e) {
			throw new RuntimeException("Failed to pull from Google Drive: " + cloudFileId, e);
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

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
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
				throw new RuntimeException("OneDrive upload failed: HTTP " + response.statusCode() + " " + response.body());
			}

		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed to push to OneDrive", e);
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
}
