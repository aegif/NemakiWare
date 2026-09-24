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

    /** Messages per request; Slack recommends at most 200. */
    static final int PAGE_SIZE = 200;
    /** How many {@code conversations.history} requests one listing may make unless the caller says otherwise. */
    public static final int DEFAULT_MAX_HISTORY_REQUESTS = 50;

    /** What one listing came back with: the messages, whether the channel was read to the end, and if not why. */
    public record HistoryListing(List<SlackMessage> messages, boolean complete, String truncatedBecause) {}

    /**
     * EVERY message after {@code oldest} (exclusive — Slack's own semantics for the parameter),
     * newest first as Slack lists them, following {@code response_metadata.next_cursor} up to
     * {@code maxRequests} requests.
     *
     * <p>This replaced a listing stopped at the caller's per-run limit (R107). Slack lists newest
     * first, so a listing cut at N messages was the N NEWEST since the checkpoint; the caller
     * then raised the checkpoint to the newest it saw, and every older message the cut had left
     * out fell below the checkpoint for ever. The whole span is read; the caller's budget is
     * the caller's.
     *
     * <p>A response without a {@code messages} array or without {@code has_more} is refused, not
     * read as an empty channel or as its end; {@code has_more} with no cursor, or with the cursor
     * of the previous page, is a cut.
     */
    public HistoryListing listSince(String channelId, String oldest, int maxRequests) throws Exception {
        List<SlackMessage> all = new ArrayList<>();
        String cursor = null;
        for (int request = 1; request <= maxRequests; request++) {
            StringBuilder urlBuilder = new StringBuilder(apiBase)
                    .append("/conversations.history?channel=").append(jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(channelId))
                    .append("&limit=").append(PAGE_SIZE);
            if (oldest != null && !oldest.isBlank()) urlBuilder.append("&oldest=").append(jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(oldest));
            if (cursor != null) urlBuilder.append("&cursor=").append(jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(cursor));

            JsonNode root = slackGet(urlBuilder.toString());
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray()) {
                throw new RuntimeException("Slack answered conversations.history without a messages array on request "
                        + request + ", so how many messages the channel holds is unknown");
            }
            for (JsonNode msg : messages) {
                all.add(parseMessage(msg));
            }
            // A missing has_more is a malformed answer, not "no more": read as false it would
            // make a broken page the end of the channel. Slack always writes the field.
            if (!root.hasNonNull("has_more")) {
                throw new RuntimeException("Slack answered conversations.history without has_more on request "
                        + request + ", so whether the channel continues is unknown");
            }
            if (!root.path("has_more").asBoolean(false)) {
                return new HistoryListing(all, true, null);
            }
            String next = root.path("response_metadata").path("next_cursor").asText("");
            if (next.isEmpty()) {
                return new HistoryListing(all, false, "Slack said there is more and gave no cursor to read it with, after "
                        + all.size() + " message(s)");
            }
            if (next.equals(cursor)) {
                return new HistoryListing(all, false, "Slack returned the same cursor twice (" + next
                        + "), so the listing cannot move forward");
            }
            cursor = next;
        }
        return new HistoryListing(all, false, "the cap of " + maxRequests + " history request(s) was reached with "
                + all.size() + " message(s) read and more still in the channel (raise the profile's "
                + "slackHistoryMaxRequests parameter)");
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
