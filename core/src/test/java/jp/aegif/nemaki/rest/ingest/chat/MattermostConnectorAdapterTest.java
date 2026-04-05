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
        adapter = new MattermostConnectorAdapter(
                "http://localhost:" + wireMock.port(), "test-mm-token");
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

        var posts = adapter.getPosts("ch1", 50);
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

        var posts = adapter.getPosts("ch1", 50);
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

        var posts = adapter.getPosts("ch1", 50);
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

        var posts = adapter.getPosts("ch1", 50);
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
        assertThrows(RuntimeException.class, () -> adapter.getPosts("ch1", 50));
    }
}
