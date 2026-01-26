package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Response for audit metrics reset operation.
 */
@Schema(description = "Audit metrics reset response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditMetricsResetResponse {

    @Schema(description = "Response status", example = "ok")
    @JsonProperty("status")
    private String status = "ok";

    @Schema(description = "Status message")
    @JsonProperty("message")
    private String message;

    @Schema(description = "Metrics values before reset")
    @JsonProperty("previousValues")
    private AuditMetricsData previousValues;

    @Schema(description = "Timestamp when reset occurred (epoch millis)")
    @JsonProperty("timestamp")
    private long timestamp;

    @Schema(description = "HATEOAS links")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;

    public AuditMetricsResetResponse() {
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

    public AuditMetricsData getPreviousValues() {
        return previousValues;
    }

    public void setPreviousValues(AuditMetricsData previousValues) {
        this.previousValues = previousValues;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, LinkInfo> getLinks() {
        return links;
    }

    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
