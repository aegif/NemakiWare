package jp.aegif.nemaki.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Filesystem-based long-term storage adapter.
 * Stores archived content on the local filesystem with optional immutability via read-only permissions.
 */
public class FilesystemStorageAdapter implements LongTermStorageAdapter {

    private static final Log log = LogFactory.getLog(FilesystemStorageAdapter.class);

    private final String basePath;

    public FilesystemStorageAdapter(String basePath) {
        this.basePath = basePath;
        log.info("FilesystemStorageAdapter initialized: basePath=" + basePath);
    }

    @Override
    public String put(String repositoryId, String objectId, InputStream content, Map<String, String> metadata) {
        Path filePath = buildPath(repositoryId, objectId);

        try {
            Files.createDirectories(filePath.getParent());
            Files.copy(content, filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Stored content to filesystem: " + filePath);
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to store content to filesystem: " + filePath, e);
        }
    }

    @Override
    public InputStream get(String repositoryId, String objectId) {
        Path filePath = buildPath(repositoryId, objectId);
        try {
            if (!Files.exists(filePath)) {
                log.warn("Content not found on filesystem: " + filePath);
                return null;
            }
            return Files.newInputStream(filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read content from filesystem: " + filePath, e);
        }
    }

    @Override
    public void delete(String repositoryId, String objectId) {
        Path filePath = buildPath(repositoryId, objectId);
        try {
            // Remove read-only before delete
            if (Files.exists(filePath)) {
                filePath.toFile().setWritable(true);
                Files.delete(filePath);
                log.info("Deleted content from filesystem: " + filePath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete content from filesystem: " + filePath, e);
        }
    }

    @Override
    public boolean exists(String repositoryId, String objectId) {
        return Files.exists(buildPath(repositoryId, objectId));
    }

    @Override
    public void enforceImmutability(String repositoryId, String objectId) {
        Path filePath = buildPath(repositoryId, objectId);
        try {
            if (Files.exists(filePath)) {
                // Set read-only permissions (chmod 444 equivalent)
                boolean success = filePath.toFile().setReadOnly();
                if (success) {
                    log.info("Set read-only on archived content: " + filePath);
                } else {
                    log.warn("Failed to set read-only on archived content: " + filePath);
                }

                // Best-effort chattr +i (Linux only)
                try {
                    ProcessBuilder pb = new ProcessBuilder("chattr", "+i", filePath.toString());
                    Process process = pb.start();
                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        log.info("Applied chattr +i on archived content: " + filePath);
                    }
                    // Silently ignore failures - chattr requires root and may not be available
                } catch (Exception e) {
                    log.debug("chattr +i not available: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enforce immutability on filesystem: " + filePath + " - " + e.getMessage());
        }
    }

    @Override
    public boolean checkConnection() {
        try {
            Path base = Paths.get(basePath);
            if (!Files.exists(base)) {
                Files.createDirectories(base);
            }
            return Files.isWritable(base);
        } catch (Exception e) {
            log.warn("Filesystem connection check failed: " + e.getMessage());
            return false;
        }
    }

    private Path buildPath(String repositoryId, String objectId) {
        return Paths.get(basePath, repositoryId, objectId, "content");
    }
}
