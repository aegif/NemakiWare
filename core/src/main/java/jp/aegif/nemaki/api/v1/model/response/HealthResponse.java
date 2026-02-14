package jp.aegif.nemaki.api.v1.model.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.Map;

/**
 * Response model for health check endpoint.
 * Follows standard health check response format.
 */
@Schema(description = "Health check response containing system status and component checks")
public class HealthResponse {

    @Schema(description = "Overall health status", example = "healthy", 
            allowableValues = {"healthy", "degraded", "unhealthy"})
    private String status;

    @Schema(description = "Individual component health checks")
    private Map<String, HealthCheckResult> checks;

    @Schema(description = "Timestamp of the health check in milliseconds since epoch", example = "1706500000000")
    private long timestamp;

    public HealthResponse() {
        this.checks = new HashMap<>();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, HealthCheckResult> getChecks() {
        return checks;
    }

    public void setChecks(Map<String, HealthCheckResult> checks) {
        this.checks = checks;
    }

    public void addCheck(String name, HealthCheckResult result) {
        this.checks.put(name, result);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
