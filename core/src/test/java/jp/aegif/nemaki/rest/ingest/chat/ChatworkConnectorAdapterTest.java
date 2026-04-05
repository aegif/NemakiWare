package jp.aegif.nemaki.rest.ingest.chat;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChatworkConnectorAdapter record types and edge cases.
 */
class ChatworkConnectorAdapterTest {

    @Test
    void testChatworkRoomRecordMapping() {
        var room = new ChatworkConnectorAdapter.ChatworkRoom("123", "General", "group", 42, 5);
        assertEquals("123", room.id());
        assertEquals("General", room.name());
        assertEquals("group", room.type());
        assertEquals(42, room.messageNum());
        assertEquals(5, room.fileNum());
    }

    @Test
    void testChatworkMessageRecordMapping() {
        var msg = new ChatworkConnectorAdapter.ChatworkMessage(
                "msg-001", "acct-123", "Test User",
                "Hello from Chatwork", 1700000000L, 1700000100L);
        assertEquals("msg-001", msg.messageId());
        assertEquals("acct-123", msg.accountId());
        assertEquals("Test User", msg.accountName());
        assertEquals("Hello from Chatwork", msg.body());
        assertEquals(1700000000L, msg.sendTime());
        assertEquals(1700000100L, msg.updateTime());
    }

    @Test
    void testChatworkFileRecordMapping() {
        var file = new ChatworkConnectorAdapter.ChatworkFile(
                "file-001", "acct-123", "report.pdf", 1024, null);
        assertEquals("file-001", file.fileId());
        assertEquals("acct-123", file.accountId());
        assertEquals("report.pdf", file.name());
        assertEquals(1024, file.size());
        assertNull(file.downloadUrl());
    }

    @Test
    void testChatworkMessageEmptyBody() {
        var msg = new ChatworkConnectorAdapter.ChatworkMessage(
                "msg-002", "acct-456", "User2", "", 0, 0);
        assertEquals("", msg.body());
        assertEquals(0, msg.sendTime());
    }
}
