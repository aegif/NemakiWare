package jp.aegif.nemaki.api.v1.resource;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.model.response.StatsResponse;
import jp.aegif.nemaki.api.v1.model.response.StatsResponse.JvmStats;
import jp.aegif.nemaki.api.v1.model.response.StatsResponse.RepositoryStats;

@Path("/repo/{repositoryId}/stats")
public class StatsResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStats(@PathParam("repositoryId") String repositoryId) {
        StatsResponse response = new StatsResponse();

        response.setRepository(getRepositoryStats(repositoryId));
        response.setJvm(getJvmStats());

        return Response.ok(response).build();
    }

    private RepositoryStats getRepositoryStats(String repositoryId) {
        RepositoryStats stats = new RepositoryStats();
        stats.setRepositoryId(repositoryId);
        
        // TODO: Integrate with ContentService to get actual counts
        // For now, return placeholder values
        stats.setNodeCount(0);
        stats.setDocumentCount(0);
        stats.setFolderCount(0);
        
        return stats;
    }

    private JvmStats getJvmStats() {
        JvmStats stats = new JvmStats();

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        stats.setHeapUsed(heapUsage.getUsed());
        stats.setHeapMax(heapUsage.getMax());
        stats.setHeapCommitted(heapUsage.getCommitted());

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        stats.setThreadCount(threadBean.getThreadCount());

        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        stats.setUptimeMs(runtimeBean.getUptime());

        stats.setAvailableProcessors(Runtime.getRuntime().availableProcessors());

        return stats;
    }
}
