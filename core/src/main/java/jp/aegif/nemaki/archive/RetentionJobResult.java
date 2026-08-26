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

    /**
     * Dispositions the evidence ledger would not record, so they did not happen.
     *
     * <p>Counted apart from {@code skipped} because they are not the same fact. A skip is
     * "there was nothing to do here"; a refusal is "there was something to do and this
     * deployment could not record it, so it was not done". Folded together, a run in which
     * NOTHING could be disposed of reports SUCCESS with a skip count, which is how an operator
     * finds out months later that retention has not been running.
     */
    private int refused;
    private final List<String> skippedDocumentIds = new ArrayList<>();

    public RetentionJobResult(String jobName, String repositoryId) {
        this.jobName = jobName;
        this.repositoryId = repositoryId;
    }

    public void incrementProcessed() { processed++; }
    public void incrementSucceeded() { succeeded++; }
    public void incrementFailed() { failed++; }
    public void incrementSkipped() { skipped++; }
    /**
     * Counts a refusal.
     *
     * <p>Does NOT also bump {@code skipped}: the caller already does that when moveToCold
     * returns false, so doing it here counted every refusal twice and the job log overstated
     * how many archives it had passed over.
     */
    public void incrementRefused() { refused++; }
    public int getRefused() { return refused; }

    public String getJobName() { return jobName; }
    public String getRepositoryId() { return repositoryId; }
    public int getProcessed() { return processed; }
    public int getSucceeded() { return succeeded; }
    public int getFailed() { return failed; }
    public int getSkipped() { return skipped; }
    public void addSkippedDocumentId(String id) { skippedDocumentIds.add(id); }
    public List<String> getSkippedDocumentIds() { return Collections.unmodifiableList(skippedDocumentIds); }

    /**
     * Includes {@code refused}, because this is the line an operator actually reads.
     *
     * <p>Without it a run that refused every disposition looks like a run that skipped them,
     * and "skipped" reads as "there was nothing to do". The counter existed and the summary
     * did not print it, which is the same as not having it for anyone reading a log.
     */
    @Override
    public String toString() {
        return String.format("RetentionJobResult[job=%s, repo=%s, processed=%d, succeeded=%d, "
                        + "failed=%d, skipped=%d, of which refused=%d]",
                jobName, repositoryId, processed, succeeded, failed, skipped, refused);
    }
}
