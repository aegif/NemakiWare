package jp.aegif.nemaki.businesslogic.impl;

import jp.aegif.nemaki.businesslogic.CloudDriveService;
import jp.aegif.nemaki.cmis.service.ObjectService;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Date;

/**
 * Cloud drive integration implementation supporting Google Drive and OneDrive.
 */
public class CloudDriveServiceImpl implements CloudDriveService {

	private static final Log log = LogFactory.getLog(CloudDriveServiceImpl.class);

	private ObjectService objectService;

	public void setObjectService(ObjectService objectService) {
		this.objectService = objectService;
	}

	@Override
	public String pushToCloud(String repositoryId, String objectId, String provider, String accessToken) {
		log.info("pushToCloud: provider=" + provider + ", objectId=" + objectId);

		// Use SystemCallContext for authorized internal operation (not null)
		jp.aegif.nemaki.cmis.factory.SystemCallContext callContext =
			new jp.aegif.nemaki.cmis.factory.SystemCallContext(repositoryId);
		ContentStream contentStream = objectService.getContentStream(
			callContext, repositoryId, objectId, null, null, null);
		if (contentStream == null || contentStream.getStream() == null) {
			throw new RuntimeException("Document has no content stream: " + objectId);
		}

		switch (provider) {
			case "google":
				return pushToGoogleDrive(contentStream, accessToken);
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
				return "https://drive.google.com/file/d/" + cloudFileId + "/edit";
			case "microsoft":
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

	private String pushToGoogleDrive(ContentStream contentStream, String accessToken) {
		try {
			Drive driveService = buildGoogleDriveService(accessToken);

			File fileMetadata = new File();
			fileMetadata.setName(contentStream.getFileName());

			InputStreamContent mediaContent = new InputStreamContent(
				contentStream.getMimeType(), contentStream.getStream());

			File uploadedFile = driveService.files().create(fileMetadata, mediaContent)
				.setFields("id, webViewLink")
				.execute();

			log.info("Pushed to Google Drive: fileId=" + uploadedFile.getId());
			return uploadedFile.getId();

		} catch (Exception e) {
			throw new RuntimeException("Failed to push to Google Drive", e);
		}
	}

	private InputStream pullFromGoogleDrive(String cloudFileId, String accessToken) {
		try {
			Drive driveService = buildGoogleDriveService(accessToken);

			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			driveService.files().get(cloudFileId).executeMediaAndDownloadTo(outputStream);

			return new ByteArrayInputStream(outputStream.toByteArray());

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
			byte[] content = contentStream.getStream().readAllBytes();
			String fileName = contentStream.getFileName();

			java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("https://graph.microsoft.com/v1.0/me/drive/root:/" + fileName + ":/content"))
				.header("Authorization", "Bearer " + accessToken)
				.header("Content-Type", contentStream.getMimeType())
				.PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(content))
				.build();

			java.net.http.HttpResponse<String> response = httpClient.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				org.json.simple.JSONObject json = (org.json.simple.JSONObject)
					new org.json.simple.parser.JSONParser().parse(response.body());
				String fileId = (String) json.get("id");
				log.info("Pushed to OneDrive: fileId=" + fileId);
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
			java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("https://graph.microsoft.com/v1.0/me/drive/items/" + cloudFileId + "/content"))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();

			java.net.http.HttpResponse<byte[]> response = httpClient.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofByteArray());

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				return new ByteArrayInputStream(response.body());
			} else {
				throw new RuntimeException("OneDrive download failed: HTTP " + response.statusCode());
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
