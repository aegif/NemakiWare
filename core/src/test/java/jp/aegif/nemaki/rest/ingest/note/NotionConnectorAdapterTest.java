package jp.aegif.nemaki.rest.ingest.note;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Notion adapter contract tests.
 * Pins the API behavior the note-import pipeline depends on.
 */
class NotionConnectorAdapterTest {

    private static WireMockServer wireMock;
    private NotionConnectorAdapter adapter;

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
        adapter = new NotionConnectorAdapter("test-token",
                "http://localhost:" + wireMock.port());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("nemaki.ingest.allowLocalhost");
    }

    // ── Auth + version header contract ───────────────────────────

    @Test
    void shouldSendAuthAndVersionHeaders() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/search"))
                .willReturn(aResponse().withBody("{\"results\":[]}")));
        adapter.searchPages(null, 10);
        wireMock.verify(postRequestedFor(urlPathEqualTo("/search"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("Notion-Version", equalTo("2022-06-28")));
    }

    // ── Search with query injection safety ────────────────────────

    @Test
    void shouldSafelyEscapeQueryInSearchBody() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/search"))
                .willReturn(aResponse().withBody("{\"results\":[]}")));
        // Query with special characters should be JSON-safe
        adapter.searchPages("test\"injection", 10);
        // Should not throw; Jackson ObjectMapper handles escaping
    }

    // ── Block pagination (has_more / next_cursor) ────────────────

    @Test
    void shouldFollowPaginationAcrossMultiplePages() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/blocks/page-1/children"))
                .withQueryParam("page_size", equalTo("100"))
                .willReturn(aResponse().withBody("""
                    {"results": [{"type":"paragraph","paragraph":{"rich_text":[{"plain_text":"Page 1"}]}}],
                     "has_more": true, "next_cursor": "cursor-2"}
                    """)));
        wireMock.stubFor(get(urlPathEqualTo("/blocks/page-1/children"))
                .withQueryParam("start_cursor", equalTo("cursor-2"))
                .willReturn(aResponse().withBody("""
                    {"results": [{"type":"paragraph","paragraph":{"rich_text":[{"plain_text":"Page 2"}]}}],
                     "has_more": false}
                    """)));

        String html = adapter.fetchPageAsHtml("page-1");
        assertTrue(html.contains("Page 1"));
        assertTrue(html.contains("Page 2"));
    }

    // ── Attachment extraction across paginated blocks ──────────────

    @Test
    void shouldExtractFilesFromPaginatedBlocks() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/blocks/page-2/children"))
                .withQueryParam("page_size", equalTo("100"))
                .willReturn(aResponse().withBody("""
                    {"results": [{"type":"paragraph","paragraph":{"rich_text":[]}}],
                     "has_more": true, "next_cursor": "c2"}
                    """)));
        wireMock.stubFor(get(urlPathEqualTo("/blocks/page-2/children"))
                .withQueryParam("start_cursor", equalTo("c2"))
                .willReturn(aResponse().withBody("""
                    {"results": [
                        {"id":"b1","type":"file","file":{"file":{"url":"https://s3/f1"},"name":"doc.pdf"}},
                        {"id":"b2","type":"image","image":{"file":{"url":"https://s3/img.png"}}}
                    ], "has_more": false}
                    """)));

        List<NotionConnectorAdapter.NotionFile> files = adapter.extractFiles("page-2");
        assertEquals(2, files.size());
        assertEquals("b1", files.get(0).blockId());
        assertEquals("file", files.get(0).type());
        assertEquals("b2", files.get(1).blockId());
        assertEquals("image", files.get(1).type());
    }

    // ── Unsupported block types ──────────────────────────────────

    @Test
    void shouldRenderUnsupportedBlocksAsHtmlComment() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/blocks/page-3/children"))
                .willReturn(aResponse().withBody("""
                    {"results": [{"type":"unknown_block_type"}], "has_more": false}
                    """)));
        String html = adapter.fetchPageAsHtml("page-3");
        assertTrue(html.contains("<!-- unsupported block: unknown_block_type -->"));
    }

    // ── Failure contract ─────────────────────────────────────────

    @Test
    void shouldThrowOn401() {
        wireMock.stubFor(post(urlPathEqualTo("/search"))
                .willReturn(aResponse().withStatus(401)));
        assertThrows(RuntimeException.class, () -> adapter.searchPages("test", 10));
    }

    @Test
    void shouldStopPaginationOnNon200() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/blocks/page-4/children"))
                .willReturn(aResponse().withStatus(500)));
        // Should not throw — just stops pagination and returns empty
        String html = adapter.fetchPageAsHtml("page-4");
        assertEquals("", html);
    }

    // ── Record mapping ───────────────────────────────────────────

    @Test
    void shouldMapPageSummaryFields() {
        var page = new NotionConnectorAdapter.NotionPageSummary("p1", "Title", "url", "parent-1", "2024-01-15T10:00:00.000Z");
        assertEquals("parent-1", page.parentId());
        assertNotNull(page.lastEditedTime());
    }
}
