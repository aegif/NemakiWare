package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * REST endpoint for receiving inbound webhooks from external sources.
 *
 * <p>Each connector can receive push notifications at:
 * {@code POST /v1/ingest-webhook/{connectorId}}
 *
 * <p>Supported webhook protocols:
 * <ul>
 *   <li>Slack Events API (url_verification + event_callback)</li>
 *   <li>Microsoft Graph change notifications (validationToken + changeNotification; validation
 *       handshake uses {@code Content-Type: text/plain} per Microsoft)</li>
 *   <li>Chatwork webhooks (X-ChatWorkWebhookSignature)</li>
 *   <li>Box Webhooks V2 (BOX-SIGNATURE-PRIMARY/SECONDARY over body+timestamp;
 *       FILE.* triggers refetch the parent folder)</li>
 *   <li>Dropbox webhooks (GET challenge handshake + X-Dropbox-Signature;
 *       notification triggers an incremental cursor fetch)</li>
 *   <li>Generic JSON payload (other systems)</li>
 * </ul>
 *
 * <p>Box and Dropbox webhook subscriptions are registered in the provider's
 * own console (Box Developer / Dropbox App Console) pointing at
 * {@code .../ingest-webhook/{connectorId}}; unlike Graph there is no
 * server-side subscription API for them.
 */
@RestController
@RequestMapping("/v1/ingest-webhook")
// No @CrossOrigin — webhook endpoints are server-to-server, not browser-accessible
public class IngestWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(IngestWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Microsoft Graph subscription handshake echoes {@code validationToken} without signature verification.
     * Only {@code teams} and {@code m365_mail} connectors must use this; other source systems must not.
     */
    static boolean isMicrosoftGraphSubscriptionValidation(String sourceSystem, String validationToken) {
        if (validationToken == null || validationToken.isBlank()) {
            return false;
        }
        return "teams".equals(sourceSystem) || "m365_mail".equals(sourceSystem);
    }

    /** Per-connector rate limiter: max 100 webhook calls per minute. */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> webhookRateCounters =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int WEBHOOK_MAX_PER_MINUTE = 100;

    private boolean isWebhookRateLimited(String connectorId) {
        long now = System.currentTimeMillis();
        // Periodic cleanup: remove entries older than 1 hour to prevent unbounded growth
        if (webhookRateCounters.size() > 500) {
            webhookRateCounters.entrySet().removeIf(e -> now - e.getValue()[1] > 3_600_000);
        }
        long[] counter = webhookRateCounters.computeIfAbsent(connectorId, k -> new long[]{0, now});
        synchronized (counter) {
            if (now - counter[1] > 60_000) {
                counter[0] = 1;
                counter[1] = now;
                return false;
            }
            counter[0]++;
            return counter[0] > WEBHOOK_MAX_PER_MINUTE;
        }
    }

    @Autowired
    private ConnectorDefinitionService connectorDefinitionService;

    @Autowired
    private IngestSchedulerService schedulerService;

    @Autowired
    private ImportProfileDefinitionService profileService;

    @Autowired
    private HttpServletRequest httpRequest;

    @Autowired(required = false)
    private jp.aegif.nemaki.util.PropertyManager propertyManager;

    /**
     * Receive webhook from external source.
     * Handles Slack url_verification, Graph validationToken, and actual event payloads.
     */
    @PostMapping(value = "/{connectorId}", consumes = {
            MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_PLAIN_VALUE
    })
    public ResponseEntity<?> receiveWebhook(
            @PathVariable String connectorId,
            @RequestParam(value = "validationToken", required = false) String validationToken,
            @RequestBody(required = false) String rawBody) {
        if (rawBody == null) {
            rawBody = "";
        }
        // Payload size limit (10 MB) to prevent OOM from oversized webhook payloads
        if (rawBody.length() > 10_000_000) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "Webhook payload too large"));
        }

        // 1. Resolve connector — return uniform 401 for not-found/disabled to prevent
        // connector ID enumeration via status code differences
        ConnectorDefinition connector = connectorDefinitionService.get(connectorId);
        if (connector == null || !connector.isEnabled()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Signature verification failed"));
        }

        String system = connector.getSourceSystem();

        // 2. Microsoft Graph: subscription validation (Teams / M365 Mail only)
        // Graph sends validationToken during subscription creation — server must echo it back.
        // Only Graph-backed connectors use this handshake; Slack/Chatwork/generic must not bypass verifySignature.
        if (isMicrosoftGraphSubscriptionValidation(system, validationToken)) {
            logger.info("Graph subscription validation for connector {}", connectorId);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(validationToken);
        }

        // 3. Verify signature FIRST (before processing any payload)
        if (!verifySignature(connector, rawBody)) {
            logger.warn("Webhook signature verification failed for connector {}", connectorId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Signature verification failed"));
        }

        // 4. Rate limit AFTER signature verification to prevent unauthenticated exhaustion
        if (isWebhookRateLimited(connectorId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Webhook rate limit exceeded"));
        }

        // 4. Parse and dispatch payload
        try {
            JsonNode payload = MAPPER.readTree(rawBody);

            // 4a. Slack: url_verification challenge (after signature verified)
            if (payload.has("type") && "url_verification".equals(payload.get("type").asText())) {
                String challenge = payload.path("challenge").asText();
                logger.info("Slack url_verification for connector {}", connectorId);
                return ResponseEntity.ok(Map.of("challenge", challenge));
            }

            // 4. Dispatch based on source system
            return switch (system != null ? system : "") {
                case "slack" -> handleSlackEvent(connector, payload);
                case "teams", "m365_mail" -> handleGraphNotification(connector, payload);
                case "chatwork" -> handleChatworkEvent(connector, payload);
                case "box" -> handleBoxEvent(connector, payload);
                case "dropbox" -> handleDropboxEvent(connector, payload);
                default -> handleGenericWebhook(connector, payload);
            };

        } catch (Exception e) {
            logger.error("Webhook processing failed for {}: {}", connectorId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Webhook processing failed"));
        }
    }

    /**
     * Webhook verification handshake (GET).
     *
     * <p>Dropbox verifies a webhook URL by issuing
     * {@code GET /{connectorId}?challenge=...} and expecting the challenge
     * value echoed back verbatim as {@code text/plain}. We gate this on the
     * connector being an enabled Dropbox connector and the challenge being
     * present, so the endpoint is not a general open echo. {@code nosniff}
     * is set per Dropbox guidance.
     *
     * <p>Note: Dropbox requires this handshake to be <em>unauthenticated</em>
     * (no signature is sent during URL verification), so a {@code 200} vs
     * {@code 404} difference can reveal that an enabled Dropbox connector with a
     * given id exists. This only leaks connector existence (no secret), and the
     * connectorId is already embedded in the operator-registered webhook URL.
     * Operators who care should use non-guessable connector ids.
     */
    @GetMapping("/{connectorId}")
    public ResponseEntity<?> verifyWebhook(
            @PathVariable String connectorId,
            @RequestParam(value = "challenge", required = false) String challenge) {
        ConnectorDefinition connector = connectorDefinitionService.get(connectorId);
        if (connector == null || !connector.isEnabled()
                || !"dropbox".equals(connector.getSourceSystem())
                || challenge == null || challenge.isBlank()
                || challenge.length() > 1024) {
            // Uniform 404 — don't reveal connector existence/type, and bound the
            // echoed value so it can't be used for response amplification.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Content-Type-Options", "nosniff")
                .body(challenge);
    }

    /**
     * Handle Slack event_callback — triggers a fetch for the relevant channel.
     */
    private ResponseEntity<?> handleSlackEvent(ConnectorDefinition connector, JsonNode payload) {
        String eventType = payload.path("event").path("type").asText();
        if (!"message".equals(eventType) && !"file_shared".equals(eventType)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "eventType", eventType));
        }

        String channelId = payload.path("event").path("channel").asText(null);
        if (channelId == null) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no channel"));
        }

        // Find profiles whose scope keys match the event context.
        // Uses the adapter registry's webhookScopeKeys as the single source of truth.
        Map<String, String> eventScope = Map.of("channelId", channelId);
        List<ImportProfileDefinition> matchingProfiles = filterProfilesByScope(connector, eventScope);

        if (matchingProfiles.isEmpty()) {
            logger.info("Slack webhook: no profile matches channel {} on connector {}", channelId, connector.getConnectorId());
            return ResponseEntity.ok(Map.of("status", "no_matching_profile", "channel", channelId));
        }

        // Trigger async fetch for each matching profile
        for (ImportProfileDefinition profile : matchingProfiles) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("channelId", channelId);
            params.put("limit", "10");
            triggerFetchAsync(profile, connector, params);
        }

        return ResponseEntity.ok(Map.of("status", "accepted", "channel", channelId, "profiles", matchingProfiles.size()));
    }

    /**
     * Handle Microsoft Graph change notification — triggers a fetch for the relevant resource.
     */
    private ResponseEntity<?> handleGraphNotification(ConnectorDefinition connector, JsonNode payload) {
        JsonNode notifications = payload.path("value");
        if (!notifications.isArray() || notifications.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "no_notifications"));
        }

        List<ImportProfileDefinition> profiles = findAllProfilesForConnector(connector);
        if (profiles.isEmpty()) {
            logger.warn("No profile found for Graph webhook connector {}", connector.getConnectorId());
            return ResponseEntity.ok(Map.of("status", "no_profile"));
        }

        // Process each notification — match to the profile whose schedulerParams
        // Match each notification's resource against profile scope keys using the registry.
        // Graph resources contain scope values inline (e.g., "teams('T01')/channels('C01')/messages").
        AdapterDescriptor desc = AdapterRegistry.get(connector.getSourceSystem());
        java.util.List<String> scopeKeys = (desc != null) ? desc.webhookScopeKeys() : List.of();

        int triggered = 0;
        int notifCount = 0;
        final int MAX_NOTIFICATIONS = 100;
        for (JsonNode notification : notifications) {
            if (++notifCount > MAX_NOTIFICATIONS) {
                logger.warn("Graph webhook truncated at {} notifications", MAX_NOTIFICATIONS);
                break;
            }
            String resource = notification.path("resource").asText(null);
            if (resource == null) continue;

            // Parse scope values from the Graph resource path.
            // Teams: "teams('T01')/channels('C01')/messages" → {teamId=T01, channelId=C01}
            // M365:  "users/user@co.com/mailFolders('inbox')/messages" → {userId=user@co.com, folderId=inbox}
            Map<String, String> resourceScope = parseGraphResourceScope(resource);

            for (ImportProfileDefinition profile : profiles) {
                Map<String, String> sp = profile.getSchedulerParams();
                // Skip profiles without params when adapter requires scope keys
                if ((sp == null || sp.isEmpty()) && !scopeKeys.isEmpty()) continue;
                if (sp == null) sp = Map.of();

                // Must declare at least one scope key
                Set<String> requiredScopeKeys = (desc != null) ? desc.requiredParams() : Set.of();
                final Map<String, String> params = sp;
                boolean hasAnyScopeKey = scopeKeys.stream()
                        .anyMatch(k -> { String v = params.get(k); return v != null && !v.isBlank(); });
                if (!scopeKeys.isEmpty() && !hasAnyScopeKey) continue;

                boolean matches = true;
                for (String key : scopeKeys) {
                    String profileVal = sp.get(key);
                    // Apply runtime defaults for optional scope keys instead of wildcard.
                    // This ensures a profile without explicit folderId matches only "inbox"
                    // notifications, not all folders.
                    if (profileVal == null || profileVal.isBlank()) {
                        if (requiredScopeKeys.contains(key)) { matches = false; break; }
                        // For M365 Mail: omitted userId means /me, omitted folderId means inbox.
                        // These must NOT wildcard-match — a profile without userId should only
                        // match notifications that also lack userId (i.e., /me subscriptions).
                        if ("userId".equals(key)) {
                            // Profile uses /me → only match if resource also has no userId
                            if (resourceScope.containsKey("userId")) { matches = false; break; }
                            continue;
                        }
                        if ("folderId".equals(key)) { profileVal = "inbox"; }
                        else continue; // Other optional keys: true wildcard
                    }
                    String resourceVal = resourceScope.get(key);
                    if (resourceVal == null || !profileVal.equals(resourceVal)) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    Map<String, String> fetchParams = new LinkedHashMap<>(sp);
                    fetchParams.put("limit", "10");
                    triggerFetchAsync(profile, connector, fetchParams);
                    triggered++;
                }
            }
        }

        return ResponseEntity.ok(Map.of("status", "accepted", "triggered", triggered));
    }

    /**
     * Handle generic webhook — log and trigger fetch with default params.
     */
    /**
     * Handle Chatwork webhook — message_created / message_updated events.
     */
    private ResponseEntity<?> handleChatworkEvent(ConnectorDefinition connector, JsonNode payload) {
        String eventType = payload.path("webhook_event_type").asText("");
        if (!"message_created".equals(eventType) && !"message_updated".equals(eventType)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "eventType", eventType));
        }

        String roomId = String.valueOf(payload.path("webhook_event").path("room_id").asLong(0));
        if ("0".equals(roomId)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no room_id"));
        }

        Map<String, String> eventScope = Map.of("roomId", roomId);
        List<ImportProfileDefinition> matchingProfiles = filterProfilesByScope(connector, eventScope);

        if (matchingProfiles.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "no_matching_profile", "room", roomId));
        }

        int triggered = 0;
        for (ImportProfileDefinition profile : matchingProfiles) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("roomId", roomId);
            params.put("limit", "10");
            triggerFetchAsync(profile, connector, params);
            triggered++;
        }
        return ResponseEntity.ok(Map.of("status", "accepted", "room", roomId, "profiles", triggered));
    }

    /**
     * Handle a Box webhook (Webhooks V2). Box delivers a single event with a
     * {@code trigger} (e.g. FILE.UPLOADED) and a {@code source} object. We act
     * only on FILE.* triggers and trigger an incremental fetch of the parent
     * folder for profiles that watch it (folderId scope).
     */
    private ResponseEntity<?> handleBoxEvent(ConnectorDefinition connector, JsonNode payload) {
        String trigger = payload.path("trigger").asText("");
        // Only file lifecycle events lead to new content to ingest.
        if (!trigger.startsWith("FILE.")) {
            return ResponseEntity.ok(Map.of("status", "ignored", "trigger", trigger));
        }
        JsonNode source = payload.path("source");
        String type = source.path("type").asText("");
        // For a file event the folder to refetch is the file's parent; for a
        // folder event it is the folder itself.
        String folderId = "folder".equals(type)
                ? source.path("id").asText(null)
                : source.path("parent").path("id").asText(null);
        if (folderId == null || folderId.isBlank()) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no folder id"));
        }

        Map<String, String> eventScope = Map.of("folderId", folderId);
        List<ImportProfileDefinition> matchingProfiles = filterProfilesByScope(connector, eventScope);
        if (matchingProfiles.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "no_matching_profile", "folderId", folderId));
        }

        int triggered = 0;
        for (ImportProfileDefinition profile : matchingProfiles) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("folderId", folderId);
            params.put("limit", "25");
            triggerFetchAsync(profile, connector, params);
            triggered++;
        }
        return ResponseEntity.ok(Map.of("status", "accepted", "folderId", folderId, "profiles", triggered));
    }

    /**
     * Handle a Dropbox webhook notification. Dropbox notifications carry no path
     * information — they only say "something changed for these accounts" — so
     * every profile on the connector does an incremental (cursor/checkpoint)
     * fetch, which pulls exactly the delta.
     */
    private ResponseEntity<?> handleDropboxEvent(ConnectorDefinition connector, JsonNode payload) {
        List<ImportProfileDefinition> profiles = findAllProfilesForConnector(connector);
        if (profiles.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "no_profile"));
        }
        int triggered = 0;
        for (ImportProfileDefinition profile : profiles) {
            Map<String, String> params = profile.getSchedulerParams() != null
                    ? new LinkedHashMap<>(profile.getSchedulerParams()) : new LinkedHashMap<>();
            params.put("limit", "25");
            triggerFetchAsync(profile, connector, params);
            triggered++;
        }
        return ResponseEntity.ok(Map.of("status", "accepted", "profiles", triggered));
    }

    private ResponseEntity<?> handleGenericWebhook(ConnectorDefinition connector, JsonNode payload) {
        List<ImportProfileDefinition> profiles = findAllProfilesForConnector(connector);
        if (profiles.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "no_profile"));
        }

        for (ImportProfileDefinition profile : profiles) {
            Map<String, String> params = profile.getSchedulerParams() != null
                    ? new LinkedHashMap<>(profile.getSchedulerParams()) : new LinkedHashMap<>();
            params.put("limit", "10");
            triggerFetchAsync(profile, connector, params);
        }

        return ResponseEntity.ok(Map.of("status", "accepted", "profiles", profiles.size()));
    }

    /**
     * Find the first profile that uses this connector as its default.
     * For webhook dispatch that needs a single profile.
     */
    private ImportProfileDefinition findProfileForConnector(ConnectorDefinition connector) {
        List<ImportProfileDefinition> profiles = findAllProfilesForConnector(connector);
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    /**
     * Find ALL enabled profiles explicitly linked to this connector.
     *
     * <p>For webhook dispatch, we require an explicit link: either
     * {@code defaultConnectorId == connectorId} or the connector appears
     * in {@code allowedConnectorIds}.  The broad "empty list → accept all"
     * semantics of {@link ImportProfileDefinition#isConnectorAllowed(String)}
     * is intentionally NOT used here to prevent unrelated profiles from
     * receiving webhook events.
     */
    private List<ImportProfileDefinition> findAllProfilesForConnector(ConnectorDefinition connector) {
        if (profileService == null) return List.of();
        String connId = connector.getConnectorId();
        return profileService.list().stream()
                .filter(ImportProfileDefinition::isEnabled)
                .filter(p -> connId.equals(p.getDefaultConnectorId())
                        || (p.getAllowedConnectorIds() != null
                            && !p.getAllowedConnectorIds().isEmpty()
                            && p.getAllowedConnectorIds().contains(connId)))
                .filter(p -> p.isArchetypeAllowed(connector.getSourceArchetype()))
                .toList();
    }

    /**
     * Filter profiles for a connector by matching event scope values against
     * the profile's schedulerParams, using the adapter registry's
     * {@link AdapterDescriptor#webhookScopeKeys()} as the key set.
     *
     * <p>A profile matches if, for every scope key that the profile declares,
     * the event's value equals the profile's value.  Profiles that don't declare
     * a scope key (null or blank) accept any event value for that key.
     */
    private List<ImportProfileDefinition> filterProfilesByScope(ConnectorDefinition connector,
                                                                 Map<String, String> eventScope) {
        List<ImportProfileDefinition> profiles = findAllProfilesForConnector(connector);
        AdapterDescriptor desc = AdapterRegistry.get(connector.getSourceSystem());
        java.util.List<String> scopeKeys = (desc != null) ? desc.webhookScopeKeys() : List.of();

        // Scope key enforcement:
        // - Keys in adapter.requiredParams: profile MUST declare them (e.g., Slack channelId)
        // - Keys only in webhookScopeKeys but NOT requiredParams: optional (e.g., M365 userId)
        //   If declared, must match; if omitted, treated as wildcard.
        Set<String> requiredScopeKeys = (desc != null) ? desc.requiredParams() : Set.of();

        return profiles.stream().filter(p -> {
            Map<String, String> sp = p.getSchedulerParams();
            if (sp == null || sp.isEmpty()) {
                return scopeKeys.isEmpty();
            }
            // Profile must declare at least one scope key to participate in webhook dispatch.
            // This prevents profiles with only non-scope params (e.g., {limit:10}) from matching.
            if (!scopeKeys.isEmpty()) {
                boolean hasAnyScopeKey = scopeKeys.stream()
                        .anyMatch(k -> { String v = sp.get(k); return v != null && !v.isBlank(); });
                if (!hasAnyScopeKey) return false;
            }
            for (String key : scopeKeys) {
                String profileVal = sp.get(key);
                if (profileVal == null || profileVal.isBlank()) {
                    if (requiredScopeKeys.contains(key)) return false;
                    // userId omission means /me — must not wildcard-match explicit users
                    if ("userId".equals(key)) {
                        if (eventScope.containsKey("userId")) return false;
                        continue;
                    }
                    // folderId omission means inbox — apply default, not wildcard
                    if ("folderId".equals(key)) {
                        profileVal = "inbox";
                    } else {
                        continue; // True wildcard for keys without defaults
                    }
                }
                String eventVal = eventScope.get(key);
                if (eventVal != null && !eventVal.isBlank() && !profileVal.equals(eventVal)) {
                    return false; // Value mismatch
                }
            }
            return true;
        }).toList();
    }

    /**
     * Parse scope values from a Graph notification resource path.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code teams('T01')/channels('C01')/messages} → {teamId=T01, channelId=C01}</li>
     *   <li>{@code users/user@co.com/mailFolders('inbox')/messages} → {userId=user@co.com, folderId=inbox}</li>
     * </ul>
     */
    /** Escape a value for use inside a Graph OData resource literal (single-quoted segments). */
    private static String escapeGraphId(String value) {
        if (value == null) return "";
        // OData single-quoted literals: escape ' as ''
        return value.replace("'", "''");
    }

    /** Unescape OData single-quoted literal (reverse of escapeGraphId). */
    private static String unescapeODataLiteral(String value) {
        return value != null ? value.replace("''", "'") : null;
    }

    static Map<String, String> parseGraphResourceScope(String resource) {
        Map<String, String> scope = new LinkedHashMap<>();
        if (resource == null) return scope;

        // Teams: teams('...')/channels('...')
        // Pattern accepts '' (escaped quotes) inside the literal
        java.util.regex.Matcher teamsMatcher = java.util.regex.Pattern
                .compile("teams\\('((?:[^']|'')+)'\\)/channels\\('((?:[^']|'')+)'\\)")
                .matcher(resource);
        if (teamsMatcher.find()) {
            scope.put("teamId", unescapeODataLiteral(teamsMatcher.group(1)));
            scope.put("channelId", unescapeODataLiteral(teamsMatcher.group(2)));
        }

        // M365 Mail: users/{userId}/mailFolders('...')
        java.util.regex.Matcher userMatcher = java.util.regex.Pattern
                .compile("users/([^/]+)")
                .matcher(resource);
        if (userMatcher.find()) {
            // Decode path-segment encoding (preserves literal + unlike URLDecoder)
            scope.put("userId", AdapterHttpClient.decodePathSegment(userMatcher.group(1)));
        }
        java.util.regex.Matcher folderMatcher = java.util.regex.Pattern
                .compile("mailFolders\\('((?:[^']|'')+)'\\)")
                .matcher(resource);
        if (folderMatcher.find()) {
            scope.put("folderId", unescapeODataLiteral(folderMatcher.group(1)));
        } else if (scope.containsKey("userId")) {
            // M365 Mail resource without explicit mailFolder → default to inbox
            // to match the runtime default in executeM365MailFetch
            scope.put("folderId", "inbox");
        }

        return scope;
    }

    /**
     * Trigger an async fetch (fire-and-forget) via the scheduler service.
     *
     * <p>For delegated profiles we re-run the same authorization the
     * scheduler applies per tick (creator still active, still holds
     * {@code cmis:all} on the target folder, connector still delegated
     * to the creator) and run the fetch under the delegating user's
     * synthesised CallContext. Without this, a signed webhook event
     * would keep ingesting under an unscoped admin context even after
     * the creator lost access or the admin revoked the delegation —
     * the bypass the scheduled path already prevents. Admin profiles
     * keep the legacy null-context (admin) behaviour.
     */
    private void triggerFetchAsync(ImportProfileDefinition profile, ConnectorDefinition connector,
                                   Map<String, String> params) {
        IngestSchedulerService.DelegatedAuthorization auth =
                schedulerService.authorizeDelegatedFetch(profile, connector);
        if (!auth.isAllowed()) {
            logger.warn("Webhook-triggered fetch refused for delegated profile {} (connector {}): {}",
                    profile.getProfileId(), connector.getConnectorId(), auth.getDenialReason());
            return;
        }
        CallContext fetchCtx = auth.getCallContext();   // null = admin profile
        Thread.ofVirtual().name("webhook-fetch-" + profile.getProfileId()).start(() -> {
            try {
                schedulerService.executeFetch(fetchCtx, profile, connector, params);
            } catch (Exception e) {
                logger.error("Webhook-triggered fetch failed for profile {}: {}",
                        profile.getProfileId(), e.getMessage());
            }
        });
    }

    /**
     * Verify webhook signature based on connector's webhookSecret and source system.
     */
    private boolean verifySignature(ConnectorDefinition connector, String rawBody) {
        String secret = connector.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            // No secret configured — reject for security
            logger.warn("Webhook rejected for connector {} — webhookSecret not configured", connector.getConnectorId());
            return false;
        }

        String system = connector.getSourceSystem();
        if ("slack".equals(system)) {
            return verifySlackSignature(secret, rawBody);
        }
        if ("teams".equals(system) || "m365_mail".equals(system)) {
            return verifyGraphClientState(secret, rawBody);
        }
        if ("chatwork".equals(system)) {
            return verifyChatworkSignature(secret, rawBody);
        }
        if ("box".equals(system)) {
            return verifyBoxSignature(secret, rawBody);
        }
        if ("dropbox".equals(system)) {
            return verifyDropboxSignature(secret, rawBody);
        }

        // Generic HMAC-SHA256 verification — require signature when secret is configured.
        //
        // Optional replay protection: if X-Webhook-Timestamp is present, the
        // signature must cover "<timestamp>:<body>" and the timestamp must be
        // within 5 minutes of the server clock.  This matches the Slack
        // signing scheme.  Callers without timestamp support fall back to
        // signing only the body — replay is then mitigated only by the
        // ingest pipeline's source-identity dedupe.
        String headerSig = httpRequest.getHeader("X-Webhook-Signature");
        if (headerSig == null) {
            logger.warn("Webhook signature header missing for connector with configured secret");
            return false; // Secret is set but no signature provided — reject
        }

        String headerTimestamp = httpRequest.getHeader("X-Webhook-Timestamp");
        String signedPayload;
        if (headerTimestamp != null && !headerTimestamp.isBlank()) {
            long now = System.currentTimeMillis() / 1000L;
            try {
                long ts = Long.parseLong(headerTimestamp.trim());
                if (Math.abs(now - ts) > 300) {
                    logger.warn("Generic webhook timestamp outside 5-minute window: now={}, header={}",
                            now, ts);
                    return false;
                }
            } catch (NumberFormatException e) {
                logger.warn("Generic webhook X-Webhook-Timestamp is not a unix epoch second: {}", headerTimestamp);
                return false;
            }
            signedPayload = headerTimestamp + ":" + rawBody;
        } else {
            signedPayload = rawBody;
        }

        String computed = hmacSha256(secret, signedPayload);
        return java.security.MessageDigest.isEqual(
                headerSig.getBytes(StandardCharsets.UTF_8),
                computed.getBytes(StandardCharsets.UTF_8));
    }

    private boolean verifyGraphClientState(String expectedSecret, String rawBody) {
        try {
            JsonNode payload = MAPPER.readTree(rawBody);
            JsonNode notifications = payload.path("value");
            if (!notifications.isArray()) return false;
            for (JsonNode notification : notifications) {
                String clientState = notification.path("clientState").asText(null);
                if (clientState == null || !java.security.MessageDigest.isEqual(
                        clientState.getBytes(StandardCharsets.UTF_8),
                        expectedSecret.getBytes(StandardCharsets.UTF_8))) {
                    logger.warn("Graph clientState mismatch for connector (expected length={}, got length={})",
                            expectedSecret.length(), clientState != null ? clientState.length() : 0);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.warn("Graph clientState verification failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean verifyChatworkSignature(String token, String rawBody) {
        // Chatwork uses Base64-encoded HMAC-SHA256 with the webhook token as key
        String headerSig = httpRequest.getHeader("X-ChatWorkWebhookSignature");
        if (headerSig == null) {
            logger.warn("Chatwork signature header missing");
            return false;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    java.util.Base64.getDecoder().decode(token), "HmacSHA256"));
            String computed = java.util.Base64.getEncoder().encodeToString(
                    mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
            return java.security.MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    headerSig.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warn("Chatwork signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verify a Box Webhooks V2 signature. Box signs {@code body + delivery-timestamp}
     * with HMAC-SHA256 and sends the base64 digest in BOX-SIGNATURE-PRIMARY and
     * BOX-SIGNATURE-SECONDARY (two keys for rotation). Our model holds one
     * webhookSecret, so we accept the request if it matches either header with
     * that key (covering whichever key the operator configured). The delivery
     * timestamp must be within 10 minutes (replay protection).
     */
    private boolean verifyBoxSignature(String secret, String rawBody) {
        String version = httpRequest.getHeader("BOX-SIGNATURE-VERSION");
        String algorithm = httpRequest.getHeader("BOX-SIGNATURE-ALGORITHM");
        String timestamp = httpRequest.getHeader("BOX-DELIVERY-TIMESTAMP");
        String primary = httpRequest.getHeader("BOX-SIGNATURE-PRIMARY");
        String secondary = httpRequest.getHeader("BOX-SIGNATURE-SECONDARY");

        if (!"1".equals(version) || algorithm == null || !"HmacSHA256".equalsIgnoreCase(algorithm)) {
            logger.warn("Box webhook unsupported signature version/algorithm: {}/{}", version, algorithm);
            return false;
        }
        if (timestamp == null || timestamp.isBlank()) {
            logger.warn("Box webhook missing BOX-DELIVERY-TIMESTAMP");
            return false;
        }
        // Replay protection: reject deliveries older than 10 minutes.
        try {
            long deliverySec = java.time.OffsetDateTime.parse(timestamp).toInstant().getEpochSecond();
            long nowSec = System.currentTimeMillis() / 1000L;
            if (Math.abs(nowSec - deliverySec) > 600) {
                logger.warn("Box webhook delivery timestamp outside 10-minute window");
                return false;
            }
        } catch (Exception e) {
            logger.warn("Box webhook BOX-DELIVERY-TIMESTAMP not parseable: {}", timestamp);
            return false;
        }
        if (primary == null && secondary == null) {
            logger.warn("Box webhook missing both signature headers");
            return false;
        }
        // Box signs the raw body followed by the delivery timestamp.
        String computed = hmacSha256Base64(secret, rawBody + timestamp);
        return constantTimeEquals(computed, primary) || constantTimeEquals(computed, secondary);
    }

    /**
     * Verify a Dropbox webhook signature: hex HMAC-SHA256 of the raw body using
     * the app secret, delivered in the {@code X-Dropbox-Signature} header.
     */
    private boolean verifyDropboxSignature(String secret, String rawBody) {
        String headerSig = httpRequest.getHeader("X-Dropbox-Signature");
        if (headerSig == null || headerSig.isBlank()) {
            logger.warn("Dropbox webhook missing X-Dropbox-Signature");
            return false;
        }
        String computed = hmacSha256(secret, rawBody);
        return java.security.MessageDigest.isEqual(
                headerSig.getBytes(StandardCharsets.UTF_8),
                computed.getBytes(StandardCharsets.UTF_8));
    }

    /** Constant-time string compare that treats null as non-matching. */
    private static boolean constantTimeEquals(String computed, String header) {
        if (header == null) return false;
        return java.security.MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                header.getBytes(StandardCharsets.UTF_8));
    }

    private boolean verifySlackSignature(String signingSecret, String rawBody) {
        String timestamp = httpRequest.getHeader("X-Slack-Request-Timestamp");
        String signature = httpRequest.getHeader("X-Slack-Signature");
        if (timestamp == null || signature == null) return false;

        // Reject requests older than 5 minutes (replay protection)
        long now = System.currentTimeMillis() / 1000;
        try {
            if (Math.abs(now - Long.parseLong(timestamp)) > 300) return false;
        } catch (NumberFormatException e) {
            return false;
        }

        String baseString = "v0:" + timestamp + ":" + rawBody;
        String computed = "v0=" + hmacSha256(signingSecret, baseString);
        return java.security.MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // Fail closed: never return empty string from a crypto operation.
            // Callers compare the result; an empty string could match an empty header.
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    /** Base64 HMAC-SHA256 (used by Box, which delivers base64 signatures). */
    private static String hmacSha256Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.Base64.getEncoder().encodeToString(
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    // ── Subscription Management (admin) ────────────────────────────

    /**
     * Create a webhook subscription for Graph (M365 Mail / Teams).
     * Requires the connector to have a valid token and webhookSecret (used as clientState).
     */
    @PostMapping("/{connectorId}/subscribe")
    public ResponseEntity<?> createSubscription(@PathVariable String connectorId,
                                                 @RequestBody Map<String, String> params) {
        if (!isAdmin()) return forbidden();
        ConnectorDefinition connector = connectorDefinitionService.get(connectorId);
        if (connector == null) return notFound("Connector not found: " + connectorId);

        String system = connector.getSourceSystem();
        if (!"teams".equals(system) && !"m365_mail".equals(system)) {
            return badRequest("Subscription creation is only supported for Graph (teams, m365_mail)");
        }

        String token = resolveToken(connector);
        if (token == null) return badRequest("No access token for connector");

        // Build Graph subscription — derive default resource from connector type
        String resource = params.get("resource");
        if (resource == null || resource.isBlank()) {
            if ("teams".equals(system)) {
                String teamId = params.get("teamId");
                String channelId = params.get("channelId");
                if (teamId == null || channelId == null) {
                    return badRequest("Teams subscription requires teamId and channelId (or explicit resource)");
                }
                resource = "teams('" + escapeGraphId(teamId) + "')/channels('" + escapeGraphId(channelId) + "')/messages";
            } else {
                // m365_mail: use /users/{userId} for client-credentials, /me for delegated
                String userId = params.get("userId");
                String folderId = params.getOrDefault("folderId", "inbox");
                if (userId != null && !userId.isBlank()) {
                    // userId is a path segment (not OData literal), use path encoding
                    resource = "users/" + AdapterHttpClient.encodePathSegment(userId) + "/mailFolders('" + escapeGraphId(folderId) + "')/messages";
                } else {
                    resource = "me/mailFolders('" + escapeGraphId(folderId) + "')/messages";
                }
            }
        }
        String notificationUrl = params.get("notificationUrl");
        if (notificationUrl == null || notificationUrl.isBlank()) {
            return badRequest("notificationUrl is required");
        }
        // SSRF prevention: only HTTPS public URLs allowed for webhook callbacks
        try {
            java.net.URI uri = java.net.URI.create(notificationUrl);
            if (!"https".equals(uri.getScheme())) {
                return badRequest("notificationUrl must use HTTPS");
            }
            AdapterHttpClient.validateExternalUrl(notificationUrl);
        } catch (SecurityException se) {
            return badRequest("notificationUrl rejected: " + se.getMessage());
        } catch (Exception e) {
            return badRequest("Invalid notificationUrl");
        }
        // Require webhookSecret to be configured — without it, receiveWebhook will
        // reject all incoming notifications, making the subscription useless.
        if (connector.getWebhookSecret() == null || connector.getWebhookSecret().isBlank()) {
            return badRequest("webhookSecret must be configured on the connector before creating a subscription");
        }

        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "changeType", "created",
                    "notificationUrl", notificationUrl,
                    "resource", resource,
                    "expirationDateTime", java.time.Instant.now().plusSeconds(43200).toString(), // 12h
                    "clientState", connector.getWebhookSecret() != null ? connector.getWebhookSecret() : ""
            ));

            var httpClient = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://graph.microsoft.com/v1.0/subscriptions"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = AdapterHttpClient.sendWithRetry(httpClient, request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                JsonNode result = MAPPER.readTree(response.body());
                return ResponseEntity.ok(Map.of(
                        "status", "success",
                        "subscriptionId", result.path("id").asText(),
                        "expirationDateTime", result.path("expirationDateTime").asText()
                ));
            }
            return ResponseEntity.status(response.statusCode())
                    .body(Map.of("error", "Graph subscription failed: " + AdapterHttpClient.truncateBody(response.body())));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal error"));
        }
    }

    @DeleteMapping("/{connectorId}/subscribe")
    public ResponseEntity<?> deleteSubscription(@PathVariable String connectorId,
                                                 @RequestParam String subscriptionId) {
        if (!isAdmin()) return forbidden();
        ConnectorDefinition connector = connectorDefinitionService.get(connectorId);
        if (connector == null) return notFound("Connector not found");

        String token = resolveToken(connector);
        if (token == null) return badRequest("No access token");

        try {
            var httpClient = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://graph.microsoft.com/v1.0/subscriptions/" + subscriptionId))
                    .header("Authorization", "Bearer " + token)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .DELETE()
                    .build();
            var response = AdapterHttpClient.sendWithRetry(httpClient, request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) {
                return ResponseEntity.ok(Map.of("status", "success"));
            }
            return ResponseEntity.status(response.statusCode())
                    .body(Map.of("error", AdapterHttpClient.truncateBody(response.body())));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal error"));
        }
    }

    private String resolveToken(ConnectorDefinition connector) {
        if (connector.getCredentialRef() == null) return null;
        if (propertyManager != null) {
            try { return propertyManager.readValue(connector.getCredentialRef()); }
            catch (Exception e) { /* ignore */ }
        }
        return null;
    }

    private boolean isAdmin() {
        if (httpRequest == null) return false;
        var ctx = (CallContext) httpRequest.getAttribute("CallContext");
        if (ctx == null) return false;
        var admin = (Boolean) ctx.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN);
        return admin != null && admin;
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
    }

    private ResponseEntity<?> notFound(String msg) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", msg));
    }

    private ResponseEntity<?> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", msg));
    }
}
