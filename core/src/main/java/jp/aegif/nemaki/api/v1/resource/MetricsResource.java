package jp.aegif.nemaki.api.v1.resource;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

@Path("/repo/{repositoryId}/metrics")
public class MetricsResource {

    private static final String CONTENT_TYPE_OPENMETRICS = "text/plain; version=0.0.4; charset=utf-8";

    @GET
    @Produces("text/plain")
    public Response getMetrics(@PathParam("repositoryId") String repositoryId) {
        StringBuilder sb = new StringBuilder();

        appendJvmMetrics(sb);
        appendRepositoryMetrics(sb, repositoryId);
        appendJobMetrics(sb, repositoryId);
        appendGcMetrics(sb);

        return Response.ok(sb.toString())
                .header("Content-Type", CONTENT_TYPE_OPENMETRICS)
                .build();
    }

    private void appendJvmMetrics(StringBuilder sb) {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

        sb.append("# HELP jvm_memory_heap_used_bytes JVM heap memory used in bytes\n");
        sb.append("# TYPE jvm_memory_heap_used_bytes gauge\n");
        sb.append("jvm_memory_heap_used_bytes ").append(heapUsage.getUsed()).append("\n");

        sb.append("# HELP jvm_memory_heap_max_bytes JVM heap memory max in bytes\n");
        sb.append("# TYPE jvm_memory_heap_max_bytes gauge\n");
        sb.append("jvm_memory_heap_max_bytes ").append(heapUsage.getMax()).append("\n");

        sb.append("# HELP jvm_memory_heap_committed_bytes JVM heap memory committed in bytes\n");
        sb.append("# TYPE jvm_memory_heap_committed_bytes gauge\n");
        sb.append("jvm_memory_heap_committed_bytes ").append(heapUsage.getCommitted()).append("\n");

        sb.append("# HELP jvm_memory_nonheap_used_bytes JVM non-heap memory used in bytes\n");
        sb.append("# TYPE jvm_memory_nonheap_used_bytes gauge\n");
        sb.append("jvm_memory_nonheap_used_bytes ").append(nonHeapUsage.getUsed()).append("\n");

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        sb.append("# HELP jvm_threads_current Current number of JVM threads\n");
        sb.append("# TYPE jvm_threads_current gauge\n");
        sb.append("jvm_threads_current ").append(threadBean.getThreadCount()).append("\n");

        sb.append("# HELP jvm_threads_daemon Number of JVM daemon threads\n");
        sb.append("# TYPE jvm_threads_daemon gauge\n");
        sb.append("jvm_threads_daemon ").append(threadBean.getDaemonThreadCount()).append("\n");

        sb.append("# HELP jvm_threads_peak Peak number of JVM threads\n");
        sb.append("# TYPE jvm_threads_peak gauge\n");
        sb.append("jvm_threads_peak ").append(threadBean.getPeakThreadCount()).append("\n");

        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        double uptimeSeconds = runtimeBean.getUptime() / 1000.0;
        sb.append("# HELP jvm_uptime_seconds JVM uptime in seconds\n");
        sb.append("# TYPE jvm_uptime_seconds gauge\n");
        sb.append("jvm_uptime_seconds ").append(String.format("%.3f", uptimeSeconds)).append("\n");

        sb.append("# HELP jvm_available_processors Number of available processors\n");
        sb.append("# TYPE jvm_available_processors gauge\n");
        sb.append("jvm_available_processors ").append(Runtime.getRuntime().availableProcessors()).append("\n");
    }

    private void appendRepositoryMetrics(StringBuilder sb, String repositoryId) {
        // TODO: Integrate with ContentService to get actual counts
        // For now, return placeholder values
        long nodeCount = 0;
        long documentCount = 0;
        long folderCount = 0;

        sb.append("# HELP nemaki_repository_nodes_total Total number of nodes in the repository\n");
        sb.append("# TYPE nemaki_repository_nodes_total gauge\n");
        sb.append("nemaki_repository_nodes_total{repository=\"").append(repositoryId).append("\"} ")
                .append(nodeCount).append("\n");

        sb.append("# HELP nemaki_repository_documents_total Total number of documents in the repository\n");
        sb.append("# TYPE nemaki_repository_documents_total gauge\n");
        sb.append("nemaki_repository_documents_total{repository=\"").append(repositoryId).append("\"} ")
                .append(documentCount).append("\n");

        sb.append("# HELP nemaki_repository_folders_total Total number of folders in the repository\n");
        sb.append("# TYPE nemaki_repository_folders_total gauge\n");
        sb.append("nemaki_repository_folders_total{repository=\"").append(repositoryId).append("\"} ")
                .append(folderCount).append("\n");
    }

    private void appendJobMetrics(StringBuilder sb, String repositoryId) {
        // TODO: Integrate with actual job queue
        int pendingJobs = 0;
        int runningJobs = 0;

        sb.append("# HELP nemaki_jobs_pending Number of pending jobs\n");
        sb.append("# TYPE nemaki_jobs_pending gauge\n");
        sb.append("nemaki_jobs_pending{repository=\"").append(repositoryId).append("\"} ")
                .append(pendingJobs).append("\n");

        sb.append("# HELP nemaki_jobs_running Number of running jobs\n");
        sb.append("# TYPE nemaki_jobs_running gauge\n");
        sb.append("nemaki_jobs_running{repository=\"").append(repositoryId).append("\"} ")
                .append(runningJobs).append("\n");

        sb.append("# HELP nemaki_jobs_paused Whether jobs are paused (1=paused, 0=running)\n");
        sb.append("# TYPE nemaki_jobs_paused gauge\n");
        sb.append("nemaki_jobs_paused{repository=\"").append(repositoryId).append("\"} ")
                .append(JobControlResource.isJobsPaused() ? 1 : 0).append("\n");
    }

    private void appendGcMetrics(StringBuilder sb) {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        sb.append("# HELP jvm_gc_collection_count_total Total number of GC collections\n");
        sb.append("# TYPE jvm_gc_collection_count_total counter\n");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String gcName = gcBean.getName().replace(" ", "_");
            sb.append("jvm_gc_collection_count_total{gc=\"").append(gcName).append("\"} ")
                    .append(gcBean.getCollectionCount()).append("\n");
        }

        sb.append("# HELP jvm_gc_collection_time_seconds_total Total time spent in GC in seconds\n");
        sb.append("# TYPE jvm_gc_collection_time_seconds_total counter\n");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String gcName = gcBean.getName().replace(" ", "_");
            double timeSeconds = gcBean.getCollectionTime() / 1000.0;
            sb.append("jvm_gc_collection_time_seconds_total{gc=\"").append(gcName).append("\"} ")
                    .append(String.format("%.3f", timeSeconds)).append("\n");
        }
    }
}
