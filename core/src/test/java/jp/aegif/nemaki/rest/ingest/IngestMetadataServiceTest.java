package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.rest.ingest.mail.MailMessageParser.ParsedMailMessage;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IngestMetadataService utility methods and property building.
 */
class IngestMetadataServiceTest {

    // ── addStringProp ──

    @Test
    void addStringProp_addsNonBlankValue() {
        List<Property> props = new ArrayList<>();
        IngestMetadataService.addStringProp(props, "key1", "value1");
        assertEquals(1, props.size());
        assertEquals("key1", props.get(0).getKey());
        assertEquals("value1", props.get(0).getValue());
    }

    @Test
    void addStringProp_skipsNull() {
        List<Property> props = new ArrayList<>();
        IngestMetadataService.addStringProp(props, "key1", null);
        assertTrue(props.isEmpty());
    }

    @Test
    void addStringProp_skipsBlank() {
        List<Property> props = new ArrayList<>();
        IngestMetadataService.addStringProp(props, "key1", "  ");
        assertTrue(props.isEmpty());
    }

    @Test
    void addStringProp_skipsNonString() {
        List<Property> props = new ArrayList<>();
        IngestMetadataService.addStringProp(props, "key1", 123);
        assertTrue(props.isEmpty());
    }

    // ── resolveMetadataString ──

    @Test
    void resolveMetadataString_extractsValue() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setMetadata(Map.of("key1", "value1", "key2", 42));
        assertEquals("value1", IngestMetadataService.resolveMetadataString(req, "key1"));
    }

    @Test
    void resolveMetadataString_returnsNullForMissingKey() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setMetadata(Map.of("key1", "value1"));
        assertNull(IngestMetadataService.resolveMetadataString(req, "missing"));
    }

    @Test
    void resolveMetadataString_returnsNullForNonString() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setMetadata(Map.of("key1", 42));
        assertNull(IngestMetadataService.resolveMetadataString(req, "key1"));
    }

    @Test
    void resolveMetadataString_returnsNullForBlank() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setMetadata(Map.of("key1", "  "));
        assertNull(IngestMetadataService.resolveMetadataString(req, "key1"));
    }

    @Test
    void resolveMetadataString_nullMetadata() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        assertNull(IngestMetadataService.resolveMetadataString(req, "key1"));
    }

    // ── buildMessageProperties: internetMessageId dedup regression ──

    @Test
    void buildMessageProperties_metadataOverridesParsedInternetMessageId() {
        // Both parsed EML and metadata provide internetMessageId —
        // metadata value (e.g., from Graph API) must win, and there must be exactly 1 entry
        ParsedMailMessage parsed = new ParsedMailMessage(
                "<parsed@example.com>", "Subject", "from@test.com",
                "to@test.com", null, null, null, null, null, null,
                "body", null, List.of());
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setMetadata(Map.of("internetMessageId", "<graph@contoso.com>",
                               "mailboxId", "inbox"));

        List<Property> props = IngestMetadataService.buildMessageProperties(parsed, req);

        // Count nemaki:internetMessageId entries — must be exactly 1
        long count = props.stream()
                .filter(p -> "nemaki:internetMessageId".equals(p.getKey()))
                .count();
        assertEquals(1, count, "internetMessageId must appear exactly once (no duplicates)");

        // The metadata value must override the parsed EML value
        String value = props.stream()
                .filter(p -> "nemaki:internetMessageId".equals(p.getKey()))
                .map(p -> (String) p.getValue())
                .findFirst().orElse(null);
        assertEquals("<graph@contoso.com>", value, "Metadata internetMessageId should override parsed EML value");
    }

    @Test
    void buildMessageProperties_parsedOnlyInternetMessageId() {
        // Only parsed EML provides internetMessageId — should be used as-is
        ParsedMailMessage parsed = new ParsedMailMessage(
                "<parsed@example.com>", "Subject", "from@test.com",
                null, null, null, null, null, null, null,
                "body", null, List.of());
        ExternalIngestRequest req = new ExternalIngestRequest();

        List<Property> props = IngestMetadataService.buildMessageProperties(parsed, req);

        long count = props.stream()
                .filter(p -> "nemaki:internetMessageId".equals(p.getKey()))
                .count();
        assertEquals(1, count);
        String value = props.stream()
                .filter(p -> "nemaki:internetMessageId".equals(p.getKey()))
                .map(p -> (String) p.getValue())
                .findFirst().orElse(null);
        assertEquals("<parsed@example.com>", value);
    }

    @Test
    void buildMessageProperties_neitherParsedNorMetadataInternetMessageId() {
        ParsedMailMessage parsed = new ParsedMailMessage(
                null, "Subject", "from@test.com",
                null, null, null, null, null, null, null,
                "body", null, List.of());
        ExternalIngestRequest req = new ExternalIngestRequest();

        List<Property> props = IngestMetadataService.buildMessageProperties(parsed, req);

        long count = props.stream()
                .filter(p -> "nemaki:internetMessageId".equals(p.getKey()))
                .count();
        assertEquals(0, count, "No internetMessageId should be present");
    }

    @Test
    void buildMessageProperties_allFieldsPopulated() {
        ParsedMailMessage parsed = new ParsedMailMessage(
                "<msg@test>", "Hello", "sender@test.com",
                "recipient@test.com", "cc@test.com", null,
                new Date(), null, "<reply@test>", "<ref@test>",
                "body", null, List.of());
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setMetadata(Map.of("mailboxId", "inbox", "messageStableId", "stable-1"));

        List<Property> props = IngestMetadataService.buildMessageProperties(parsed, req);

        // Verify no duplicate keys
        Set<String> keys = new HashSet<>();
        for (Property p : props) {
            assertTrue(keys.add(p.getKey()), "Duplicate key: " + p.getKey());
        }
        assertTrue(keys.contains("nemaki:internetMessageId"));
        assertTrue(keys.contains("nemaki:mailSubject"));
        assertTrue(keys.contains("nemaki:mailFrom"));
        assertTrue(keys.contains("nemaki:mailTo"));
        assertTrue(keys.contains("nemaki:mailCc"));
        assertTrue(keys.contains("nemaki:mailSentAt"));
        assertTrue(keys.contains("nemaki:mailboxId"));
        assertTrue(keys.contains("nemaki:messageStableId"));
    }
}
