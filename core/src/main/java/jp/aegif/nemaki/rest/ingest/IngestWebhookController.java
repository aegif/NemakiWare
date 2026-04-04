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

/**
 * REST endpoint for receiving inbound webhooks from external sources.
 *
 * <p>Each connector can receive push notifications at:
 * {@code POST /v1/ingest-webhook/{connectorId}}
 *
 * <p>Supported webhook protocols:
 * <ul>
 *   <li>Slack Events API (url_verification + event_callback)</li>
 *   <li>Microsoft Graph change notifications (validationToken + changeNotification)</li>
 *   <li>Generic JSON payload (other systems)</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/ingest-webhook")
@CrossOrigin(origins = "*", maxAge = 3600)
public class IngestWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(IngestWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ConnectorDefinitionService connectorDefinitionService;

    @Autowired
    private IngestSchedulerService schedulerService;

    @Autowired
    private ImportProfileDefinitionService profileService;

    @Autowired
    private HttpServletRequest httpRequest;

    /**
     * Receive webhook from external source.
     * Handles Slack url_verification, Graph validationToken, and actual event payloads.
     */
    @PostMapping(value = "/{connectorId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receiveWebhook(
            @PathVariable String connectorId,
            @RequestParam(value = "validationToken", required = false) String validationToken,
            @RequestBody String rawBody) {

        // 1. Resolve connector
        ConnectorDefinition connector = connectorDefinitionService.get(connectorId);
        if (connector == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Connector not found: " + connectorId));
        }
        if (!connector.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Connector is disabled"));
        }

        String system = connector.getSourceSystem();

        // 2. Microsoft Graph: subscription validation
        if (validationToken != null && !validationToken.isBlank()) {
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
                default -> handleGenericWebhook(connector, payload);
            };

        } catch (Exception e) {
            logger.error("Webhook processing failed for {}: {}", connectorId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Processing failed: " + e.getMessage()));
        }
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

        // Find all profiles for this connector (supports many-to-many)
        List<ImportProfileDefinition> profiles = findAllProfilesForConnector(connector);
        if (profiles.isEmpty()) {
            logger.warn("No profile found for Slack webhook connector {}", connector.getConnectorId());
            return ResponseEntity.ok(Map.of("status", "no_profile"));
        }

        // Trigger async fetch for each profile
        for (ImportProfileDefinition profile : profiles) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("channelId", channelId);
            params.put("limit", "10");
            triggerFetchAsync(profile, connector, params);
        }

        return ResponseEntity.ok(Map.of("status", "accepted", "channel", channelId, "profiles", profiles.size()));
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

        // Process each notification × each profile
        int triggered = 0;
        for (JsonNode notification : notifications) {
            String resource = notification.path("resource").asText(null);
            if (resource != null) {
                for (ImportProfileDefinition profile : profiles) {
                    Map<String, String> params = profile.getSchedulerParams() != null
                            ? new LinkedHashMap<>(profile.getSchedulerParams()) : new LinkedHashMap<>();
                    params.put("limit", "10");
                    triggerFetchAsync(profile, connector, params);
                    triggered++;
                }
            }
        }

        return ResponseEntity.ok(Map.of("status", "accepted", "triggered", triggered));
    }

    /**
     * Handle generic webhook — log and trigger fetch with default params.
     */
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
     * Find ALL enabled profiles that accept this connector — either as their
     * defaultConnectorId or via allowedConnectorIds (many-to-many model).
     */
    private List<ImportProfileDefinition> findAllProfilesForConnector(ConnectorDefinition connector) {
        if (profileService == null) return List.of();
        String connId = connector.getConnectorId();
        return profileService.list().stream()
                .filter(ImportProfileDefinition::isEnabled)
                .filter(p -> connId.equals(p.getDefaultConnectorId())
                        || p.isConnectorAllowed(connId))
                .filter(p -> p.isArchetypeAllowed(connector.getSourceArchetype()))
                .toList();
    }

    /**
     * Trigger an async fetch (fire-and-forget) via the scheduler service.
     */
    private void triggerFetchAsync(ImportProfileDefinition profile, ConnectorDefinition connector,
                                   Map<String, String> params) {
        Thread.ofVirtual().name("webhook-fetch-" + profile.getProfileId()).start(() -> {
            try {
                schedulerService.executeFetch(null, profile, connector, params);
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
            // No secret configured — accept all (for development/testing)
            return true;
        }

        String system = connector.getSourceSystem();
        if ("slack".equals(system)) {
            return verifySlackSignature(secret, rawBody);
        }
        if ("teams".equals(system) || "m365_mail".equals(system)) {
            // Graph sends clientState in each notification — verify it matches webhookSecret
            return verifyGraphClientState(secret, rawBody);
        }

        // Generic HMAC-SHA256 verification — require signature when secret is configured
        String headerSig = httpRequest.getHeader("X-Webhook-Signature");
        if (headerSig == null) {
            logger.warn("Webhook signature header missing for connector with configured secret");
            return false; // Secret is set but no signature provided — reject
        }
        String computed = hmacSha256(secret, rawBody);
        return headerSig.equals(computed);
    }

    private boolean verifyGraphClientState(String expectedSecret, String rawBody) {
        try {
            JsonNode payload = MAPPER.readTree(rawBody);
            JsonNode notifications = payload.path("value");
            if (!notifications.isArray()) return false;
            for (JsonNode notification : notifications) {
                String clientState = notification.path("clientState").asText(null);
                if (clientState == null || !clientState.equals(expectedSecret)) {
                    logger.warn("Graph clientState mismatch: expected={}, got={}", expectedSecret, clientState);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.warn("Graph clientState verification failed: {}", e.getMessage());
            return false;
        }
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
        return computed.equals(signature);
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
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

        // Build Graph subscription
        String resource = params.getOrDefault("resource", "me/mailfolders('inbox')/messages");
        String notificationUrl = params.get("notificationUrl");
        if (notificationUrl == null || notificationUrl.isBlank()) {
            return badRequest("notificationUrl is required");
        }

        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "changeType", "created",
                    "notificationUrl", notificationUrl,
                    "resource", resource,
                    "expirationDateTime", java.time.Instant.now().plusSeconds(43200).toString(), // 12h
                    "clientState", connector.getWebhookSecret() != null ? connector.getWebhookSecret() : ""
            ));

            var httpClient = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://graph.microsoft.com/v1.0/subscriptions"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                JsonNode result = MAPPER.readTree(response.body());
                return ResponseEntity.ok(Map.of(
                        "status", "success",
                        "subscriptionId", result.path("id").asText(),
                        "expirationDateTime", result.path("expirationDateTime").asText()
                ));
            }
            return ResponseEntity.status(response.statusCode())
                    .body(Map.of("error", "Graph subscription failed: " + response.body()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
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
            var httpClient = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://graph.microsoft.com/v1.0/subscriptions/" + subscriptionId))
                    .header("Authorization", "Bearer " + token)
                    .DELETE()
                    .build();
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) {
                return ResponseEntity.ok(Map.of("status", "success"));
            }
            return ResponseEntity.status(response.statusCode())
                    .body(Map.of("error", response.body()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private String resolveToken(ConnectorDefinition connector) {
        if (connector.getCredentialRef() == null) return null;
        try {
            var ctx = jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext();
            if (ctx != null) {
                var pm = ctx.getBean(jp.aegif.nemaki.util.PropertyManager.class);
                return pm.readValue(connector.getCredentialRef());
            }
        } catch (Exception e) { /* ignore */ }
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
