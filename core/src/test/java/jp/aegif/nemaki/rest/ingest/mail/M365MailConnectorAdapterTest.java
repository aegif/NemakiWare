package jp.aegif.nemaki.rest.ingest.mail;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M365 Mail adapter contract tests via WireMock.
 */
class M365MailConnectorAdapterTest {

    private static WireMockServer wireMock;
    private M365MailConnectorAdapter adapter;

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
        adapter = new M365MailConnectorAdapter("test-graph-token", null,
                HttpClient.newHttpClient(), "http://localhost:" + wireMock.port());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nemaki.ingest.allowLocalhost");
    }

    // ── Auth ──

    @Test
    void shouldSendBearerTokenOnEveryRequest() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/me/mailFolders/.*"))
                .willReturn(aResponse().withBody("{\"value\":[]}")));
        adapter.listMessages("inbox", 10, null);
        wireMock.verify(getRequestedFor(urlPathMatching("/me/mailFolders/.*"))
                .withHeader("Authorization", equalTo("Bearer test-graph-token")));
    }

    // ── listMessages ──

    @Test
    void listMessages_returnsMessages() throws Exception {
        String json = """
                {"value":[
                    {"id":"msg1","internetMessageId":"<abc@test>","subject":"Hello",
                     "from":{"emailAddress":{"address":"user@test.com"}},
                     "receivedDateTime":"2026-01-01T00:00:00Z"}
                ]}""";
        wireMock.stubFor(get(urlPathMatching("/me/mailFolders/inbox/messages"))
                .willReturn(aResponse().withBody(json)));

        var msgs = adapter.listMessages("inbox", 10, null);
        assertEquals(1, msgs.size());
        assertEquals("msg1", msgs.get(0).id());
        assertEquals("<abc@test>", msgs.get(0).internetMessageId());
        assertEquals("Hello", msgs.get(0).subject());
        assertEquals("user@test.com", msgs.get(0).from());
    }

    @Test
    void listMessages_emptyFolder() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/me/mailFolders/.*"))
                .willReturn(aResponse().withBody("{\"value\":[]}")));
        var msgs = adapter.listMessages("inbox", 10, null);
        assertTrue(msgs.isEmpty());
    }

    @Test
    void listMessages_respectsTopLimit() throws Exception {
        StringBuilder sb = new StringBuilder("{\"value\":[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"msg").append(i).append("\",\"subject\":\"S").append(i).append("\"}");
        }
        sb.append("]}");
        wireMock.stubFor(get(urlPathMatching("/me/mailFolders/.*"))
                .willReturn(aResponse().withBody(sb.toString())));

        var msgs = adapter.listMessages("inbox", 3, null);
        assertEquals(3, msgs.size());
    }

    @Test
    void listMessages_handlesApiError() {
        wireMock.stubFor(get(urlPathMatching("/me/mailFolders/.*"))
                .willReturn(aResponse().withStatus(500).withBody("Server Error")));
        assertThrows(RuntimeException.class, () -> adapter.listMessages("inbox", 10, null));
    }

    // ── fetchMimeMessage ──

    @Test
    void fetchMimeMessage_returnsStream() throws Exception {
        String emlContent = "From: test@test.com\r\nSubject: Test\r\n\r\nBody";
        wireMock.stubFor(get(urlPathMatching("/me/messages/.*/\\$value"))
                .willReturn(aResponse().withBody(emlContent)));

        try (InputStream is = adapter.fetchMimeMessage("msg1")) {
            String body = new String(is.readAllBytes());
            assertTrue(body.contains("Subject: Test"));
        }
    }

    // ── userId routing ──

    @Test
    void userId_routesToUsersPath() throws Exception {
        var userAdapter = new M365MailConnectorAdapter("token", "admin@contoso.com",
                HttpClient.newHttpClient(), "http://localhost:" + wireMock.port());
        wireMock.stubFor(get(urlPathMatching("/users/.*/mailFolders/.*"))
                .willReturn(aResponse().withBody("{\"value\":[]}")));

        userAdapter.listMessages("inbox", 10, null);
        wireMock.verify(getRequestedFor(urlPathMatching("/users/admin%40contoso\\.com/mailFolders/.*")));
    }

    @Test
    void noUserId_routesToMePath() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/me/mailFolders/.*"))
                .willReturn(aResponse().withBody("{\"value\":[]}")));

        adapter.listMessages("inbox", 10, null);
        wireMock.verify(getRequestedFor(urlPathMatching("/me/mailFolders/.*")));
    }

    // ── listFolders ──

    @Test
    void listFolders_returnsFolderNames() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/me/mailFolders"))
                .willReturn(aResponse().withBody("{\"value\":[{\"id\":\"f1\",\"displayName\":\"Inbox\"}]}")));

        var folders = adapter.listFolders();
        assertEquals(1, folders.size());
        assertTrue(folders.get(0).contains("Inbox"));
    }
}
