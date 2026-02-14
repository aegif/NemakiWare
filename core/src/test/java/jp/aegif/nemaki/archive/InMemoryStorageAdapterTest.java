package jp.aegif.nemaki.archive;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class InMemoryStorageAdapterTest {

    private InMemoryStorageAdapter adapter;

    @Before
    public void setUp() {
        adapter = new InMemoryStorageAdapter();
    }

    @Test
    public void testPutAndGet() throws Exception {
        String content = "Hello, NemakiWare!";
        InputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("name", "test.txt");
        metadata.put("mimeType", "text/plain");

        String ref = adapter.put("bedroom", "obj001", input, metadata);
        assertNotNull(ref);

        InputStream result = adapter.get("bedroom", "obj001");
        assertNotNull(result);
        assertEquals(content, new String(result.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void testPutOverwrite() throws Exception {
        adapter.put("bedroom", "obj001",
                new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)), null);
        adapter.put("bedroom", "obj001",
                new ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8)), null);

        InputStream result = adapter.get("bedroom", "obj001");
        assertNotNull(result);
        assertEquals("second", new String(result.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void testGetNonExistent() {
        InputStream result = adapter.get("bedroom", "nonexistent");
        assertNull(result);
    }

    @Test
    public void testDelete() {
        adapter.put("bedroom", "obj001",
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), null);
        assertTrue(adapter.exists("bedroom", "obj001"));

        adapter.delete("bedroom", "obj001");
        assertFalse(adapter.exists("bedroom", "obj001"));
    }

    @Test
    public void testDeleteNonExistent() {
        // Should not throw
        adapter.delete("bedroom", "nonexistent");
    }

    @Test
    public void testExists() {
        assertFalse(adapter.exists("bedroom", "obj001"));

        adapter.put("bedroom", "obj001",
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), null);
        assertTrue(adapter.exists("bedroom", "obj001"));
    }

    @Test
    public void testEnforceImmutability() {
        adapter.put("bedroom", "obj001",
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), null);
        // Should not throw (no-op)
        adapter.enforceImmutability("bedroom", "obj001");
        // Content should still be accessible
        assertNotNull(adapter.get("bedroom", "obj001"));
    }

    @Test
    public void testCheckConnection() {
        assertTrue(adapter.checkConnection());
    }

    @Test
    public void testClear() {
        adapter.put("bedroom", "obj001",
                new ByteArrayInputStream("data1".getBytes(StandardCharsets.UTF_8)), null);
        adapter.put("bedroom", "obj002",
                new ByteArrayInputStream("data2".getBytes(StandardCharsets.UTF_8)), null);
        assertEquals(2, adapter.getStoredCount());

        adapter.clear();
        assertEquals(0, adapter.getStoredCount());
        assertFalse(adapter.exists("bedroom", "obj001"));
    }

    @Test
    public void testMultipleRepositories() throws Exception {
        adapter.put("bedroom", "obj001",
                new ByteArrayInputStream("bedroom-data".getBytes(StandardCharsets.UTF_8)), null);
        adapter.put("canopy", "obj001",
                new ByteArrayInputStream("canopy-data".getBytes(StandardCharsets.UTF_8)), null);

        assertEquals(2, adapter.getStoredCount());

        InputStream bedroomResult = adapter.get("bedroom", "obj001");
        assertEquals("bedroom-data", new String(bedroomResult.readAllBytes(), StandardCharsets.UTF_8));

        InputStream canopyResult = adapter.get("canopy", "obj001");
        assertEquals("canopy-data", new String(canopyResult.readAllBytes(), StandardCharsets.UTF_8));

        // Delete from one repository should not affect the other
        adapter.delete("bedroom", "obj001");
        assertFalse(adapter.exists("bedroom", "obj001"));
        assertTrue(adapter.exists("canopy", "obj001"));
    }

    @Test
    public void testLargeContent() throws Exception {
        // 1MB content
        byte[] largeContent = new byte[1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        adapter.put("bedroom", "large-obj",
                new ByteArrayInputStream(largeContent), null);

        InputStream result = adapter.get("bedroom", "large-obj");
        assertNotNull(result);
        byte[] retrieved = result.readAllBytes();
        assertArrayEquals(largeContent, retrieved);
    }

    @Test
    public void testMetadataIgnored() throws Exception {
        // metadata=null should work fine
        String content = "test content";
        adapter.put("bedroom", "obj001",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), null);

        InputStream result = adapter.get("bedroom", "obj001");
        assertNotNull(result);
        assertEquals(content, new String(result.readAllBytes(), StandardCharsets.UTF_8));
    }
}
