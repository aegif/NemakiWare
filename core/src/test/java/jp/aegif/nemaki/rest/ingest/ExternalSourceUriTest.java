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

    /**
     * A {@code ?} or {@code #} in an encoded segment must be rejected BEFORE encoding — encoding
     * first would turn it into {@code %3F}/{@code %23} and slip a URL-shaped value past
     * ExternalAssetIdentity.parse's literal-character checks, embedding a signed URL reversibly
     * in a qualified name. Pre-encoded forms are the same smell one level down.
     */
    @Test
    void testRejectsQueryAndFragmentDelimitersInRawIds() {
        IllegalArgumentException q = assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.forFileShare("google_drive", "t1",
                        "https://drive.example/file?sig=SECRET"));
        assertTrue(q.getMessage().contains("query"), q.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.build("custom", "t1", "objs", "id#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.build("custom", "t1", "objs", "?leading"),
                "a delimiter at position 0 is still a delimiter (indexOf >= 0, not > 0)");
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.build("custom", "t1", "objs", "#leading"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.build("custom", "t1?x=1", "objs", "id-1"),
                "tenantId is encoded too, so it gets the same pre-encoding check");
    }

    @Test
    void testRejectsPreEncodedDelimitersInRawIds() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.build("custom", "t1", "objs", "file%3Fsig%3DSECRET"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.build("custom", "t1", "objs", "file%3fsig"),
                "case-insensitive");
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.build("custom", "t1", "objs", "file%23frag"));
    }

    /**
     * Mailbox names are arbitrary IMAP {@code astring}s (RFC 9051 §9): {@code #news.…} (the
     * §5.1.2.1 namespace convention) and even {@code Questions?} are legal names that must
     * encode, not reject — a rejection here silently loses every lineage fact of that mailbox
     * while imports keep succeeding. Only a URI scheme marks a value as a URL; message ids
     * keep the strict object-id policy.
     */
    @Test
    void testMailboxNamesPermitTheImapAlphabet() {
        String uri = ExternalSourceUri.forMailMessage("imap", "acct1",
                "#news.comp.mail.misc", "msg-1");
        assertTrue(uri.contains("mailboxes/%23news.comp.mail.misc/messages/"), uri);

        String att = ExternalSourceUri.forMailAttachment("imap", "acct1",
                "#shared/team", "msg-1", "att-1");
        assertTrue(att.contains("%23shared"), att);

        String question = ExternalSourceUri.forMailMessage("imap", "acct1",
                "Questions?", "msg-1");
        assertTrue(question.contains("mailboxes/Questions%3F/messages/"), question);
    }

    @Test
    void testMailboxNamesStillRejectUrlShapedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.forMailMessage("imap", "acct1",
                        "https://mail.example/?sig=SECRET", "msg-1"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.forMailMessage("imap", "acct1",
                        "https://mail.example/#access_token=SECRET", "msg-1"),
                "a scheme is the URL mark that survives even without a query string");
    }

    @Test
    void testMessageIdsKeepTheStrictObjectIdPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalSourceUri.forMailAttachment("imap", "acct1", "INBOX",
                        "msg#1", "att-1"));
    }

    @Test
    void testForBusinessRecord() {
        assertEquals("salesforce://tenant/org1/records/Account/r1",
                ExternalSourceUri.forBusinessRecord("salesforce", "org1", "Account", "r1"));
    }

    @Test
    void testForMailMessage() {
        String uri = ExternalSourceUri.forMailMessage("imap", "acct1", "INBOX", "msg-stable-1");
        assertTrue(uri.startsWith("imap://tenant/acct1/mailboxes/INBOX/messages/"), uri);
        assertTrue(uri.endsWith("msg-stable-1"), uri);
    }

    @Test
    void testForMailMessagePreservesSourceSystem() {
        String gmailUri = ExternalSourceUri.forMailMessage("gmail_mail", "acct1", "INBOX", "msg-1");
        assertTrue(gmailUri.startsWith("gmail_mail://"), gmailUri);

        String m365Uri = ExternalSourceUri.forMailMessage("m365_mail", "acct1", "INBOX", "msg-1");
        assertTrue(m365Uri.startsWith("m365_mail://"), m365Uri);
    }

    @Test
    void testForMailAttachment() {
        String uri = ExternalSourceUri.forMailAttachment("imap", "acct1", "INBOX", "msg-1", "att-1");
        assertTrue(uri.contains("mailboxes/INBOX/messages/msg-1/attachments/"), uri);
        assertTrue(uri.endsWith("att-1"), uri);
        assertTrue(uri.startsWith("imap://"), uri);
    }
}
