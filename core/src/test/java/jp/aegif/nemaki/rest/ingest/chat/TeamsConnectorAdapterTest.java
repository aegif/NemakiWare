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
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages/delta"))
                .willReturn(aResponse().withBody("""
                    {"@odata.deltaLink": "http://x/d", "value": [{
                        "id": "msg-1",
                        "body": {"content": "<p>Hello Teams</p>"},
                        "from": {"user": {"displayName": "Admin"}},
                        "createdDateTime": "2024-01-15T10:00:00Z",
                        "replyToId": null,
                        "attachments": []
                    }]}
                    """)));

        var msgs = adapter.delta(adapter.initialDeltaLink("T1", "C1", null)).messages();
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
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages/delta"))
                .willReturn(aResponse().withBody("""
                    {"@odata.deltaLink": "http://x/d", "value": [{
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

        var msgs = adapter.delta(adapter.initialDeltaLink("T1", "C1", null)).messages();
        assertEquals(1, msgs.size());
        // Only contentType="file" should be extracted — reference and card should be ignored
        assertEquals(1, msgs.get(0).attachments().size());
        assertEquals("att-file", msgs.get(0).attachments().get(0).id());
        assertEquals("doc.pdf", msgs.get(0).attachments().get(0).name());
    }

    @Test
    void shouldHandleMessageWithNoAttachments() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages/delta"))
                .willReturn(aResponse().withBody("""
                    {"@odata.deltaLink": "http://x/d", "value": [{"id": "msg-3", "body": {"content": "text only"},
                        "from": {"user": {"displayName": "U"}}, "createdDateTime": "2024-01-15T12:00:00Z",
                        "attachments": []}]}
                    """)));
        var msgs = adapter.delta(adapter.initialDeltaLink("T1", "C1", null)).messages();
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
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages/delta"))
                .willReturn(aResponse().withStatus(500)));
        assertThrows(RuntimeException.class, () -> adapter.delta(adapter.initialDeltaLink("T1", "C1", null)));
    }

    // ── the delta feed ───────────────────────────────────────────

    /** The first request of a round: the feed, filtered on lastModifiedDateTime to the millisecond — or the whole feed. No $top: Graph's default page. */
    @Test
    void theInitialDeltaLinkFiltersOnLastModifiedToTheMillisecond() {
        String link = adapter.initialDeltaLink("T1", "C1", java.time.Instant.parse("2026-01-01T00:55:00Z"));
        assertEquals("http://localhost:" + wireMock.port() + "/teams/T1/channels/C1/messages/delta?$filter=lastModifiedDateTime%20gt%202026-01-01T00%3A55%3A00.000Z", link);
        assertEquals("http://localhost:" + wireMock.port() + "/teams/T1/channels/C1/messages/delta", adapter.initialDeltaLink("T1", "C1", null));
        assertTrue(adapter.isOwnDeltaLink(link, "T1", "C1"));
        assertFalse(adapter.isOwnDeltaLink(link, "T1", "C2"), "another channel's link read as this channel's");
        assertFalse(adapter.isOwnDeltaLink("https://graph.microsoft.com/v1.0/teams/T1/channels/C1/messages/delta", "T1", "C1"), "another host's link read as this endpoint's");
    }

    /**
     * A channel id with a colon and an at sign: this connector encodes it in the links it writes,
     * Graph leaves it raw in the links it returns. Both are the channel's own feed; a comparison of
     * the raw strings refused every link Graph handed back.
     */
    @Test
    void aDeltaLinkIsTheChannelsOwnWhetherItsIdIsWrittenRawOrEncoded() {
        String channel = "19:abc@thread.tacv2";
        String written = adapter.initialDeltaLink("T1", channel, null);
        assertTrue(written.contains("19%3Aabc%40thread.tacv2"), "the id was not encoded in the link this connector writes: " + written);
        assertTrue(adapter.isOwnDeltaLink(written, "T1", channel), written);
        assertTrue(adapter.isOwnDeltaLink("http://localhost:" + wireMock.port() + "/teams/T1/channels/19:abc@thread.tacv2/messages/delta?$skiptoken=x", "T1", channel),
                "Graph's raw form of the same link was refused");
        assertTrue(adapter.isOwnDeltaLink("http://LOCALHOST:" + wireMock.port() + "/teams/T1/channels/" + "19:abc@thread.tacv2" + "/messages/delta", "T1", channel),
                "a host name differing only in case was refused");
        assertFalse(adapter.isOwnDeltaLink("http://localhost:" + wireMock.port() + "/teams/T1/channels/19:abc@thread.tacv2/messages", "T1", channel),
                "the channel listing was read as the delta feed");
        assertFalse(adapter.isOwnDeltaLink("http://localhost:" + (wireMock.port() + 1) + "/teams/T1/channels/19:abc@thread.tacv2/messages/delta", "T1", channel),
                "another port was read as this endpoint");
        assertFalse(adapter.isOwnDeltaLink("not a url at all", "T1", channel));
    }

    /**
     * Graph may spell the same feed in the OData key syntax, with delta() as a function call and
     * in its own case — its mail delta links do. All the channel's own; another channel is not.
     */
    @Test
    void aDeltaLinkInGraphsKeySyntaxIsTheChannelsOwn() {
        String channel = "19:abc@thread.tacv2";
        String host = "http://localhost:" + wireMock.port();
        assertTrue(adapter.isOwnDeltaLink(host + "/teams('T1')/channels('19:abc@thread.tacv2')/messages/delta()?$skiptoken=x", "T1", channel),
                "the key syntax was refused");
        assertTrue(adapter.isOwnDeltaLink(host + "/Teams/t1/Channels/19:ABC@thread.tacv2/messages/microsoft.graph.delta()?$deltatoken=y", "T1", channel),
                "Graph's own case, or the qualified function name, was refused");
        assertFalse(adapter.isOwnDeltaLink(host + "/teams('T1')/channels('19:other@thread.tacv2')/messages/delta()?$skiptoken=x", "T1", channel),
                "another channel in the key syntax was read as this channel's");
        assertFalse(adapter.isOwnDeltaLink(host + "/teams('T1')/channels('19:abc@thread.tacv2')/messages/delta()/extra", "T1", channel),
                "a longer path was read as the feed");
    }

    /** A deleted message comes back marked — deletedDateTime, or the delta convention @removed — and is counted, not carried. */
    @Test
    void aRemovedMessageIsCountedNotCarried() throws Exception {
        String link = "http://localhost:" + wireMock.port() + "/teams/T1/channels/C1/messages/delta?$deltatoken=t1";
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages/delta"))
                .willReturn(okJson("""
                    {"value":[{"id":"m4","@removed":{"reason":"deleted"}}],"@odata.deltaLink":"http://localhost:%d/teams/T1/channels/C1/messages/delta?$deltatoken=t2"}
                    """.formatted(wireMock.port()))));
        var page = adapter.delta(link);
        assertTrue(page.messages().isEmpty(), page.messages().toString());
        assertEquals(1, page.deleted());
    }

    @Test
    void aDeltaPageCarriesItsMessagesAndTheLinkToContinueFrom() throws Exception {
        String link = "http://localhost:" + wireMock.port() + "/teams/T1/channels/C1/messages/delta?$deltatoken=t1";
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages/delta"))
                .withQueryParam("$deltatoken", equalTo("t1"))
                .willReturn(okJson("""
                    {"value":[
                        {"id":"m9","body":{"content":"new"},"createdDateTime":"2026-01-01T00:09:00Z"},
                        {"id":"m5","body":{"content":""},"createdDateTime":"2026-01-01T00:05:00Z","deletedDateTime":"2026-01-02T00:00:00Z"}
                    ],"@odata.nextLink":"http://localhost:%d/teams/T1/channels/C1/messages/delta?$skiptoken=s1"}
                    """.formatted(wireMock.port()))));
        var page = adapter.delta(link);
        assertEquals(1, page.messages().size(), "the deleted message was carried: " + page.messages());
        assertEquals("m9", page.messages().get(0).id());
        assertEquals(1, page.deleted());
        assertEquals("http://localhost:" + wireMock.port() + "/teams/T1/channels/C1/messages/delta?$skiptoken=s1", page.nextLink());
        assertNull(page.deltaLink());
    }

    @Test
    void aDeltaPageWithoutALinkIsRefused() {
        String link = "http://localhost:" + wireMock.port() + "/teams/T1/channels/C1/messages/delta?$deltatoken=t1";
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages/delta"))
                .willReturn(okJson("{\"value\":[]}")));
        RuntimeException refused = assertThrows(RuntimeException.class, () -> adapter.delta(link));
        assertTrue(refused.getMessage().contains("@odata.nextLink or @odata.deltaLink"), refused.getMessage());
    }
}
