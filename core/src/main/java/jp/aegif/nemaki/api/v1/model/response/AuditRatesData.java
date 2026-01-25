package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Calculated success/skip/failure rates as formatted strings.
 */
@Schema(description = "Calculated success/skip/failure rates")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditRatesData {

    @Schema(description = "Success rate as percentage string", example = "95.50%")
    @JsonProperty("success.rate")
    private String successRate;

    @Schema(description = "Skip rate as percentage string", example = "2.50%")
    @JsonProperty("skip.rate")
    private String skipRate;

    @Schema(description = "Failure rate as percentage string", example = "2.00%")
    @JsonProperty("failure.rate")
    private String failureRate;

    public AuditRatesData() {
    }

    public String getSuccessRate() {
        return successRate;
    }

    public void setSuccessRate(String successRate) {
        this.successRate = successRate;
    }

    public String getSkipRate() {
        return skipRate;
    }

    public void setSkipRate(String skipRate) {
        this.skipRate = skipRate;
    }

    public String getFailureRate() {
        return failureRate;
    }

    public void setFailureRate(String failureRate) {
        this.failureRate = failureRate;
    }

    /**
     * Creates AuditRatesData from raw counts.
     *
     * @param total Total events
     * @param logged Logged events
     * @param skipped Skipped events
     * @param failed Failed events
     * @return AuditRatesData with formatted percentage strings
     */
    public static AuditRatesData fromCounts(long total, long logged, long skipped, long failed) {
        if (total <= 0) {
            return null;
        }
        AuditRatesData rates = new AuditRatesData();
        rates.setSuccessRate(String.format("%.2f%%", (double) logged / total * 100));
        rates.setSkipRate(String.format("%.2f%%", (double) skipped / total * 100));
        rates.setFailureRate(String.format("%.2f%%", (double) failed / total * 100));
        return rates;
    }
}
