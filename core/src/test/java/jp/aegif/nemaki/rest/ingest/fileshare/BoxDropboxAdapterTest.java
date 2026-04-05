package jp.aegif.nemaki.rest.ingest.fileshare;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Box and Dropbox adapter record types.
 */
class BoxDropboxAdapterTest {

    @Test
    void testBoxFileRecordMapping() {
        var file = new BoxConnectorAdapter.BoxFile(
                "12345", "report.xlsx", "file", 2048,
                "2024-01-15T10:30:00-08:00", "0");
        assertEquals("12345", file.id());
        assertEquals("report.xlsx", file.name());
        assertEquals("file", file.type());
        assertEquals(2048, file.size());
        assertEquals("0", file.parentId());
        assertNotNull(file.modifiedAt());
    }

    @Test
    void testBoxFileNullParent() {
        var file = new BoxConnectorAdapter.BoxFile("1", "doc.pdf", "file", 100, null, null);
        assertNull(file.modifiedAt());
        assertNull(file.parentId());
    }

    @Test
    void testDropboxFileRecordMapping() {
        var file = new DropboxConnectorAdapter.DropboxFile(
                "id:abc123", "notes.md", "/Documents/notes.md",
                512, "2024-01-15T10:30:00Z");
        assertEquals("id:abc123", file.id());
        assertEquals("notes.md", file.name());
        assertEquals("/Documents/notes.md", file.pathDisplay());
        assertEquals(512, file.size());
        assertEquals("2024-01-15T10:30:00Z", file.serverModified());
    }

    @Test
    void testDropboxFileNullTimestamp() {
        var file = new DropboxConnectorAdapter.DropboxFile("id:x", "f.txt", "/f.txt", 0, null);
        assertNull(file.serverModified());
        assertEquals(0, file.size());
    }
}
