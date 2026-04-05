package jp.aegif.nemaki.rest.ingest.chat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void testGetPostsSendsAuthHeader() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/channels/ch1/posts"))
                .withHeader("Authorization", equalTo("Bearer test-mm-token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "order": ["p1"],
                                "posts": {
                                    "p1": {
                                        "id": "p1", "message": "Hello MM",
                                        "user_id": "u1", "create_at": 1700000000000,
                                        "root_id": "", "file_ids": ["f1"]
                                    }
                                }
                            }
                            """)));

        List<MattermostConnectorAdapter.MattermostPost> posts = adapter.getPosts("ch1", 50);
        assertEquals(1, posts.size());
        assertEquals("p1", posts.get(0).id());
        assertEquals("Hello MM", posts.get(0).message());
        assertEquals(1, posts.get(0).fileIds().size());
    }

    @Test
    void testGetFileInfo() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/files/f1/info"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"id": "f1", "name": "doc.pdf", "mime_type": "application/pdf", "size": 1024}
                            """)));

        MattermostConnectorAdapter.MattermostFile file = adapter.getFileInfo("f1");
        assertEquals("f1", file.id());
        assertEquals("doc.pdf", file.name());
        assertEquals("application/pdf", file.mimeType());
    }

    @Test
    void testListChannels() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/teams/t1/channels"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [{"id": "ch1", "name": "town-square", "display_name": "Town Square", "team_id": "t1"}]
                            """)));

        List<MattermostConnectorAdapter.MattermostChannel> channels = adapter.listChannels("t1");
        assertEquals(1, channels.size());
        assertEquals("Town Square", channels.get(0).displayName());
    }

    @Test
    void testApiErrorThrows() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/channels/ch1/posts"))
                .willReturn(aResponse().withStatus(401)));
        assertThrows(RuntimeException.class, () -> adapter.getPosts("ch1", 50));
    }
}
