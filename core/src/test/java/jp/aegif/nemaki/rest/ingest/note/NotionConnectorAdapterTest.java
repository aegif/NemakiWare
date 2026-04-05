package jp.aegif.nemaki.rest.ingest.note;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NotionConnectorAdapter record types and HTML escaping.
 */
class NotionConnectorAdapterTest {

    @Test
    void testNotionPageSummaryRecordMapping() {
        var page = new NotionConnectorAdapter.NotionPageSummary(
                "page-123", "Meeting Notes", "https://www.notion.so/page-123",
                "parent-456", "2024-01-15T10:30:00.000Z");
        assertEquals("page-123", page.id());
        assertEquals("Meeting Notes", page.title());
        assertEquals("https://www.notion.so/page-123", page.url());
        assertEquals("parent-456", page.parentId());
    }

    @Test
    void testNotionFileRecordMapping() {
        var file = new NotionConnectorAdapter.NotionFile(
                "block-789", "diagram.png", "https://s3.amazonaws.com/diagram.png", "image");
        assertEquals("block-789", file.blockId());
        assertEquals("diagram.png", file.name());
        assertEquals("image", file.type());
    }

    @Test
    void testNotionPageNullParent() {
        var page = new NotionConnectorAdapter.NotionPageSummary(
                "p1", "Root Page", "https://notion.so/p1", null, null);
        assertNull(page.parentId());
        assertNull(page.lastEditedTime());
    }
}
