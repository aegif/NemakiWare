package jp.aegif.nemaki.rest.ingest.record;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Salesforce adapter contract tests.
 * Pins the REST API behavior the business-record scheduler depends on.
 */
class SalesforceConnectorAdapterTest {

    private static WireMockServer wireMock;
    private SalesforceConnectorAdapter adapter;

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
        adapter = new SalesforceConnectorAdapter(
                "http://localhost:" + wireMock.port(), "test-sf-token");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("nemaki.ingest.allowLocalhost");
    }

    // ── Auth contract ────────────────────────────────────────────

    @Test
    void shouldSendBearerTokenOnQuery() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse().withBody("{\"records\":[]}")));
        adapter.query("SELECT Id FROM Account");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/services/data/v59.0/query"))
                .withHeader("Authorization", equalTo("Bearer test-sf-token")));
    }

    // ── SOQL query URL encoding ──────────────────────────────────

    @Test
    void shouldUrlEncodeSoqlQuery() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse().withBody("{\"records\":[]}")));
        adapter.query("SELECT Id, Name FROM Account WHERE Name = 'Acme'");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/services/data/v59.0/query"))
                .withQueryParam("q", equalTo("SELECT Id, Name FROM Account WHERE Name = 'Acme'")));
    }

    // ── Record parsing with fields ───────────────────────────────

    @Test
    void shouldParseRecordFieldsExcludingAttributes() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse().withBody("""
                    {"records": [{
                        "attributes": {"type": "Account", "url": "/services/data/v59.0/sobjects/Account/001"},
                        "Id": "001XX0001",
                        "Name": "Acme",
                        "Industry": "Technology",
                        "AnnualRevenue": "1000000"
                    }]}
                    """)));

        var records = adapter.query("SELECT Id, Name, Industry FROM Account");
        assertEquals(1, records.size());
        assertEquals("001XX0001", records.get(0).id());
        assertEquals("Account", records.get(0).type());
        assertEquals("Acme", records.get(0).name());
        assertEquals("Technology", records.get(0).fields().get("Industry"));
        // "attributes" key should NOT appear in fields map
        assertFalse(records.get(0).fields().containsKey("attributes"));
    }

    // ── getRecord by type and ID ─────────────────────────────────

    @Test
    void shouldFetchSingleRecordByTypeAndId() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/sobjects/Contact/003XX0001"))
                .willReturn(aResponse().withBody("""
                    {"attributes":{"type":"Contact"}, "Id":"003XX0001", "Name":"John", "Email":"j@test.com"}
                    """)));
        var rec = adapter.getRecord("Contact", "003XX0001");
        assertEquals("003XX0001", rec.id());
        assertEquals("Contact", rec.type());
        assertEquals("j@test.com", rec.fields().get("Email"));
    }

    // ── SOQL injection prevention ────────────────────────────────

    @Test
    void shouldRejectInvalidParentIdWithSingleQuote() {
        // SOQL injection attempt: invalid Salesforce ID format must be rejected
        assertThrows(IllegalArgumentException.class,
                () -> adapter.getAttachments("001'XX--inject"));
    }

    @Test
    void shouldRejectInvalidParentIdWithBackslash() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.getAttachments("001\\XX"));
    }

    @Test
    void shouldAcceptValidSalesforceId() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse().withBody("{\"records\":[]}")));
        // Valid 18-char Salesforce ID
        adapter.getAttachments("001000000000001AAA");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/services/data/v59.0/query"))
                .withQueryParam("q", containing("001000000000001AAA")));
    }

    // ── Empty results ────────────────────────────────────────────

    @Test
    void shouldReturnEmptyListForNoRecords() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse().withBody("{\"records\":[]}")));
        assertTrue(adapter.query("SELECT Id FROM Account").isEmpty());
    }

    // ── Failure contract ─────────────────────────────────────────

    @Test
    void shouldThrowOn401() {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse().withStatus(401)));
        assertThrows(RuntimeException.class, () -> adapter.query("SELECT Id FROM Account"));
    }

    @Test
    void shouldThrowOn400BadRequest() {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse().withStatus(400).withBody("[{\"errorCode\":\"MALFORMED_QUERY\"}]")));
        assertThrows(RuntimeException.class, () -> adapter.query("INVALID SOQL"));
    }

    @Test
    void shouldThrowOn500() {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/sobjects/Account/001"))
                .willReturn(aResponse().withStatus(500)));
        assertThrows(RuntimeException.class, () -> adapter.getRecord("Account", "001"));
    }
}
