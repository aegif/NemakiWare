package jp.aegif.nemaki.model.couch;

import java.util.GregorianCalendar;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jp.aegif.nemaki.archive.RetentionMigrationLog;

/**
 * CouchDB model for RetentionMigrationLog.
 *
 * Document type: "retentionMigrationLog"
 * View: retentionMigrationLogs (by startedAt, descending)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CouchRetentionMigrationLog extends CouchNodeBase {

    private static final String TYPE = "retentionMigrationLog";

    @JsonProperty("jobType")
    private String jobType;

    @JsonProperty("repositoryId")
    private String repositoryId;

    @JsonProperty("startedAt")
    private Long startedAt;

    @JsonProperty("completedAt")
    private Long completedAt;

    @JsonProperty("processed")
    private int processed;

    @JsonProperty("succeeded")
    private int succeeded;

    @JsonProperty("failed")
    private int failed;

    @JsonProperty("status")
    private String status;

    @JsonProperty("details")
    private String details;

    public CouchRetentionMigrationLog() {
        setType(TYPE);
    }

    @JsonCreator
    public CouchRetentionMigrationLog(Map<String, Object> properties) {
        super(properties);
        setType(TYPE);

        if (properties != null) {
            if (properties.containsKey("jobType")) {
                this.jobType = (String) properties.get("jobType");
            }
            if (properties.containsKey("repositoryId")) {
                this.repositoryId = (String) properties.get("repositoryId");
            }
            if (properties.containsKey("startedAt")) {
                Object val = properties.get("startedAt");
                if (val instanceof Number) {
                    this.startedAt = ((Number) val).longValue();
                }
            }
            if (properties.containsKey("completedAt")) {
                Object val = properties.get("completedAt");
                if (val instanceof Number) {
                    this.completedAt = ((Number) val).longValue();
                }
            }
            if (properties.containsKey("processed")) {
                Object val = properties.get("processed");
                if (val instanceof Number) {
                    this.processed = ((Number) val).intValue();
                }
            }
            if (properties.containsKey("succeeded")) {
                Object val = properties.get("succeeded");
                if (val instanceof Number) {
                    this.succeeded = ((Number) val).intValue();
                }
            }
            if (properties.containsKey("failed")) {
                Object val = properties.get("failed");
                if (val instanceof Number) {
                    this.failed = ((Number) val).intValue();
                }
            }
            if (properties.containsKey("status")) {
                this.status = (String) properties.get("status");
            }
            if (properties.containsKey("details")) {
                this.details = (String) properties.get("details");
            }
        }
    }

    public CouchRetentionMigrationLog(RetentionMigrationLog log) {
        setType(TYPE);
        if (log.getId() != null) {
            setId(log.getId());
        }
        this.jobType = log.getJobType();
        this.repositoryId = log.getRepositoryId();
        this.processed = log.getProcessed();
        this.succeeded = log.getSucceeded();
        this.failed = log.getFailed();
        this.status = log.getStatus();
        this.details = log.getDetails();

        if (log.getStartedAt() != null) {
            this.startedAt = log.getStartedAt().getTimeInMillis();
        }
        if (log.getCompletedAt() != null) {
            this.completedAt = log.getCompletedAt().getTimeInMillis();
        }
    }

    public RetentionMigrationLog convertToMigrationLog() {
        RetentionMigrationLog log = new RetentionMigrationLog();
        log.setId(getId());
        log.setJobType(jobType);
        log.setRepositoryId(repositoryId);
        log.setProcessed(processed);
        log.setSucceeded(succeeded);
        log.setFailed(failed);
        log.setStatus(status);
        log.setDetails(details);

        if (startedAt != null) {
            GregorianCalendar cal = new GregorianCalendar();
            cal.setTimeInMillis(startedAt);
            log.setStartedAt(cal);
        }
        if (completedAt != null) {
            GregorianCalendar cal = new GregorianCalendar();
            cal.setTimeInMillis(completedAt);
            log.setCompletedAt(cal);
        }

        return log;
    }

    // Getters and Setters

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Long completedAt) {
        this.completedAt = completedAt;
    }

    public int getProcessed() {
        return processed;
    }

    public void setProcessed(int processed) {
        this.processed = processed;
    }

    public int getSucceeded() {
        return succeeded;
    }

    public void setSucceeded(int succeeded) {
        this.succeeded = succeeded;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
