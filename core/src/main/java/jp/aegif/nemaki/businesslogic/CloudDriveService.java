package jp.aegif.nemaki.businesslogic;

import java.io.InputStream;

/**
 * Service interface for cloud drive integration (Google Drive / OneDrive).
 *
 * Provides push/pull operations for synchronizing document content
 * between NemakiWare and cloud storage providers.
 */
public interface CloudDriveService {

	/**
	 * Push a document's content to a cloud drive provider.
	 *
	 * @param repositoryId Repository ID
	 * @param objectId CMIS object ID of the document (typically a PWC)
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @param accessToken OAuth2 access token for the cloud provider API
	 * @return Cloud file ID assigned by the provider
	 */
	String pushToCloud(String repositoryId, String objectId, String provider, String accessToken);

	/**
	 * Pull a document's content from a cloud drive provider.
	 *
	 * @param repositoryId Repository ID
	 * @param objectId CMIS object ID of the document
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @param accessToken OAuth2 access token for the cloud provider API
	 * @return InputStream of the file content from the cloud
	 */
	InputStream pullFromCloud(String repositoryId, String objectId, String provider, String accessToken);

	/**
	 * Get the URL to open a cloud file in the provider's web editor.
	 *
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @param cloudFileId File ID in the cloud provider
	 * @return URL string to open the file, or null if not available
	 */
	String getCloudFileUrl(String provider, String cloudFileId);

	/**
	 * Delete a file from the cloud drive.
	 *
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @param cloudFileId File ID in the cloud provider
	 * @param accessToken OAuth2 access token
	 */
	void deleteFromCloud(String provider, String cloudFileId, String accessToken);

	/**
	 * Pull file content from cloud storage using the cloud file ID directly.
	 *
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @param cloudFileId The file ID in the cloud provider
	 * @param accessToken OAuth access token for the cloud provider
	 * @return InputStream of the file content
	 */
	InputStream pullFromCloudByFileId(String provider, String cloudFileId, String accessToken);
}
