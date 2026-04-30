package jp.aegif.nemaki.rest.ingest.fileshare;

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
 * Dropbox API connector adapter — lists files and downloads content.
 *
 * <p>Uses Dropbox API v2 with OAuth2 Bearer token.
 * <a href="https://www.dropbox.com/developers/documentation/http/documentation">API Reference</a>
 */
public class DropboxConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DropboxConnectorAdapter.class);
    private static final String DROPBOX_API = "https://api.dropboxapi.com/2";
    private static final String DROPBOX_CONTENT = "https://content.dropboxapi.com/2";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String accessToken;
    private final HttpClient httpClient;

    public DropboxConnectorAdapter(String accessToken) {
        this(accessToken, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
    }

    public DropboxConnectorAdapter(String accessToken, HttpClient httpClient) {
        this.accessToken = accessToken;
        this.httpClient = httpClient;
    }

    public record DropboxFile(String id, String name, String pathDisplay,
                              long size, String serverModified) {}

    /**
     * List files in a folder.
     *
     * @param folderPath Dropbox folder path ("" for root, "/Documents", etc.)
     * @param limit      max items to return
     * @return list of files (excludes sub-folders)
     */
    public List<DropboxFile> listFiles(String folderPath, int limit) throws Exception {
        int pageSize = Math.min(limit, 2000); // Dropbox max: 2000
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "path", folderPath != null ? folderPath : "",
                "recursive", false,
                "limit", pageSize
        ));

        HttpResponse<String> response = post(DROPBOX_API + "/files/list_folder", body);
        JsonNode root = MAPPER.readTree(response.body());

        List<DropboxFile> allFiles = new ArrayList<>();
        for (int page = 0; page < 50; page++) { // Hard cap
            JsonNode entries = root.get("entries");
            if (entries == null || !entries.isArray()) break;

            for (JsonNode entry : entries) {
                if (!"file".equals(entry.path(".tag").asText())) continue;
                allFiles.add(new DropboxFile(
                        entry.path("id").asText(),
                        entry.path("name").asText(),
                        entry.path("path_display").asText(),
                        entry.path("size").asLong(0),
                        entry.path("server_modified").asText(null)
                ));
                if (allFiles.size() >= limit) break;
            }
            if (allFiles.size() >= limit) break;

            // Dropbox uses has_more + cursor for pagination
            if (!root.path("has_more").asBoolean(false)) break;
            String cursor = root.path("cursor").asText(null);
            if (cursor == null || cursor.isEmpty()) break;

            String continueBody = MAPPER.writeValueAsString(java.util.Map.of("cursor", cursor));
            response = post(DROPBOX_API + "/files/list_folder/continue", continueBody);
            root = MAPPER.readTree(response.body());
        }
        return allFiles;
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

        HttpResponse<String> response = post(DROPBOX_API + "/files/search_v2", body);
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
                .uri(URI.create(DROPBOX_CONTENT + "/files/download"))
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
        HttpResponse<String> response = post(DROPBOX_API + "/files/get_metadata", body);
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
