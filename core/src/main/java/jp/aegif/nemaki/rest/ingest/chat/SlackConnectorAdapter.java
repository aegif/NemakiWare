package jp.aegif.nemaki.rest.ingest.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Slack Web API connector adapter — fetches conversation history and files.
 *
 * <p>Uses Slack Web API with Bot token (xoxb-*) or User token (xoxp-*).
 */
public class SlackConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SlackConnectorAdapter.class);
    private static final String SLACK_API = "https://slack.com/api";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String token;
    private final HttpClient httpClient;

    public SlackConnectorAdapter(String token) {
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record SlackMessage(String ts, String userId, String text, String threadTs, List<SlackFile> files) {}
    public record SlackFile(String id, String name, String mimeType, String urlPrivateDownload, long size) {}
    public record SlackChannel(String id, String name, boolean isPrivate) {}

    /**
     * List channels the bot has access to.
     */
    public List<SlackChannel> listChannels(int limit) throws Exception {
        String url = SLACK_API + "/conversations.list?limit=" + limit + "&types=public_channel,private_channel";
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
     * Fetch conversation history for a channel.
     *
     * @param channelId Slack channel ID
     * @param oldest    Unix timestamp (seconds) for oldest message (nullable)
     * @param limit     max messages
     */
    public List<SlackMessage> getHistory(String channelId, String oldest, int limit) throws Exception {
        String url = SLACK_API + "/conversations.history?channel=" + channelId + "&limit=" + limit;
        if (oldest != null && !oldest.isBlank()) url += "&oldest=" + oldest;

        JsonNode root = slackGet(url);
        JsonNode messages = root.get("messages");
        if (messages == null || !messages.isArray()) return List.of();

        List<SlackMessage> result = new ArrayList<>();
        for (JsonNode msg : messages) {
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
            result.add(new SlackMessage(
                    msg.path("ts").asText(),
                    msg.path("user").asText(),
                    msg.path("text").asText(),
                    msg.has("thread_ts") ? msg.path("thread_ts").asText() : null,
                    files));
        }
        return result;
    }

    /**
     * Fetch thread replies.
     */
    public List<SlackMessage> getThreadReplies(String channelId, String threadTs) throws Exception {
        String url = SLACK_API + "/conversations.replies?channel=" + channelId + "&ts=" + threadTs;
        JsonNode root = slackGet(url);
        JsonNode messages = root.get("messages");
        if (messages == null) return List.of();

        List<SlackMessage> result = new ArrayList<>();
        for (JsonNode msg : messages) {
            result.add(new SlackMessage(
                    msg.path("ts").asText(),
                    msg.path("user").asText(),
                    msg.path("text").asText(),
                    msg.has("thread_ts") ? msg.path("thread_ts").asText() : null,
                    List.of()));
        }
        return result;
    }

    /**
     * Download a file from Slack.
     */
    public InputStream downloadFile(String urlPrivateDownload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlPrivateDownload))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Slack file download error " + response.statusCode());
        }
        return response.body();
    }

    private JsonNode slackGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());
        if (!root.path("ok").asBoolean(false)) {
            throw new RuntimeException("Slack API error: " + root.path("error").asText("unknown"));
        }
        return root;
    }
}
