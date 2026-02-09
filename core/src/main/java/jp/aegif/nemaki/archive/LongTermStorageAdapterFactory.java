package jp.aegif.nemaki.archive;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * Factory for creating the appropriate LongTermStorageAdapter based on configuration.
 * Reads longterm.storage.type from nemakiware.properties and creates the corresponding adapter.
 */
public class LongTermStorageAdapterFactory {

    private static final Log log = LogFactory.getLog(LongTermStorageAdapterFactory.class);

    private PropertyManager propertyManager;
    private LongTermStorageAdapter adapter;

    public void init() {
        String storageType = propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE);

        if ("s3".equalsIgnoreCase(storageType)) {
            String bucket = propertyManager.readValue(PropertyKey.LONGTERM_S3_BUCKET);
            String region = propertyManager.readValue(PropertyKey.LONGTERM_S3_REGION);
            String endpoint = propertyManager.readValue(PropertyKey.LONGTERM_S3_ENDPOINT);
            String accessKey = propertyManager.readValue(PropertyKey.LONGTERM_S3_ACCESS_KEY);
            String secretKey = propertyManager.readValue(PropertyKey.LONGTERM_S3_SECRET_KEY);
            String objectLockMode = propertyManager.readValue(PropertyKey.LONGTERM_S3_OBJECT_LOCK_MODE);

            if (bucket == null || bucket.isEmpty()) {
                log.error("S3 storage type configured but longterm.s3.bucket is not set");
                return;
            }

            adapter = new S3StorageAdapter(bucket, region, endpoint, accessKey, secretKey, objectLockMode);
            log.info("[BETA] Long-term storage adapter initialized: S3 (bucket=" + bucket + ")");
        } else if ("inmemory".equalsIgnoreCase(storageType)) {
            adapter = new InMemoryStorageAdapter();
            log.info("Long-term storage adapter initialized: in-memory (testing only)");
        } else {
            // Default to filesystem
            String path = propertyManager.readValue(PropertyKey.LONGTERM_FS_PATH);
            if (path == null || path.isEmpty()) {
                path = "/var/nemakiware/cold-archive";
            }
            adapter = new FilesystemStorageAdapter(path);
            log.info("Long-term storage adapter initialized: filesystem (path=" + path + ")");
        }
    }

    public LongTermStorageAdapter getAdapter() {
        return adapter;
    }

    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }
}
