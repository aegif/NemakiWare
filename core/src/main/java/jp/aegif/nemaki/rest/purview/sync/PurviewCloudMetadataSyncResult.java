package jp.aegif.nemaki.rest.purview.sync;

public class PurviewCloudMetadataSyncResult {

    private final String snapshot;
    private final boolean changed;
    private final int publishedCount;
    private final int reconciledCount;

    /**
     * Whether the walk behind this result could not read every child row.
     *
     * <p>An incomplete walk publishes what it saw and reconciles nothing — a partial success.
     * The dead-letter retry used to read that partial success as SUCCESS and delete the dead
     * letter, erasing the only durable signal that a retry was still owed.
     */
    private final boolean walkIncomplete;

    public PurviewCloudMetadataSyncResult(String snapshot, boolean changed, int publishedCount,
            int reconciledCount, boolean walkIncomplete) {
        this.snapshot = snapshot;
        this.changed = changed;
        this.publishedCount = publishedCount;
        this.reconciledCount = reconciledCount;
        this.walkIncomplete = walkIncomplete;
    }

    public boolean isWalkIncomplete() {
        return walkIncomplete;
    }

    public PurviewCloudMetadataSyncResult(
            String snapshot,
            boolean changed,
            int publishedCount,
            int reconciledCount) {
        this.snapshot = snapshot;
        this.changed = changed;
        this.publishedCount = publishedCount;
        this.reconciledCount = reconciledCount;
        this.walkIncomplete = false;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public boolean isChanged() {
        return changed;
    }

    public int getPublishedCount() {
        return publishedCount;
    }

    public int getReconciledCount() {
        return reconciledCount;
    }

    public int getProcessedCount() {
        return publishedCount + reconciledCount;
    }
}
