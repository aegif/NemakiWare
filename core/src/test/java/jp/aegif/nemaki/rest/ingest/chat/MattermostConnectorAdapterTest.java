package jp.aegif.nemaki.rest.ingest.chat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Mattermost adapter contract tests.
 * Pins the REST v4 API behavior the chat-context scheduler depends on.
 */
class MattermostConnectorAdapterTest {

    private static WireMockServer wireMock;
    private MattermostConnectorAdapter adapter;

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
        // RC6.8 P1: sendWithRetry now validates + IP-pins every request
        // against the SSRF blocklist, including localhost. WireMock binds
        // to localhost so tests must opt-in via the documented test-only
        // property (never set in production).
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        adapter = new MattermostConnectorAdapter(
                "http://localhost:" + wireMock.port(), "test-mm-token");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("nemaki.ingest.allowLocalhost");
    }

    /** A full page of {@code count} posts p{firstK}, p{firstK-1}, … newest first, one per second. */
    private static String pageOf(int firstK, int count) {
        StringBuilder order = new StringBuilder();
        StringBuilder posts = new StringBuilder();
        for (int k = firstK; k > firstK - count; k--) {
            if (order.length() > 0) { order.append(','); posts.append(','); }
            order.append("\"p").append(k).append('"');
            posts.append("\"p").append(k).append("\":{\"id\":\"p").append(k).append("\",\"message\":\"m\",\"user_id\":\"u1\",\"create_at\":")
                    .append(1_700_000_000_000L + k * 1000L).append('}');
        }
        return "{\"order\":[" + order + "],\"posts\":{" + posts + "}}";
    }

    // ── Auth contract ────────────────────────────────────────────

    @Test
    void shouldSendBearerTokenOnEveryRequest() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/teams/t1/channels"))
                .willReturn(aResponse().withBody("[]")));
        adapter.listChannels("t1");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v4/teams/t1/channels"))
                .withHeader("Authorization", equalTo("Bearer test-mm-token")));
    }

    // ── Post parsing with file_ids and root_id ───────────────────

    @Test
    void shouldParsePostFieldsForScheduler() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/channels/ch1/posts"))
                .willReturn(aResponse().withBody("""
                    {"order": ["p1"], "posts": {
                        "p1": {"id": "p1", "message": "Hello MM", "user_id": "u1",
                               "create_at": 1700000000000, "root_id": "", "file_ids": ["f1", "f2"]}
                    }}
                    """)));

        var posts = adapter.listSince("ch1", null, 10).posts();
        assertEquals(1, posts.size());
        assertEquals("p1", posts.get(0).id());
        assertEquals("Hello MM", posts.get(0).message());
        assertEquals("u1", posts.get(0).userId());
        assertEquals(1700000000000L, posts.get(0).createAt());
        assertEquals(List.of("f1", "f2"), posts.get(0).fileIds());
    }

    @Test
    void shouldPreserveRootIdForThreadDetection() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/channels/ch1/posts"))
                .willReturn(aResponse().withBody("""
                    {"order": ["p2"], "posts": {
                        "p2": {"id": "p2", "message": "reply", "user_id": "u1",
                               "create_at": 1700000001000, "root_id": "p1", "file_ids": []}
                    }}
                    """)));

        var posts = adapter.listSince("ch1", null, 10).posts();
        assertEquals("p1", posts.get(0).rootId());
    }

    @Test
    void shouldHandlePostWithEmptyFileIds() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/channels/ch1/posts"))
                .willReturn(aResponse().withBody("""
                    {"order": ["p3"], "posts": {
                        "p3": {"id": "p3", "message": "no files", "user_id": "u1",
                               "create_at": 1700000002000, "root_id": "", "file_ids": []}
                    }}
                    """)));

        var posts = adapter.listSince("ch1", null, 10).posts();
        assertTrue(posts.get(0).fileIds().isEmpty());
    }

    @Test
    void shouldRespectPostOrderFromApiResponse() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/channels/ch1/posts"))
                .willReturn(aResponse().withBody("""
                    {"order": ["p2", "p1"], "posts": {
                        "p1": {"id": "p1", "message": "first", "user_id": "u", "create_at": 100, "root_id": "", "file_ids": []},
                        "p2": {"id": "p2", "message": "second", "user_id": "u", "create_at": 200, "root_id": "", "file_ids": []}
                    }}
                    """)));

        var posts = adapter.listSince("ch1", null, 10).posts();
        assertEquals(2, posts.size());
        // Posts should be returned in "order" array sequence
        assertEquals("p2", posts.get(0).id());
        assertEquals("p1", posts.get(1).id());
    }

    // ── File info and download ───────────────────────────────────

    @Test
    void shouldParseFileInfoFields() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/files/f1/info"))
                .willReturn(aResponse().withBody(
                        "{\"id\":\"f1\",\"name\":\"doc.pdf\",\"mime_type\":\"application/pdf\",\"size\":1024}")));

        var file = adapter.getFileInfo("f1");
        assertEquals("f1", file.id());
        assertEquals("doc.pdf", file.name());
        assertEquals("application/pdf", file.mimeType());
        assertEquals(1024, file.size());
    }

    @Test
    void shouldStreamFileBytesByFileId() throws Exception {
        byte[] content = "mattermost file bytes".getBytes();
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/files/f1"))
                .willReturn(aResponse().withBody(content)));

        InputStream stream = adapter.downloadFile("f1");
        assertArrayEquals(content, stream.readAllBytes());
    }

    @Test
    void shouldThrowOnFileDownloadFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/files/gone"))
                .willReturn(aResponse().withStatus(404)));
        assertThrows(RuntimeException.class, () -> adapter.downloadFile("gone"));
    }

    // ── Channel listing ──────────────────────────────────────────

    @Test
    void shouldParseChannelFields() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/teams/t1/channels"))
                .willReturn(aResponse().withBody(
                        "[{\"id\":\"ch1\",\"name\":\"town-square\",\"display_name\":\"Town Square\",\"team_id\":\"t1\"}]")));
        var channels = adapter.listChannels("t1");
        assertEquals("Town Square", channels.get(0).displayName());
    }

    // ── Failure contract ─────────────────────────────────────────

    @Test
    void shouldThrowOn401() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/channels/ch1/posts"))
                .willReturn(aResponse().withStatus(401)));
        assertThrows(RuntimeException.class, () -> adapter.listSince("ch1", null, 10));
    }

    // ── Pagination contract ──────────────────────────────────────

    /**
     * A listing stopped at the request cap says so and is NOT complete — the posts it did not
     * reach are the older ones, and a checkpoint raised over the ones it did reach would exclude
     * them for ever (R107). The old getPosts() cut at a post count and said nothing.
     */
    @Test
    void aListingCutAtTheRequestCapSaysSoAndIsNotComplete() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/api/v4/channels/.*/posts.*"))
                .willReturn(okJson(pageOf(300, 200))));

        var listing = adapter.listSince("ch1", null, 1);
        assertFalse(listing.complete(), "a cut listing was reported whole");
        assertTrue(listing.truncatedBecause().contains("mattermostPostMaxRequests"), listing.truncatedBecause());
        assertEquals(200, listing.posts().size());
    }

    /** The listing stops at the first post created before the checkpoint — Mattermost lists newest first. */
    @Test
    void theListingStopsAtTheCheckpoint() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/api/v4/channels/.*/posts.*"))
                .willReturn(okJson("""
                    {"order":["p3","p2","p1"],"posts":{
                        "p3":{"id":"p3","message":"c","user_id":"u1","create_at":3000},
                        "p2":{"id":"p2","message":"b","user_id":"u1","create_at":2000},
                        "p1":{"id":"p1","message":"a","user_id":"u1","create_at":1000}
                    }}
                    """)));
        var listing = adapter.listSince("ch1", "1970-01-01T00:00:01.500000000Z", 10);
        assertTrue(listing.complete(), listing.truncatedBecause());
        assertEquals(2, listing.posts().size(), "only the posts newer than the checkpoint: " + listing.posts());
        assertEquals("p3", listing.posts().get(0).id());
        assertEquals("p2", listing.posts().get(1).id());
    }

    /** The pages are walked by {@code before=<post id>}: the second page is asked before the post above the first page's oldest time. */
    @Test
    void theListingFollowsBeforeCursorsToTheEnd() throws Exception {
        // Page 1: full (200 posts, p300…p101), asked without a cursor
        wireMock.stubFor(get(urlPathMatching("/api/v4/channels/.*/posts.*"))
                .withQueryParam("before", absent())
                .willReturn(okJson(pageOf(300, 200))));
        // Page 2: asked before p102 (the post above p101, the oldest of page 1); short, so the end
        wireMock.stubFor(get(urlPathMatching("/api/v4/channels/.*/posts.*"))
                .withQueryParam("before", equalTo("p102"))
                .willReturn(okJson(pageOf(101, 2))));

        var listing = adapter.listSince("ch1", null, 10);
        assertTrue(listing.complete(), listing.truncatedBecause());
        assertEquals(201, listing.posts().size(), "p101 is listed once although both pages carry it");
        assertEquals("p100", listing.posts().get(200).id());
        wireMock.verify(1, getRequestedFor(urlPathMatching("/api/v4/channels/.*/posts")).withQueryParam("before", equalTo("p102")));
    }
}
