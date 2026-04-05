package jp.aegif.nemaki.rest.ingest.chat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class ChatworkConnectorAdapterTest {

    private static WireMockServer wireMock;
    private ChatworkConnectorAdapter adapter;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() { wireMock.stop(); }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        adapter = new ChatworkConnectorAdapter("test-cw-token",
                "http://localhost:" + wireMock.port());
    }

    @Test
    void testListRoomsSendsTokenHeader() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms"))
                .withHeader("X-ChatWorkToken", equalTo("test-cw-token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [
                                {"room_id": 123, "name": "General", "type": "group", "message_num": 42, "file_num": 5},
                                {"room_id": 456, "name": "Private", "type": "direct", "message_num": 10, "file_num": 0}
                            ]
                            """)));

        List<ChatworkConnectorAdapter.ChatworkRoom> rooms = adapter.listRooms();
        assertEquals(2, rooms.size());
        assertEquals("123", rooms.get(0).id());
        assertEquals("General", rooms.get(0).name());
        assertEquals(42, rooms.get(0).messageNum());
    }

    @Test
    void testGetMessagesParsesAccountInfo() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/123/messages"))
                .withQueryParam("force", equalTo("1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [{
                                "message_id": "msg-001",
                                "account": {"account_id": "acct-1", "name": "Taro"},
                                "body": "Hello from Chatwork",
                                "send_time": 1700000000,
                                "update_time": 1700000100
                            }]
                            """)));

        List<ChatworkConnectorAdapter.ChatworkMessage> msgs = adapter.getMessages("123", true);
        assertEquals(1, msgs.size());
        assertEquals("msg-001", msgs.get(0).messageId());
        assertEquals("acct-1", msgs.get(0).accountId());
        assertEquals("Taro", msgs.get(0).accountName());
        assertEquals("Hello from Chatwork", msgs.get(0).body());
    }

    @Test
    void testGetMessagesReturnsEmptyOn204() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/123/messages"))
                .willReturn(aResponse().withStatus(204)));

        List<ChatworkConnectorAdapter.ChatworkMessage> msgs = adapter.getMessages("123", false);
        assertTrue(msgs.isEmpty());
    }

    @Test
    void testListFiles() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/123/files"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [{
                                "file_id": "f-001",
                                "account": {"account_id": "acct-1"},
                                "filename": "report.pdf",
                                "filesize": 2048
                            }]
                            """)));

        List<ChatworkConnectorAdapter.ChatworkFile> files = adapter.listFiles("123");
        assertEquals(1, files.size());
        assertEquals("f-001", files.get(0).fileId());
        assertEquals("report.pdf", files.get(0).name());
        assertEquals(2048, files.get(0).size());
    }

    @Test
    void testGetFileDownloadUrl() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/123/files/f-001"))
                .withQueryParam("create_download_url", equalTo("1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"download_url\":\"https://tmp.chatwork.com/dl/abc123\"}")));

        String url = adapter.getFileDownloadUrl("123", "f-001");
        assertEquals("https://tmp.chatwork.com/dl/abc123", url);
    }

    @Test
    void testApiErrorThrows() {
        wireMock.stubFor(get(urlPathEqualTo("/rooms"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));
        assertThrows(RuntimeException.class, () -> adapter.listRooms());
    }

    @Test
    void testRecordMapping() {
        var room = new ChatworkConnectorAdapter.ChatworkRoom("1", "R", "group", 0, 0);
        assertEquals("1", room.id());
        var msg = new ChatworkConnectorAdapter.ChatworkMessage("m1", "a1", "N", "", 0, 0);
        assertEquals("", msg.body());
    }
}
