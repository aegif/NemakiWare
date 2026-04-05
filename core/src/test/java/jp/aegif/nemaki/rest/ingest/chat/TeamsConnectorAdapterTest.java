package jp.aegif.nemaki.rest.ingest.chat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

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
        adapter = new TeamsConnectorAdapter("test-graph-token",
                "http://localhost:" + wireMock.port());
    }

    @Test
    void testGetMessagesSendsAuthHeader() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages"))
                .withHeader("Authorization", equalTo("Bearer test-graph-token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"value": [{
                                "id": "msg-1", "body": {"content": "Hello Teams"},
                                "from": {"user": {"displayName": "Admin"}},
                                "createdDateTime": "2024-01-15T10:00:00Z",
                                "attachments": []
                            }]}
                            """)));

        List<TeamsConnectorAdapter.TeamsMessage> msgs = adapter.getMessages("T1", "C1", 50);
        assertEquals(1, msgs.size());
        assertEquals("msg-1", msgs.get(0).id());
        assertEquals("Hello Teams", msgs.get(0).body());
    }

    @Test
    void testListChannels() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"value": [
                                {"id": "C1", "displayName": "General"},
                                {"id": "C2", "displayName": "Random"}
                            ]}
                            """)));

        List<TeamsConnectorAdapter.TeamsChannel> channels = adapter.listChannels("T1");
        assertEquals(2, channels.size());
        assertEquals("General", channels.get(0).displayName());
    }

    @Test
    void testGetMessagesWithAttachments() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels/C1/messages"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"value": [{
                                "id": "msg-2",
                                "body": {"content": "<p>See attached</p>"},
                                "from": {"user": {"displayName": "User1"}},
                                "createdDateTime": "2024-01-15T11:00:00Z",
                                "attachments": [{
                                    "id": "att-1", "name": "doc.pdf",
                                    "contentUrl": "http://localhost:%d/files/doc.pdf",
                                    "contentType": "file"
                                }]
                            }]}
                            """.formatted(wireMock.port()))));

        var msgs = adapter.getMessages("T1", "C1", 50);
        assertEquals(1, msgs.size());
        assertEquals(1, msgs.get(0).attachments().size());
        assertEquals("att-1", msgs.get(0).attachments().get(0).id());
        assertEquals("doc.pdf", msgs.get(0).attachments().get(0).name());
    }

    @Test
    void testDownloadFile() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/files/doc.pdf"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/pdf")
                        .withBody("fake pdf bytes")));

        var stream = adapter.downloadFile("http://localhost:" + wireMock.port() + "/files/doc.pdf");
        assertEquals("fake pdf bytes", new String(stream.readAllBytes()));
    }

    @Test
    void testApiErrorThrows() {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels"))
                .willReturn(aResponse().withStatus(403)));
        assertThrows(RuntimeException.class, () -> adapter.listChannels("T1"));
    }
}
