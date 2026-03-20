package jp.aegif.nemaki.rest.purview;

public class PurviewJobState {

    private final String jobId;
    private final String jobKind;
    private final String repositoryId;
    private final String status;
    private final String startedAt;
    private final String endedAt;
    private final int processedCount;
    private final int failedCount;
    private final String checkpoint;
    private final String errorSummary;

    public PurviewJobState(
            String jobId,
            String jobKind,
            String repositoryId,
            String status,
            String startedAt,
            String endedAt,
            int processedCount,
            int failedCount,
            String checkpoint,
            String errorSummary) {
        this.jobId = jobId;
        this.jobKind = jobKind;
        this.repositoryId = repositoryId;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.processedCount = processedCount;
        this.failedCount = failedCount;
        this.checkpoint = checkpoint;
        this.errorSummary = errorSummary;
    }

    public String getJobId() {
        return jobId;
    }

    public String getJobKind() {
        return jobKind;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public String getStatus() {
        return status;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String getEndedAt() {
        return endedAt;
    }

    public int getProcessedCount() {
        return processedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public String getCheckpoint() {
        return checkpoint;
    }

    public String getErrorSummary() {
        return errorSummary;
    }
}
