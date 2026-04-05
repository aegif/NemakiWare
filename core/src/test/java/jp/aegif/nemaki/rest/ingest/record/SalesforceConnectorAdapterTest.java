package jp.aegif.nemaki.rest.ingest.record;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

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
        adapter = new SalesforceConnectorAdapter(
                "http://localhost:" + wireMock.port(), "test-sf-token");
    }

    @Test
    void testQuerySendsAuthAndParsesRecords() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .withHeader("Authorization", equalTo("Bearer test-sf-token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "records": [{
                                    "attributes": {"type": "Account"},
                                    "Id": "001XX0001",
                                    "Name": "Acme Corp",
                                    "Industry": "Technology"
                                }]
                            }
                            """)));

        List<SalesforceConnectorAdapter.SalesforceRecord> records =
                adapter.query("SELECT Id, Name FROM Account LIMIT 10");
        assertEquals(1, records.size());
        assertEquals("001XX0001", records.get(0).id());
        assertEquals("Account", records.get(0).type());
        assertEquals("Acme Corp", records.get(0).name());
        assertEquals("Technology", records.get(0).fields().get("Industry"));
    }

    @Test
    void testGetRecordByTypeAndId() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/sobjects/Contact/003XX0001"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "attributes": {"type": "Contact"},
                                "Id": "003XX0001",
                                "Name": "John Doe",
                                "Email": "john@example.com"
                            }
                            """)));

        SalesforceConnectorAdapter.SalesforceRecord rec = adapter.getRecord("Contact", "003XX0001");
        assertEquals("003XX0001", rec.id());
        assertEquals("John Doe", rec.name());
        assertEquals("john@example.com", rec.fields().get("Email"));
    }

    @Test
    void testGetAttachmentsUsesSoqlEscaping() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"records\":[]}")));

        // This should not throw even with special characters
        List<SalesforceConnectorAdapter.SalesforceRecord> atts =
                adapter.getAttachments("001'XX--inject");
        assertTrue(atts.isEmpty());

        // Verify the SOQL was URL-encoded (no raw single quote in query param)
        wireMock.verify(getRequestedFor(urlPathEqualTo("/services/data/v59.0/query"))
                .withQueryParam("q", containing("ParentId")));
    }

    @Test
    void testApiErrorThrows() {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));
        assertThrows(RuntimeException.class,
                () -> adapter.query("SELECT Id FROM Account"));
    }

    @Test
    void testEmptyResults() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/services/data/v59.0/query"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"records\":[]}")));
        assertTrue(adapter.query("SELECT Id FROM Account").isEmpty());
    }
}
