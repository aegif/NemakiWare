package jp.aegif.nemaki.rest.purview;

public class PurviewEntityPublishResult {

    private final boolean success;
    private final int publishedCount;
    private final String message;

    private PurviewEntityPublishResult(boolean success, int publishedCount, String message) {
        this.success = success;
        this.publishedCount = publishedCount;
        this.message = message;
    }

    public static PurviewEntityPublishResult success(int publishedCount, String message) {
        return new PurviewEntityPublishResult(true, publishedCount, message);
    }

    public static PurviewEntityPublishResult failure(String message) {
        return new PurviewEntityPublishResult(false, 0, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public int getPublishedCount() {
        return publishedCount;
    }

    public String getMessage() {
        return message;
    }
}
