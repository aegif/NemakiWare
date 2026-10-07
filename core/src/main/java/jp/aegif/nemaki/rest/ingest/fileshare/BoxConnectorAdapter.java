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
     * time, up to {@code maxRequests} requests — by MARKER, not by offset.
     *
     * <p>This replaced a listing stopped at the caller's per-run limit (R107). Box returns folder
     * items by type and name, not by modification time, so a listing cut at N items was the
     * first N names — every file after them was never listed at all, on any poll, and the
     * checkpoint the caller raised from the files it did see excluded any of them modified
     * earlier for ever. The whole folder is read; the caller's budget is the caller's.
     *
     * <p>Marker pagination ({@code usemarker=true}), because offset pagination skips: an item
     * deleted while the folder is being listed shifts every later item one place back, so the
     * item that moved into the page already read is never returned — and, newer than the
     * checkpoint, is excluded by it for ever (review, P1). A marker names a position, not a
     * count.
     *
     * <p>A response without an {@code entries} array is a malformed answer and is refused, not
     * read as an empty folder. The end is a missing, null or empty {@code next_marker} — the
     * documented shape; whether Box ever omits the field on a page that is NOT the last is not
     * something this reader can tell, and that reading is recorded beside R61's.
     */
    public FileListing listAllFiles(String folderId, int maxRequests) throws Exception {
        List<BoxFile> allFiles = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String marker = null;
        for (int request = 0; request < maxRequests; request++) {
            String url = apiBase + "/folders/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(folderId)
                    + "/items?fields=id,name,type,size,modified_at,parent"
                    + "&limit=" + PAGE_SIZE + "&usemarker=true"
                    + (marker == null ? "" : "&marker=" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(marker));
            HttpResponse<String> response = get(url);
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode entries = root.get("entries");
            if (entries == null || !entries.isArray()) {
                throw new RuntimeException("Box answered the folder listing without an entries array on request "
                        + (request + 1) + ", so how many items the folder holds is unknown");
            }
            int newOnThisPage = 0;
            for (JsonNode entry : entries) {
                // An item without an id cannot be told from any other — two of them would
                // collapse into one and the second be dropped without a word (review, P1).
                String id = entry.path("id").asText("");
                if (id.isEmpty()) {
                    throw new RuntimeException("Box answered the folder listing with an item that has no id on request "
                            + (request + 1) + ", so the items cannot be told apart");
                }
                // An item this listing already holds is not listed twice: a page that repeats
                // part of the previous one still makes progress by what it adds.
                if (!seen.add(id)) continue;
                newOnThisPage++;
                if (!"file".equals(entry.path("type").asText())) continue;
                allFiles.add(new BoxFile(
                        id,
                        entry.path("name").asText(),
                        entry.path("type").asText(),
                        entry.path("size").asLong(0),
                        entry.path("modified_at").asText(null),
                        entry.path("parent").path("id").asText(null)
                ));
            }
            String nextMarker = root.path("next_marker").asText("");
            if (nextMarker.isEmpty()) {
                return new FileListing(allFiles, true, null);
            }
            // A marker that moves but returns nothing this listing has not already seen is not
            // progress: read as one, an API that repeats a page would spend the cap on repeats
            // and — offering a marker at the end — be reported as cut, or worse, run out of
            // cap on the last repeat and be reported whole (review, P1).
            if (nextMarker.equals(marker) || newOnThisPage == 0) {
                return new FileListing(allFiles, false, "Box offered marker " + nextMarker
                        + " after a page that added nothing this listing had not seen, so the listing cannot move forward");
            }
            marker = nextMarker;
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
