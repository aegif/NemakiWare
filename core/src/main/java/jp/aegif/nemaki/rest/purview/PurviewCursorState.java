package jp.aegif.nemaki.rest.purview;

public class PurviewCursorState {

    private final String repositoryId;
    private final String streamKind;
    private final String cursor;
    private final String cursorKind;
    private final String lastRunAt;
    private final String lastSuccessAt;
    private final String lastErrorAt;
    private final String lastErrorMessage;
    private final int consecutiveFailureCount;
    private final int deadLetterCount;

    public PurviewCursorState(
            String repositoryId,
            String streamKind,
            String cursor,
            String cursorKind,
            String lastRunAt,
            String lastSuccessAt,
            String lastErrorAt,
            String lastErrorMessage,
            int consecutiveFailureCount,
            int deadLetterCount) {
        this.repositoryId = repositoryId;
        this.streamKind = streamKind;
        this.cursor = cursor;
        this.cursorKind = cursorKind;
        this.lastRunAt = lastRunAt;
        this.lastSuccessAt = lastSuccessAt;
        this.lastErrorAt = lastErrorAt;
        this.lastErrorMessage = lastErrorMessage;
        this.consecutiveFailureCount = consecutiveFailureCount;
        this.deadLetterCount = deadLetterCount;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public String getStreamKind() {
        return streamKind;
    }

    public String getCursor() {
        return cursor;
    }

    public String getCursorKind() {
        return cursorKind;
    }

    public String getLastRunAt() {
        return lastRunAt;
    }

    public String getLastSuccessAt() {
        return lastSuccessAt;
    }

    public String getLastErrorAt() {
        return lastErrorAt;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public int getConsecutiveFailureCount() {
        return consecutiveFailureCount;
    }

    public int getDeadLetterCount() {
        return deadLetterCount;
    }
}
