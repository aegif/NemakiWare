package jp.aegif.nemaki.sync.service;

import java.util.List;
import java.util.Map;

/**
 * Service interface for cloud directory synchronization (Google Workspace / Microsoft Entra ID).
 *
 * Synchronizes users and groups from cloud identity providers into NemakiWare.
 * Supports login-time instant sync, scheduled delta sync, and admin-triggered full reconciliation.
 */
public interface CloudDirectorySyncService {

	/**
	 * Synchronize a single user's group memberships from cloud provider.
	 * Typically called at login time for instant sync.
	 *
	 * @param repositoryId Repository ID
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @param externalUserId User's external ID (Google: sub, Microsoft: oid)
	 * @param email User's email address
	 * @return List of group names the user belongs to
	 */
	List<String> syncUserGroups(String repositoryId, String provider, String externalUserId, String email);

	/**
	 * Start a delta sync (incremental changes only).
	 * Uses MS Graph delta tokens or Google updatedMin for efficiency.
	 * Runs asynchronously; poll getSyncStatus() for progress.
	 *
	 * @param repositoryId Repository ID
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @return Initial sync result with syncId for tracking
	 */
	CloudSyncResult startDeltaSync(String repositoryId, String provider);

	/**
	 * Start a full reconciliation sync.
	 * Fetches all users/groups and reconciles with NemakiWare.
	 * Runs asynchronously; poll getSyncStatus() for progress.
	 *
	 * @param repositoryId Repository ID
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @return Initial sync result with syncId for tracking
	 */
	CloudSyncResult startFullReconciliation(String repositoryId, String provider);

	/**
	 * Get the current sync status for a provider.
	 *
	 * @param repositoryId Repository ID
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @return Current sync result (or IDLE if no sync is running)
	 */
	CloudSyncResult getSyncStatus(String repositoryId, String provider);

	/**
	 * Cancel a running sync.
	 *
	 * @param repositoryId Repository ID
	 * @param provider Cloud provider ("google" or "microsoft")
	 */
	void cancelSync(String repositoryId, String provider);

	/**
	 * Test connectivity to the cloud directory provider.
	 *
	 * @param provider Cloud provider ("google" or "microsoft")
	 * @return true if connection is successful
	 */
	boolean testConnection(String provider);
}
