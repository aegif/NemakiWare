package jp.aegif.nemaki.api.v1;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import jp.aegif.nemaki.api.v1.resource.MetricsResource;

import jakarta.ws.rs.core.Response;

public class MetricsResourceTest {

    private MetricsResource metricsResource;

    @Before
    public void setUp() {
        metricsResource = new MetricsResource();
    }

    @Test
    public void testMetricsEndpointReturns200() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testMetricsReturnsTextPlain() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String contentType = response.getMediaType().toString();
        assertTrue(contentType.contains("text/plain"));
    }

    @Test
    public void testMetricsContainsJvmHeapUsed() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("jvm_memory_heap_used_bytes"));
    }

    @Test
    public void testMetricsContainsJvmHeapMax() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("jvm_memory_heap_max_bytes"));
    }

    @Test
    public void testMetricsContainsJvmThreadCount() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("jvm_threads_current"));
    }

    @Test
    public void testMetricsContainsJvmUptime() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("jvm_uptime_seconds"));
    }

    @Test
    public void testMetricsContainsRepositoryNodeCount() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("nemaki_repository_nodes_total"));
    }

    @Test
    public void testMetricsContainsRepositoryDocumentCount() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("nemaki_repository_documents_total"));
    }

    @Test
    public void testMetricsContainsRepositoryFolderCount() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("nemaki_repository_folders_total"));
    }

    @Test
    public void testMetricsContainsJobsPending() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("nemaki_jobs_pending"));
    }

    @Test
    public void testMetricsContainsJobsRunning() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("nemaki_jobs_running"));
    }

    @Test
    public void testMetricsFormatIsPrometheusCompatible() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        // Prometheus format: metric_name{labels} value
        // Check that at least one metric follows this format
        assertTrue(metrics.matches("(?s).*\\w+\\{[^}]*\\}\\s+[\\d.]+.*") || 
                   metrics.matches("(?s).*\\w+\\s+[\\d.]+.*"));
    }

    @Test
    public void testMetricsContainsHelpComments() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("# HELP"));
    }

    @Test
    public void testMetricsContainsTypeComments() {
        String repositoryId = "test-repo";
        Response response = metricsResource.getMetrics(repositoryId);
        String metrics = (String) response.getEntity();
        assertTrue(metrics.contains("# TYPE"));
    }
}
