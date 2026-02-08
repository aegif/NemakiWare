package jp.aegif.nemaki.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of a retention job execution.
 */
public class RetentionJobResult {

    private final String jobName;
    private final String repositoryId;
    private int processed;
    private int succeeded;
    private int failed;
    private int skipped;
    private final List<String> skippedDocumentIds = new ArrayList<>();

    public RetentionJobResult(String jobName, String repositoryId) {
        this.jobName = jobName;
        this.repositoryId = repositoryId;
    }

    public void incrementProcessed() { processed++; }
    public void incrementSucceeded() { succeeded++; }
    public void incrementFailed() { failed++; }
    public void incrementSkipped() { skipped++; }

    public String getJobName() { return jobName; }
    public String getRepositoryId() { return repositoryId; }
    public int getProcessed() { return processed; }
    public int getSucceeded() { return succeeded; }
    public int getFailed() { return failed; }
    public int getSkipped() { return skipped; }
    public void addSkippedDocumentId(String id) { skippedDocumentIds.add(id); }
    public List<String> getSkippedDocumentIds() { return Collections.unmodifiableList(skippedDocumentIds); }

    @Override
    public String toString() {
        return String.format("RetentionJobResult[job=%s, repo=%s, processed=%d, succeeded=%d, failed=%d, skipped=%d]",
                jobName, repositoryId, processed, succeeded, failed, skipped);
    }
}
