package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalSourceUriTest {

    @Test
    void testBuildWithAllParts() {
        assertEquals("google_drive://tenant/t1/files/f123",
                ExternalSourceUri.build("google_drive", "t1", "files", "f123"));
    }

    @Test
    void testBuildWithoutTenant() {
        assertEquals("local_fs://docs/d1",
                ExternalSourceUri.build("local_fs", null, "docs", "d1"));
    }

    @Test
    void testBuildWithoutObjectTypePath() {
        assertEquals("custom://tenant/t1/obj1",
                ExternalSourceUri.build("custom", "t1", null, "obj1"));
    }

    @Test
    void testBuildRejectsBlankSourceSystem() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.build("", "t1", "files", "f1"));
    }

    @Test
    void testBuildRejectsBlankObjectId() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.build("google_drive", "t1", "files", ""));
    }

    @Test
    void testForFileShare() {
        assertEquals("onedrive://tenant/t1/files/f1",
                ExternalSourceUri.forFileShare("onedrive", "t1", "f1"));
    }

    @Test
    void testForNotePage() {
        assertEquals("notion://tenant/ws1/pages/p1",
                ExternalSourceUri.forNotePage("notion", "ws1", "p1"));
    }

    @Test
    void testForChatMessage() {
        assertEquals("slack://tenant/ws1/channels/ch1/messages/m1",
                ExternalSourceUri.forChatMessage("slack", "ws1", "ch1", "m1"));
    }

    @Test
    void testForBusinessRecord() {
        assertEquals("salesforce://tenant/org1/records/Account/r1",
                ExternalSourceUri.forBusinessRecord("salesforce", "org1", "Account", "r1"));
    }
}
