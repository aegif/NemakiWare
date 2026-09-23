package jp.aegif.nemaki.rest.ingest.fileshare;

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
 * Dropbox API connector adapter — lists files and downloads content.
 *
 * <p>Uses Dropbox API v2 with OAuth2 Bearer token.
 * <a href="https://www.dropbox.com/developers/documentation/http/documentation">API Reference</a>
 */
public class DropboxConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DropboxConnectorAdapter.class);
    private static final String DROPBOX_API = "https://api.dropboxapi.com/2";
    private static final String DROPBOX_CONTENT = "https://content.dropboxapi.com/2";
    /** Dropbox's largest page of folder entries. */
    static final int PAGE_SIZE = 2000;
    /** How many {@code list_folder} (+ {@code /continue}) requests one listing may make unless the caller says otherwise. */
    public static final int DEFAULT_MAX_LIST_REQUESTS = 50;
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private final String accessToken;
    private final HttpClient httpClient;

    private final String apiBase;
    private final String contentBase;

    public DropboxConnectorAdapter(String accessToken) {
        this(accessToken, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
    }

    public DropboxConnectorAdapter(String accessToken, HttpClient httpClient) {
        this(accessToken, DROPBOX_API, DROPBOX_CONTENT, httpClient);
    }

    /** Tests point the real adapter at a local stub of the Dropbox API through the two bases. */
    public DropboxConnectorAdapter(String accessToken, String apiBase, String contentBase, HttpClient httpClient) {
        this.accessToken = accessToken;
        this.apiBase = apiBase;
        this.contentBase = contentBase;
        this.httpClient = httpClient;
    }

    public record DropboxFile(String id, String name, String pathDisplay,
                              long size, String serverModified) {}

    /**
     * A folder listing, and whether it is the WHOLE folder.
     *
     * @param files every file the listing reached, in the order Dropbox returned them (which
     *     is NOT by modification time)
     * @param complete true when Dropbox said there is no more
     * @param truncatedBecause why it stopped early; null when {@code complete}
     */
    public record FileListing(List<DropboxFile> files, boolean complete, String truncatedBecause) {}

    /**
     * List EVERY file in a folder (sub-folders excluded), following {@code has_more} /
     * {@code cursor}, up to {@code maxRequests} requests.
     *
     * <p>This replaced a listing stopped at the caller's per-run limit (R107). Dropbox does not
     * return entries by modification time, so a listing cut at N entries left every file after
     * them unlisted on every poll, and the checkpoint the caller raised from the files it did
     * see excluded any of them modified earlier for ever. The whole folder is read; the
     * caller's budget is the caller's.
     *
     * <p>A response without an {@code entries} array or without {@code has_more} is refused,
     * not read as an empty folder or as its end; {@code has_more} with no cursor is a cut.
     */
    public FileListing listAllFiles(String folderPath, int maxRequests) throws Exception {
        List<DropboxFile> allFiles = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "path", folderPath != null ? folderPath : "",
                "recursive", false,
                "limit", PAGE_SIZE
        ));
        HttpResponse<String> response = post(apiBase + "/files/list_folder", body);
        JsonNode root = MAPPER.readTree(response.body());
        for (int request = 1; request <= maxRequests; request++) {
            JsonNode entries = root.get("entries");
            if (entries == null || !entries.isArray()) {
                throw new RuntimeException("Dropbox answered the folder listing without an entries array on request "
                        + request + ", so how many entries the folder holds is unknown");
            }
            for (JsonNode entry : entries) {
                if (!"file".equals(entry.path(".tag").asText())) continue;
                // An item this listing already holds is not listed twice.
                if (!seen.add(entry.path("id").asText())) continue;
                allFiles.add(new DropboxFile(
                        entry.path("id").asText(),
                        entry.path("name").asText(),
                        entry.path("path_display").asText(),
                        entry.path("size").asLong(0),
                        entry.path("server_modified").asText(null)
                ));
            }
            // A missing has_more is a malformed answer, not "no more": read as false it made
            // a broken page the end of the folder, and the checkpoint then excluded the rest
            // (review, P1). Dropbox always writes the field.
            if (!root.hasNonNull("has_more")) {
                throw new RuntimeException("Dropbox answered the folder listing without has_more on request "
                        + request + ", so whether the folder continues is unknown");
            }
            if (!root.path("has_more").asBoolean(false)) {
                return new FileListing(allFiles, true, null);
            }
            String cursor = root.path("cursor").asText(null);
            if (cursor == null || cursor.isEmpty()) {
                return new FileListing(allFiles, false, "Dropbox said there is more and gave no cursor to read it with, after "
                        + allFiles.size() + " file(s)");
            }
            if (request == maxRequests) break;
            String continueBody = MAPPER.writeValueAsString(java.util.Map.of("cursor", cursor));
            response = post(apiBase + "/files/list_folder/continue", continueBody);
            root = MAPPER.readTree(response.body());
        }
        return new FileListing(allFiles, false, "the cap of " + maxRequests + " listing request(s) was reached with "
                + allFiles.size() + " file(s) read and more entries still in the folder (raise the profile's "
                + "dropboxListMaxRequests parameter)");
    }

    /**
     * Search for files by query.
     */
    public List<DropboxFile> searchFiles(String query, int limit) throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "query", query,
                "options", java.util.Map.of(
                        "max_results", Math.min(limit, 100),
                        "file_status", "active"
                )
        ));

        HttpResponse<String> response = post(apiBase + "/files/search_v2", body);
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode matches = root.get("matches");
        if (matches == null || !matches.isArray()) return List.of();

        List<DropboxFile> files = new ArrayList<>();
        for (JsonNode match : matches) {
            JsonNode metadata = match.path("metadata").path("metadata");
            if (!"file".equals(metadata.path(".tag").asText())) continue;
            files.add(new DropboxFile(
                    metadata.path("id").asText(),
                    metadata.path("name").asText(),
                    metadata.path("path_display").asText(),
                    metadata.path("size").asLong(0),
                    metadata.path("server_modified").asText(null)
            ));
        }
        return files;
    }

    /**
     * Download file content.
     *
     * @param filePath Dropbox file path or ID (rev:xxx or id:xxx)
     * @return input stream of file content
     */
    public InputStream downloadFile(String filePath) throws Exception {
        String apiArg = MAPPER.writeValueAsString(java.util.Map.of("path", filePath));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(contentBase + "/files/download"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Dropbox-API-Arg", apiArg)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<InputStream> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(httpClient, request, HttpResponse.BodyHandlers.ofInputStream());
        return jp.aegif.nemaki.rest.ingest.AdapterHttpClient.requireOkOrClose(response, "Dropbox download");
    }

    /**
     * Get file metadata.
     */
    public DropboxFile getFileInfo(String filePath) throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of("path", filePath));
        HttpResponse<String> response = post(apiBase + "/files/get_metadata", body);
        JsonNode entry = MAPPER.readTree(response.body());
        return new DropboxFile(
                entry.path("id").asText(),
                entry.path("name").asText(),
                entry.path("path_display").asText(),
                entry.path("size").asLong(0),
                entry.path("server_modified").asText(null)
        );
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(httpClient, request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Dropbox API error " + response.statusCode() + ": " + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
        }
        return response;
    }
}
