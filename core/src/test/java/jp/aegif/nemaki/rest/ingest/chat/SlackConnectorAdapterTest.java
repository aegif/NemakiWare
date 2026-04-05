package jp.aegif.nemaki.rest.ingest.chat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Adapter integration tests for SlackConnectorAdapter using WireMock.
 * Tests HTTP contract, auth headers, pagination, error handling.
 */
class SlackConnectorAdapterTest {

    private static WireMockServer wireMock;
    private SlackConnectorAdapter adapter;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        adapter = new SlackConnectorAdapter("xoxb-test-token",
                "http://localhost:" + wireMock.port());
    }

    // ── HTTP contract tests ─────────────────────────────────────

    @Test
    void testListChannelsSendsAuthHeaderAndParsesResponse() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.list"))
                .withHeader("Authorization", equalTo("Bearer xoxb-test-token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "ok": true,
                                "channels": [
                                    {"id": "C001", "name": "general", "is_private": false},
                                    {"id": "C002", "name": "secret", "is_private": true}
                                ]
                            }
                            """)));

        List<SlackConnectorAdapter.SlackChannel> channels = adapter.listChannels(100);
        assertEquals(2, channels.size());
        assertEquals("C001", channels.get(0).id());
        assertEquals("general", channels.get(0).name());
        assertFalse(channels.get(0).isPrivate());
        assertTrue(channels.get(1).isPrivate());
    }

    @Test
    void testGetHistorySendsOldestParameter() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .withQueryParam("channel", equalTo("C001"))
                .withQueryParam("oldest", equalTo("1700000000.000000"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "ok": true,
                                "messages": [
                                    {
                                        "ts": "1700000001.000001",
                                        "user": "U001",
                                        "text": "Hello",
                                        "thread_ts": null,
                                        "files": []
                                    }
                                ]
                            }
                            """)));

        List<SlackConnectorAdapter.SlackMessage> messages =
                adapter.getHistory("C001", "1700000000.000000", 100);
        assertEquals(1, messages.size());
        assertEquals("1700000001.000001", messages.get(0).ts());
        assertEquals("U001", messages.get(0).userId());
        assertEquals("Hello", messages.get(0).text());
    }

    @Test
    void testGetHistoryWithNullOldestOmitsParameter() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .withQueryParam("channel", equalTo("C001"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"messages\":[]}")));

        List<SlackConnectorAdapter.SlackMessage> messages =
                adapter.getHistory("C001", null, 50);
        assertTrue(messages.isEmpty());
    }

    @Test
    void testGetHistoryParsesFilesInMessages() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "ok": true,
                                "messages": [{
                                    "ts": "1700000002.000002",
                                    "user": "U002",
                                    "text": "See attached",
                                    "files": [{
                                        "id": "F001",
                                        "name": "doc.pdf",
                                        "mimetype": "application/pdf",
                                        "url_private_download": "https://files.slack.com/F001",
                                        "size": 1024
                                    }]
                                }]
                            }
                            """)));

        List<SlackConnectorAdapter.SlackMessage> messages =
                adapter.getHistory("C001", null, 100);
        assertEquals(1, messages.size());
        assertEquals(1, messages.get(0).files().size());
        assertEquals("F001", messages.get(0).files().get(0).id());
        assertEquals("doc.pdf", messages.get(0).files().get(0).name());
        assertEquals("application/pdf", messages.get(0).files().get(0).mimeType());
        assertEquals(1024, messages.get(0).files().get(0).size());
    }

    @Test
    void testDownloadFileStreamsContent() throws Exception {
        String fileUrl = "/files-pri/T001/doc.pdf";
        wireMock.stubFor(get(urlPathEqualTo(fileUrl))
                .withHeader("Authorization", equalTo("Bearer xoxb-test-token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/pdf")
                        .withBody("fake pdf content")));

        InputStream stream = adapter.downloadFile(
                "http://localhost:" + wireMock.port() + fileUrl);
        assertNotNull(stream);
        String content = new String(stream.readAllBytes());
        assertEquals("fake pdf content", content);
    }

    @Test
    void testApiErrorThrowsException() {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.list"))
                .willReturn(aResponse().withStatus(429)
                        .withBody("{\"ok\":false,\"error\":\"rate_limited\"}")));

        assertThrows(RuntimeException.class, () -> adapter.listChannels(100));
    }

    // ── Record mapping tests ────────────────────────────────────

    @Test
    void testSlackMessageRecordMapping() {
        var msg = new SlackConnectorAdapter.SlackMessage(
                "1234567890.123456", "U001", "Hello world",
                "1234567890.000001",
                List.of(new SlackConnectorAdapter.SlackFile(
                        "F001", "doc.pdf", "application/pdf",
                        "https://files.slack.com/F001", 1024)));
        assertEquals("1234567890.123456", msg.ts());
        assertEquals("U001", msg.userId());
        assertEquals(1, msg.files().size());
    }

    @Test
    void testEmptyFilesListInMessage() {
        var msg = new SlackConnectorAdapter.SlackMessage(
                "1234567890.123456", "U001", "No files", null, List.of());
        assertTrue(msg.files().isEmpty());
        assertNull(msg.threadTs());
    }
}
