package jp.aegif.nemaki.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NemakiwareMcpServer#redactArguments(Map)}.
 *
 * <p>The MCP server's debug log path used to dump the raw {@code arguments}
 * map, so {@code login}/{@code apikey_login} calls would write their
 * password / API key to the application log. This test pins the redaction
 * contract for the keys MCP tools currently use.
 */
class NemakiwareMcpServerRedactionTest {

    @Test
    void redactArguments_replacesPasswordToken() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("username", "alice");
        args.put("password", "super-secret");
        Map<String, Object> redacted = NemakiwareMcpServer.redactArguments(args);
        assertEquals("alice", redacted.get("username"));
        assertEquals("[REDACTED]", redacted.get("password"));
    }

    @Test
    void redactArguments_redactsAllKnownSensitiveKeys() {
        for (String key : new String[]{
                "password", "passwd", "pwd",
                "apiKey", "api_key",
                "secret", "token",
                "sessionToken", "session_token",
                "accessToken", "access_token",
                "refreshToken", "refresh_token",
                "credential", "credentials",
                "Authorization",
                "clientSecret", "client_secret",
                "privateKey", "private_key"}) {
            Map<String, Object> args = new HashMap<>();
            args.put(key, "must-not-leak");
            Map<String, Object> redacted = NemakiwareMcpServer.redactArguments(args);
            assertEquals("[REDACTED]", redacted.get(key),
                    "key '" + key + "' should be redacted (case-insensitive)");
        }
    }

    @Test
    void redactArguments_preservesNonSensitiveValuesAndAbbreviatesLongOnes() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "find me documents about security");
        args.put("limit", 10);
        args.put("description", "x".repeat(500));
        Map<String, Object> redacted = NemakiwareMcpServer.redactArguments(args);
        assertEquals("find me documents about security", redacted.get("query"));
        assertEquals("10", redacted.get("limit"));
        String desc = (String) redacted.get("description");
        assertNotNull(desc);
        assertTrue(desc.endsWith("..."));
        assertTrue(desc.length() < 500);
    }

    @Test
    void redactArguments_recursesIntoNestedMaps() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("password", "deep-secret");
        nested.put("display", "ok");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("auth", nested);
        Map<String, Object> redacted = NemakiwareMcpServer.redactArguments(args);
        assertTrue(redacted.get("auth") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> redactedAuth = (Map<String, Object>) redacted.get("auth");
        assertEquals("[REDACTED]", redactedAuth.get("password"));
        assertEquals("ok", redactedAuth.get("display"));
    }

    @Test
    void redactArguments_handlesNullAndEmpty() {
        assertTrue(NemakiwareMcpServer.redactArguments(null).isEmpty());
        assertTrue(NemakiwareMcpServer.redactArguments(new HashMap<>()).isEmpty());
    }

    @Test
    void redactArguments_keepsNullValueAsNull() {
        Map<String, Object> args = new HashMap<>();
        args.put("optional", null);
        Map<String, Object> redacted = NemakiwareMcpServer.redactArguments(args);
        assertTrue(redacted.containsKey("optional"));
        assertNull(redacted.get("optional"));
    }
}
