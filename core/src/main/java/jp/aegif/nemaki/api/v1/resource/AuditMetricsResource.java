/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * OpenAPI compliant REST endpoint for audit logging metrics.
 * Provides monitoring and alerting capabilities for audit log statistics.
 *
 * This endpoint is part of the OpenAPI 3.0 compliant API v1 and appears in Swagger UI.
 * For backward compatibility, the legacy endpoint at /rest/all/audit/metrics is also available.
 */
@Component
@Path("/audit/metrics")
@Tag(name = "audit", description = "Audit logging metrics and monitoring")
@Produces(MediaType.APPLICATION_JSON)
public class AuditMetricsResource {

    private static final Logger logger = Logger.getLogger(AuditMetricsResource.class.getName());

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpServletRequest httpRequest;

    /**
     * Checks if the current user has admin authorization.
     *
     * @throws ApiException if user is not authenticated or not an admin
     */
    private void checkAdminAuthorization() {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            throw ApiException.unauthorized("Authentication required for audit metrics operations");
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        if (isAdmin == null || !isAdmin) {
            throw ApiException.permissionDenied("Only administrators can access audit metrics");
        }
    }

    @GET
    @Operation(
            summary = "Get audit metrics",
            description = "Returns audit event statistics including total events, logged count, skipped count, " +
                          "failure count, and calculated rates. Useful for monitoring dashboards and alerting."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Audit metrics retrieved successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuditMetricsResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Authentication required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Admin privileges required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getMetrics() {
        logger.info("API v1: Getting audit metrics");

        checkAdminAuthorization();

        try {
            Map<String, Long> metrics = AuditLogger.getMetrics();

            AuditMetricsResponse response = new AuditMetricsResponse();
            response.setMetrics(new MetricsData());
            response.getMetrics().setTotal(metrics.getOrDefault("audit.events.total", 0L));
            response.getMetrics().setLogged(metrics.getOrDefault("audit.events.logged", 0L));
            response.getMetrics().setSkipped(metrics.getOrDefault("audit.events.skipped", 0L));
            response.getMetrics().setFailed(metrics.getOrDefault("audit.events.failed", 0L));

            // Calculate rates
            long total = response.getMetrics().getTotal();
            if (total > 0) {
                RatesData rates = new RatesData();
                rates.setSuccessRate((double) response.getMetrics().getLogged() / total * 100);
                rates.setSkipRate((double) response.getMetrics().getSkipped() / total * 100);
                rates.setFailureRate((double) response.getMetrics().getFailed() / total * 100);
                response.setRates(rates);
            }

            // Configuration status
            response.setEnabled(AuditLogger.isEnabled());
            response.setReadAuditLevel(AuditLogger.getReadAuditLevel());
            response.setTimestamp(System.currentTimeMillis());

            // HATEOAS links
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/audit/metrics"));
            links.put("reset", new LinkInfo("/api/v1/cmis/audit/metrics/reset"));
            links.put("prometheus", new LinkInfo("/api/v1/cmis/audit/metrics/prometheus"));
            response.setLinks(links);

            return Response.ok(response).build();

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting audit metrics: " + e.getMessage());
            // Log the actual error but return a generic message
            throw ApiException.internalError("Failed to retrieve audit metrics");
        }
    }

    @POST
    @Path("/reset")
    @Operation(
            summary = "Reset audit metrics",
            description = "Resets all audit metrics counters to zero. Returns the values before reset for reference."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Audit metrics reset successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuditMetricsResetResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Authentication required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Admin privileges required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response resetMetrics() {
        logger.info("API v1: Resetting audit metrics");

        checkAdminAuthorization();

        try {
            // Get metrics before reset
            Map<String, Long> beforeReset = AuditLogger.getMetrics();

            // Reset metrics
            AuditLogger.resetMetrics();

            AuditMetricsResetResponse response = new AuditMetricsResetResponse();
            response.setSuccess(true);
            response.setMessage("Audit metrics reset successfully");

            // Previous values
            MetricsData previous = new MetricsData();
            previous.setTotal(beforeReset.getOrDefault("audit.events.total", 0L));
            previous.setLogged(beforeReset.getOrDefault("audit.events.logged", 0L));
            previous.setSkipped(beforeReset.getOrDefault("audit.events.skipped", 0L));
            previous.setFailed(beforeReset.getOrDefault("audit.events.failed", 0L));
            response.setPreviousValues(previous);

            response.setTimestamp(System.currentTimeMillis());

            // HATEOAS links
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/audit/metrics/reset"));
            links.put("metrics", new LinkInfo("/api/v1/cmis/audit/metrics"));
            response.setLinks(links);

            return Response.ok(response).build();

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error resetting audit metrics: " + e.getMessage());
            throw ApiException.internalError("Failed to reset audit metrics");
        }
    }

    @GET
    @Path("/prometheus")
    @Produces("text/plain; version=0.0.4; charset=utf-8")
    @Operation(
            summary = "Get Prometheus metrics",
            description = "Returns audit metrics in Prometheus exposition format. Compatible with Prometheus scraping and Grafana dashboards."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Prometheus metrics retrieved successfully",
                    content = @Content(
                            mediaType = "text/plain; version=0.0.4; charset=utf-8",
                            schema = @Schema(type = "string", example = "# HELP nemakiware_audit_events_total Total audit events\n# TYPE nemakiware_audit_events_total counter\nnemakiware_audit_events_total 1234")
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Authentication required"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Admin privileges required"
            )
    })
    public Response getPrometheusMetrics() {
        logger.fine("API v1: Getting Prometheus audit metrics");

        checkAdminAuthorization();

        try {
            Map<String, Long> metrics = AuditLogger.getMetrics();
            StringBuilder prometheus = new StringBuilder();

            // Total events (counter)
            prometheus.append("# HELP nemakiware_audit_events_total Total number of audit events processed\n");
            prometheus.append("# TYPE nemakiware_audit_events_total counter\n");
            prometheus.append("nemakiware_audit_events_total ")
                      .append(metrics.getOrDefault("audit.events.total", 0L))
                      .append("\n\n");

            // Logged events (counter)
            prometheus.append("# HELP nemakiware_audit_events_logged Number of audit events successfully logged\n");
            prometheus.append("# TYPE nemakiware_audit_events_logged counter\n");
            prometheus.append("nemakiware_audit_events_logged ")
                      .append(metrics.getOrDefault("audit.events.logged", 0L))
                      .append("\n\n");

            // Skipped events (counter)
            prometheus.append("# HELP nemakiware_audit_events_skipped Number of audit events skipped\n");
            prometheus.append("# TYPE nemakiware_audit_events_skipped counter\n");
            prometheus.append("nemakiware_audit_events_skipped ")
                      .append(metrics.getOrDefault("audit.events.skipped", 0L))
                      .append("\n\n");

            // Failed events (counter)
            prometheus.append("# HELP nemakiware_audit_events_failed Number of audit events that failed to log\n");
            prometheus.append("# TYPE nemakiware_audit_events_failed counter\n");
            prometheus.append("nemakiware_audit_events_failed ")
                      .append(metrics.getOrDefault("audit.events.failed", 0L))
                      .append("\n\n");

            // Audit enabled status (gauge)
            prometheus.append("# HELP nemakiware_audit_enabled Whether audit logging is enabled (1=enabled, 0=disabled)\n");
            prometheus.append("# TYPE nemakiware_audit_enabled gauge\n");
            prometheus.append("nemakiware_audit_enabled ")
                      .append(AuditLogger.isEnabled() ? 1 : 0)
                      .append("\n");

            return Response.ok(prometheus.toString())
                    .type("text/plain; version=0.0.4; charset=utf-8")
                    .build();

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting Prometheus metrics: " + e.getMessage());
            return Response.status(500)
                    .type("text/plain")
                    .entity("# Error: Internal server error\n")
                    .build();
        }
    }

    // Response DTOs with OpenAPI Schema annotations

    @Schema(description = "Audit metrics response")
    public static class AuditMetricsResponse {
        @Schema(description = "Audit event metrics")
        private MetricsData metrics;

        @Schema(description = "Calculated success/skip/failure rates (only present if total > 0)")
        private RatesData rates;

        @Schema(description = "Whether audit logging is enabled")
        private boolean enabled;

        @Schema(description = "Read audit level (NONE, BASIC, FULL)")
        private String readAuditLevel;

        @Schema(description = "Timestamp when metrics were retrieved (epoch millis)")
        private long timestamp;

        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;

        public MetricsData getMetrics() { return metrics; }
        public void setMetrics(MetricsData metrics) { this.metrics = metrics; }
        public RatesData getRates() { return rates; }
        public void setRates(RatesData rates) { this.rates = rates; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getReadAuditLevel() { return readAuditLevel; }
        public void setReadAuditLevel(String readAuditLevel) { this.readAuditLevel = readAuditLevel; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }

    @Schema(description = "Audit metrics data")
    public static class MetricsData {
        @Schema(description = "Total number of audit events processed")
        private long total;

        @Schema(description = "Number of events successfully logged")
        private long logged;

        @Schema(description = "Number of events skipped")
        private long skipped;

        @Schema(description = "Number of events that failed to log")
        private long failed;

        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public long getLogged() { return logged; }
        public void setLogged(long logged) { this.logged = logged; }
        public long getSkipped() { return skipped; }
        public void setSkipped(long skipped) { this.skipped = skipped; }
        public long getFailed() { return failed; }
        public void setFailed(long failed) { this.failed = failed; }
    }

    @Schema(description = "Calculated rate data (percentages)")
    public static class RatesData {
        @Schema(description = "Success rate as percentage (0-100)")
        private double successRate;

        @Schema(description = "Skip rate as percentage (0-100)")
        private double skipRate;

        @Schema(description = "Failure rate as percentage (0-100)")
        private double failureRate;

        public double getSuccessRate() { return successRate; }
        public void setSuccessRate(double successRate) { this.successRate = successRate; }
        public double getSkipRate() { return skipRate; }
        public void setSkipRate(double skipRate) { this.skipRate = skipRate; }
        public double getFailureRate() { return failureRate; }
        public void setFailureRate(double failureRate) { this.failureRate = failureRate; }
    }

    @Schema(description = "Audit metrics reset response")
    public static class AuditMetricsResetResponse {
        @Schema(description = "Whether the reset was successful")
        private boolean success;

        @Schema(description = "Status message")
        private String message;

        @Schema(description = "Metrics values before reset")
        private MetricsData previousValues;

        @Schema(description = "Timestamp when reset occurred (epoch millis)")
        private long timestamp;

        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public MetricsData getPreviousValues() { return previousValues; }
        public void setPreviousValues(MetricsData previousValues) { this.previousValues = previousValues; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
}
