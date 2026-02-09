package jp.aegif.nemaki.archive;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * In-memory implementation of LongTermStorageAdapter for testing purposes.
 * Uses ConcurrentHashMap to hold content in memory.
 */
public class InMemoryStorageAdapter implements LongTermStorageAdapter {

    private static final Log log = LogFactory.getLog(InMemoryStorageAdapter.class);

    private final ConcurrentHashMap<String, byte[]> storage = new ConcurrentHashMap<>();

    @Override
    public String put(String repositoryId, String objectId, InputStream content, Map<String, String> metadata) {
        String key = buildKey(repositoryId, objectId);
        try {
            byte[] bytes = content.readAllBytes();
            storage.put(key, bytes);
            log.debug("Stored object in memory: key=" + key + ", size=" + bytes.length);
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Failed to store object in memory: " + key, e);
        }
    }

    @Override
    public InputStream get(String repositoryId, String objectId) {
        String key = buildKey(repositoryId, objectId);
        byte[] bytes = storage.get(key);
        if (bytes == null) {
            return null;
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void delete(String repositoryId, String objectId) {
        String key = buildKey(repositoryId, objectId);
        storage.remove(key);
        log.debug("Deleted object from memory: " + key);
    }

    @Override
    public boolean exists(String repositoryId, String objectId) {
        String key = buildKey(repositoryId, objectId);
        return storage.containsKey(key);
    }

    @Override
    public void enforceImmutability(String repositoryId, String objectId) {
        // No-op for in-memory storage
    }

    @Override
    public boolean checkConnection() {
        return true;
    }

    // ========================================
    // Test Helpers
    // ========================================

    /**
     * Clear all stored content.
     */
    public void clear() {
        storage.clear();
    }

    /**
     * Get the number of stored objects.
     */
    public int getStoredCount() {
        return storage.size();
    }

    /**
     * Get all stored keys.
     */
    public Set<String> getStoredKeys() {
        return storage.keySet();
    }

    private String buildKey(String repositoryId, String objectId) {
        return repositoryId + "/" + objectId + "/content";
    }
}
