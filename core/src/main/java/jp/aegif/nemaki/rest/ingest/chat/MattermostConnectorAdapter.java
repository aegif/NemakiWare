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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint;

/**
 * Mattermost REST API connector adapter — fetches channel posts and files.
 *
 * <p>Uses Mattermost REST API v4 with personal access token or bot token.
 * API docs: https://api.mattermost.com/
 */
public class MattermostConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MattermostConnectorAdapter.class);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    /** Posts per page: Mattermost clamps {@code per_page} to 200 ({@code PerPageMaximum} in the server). */
    static final int PAGE_SIZE = 200;
    /** How many post pages one listing may ask for by default: 50 × 200 = 10,000 posts. */
    public static final int DEFAULT_MAX_POST_REQUESTS = 50;

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
     * What a listing down to the checkpoint read.
     *
     * @param posts the posts newer than the checkpoint, newest first as Mattermost lists them
     * @param complete false when the listing was CUT before reaching the checkpoint — the
     *                 posts it did not reach are the OLDER ones, so a checkpoint raised from what
     *                 it did reach would exclude them for ever
     * @param truncatedBecause why, when not complete
     */
    public record PostListing(List<MattermostPost> posts, boolean complete, String truncatedBecause) {}

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
     * The posts of a channel newer than a checkpoint, read newest first down to it.
     *
     * <p>Mattermost lists a channel's posts by creation time, newest first ({@code ORDER BY
     * CreateAt DESC} in the server's store), and offers no filter on the creation time — its
     * {@code since} selects by UPDATE time, is capped at 1,000 rows by the server without saying
     * so, and ignores paging — so the listing walks the pages until it sees a post created before
     * the checkpoint. A post AT the checkpoint's time is passed on: the caller's checkpoint names
     * the ids done at its time, and only the caller can tell those from the rest.
     *
     * <p>The pages are walked by {@code before=<post id>}, a cursor on the post's creation time,
     * not by page offsets — an offset skips a post when one above it is deleted while the pages
     * are read. {@code before} is strict (posts created BEFORE that post's time), so the next page
     * is asked before the LAST post created strictly after the page's oldest creation time: it
     * begins with that oldest time again, and a post at it that the page did not show is not lost
     * (the ones it did show are listed once — a seen set). So a page asked {@code before} a post
     * always carries at least one post already seen; one that carries none is a cut — the
     * cursor post was deleted since (the server's subquery for its time answers nothing and the
     * page comes back empty), and read as the end it would pass every older post (review, P1).
     * A full page of posts all created at one time cannot be walked past this way and is a cut —
     * one no parameter lifts: {@code before} cannot split a millisecond, and walking the tie with
     * {@code page} offsets is not used because SQL leaves the order of equal {@code CreateAt}
     * unspecified, so an offset could skip a post. The channel stays PARTIAL until those posts are
     * dealt with outside this connector (R113). A page that repeats an earlier one is out
     * of order — its first post is newer than the last post read — and is refused by the order
     * check below, so a server that repeats itself is not walked to the cap.
     *
     * <p>Refused, as a whole: a page without {@code order} / {@code posts}, an order naming a post
     * the page does not carry, a post without an id or a creation time, and a post created after
     * the one before it — the stop rests on newest-first order, and a listing that is not in
     * order would report as complete a span with newer posts behind it. A short page is the end:
     * the server answers {@code LIMIT per_page} rows and clamps {@code per_page} to 200.
     *
     * @param sinceCanonical the checkpoint's creation time in {@link WatermarkCheckpoint#canonical}
     *                       form, or null to read the whole channel
     * @param maxRequests    how many pages may be asked for before the listing is cut
     */
    public PostListing listSince(String channelId, String sinceCanonical, int maxRequests) throws Exception {
        String channel = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(channelId);
        List<MattermostPost> newer = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String before = null;
        String previousAt = null;
        for (int request = 1; request <= maxRequests; request++) {
            String url = baseUrl + "/api/v4/channels/" + channel + "/posts?per_page=" + PAGE_SIZE + "&page=0"
                    + (before == null ? "" : "&before=" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(before));
            JsonNode root = mmGet(url);
            JsonNode order = root.get("order");
            JsonNode posts = root.get("posts");
            if (order == null || !order.isArray() || posts == null || !posts.isObject()) {
                throw new RuntimeException("Mattermost answered the channel posts without an order array and a posts map on request "
                        + request + ", so how many posts the channel holds is unknown");
            }
            List<MattermostPost> page = new ArrayList<>();
            List<String> ats = new ArrayList<>();
            boolean overlapped = false;
            for (JsonNode postId : order) {
                String id = postId.asText("");
                JsonNode post = id.isEmpty() ? null : posts.get(id);
                if (post == null) {
                    throw new RuntimeException("Mattermost listed post '" + id + "' in the order of a page without carrying it in posts, "
                            + "so the page cannot be read");
                }
                MattermostPost msg = parsePost(post);
                if (msg.id() == null || msg.id().isBlank()) {
                    throw new RuntimeException("Mattermost listed a post without an id, so it can neither be skipped nor named");
                }
                String at = creationTime(msg);
                // The stop below rests on newest-first order. A post NEWER than the one before it
                // — within a page or across the pages read — breaks that, and a stop taken on such
                // a listing would report as complete a span with newer posts behind it. Refused.
                if (previousAt != null && at.compareTo(previousAt) > 0) {
                    throw new RuntimeException("Mattermost listed post " + msg.id() + " (create_at " + msg.createAt()
                            + ") newer than the one before it, so the channel is not listed newest first and "
                            + "the listing cannot tell where the checkpoint is");
                }
                previousAt = at;
                if (seen.contains(msg.id())) overlapped = true;
                page.add(msg);
                ats.add(at);
            }
            if (before != null && !overlapped) {
                return new PostListing(newer, false, "the page before post " + before + " carried none of the posts the page "
                        + "it was asked from ended with — the post may have been deleted since — so it cannot be read as the "
                        + "end of the channel");
            }
            for (int i = 0; i < page.size(); i++) {
                MattermostPost msg = page.get(i);
                if (!seen.add(msg.id())) {
                    continue; // listed again by the overlap the cursor leaves
                }
                if (sinceCanonical != null && ats.get(i).compareTo(sinceCanonical) < 0) {
                    // Newest first: this and everything after it is older than the checkpoint.
                    return new PostListing(newer, true, null);
                }
                newer.add(msg);
            }
            if (page.size() < PAGE_SIZE) {
                return new PostListing(newer, true, null);
            }
            // The next page is asked BEFORE the last post created strictly after this page's
            // oldest creation time, so that it begins with that time again — a post at it that
            // this page did not show (a tie cut by the page boundary) is not lost.
            String oldestAt = ats.get(ats.size() - 1);
            String cursor = null;
            for (int i = 0; i < page.size(); i++) {
                if (ats.get(i).compareTo(oldestAt) > 0) cursor = page.get(i).id();
            }
            if (cursor == null) {
                return new PostListing(newer, false, "a full page of posts all created at " + oldestAt
                        + " (create_at " + page.get(0).createAt() + ") cannot be walked past by this connector, "
                        + "so the listing cannot move forward — raising mattermostPostMaxRequests does not help: more than "
                        + PAGE_SIZE + " posts share one millisecond (R113)");
            }
            before = cursor;
        }
        return new PostListing(newer, false, "the cap of " + maxRequests + " post request(s) was reached with "
                + newer.size() + " post(s) read and older ones still above the checkpoint (raise the profile's "
                + "mattermostPostMaxRequests parameter)");
    }

    /**
     * A post's creation time in the canonical form. Mattermost writes {@code create_at} (Unix
     * milliseconds) for every post; a post without one — or with one this connector cannot
     * place — can neither be skipped nor named, so the listing that carries it is refused.
     */
    static String creationTime(MattermostPost post) {
        if (post.createAt() <= 0) {
            throw new RuntimeException("Mattermost listed post " + post.id() + " without a creation time (create_at), "
                    + "so it can neither be skipped nor named");
        }
        try {
            return WatermarkCheckpoint.canonical(java.time.Instant.ofEpochMilli(post.createAt()));
        } catch (IllegalStateException | java.time.DateTimeException outOfRange) {
            throw new RuntimeException("Mattermost listed post " + post.id() + " with a creation time this connector cannot place ("
                    + post.createAt() + "): " + outOfRange.getMessage());
        }
    }

    private static MattermostPost parsePost(JsonNode post) {
        List<String> fileIds = new ArrayList<>();
        JsonNode fids = post.get("file_ids");
        if (fids != null && fids.isArray()) {
            for (JsonNode fid : fids) fileIds.add(fid.asText());
        }
        return new MattermostPost(
                post.path("id").asText(""),
                post.path("message").asText(),
                post.path("user_id").asText(),
                post.path("create_at").asLong(0),
                post.has("root_id") ? post.path("root_id").asText("") : "",
                fileIds);
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
