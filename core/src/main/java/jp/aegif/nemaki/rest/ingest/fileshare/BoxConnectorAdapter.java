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
 * Box API connector adapter — lists files and downloads content.
 *
 * <p>Uses Box Content API v2.0 with OAuth2 Bearer token.
 * <a href="https://developer.box.com/reference/">API Reference</a>
 */
public class BoxConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BoxConnectorAdapter.class);
    private static final String BOX_API = "https://api.box.com/2.0";
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();
    /** Box's largest page of folder items. */
    static final int PAGE_SIZE = 1000;
    /** How many {@code /folders/{id}/items} requests one listing may make unless the caller says otherwise. */
    public static final int DEFAULT_MAX_LIST_REQUESTS = 50;

    private final String accessToken;
    private final String apiBase;
    private final HttpClient httpClient;

    public BoxConnectorAdapter(String accessToken) {
        this(accessToken, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
    }

    public BoxConnectorAdapter(String accessToken, HttpClient httpClient) {
        this(accessToken, BOX_API, httpClient);
    }

    /** Tests point the real adapter at a local stub of the Box API through {@code apiBase}. */
    public BoxConnectorAdapter(String accessToken, String apiBase, HttpClient httpClient) {
        this.accessToken = accessToken;
        this.apiBase = apiBase;
        this.httpClient = httpClient;
    }

    public record BoxFile(String id, String name, String type, long size,
                          String modifiedAt, String parentId) {}

    /**
     * A folder listing, and whether it is the WHOLE folder.
     *
     * @param files every file the listing reached, in the order Box returned them (by type and
     *     name — NOT by modification time)
     * @param complete true when the listing reached the end of the folder
     * @param truncatedBecause why it stopped early; null when {@code complete}
     */
    public record FileListing(List<BoxFile> files, boolean complete, String truncatedBecause) {}

    /**
     * List EVERY file in a folder (sub-folders excluded), a page of {@value #PAGE_SIZE} at a
     * time, up to {@code maxRequests} requests.
     *
     * <p>This replaced a listing stopped at the caller's per-run limit (R107). Box returns folder
     * items by type and name, not by modification time, so a listing cut at N items was the
     * first N names — every file after them was never listed at all, on any poll, and the
     * checkpoint the caller raised from the files it did see excluded any of them modified
     * earlier for ever. The whole folder is read; the caller's budget is the caller's.
     *
     * <p>A response without an {@code entries} array or a {@code total_count} is a malformed
     * answer and is refused, not read as an empty folder. An empty page before
     * {@code total_count} was reached is reported as a cut, not as the end.
     */
    public FileListing listAllFiles(String folderId, int maxRequests) throws Exception {
        List<BoxFile> allFiles = new ArrayList<>();
        int offset = 0;
        for (int request = 0; request < maxRequests; request++) {
            String url = apiBase + "/folders/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(folderId)
                    + "/items?fields=id,name,type,size,modified_at,parent"
                    + "&limit=" + PAGE_SIZE + "&offset=" + offset;
            HttpResponse<String> response = get(url);
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode entries = root.get("entries");
            if (entries == null || !entries.isArray() || !root.hasNonNull("total_count")) {
                throw new RuntimeException("Box answered the folder listing without an entries array "
                        + "or a total_count on request " + (request + 1) + ", so how many items the folder holds is unknown");
            }
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
            }
            int totalCount = root.path("total_count").asInt(0);
            offset += PAGE_SIZE; // Box API: advance by the requested limit, not by entries.size()
            if (offset >= totalCount) {
                return new FileListing(allFiles, true, null);
            }
            if (entries.isEmpty()) {
                return new FileListing(allFiles, false, "Box answered an empty page at offset "
                        + (offset - PAGE_SIZE) + " while total_count says " + totalCount + " items");
            }
        }
        return new FileListing(allFiles, false, "the cap of " + maxRequests + " listing request(s) was reached with "
                + allFiles.size() + " file(s) read and more items still in the folder (raise the profile's "
                + "boxListMaxRequests parameter)");
    }

    /**
     * Search for files by query.
     *
     * @param query  search query
     * @param limit  max results
     * @return list of matching files
     */
    public List<BoxFile> searchFiles(String query, int limit) throws Exception {
        String url = apiBase + "/search?query=" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(query)
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
                .uri(URI.create(apiBase + "/files/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(fileId) + "/content"))
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
        HttpResponse<String> response = get(apiBase + "/files/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(fileId) + "?fields=id,name,type,size,modified_at,parent");
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
