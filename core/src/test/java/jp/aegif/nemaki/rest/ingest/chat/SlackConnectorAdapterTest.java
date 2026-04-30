package jp.aegif.nemaki.rest.ingest.chat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Slack adapter contract tests.
 * Pins the Web API behavior the chat-context scheduler depends on.
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
    static void stopWireMock() { wireMock.stop(); }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        adapter = new SlackConnectorAdapter("xoxb-test-token",
                "http://localhost:" + wireMock.port());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nemaki.ingest.allowLocalhost");
    }

    // ── Auth contract ────────────────────────────────────────────

    @Test
    void shouldSendBearerTokenOnEveryRequest() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.list"))
                .willReturn(aResponse().withBody("{\"ok\":true,\"channels\":[]}")));
        adapter.listChannels(100);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/conversations.list"))
                .withHeader("Authorization", equalTo("Bearer xoxb-test-token")));
    }

    // ── History with/without oldest (checkpoint parameter) ───────

    @Test
    void shouldPassOldestParameterForCheckpoint() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .withQueryParam("channel", equalTo("C001"))
                .withQueryParam("oldest", equalTo("1700000000.000000"))
                .willReturn(aResponse().withBody("{\"ok\":true,\"messages\":[]}")));

        adapter.getHistory("C001", "1700000000.000000", 100);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/conversations.history"))
                .withQueryParam("oldest", equalTo("1700000000.000000")));
    }

    @Test
    void shouldOmitOldestWhenNull() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .willReturn(aResponse().withBody("{\"ok\":true,\"messages\":[]}")));
        adapter.getHistory("C001", null, 50);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/conversations.history"))
                .withoutQueryParam("oldest"));
    }

    // ── Message with thread_ts (thread detection) ────────────────

    @Test
    void shouldPreserveThreadTsForThreadDetection() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .willReturn(aResponse().withBody("""
                    {"ok":true,"messages":[{
                        "ts": "1700000001.000001",
                        "user": "U001",
                        "text": "thread reply",
                        "thread_ts": "1700000000.000000",
                        "files": []
                    }]}
                    """)));

        var msgs = adapter.getHistory("C001", null, 100);
        assertEquals("1700000000.000000", msgs.get(0).threadTs());
    }

    // ── Multiple files in a single message ────────────────────────

    @Test
    void shouldParseMultipleFilesInMessage() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .willReturn(aResponse().withBody("""
                    {"ok":true,"messages":[{
                        "ts": "1700000002.000002",
                        "user": "U002",
                        "text": "files attached",
                        "files": [
                            {"id":"F1","name":"a.pdf","mimetype":"application/pdf","url_private_download":"http://host/f1","size":100},
                            {"id":"F2","name":"b.png","mimetype":"image/png","url_private_download":"http://host/f2","size":200}
                        ]
                    }]}
                    """)));

        var msgs = adapter.getHistory("C001", null, 100);
        assertEquals(2, msgs.get(0).files().size());
        assertEquals("F1", msgs.get(0).files().get(0).id());
        assertEquals("F2", msgs.get(0).files().get(1).id());
    }

    // ── File download ────────────────────────────────────────────

    @Test
    void shouldStreamFileWithBearerAuth() throws Exception {
        byte[] content = "slack file bytes".getBytes();
        wireMock.stubFor(get(urlPathEqualTo("/files/f1"))
                .withHeader("Authorization", equalTo("Bearer xoxb-test-token"))
                .willReturn(aResponse().withBody(content)));

        InputStream stream = adapter.downloadFile("http://localhost:" + wireMock.port() + "/files/f1");
        assertArrayEquals(content, stream.readAllBytes());
    }

    // ── Failure contract ─────────────────────────────────────────

    @Test
    void shouldThrowOnHttp429RateLimit() {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.list"))
                .willReturn(aResponse().withStatus(429)
                        .withBody("{\"ok\":false,\"error\":\"rate_limited\"}")));
        assertThrows(RuntimeException.class, () -> adapter.listChannels(100));
    }

    @Test
    void shouldThrowOnHttp500() {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .willReturn(aResponse().withStatus(500)));
        assertThrows(RuntimeException.class, () -> adapter.getHistory("C001", null, 50));
    }

    // ── Channel listing ──────────────────────────────────────────

    @Test
    void shouldParseChannelFields() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.list"))
                .willReturn(aResponse().withBody("""
                    {"ok":true,"channels":[
                        {"id":"C1","name":"general","is_private":false},
                        {"id":"C2","name":"secret","is_private":true}
                    ]}
                    """)));
        var channels = adapter.listChannels(100);
        assertEquals(2, channels.size());
        assertFalse(channels.get(0).isPrivate());
        assertTrue(channels.get(1).isPrivate());
    }

    // ── Pagination contract ──────────────────────────────────────

    @Test
    void getHistoryFollowsCursorPagination() throws Exception {
        // Page 1: has_more=true with next_cursor
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .withQueryParam("channel", equalTo("C1"))
                .withQueryParam("limit", equalTo("200"))
                .willReturn(okJson("""
                    {"ok":true,"messages":[
                        {"ts":"1.1","user":"U1","text":"hello"}
                    ],"has_more":true,"response_metadata":{"next_cursor":"cursor_page2"}}
                    """)));
        // Page 2: no more
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .withQueryParam("cursor", matching("cursor_page2"))
                .willReturn(okJson("""
                    {"ok":true,"messages":[
                        {"ts":"1.2","user":"U1","text":"world"}
                    ],"has_more":false}
                    """)));

        var messages = adapter.getHistory("C1", null, 200);
        assertEquals(2, messages.size());
        assertEquals("1.1", messages.get(0).ts());
        assertEquals("1.2", messages.get(1).ts());
    }

    @Test
    void getHistoryRespectsLimitCap() throws Exception {
        // Return 3 messages but limit is 2
        wireMock.stubFor(get(urlPathEqualTo("/conversations.history"))
                .willReturn(okJson("""
                    {"ok":true,"messages":[
                        {"ts":"1","user":"U1","text":"a"},
                        {"ts":"2","user":"U1","text":"b"},
                        {"ts":"3","user":"U1","text":"c"}
                    ],"has_more":false}
                    """)));

        var messages = adapter.getHistory("C1", null, 2);
        assertEquals(2, messages.size(), "Should respect limit cap of 2");
    }

    // ── Thread replies contract ──────────────────────────────────

    @Test
    void getThreadRepliesExcludesParent() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/conversations.replies"))
                .willReturn(okJson("""
                    {"ok":true,"messages":[
                        {"ts":"1.0","user":"U1","text":"parent","thread_ts":"1.0"},
                        {"ts":"1.1","user":"U2","text":"reply","thread_ts":"1.0"}
                    ]}
                    """)));

        var replies = adapter.getThreadReplies("C1", "1.0");
        assertEquals(1, replies.size(), "Parent message should be excluded");
        assertEquals("1.1", replies.get(0).ts());
    }
}
