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
 * Microsoft Teams connector adapter — fetches channel messages and files
 * via Microsoft Graph API.
 *
 * <p>Uses Graph API v1.0 with delegated or application permissions.
 * Required Graph permissions: ChannelMessage.Read.All, Files.Read.All
 */
public class TeamsConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TeamsConnectorAdapter.class);
    private static final String DEFAULT_BASE = "https://graph.microsoft.com/v1.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String accessToken;
    private final String apiBase;
    private final HttpClient httpClient;

    public TeamsConnectorAdapter(String accessToken) {
        this(accessToken, DEFAULT_BASE);
    }

    public TeamsConnectorAdapter(String accessToken, String apiBase) {
        this(accessToken, apiBase, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
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
        String url = apiBase + "/teams/" + teamId + "/channels?$select=id,displayName";
        JsonNode root = graphGet(url);
        JsonNode values = root.get("value");
        if (values == null || !values.isArray()) return List.of();

        List<TeamsChannel> result = new ArrayList<>();
        for (JsonNode ch : values) {
            result.add(new TeamsChannel(ch.path("id").asText(), ch.path("displayName").asText(), teamId));
        }
        return result;
    }

    /**
     * Fetch messages from a channel.
     *
     * @param teamId    team ID
     * @param channelId channel ID
     * @param top       max messages
     */
    public List<TeamsMessage> getMessages(String teamId, String channelId, int top) throws Exception {
        String url = apiBase + "/teams/" + teamId + "/channels/" + channelId
                + "/messages?$top=" + top;
        JsonNode root = graphGet(url);
        JsonNode values = root.get("value");
        if (values == null || !values.isArray()) return List.of();

        List<TeamsMessage> result = new ArrayList<>();
        for (JsonNode msg : values) {
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
            result.add(new TeamsMessage(msg.path("id").asText(), body, from,
                    msg.path("createdDateTime").asText(null), replyTo, files));
        }
        return result;
    }

    /**
     * Fetch replies to a message.
     */
    public List<TeamsMessage> getReplies(String teamId, String channelId, String messageId) throws Exception {
        String url = apiBase + "/teams/" + teamId + "/channels/" + channelId
                + "/messages/" + messageId + "/replies";
        JsonNode root = graphGet(url);
        JsonNode values = root.get("value");
        if (values == null) return List.of();

        List<TeamsMessage> result = new ArrayList<>();
        for (JsonNode msg : values) {
            String from = msg.path("from").path("user").path("displayName").asText(null);
            String body = msg.path("body").path("content").asText("");
            result.add(new TeamsMessage(msg.path("id").asText(), body, from,
                    msg.path("createdDateTime").asText(null), messageId, List.of()));
        }
        return result;
    }

    /**
     * Download a file from a content URL.
     */
    public InputStream downloadFile(String contentUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(contentUrl))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Teams file download error " + response.statusCode());
        }
        return response.body();
    }

    private JsonNode graphGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Graph API error " + response.statusCode() + ": " + response.body());
        }
        return MAPPER.readTree(response.body());
    }
}
