package jp.aegif.nemaki.rest.ingest.chat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Teams adapter contract tests.
 * Pins the Graph API behavior the chat-context import pipeline depends on.
 */
class TeamsConnectorAdapterTest {

    private static WireMockServer wireMock;
    private TeamsConnectorAdapter adapter;

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
        adapter = new TeamsConnectorAdapter("test-graph-token",
                "http://localhost:" + wireMock.port());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nemaki.ingest.allowLocalhost");
    }

    // ── Auth contract ────────────────────────────────────────────

    @Test
    void shouldSendBearerTokenOnEveryRequest() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels"))
                .willReturn(aResponse().withBody("{\"value\":[]}")));
        adapter.listChannels("T1");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/teams/T1/channels"))
                .withHeader("Authorization", equalTo("Bearer test-graph-token")));
    }

    // ── Message parsing with body, from, createdDateTime ─────────

    @Test
    void shouldParseMessageFieldsForScheduler() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages"))
                .willReturn(aResponse().withBody("""
                    {"value": [{
                        "id": "msg-1",
                        "body": {"content": "<p>Hello Teams</p>"},
                        "from": {"user": {"displayName": "Admin"}},
                        "createdDateTime": "2024-01-15T10:00:00Z",
                        "replyToId": null,
                        "attachments": []
                    }]}
                    """)));

        var msgs = adapter.getMessages("T1", "C1", 50);
        assertEquals(1, msgs.size());
        assertEquals("msg-1", msgs.get(0).id());
        assertEquals("<p>Hello Teams</p>", msgs.get(0).body());
        assertEquals("Admin", msgs.get(0).from());
        assertEquals("2024-01-15T10:00:00Z", msgs.get(0).createdDateTime());
        assertNull(msgs.get(0).replyToId());
    }

    // ── File attachment extraction (only contentType="file") ─────

    @Test
    void shouldExtractOnlyFileTypeAttachments() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages"))
                .willReturn(aResponse().withBody("""
                    {"value": [{
                        "id": "msg-2",
                        "body": {"content": "files"},
                        "from": {"user": {"displayName": "User"}},
                        "createdDateTime": "2024-01-15T11:00:00Z",
                        "attachments": [
                            {"id": "att-file", "name": "doc.pdf", "contentUrl": "http://host/f1", "contentType": "file"},
                            {"id": "att-ref", "name": "link", "contentUrl": null, "contentType": "reference"},
                            {"id": "att-card", "name": "card", "contentUrl": null, "contentType": "application/vnd.microsoft.card.adaptive"}
                        ]
                    }]}
                    """)));

        var msgs = adapter.getMessages("T1", "C1", 50);
        assertEquals(1, msgs.size());
        // Only contentType="file" should be extracted — reference and card should be ignored
        assertEquals(1, msgs.get(0).attachments().size());
        assertEquals("att-file", msgs.get(0).attachments().get(0).id());
        assertEquals("doc.pdf", msgs.get(0).attachments().get(0).name());
    }

    @Test
    void shouldHandleMessageWithNoAttachments() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages"))
                .willReturn(aResponse().withBody("""
                    {"value": [{"id": "msg-3", "body": {"content": "text only"},
                        "from": {"user": {"displayName": "U"}}, "createdDateTime": "2024-01-15T12:00:00Z",
                        "attachments": []}]}
                    """)));
        var msgs = adapter.getMessages("T1", "C1", 50);
        assertTrue(msgs.get(0).attachments().isEmpty());
    }

    // ── File download ────────────────────────────────────────────

    @Test
    void shouldStreamFileBytesByContentUrl() throws Exception {
        byte[] content = "teams file binary".getBytes();
        wireMock.stubFor(get(urlPathEqualTo("/files/doc.pdf"))
                .willReturn(aResponse().withBody(content)));

        InputStream stream = adapter.downloadFile("http://localhost:" + wireMock.port() + "/files/doc.pdf");
        assertArrayEquals(content, stream.readAllBytes());
    }

    @Test
    void shouldThrowOnDownloadFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/files/gone"))
                .willReturn(aResponse().withStatus(404)));
        assertThrows(RuntimeException.class,
                () -> adapter.downloadFile("http://localhost:" + wireMock.port() + "/files/gone"));
    }

    // ── Channel listing ──────────────────────────────────────────

    @Test
    void shouldParseChannelFields() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels"))
                .willReturn(aResponse().withBody("""
                    {"value": [
                        {"id": "C1", "displayName": "General"},
                        {"id": "C2", "displayName": "Random"}
                    ]}
                    """)));
        var channels = adapter.listChannels("T1");
        assertEquals(2, channels.size());
        assertEquals("General", channels.get(0).displayName());
    }

    // ── Failure contract ─────────────────────────────────────────

    @Test
    void shouldThrowOn403() {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels"))
                .willReturn(aResponse().withStatus(403)));
        assertThrows(RuntimeException.class, () -> adapter.listChannels("T1"));
    }

    @Test
    void shouldThrowOn500() {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages"))
                .willReturn(aResponse().withStatus(500)));
        assertThrows(RuntimeException.class, () -> adapter.getMessages("T1", "C1", 50));
    }

    // ── Pagination contract ──────────────────────────────────────

    @Test
    void getMessagesRespectsLimitCap() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/teams/.*/channels/.*/messages.*"))
                .willReturn(okJson("""
                    {"value":[
                        {"id":"m1","body":{"content":"a"},"createdDateTime":"2026-01-01T00:00:00Z"},
                        {"id":"m2","body":{"content":"b"},"createdDateTime":"2026-01-01T00:01:00Z"},
                        {"id":"m3","body":{"content":"c"},"createdDateTime":"2026-01-01T00:02:00Z"}
                    ]}
                    """)));

        var messages = adapter.getMessages("T1", "C1", 2);
        assertEquals(2, messages.size(), "Should respect limit cap of 2");
    }

    @Test
    void getMessagesFollowsNextLink() throws Exception {
        // Page 1 with @odata.nextLink
        wireMock.stubFor(get(urlPathMatching("/teams/.*/channels/.*/messages"))
                .withQueryParam("$top", matching(".*"))
                .willReturn(okJson("""
                    {"value":[{"id":"m1","body":{"content":"a"},"createdDateTime":"2026-01-01T00:00:00Z"}],
                     "@odata.nextLink":"http://localhost:%d/teams/T1/channels/C1/messages?$skiptoken=page2"}
                    """.formatted(wireMock.port()))));

        // Page 2 (no nextLink)
        wireMock.stubFor(get(urlPathMatching("/teams/.*/channels/.*/messages"))
                .withQueryParam("$skiptoken", equalTo("page2"))
                .willReturn(okJson("""
                    {"value":[{"id":"m2","body":{"content":"b"},"createdDateTime":"2026-01-01T00:01:00Z"}]}
                    """)));

        var messages = adapter.getMessages("T1", "C1", 50);
        assertEquals(2, messages.size());
    }
}
