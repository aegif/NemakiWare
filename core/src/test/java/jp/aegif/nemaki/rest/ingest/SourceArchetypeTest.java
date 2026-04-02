package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceArchetypeTest {

    @Test
    void testAllFiveValuesExist() {
        assertEquals(5, SourceArchetype.values().length);
        assertNotNull(SourceArchetype.valueOf("FILE_SHARE"));
        assertNotNull(SourceArchetype.valueOf("COMPOUND_NOTE"));
        assertNotNull(SourceArchetype.valueOf("CHAT_CONTEXT"));
        assertNotNull(SourceArchetype.valueOf("BUSINESS_RECORD"));
        assertNotNull(SourceArchetype.valueOf("MESSAGE_CONTEXT"));
    }

    @Test
    void testValueOfRoundTrip() {
        for (SourceArchetype a : SourceArchetype.values()) {
            assertEquals(a, SourceArchetype.valueOf(a.name()));
        }
    }
}
