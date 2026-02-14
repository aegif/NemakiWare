package jp.aegif.nemaki.dao.impl.couch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.archive.RetentionMigrationLog;
import jp.aegif.nemaki.dao.RetentionLogDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.model.couch.CouchRetentionMigrationLog;

/**
 * CouchDB implementation of RetentionLogDaoService.
 */
public class RetentionLogDaoServiceImpl implements RetentionLogDaoService {

    private static final Log log = LogFactory.getLog(RetentionLogDaoServiceImpl.class);

    private CloudantClientPool connectorPool;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    @Override
    public RetentionMigrationLog createLog(String repositoryId, RetentionMigrationLog migrationLog) {
        if (migrationLog == null) {
            return null;
        }

        try {
            CouchRetentionMigrationLog couchLog = new CouchRetentionMigrationLog(migrationLog);
            connectorPool.getClient(repositoryId).create(couchLog);

            log.debug("Created retention migration log: " + couchLog.getId());
            return couchLog.convertToMigrationLog();
        } catch (Exception e) {
            log.error("Failed to create retention migration log: " + e.getMessage(), e);
            throw new RuntimeException("Failed to create retention migration log", e);
        }
    }

    @Override
    public List<RetentionMigrationLog> getLogs(String repositoryId, int limit) {
        List<RetentionMigrationLog> logs = new ArrayList<>();

        try {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("include_docs", true);
            queryParams.put("limit", Math.min(limit, 100));
            queryParams.put("descending", true);

            ViewResult result = connectorPool.getClient(repositoryId)
                .queryView("_repo", "retentionMigrationLogs", queryParams);

            if (result != null && result.getRows() != null) {
                for (ViewResultRow row : result.getRows()) {
                    if (row.getDoc() != null) {
                        Map<String, Object> docMap = extractDocMap(row.getDoc());
                        if (docMap != null) {
                            CouchRetentionMigrationLog couchLog = new CouchRetentionMigrationLog(docMap);
                            logs.add(couchLog.convertToMigrationLog());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to get retention migration logs: " + e.getMessage(), e);
        }

        return logs;
    }

    @Override
    public int deleteOldLogs(String repositoryId, int keepCount) {
        int deletedCount = 0;

        try {
            // Get all logs in descending order
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("include_docs", true);
            queryParams.put("descending", true);
            queryParams.put("limit", 1000);

            ViewResult result = connectorPool.getClient(repositoryId)
                .queryView("_repo", "retentionMigrationLogs", queryParams);

            if (result != null && result.getRows() != null) {
                List<ViewResultRow> rows = result.getRows();

                // Skip the first keepCount entries, delete the rest
                for (int i = keepCount; i < rows.size(); i++) {
                    ViewResultRow row = rows.get(i);
                    if (row.getDoc() != null) {
                        Map<String, Object> docMap = extractDocMap(row.getDoc());
                        if (docMap != null) {
                            String id = (String) docMap.get("_id");
                            String rev = (String) docMap.get("_rev");
                            if (id != null && rev != null) {
                                try {
                                    connectorPool.getClient(repositoryId).delete(id, rev);
                                    deletedCount++;
                                } catch (Exception e) {
                                    log.warn("Failed to delete old retention log: " + id);
                                }
                            }
                        }
                    }
                }
            }

            if (deletedCount > 0) {
                log.info("Deleted " + deletedCount + " old retention migration logs for " + repositoryId);
            }
        } catch (Exception e) {
            log.error("Failed to delete old retention migration logs: " + e.getMessage(), e);
        }

        return deletedCount;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDocMap(Object docObj) {
        if (docObj instanceof Map) {
            return (Map<String, Object>) docObj;
        } else if (docObj instanceof com.ibm.cloud.cloudant.v1.model.Document) {
            com.ibm.cloud.cloudant.v1.model.Document doc = (com.ibm.cloud.cloudant.v1.model.Document) docObj;
            Map<String, Object> docMap = new HashMap<>(doc.getProperties());
            if (doc.getId() != null) {
                docMap.put("_id", doc.getId());
            }
            if (doc.getRev() != null) {
                docMap.put("_rev", doc.getRev());
            }
            return docMap;
        }
        return null;
    }
}
