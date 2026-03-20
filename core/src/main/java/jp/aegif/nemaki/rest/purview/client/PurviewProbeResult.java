package jp.aegif.nemaki.rest.purview.client;

public class PurviewProbeResult {

    private final boolean success;
    private final int statusCode;
    private final String message;

    private PurviewProbeResult(boolean success, int statusCode, String message) {
        this.success = success;
        this.statusCode = statusCode;
        this.message = message;
    }

    public static PurviewProbeResult success(int statusCode, String message) {
        return new PurviewProbeResult(true, statusCode, message);
    }

    public static PurviewProbeResult failure(int statusCode, String message) {
        return new PurviewProbeResult(false, statusCode, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getMessage() {
        return message;
    }
}
