package jp.aegif.nemaki.rest.purview.client;

import java.util.List;

public class PurviewEntityPublishResult {

    private final boolean success;
    private final int publishedCount;
    private final int failureCount;
    private final List<FailedItem> failedItems;
    private final String message;
    private final String resourceGuid;

    private PurviewEntityPublishResult(boolean success, int publishedCount, int failureCount,
            List<FailedItem> failedItems, String message, String resourceGuid) {
        this.success = success;
        this.publishedCount = publishedCount;
        this.failureCount = failureCount;
        this.failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
        this.message = message;
        this.resourceGuid = resourceGuid;
    }

    public static PurviewEntityPublishResult success(int publishedCount, String message) {
        return new PurviewEntityPublishResult(true, publishedCount, 0, List.of(), message, null);
    }

    public static PurviewEntityPublishResult success(int publishedCount, String message, String resourceGuid) {
        return new PurviewEntityPublishResult(true, publishedCount, 0, List.of(), message, resourceGuid);
    }

    public static PurviewEntityPublishResult partialSuccess(int publishedCount, int failureCount,
            List<FailedItem> failedItems, String message) {
        return new PurviewEntityPublishResult(true, publishedCount, failureCount, failedItems, message, null);
    }

    public static PurviewEntityPublishResult failure(String message) {
        return new PurviewEntityPublishResult(false, 0, 0, List.of(), message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean hasFailures() {
        return failureCount > 0;
    }

    public int getPublishedCount() {
        return publishedCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public List<FailedItem> getFailedItems() {
        return failedItems;
    }

    public String getMessage() {
        return message;
    }

    public String getResourceGuid() {
        return resourceGuid;
    }

    public static class FailedItem {

        private final String qualifiedName;
        private final String typeName;
        private final String errorMessage;

        public FailedItem(String qualifiedName, String typeName, String errorMessage) {
            this.qualifiedName = qualifiedName;
            this.typeName = typeName;
            this.errorMessage = errorMessage;
        }

        public String getQualifiedName() {
            return qualifiedName;
        }

        public String getTypeName() {
            return typeName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
