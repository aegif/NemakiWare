package jp.aegif.nemaki.api.v1.model.response;

import java.time.Instant;

public class StatsResponse {
    private RepositoryStats repository;
    private JvmStats jvm;
    private String timestamp;

    public StatsResponse() {
        this.timestamp = Instant.now().toString();
    }

    public RepositoryStats getRepository() {
        return repository;
    }

    public void setRepository(RepositoryStats repository) {
        this.repository = repository;
    }

    public JvmStats getJvm() {
        return jvm;
    }

    public void setJvm(JvmStats jvm) {
        this.jvm = jvm;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public static class RepositoryStats {
        private String repositoryId;
        private long nodeCount;
        private long documentCount;
        private long folderCount;

        public String getRepositoryId() {
            return repositoryId;
        }

        public void setRepositoryId(String repositoryId) {
            this.repositoryId = repositoryId;
        }

        public long getNodeCount() {
            return nodeCount;
        }

        public void setNodeCount(long nodeCount) {
            this.nodeCount = nodeCount;
        }

        public long getDocumentCount() {
            return documentCount;
        }

        public void setDocumentCount(long documentCount) {
            this.documentCount = documentCount;
        }

        public long getFolderCount() {
            return folderCount;
        }

        public void setFolderCount(long folderCount) {
            this.folderCount = folderCount;
        }
    }

    public static class JvmStats {
        private long heapUsed;
        private long heapMax;
        private long heapCommitted;
        private int threadCount;
        private long uptimeMs;
        private int availableProcessors;

        public long getHeapUsed() {
            return heapUsed;
        }

        public void setHeapUsed(long heapUsed) {
            this.heapUsed = heapUsed;
        }

        public long getHeapMax() {
            return heapMax;
        }

        public void setHeapMax(long heapMax) {
            this.heapMax = heapMax;
        }

        public long getHeapCommitted() {
            return heapCommitted;
        }

        public void setHeapCommitted(long heapCommitted) {
            this.heapCommitted = heapCommitted;
        }

        public int getThreadCount() {
            return threadCount;
        }

        public void setThreadCount(int threadCount) {
            this.threadCount = threadCount;
        }

        public long getUptimeMs() {
            return uptimeMs;
        }

        public void setUptimeMs(long uptimeMs) {
            this.uptimeMs = uptimeMs;
        }

        public int getAvailableProcessors() {
            return availableProcessors;
        }

        public void setAvailableProcessors(int availableProcessors) {
            this.availableProcessors = availableProcessors;
        }
    }
}
