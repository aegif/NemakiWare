package jp.aegif.nemaki.sync.service;

/**
 * Persisted state for cloud directory sync.
 * Stores delta tokens and timestamps for incremental sync.
 */
public class CloudSyncState {

	private String repositoryId;
	private String provider;
	private String lastDeltaToken;       // MS Graph deltaLink
	private String lastSyncTimestamp;    // Google updatedMin (RFC3339)
	private String lastFullReconciliationTimestamp;

	public CloudSyncState() {
	}

	public CloudSyncState(String repositoryId, String provider) {
		this.repositoryId = repositoryId;
		this.provider = provider;
	}

	public String getRepositoryId() {
		return repositoryId;
	}

	public void setRepositoryId(String repositoryId) {
		this.repositoryId = repositoryId;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getLastDeltaToken() {
		return lastDeltaToken;
	}

	public void setLastDeltaToken(String lastDeltaToken) {
		this.lastDeltaToken = lastDeltaToken;
	}

	public String getLastSyncTimestamp() {
		return lastSyncTimestamp;
	}

	public void setLastSyncTimestamp(String lastSyncTimestamp) {
		this.lastSyncTimestamp = lastSyncTimestamp;
	}

	public String getLastFullReconciliationTimestamp() {
		return lastFullReconciliationTimestamp;
	}

	public void setLastFullReconciliationTimestamp(String lastFullReconciliationTimestamp) {
		this.lastFullReconciliationTimestamp = lastFullReconciliationTimestamp;
	}
}
