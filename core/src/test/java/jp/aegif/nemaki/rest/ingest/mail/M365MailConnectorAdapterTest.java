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

    /** A delta page that ends the round (a deltaLink), carrying the given messages. */
    private String deltaPage(String messagesJson) {
        return "{\"value\":[" + messagesJson + "],\"@odata.deltaLink\":\"http://localhost:" + wireMock.port()
                + "/me/mailFolders/inbox/messages/delta?$deltatoken=t2\"}";
    }

    @Test
    void shouldSendBearerTokenOnEveryRequest() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/me/mailFolders/.*"))
                .willReturn(aResponse().withBody(deltaPage(""))));
        adapter.delta(adapter.initialDeltaLink("inbox"));
        wireMock.verify(getRequestedFor(urlPathMatching("/me/mailFolders/.*"))
                .withHeader("Authorization", equalTo("Bearer test-graph-token")));
    }

    // ── the delta feed ──

    @Test
    void aDeltaPageCarriesTheMessageFields() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/me/mailFolders/inbox/messages/delta"))
                .willReturn(aResponse().withBody(deltaPage("""
                    {"id":"msg1","internetMessageId":"<abc@test>","subject":"Hello",
                     "from":{"emailAddress":{"address":"user@test.com"}},
                     "receivedDateTime":"2026-01-01T00:00:00Z"}"""))));

        var page = adapter.delta(adapter.initialDeltaLink("inbox"));
        assertEquals(1, page.messages().size());
        assertEquals("msg1", page.messages().get(0).id());
        assertEquals("<abc@test>", page.messages().get(0).internetMessageId());
        assertEquals("Hello", page.messages().get(0).subject());
        assertEquals("user@test.com", page.messages().get(0).from());
        assertNull(page.nextLink());
        assertNotNull(page.deltaLink());
    }

    /** The delta call's page size is asked with Prefer: odata.maxpagesize — it takes no $top. */
    @Test
    void theDeltaFeedAsksForItsPageSizeWithPrefer() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/me/mailFolders/inbox/messages/delta"))
                .willReturn(aResponse().withBody(deltaPage(""))));
        adapter.delta(adapter.initialDeltaLink("inbox"));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/me/mailFolders/inbox/messages/delta"))
                .withHeader("Prefer", equalTo("odata.maxpagesize=50")));
    }

    /** A message removed from the folder — deleted, or moved out — comes as @removed: counted, not carried. */
    @Test
    void aRemovedMessageIsCountedNotCarried() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/me/mailFolders/inbox/messages/delta"))
                .willReturn(aResponse().withBody(deltaPage("{\"id\":\"gone\",\"@removed\":{\"reason\":\"deleted\"}}"))));
        var page = adapter.delta(adapter.initialDeltaLink("inbox"));
        assertTrue(page.messages().isEmpty(), page.messages().toString());
        assertEquals(1, page.removed());
    }

    /** No $filter: Graph caps a filtered delta query at 5,000 messages (review, P1). */
    @Test
    void theInitialDeltaLinkSelectsAndCarriesNoFilter() {
        String link = adapter.initialDeltaLink("inbox");
        assertEquals("http://localhost:" + wireMock.port() + "/me/mailFolders/inbox/messages/delta"
                + "?$select=id,internetMessageId,subject,from,receivedDateTime", link);
    }

    @Test
    void theFolderIdentityIsTheIdGraphAnswersForTheFolder() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/me/mailFolders/inbox")).withQueryParam("$select", equalTo("id"))
                .willReturn(aResponse().withBody("{\"id\":\"AAMkX\"}")));
        assertEquals("AAMkX", adapter.folderIdentity("inbox"));
    }

    @Test
    void aFolderAnsweredWithoutAnIdIsRefused() {
        wireMock.stubFor(get(urlPathEqualTo("/me/mailFolders/inbox")).willReturn(aResponse().withBody("{\"displayName\":\"Inbox\"}")));
        RuntimeException refused = assertThrows(RuntimeException.class, () -> adapter.folderIdentity("inbox"));
        assertTrue(refused.getMessage().contains("without an id"), refused.getMessage());
    }

    /** A removal without an id cannot be accounted for: the page is refused (review, P3). */
    @Test
    void aRemovedEntryWithoutAnIdIsRefused() {
        wireMock.stubFor(get(urlPathEqualTo("/me/mailFolders/inbox/messages/delta"))
                .willReturn(aResponse().withBody(deltaPage("{\"@removed\":{\"reason\":\"deleted\"}}"))));
        RuntimeException refused = assertThrows(RuntimeException.class, () -> adapter.delta(adapter.initialDeltaLink("inbox")));
        assertTrue(refused.getMessage().contains("removed entry without an id"), refused.getMessage());
    }

    /**
     * A mail delta link on this endpoint in any of Graph's spellings — /me or /users/{id}, the
     * folder by name or by id, the key syntax, the names in any case — is one this connector reads;
     * a link on another host, or of another shape, is not.
     */
    @Test
    void aMailDeltaLinkIsReadInGraphsSpellingsAndOnlyOnThisEndpoint() {
        String host = "http://localhost:" + wireMock.port();
        assertTrue(adapter.isMailDeltaLink(host + "/me/mailFolders/inbox/messages/delta?$skiptoken=x"));
        assertTrue(adapter.isMailDeltaLink(host + "/me/mailfolders('AQMkADNkNAAAgEMAAAA')/messages/delta()?$skiptoken=x"));
        assertTrue(adapter.isMailDeltaLink(host + "/users/8ea0e38b-efb3-4757-924a-5f94061cf8c2/MailFolders/AAMk=/messages/delta?$deltatoken=y"));
        assertTrue(adapter.isMailDeltaLink(host + "/users('user@contoso.com')/mailFolders('inbox')/messages/delta"));
        assertFalse(adapter.isMailDeltaLink("http://graph.example.invalid:" + wireMock.port() + "/me/mailFolders/inbox/messages/delta"),
                "another host was read as this endpoint");
        assertFalse(adapter.isMailDeltaLink(host + "/me/mailFolders/inbox/messages"), "the folder listing was read as the delta feed");
        assertFalse(adapter.isMailDeltaLink(host + "/me/messages/delta"), "a feed that is not a folder's was read as one");
        assertFalse(adapter.isMailDeltaLink(host + "/teams/T1/channels/C1/messages/delta"), "a Teams feed was read as a mail one");
        assertFalse(adapter.isMailDeltaLink("not a url"));
    }

    @Test
    void aDeltaPageWithoutALinkIsRefused() {
        wireMock.stubFor(get(urlPathEqualTo("/me/mailFolders/inbox/messages/delta"))
                .willReturn(aResponse().withBody("{\"value\":[]}")));
        RuntimeException refused = assertThrows(RuntimeException.class, () -> adapter.delta(adapter.initialDeltaLink("inbox")));
        assertTrue(refused.getMessage().contains("@odata.nextLink or @odata.deltaLink"), refused.getMessage());
    }

    @Test
    void theDeltaFeedFailingIsAnError() {
        wireMock.stubFor(get(urlPathMatching("/me/mailFolders/.*"))
                .willReturn(aResponse().withStatus(500).withBody("Server Error")));
        assertThrows(RuntimeException.class, () -> adapter.delta(adapter.initialDeltaLink("inbox")));
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
                .willReturn(aResponse().withBody(deltaPage(""))));

        userAdapter.delta(userAdapter.initialDeltaLink("inbox"));
        wireMock.verify(getRequestedFor(urlPathMatching("/users/admin%40contoso\\.com/mailFolders/.*")));
        assertEquals("admin@contoso.com", userAdapter.mailbox());
    }

    @Test
    void noUserId_routesToMePath() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/me/mailFolders/.*"))
                .willReturn(aResponse().withBody(deltaPage(""))));

        adapter.delta(adapter.initialDeltaLink("inbox"));
        wireMock.verify(getRequestedFor(urlPathMatching("/me/mailFolders/.*")));
        assertEquals("me", adapter.mailbox());
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
