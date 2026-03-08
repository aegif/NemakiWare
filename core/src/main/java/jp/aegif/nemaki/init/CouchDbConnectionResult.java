package jp.aegif.nemaki.init;

/**
 * Result of a CouchDB connection test.
 */
public class CouchDbConnectionResult {

    private boolean reachable;
    private String couchDbVersion;
    private String errorMessage;
    private long responseTimeMs;

    public CouchDbConnectionResult() {
    }

    public boolean isReachable() {
        return reachable;
    }

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }

    public String getCouchDbVersion() {
        return couchDbVersion;
    }

    public void setCouchDbVersion(String couchDbVersion) {
        this.couchDbVersion = couchDbVersion;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(long responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }
}
