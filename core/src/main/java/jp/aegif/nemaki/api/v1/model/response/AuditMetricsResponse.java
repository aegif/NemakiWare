package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Audit metrics response containing event statistics and configuration status.
 */
@Schema(description = "Audit metrics response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditMetricsResponse {

    @Schema(description = "Response status", example = "ok")
    @JsonProperty("status")
    private String status = "ok";

    @Schema(description = "Audit event metrics")
    @JsonProperty("metrics")
    private AuditMetricsData metrics;

    @Schema(description = "Calculated success/skip/failure rates (only present if total > 0)")
    @JsonProperty("rates")
    private AuditRatesData rates;

    @Schema(description = "Whether audit logging is enabled")
    @JsonProperty("enabled")
    private boolean enabled;

    @Schema(description = "Read audit level (NONE, BASIC, FULL)")
    @JsonProperty("readAuditLevel")
    private String readAuditLevel;

    @Schema(description = "Timestamp when metrics were retrieved (epoch millis)")
    @JsonProperty("timestamp")
    private long timestamp;

    @Schema(description = "HATEOAS links")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;

    public AuditMetricsResponse() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public AuditMetricsData getMetrics() {
        return metrics;
    }

    public void setMetrics(AuditMetricsData metrics) {
        this.metrics = metrics;
    }

    public AuditRatesData getRates() {
        return rates;
    }

    public void setRates(AuditRatesData rates) {
        this.rates = rates;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getReadAuditLevel() {
        return readAuditLevel;
    }

    public void setReadAuditLevel(String readAuditLevel) {
        this.readAuditLevel = readAuditLevel;
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
