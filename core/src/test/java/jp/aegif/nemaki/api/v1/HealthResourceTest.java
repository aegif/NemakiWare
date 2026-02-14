package jp.aegif.nemaki.api.v1;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;
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

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        healthResource = new HealthResource();
    }

    @After
    public void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void testHealthEndpointReturns200() {
        Response response = healthResource.getHealth();
        
        assertNotNull("Response should not be null", response);
        assertEquals("Health endpoint should return 200 OK", 
            Response.Status.OK.getStatusCode(), response.getStatus());
    }

    @Test
    public void testHealthResponseContainsStatus() {
        Response response = healthResource.getHealth();
        Object entity = response.getEntity();
        
        assertNotNull("Response entity should not be null", entity);
        assertTrue("Response should be HealthResponse", entity instanceof HealthResponse);
        
        HealthResponse healthResponse = (HealthResponse) entity;
        assertNotNull("Status should not be null", healthResponse.getStatus());
        assertTrue("Status should be healthy, degraded, or unhealthy",
            healthResponse.getStatus().equals("healthy") ||
            healthResponse.getStatus().equals("degraded") ||
            healthResponse.getStatus().equals("unhealthy"));
    }

    @Test
    public void testHealthResponseContainsChecks() {
        Response response = healthResource.getHealth();
        HealthResponse healthResponse = (HealthResponse) response.getEntity();
        
        Map<String, HealthCheckResult> checks = healthResponse.getChecks();
        assertNotNull("Checks map should not be null", checks);
        
        assertTrue("Checks should include 'couchdb'", checks.containsKey("couchdb"));
        assertTrue("Checks should include 'memory'", checks.containsKey("memory"));
    }

    @Test
    public void testHealthCheckResultStructure() {
        Response response = healthResource.getHealth();
        HealthResponse healthResponse = (HealthResponse) response.getEntity();
        
        HealthCheckResult memoryCheck = healthResponse.getChecks().get("memory");
        assertNotNull("Memory check should not be null", memoryCheck);
        assertNotNull("Memory check status should not be null", memoryCheck.getStatus());
        assertTrue("Memory check status should be up or down",
            memoryCheck.getStatus().equals("up") || memoryCheck.getStatus().equals("down"));
    }

    @Test
    public void testHealthResponseContainsTimestamp() {
        Response response = healthResource.getHealth();
        HealthResponse healthResponse = (HealthResponse) response.getEntity();
        
        assertTrue("Timestamp should be positive", healthResponse.getTimestamp() > 0);
        assertTrue("Timestamp should be recent (within last minute)",
            System.currentTimeMillis() - healthResponse.getTimestamp() < 60000);
    }

    @Test
    public void testMemoryCheckReturnsUsagePercentage() {
        Response response = healthResource.getHealth();
        HealthResponse healthResponse = (HealthResponse) response.getEntity();
        
        HealthCheckResult memoryCheck = healthResponse.getChecks().get("memory");
        assertNotNull("Memory check should have details", memoryCheck.getDetails());
        
        Object usedPercent = memoryCheck.getDetails().get("usedPercent");
        assertNotNull("Memory check should include usedPercent", usedPercent);
        assertTrue("usedPercent should be a number", usedPercent instanceof Number);
        
        double percent = ((Number) usedPercent).doubleValue();
        assertTrue("usedPercent should be between 0 and 100", percent >= 0 && percent <= 100);
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
        assertEquals("Status should be degraded when a component is down", "degraded", overallStatus);
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
        assertEquals("Status should be healthy when all components are up", "healthy", overallStatus);
    }
}
