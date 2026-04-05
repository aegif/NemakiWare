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
    void testApiErrorThrows() {
        wireMock.stubFor(get(urlPathEqualTo("/teams/T1/channels"))
                .willReturn(aResponse().withStatus(403)));
        assertThrows(RuntimeException.class, () -> adapter.listChannels("T1"));
    }
}
