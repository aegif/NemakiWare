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

    /** How many delta-feed page requests one run may make unless the caller says otherwise. */
    public static final int DEFAULT_MAX_MESSAGE_REQUESTS = 50;

    /**
     * One page of the channel's delta feed: the messages it carries (deleted ones counted, not
     * carried), and the link to the next page ({@code @odata.nextLink}) or, when the round is
     * complete, the link the next round starts from ({@code @odata.deltaLink}).
     */
    public record DeltaPage(List<TeamsMessage> messages, int deleted, String nextLink, String deltaLink) {}

    /** The channel's delta feed on this endpoint, the path segments encoded as this connector writes them. */
    private String deltaBase(String teamId, String channelId) {
        return apiBase + "/teams/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(teamId) + "/channels/"
                + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(channelId) + "/messages/delta";
    }

    /**
     * The first request of a round of the channel's delta feed ({@code /messages/delta}): every
     * root message created or changed after {@code modifiedAfter} — Graph's only delta filter,
     * {@code lastModifiedDateTime gt}, written to the millisecond — or the whole feed when null.
     *
     * <p>Why the feed and not the channel listing: Graph lists channel messages sorted by the last
     * modified time of the whole reply chain (List channel messages, "Response"), so a root with a
     * fresh reply comes first whatever its creation time, and no listing can stop at a checkpoint
     * on the creation time; the feed is Graph's own change tracking. Graph documents it with an
     * eight-month window (older copies of the chatMessage delta page), and without {@code $top}
     * here: its page size is Graph's default. Measured against a stub, not against Graph.
     */
    public String initialDeltaLink(String teamId, String channelId, java.time.Instant modifiedAfter) {
        String url = deltaBase(teamId, channelId);
        if (modifiedAfter != null) {
            String stamp = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(java.time.ZoneOffset.UTC).format(modifiedAfter);
            url += "?$filter=" + java.net.URLEncoder.encode("lastModifiedDateTime gt " + stamp, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
        }
        return url;
    }

    /**
     * Whether a link points into THIS channel's delta feed on THIS endpoint — the only links read
     * or saved. Compared on the scheme, the host, the effective port and the DECODED path read as
     * OData segments: Graph writes the channel id raw in the links it returns ({@code 19:…@thread.tacv2})
     * where this connector writes it encoded, and Graph's links may use the key syntax
     * ({@code teams('…')/channels('…')/messages/delta()} — its mail delta links do) and its own case.
     * The same feed in every one of these spellings; a comparison of the strings refused every link
     * Graph handed back and stopped the channel for good.
     */
    public boolean isOwnDeltaLink(String link, String teamId, String channelId) {
        if (link == null || teamId == null || channelId == null) return false;
        try {
            URI candidate = URI.create(link);
            URI own = URI.create(apiBase);
            if (candidate.getScheme() == null || own.getScheme() == null
                    || !candidate.getScheme().equalsIgnoreCase(own.getScheme())) {
                return false;
            }
            if (candidate.getHost() == null || own.getHost() == null
                    || !candidate.getHost().equalsIgnoreCase(own.getHost())) {
                return false;
            }
            if (effectivePort(candidate) != effectivePort(own)) {
                return false;
            }
            List<String> expected = new ArrayList<>(odataSegments(own.getPath()));
            expected.addAll(List.of("teams", teamId, "channels", channelId, "messages", "delta"));
            List<String> actual = odataSegments(candidate.getPath());
            if (actual.size() != expected.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).equalsIgnoreCase(actual.get(i))) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static final java.util.regex.Pattern KEY_SEGMENT = java.util.regex.Pattern.compile("^([^(]+)\\('(.*)'\\)$");

    /**
     * A decoded path as OData segments: {@code name('key')} is two segments, {@code delta()} and
     * {@code microsoft.graph.delta()} are {@code delta}, empty segments are dropped.
     */
    static List<String> odataSegments(String decodedPath) {
        List<String> out = new ArrayList<>();
        if (decodedPath == null) return out;
        for (String part : decodedPath.split("/")) {
            if (part.isEmpty()) continue;
            java.util.regex.Matcher key = KEY_SEGMENT.matcher(part);
            if (key.matches()) {
                out.add(key.group(1));
                out.add(key.group(2).replace("''", "'"));
                continue;
            }
            String segment = part.endsWith("()") ? part.substring(0, part.length() - 2) : part;
            if (segment.startsWith("microsoft.graph.")) {
                segment = segment.substring("microsoft.graph.".length());
            }
            out.add(segment);
        }
        return out;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    /**
     * One page of the delta feed at {@code link}. A deleted message — {@code deletedDateTime}
     * set, or the delta convention {@code @removed} — is counted, not carried: there is nothing to
     * import. Refused: a response without a {@code value} array, and a page that carries neither
     * {@code @odata.nextLink} nor {@code @odata.deltaLink} — the feed could not be continued from
     * it, and a page taken as the end would drop what follows. A page whose next link is the link
     * it was read from is refused too: it cannot move forward.
     */
    public DeltaPage delta(String link) throws Exception {
        JsonNode root = graphGet(link);
        JsonNode values = root.get("value");
        if (values == null || !values.isArray()) {
            throw new RuntimeException("Graph answered the delta feed without a value array, so the page cannot be read");
        }
        List<TeamsMessage> messages = new ArrayList<>();
        int deleted = 0;
        for (JsonNode node : values) {
            if (node.hasNonNull("deletedDateTime") || node.has("@removed")) {
                deleted++;
                continue;
            }
            messages.add(parseMessage(node));
        }
        JsonNode nextNode = root.get("@odata.nextLink");
        JsonNode deltaNode = root.get("@odata.deltaLink");
        String next = nextNode == null || nextNode.isNull() ? null : nextNode.asText(null);
        String deltaLink = deltaNode == null || deltaNode.isNull() ? null : deltaNode.asText(null);
        if (next != null && next.isBlank()) next = null;
        if (deltaLink != null && deltaLink.isBlank()) deltaLink = null;
        if (next == null && deltaLink == null) {
            throw new RuntimeException("Graph answered a delta page without @odata.nextLink or @odata.deltaLink, "
                    + "so the feed cannot be continued from it");
        }
        if (link.equals(next)) {
            throw new RuntimeException("Graph returned the delta page's own link as its @odata.nextLink (" + next
                    + "), so the feed cannot move forward");
        }
        return new DeltaPage(messages, deleted, next, deltaLink);
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
