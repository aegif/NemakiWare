package jp.aegif.nemaki.archive;

import java.util.GregorianCalendar;

/**
 * Domain model for retention migration job execution logs.
 * Records the result of each scheduled cold-move job execution.
 */
public class RetentionMigrationLog {

    private String id;
    private String jobType;       // "cold-move"
    private String repositoryId;
    private GregorianCalendar startedAt;
    private GregorianCalendar completedAt;
    private int processed;
    private int succeeded;
    private int failed;
    private String status;        // "SUCCESS", "PARTIAL_FAILURE", "FAILURE"
    private String details;

    public RetentionMigrationLog() {
    }

    public RetentionMigrationLog(String jobType, String repositoryId) {
        this.jobType = jobType;
        this.repositoryId = repositoryId;
    }

    public String computeStatus() {
        if (failed == 0 && processed > 0) {
            return "SUCCESS";
        } else if (failed > 0 && succeeded > 0) {
            return "PARTIAL_FAILURE";
        } else if (failed > 0 && succeeded == 0) {
            return "FAILURE";
        } else {
            // processed == 0
            return "SUCCESS";
        }
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

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

    public GregorianCalendar getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(GregorianCalendar startedAt) {
        this.startedAt = startedAt;
    }

    public GregorianCalendar getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(GregorianCalendar completedAt) {
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
