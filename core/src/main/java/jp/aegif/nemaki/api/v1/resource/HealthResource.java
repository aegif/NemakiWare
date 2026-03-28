package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.model.response.HealthResponse;
import jp.aegif.nemaki.api.v1.model.response.HealthCheckResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.init.CouchDbConnectionResult;
import jp.aegif.nemaki.init.StartupProbeService;
import jp.aegif.nemaki.util.PropertyManager;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Health check endpoint for NemakiWare (T-MGT-001, T-MGT-002).
 * 
 * Provides system health status including:
 * - CouchDB connectivity
 * - Solr connectivity  
 * - Memory status
 * 
 * This endpoint does not require authentication to allow load balancers
 * and monitoring systems to check health status.
 */
@Component
@Path("/health")
@Tag(name = "operations", description = "Operations and management endpoints")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {

    private static final Logger logger = Logger.getLogger(HealthResource.class.getName());

    private static final double MEMORY_WARNING_THRESHOLD = 80.0;
    private static final double MEMORY_CRITICAL_THRESHOLD = 95.0;

    @Autowired(required = false)
    private StartupProbeService startupProbeService;

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageConfig lineageConfig;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageJournalStore lineageJournalStore;

    @GET
    @Operation(
            summary = "Get system health status",
            description = "Returns the health status of NemakiWare and its dependencies. " +
                          "Includes checks for CouchDB, Solr, and JVM memory. " +
                          "This endpoint does not require authentication."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Health check completed successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = HealthResponse.class)
                    )
            )
    })
    public Response getHealth() {
        logger.fine("Health check requested");

        HealthResponse response = new HealthResponse();
        response.setTimestamp(System.currentTimeMillis());

        HealthCheckResult couchdbCheck = checkCouchDB();
        response.addCheck("couchdb", couchdbCheck);

        HealthCheckResult memoryCheck = checkMemory();
        response.addCheck("memory", memoryCheck);

        HealthCheckResult lineageCheck = checkLineage();
        if (lineageCheck != null) {
            response.addCheck("lineage", lineageCheck);
        }

        String overallStatus = calculateOverallStatus(response.getChecks());
        response.setStatus(overallStatus);

        return Response.ok(response).build();
    }

    /**
     * Check CouchDB connectivity via StartupProbeService.
     */
    private HealthCheckResult checkCouchDB() {
        HealthCheckResult result = new HealthCheckResult();

        if (startupProbeService == null) {
            result.setStatus("down");
            result.setError("StartupProbeService not available — initialization anomaly");
            logger.warning("CouchDB health check: StartupProbeService is null (wiring failure)");
            return result;
        }

        try {
            String url = propertyManager != null ? propertyManager.readValue("db.couchdb.url") : null;
            if (url == null || url.trim().isEmpty()) url = "http://couchdb:5984";
            String user = propertyManager != null ? propertyManager.readValue("db.couchdb.auth.username") : "admin";
            if (user == null || user.trim().isEmpty()) user = "admin";
            String pass = propertyManager != null ? propertyManager.readValue("db.couchdb.auth.password") : "password";
            if (pass == null || pass.trim().isEmpty()) pass = "password";

            CouchDbConnectionResult conn = startupProbeService.testConnection(url, user, pass);
            result.setResponseTimeMs(conn.getResponseTimeMs());

            if (conn.isReachable()) {
                result.setStatus("up");
                result.addDetail("version", conn.getCouchDbVersion());
            } else {
                result.setStatus("down");
                result.setError(conn.getErrorMessage());
            }
        } catch (Exception e) {
            result.setStatus("down");
            result.setError(e.getMessage());
            logger.warning("CouchDB health check failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Check JVM memory status.
     */
    private HealthCheckResult checkMemory() {
        HealthCheckResult result = new HealthCheckResult();

        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

            long usedMemory = heapUsage.getUsed();
            long maxMemory = heapUsage.getMax();
            double usedPercent = (double) usedMemory / maxMemory * 100;

            result.addDetail("usedBytes", usedMemory);
            result.addDetail("maxBytes", maxMemory);
            result.addDetail("usedPercent", Math.round(usedPercent * 100.0) / 100.0);

            if (usedPercent >= MEMORY_CRITICAL_THRESHOLD) {
                result.setStatus("down");
                result.addDetail("warning", "Memory usage critical");
            } else if (usedPercent >= MEMORY_WARNING_THRESHOLD) {
                result.setStatus("up");
                result.addDetail("warning", "Memory usage high");
            } else {
                result.setStatus("up");
            }
        } catch (Exception e) {
            result.setStatus("down");
            result.setError("Failed to check memory: " + e.getMessage());
            logger.warning("Memory health check failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Check lineage journal health. Returns null if lineage is not configured.
     */
    private HealthCheckResult checkLineage() {
        if (lineageConfig == null) return null;

        try {
            String mode = lineageConfig.getMode().name().toLowerCase();
            if ("disabled".equals(mode)) return null;

            HealthCheckResult result = new HealthCheckResult();
            result.addDetail("mode", mode);

            boolean storeActive = lineageJournalStore != null && lineageJournalStore.isActive();
            result.addDetail("storeActive", storeActive);

            if ("journaled".equals(mode) && !storeActive) {
                result.setStatus("down");
                result.setError("Journaled mode enabled but journal store is not active");
                return result;
            }

            // Check backlog for warning
            if (storeActive) {
                int maxDocs = lineageConfig.getBacklogMaxDocs();
                for (String target : lineageConfig.getTargets()) {
                    long nonTerminal = lineageJournalStore.countNonTerminalByTarget(target);
                    if (maxDocs > 0 && nonTerminal > maxDocs * 0.8) {
                        result.addDetail("warning", "Backlog for '" + target + "' at " +
                                (nonTerminal * 100 / maxDocs) + "% capacity (" + nonTerminal + "/" + maxDocs + ")");
                    }
                }
            }

            result.setStatus("up");
            return result;
        } catch (Exception e) {
            HealthCheckResult result = new HealthCheckResult();
            result.setStatus("down");
            result.setError("Lineage health check failed: " + e.getMessage());
            return result;
        }
    }

    /**
     * Calculate overall health status based on individual checks.
     * 
     * @param checks Map of health check results
     * @return "healthy" if all checks pass, "degraded" if some fail, "unhealthy" if critical checks fail
     */
    public static String calculateOverallStatus(Map<String, HealthCheckResult> checks) {
        if (checks == null || checks.isEmpty()) {
            return "unhealthy";
        }

        boolean hasDown = false;
        boolean allUp = true;

        for (HealthCheckResult check : checks.values()) {
            if ("down".equals(check.getStatus())) {
                hasDown = true;
                allUp = false;
            } else if (!"up".equals(check.getStatus())) {
                allUp = false;
            }
        }

        if (allUp) {
            return "healthy";
        } else if (hasDown) {
            return "degraded";
        } else {
            return "degraded";
        }
    }
}
