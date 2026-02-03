package jp.aegif.nemaki.businesslogic;

import java.io.InputStream;

import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Service interface for cloud drive integration (Google Drive / OneDrive).
 *
 * Provides push/pull operations for synchronizing document content
 * between NemakiWare and cloud storage providers.
 *
 * SECURITY: All methods that access CMIS objects require a CallContext
 * to ensure proper ACL enforcement. Never use SystemCallContext unless
 * performing system-level operations that are not user-initiated.
 */
public interface CloudDriveService {

	/**
	 * Push a document's content to a cloud drive provider.
	 *
	 * @param callContext CMIS call context for ACL checks (must not be null)
	 * @param repositoryId Repository ID
	 * @param objectId CMIS object ID of the document (typically a PWC)
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @param accessToken OAuth2 access token for the cloud provider API
	 * @return Cloud file ID assigned by the provider
	 */
	String pushToCloud(CallContext callContext, String repositoryId, String objectId, String provider, String accessToken);

	/**
	 * Push a document's content to a cloud drive provider, optionally updating an existing cloud file.
	 *
	 * @param callContext CMIS call context for ACL checks (must not be null)
	 * @param repositoryId Repository ID
	 * @param objectId CMIS object ID of the document (typically a PWC)
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @param accessToken OAuth2 access token for the cloud provider API
	 * @param existingCloudFileId Existing cloud file ID to update (null for new upload)
	 * @return Cloud file ID assigned by the provider
	 */
	String pushToCloud(CallContext callContext, String repositoryId, String objectId, String provider, String accessToken, String existingCloudFileId);

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
