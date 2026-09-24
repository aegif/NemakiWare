package jp.aegif.nemaki.rest.ingest.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.rest.ingest.AdapterHttpClient;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Microsoft Teams connector adapter — fetches channel messages and files
 * via Microsoft Graph API.
 *
 * <p>Uses Graph API v1.0 with delegated or application permissions.
 * Required Graph permissions: ChannelMessage.Read.All, Files.Read.All
 *
 * <p>Implements {@code @odata.nextLink} pagination per Graph API spec
 * and respects {@code Retry-After} headers on HTTP 429 via
 * {@link AdapterHttpClient#sendWithRetry}.
 */
public class TeamsConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TeamsConnectorAdapter.class);
    private static final String DEFAULT_BASE = "https://graph.microsoft.com/v1.0";
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();
    /** Hard cap on pagination pages to prevent runaway loops. */
    private static final int MAX_PAGES = 100;

    private final String accessToken;
    private final String apiBase;
    private final HttpClient httpClient;

    public TeamsConnectorAdapter(String accessToken) {
        this(accessToken, DEFAULT_BASE);
    }

    public TeamsConnectorAdapter(String accessToken, String apiBase) {
        this(accessToken, apiBase, AdapterHttpClient.shared());
    }

    public TeamsConnectorAdapter(String accessToken, String apiBase, HttpClient httpClient) {
        this.accessToken = accessToken;
        this.apiBase = apiBase;
        this.httpClient = httpClient;
    }

    public record TeamsChannel(String id, String displayName, String teamId) {}
    public record TeamsMessage(String id, String body, String from, String createdDateTime,
                               String replyToId, List<TeamsFile> attachments) {}
    public record TeamsFile(String id, String name, String contentUrl, String contentType, long size) {}

    /**
     * List channels in a team.
     */
    public List<TeamsChannel> listChannels(String teamId) throws Exception {
        String url = apiBase + "/teams/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(teamId) + "/channels?$select=id,displayName";
        JsonNode root = graphGet(url);
        JsonNode values = root.get("value");
        if (values == null || !values.isArray()) return List.of();

        List<TeamsChannel> result = new ArrayList<>();
        for (JsonNode ch : values) {
            result.add(new TeamsChannel(ch.path("id").asText(), ch.path("displayName").asText(), teamId));
        }
        return result;
    }

    /** Messages per page; Graph's maximum for channel messages. */
    static final int PAGE_SIZE = 50;
    /** How many message-page requests one listing may make unless the caller says otherwise. */
    public static final int DEFAULT_MAX_MESSAGE_REQUESTS = 50;

    /** What one listing came back with: the messages newer than the checkpoint, whether it read down to it, and if not why. */
    public record MessageListing(List<TeamsMessage> messages, boolean complete, String truncatedBecause) {}

    /**
     * EVERY channel message newer than {@code sinceCanonical} (a {@link WatermarkCheckpoint#canonical}
     * form, or null for all), following {@code @odata.nextLink} up to {@code maxRequests} requests.
     *
     * <p>This replaced a listing stopped at the caller's per-run limit (R107). Graph lists channel
     * messages newest first and offers no {@code $filter} on the creation time, so a listing cut at
     * N messages was the N NEWEST; the caller then raised the checkpoint to the newest it had seen,
     * and every older message the cut had left out fell below the checkpoint for ever. The listing
     * now reads DOWN TO the checkpoint: pages are newest first, so the first page holding a message
     * older than it is the last one read (the rest of that page and every later page are older;
     * messages AT the checkpoint's time are passed on for the caller's id-level check).
     * That rests on Graph's order being by creation time, newest first — measured against a stub,
     * not against Graph.
     *
     * <p>A response without a {@code value} array, or a message without a creation time this
     * connector can read, is refused, not read around: a message that cannot be placed can neither
     * be skipped nor named. The same {@code @odata.nextLink} twice is a cut.
     */
    public MessageListing listSince(String teamId, String channelId, String sinceCanonical, int maxRequests) throws Exception {
        String url = apiBase + "/teams/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(teamId) + "/channels/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(channelId)
                + "/messages?$top=" + PAGE_SIZE;
        List<TeamsMessage> newer = new ArrayList<>();
        String previous = null;
        String previousAt = null;
        for (int request = 1; request <= maxRequests; request++) {
            JsonNode root = graphGet(url);
            JsonNode values = root.get("value");
            if (values == null || !values.isArray()) {
                throw new RuntimeException("Graph answered the channel messages without a value array on request "
                        + request + ", so how many messages the channel holds is unknown");
            }
            for (JsonNode node : values) {
                TeamsMessage msg = parseMessage(node);
                String at = jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.canonical(msg.createdDateTime());
                if (at == null) {
                    throw new RuntimeException("Graph listed message " + msg.id() + " without a creation time this connector can read ('"
                            + msg.createdDateTime() + "'), so it can neither be skipped nor named");
                }
                // The stop below rests on newest-first order. A message NEWER than the one before
                // it — within a page or across the pages read — breaks that, and a stop taken on
                // such a listing would report as complete a span with newer messages behind it
                // (review, P1). Refused; the pages not read cannot be checked (R112).
                if (previousAt != null && at.compareTo(previousAt) > 0) {
                    throw new RuntimeException("Graph listed message " + msg.id() + " (" + msg.createdDateTime()
                            + ") newer than the one before it, so the channel is not listed newest first and "
                            + "the listing cannot tell where the checkpoint is");
                }
                previousAt = at;
                if (sinceCanonical != null && at.compareTo(sinceCanonical) < 0) {
                    // Newest first: this and everything after it is older than the checkpoint. A
                    // message AT the checkpoint's time is passed on — the checkpoint names the
                    // ids done at its time, and only the caller can tell those from the rest.
                    return new MessageListing(newer, true, null);
                }
                newer.add(msg);
            }
            JsonNode nextLink = root.get("@odata.nextLink");
            String next = nextLink == null || nextLink.isNull() ? null : nextLink.asText(null);
            if (next == null || next.isBlank()) {
                return new MessageListing(newer, true, null);
            }
            if (next.equals(previous) || next.equals(url)) {
                return new MessageListing(newer, false, "Graph returned the same @odata.nextLink twice (" + next
                        + "), so the listing cannot move forward");
            }
            previous = url;
            url = next;
        }
        return new MessageListing(newer, false, "the cap of " + maxRequests + " message request(s) was reached with "
                + newer.size() + " message(s) read and older ones still above the checkpoint (raise the profile's "
                + "teamsMessageMaxRequests parameter)");
    }

    /**
     * Fetch replies to a message with pagination.
     */
    public List<TeamsMessage> getReplies(String teamId, String channelId, String messageId) throws Exception {
        String url = apiBase + "/teams/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(teamId) + "/channels/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(channelId)
                + "/messages/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(messageId) + "/replies";

        List<TeamsMessage> allReplies = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES && url != null; page++) {
            JsonNode root = graphGet(url);
            JsonNode values = root.get("value");
            if (values == null || !values.isArray() || values.isEmpty()) break;

            for (JsonNode msg : values) {
                String from = msg.path("from").path("user").path("displayName").asText(null);
                String body = msg.path("body").path("content").asText("");
                allReplies.add(new TeamsMessage(msg.path("id").asText(), body, from,
                        msg.path("createdDateTime").asText(null), messageId, List.of()));
            }

            JsonNode nextLink = root.get("@odata.nextLink");
            url = (nextLink != null && !nextLink.isNull()) ? nextLink.asText(null) : null;
        }
        return allReplies;
    }

    /**
     * Download a file from a content URL with retry on 429/503.
     */
    public InputStream downloadFile(String contentUrl) throws Exception {
        AdapterHttpClient.validateExternalUrl(contentUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(contentUrl))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = AdapterHttpClient.sendWithRedirectValidation(
                request, HttpResponse.BodyHandlers.ofInputStream(), 5);
        return AdapterHttpClient.requireOkOrClose(response, "Teams file download");
    }

    private TeamsMessage parseMessage(JsonNode msg) {
        String from = msg.path("from").path("user").path("displayName").asText(null);
        String body = msg.path("body").path("content").asText("");
        String replyTo = msg.has("replyToId") ? msg.path("replyToId").asText(null) : null;

        List<TeamsFile> files = new ArrayList<>();
        JsonNode attachments = msg.get("attachments");
        if (attachments != null && attachments.isArray()) {
            for (JsonNode att : attachments) {
                if ("file".equals(att.path("contentType").asText())) {
                    files.add(new TeamsFile(
                            att.path("id").asText(),
                            att.path("name").asText(),
                            att.path("contentUrl").asText(null),
                            att.path("contentType").asText(),
                            0));
                }
            }
        }
        return new TeamsMessage(msg.path("id").asText(), body, from,
                msg.path("createdDateTime").asText(null), replyTo, files);
    }

    /**
     * Low-level Graph GET with retry on 429/503 and HTTP status validation.
     */
    private JsonNode graphGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = AdapterHttpClient.sendWithRetry(
                httpClient, request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 429) {
            throw new RuntimeException("Graph API rate limited (HTTP 429) after retries");
        }
        if (status == 401 || status == 403) {
            throw new RuntimeException("Graph API auth error " + status + ": " + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
        }
        if (status != 200) {
            throw new RuntimeException("Graph API error " + status + ": " + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
        }
        return MAPPER.readTree(response.body());
    }
}
