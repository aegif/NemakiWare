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

    // ─── RC6.8 P1: pinRequestToValidatedAddress (DNS rebinding close) ───

    @Test
    void pinRequestRewritesHttpUriToValidatedIpv4Literal() throws Exception {
        // 8.8.8.8 resolves to itself (literal IP); pinning should produce a URI
        // whose host is the IP literal (no DNS dependency at this layer).
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://8.8.8.8/some/path?q=1"))
                .GET().build();
        var pinned = AdapterHttpClient.pinRequestToValidatedAddress(req);
        assertEquals("8.8.8.8", pinned.uri().getHost(),
                "HTTP path should pin URI host to validated IPv4 literal");
        assertEquals("/some/path", pinned.uri().getRawPath());
        assertEquals("q=1", pinned.uri().getRawQuery());
    }

    @Test
    void pinRequestLeavesHttpsUriUnchanged() throws Exception {
        // HTTPS: TLS cert verification handles DNS rebinding, so the URI is
        // returned unchanged (re-validation still happens — verified by the
        // SecurityException tests below).
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://8.8.8.8/api/x"))
                .GET().build();
        var pinned = AdapterHttpClient.pinRequestToValidatedAddress(req);
        assertEquals("https", pinned.uri().getScheme());
        assertEquals("8.8.8.8", pinned.uri().getHost(),
                "HTTPS path returns request unchanged");
        assertEquals("/api/x", pinned.uri().getRawPath());
    }

    @Test
    void pinRequestThrowsWhenHostResolvesToBlockedIpv4() {
        // 127.0.0.1 literal resolves to loopback → must throw at pin time.
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1/file"))
                .GET().build();
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.pinRequestToValidatedAddress(req));
    }

    @Test
    void pinRequestThrowsWhenHostResolvesToBlockedIpv6Transition() {
        // 64:ff9b::a9fe:a9fe = NAT64 wrap of 169.254.169.254 (cloud metadata).
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://[64:ff9b::a9fe:a9fe]/latest/"))
                .GET().build();
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.pinRequestToValidatedAddress(req));
    }

    @Test
    void pinRequestPreservesNonRestrictedHeadersOnHttpPin() throws Exception {
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://8.8.8.8/api"))
                .header("Authorization", "Bearer abc")
                .header("X-Custom-Trace", "trace-123")
                .GET().build();
        var pinned = AdapterHttpClient.pinRequestToValidatedAddress(req);
        assertEquals(java.util.Optional.of("Bearer abc"),
                pinned.headers().firstValue("Authorization"));
        assertEquals(java.util.Optional.of("trace-123"),
                pinned.headers().firstValue("X-Custom-Trace"));
    }

    @Test
    void pinRequestPreservesOriginalHostHeaderOnHttpPin() throws Exception {
        // RC6.9 P3 fix: the pinned URI uses the IP literal as authority,
        // but the Host header must carry the ORIGINAL hostname so that
        // name-based virtual-host servers route to the correct vhost.
        // 8.8.8.8 resolves to itself so the "hostname" here IS 8.8.8.8;
        // for a real DNS name this header would be the original hostname.
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://8.8.8.8:8080/path"))
                .GET().build();
        var pinned = AdapterHttpClient.pinRequestToValidatedAddress(req);
        // URI should be IP literal-based with the same port + path.
        assertEquals("8.8.8.8", pinned.uri().getHost());
        assertEquals(8080, pinned.uri().getPort());
        // Host header should carry the ORIGINAL authority (hostname[:port]).
        assertEquals(java.util.Optional.of("8.8.8.8:8080"),
                pinned.headers().firstValue("Host"),
                "Pinned HTTP request must preserve original Host:port for vhost routing");
    }

    @Test
    void pinRequestPreservesOriginalHostHeaderWithoutPort() throws Exception {
        // Default port 80 case — Host header has no :port suffix.
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://8.8.8.8/"))
                .GET().build();
        var pinned = AdapterHttpClient.pinRequestToValidatedAddress(req);
        assertEquals(java.util.Optional.of("8.8.8.8"),
                pinned.headers().firstValue("Host"));
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
