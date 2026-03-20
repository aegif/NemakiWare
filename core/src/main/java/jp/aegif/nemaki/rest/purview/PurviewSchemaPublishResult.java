package jp.aegif.nemaki.rest.purview;

public class PurviewSchemaPublishResult {

    private final boolean success;
    private final String message;

    private PurviewSchemaPublishResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static PurviewSchemaPublishResult success(String message) {
        return new PurviewSchemaPublishResult(true, message);
    }

    public static PurviewSchemaPublishResult failure(String message) {
        return new PurviewSchemaPublishResult(false, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}
