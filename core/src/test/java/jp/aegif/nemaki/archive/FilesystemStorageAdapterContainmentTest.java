package jp.aegif.nemaki.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FilesystemStorageAdapter} path containment (3.2.1
 * defence-in-depth). {@code repositoryId} / {@code objectId} are system
 * generated in production, but the resolved storage path must never escape the
 * configured base directory even if a traversal token is ever passed.
 */
class FilesystemStorageAdapterContainmentTest {

    @Test
    void normalPutGetRoundTrip_staysUnderBase(@TempDir Path base) throws Exception {
        FilesystemStorageAdapter adapter = new FilesystemStorageAdapter(base.toString());
        byte[] data = "archived-bytes".getBytes();

        String stored = adapter.put("bedroom", "0123456789abcdef", stream(data), Map.of());
        // The stored file is under the configured base directory.
        assertTrue(Path.of(stored).normalize().startsWith(base.toAbsolutePath().normalize()));
        assertTrue(adapter.exists("bedroom", "0123456789abcdef"));

        try (InputStream in = adapter.get("bedroom", "0123456789abcdef")) {
            assertArrayEquals(data, in.readAllBytes());
        }

        adapter.delete("bedroom", "0123456789abcdef");
        assertFalse(adapter.exists("bedroom", "0123456789abcdef"));
    }

    @Test
    void traversalObjectId_isRejected(@TempDir Path base) {
        FilesystemStorageAdapter adapter = new FilesystemStorageAdapter(base.toString());
        assertThrows(IllegalArgumentException.class,
                () -> adapter.exists("bedroom", "../../../../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.put("bedroom", "../../../../etc/passwd", stream(new byte[1]), Map.of()));
    }

    @Test
    void traversalRepositoryId_isRejected(@TempDir Path base) {
        FilesystemStorageAdapter adapter = new FilesystemStorageAdapter(base.toString());
        assertThrows(IllegalArgumentException.class,
                () -> adapter.exists("../../evil", "0123456789abcdef"));
    }

    @Test
    void containmentDoesNotBreakNestedRepoName(@TempDir Path base) throws Exception {
        // A normal repository name + UUID resolves cleanly under the base and
        // must NOT be rejected.
        FilesystemStorageAdapter adapter = new FilesystemStorageAdapter(base.toString());
        adapter.put("canopy", "fedcba9876543210", stream("x".getBytes()), Map.of());
        assertTrue(adapter.exists("canopy", "fedcba9876543210"));
        assertTrue(Files.exists(base.resolve("canopy").resolve("fedcba9876543210").resolve("content")));
    }

    private static InputStream stream(byte[] b) {
        return new ByteArrayInputStream(b);
    }
}
