package jp.aegif.nemaki.rest.purview.sync;

public class PurviewTypeDefinitionSyncResult {

    private final String snapshot;
    private final boolean changed;
    private final int publishedCount;
    private final int deletedCount;

    public PurviewTypeDefinitionSyncResult(
            String snapshot,
            boolean changed,
            int publishedCount,
            int deletedCount) {
        this.snapshot = snapshot;
        this.changed = changed;
        this.publishedCount = publishedCount;
        this.deletedCount = deletedCount;
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

    public int getDeletedCount() {
        return deletedCount;
    }

    public int getProcessedCount() {
        return publishedCount + deletedCount;
    }
}
