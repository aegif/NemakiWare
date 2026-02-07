package jp.aegif.nemaki.api.v1.resource;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.model.response.StatsResponse;
import jp.aegif.nemaki.api.v1.model.response.StatsResponse.JvmStats;
import jp.aegif.nemaki.api.v1.model.response.StatsResponse.RepositoryStats;
import org.apache.chemistry.opencmis.commons.server.CallContext;

@Path("/repo/{repositoryId}/stats")
public class StatsResource {

    private boolean isAdmin(HttpServletRequest request) {
        CallContext callContext = (CallContext) request.getAttribute("CallContext");
        if (callContext == null) return false;
        Boolean isAdmin = (Boolean) callContext.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN);
        return isAdmin != null && isAdmin;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStats(@PathParam("repositoryId") String repositoryId,
            @Context HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("{\"error\":\"Admin access required\"}").build();
        }
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
