package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for ImportProfileDefinition serialization and validation logic.
 */
class ImportProfileDefinitionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testJsonRoundTrip() throws Exception {
        ImportProfileDefinition def = new ImportProfileDefinition();
        def.setProfileId("test-profile");
        def.setRepositoryId("bedroom");
        def.setTargetFolderPath("/import");
        def.setDedupePolicy("create_new_version");
        def.setUpdatePolicy("version_up_on_content_change");
        def.setVersioningPolicy("major");
        def.setDefaultClassification("Confidential");
        def.setRetentionDays(90);
        def.setAclSyncPolicy("inherit_from_folder");
        def.setSchedulerEnabled(true);
        def.setPreserveOriginalEml(true);
        def.setDefaultProfile(true);
        def.setSchedulerParams(Map.of("mailbox", "INBOX"));
        def.setAllowedArchetypes(List.of(SourceArchetype.MESSAGE_CONTEXT));
        def.setEnabled(true);

        String json = MAPPER.writeValueAsString(def);
        ImportProfileDefinition parsed = MAPPER.readValue(json, ImportProfileDefinition.class);

        assertEquals("test-profile", parsed.getProfileId());
        assertEquals("Confidential", parsed.getDefaultClassification());
        assertEquals(Integer.valueOf(90), parsed.getRetentionDays());
        assertTrue(parsed.isSchedulerEnabled());
        assertTrue(parsed.isPreserveOriginalEml());
        assertTrue(parsed.isDefaultProfile());
        assertEquals("INBOX", parsed.getSchedulerParams().get("mailbox"));
    }

    @Test
    void testIsConnectorAllowed_emptyList() {
        ImportProfileDefinition def = new ImportProfileDefinition();
        // null or empty allowedConnectorIds means any connector is allowed
        assertTrue(def.isConnectorAllowed("any-connector"));
    }

    @Test
    void testIsConnectorAllowed_specificList() {
        ImportProfileDefinition def = new ImportProfileDefinition();
        def.setAllowedConnectorIds(List.of("conn-a", "conn-b"));
        assertTrue(def.isConnectorAllowed("conn-a"));
        assertFalse(def.isConnectorAllowed("conn-c"));
    }

    @Test
    void testIsArchetypeAllowed_emptyList() {
        ImportProfileDefinition def = new ImportProfileDefinition();
        assertTrue(def.isArchetypeAllowed(SourceArchetype.FILE_SHARE));
    }

    @Test
    void testIsArchetypeAllowed_specificList() {
        ImportProfileDefinition def = new ImportProfileDefinition();
        def.setAllowedArchetypes(List.of(SourceArchetype.MESSAGE_CONTEXT));
        assertTrue(def.isArchetypeAllowed(SourceArchetype.MESSAGE_CONTEXT));
        assertFalse(def.isArchetypeAllowed(SourceArchetype.CHAT_CONTEXT));
    }

    @Test
    void testDedupeDefaults() {
        ImportProfileDefinition def = new ImportProfileDefinition();
        assertEquals("skip_if_same_version", def.getDedupePolicy());
        assertEquals("version_up_on_content_change", def.getUpdatePolicy());
        assertEquals("major", def.getVersioningPolicy());
        assertEquals("inherit_from_folder", def.getAclSyncPolicy());
    }

    @Test
    void testDedupeMatchByDefault() {
        ImportProfileDefinition def = new ImportProfileDefinition();
        assertEquals("source_id", def.getDedupeMatchBy(),
                "Default dedupeMatchBy must be source_id for backward compatibility");
    }

    @Test
    void testDedupeMatchByJsonRoundTrip() throws Exception {
        for (String matchBy : List.of("source_id", "filename", "source_id_or_filename")) {
            ImportProfileDefinition def = new ImportProfileDefinition();
            def.setProfileId("p-" + matchBy);
            def.setDedupeMatchBy(matchBy);
            String json = MAPPER.writeValueAsString(def);
            ImportProfileDefinition parsed = MAPPER.readValue(json, ImportProfileDefinition.class);
            assertEquals(matchBy, parsed.getDedupeMatchBy(),
                    "dedupeMatchBy should survive JSON round-trip: " + matchBy);
        }
    }

    @Test
    void testNewDedupePolicies() throws Exception {
        ImportProfileDefinition def = new ImportProfileDefinition();
        def.setProfileId("p");
        def.setDedupePolicy("create_new_if_parent_context_changed");
        String json = MAPPER.writeValueAsString(def);
        ImportProfileDefinition parsed = MAPPER.readValue(json, ImportProfileDefinition.class);
        assertEquals("create_new_if_parent_context_changed", parsed.getDedupePolicy());

        def.setDedupePolicy("replace_relationships_on_resync");
        json = MAPPER.writeValueAsString(def);
        parsed = MAPPER.readValue(json, ImportProfileDefinition.class);
        assertEquals("replace_relationships_on_resync", parsed.getDedupePolicy());
    }
}
