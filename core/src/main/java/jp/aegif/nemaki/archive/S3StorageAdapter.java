package jp.aegif.nemaki.archive;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHold;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.PutObjectLegalHoldRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * S3-compatible long-term storage adapter.
 * Supports AWS S3, MinIO, and other S3-compatible services.
 */
public class S3StorageAdapter implements LongTermStorageAdapter {

    private static final Log log = LogFactory.getLog(S3StorageAdapter.class);

    private final S3Client s3Client;
    private final String bucket;
    private final boolean objectLockEnabled;

    public S3StorageAdapter(String bucket, String region, String endpoint,
                            String accessKey, String secretKey, boolean legalHoldEnabled) {
        this.bucket = bucket;
        this.objectLockEnabled = legalHoldEnabled;

        S3ClientBuilder builder = S3Client.builder();

        // Use explicit credentials if provided, otherwise fall back to the default
        // AWS credential chain (environment variables, instance profile, ECS task role, etc.)
        if (accessKey != null && !accessKey.trim().isEmpty()
                && secretKey != null && !secretKey.trim().isEmpty()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        if (region != null && !region.isEmpty()) {
            builder.region(Region.of(region));
        }
        if (endpoint != null && !endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
            builder.forcePathStyle(true);
        }

        this.s3Client = builder.build();
        log.info("S3StorageAdapter initialized: bucket=" + bucket + ", region=" + region
                + ", legalHold=" + legalHoldEnabled
                + ", credentials=" + (accessKey != null && !accessKey.trim().isEmpty() ? "explicit" : "default-chain"));
    }

    @Override
    public String put(String repositoryId, String objectId, InputStream content, Map<String, String> metadata) {
        String key = buildKey(repositoryId, objectId);

        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key);

        if (metadata != null) {
            requestBuilder.metadata(metadata);
        }

        // Use temp file to avoid buffering entire content in memory (OOM risk for large files)
        java.nio.file.Path tempFile = null;
        try {
            tempFile = java.nio.file.Files.createTempFile("nemaki-s3-", ".tmp");
            long size = java.nio.file.Files.copy(content, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            PutObjectResponse response = s3Client.putObject(
                    requestBuilder.build(),
                    RequestBody.fromFile(tempFile));

            String versionId = response.versionId();
            log.info("Stored object in S3: key=" + key + ", versionId=" + versionId
                    + ", size=" + size);
            return versionId != null ? versionId : key;
        } catch (Exception e) {
            throw new RuntimeException("Failed to store object in S3: " + key, e);
        } finally {
            if (tempFile != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    log.warn("Failed to delete temp file: " + tempFile);
                }
            }
        }
    }

    @Override
    public InputStream get(String repositoryId, String objectId) {
        String key = buildKey(repositoryId, objectId);
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            log.warn("Object not found in S3: " + key);
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve object from S3: " + key, e);
        }
    }

    @Override
    public void delete(String repositoryId, String objectId) {
        String key = buildKey(repositoryId, objectId);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            log.info("Deleted object from S3: " + key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete object from S3: " + key, e);
        }
    }

    @Override
    public void delete(String repositoryId, String objectId, String storageRef) {
        String key = buildKey(repositoryId, objectId);
        try {
            DeleteObjectRequest.Builder builder = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key);
            // If storageRef looks like a versionId (not a key path), use it to target the specific version
            if (storageRef != null && !storageRef.equals(key) && !storageRef.contains("/")) {
                builder.versionId(storageRef);
                log.info("Deleting specific S3 version: key=" + key + ", versionId=" + storageRef);
            }
            s3Client.deleteObject(builder.build());
            log.info("Deleted object from S3: key=" + key + ", storageRef=" + storageRef);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete object from S3: key=" + key + ", storageRef=" + storageRef, e);
        }
    }

    @Override
    public boolean exists(String repositoryId, String objectId) {
        String key = buildKey(repositoryId, objectId);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.warn("Error checking existence in S3: " + key + " - " + e.getMessage());
            return false;
        }
    }

    @Override
    public void enforceImmutability(String repositoryId, String objectId) {
        if (!objectLockEnabled) {
            log.debug("Object Lock not enabled, skipping immutability enforcement");
            return;
        }

        String key = buildKey(repositoryId, objectId);
        try {
            s3Client.putObjectLegalHold(PutObjectLegalHoldRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .legalHold(ObjectLockLegalHold.builder()
                            .status(ObjectLockLegalHoldStatus.ON)
                            .build())
                    .build());
            log.info("Enforced legal hold on S3 object: " + key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to enforce legal hold on S3 object: " + key, e);
        }
    }

    @Override
    public void removeProtection(String repositoryId, String objectId) {
        if (!objectLockEnabled) {
            return;
        }

        String key = buildKey(repositoryId, objectId);
        try {
            s3Client.putObjectLegalHold(PutObjectLegalHoldRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .legalHold(ObjectLockLegalHold.builder()
                            .status(ObjectLockLegalHoldStatus.OFF)
                            .build())
                    .build());
            log.info("Removed legal hold from S3 object: " + key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove legal hold from S3 object: " + key, e);
        }
    }

    @Override
    public boolean checkConnection() {
        try {
            s3Client.headBucket(software.amazon.awssdk.services.s3.model.HeadBucketRequest.builder()
                    .bucket(bucket)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("S3 connection check failed: " + e.getMessage());
            return false;
        }
    }

    private String buildKey(String repositoryId, String objectId) {
        return repositoryId + "/" + objectId + "/content";
    }
}
