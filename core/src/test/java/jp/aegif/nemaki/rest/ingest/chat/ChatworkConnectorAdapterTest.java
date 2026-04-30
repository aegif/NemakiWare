package jp.aegif.nemaki.rest.ingest.chat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Chatwork adapter contract tests.
 * Pins the external API behavior the scheduler checkpoint logic depends on.
 */
class ChatworkConnectorAdapterTest {

    private static WireMockServer wireMock;
    private ChatworkConnectorAdapter adapter;

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
        adapter = new ChatworkConnectorAdapter("test-cw-token",
                "http://localhost:" + wireMock.port());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nemaki.ingest.allowLocalhost");
    }

    // ── Auth contract ───────────────────────────────────────────

    @Test
    void shouldSendTokenHeaderOnEveryRequest() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms"))
                .withHeader("X-ChatWorkToken", equalTo("test-cw-token"))
                .willReturn(aResponse().withBody("[]")));
        adapter.listRooms();
        wireMock.verify(getRequestedFor(urlPathEqualTo("/rooms"))
                .withHeader("X-ChatWorkToken", equalTo("test-cw-token")));
    }

    // ── Differential fetch semantics (force=0 vs force=1) ────────

    @Test
    void shouldPreserveDifferentialFetchSemanticsWithForce0() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/R1/messages"))
                .withQueryParam("force", equalTo("0"))
                .willReturn(aResponse().withBody(
                        "[{\"message_id\":\"50\",\"account\":{\"account_id\":\"a\",\"name\":\"N\"},\"body\":\"delta\",\"send_time\":0,\"update_time\":0}]")));

        var msgs = adapter.getMessages("R1", false);
        assertEquals(1, msgs.size());
        assertEquals("50", msgs.get(0).messageId());
        wireMock.verify(getRequestedFor(urlPathEqualTo("/rooms/R1/messages"))
                .withQueryParam("force", equalTo("0")));
    }

    @Test
    void shouldReturnLatest100WithForce1() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/R1/messages"))
                .withQueryParam("force", equalTo("1"))
                .willReturn(aResponse().withBody(
                        "[{\"message_id\":\"100\",\"account\":{\"account_id\":\"a\",\"name\":\"N\"},\"body\":\"latest\",\"send_time\":0,\"update_time\":0},"
                        + "{\"message_id\":\"99\",\"account\":{\"account_id\":\"a\",\"name\":\"N\"},\"body\":\"prev\",\"send_time\":0,\"update_time\":0}]")));

        var msgs = adapter.getMessages("R1", true);
        assertEquals(2, msgs.size());
        // Scheduler depends on message_id ordering for checkpoint — verify returned order
        assertEquals("100", msgs.get(0).messageId());
        assertEquals("99", msgs.get(1).messageId());
    }

    @Test
    void shouldReturnEmptyListOn204NoContent() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/R1/messages"))
                .willReturn(aResponse().withStatus(204)));
        assertTrue(adapter.getMessages("R1", false).isEmpty());
    }

    // ── Message ID ordering (scheduler checkpoint depends on numeric ordering) ──

    @Test
    void shouldReturnMessagesInApiOrder() throws Exception {
        // Chatwork returns messages in chronological order (oldest first for force=1)
        wireMock.stubFor(get(urlPathEqualTo("/rooms/R1/messages"))
                .willReturn(aResponse().withBody(
                        "[{\"message_id\":\"2\",\"account\":{\"account_id\":\"a\",\"name\":\"N\"},\"body\":\"old\",\"send_time\":100,\"update_time\":100},"
                        + "{\"message_id\":\"10\",\"account\":{\"account_id\":\"a\",\"name\":\"N\"},\"body\":\"new\",\"send_time\":200,\"update_time\":200}]")));

        var msgs = adapter.getMessages("R1", true);
        // Scheduler uses numeric comparison — "2" < "10" numerically but "2" > "10" lexically
        // Adapter returns in API order; scheduler is responsible for numeric comparison
        assertEquals("2", msgs.get(0).messageId());
        assertEquals("10", msgs.get(1).messageId());
    }

    // ── File listing and download ────────────────────────────────

    @Test
    void shouldParseFileListWithAccountInfo() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/R1/files"))
                .willReturn(aResponse().withBody(
                        "[{\"file_id\":\"f1\",\"account\":{\"account_id\":\"a1\"},\"filename\":\"report.pdf\",\"filesize\":2048},"
                        + "{\"file_id\":\"f2\",\"account\":{\"account_id\":\"a2\"},\"filename\":\"data.csv\",\"filesize\":512}]")));

        var files = adapter.listFiles("R1");
        assertEquals(2, files.size());
        assertEquals("f1", files.get(0).fileId());
        assertEquals("report.pdf", files.get(0).name());
        assertEquals(2048, files.get(0).size());
    }

    @Test
    void shouldReturnEmptyFileListOn204() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/R1/files"))
                .willReturn(aResponse().withStatus(204)));
        assertTrue(adapter.listFiles("R1").isEmpty());
    }

    @Test
    void shouldFetchDownloadUrlWithCreateParam() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/R1/files/f1"))
                .withQueryParam("create_download_url", equalTo("1"))
                .willReturn(aResponse().withBody("{\"download_url\":\"https://tmp.cw.com/dl/abc\"}")));

        assertEquals("https://tmp.cw.com/dl/abc", adapter.getFileDownloadUrl("R1", "f1"));
    }

    @Test
    void shouldStreamDownloadedFileBytes() throws Exception {
        byte[] content = "chatwork file binary content".getBytes();
        wireMock.stubFor(get(urlPathEqualTo("/dl/test-file"))
                .willReturn(aResponse().withBody(content)));

        InputStream stream = adapter.downloadFile("http://localhost:" + wireMock.port() + "/dl/test-file");
        assertArrayEquals(content, stream.readAllBytes());
    }

    @Test
    void shouldThrowOnDownloadFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/dl/gone"))
                .willReturn(aResponse().withStatus(404)));
        assertThrows(RuntimeException.class,
                () -> adapter.downloadFile("http://localhost:" + wireMock.port() + "/dl/gone"));
    }

    // ── Failure contract ─────────────────────────────────────────

    @Test
    void shouldThrowOn401() {
        wireMock.stubFor(get(urlPathEqualTo("/rooms"))
                .willReturn(aResponse().withStatus(401)));
        assertThrows(RuntimeException.class, () -> adapter.listRooms());
    }

    @Test
    void shouldThrowOn500() {
        wireMock.stubFor(get(urlPathEqualTo("/rooms/R1/messages"))
                .willReturn(aResponse().withStatus(500)));
        assertThrows(RuntimeException.class, () -> adapter.getMessages("R1", true));
    }

    // ── Room listing ─────────────────────────────────────────────

    @Test
    void shouldParseRoomFields() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/rooms"))
                .willReturn(aResponse().withBody(
                        "[{\"room_id\":123,\"name\":\"Dev\",\"type\":\"group\",\"message_num\":42,\"file_num\":5}]")));
        var rooms = adapter.listRooms();
        assertEquals(1, rooms.size());
        assertEquals("123", rooms.get(0).id());
        assertEquals("Dev", rooms.get(0).name());
        assertEquals(42, rooms.get(0).messageNum());
    }
}
