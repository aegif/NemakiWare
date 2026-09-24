package jp.aegif.nemaki.rest.ingest.mail;

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
 * Microsoft 365 Graph Mail connector adapter — fetches messages via Microsoft Graph API.
 *
 * <p>Uses the same direct HTTP client pattern as CloudDriveServiceImpl for OneDrive.
 * Access token is passed from the UI OAuth flow.
 */
public class M365MailConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(M365MailConnectorAdapter.class);
    private static final String DEFAULT_GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private final String accessToken;
    private final HttpClient httpClient;
    private final String graphBase;
    /** Graph API path prefix: "/me" for delegated auth, "/users/{id}" for client credentials. */
    private final String mailboxPath;
    /** The user id / UPN this adapter was given, or null for /me. */
    private final String mailboxLabel;

    public M365MailConnectorAdapter(String accessToken) {
        this(accessToken, null, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared(), DEFAULT_GRAPH_BASE);
    }

    /** @param userId mailbox user ID or UPN; null defaults to /me (delegated auth). */
    public M365MailConnectorAdapter(String accessToken, String userId) {
        this(accessToken, userId, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared(), DEFAULT_GRAPH_BASE);
    }

    public M365MailConnectorAdapter(String accessToken, HttpClient httpClient) {
        this(accessToken, null, httpClient, DEFAULT_GRAPH_BASE);
    }

    public M365MailConnectorAdapter(String accessToken, String userId, HttpClient httpClient) {
        this(accessToken, userId, httpClient, DEFAULT_GRAPH_BASE);
    }

    /** Test constructor with custom base URL. */
    public M365MailConnectorAdapter(String accessToken, String userId, HttpClient httpClient, String graphBase) {
        this.accessToken = accessToken;
        this.httpClient = httpClient;
        this.graphBase = graphBase;
        // Client Credentials auth requires /users/{id}; delegated auth uses /me
        this.mailboxPath = (userId != null && !userId.isBlank())
                ? "/users/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(userId) : "/me";
        this.mailboxLabel = (userId != null && !userId.isBlank()) ? userId : null;
    }

    /**
     * Summary of an M365 mail message.
     */
    public record M365MessageSummary(
            String id,
            String internetMessageId,
            String subject,
            String from,
            String receivedDateTime) {}

    /** How many delta-feed page requests one run may make unless the caller says otherwise. */
    public static final int DEFAULT_MAX_MESSAGE_REQUESTS = 50;
    /** Messages per delta page, asked for with {@code Prefer: odata.maxpagesize} (the delta call's own page size). */
    static final int PAGE_SIZE = 50;

    /** The mailbox this adapter reads: {@code me}, or the user id / UPN it was given. */
    public String mailbox() {
        return mailboxPath.equals("/me") ? "me" : mailboxLabel;
    }

    /**
     * One page of the folder's delta feed: the messages it carries (removed ones — deleted, or moved
     * out of the folder — counted, not carried), and the link to the next page
     * ({@code @odata.nextLink}) or, when the round is complete, the link the next round starts from
     * ({@code @odata.deltaLink}).
     */
    public record DeltaPage(List<M365MessageSummary> messages, int removed, String nextLink, String deltaLink) {}

    private String deltaBase(String folderId) {
        return graphBase + mailboxPath + "/mailFolders/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(folderId)
                + "/messages/delta";
    }

    /**
     * The first request of a round of the folder's delta feed ({@code /mailFolders/{id}/messages/delta}):
     * every message in the folder. A message MOVED into the folder comes in the feed like one
     * received there — the reason for the feed: a listing by received time never offers a message
     * filed into the folder after the checkpoint passed it.
     *
     * <p>No {@code $filter}: Graph documents that a delta query with {@code $filter} "returns only up to
     * 5,000 messages", and a round cut there ends with a deltaLink like a complete one — the messages
     * past the cut would never come (review, P1).
     */
    public String initialDeltaLink(String folderId) {
        return deltaBase(folderId) + "?$select=id,internetMessageId,subject,from,receivedDateTime";
    }

    /**
     * The id Graph answers for the folder this adapter reads ({@code GET /mailFolders/{id}?$select=id}).
     * A checkpoint is bound to it: the configured mailbox is only a string — {@code me} is whoever
     * the token belongs to, and one mailbox can be named by its UPN or its object id — while the
     * folder's id belongs to one folder of one mailbox (review, P2).
     */
    public String folderIdentity(String folderId) throws Exception {
        return folderIdAt(graphBase + mailboxPath + "/mailFolders/"
                + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(folderId) + "?$select=id", folderId);
    }

    /**
     * Whether a mail delta link names exactly the mailbox and folder this adapter reads with the
     * given folder — the segments its own first request carries ({@code me} or {@code users/<userId>},
     * and {@code folderId}), in any of Graph's spellings of the path. Exactly: a folder's id is
     * case-sensitive, and a spelling that differs is resolved, not guessed (see {@link #folderIdentityOf}).
     */
    public boolean namesFolder(String link, String folderId) {
        List<String> target = mailboxAndFolder(link);
        if (target == null) return false;
        String mailbox = mailboxPath.equals("/me") ? "me" : "users/" + mailboxLabel;
        return target.get(0).equals(mailbox) && target.get(1).equals(folderId);
    }

    /**
     * The id Graph answers for the folder a mail delta link points at — by the link's OWN mailbox
     * and folder, whatever spelling Graph wrote them in. A stored or answered link is the profile's
     * only when this is the id of the profile's folder: a link of the right shape can still read
     * another folder (review, P1).
     */
    public String folderIdentityOf(String link) throws Exception {
        List<String> target = mailboxAndFolder(link);
        if (target == null) throw new IllegalArgumentException("not a mail delta link on this endpoint: " + link);
        String mailbox = target.get(0).equals("me") ? "/me"
                : "/users/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(target.get(0).substring("users/".length()));
        return folderIdAt(graphBase + mailbox + "/mailFolders/"
                + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(target.get(1)) + "?$select=id", target.get(1));
    }

    /** The mailbox ({@code me} or {@code users/<id>}) and the folder a mail delta link names; null when it is not one. */
    private List<String> mailboxAndFolder(String link) {
        if (!isMailDeltaLink(link)) return null;
        List<String> base = odataSegments(URI.create(graphBase).getPath());
        List<String> path = odataSegments(URI.create(link).getPath());
        List<String> rest = path.subList(base.size(), path.size());
        return "me".equalsIgnoreCase(rest.get(0)) ? List.of("me", rest.get(2)) : List.of("users/" + rest.get(1), rest.get(3));
    }

    private String folderIdAt(String url, String folderId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(
                httpClient, request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Graph API error " + response.statusCode() + " reading the mail folder '" + folderId + "': "
                    + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
        }
        JsonNode id = MAPPER.readTree(response.body()).get("id");
        if (id == null || !id.isTextual() || id.asText().isBlank()) {
            throw new RuntimeException("Graph answered the mail folder '" + folderId + "' without an id");
        }
        return id.asText();
    }

    /**
     * Whether a link is a mail-folder delta link on THIS endpoint — the only links read or saved:
     * the scheme, the host, the effective port, and a decoded path of the shape
     * {@code <base>/(me | users/<id>)/mailFolders/<id>/messages/delta}, read as OData segments (the
     * key syntax {@code mailFolders('…')} and {@code delta()} included, the names in any case).
     *
     * <p>The mailbox and folder VALUES are not compared. Graph's documentation writes the same feed
     * as {@code mailFolders/{id}} and as {@code mailfolders('{id}')}, and a mailbox given as a UPN or
     * a folder given by its well-known name may come back as their ids — a comparison of the values
     * would refuse Graph's own link and stop the folder for good. What ties a stored link to this
     * profile's folder is the checkpoint, which names the id Graph answered for the folder it was
     * written for ({@link #folderIdentity}); a link Graph answers comes from a request this
     * connector made.
     */
    public boolean isMailDeltaLink(String link) {
        if (link == null) return false;
        try {
            URI candidate = URI.create(link);
            URI own = URI.create(graphBase);
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
            List<String> base = odataSegments(own.getPath());
            List<String> path = odataSegments(candidate.getPath());
            for (int i = 0; i < base.size(); i++) {
                if (i >= path.size() || !base.get(i).equalsIgnoreCase(path.get(i))) return false;
            }
            List<String> rest = path.subList(base.size(), path.size());
            int mailbox;
            if (rest.size() == 5 && "me".equalsIgnoreCase(rest.get(0))) {
                mailbox = 1;
            } else if (rest.size() == 6 && "users".equalsIgnoreCase(rest.get(0)) && !rest.get(1).isBlank()) {
                mailbox = 2;
            } else {
                return false;
            }
            return "mailFolders".equalsIgnoreCase(rest.get(mailbox))
                    && !rest.get(mailbox + 1).isBlank()
                    && "messages".equalsIgnoreCase(rest.get(mailbox + 2))
                    && "delta".equalsIgnoreCase(rest.get(mailbox + 3));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    private static final java.util.regex.Pattern KEY_SEGMENT = java.util.regex.Pattern.compile("^([^(]+)\\('(.*)'\\)$");

    /** A decoded path as OData segments: {@code name('key')} is two segments, {@code delta()} is {@code delta}. */
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

    /**
     * One page of the delta feed at {@code link}, asked with {@code Prefer: odata.maxpagesize}. A
     * message marked {@code @removed} (deleted, or moved out of the folder) is counted, not carried.
     * Refused: a response without a {@code value} array, and a page that carries neither
     * {@code @odata.nextLink} nor {@code @odata.deltaLink}, or whose next link is its own.
     */
    public DeltaPage delta(String link) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(link))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .header("Prefer", "odata.maxpagesize=" + PAGE_SIZE)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(
                httpClient, request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Graph API error " + response.statusCode() + ": "
                    + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
        }
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode values = root.get("value");
        if (values == null || !values.isArray()) {
            throw new RuntimeException("Graph answered the mail delta feed without a value array, so the page cannot be read");
        }
        List<M365MessageSummary> messages = new ArrayList<>();
        int removed = 0;
        for (JsonNode msg : values) {
            if (msg.has("@removed")) {
                // A removal names what was removed. One without an id is not a removal this
                // connector can account for, and counting it would pass the page on it (review, P3).
                if (!msg.path("id").isTextual() || msg.path("id").asText().isBlank()) {
                    throw new RuntimeException("Graph's mail delta feed carried a removed entry without an id, so the page cannot be passed");
                }
                removed++;
                continue;
            }
            String from = null;
            JsonNode fromNode = msg.path("from").path("emailAddress").path("address");
            if (!fromNode.isMissingNode()) from = fromNode.asText();
            messages.add(new M365MessageSummary(
                    msg.path("id").asText(""),
                    msg.has("internetMessageId") ? msg.path("internetMessageId").asText() : null,
                    msg.has("subject") ? msg.path("subject").asText() : null,
                    from,
                    msg.has("receivedDateTime") ? msg.path("receivedDateTime").asText() : null));
        }
        JsonNode nextNode = root.get("@odata.nextLink");
        JsonNode deltaNode = root.get("@odata.deltaLink");
        String next = nextNode == null || nextNode.isNull() ? null : nextNode.asText(null);
        String deltaLink = deltaNode == null || deltaNode.isNull() ? null : deltaNode.asText(null);
        if (next != null && next.isBlank()) next = null;
        if (deltaLink != null && deltaLink.isBlank()) deltaLink = null;
        if (next == null && deltaLink == null) {
            throw new RuntimeException("Graph answered a mail delta page without @odata.nextLink or @odata.deltaLink, "
                    + "so the feed cannot be continued from it");
        }
        if (link.equals(next)) {
            throw new RuntimeException("Graph returned the mail delta page's own link as its @odata.nextLink (" + next
                    + "), so the feed cannot move forward");
        }
        return new DeltaPage(messages, removed, next, deltaLink);
    }

    /**
     * Fetch a single message as MIME content (.eml format).
     * Uses Graph API's $value endpoint which returns RFC 2822 format.
     */
    public InputStream fetchMimeMessage(String messageId) throws Exception {
        String url = graphBase + mailboxPath + "/messages/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(messageId) + "/$value";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<InputStream> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(
                httpClient, request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Graph API MIME fetch error " + response.statusCode());
        }
        return response.body();
    }

    /**
     * List available mail folders.
     */
    public List<String> listFolders() throws Exception {
        String url = graphBase + mailboxPath + "/mailFolders?$select=id,displayName";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(httpClient, request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return List.of();

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode values = root.get("value");
        if (values == null) return List.of();

        List<String> folders = new ArrayList<>();
        for (JsonNode folder : values) {
            folders.add(folder.path("displayName").asText() + " (" + folder.path("id").asText() + ")");
        }
        return folders;
    }
}
