package jp.aegif.nemaki.api.v1.model.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.Map;

/**
 * Result of an individual health check component.
 */
@Schema(description = "Individual health check result for a component")
public class HealthCheckResult {

    @Schema(description = "Component status", example = "up", allowableValues = {"up", "down"})
    private String status;

    @Schema(description = "Response time in milliseconds (if applicable)", example = "15")
    private Long responseTimeMs;

    @Schema(description = "Additional details about the health check")
    private Map<String, Object> details;

    @Schema(description = "Error message if the check failed")
    private String error;

    public HealthCheckResult() {
        this.details = new HashMap<>();
    }

    public HealthCheckResult(String status) {
        this();
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(Long responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }

    public void addDetail(String key, Object value) {
        this.details.put(key, value);
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
