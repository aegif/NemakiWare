package jp.aegif.nemaki.archive;

import java.io.InputStream;
import java.util.Map;

/**
 * Adapter interface for long-term (cold) archive storage.
 * Implementations include S3-compatible object stores and local filesystem.
 */
public interface LongTermStorageAdapter {

    /**
     * Store content in long-term storage.
     *
     * @param repositoryId the repository identifier
     * @param objectId     the CMIS object identifier
     * @param content      the content stream to store
     * @param metadata     additional metadata (name, mimeType, etc.)
     * @return a storage reference key (e.g., S3 versionId or filesystem path)
     */
    String put(String repositoryId, String objectId, InputStream content, Map<String, String> metadata);

    /**
     * Retrieve content from long-term storage.
     *
     * @param repositoryId the repository identifier
     * @param objectId     the CMIS object identifier
     * @return the content stream, or null if not found
     */
    InputStream get(String repositoryId, String objectId);

    /**
     * Delete content from long-term storage.
     *
     * @param repositoryId the repository identifier
     * @param objectId     the CMIS object identifier
     */
    void delete(String repositoryId, String objectId);

    /**
     * Delete a specific version of content from long-term storage.
     * On versioned S3 buckets, this targets the exact version uploaded by put().
     *
     * @param repositoryId the repository identifier
     * @param objectId     the CMIS object identifier
     * @param storageRef   the storage reference (versionId) returned by put()
     */
    default void delete(String repositoryId, String objectId, String storageRef) {
        // Default: fall back to non-versioned delete
        delete(repositoryId, objectId);
    }

    /**
     * Check if content exists in long-term storage.
     *
     * @param repositoryId the repository identifier
     * @param objectId     the CMIS object identifier
     * @return true if content exists
     */
    boolean exists(String repositoryId, String objectId);

    /**
     * Attempt to enforce immutability on stored content (e.g., S3 Object Lock, filesystem chattr).
     *
     * @param repositoryId the repository identifier
     * @param objectId     the CMIS object identifier
     */
    void enforceImmutability(String repositoryId, String objectId);


    /**
     * Remove protection (e.g., S3 legal hold) from stored content so it can be deleted.
     * Called before delete() in cleanup paths where enforceImmutability() was already applied.
     *
     * @param repositoryId the repository identifier
     * @param objectId     the CMIS object identifier
     */
    default void removeProtection(String repositoryId, String objectId) {
        // No-op by default (filesystem adapter uses chmod which doesn't block delete)
    }

    /**
     * Check if the storage backend is reachable and writable.
     *
     * @return true if the connection is healthy
     */
    default boolean checkConnection() {
        return false;
    }
}
