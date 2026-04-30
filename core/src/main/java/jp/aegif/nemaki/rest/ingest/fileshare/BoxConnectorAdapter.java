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
 * Box API connector adapter — lists files and downloads content.
 *
 * <p>Uses Box Content API v2.0 with OAuth2 Bearer token.
 * <a href="https://developer.box.com/reference/">API Reference</a>
 */
public class BoxConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BoxConnectorAdapter.class);
    private static final String BOX_API = "https://api.box.com/2.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String accessToken;
    private final HttpClient httpClient;

    public BoxConnectorAdapter(String accessToken) {
        this(accessToken, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
    }

    public BoxConnectorAdapter(String accessToken, HttpClient httpClient) {
        this.accessToken = accessToken;
        this.httpClient = httpClient;
    }

    public record BoxFile(String id, String name, String type, long size,
                          String modifiedAt, String parentId) {}

    /**
     * List files in a folder.
     *
     * @param folderId Box folder ID ("0" for root)
     * @param limit    max items to return
     * @return list of files (excludes sub-folders)
     */
    public List<BoxFile> listFiles(String folderId, int limit) throws Exception {
        int pageSize = Math.min(limit, 1000); // Box max: 1000
        List<BoxFile> allFiles = new ArrayList<>();
        int offset = 0;

        for (int page = 0; page < 50; page++) { // Hard cap
            String url = BOX_API + "/folders/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(folderId) + "/items?fields=id,name,type,size,modified_at,parent"
                    + "&limit=" + pageSize + "&offset=" + offset;

            HttpResponse<String> response = get(url);
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode entries = root.get("entries");
            if (entries == null || !entries.isArray() || entries.isEmpty()) break;

            for (JsonNode entry : entries) {
                if (!"file".equals(entry.path("type").asText())) continue;
                allFiles.add(new BoxFile(
                        entry.path("id").asText(),
                        entry.path("name").asText(),
                        entry.path("type").asText(),
                        entry.path("size").asLong(0),
                        entry.path("modified_at").asText(null),
                        entry.path("parent").path("id").asText(null)
                ));
                if (allFiles.size() >= limit) break;
            }
            if (allFiles.size() >= limit) break;

            int totalCount = root.path("total_count").asInt(0);
            offset += pageSize; // Box API: advance by requested limit, not by entries.size()
            if (offset >= totalCount) break;
        }
        return allFiles;
    }

    /**
     * Search for files by query.
     *
     * @param query  search query
     * @param limit  max results
     * @return list of matching files
     */
    public List<BoxFile> searchFiles(String query, int limit) throws Exception {
        String url = BOX_API + "/search?query=" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(query)
                + "&type=file&fields=id,name,type,size,modified_at,parent&limit=" + limit;

        HttpResponse<String> response = get(url);
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode entries = root.get("entries");
        if (entries == null || !entries.isArray()) return List.of();

        List<BoxFile> files = new ArrayList<>();
        for (JsonNode entry : entries) {
            files.add(new BoxFile(
                    entry.path("id").asText(),
                    entry.path("name").asText(),
                    entry.path("type").asText(),
                    entry.path("size").asLong(0),
                    entry.path("modified_at").asText(null),
                    entry.path("parent").path("id").asText(null)
            ));
        }
        return files;
    }

    /**
     * Download file content.
     *
     * @param fileId Box file ID
     * @return input stream of file content
     */
    public InputStream downloadFile(String fileId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BOX_API + "/files/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(fileId) + "/content"))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        // Box returns 302 redirect to CDN. Use SSRF-safe redirect validation
        // to prevent redirects to private/loopback addresses.
        HttpResponse<InputStream> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient
                .sendWithRedirectValidation(request, HttpResponse.BodyHandlers.ofInputStream(), 5);
        return jp.aegif.nemaki.rest.ingest.AdapterHttpClient.requireOkOrClose(response, "Box file download");
    }

    /**
     * Get file metadata.
     */
    public BoxFile getFileInfo(String fileId) throws Exception {
        HttpResponse<String> response = get(BOX_API + "/files/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(fileId) + "?fields=id,name,type,size,modified_at,parent");
        JsonNode entry = MAPPER.readTree(response.body());
        return new BoxFile(
                entry.path("id").asText(),
                entry.path("name").asText(),
                entry.path("type").asText(),
                entry.path("size").asLong(0),
                entry.path("modified_at").asText(null),
                entry.path("parent").path("id").asText(null)
        );
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(httpClient, request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Box API error " + response.statusCode() + ": " + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
        }
        return response;
    }
}
