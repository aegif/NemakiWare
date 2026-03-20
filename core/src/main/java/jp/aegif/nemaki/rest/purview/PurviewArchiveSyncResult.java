package jp.aegif.nemaki.rest.purview;

public class PurviewArchiveSyncResult {

    private final String snapshot;
    private final boolean changed;
    private final int publishedCount;
    private final int reconciledCount;

    public PurviewArchiveSyncResult(
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
