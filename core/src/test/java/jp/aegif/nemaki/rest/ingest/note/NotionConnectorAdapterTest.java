package jp.aegif.nemaki.rest.ingest.note;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

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
        adapter = new NotionConnectorAdapter("test-token",
                "http://localhost:" + wireMock.port());
    }

    @Test
    void testSearchPagesSendsAuthAndParsesResponse() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/search"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("Notion-Version", equalTo("2022-06-28"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "results": [{
                                    "id": "page-1",
                                    "url": "https://notion.so/page-1",
                                    "last_edited_time": "2024-01-15T10:00:00.000Z",
                                    "properties": {
                                        "title": {"type": "title", "title": [{"plain_text": "My Page"}]}
                                    },
                                    "parent": {"page_id": "parent-1"}
                                }]
                            }
                            """)));

        List<NotionConnectorAdapter.NotionPageSummary> pages = adapter.searchPages(null, 10);
        assertEquals(1, pages.size());
        assertEquals("page-1", pages.get(0).id());
        assertEquals("My Page", pages.get(0).title());
        assertEquals("parent-1", pages.get(0).parentId());
    }

    @Test
    void testFetchBlocksPagination() throws Exception {
        // First page: has_more=true
        wireMock.stubFor(get(urlPathEqualTo("/blocks/page-1/children"))
                .withQueryParam("page_size", equalTo("100"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "results": [{"type": "paragraph", "paragraph": {"rich_text": [{"plain_text": "Hello"}]}}],
                                "has_more": true,
                                "next_cursor": "cursor-2"
                            }
                            """)));
        // Second page: has_more=false
        wireMock.stubFor(get(urlPathEqualTo("/blocks/page-1/children"))
                .withQueryParam("start_cursor", equalTo("cursor-2"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "results": [{"type": "paragraph", "paragraph": {"rich_text": [{"plain_text": "World"}]}}],
                                "has_more": false
                            }
                            """)));

        String html = adapter.fetchPageAsHtml("page-1");
        assertTrue(html.contains("Hello"));
        assertTrue(html.contains("World"));
    }

    @Test
    void testExtractFiles() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/blocks/page-2/children"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "results": [{
                                    "id": "block-f1",
                                    "type": "file",
                                    "file": {"file": {"url": "https://s3.aws/file.pdf"}, "name": "doc.pdf"}
                                }, {
                                    "id": "block-i1",
                                    "type": "image",
                                    "image": {"file": {"url": "https://s3.aws/img.png"}}
                                }],
                                "has_more": false
                            }
                            """)));

        List<NotionConnectorAdapter.NotionFile> files = adapter.extractFiles("page-2");
        assertEquals(2, files.size());
        assertEquals("block-f1", files.get(0).blockId());
        assertEquals("file", files.get(0).type());
    }

    @Test
    void testApiErrorThrows() {
        wireMock.stubFor(post(urlPathEqualTo("/search"))
                .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Unauthorized\"}")));
        assertThrows(RuntimeException.class, () -> adapter.searchPages("test", 10));
    }

    @Test
    void testRecordMapping() {
        var page = new NotionConnectorAdapter.NotionPageSummary("p1", "Title", "url", null, null);
        assertNull(page.parentId());
        var file = new NotionConnectorAdapter.NotionFile("b1", "f.txt", "https://url", "file");
        assertEquals("b1", file.blockId());
    }
}
