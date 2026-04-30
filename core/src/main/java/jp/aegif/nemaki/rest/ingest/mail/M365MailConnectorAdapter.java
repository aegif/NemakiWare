package jp.aegif.nemaki.rest.ingest.mail;

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
 * Microsoft 365 Graph Mail connector adapter — fetches messages via Microsoft Graph API.
 *
 * <p>Uses the same direct HTTP client pattern as CloudDriveServiceImpl for OneDrive.
 * Access token is passed from the UI OAuth flow.
 */
public class M365MailConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(M365MailConnectorAdapter.class);
    private static final String DEFAULT_GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String accessToken;
    private final HttpClient httpClient;
    private final String graphBase;
    /** Graph API path prefix: "/me" for delegated auth, "/users/{id}" for client credentials. */
    private final String mailboxPath;

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

    /**
     * List messages from a mail folder.
     *
     * @param folderId folder ID or well-known name ("inbox", "sentitems", etc.)
     * @param top      max messages to return
     * @param filter   OData filter expression (nullable)
     */
    /**
     * List messages from a mail folder with {@code @odata.nextLink} pagination
     * and retry on HTTP 429/503.
     *
     * @param folderId folder ID or well-known name ("inbox", "sentitems", etc.)
     * @param top      max total messages to return (also controls page size)
     * @param filter   OData filter expression (nullable)
     */
    public List<M365MessageSummary> listMessages(String folderId, int top, String filter) throws Exception {
        int pageSize = Math.min(top, 50); // Graph API page size max
        String url = graphBase + mailboxPath + "/mailFolders/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(folderId) + "/messages"
                + "?$top=" + pageSize
                + "&$select=id,internetMessageId,subject,from,receivedDateTime"
                + "&$orderby=receivedDateTime%20desc";
        if (filter != null && !filter.isBlank()) {
            url += "&$filter=" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(filter);
        }

        List<M365MessageSummary> allSummaries = new ArrayList<>();
        for (int page = 0; page < 100 && url != null; page++) {
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
                throw new RuntimeException("Graph API error " + response.statusCode() + ": " + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode values = root.get("value");
            if (values == null || !values.isArray() || values.isEmpty()) break;

            for (JsonNode msg : values) {
                String from = null;
                JsonNode fromNode = msg.path("from").path("emailAddress").path("address");
                if (!fromNode.isMissingNode()) from = fromNode.asText();

                allSummaries.add(new M365MessageSummary(
                        msg.path("id").asText(),
                        msg.has("internetMessageId") ? msg.path("internetMessageId").asText() : null,
                        msg.has("subject") ? msg.path("subject").asText() : null,
                        from,
                        msg.has("receivedDateTime") ? msg.path("receivedDateTime").asText() : null));
                if (allSummaries.size() >= top) break; // Respect total cap
            }
            if (allSummaries.size() >= top) break;

            // Follow @odata.nextLink for next page
            JsonNode nextLink = root.get("@odata.nextLink");
            url = (nextLink != null && !nextLink.isNull()) ? nextLink.asText(null) : null;
        }
        return allSummaries;
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
