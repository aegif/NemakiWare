package jp.aegif.nemaki.rest.purview.state;

public class PurviewDeadLetterState {

    private final String repositoryId;
    private final String streamKind;
    private final String entryKey;
    private final String typeName;
    private final String qualifiedName;
    private final String firstFailedAt;
    private final String lastFailedAt;
    private final int failureCount;
    private final String checkpoint;
    private final String errorSummary;

    public PurviewDeadLetterState(
            String repositoryId,
            String streamKind,
            String entryKey,
            String typeName,
            String qualifiedName,
            String firstFailedAt,
            String lastFailedAt,
            int failureCount,
            String checkpoint,
            String errorSummary) {
        this.repositoryId = repositoryId;
        this.streamKind = streamKind;
        this.entryKey = entryKey;
        this.typeName = typeName;
        this.qualifiedName = qualifiedName;
        this.firstFailedAt = firstFailedAt;
        this.lastFailedAt = lastFailedAt;
        this.failureCount = failureCount;
        this.checkpoint = checkpoint;
        this.errorSummary = errorSummary;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public String getStreamKind() {
        return streamKind;
    }

    public String getEntryKey() {
        return entryKey;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public String getFirstFailedAt() {
        return firstFailedAt;
    }

    public String getLastFailedAt() {
        return lastFailedAt;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public String getCheckpoint() {
        return checkpoint;
    }

    public String getErrorSummary() {
        return errorSummary;
    }
}
