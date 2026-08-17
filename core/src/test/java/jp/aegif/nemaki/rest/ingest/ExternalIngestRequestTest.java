package jp.aegif.nemaki.rest.ingest;

import tools.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.util.Map;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Unit tests for ExternalIngestRequest serialization behavior.
 */
public class ExternalIngestRequestTest {

    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    @Test
    public void testRequestIdGenerated() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        assertNotNull(req.getRequestId());
        assertTrue(req.getRequestId().startsWith("ingest-"));
    }

    @Test
    public void testJsonRoundTrip() throws Exception {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("src-123");
        req.setSourceObjectType("message");
        req.setSourceUrl("https://example.com/msg/123");
        req.setFileName("test.eml");
        req.setMimeType("message/rfc822");
        req.setIdempotencyKey("idem-key");
        req.setCorrelationId("corr-001");
        req.setExecutionMode("scheduled");
        req.setDryRun(true);
        req.setMetadata(Map.of("mailboxId", "INBOX", "key", "value"));

        String json = MAPPER.writeValueAsString(req);
        ExternalIngestRequest parsed = MAPPER.readValue(json, ExternalIngestRequest.class);

        assertEquals("p1", parsed.getProfileId());
        assertEquals("c1", parsed.getConnectorId());
        assertEquals("src-123", parsed.getSourceObjectId());
        assertEquals("message", parsed.getSourceObjectType());
        assertEquals("https://example.com/msg/123", parsed.getSourceUrl());
        assertEquals("test.eml", parsed.getFileName());
        assertEquals("idem-key", parsed.getIdempotencyKey());
        assertEquals("corr-001", parsed.getCorrelationId());
        assertTrue(parsed.isDryRun());
        assertEquals("INBOX", parsed.getMetadata().get("mailboxId"));
    }

    @Test
    public void testContentStreamJsonIgnored() throws Exception {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setContentStream(new ByteArrayInputStream("test content".getBytes()));

        String json = MAPPER.writeValueAsString(req);
        // contentStream should NOT appear in JSON
        assertFalse(json.contains("contentStream"));
        assertFalse(json.contains("test content"));
    }

    @Test
    public void testUnknownFieldsIgnored() throws Exception {
        String json = "{\"profileId\":\"x\",\"futureField\":\"ignored\"}";
        ExternalIngestRequest parsed = MAPPER.readValue(json, ExternalIngestRequest.class);
        assertEquals("x", parsed.getProfileId());
    }

    @Test
    public void testMetadataPreservation() throws Exception {
        // Verify that complex metadata survives JSON round-trip
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setMetadata(Map.of(
                "channelId", "C123",
                "threadId", "T456",
                "messageText", "Hello, world!",
                "senderId", "U789"
        ));

        String json = MAPPER.writeValueAsString(req);
        ExternalIngestRequest parsed = MAPPER.readValue(json, ExternalIngestRequest.class);

        assertEquals("C123", parsed.getMetadata().get("channelId"));
        assertEquals("Hello, world!", parsed.getMetadata().get("messageText"));
    }
}
