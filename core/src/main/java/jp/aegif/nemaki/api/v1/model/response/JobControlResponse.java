package jp.aegif.nemaki.api.v1.model.response;

import java.time.Instant;

public class JobControlResponse {
    private String status;
    private String message;
    private int pendingJobs;
    private int runningJobs;
    private String timestamp;

    public JobControlResponse() {
        this.timestamp = Instant.now().toString();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getPendingJobs() {
        return pendingJobs;
    }

    public void setPendingJobs(int pendingJobs) {
        this.pendingJobs = pendingJobs;
    }

    public int getRunningJobs() {
        return runningJobs;
    }

    public void setRunningJobs(int runningJobs) {
        this.runningJobs = runningJobs;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
