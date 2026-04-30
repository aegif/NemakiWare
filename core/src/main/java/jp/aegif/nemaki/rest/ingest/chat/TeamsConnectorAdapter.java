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

import jp.aegif.nemaki.rest.ingest.AdapterHttpClient;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    /**
     * Fetch messages from a channel with {@code @odata.nextLink} pagination.
     *
     * <p>Graph API returns max 50 messages per page.  This method follows
     * {@code @odata.nextLink} URLs until {@code top} messages are collected
     * or no more pages remain, capped at {@link #MAX_PAGES} pages.
     *
     * @param teamId    team ID
     * @param channelId channel ID
     * @param top       max total messages to return (also used as page size, Graph API max: 50)
     */
    public List<TeamsMessage> getMessages(String teamId, String channelId, int top) throws Exception {
        int pageSize = Math.min(top, 50);
        String url = apiBase + "/teams/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(teamId) + "/channels/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(channelId)
                + "/messages?$top=" + pageSize;

        List<TeamsMessage> allMessages = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES && url != null; page++) {
            JsonNode root = graphGet(url);
            JsonNode values = root.get("value");
            if (values == null || !values.isArray() || values.isEmpty()) break;

            for (JsonNode msg : values) {
                allMessages.add(parseMessage(msg));
                if (allMessages.size() >= top) break; // Respect total cap
            }
            if (allMessages.size() >= top) break;

            // Follow @odata.nextLink for next page
            JsonNode nextLink = root.get("@odata.nextLink");
            url = (nextLink != null && !nextLink.isNull()) ? nextLink.asText(null) : null;

            if (url != null) {
                logger.debug("Teams pagination: page {}, fetched {} of {} max",
                        page + 1, allMessages.size(), top);
            }
        }

        logger.info("Teams getMessages: team={}, channel={}, fetched={}, limit={}",
                teamId, channelId, allMessages.size(), top);
        return allMessages;
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
