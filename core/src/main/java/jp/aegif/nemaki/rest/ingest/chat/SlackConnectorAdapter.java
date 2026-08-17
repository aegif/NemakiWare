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
 * Slack Web API connector adapter — fetches conversation history and files.
 *
 * <p>Uses Slack Web API with Bot token (xoxb-*) or User token (xoxp-*).
 * Implements cursor-based pagination per Slack API spec and respects
 * {@code Retry-After} headers on HTTP 429 responses via
 * {@link AdapterHttpClient#sendWithRetry}.
 */
public class SlackConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SlackConnectorAdapter.class);
    private static final String DEFAULT_API = "https://slack.com/api";
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();
    /** Hard cap on total messages per getHistory() call to prevent runaway pagination. */
    private static final int MAX_PAGES = 50;

    private final String token;
    private final String apiBase;
    private final HttpClient httpClient;

    public SlackConnectorAdapter(String token) {
        this(token, DEFAULT_API);
    }

    /** Constructor with configurable API base URL (for testing with WireMock). */
    public SlackConnectorAdapter(String token, String apiBase) {
        this(token, apiBase, AdapterHttpClient.shared());
    }

    /** Constructor with explicit HttpClient (for tests that need a custom client). */
    public SlackConnectorAdapter(String token, String apiBase, HttpClient httpClient) {
        this.token = token;
        this.apiBase = apiBase;
        this.httpClient = httpClient;
    }

    public record SlackMessage(String ts, String userId, String text, String threadTs, List<SlackFile> files) {}
    public record SlackFile(String id, String name, String mimeType, String urlPrivateDownload, long size) {}
    public record SlackChannel(String id, String name, boolean isPrivate) {}

    /**
     * List channels the bot has access to.
     */
    public List<SlackChannel> listChannels(int limit) throws Exception {
        String url = apiBase + "/conversations.list?limit=" + limit + "&types=public_channel,private_channel";
        JsonNode root = slackGet(url);
        JsonNode channels = root.get("channels");
        if (channels == null || !channels.isArray()) return List.of();

        List<SlackChannel> result = new ArrayList<>();
        for (JsonNode ch : channels) {
            result.add(new SlackChannel(
                    ch.path("id").asText(),
                    ch.path("name").asText(),
                    ch.path("is_private").asBoolean(false)));
        }
        return result;
    }

    /**
     * Fetch conversation history for a channel with cursor-based pagination.
     *
     * <p>Follows {@code response_metadata.next_cursor} across multiple pages
     * until all messages since {@code oldest} are retrieved, up to
     * {@code limit} total messages (hard capped at {@link #MAX_PAGES} pages).
     *
     * @param channelId Slack channel ID
     * @param oldest    Unix timestamp (seconds) for oldest message (nullable)
     * @param limit     max messages per API call (Slack max: 999)
     */
    public List<SlackMessage> getHistory(String channelId, String oldest, int limit) throws Exception {
        List<SlackMessage> allMessages = new ArrayList<>();
        String cursor = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            StringBuilder urlBuilder = new StringBuilder(apiBase)
                    .append("/conversations.history?channel=").append(jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(channelId))
                    .append("&limit=").append(Math.min(limit, 200)); // Slack recommends ≤200
            if (oldest != null && !oldest.isBlank()) urlBuilder.append("&oldest=").append(jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(oldest));
            if (cursor != null) urlBuilder.append("&cursor=").append(jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(cursor));

            JsonNode root = slackGet(urlBuilder.toString());
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) break;

            for (JsonNode msg : messages) {
                allMessages.add(parseMessage(msg));
                if (allMessages.size() >= limit) break; // Respect total cap
            }
            if (allMessages.size() >= limit) break;

            // Check for next page via cursor
            JsonNode meta = root.path("response_metadata");
            String nextCursor = meta.path("next_cursor").asText(null);
            if (nextCursor == null || nextCursor.isBlank()) break;
            cursor = nextCursor;

            // Also check has_more for timestamp-based pagination
            if (!root.path("has_more").asBoolean(false)) break;

            logger.debug("Slack pagination: page {}, fetched {} so far, next_cursor present",
                    page + 1, allMessages.size());
        }

        logger.info("Slack getHistory: channel={}, oldest={}, total fetched={}", channelId, oldest, allMessages.size());
        return allMessages;
    }

    /**
     * Fetch thread replies (excluding the parent message).
     *
     * <p>Per Slack API spec, conversations.replies returns the parent message
     * as the first element. This method filters it out so only actual replies
     * are returned. Files are extracted from each reply.
     */
    public List<SlackMessage> getThreadReplies(String channelId, String threadTs) throws Exception {
        String url = apiBase + "/conversations.replies?channel=" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(channelId) + "&ts=" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(threadTs);
        JsonNode root = slackGet(url);
        JsonNode messages = root.get("messages");
        if (messages == null) return List.of();

        List<SlackMessage> result = new ArrayList<>();
        for (JsonNode msg : messages) {
            String ts = msg.path("ts").asText();
            // Skip the parent message (first element has ts == threadTs)
            if (ts.equals(threadTs)) continue;
            result.add(parseMessage(msg));
        }
        return result;
    }

    /**
     * Download a file from Slack.
     */
    public InputStream downloadFile(String urlPrivateDownload) throws Exception {
        AdapterHttpClient.validateExternalUrl(urlPrivateDownload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlPrivateDownload))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = AdapterHttpClient.sendWithRedirectValidation(
                request, HttpResponse.BodyHandlers.ofInputStream(), 5);
        return AdapterHttpClient.requireOkOrClose(response, "Slack file download");
    }

    private SlackMessage parseMessage(JsonNode msg) {
        List<SlackFile> files = new ArrayList<>();
        if (msg.has("files") && msg.get("files").isArray()) {
            for (JsonNode f : msg.get("files")) {
                files.add(new SlackFile(
                        f.path("id").asText(),
                        f.path("name").asText(),
                        f.path("mimetype").asText(),
                        f.path("url_private_download").asText(null),
                        f.path("size").asLong(0)));
            }
        }
        return new SlackMessage(
                msg.path("ts").asText(),
                msg.path("user").asText(),
                msg.path("text").asText(),
                msg.has("thread_ts") ? msg.path("thread_ts").asText() : null,
                files);
    }

    /**
     * Low-level Slack GET with retry on 429/503.
     * Checks HTTP status code AND Slack API {@code ok} field.
     */
    private JsonNode slackGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = AdapterHttpClient.sendWithRetry(
                httpClient, request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 429) {
            // sendWithRetry exhausted retries — propagate as transient error
            throw new RuntimeException("Slack API rate limited (HTTP 429) after retries");
        }
        if (status != 200) {
            throw new RuntimeException("Slack API HTTP " + status);
        }
        JsonNode root = MAPPER.readTree(response.body());
        if (!root.path("ok").asBoolean(false)) {
            throw new RuntimeException("Slack API error: " + root.path("error").asText("unknown"));
        }
        return root;
    }
}
