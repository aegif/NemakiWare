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
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Mattermost REST API connector adapter — fetches channel posts and files.
 *
 * <p>Uses Mattermost REST API v4 with personal access token or bot token.
 * API docs: https://api.mattermost.com/
 */
public class MattermostConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MattermostConnectorAdapter.class);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private final String baseUrl;
    private final String token;
    private final HttpClient httpClient;

    /**
     * @param baseUrl Mattermost server URL (e.g. https://mattermost.example.com)
     * @param token   Personal access token or bot token
     */
    public MattermostConnectorAdapter(String baseUrl, String token) {
        this(baseUrl, token, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
    }

    public MattermostConnectorAdapter(String baseUrl, String token, HttpClient httpClient) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.httpClient = httpClient;
    }

    public record MattermostChannel(String id, String name, String displayName, String teamId) {}
    public record MattermostPost(String id, String message, String userId, long createAt,
                                 String rootId, List<String> fileIds) {}
    public record MattermostFile(String id, String name, String mimeType, long size) {}

    /**
     * List channels in a team.
     */
    public List<MattermostChannel> listChannels(String teamId) throws Exception {
        String url = baseUrl + "/api/v4/teams/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(teamId) + "/channels?per_page=100";
        JsonNode root = mmGet(url);
        if (!root.isArray()) return List.of();

        List<MattermostChannel> result = new ArrayList<>();
        for (JsonNode ch : root) {
            result.add(new MattermostChannel(
                    ch.path("id").asText(),
                    ch.path("name").asText(),
                    ch.path("display_name").asText(),
                    ch.path("team_id").asText()));
        }
        return result;
    }

    /**
     * Fetch posts from a channel with page-based pagination.
     *
     * <p>Mattermost uses {@code page} + {@code per_page} parameters.
     * Fetches until {@code perPage} total posts are collected or no more
     * results remain.
     *
     * @param channelId channel ID
     * @param perPage   max total posts to return
     */
    public List<MattermostPost> getPosts(String channelId, int perPage) throws Exception {
        int pageSize = Math.min(perPage, 200); // Mattermost max: 200
        List<MattermostPost> allPosts = new ArrayList<>();

        for (int page = 0; page < 50; page++) { // Hard cap
            String url = baseUrl + "/api/v4/channels/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(channelId)
                    + "/posts?per_page=" + pageSize + "&page=" + page;
            JsonNode root = mmGet(url);
            JsonNode order = root.get("order");
            JsonNode posts = root.get("posts");
            if (order == null || posts == null || order.isEmpty()) break;

            for (JsonNode postId : order) {
                JsonNode post = posts.get(postId.asText());
                if (post == null) continue;
                List<String> fileIds = new ArrayList<>();
                JsonNode fids = post.get("file_ids");
                if (fids != null && fids.isArray()) {
                    for (JsonNode fid : fids) fileIds.add(fid.asText());
                }
                allPosts.add(new MattermostPost(
                        post.path("id").asText(),
                        post.path("message").asText(),
                        post.path("user_id").asText(),
                        post.path("create_at").asLong(0),
                        post.has("root_id") ? post.path("root_id").asText("") : "",
                        fileIds));
                if (allPosts.size() >= perPage) break;
            }
            if (allPosts.size() >= perPage) break;
            if (order.size() < pageSize) break; // Last page
        }
        return allPosts;
    }

    /**
     * Get file metadata.
     */
    public MattermostFile getFileInfo(String fileId) throws Exception {
        String url = baseUrl + "/api/v4/files/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(fileId) + "/info";
        JsonNode root = mmGet(url);
        return new MattermostFile(
                root.path("id").asText(),
                root.path("name").asText(),
                root.path("mime_type").asText(),
                root.path("size").asLong(0));
    }

    /**
     * Download a file.
     */
    public InputStream downloadFile(String fileId) throws Exception {
        String url = baseUrl + "/api/v4/files/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(fileId) + "?download=1";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(httpClient, request, HttpResponse.BodyHandlers.ofInputStream());
        return jp.aegif.nemaki.rest.ingest.AdapterHttpClient.requireOkOrClose(response, "Mattermost file download");
    }

    /**
     * Get thread posts (replies to a root post).
     */
    public List<MattermostPost> getThread(String postId) throws Exception {
        String url = baseUrl + "/api/v4/posts/" + postId + "/thread";
        JsonNode root = mmGet(url);
        JsonNode order = root.get("order");
        JsonNode posts = root.get("posts");
        if (order == null || posts == null) return List.of();

        List<MattermostPost> result = new ArrayList<>();
        for (JsonNode pid : order) {
            JsonNode post = posts.get(pid.asText());
            if (post == null) continue;
            result.add(new MattermostPost(
                    post.path("id").asText(),
                    post.path("message").asText(),
                    post.path("user_id").asText(),
                    post.path("create_at").asLong(0),
                    post.has("root_id") ? post.path("root_id").asText("") : "",
                    List.of()));
        }
        return result;
    }

    private JsonNode mmGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(httpClient, request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Mattermost API error " + response.statusCode() + ": " + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
        }
        return MAPPER.readTree(response.body());
    }
}
