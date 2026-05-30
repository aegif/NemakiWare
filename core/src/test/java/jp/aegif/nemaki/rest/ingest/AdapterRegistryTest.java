package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdapterRegistry} and {@link AdapterDescriptor}.
 */
class AdapterRegistryTest {

    @Test
    void allExpectedAdaptersAreRegistered() {
        var expected = Set.of(
                "imap", "gmail_mail", "m365_mail",
                "slack", "teams", "mattermost", "chatwork",
                "notion", "salesforce",
                "box", "dropbox", "google_drive", "onedrive");
        assertEquals(expected, AdapterRegistry.allSourceSystems());
    }

    @Test
    void getReturnsNullForUnknown() {
        assertNull(AdapterRegistry.get("unknown_system"));
        assertFalse(AdapterRegistry.isRegistered("unknown_system"));
    }

    @Test
    void slackDescriptorIsCorrect() {
        var desc = AdapterRegistry.get("slack");
        assertNotNull(desc);
        assertEquals("Slack", desc.displayName());
        assertEquals(SourceArchetype.CHAT_CONTEXT, desc.archetype());
        assertEquals(Set.of("channelId"), desc.requiredParams());
        assertTrue(desc.optionalParams().contains("limit"));
        assertEquals(List.of("channelId"), desc.webhookScopeKeys());
        assertEquals(3, desc.apiCallsPerItem());
    }

    @Test
    void m365MailAllParamsOptional() {
        var desc = AdapterRegistry.get("m365_mail");
        assertNotNull(desc);
        // Both userId and folderId are optional (runtime defaults: /me and inbox)
        assertTrue(desc.optionalParams().contains("userId"));
        assertTrue(desc.optionalParams().contains("folderId"));
        assertTrue(desc.requiredParams().isEmpty(),
                "M365 Mail should have no required scheduler params");
        assertEquals(List.of("userId", "folderId"), desc.webhookScopeKeys());
    }

    @Test
    void m365MailValidationPassesWithoutParams() {
        var desc = AdapterRegistry.get("m365_mail");
        // All params optional → empty params is valid
        assertTrue(desc.validateParams(Map.of()).isEmpty());
        assertTrue(desc.validateParams(Map.of("folderId", "inbox")).isEmpty());
        assertTrue(desc.validateParams(Map.of("userId", "u@co.com", "folderId", "inbox")).isEmpty());
    }

    @Test
    void teamsRequiresTeamIdAndChannelId() {
        var desc = AdapterRegistry.get("teams");
        assertNotNull(desc);
        assertEquals(Set.of("teamId", "channelId"), desc.requiredParams());
        assertEquals(List.of("teamId", "channelId"), desc.webhookScopeKeys());
    }

    @Test
    void validateParams_slackMissingChannelId() {
        var desc = AdapterRegistry.get("slack");
        var errors = desc.validateParams(Map.of());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("channelId"));
    }

    @Test
    void validateParams_slackValid() {
        var desc = AdapterRegistry.get("slack");
        var errors = desc.validateParams(Map.of("channelId", "C01ABC"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void validateParams_m365AcceptsEmptyParams() {
        // M365 has no required params (all optional with runtime defaults)
        var desc = AdapterRegistry.get("m365_mail");
        assertTrue(desc.validateParams(Map.of()).isEmpty());
    }

    @Test
    void validateParams_noRequiredParams() {
        // Gmail has no required params
        var desc = AdapterRegistry.get("gmail_mail");
        assertTrue(desc.validateParams(Map.of()).isEmpty());
        assertTrue(desc.validateParams(null).isEmpty());
    }

    @Test
    void forArchetypeFiltersCorrectly() {
        var chatAdapters = AdapterRegistry.forArchetype(SourceArchetype.CHAT_CONTEXT);
        assertEquals(4, chatAdapters.size()); // slack, teams, mattermost, chatwork
        assertTrue(chatAdapters.stream().allMatch(d -> d.archetype() == SourceArchetype.CHAT_CONTEXT));
    }

    @Test
    void forArchetypeFileShareIncludesCloudDrive() {
        var fileAdapters = AdapterRegistry.forArchetype(SourceArchetype.FILE_SHARE);
        assertTrue(fileAdapters.stream().anyMatch(d -> "box".equals(d.sourceSystem())));
        assertTrue(fileAdapters.stream().anyMatch(d -> "google_drive".equals(d.sourceSystem())));
    }

    // ── AdapterHttpClient encoding tests ────────────────────────

    @Test
    void encodeDecodePathSegmentRoundTrip() {
        // Basic round-trip
        assertEquals("hello", AdapterHttpClient.decodePathSegment(AdapterHttpClient.encodePathSegment("hello")));
        // Space: encode → %20, decode preserves
        assertEquals("a b", AdapterHttpClient.decodePathSegment(AdapterHttpClient.encodePathSegment("a b")));
        // @: encode → %40, decode restores
        assertEquals("user@co.com", AdapterHttpClient.decodePathSegment(AdapterHttpClient.encodePathSegment("user@co.com")));
    }

    @Test
    void decodePathSegmentPreservesLiteralPlus() {
        // + in path segments is literal, not space
        assertEquals("user+tag", AdapterHttpClient.decodePathSegment("user+tag"));
        assertEquals("a+b", AdapterHttpClient.decodePathSegment("a+b"));
    }

    @Test
    void encodePathSegmentConvertsSpaceToPercent20() {
        String encoded = AdapterHttpClient.encodePathSegment("hello world");
        assertTrue(encoded.contains("%20"), "Space should be %20 not +");
        assertFalse(encoded.contains("+"), "Should not contain + for spaces");
    }

    @Test
    void validateExternalUrlBlocksIpv6TransitionWrappedPrivateTargets() {
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.validateExternalUrl("http://[64:ff9b::7f00:1]/file"));
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.validateExternalUrl("http://[64:ff9b::a9fe:a9fe]/file"));
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.validateExternalUrl("http://[2002:7f00:1::]/file"));
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.validateExternalUrl("http://[64:ff9b:1:7f00:0:100::]/file"));
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.validateExternalUrl("http://[2001:0:4136:e378:8000:63bf:ffff:fffe]/file"));
    }

    @Test
    void validateExternalUrlBlocksIpv4SpecialUseRanges() {
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.validateExternalUrl("http://100.64.0.1/file"));
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.validateExternalUrl("http://198.18.0.1/file"));
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.validateExternalUrl("http://240.0.0.1/file"));
    }

    @Test
    void sharedHttpClientDoesNotFollowRedirectsAutomatically() {
        assertEquals(HttpClient.Redirect.NEVER, AdapterHttpClient.shared().followRedirects());
    }

    @Test
    void allParamsCombinesRequiredAndOptional() {
        var desc = AdapterRegistry.get("teams");
        var all = desc.allParams();
        assertTrue(all.contains("teamId"));      // required
        assertTrue(all.contains("channelId"));    // required
        assertTrue(all.contains("limit"));         // optional
    }
}
