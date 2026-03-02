package jp.aegif.nemaki.api.v1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.resource.HealthResource;
import jp.aegif.nemaki.api.v1.model.response.HealthResponse;
import jp.aegif.nemaki.api.v1.model.response.HealthCheckResult;

import java.util.Map;

/**
 * TDD tests for HealthResource (T-MGT-001, T-MGT-002).
 * 
 * Tests verify:
 * 1. Health endpoint returns proper status
 * 2. Health checks include CouchDB, Solr, and memory status
 * 3. Response format follows OpenAPI specification
 */
public class HealthResourceTest {

    private HealthResource healthResource;
    private AutoCloseable mocks;

    @Mock
    private HttpServletRequest mockRequest;

    @BeforeEach
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        healthResource = new HealthResource();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void testHealthEndpointReturns200() {
        Response response = healthResource.getHealth();
        
        assertNotNull(response, "Response should not be null");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus(), "Health endpoint should return 200 OK");
    }

    @Test
    public void testHealthResponseContainsStatus() {
        Response response = healthResource.getHealth();
        Object entity = response.getEntity();
        
        assertNotNull(entity, "Response entity should not be null");
        assertTrue(entity instanceof HealthResponse, "Response should be HealthResponse");
        
        HealthResponse healthResponse = (HealthResponse) entity;
        assertNotNull(healthResponse.getStatus(), "Status should not be null");
        assertTrue(healthResponse.getStatus().equals("healthy") ||
            healthResponse.getStatus().equals("degraded") ||
            healthResponse.getStatus().equals("unhealthy"), "Status should be healthy, degraded, or unhealthy");
    }

    @Test
    public void testHealthResponseContainsChecks() {
        Response response = healthResource.getHealth();
        HealthResponse healthResponse = (HealthResponse) response.getEntity();
        
        Map<String, HealthCheckResult> checks = healthResponse.getChecks();
        assertNotNull(checks, "Checks map should not be null");
        
        assertTrue(checks.containsKey("couchdb"), "Checks should include 'couchdb'");
        assertTrue(checks.containsKey("memory"), "Checks should include 'memory'");
    }

    @Test
    public void testHealthCheckResultStructure() {
        Response response = healthResource.getHealth();
        HealthResponse healthResponse = (HealthResponse) response.getEntity();
        
        HealthCheckResult memoryCheck = healthResponse.getChecks().get("memory");
        assertNotNull(memoryCheck, "Memory check should not be null");
        assertNotNull(memoryCheck.getStatus(), "Memory check status should not be null");
        assertTrue(memoryCheck.getStatus().equals("up") || memoryCheck.getStatus().equals("down"), "Memory check status should be up or down");
    }

    @Test
    public void testHealthResponseContainsTimestamp() {
        Response response = healthResource.getHealth();
        HealthResponse healthResponse = (HealthResponse) response.getEntity();
        
        assertTrue(healthResponse.getTimestamp() > 0, "Timestamp should be positive");
        assertTrue(System.currentTimeMillis() - healthResponse.getTimestamp() < 60000, "Timestamp should be recent (within last minute)");
    }

    @Test
    public void testMemoryCheckReturnsUsagePercentage() {
        Response response = healthResource.getHealth();
        HealthResponse healthResponse = (HealthResponse) response.getEntity();
        
        HealthCheckResult memoryCheck = healthResponse.getChecks().get("memory");
        assertNotNull(memoryCheck.getDetails(), "Memory check should have details");
        
        Object usedPercent = memoryCheck.getDetails().get("usedPercent");
        assertNotNull(usedPercent, "Memory check should include usedPercent");
        assertTrue(usedPercent instanceof Number, "usedPercent should be a number");
        
        double percent = ((Number) usedPercent).doubleValue();
        assertTrue(percent >= 0 && percent <= 100, "usedPercent should be between 0 and 100");
    }

    @Test
    public void testHealthStatusDegradedWhenComponentDown() {
        HealthResponse response = new HealthResponse();
        response.setStatus("healthy");
        
        HealthCheckResult couchdbCheck = new HealthCheckResult();
        couchdbCheck.setStatus("down");
        
        HealthCheckResult memoryCheck = new HealthCheckResult();
        memoryCheck.setStatus("up");
        
        response.addCheck("couchdb", couchdbCheck);
        response.addCheck("memory", memoryCheck);
        
        String overallStatus = HealthResource.calculateOverallStatus(response.getChecks());
        assertEquals("degraded", overallStatus, "Status should be degraded when a component is down");
    }

    @Test
    public void testHealthStatusHealthyWhenAllComponentsUp() {
        HealthResponse response = new HealthResponse();
        
        HealthCheckResult couchdbCheck = new HealthCheckResult();
        couchdbCheck.setStatus("up");
        
        HealthCheckResult memoryCheck = new HealthCheckResult();
        memoryCheck.setStatus("up");
        
        response.addCheck("couchdb", couchdbCheck);
        response.addCheck("memory", memoryCheck);
        
        String overallStatus = HealthResource.calculateOverallStatus(response.getChecks());
        assertEquals("healthy", overallStatus, "Status should be healthy when all components are up");
    }
}
