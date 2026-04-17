package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Persistent record of a scheduled ingest job execution.
 * Stored in CouchDB nemaki_conf as type "ingest_job_record".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IngestJobRecord {

    public static final String DOC_TYPE = "ingest_job_record";

    public enum Status { RUNNING, COMPLETED, FAILED, PARTIAL, STUCK }

    /** Jobs with no heartbeat for this long are considered stuck. */
    public static final long STUCK_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private String jobId;
    private String profileId;
    private String connectorId;
    private String repositoryId;
    private String startedAt;
    private String completedAt;
    private String lastHeartbeatAt;
    private Status status;
    private int fetched;
    private int imported;
    private int skipped;
    private int failed;
    private List<String> errors;

    public IngestJobRecord() {}

    // --- Getters / Setters ---

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }

    public String getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(String lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getFetched() { return fetched; }
    public void setFetched(int fetched) { this.fetched = fetched; }

    public int getImported() { return imported; }
    public void setImported(int imported) { this.imported = imported; }

    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }

    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }

    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
}
