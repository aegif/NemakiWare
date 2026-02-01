package jp.aegif.nemaki.sync.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tracks the progress and result of a cloud directory sync operation.
 * Used for both delta sync and full reconciliation.
 */
public class CloudSyncResult {

	public enum Status {
		IDLE, RUNNING, COMPLETED, ERROR, CANCELLED
	}

	public enum SyncMode {
		DELTA, FULL
	}

	private final String syncId;
	private volatile Status status;
	private SyncMode syncMode;
	private String provider;
	private String repositoryId;
	private String startTime;
	private volatile String endTime;

	// Progress counters
	private volatile int usersCreated;
	private volatile int usersUpdated;
	private volatile int usersDeleted;
	private volatile int usersSkipped;
	private volatile int groupsCreated;
	private volatile int groupsUpdated;
	private volatile int groupsDeleted;
	private volatile int groupsSkipped;

	// Paging progress
	private volatile int currentPage;
	private volatile int totalPages;

	// Errors and warnings
	private final List<String> errors = new ArrayList<>();
	private final List<String> warnings = new ArrayList<>();

	public CloudSyncResult() {
		this.syncId = UUID.randomUUID().toString();
		this.status = Status.IDLE;
	}

	public CloudSyncResult(String repositoryId, String provider, SyncMode syncMode) {
		this.syncId = UUID.randomUUID().toString();
		this.repositoryId = repositoryId;
		this.provider = provider;
		this.syncMode = syncMode;
		this.status = Status.IDLE;
	}

	public String getSyncId() {
		return syncId;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public SyncMode getSyncMode() {
		return syncMode;
	}

	public void setSyncMode(SyncMode syncMode) {
		this.syncMode = syncMode;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getRepositoryId() {
		return repositoryId;
	}

	public void setRepositoryId(String repositoryId) {
		this.repositoryId = repositoryId;
	}

	public String getStartTime() {
		return startTime;
	}

	public void setStartTime(String startTime) {
		this.startTime = startTime;
	}

	public String getEndTime() {
		return endTime;
	}

	public void setEndTime(String endTime) {
		this.endTime = endTime;
	}

	public int getUsersCreated() {
		return usersCreated;
	}

	public void incrementUsersCreated() {
		this.usersCreated++;
	}

	public int getUsersUpdated() {
		return usersUpdated;
	}

	public void incrementUsersUpdated() {
		this.usersUpdated++;
	}

	public int getUsersDeleted() {
		return usersDeleted;
	}

	public void incrementUsersDeleted() {
		this.usersDeleted++;
	}

	public int getUsersSkipped() {
		return usersSkipped;
	}

	public void incrementUsersSkipped() {
		this.usersSkipped++;
	}

	public int getGroupsCreated() {
		return groupsCreated;
	}

	public void incrementGroupsCreated() {
		this.groupsCreated++;
	}

	public int getGroupsUpdated() {
		return groupsUpdated;
	}

	public void incrementGroupsUpdated() {
		this.groupsUpdated++;
	}

	public int getGroupsDeleted() {
		return groupsDeleted;
	}

	public void incrementGroupsDeleted() {
		this.groupsDeleted++;
	}

	public int getGroupsSkipped() {
		return groupsSkipped;
	}

	public void incrementGroupsSkipped() {
		this.groupsSkipped++;
	}

	public int getCurrentPage() {
		return currentPage;
	}

	public void setCurrentPage(int currentPage) {
		this.currentPage = currentPage;
	}

	public int getTotalPages() {
		return totalPages;
	}

	public void setTotalPages(int totalPages) {
		this.totalPages = totalPages;
	}

	public List<String> getErrors() {
		return new ArrayList<>(errors);
	}

	public void addError(String error) {
		this.errors.add(error);
	}

	public List<String> getWarnings() {
		return new ArrayList<>(warnings);
	}

	public void addWarning(String warning) {
		this.warnings.add(warning);
	}
}
