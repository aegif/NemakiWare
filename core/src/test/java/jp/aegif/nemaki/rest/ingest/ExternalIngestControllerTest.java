package jp.aegif.nemaki.rest.ingest;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for ExternalIngestController utility methods.
 */
public class ExternalIngestControllerTest {

    @Test
    public void testSanitizeFilename_normal() {
        assertEquals("test.txt", ExternalIngestController.sanitizeFilename("test.txt"));
    }

    @Test
    public void testSanitizeFilename_pathTraversal() {
        assertEquals("evil.txt", ExternalIngestController.sanitizeFilename("../../evil.txt"));
        assertEquals("file.txt", ExternalIngestController.sanitizeFilename("/etc/passwd/../file.txt"));
    }

    @Test
    public void testSanitizeFilename_backslash() {
        assertEquals("doc.pdf", ExternalIngestController.sanitizeFilename("C:\\Users\\admin\\doc.pdf"));
    }

    @Test
    public void testSanitizeFilename_leadingDots() {
        assertEquals("htaccess", ExternalIngestController.sanitizeFilename(".htaccess"));
        assertEquals("hidden", ExternalIngestController.sanitizeFilename("...hidden"));
    }

    @Test
    public void testSanitizeFilename_blank() {
        assertNull(ExternalIngestController.sanitizeFilename(null));
        assertEquals("imported-file", ExternalIngestController.sanitizeFilename("..."));
    }

    @Test
    public void testSanitizeFilename_empty() {
        assertEquals("", ExternalIngestController.sanitizeFilename(""));
    }
}
