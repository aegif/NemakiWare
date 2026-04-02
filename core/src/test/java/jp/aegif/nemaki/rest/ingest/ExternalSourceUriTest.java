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
    void testEncodesSpecialCharactersInObjectId() {
        String uri = ExternalSourceUri.forFileShare("google_drive", "t1", "file with spaces.txt");
        assertTrue(uri.contains("file+with+spaces.txt") || uri.contains("file%20with%20spaces.txt"),
                "objectId should be URL-encoded: " + uri);
        assertFalse(uri.contains(" "), "Should not contain raw space: " + uri);
    }

    @Test
    void testEncodesSpecialCharactersInTenantId() {
        String uri = ExternalSourceUri.build("notion", "workspace/id", "pages", "p1");
        assertFalse(uri.contains("workspace/id"), "tenantId should be URL-encoded: " + uri);
    }

    @Test
    void testForBusinessRecord() {
        assertEquals("salesforce://tenant/org1/records/Account/r1",
                ExternalSourceUri.forBusinessRecord("salesforce", "org1", "Account", "r1"));
    }

    @Test
    void testForMailMessage() {
        String uri = ExternalSourceUri.forMailMessage("acct1", "INBOX", "msg-stable-1");
        assertTrue(uri.startsWith("mail://tenant/acct1/mailboxes/INBOX/messages/"), uri);
        assertTrue(uri.endsWith("msg-stable-1"), uri);
    }

    @Test
    void testForMailAttachment() {
        String uri = ExternalSourceUri.forMailAttachment("acct1", "INBOX", "msg-1", "att-1");
        assertTrue(uri.contains("mailboxes/INBOX/messages/msg-1/attachments/"), uri);
        assertTrue(uri.endsWith("att-1"), uri);
    }
}
