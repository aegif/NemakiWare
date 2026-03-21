package jp.aegif.nemaki.rest.purview.sync;

public class PurviewCloudMetadataSyncResult {

    private final String snapshot;
    private final boolean changed;
    private final int publishedCount;
    private final int reconciledCount;

    public PurviewCloudMetadataSyncResult(
            String snapshot,
            boolean changed,
            int publishedCount,
            int reconciledCount) {
        this.snapshot = snapshot;
        this.changed = changed;
        this.publishedCount = publishedCount;
        this.reconciledCount = reconciledCount;
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
