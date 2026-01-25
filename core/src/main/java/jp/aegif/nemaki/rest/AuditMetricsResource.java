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
package jp.aegif.nemaki.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.audit.AuditLogger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.Map;

/**
 * REST endpoint for audit logging metrics.
 * Provides monitoring and alerting capabilities for audit log statistics.
 *
 * Endpoints:
 * - GET /rest/all/audit/metrics - Returns audit event statistics
 * - POST /rest/all/audit/metrics/reset - Resets all metrics counters (admin only)
 */
@Path("/all/audit/metrics")
public class AuditMetricsResource extends ResourceBase {

    /**
     * Returns audit logging metrics.
     * Useful for monitoring dashboards, alerting, and performance analysis.
     * This endpoint is restricted to admin users only for security reasons.
     *
     * @param httpRequest The HTTP request
     * @return JSON object containing audit metrics
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public Response getMetrics(@Context HttpServletRequest httpRequest) {
        JSONArray errMsg = new JSONArray();

        // Check admin permission (audit metrics may contain sensitive information)
        if (!checkAdmin(errMsg, httpRequest)) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", "Only administrators can view audit metrics");
            error.put("errors", errMsg);
            return Response.status(403).entity(error.toJSONString()).build();
        }

        try {
            Map<String, Long> metrics = AuditLogger.getMetrics();

            JSONObject result = new JSONObject();
            result.put("status", "ok");

            // Add metrics
            JSONObject metricsJson = new JSONObject();
            for (Map.Entry<String, Long> entry : metrics.entrySet()) {
                metricsJson.put(entry.getKey(), entry.getValue());
            }
            result.put("metrics", metricsJson);

            // Add calculated rates
            long total = metrics.getOrDefault("audit.events.total", 0L);
            if (total > 0) {
                JSONObject rates = new JSONObject();
                long logged = metrics.getOrDefault("audit.events.logged", 0L);
                long skipped = metrics.getOrDefault("audit.events.skipped", 0L);
                long failed = metrics.getOrDefault("audit.events.failed", 0L);

                rates.put("success.rate", String.format("%.2f%%", (double) logged / total * 100));
                rates.put("skip.rate", String.format("%.2f%%", (double) skipped / total * 100));
                rates.put("failure.rate", String.format("%.2f%%", (double) failed / total * 100));
                result.put("rates", rates);
            }

            // Add audit configuration status
            result.put("enabled", AuditLogger.isEnabled());
            result.put("readAuditLevel", AuditLogger.getReadAuditLevel());
            result.put("timestamp", System.currentTimeMillis());

            return Response.ok(result.toJSONString()).build();

        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", "Failed to get audit metrics: " + e.getMessage());
            return Response.status(500).entity(error.toJSONString()).build();
        }
    }

    /**
     * Resets all audit metrics counters to zero.
     * This operation is restricted to admin users only.
     *
     * @param httpRequest The HTTP request
     * @return JSON object indicating success or failure
     */
    @POST
    @Path("/reset")
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public Response resetMetrics(@Context HttpServletRequest httpRequest) {
        JSONArray errMsg = new JSONArray();

        // Check admin permission
        if (!checkAdmin(errMsg, httpRequest)) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", "Only administrators can reset audit metrics");
            error.put("errors", errMsg);
            return Response.status(403).entity(error.toJSONString()).build();
        }

        try {
            // Get metrics before reset for logging
            Map<String, Long> beforeReset = AuditLogger.getMetrics();

            // Reset metrics
            AuditLogger.resetMetrics();

            JSONObject result = new JSONObject();
            result.put("status", "ok");
            result.put("message", "Audit metrics reset successfully");

            // Include previous values for reference
            JSONObject previousValues = new JSONObject();
            for (Map.Entry<String, Long> entry : beforeReset.entrySet()) {
                previousValues.put(entry.getKey(), entry.getValue());
            }
            result.put("previousValues", previousValues);
            result.put("timestamp", System.currentTimeMillis());

            return Response.ok(result.toJSONString()).build();

        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", "Failed to reset audit metrics: " + e.getMessage());
            return Response.status(500).entity(error.toJSONString()).build();
        }
    }
}
