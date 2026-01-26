package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Audit event metrics data.
 */
@Schema(description = "Audit event metrics data")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditMetricsData {

    @Schema(description = "Total number of audit events processed", example = "1234")
    @JsonProperty("audit.events.total")
    private long total;

    @Schema(description = "Number of events successfully logged", example = "1200")
    @JsonProperty("audit.events.logged")
    private long logged;

    @Schema(description = "Number of events skipped", example = "30")
    @JsonProperty("audit.events.skipped")
    private long skipped;

    @Schema(description = "Number of events that failed to log", example = "4")
    @JsonProperty("audit.events.failed")
    private long failed;

    public AuditMetricsData() {
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getLogged() {
        return logged;
    }

    public void setLogged(long logged) {
        this.logged = logged;
    }

    public long getSkipped() {
        return skipped;
    }

    public void setSkipped(long skipped) {
        this.skipped = skipped;
    }

    public long getFailed() {
        return failed;
    }

    public void setFailed(long failed) {
        this.failed = failed;
    }
}
