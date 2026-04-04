package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for ConnectorDefinition serialization and field behavior.
 */
public class ConnectorDefinitionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testJsonRoundTrip() throws Exception {
        ConnectorDefinition def = new ConnectorDefinition();
        def.setConnectorId("test-conn");
        def.setDisplayName("Test");
        def.setSourceArchetype(SourceArchetype.MESSAGE_CONTEXT);
        def.setSourceSystem("imap");
        def.setAuthType("password");
        def.setCredentialRef("secret123");
        def.setWebhookSecret("whsec_abc");
        def.setEndpoint("imap.example.com:993");
        def.setTenantId("user@example.com");
        def.setRateLimitRpm(60);
        def.setEnabled(true);

        String json = MAPPER.writeValueAsString(def);
        ConnectorDefinition parsed = MAPPER.readValue(json, ConnectorDefinition.class);

        assertEquals("test-conn", parsed.getConnectorId());
        assertEquals(SourceArchetype.MESSAGE_CONTEXT, parsed.getSourceArchetype());
        assertEquals("imap", parsed.getSourceSystem());
        assertEquals("secret123", parsed.getCredentialRef());
        assertEquals("whsec_abc", parsed.getWebhookSecret());
        assertEquals(Integer.valueOf(60), parsed.getRateLimitRpm());
        assertTrue(parsed.isEnabled());
    }

    @Test
    public void testUnknownFieldsIgnored() throws Exception {
        String json = "{\"connectorId\":\"x\",\"unknownField\":\"ignored\",\"enabled\":true}";
        ConnectorDefinition parsed = MAPPER.readValue(json, ConnectorDefinition.class);
        assertEquals("x", parsed.getConnectorId());
        assertTrue(parsed.isEnabled());
    }

    @Test
    public void testNullFieldsSerialization() throws Exception {
        ConnectorDefinition def = new ConnectorDefinition();
        def.setConnectorId("minimal");
        String json = MAPPER.writeValueAsString(def);
        // @JsonInclude(NON_NULL) means null fields are omitted
        assertFalse(json.contains("credentialRef"));
        assertFalse(json.contains("webhookSecret"));
        assertFalse(json.contains("rateLimitRpm"));
    }
}
