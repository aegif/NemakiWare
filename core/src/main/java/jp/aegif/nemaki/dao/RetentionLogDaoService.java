package jp.aegif.nemaki.dao;

import java.util.List;

import jp.aegif.nemaki.archive.RetentionMigrationLog;

/**
 * DAO Service interface for retention migration log persistence.
 */
public interface RetentionLogDaoService {

    /**
     * Create a new migration log entry.
     *
     * @param repositoryId The repository ID
     * @param log The migration log to create
     * @return The created log with ID assigned
     */
    RetentionMigrationLog createLog(String repositoryId, RetentionMigrationLog log);

    /**
     * Get recent migration logs for a repository.
     *
     * @param repositoryId The repository ID
     * @param limit Maximum number of logs to return
     * @return List of migration logs, ordered by startedAt descending
     */
    List<RetentionMigrationLog> getLogs(String repositoryId, int limit);

    /**
     * Delete old logs, keeping only the most recent ones.
     *
     * @param repositoryId The repository ID
     * @param keepCount Number of recent logs to keep
     * @return Number of deleted logs
     */
    int deleteOldLogs(String repositoryId, int keepCount);
}
