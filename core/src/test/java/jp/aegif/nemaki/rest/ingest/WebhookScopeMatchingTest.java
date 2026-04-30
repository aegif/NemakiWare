package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Graph webhook resource scope parsing and default behaviors.
 * Complements IngestWebhookGraphValidationTest with focus on userId/folderId defaults.
 */
class WebhookScopeMatchingTest {

    // ── parseGraphResourceScope defaults ──

    @Test
    void m365Mail_withFolderAndUser_parsesAll() {
        var scope = IngestWebhookController.parseGraphResourceScope(
                "users/admin@contoso.com/mailFolders('inbox')/messages");
        assertEquals("admin@contoso.com", scope.get("userId"));
        assertEquals("inbox", scope.get("folderId"));
    }

    @Test
    void m365Mail_withoutFolder_defaultsToInbox() {
        var scope = IngestWebhookController.parseGraphResourceScope(
                "users/admin@contoso.com/messages");
        assertEquals("admin@contoso.com", scope.get("userId"));
        assertEquals("inbox", scope.get("folderId"));
    }

    @Test
    void m365Mail_meEndpoint_noUserId() {
        // /me subscriptions don't have users/xxx in the resource path
        var scope = IngestWebhookController.parseGraphResourceScope(
                "me/mailFolders('sentitems')/messages");
        assertFalse(scope.containsKey("userId"), "Should not have userId for /me");
        assertEquals("sentitems", scope.get("folderId"));
    }

    @Test
    void m365Mail_meWithoutFolder_defaultsInbox() {
        var scope = IngestWebhookController.parseGraphResourceScope(
                "me/messages");
        assertFalse(scope.containsKey("userId"));
        // /me without mailFolder should NOT default to inbox (no userId trigger)
        assertFalse(scope.containsKey("folderId"), "No userId means no inbox default");
    }

    @Test
    void teams_parsesTeamAndChannel() {
        var scope = IngestWebhookController.parseGraphResourceScope(
                "teams('T01')/channels('C01')/messages");
        assertEquals("T01", scope.get("teamId"));
        assertEquals("C01", scope.get("channelId"));
        assertFalse(scope.containsKey("userId"));
        assertFalse(scope.containsKey("folderId"));
    }

    @Test
    void nullResource_returnsEmpty() {
        assertTrue(IngestWebhookController.parseGraphResourceScope(null).isEmpty());
    }

    @Test
    void emptyResource_returnsEmpty() {
        assertTrue(IngestWebhookController.parseGraphResourceScope("").isEmpty());
    }

    // ── userId special encoding ──

    @Test
    void m365Mail_encodedUserId_decoded() {
        var scope = IngestWebhookController.parseGraphResourceScope(
                "users/user%40example.com/mailFolders('inbox')/messages");
        assertEquals("user@example.com", scope.get("userId"));
    }

    @Test
    void m365Mail_customFolder() {
        var scope = IngestWebhookController.parseGraphResourceScope(
                "users/admin@co.com/mailFolders('AAMkAGI2...')/messages");
        assertEquals("admin@co.com", scope.get("userId"));
        assertEquals("AAMkAGI2...", scope.get("folderId"));
    }
}
