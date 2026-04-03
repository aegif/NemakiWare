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
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String accessToken;
    private final HttpClient httpClient;

    public M365MailConnectorAdapter(String accessToken) {
        this.accessToken = accessToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
    public List<M365MessageSummary> listMessages(String folderId, int top, String filter) throws Exception {
        String url = GRAPH_BASE + "/me/mailFolders/" + folderId + "/messages"
                + "?$top=" + top
                + "&$select=id,internetMessageId,subject,from,receivedDateTime"
                + "&$orderby=receivedDateTime desc";
        if (filter != null && !filter.isBlank()) {
            url += "&$filter=" + filter;
        }

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

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode values = root.get("value");
        if (values == null || !values.isArray()) {
            return List.of();
        }

        List<M365MessageSummary> summaries = new ArrayList<>();
        for (JsonNode msg : values) {
            String from = null;
            JsonNode fromNode = msg.path("from").path("emailAddress").path("address");
            if (!fromNode.isMissingNode()) from = fromNode.asText();

            summaries.add(new M365MessageSummary(
                    msg.path("id").asText(),
                    msg.has("internetMessageId") ? msg.path("internetMessageId").asText() : null,
                    msg.has("subject") ? msg.path("subject").asText() : null,
                    from,
                    msg.has("receivedDateTime") ? msg.path("receivedDateTime").asText() : null));
        }
        return summaries;
    }

    /**
     * Fetch a single message as MIME content (.eml format).
     * Uses Graph API's $value endpoint which returns RFC 2822 format.
     */
    public InputStream fetchMimeMessage(String messageId) throws Exception {
        String url = GRAPH_BASE + "/me/messages/" + messageId + "/$value";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Graph API MIME fetch error " + response.statusCode());
        }
        return response.body();
    }

    /**
     * List available mail folders.
     */
    public List<String> listFolders() throws Exception {
        String url = GRAPH_BASE + "/me/mailFolders?$select=id,displayName";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
