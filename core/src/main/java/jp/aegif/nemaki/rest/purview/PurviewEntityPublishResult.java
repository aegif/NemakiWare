package jp.aegif.nemaki.rest.purview;

public class PurviewEntityPublishResult {

    private final boolean success;
    private final int publishedCount;
    private final String message;
    private final String resourceGuid;

    private PurviewEntityPublishResult(boolean success, int publishedCount, String message, String resourceGuid) {
        this.success = success;
        this.publishedCount = publishedCount;
        this.message = message;
        this.resourceGuid = resourceGuid;
    }

    public static PurviewEntityPublishResult success(int publishedCount, String message) {
        return new PurviewEntityPublishResult(true, publishedCount, message, null);
    }

    public static PurviewEntityPublishResult success(int publishedCount, String message, String resourceGuid) {
        return new PurviewEntityPublishResult(true, publishedCount, message, resourceGuid);
    }

    public static PurviewEntityPublishResult failure(String message) {
        return new PurviewEntityPublishResult(false, 0, message, null);
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

    public String getResourceGuid() {
        return resourceGuid;
    }
}
