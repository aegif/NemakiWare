package jp.aegif.nemaki.rest.ingest.note;

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
 * Notion API connector adapter — fetches pages and blocks.
 *
 * <p>Uses Notion API v2022-06-28. Authentication via integration token
 * (passed as Bearer token).
 */
public class NotionConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(NotionConnectorAdapter.class);
    private static final String NOTION_API = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String token;
    private final HttpClient httpClient;

    public NotionConnectorAdapter(String token) {
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record NotionPageSummary(String id, String title, String url, String parentId, String lastEditedTime) {}

    /**
     * Search for pages in the workspace.
     */
    public List<NotionPageSummary> searchPages(String query, int limit) throws Exception {
        String body = query != null && !query.isBlank()
                ? "{\"query\":\"" + query + "\",\"filter\":{\"value\":\"page\",\"property\":\"object\"},\"page_size\":" + limit + "}"
                : "{\"filter\":{\"value\":\"page\",\"property\":\"object\"},\"page_size\":" + limit + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NOTION_API + "/search"))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", NOTION_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Notion API error " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) return List.of();

        List<NotionPageSummary> pages = new ArrayList<>();
        for (JsonNode page : results) {
            String id = page.path("id").asText();
            String url = page.path("url").asText();
            String lastEdited = page.path("last_edited_time").asText();
            String title = extractTitle(page);
            String parentId = extractParentId(page);
            pages.add(new NotionPageSummary(id, title, url, parentId, lastEdited));
        }
        return pages;
    }

    /**
     * Fetch all page blocks (with pagination) and convert to HTML.
     */
    public String fetchPageAsHtml(String pageId) throws Exception {
        List<JsonNode> allBlocks = fetchAllBlocks(pageId);
        StringBuilder html = new StringBuilder();
        for (JsonNode block : allBlocks) {
            html.append(blockToHtml(block, block.path("type").asText()));
        }
        return html.toString();
    }

    /**
     * Extract file attachments from all page blocks (with pagination).
     */
    public List<NotionFile> extractFiles(String pageId) throws Exception {
        List<JsonNode> allBlocks = fetchAllBlocks(pageId);
        List<NotionFile> files = new ArrayList<>();
        for (JsonNode block : allBlocks) {
            String type = block.path("type").asText();
            if ("file".equals(type) || "image".equals(type) || "pdf".equals(type) || "video".equals(type)) {
                JsonNode fileNode = block.path(type);
                String fileUrl = fileNode.path("file").path("url").asText(
                        fileNode.path("external").path("url").asText(null));
                String name = fileNode.path("name").asText(block.path("id").asText() + "." + type);
                if (fileUrl != null && !fileUrl.isBlank()) {
                    files.add(new NotionFile(block.path("id").asText(), name, fileUrl, type));
                }
            }
        }
        return files;
    }

    /**
     * Fetch all child blocks with pagination (follows has_more/next_cursor).
     */
    private List<JsonNode> fetchAllBlocks(String pageId) throws Exception {
        List<JsonNode> allBlocks = new ArrayList<>();
        String cursor = null;
        do {
            String url = NOTION_API + "/blocks/" + pageId + "/children?page_size=100";
            if (cursor != null) url += "&start_cursor=" + cursor;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", NOTION_VERSION)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) break;

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.get("results");
            if (results != null && results.isArray()) {
                for (JsonNode block : results) allBlocks.add(block);
            }
            cursor = root.path("has_more").asBoolean(false) ? root.path("next_cursor").asText(null) : null;
        } while (cursor != null);
        return allBlocks;
    }

    /**
     * Download a file from a URL.
     */
    public InputStream downloadFile(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("File download error " + response.statusCode());
        }
        return response.body();
    }

    public record NotionFile(String blockId, String name, String url, String type) {}

    // ── Internal helpers ──────────────────────────────────────────

    private String extractTitle(JsonNode page) {
        JsonNode props = page.path("properties");
        for (var field : (Iterable<java.util.Map.Entry<String, JsonNode>>) props::fields) {
            JsonNode prop = field.getValue();
            if ("title".equals(prop.path("type").asText())) {
                JsonNode titleArray = prop.get("title");
                if (titleArray != null && titleArray.isArray() && !titleArray.isEmpty()) {
                    return titleArray.get(0).path("plain_text").asText("");
                }
            }
        }
        return "Untitled";
    }

    private String extractParentId(JsonNode page) {
        JsonNode parent = page.get("parent");
        if (parent == null) return null;
        if (parent.has("page_id")) return parent.get("page_id").asText();
        if (parent.has("database_id")) return parent.get("database_id").asText();
        if (parent.has("workspace")) return "workspace";
        return null;
    }

    private String blockToHtml(JsonNode block, String type) {
        return switch (type) {
            case "paragraph" -> "<p>" + richTextToHtml(block.path("paragraph").path("rich_text")) + "</p>\n";
            case "heading_1" -> "<h1>" + richTextToHtml(block.path("heading_1").path("rich_text")) + "</h1>\n";
            case "heading_2" -> "<h2>" + richTextToHtml(block.path("heading_2").path("rich_text")) + "</h2>\n";
            case "heading_3" -> "<h3>" + richTextToHtml(block.path("heading_3").path("rich_text")) + "</h3>\n";
            case "bulleted_list_item" -> "<li>" + richTextToHtml(block.path("bulleted_list_item").path("rich_text")) + "</li>\n";
            case "numbered_list_item" -> "<li>" + richTextToHtml(block.path("numbered_list_item").path("rich_text")) + "</li>\n";
            case "to_do" -> {
                boolean checked = block.path("to_do").path("checked").asBoolean(false);
                yield "<p>" + (checked ? "☑ " : "☐ ") + richTextToHtml(block.path("to_do").path("rich_text")) + "</p>\n";
            }
            case "code" -> "<pre><code>" + richTextToHtml(block.path("code").path("rich_text")) + "</code></pre>\n";
            case "quote" -> "<blockquote>" + richTextToHtml(block.path("quote").path("rich_text")) + "</blockquote>\n";
            case "divider" -> "<hr/>\n";
            case "image" -> {
                String url = block.path("image").path("file").path("url").asText(
                        block.path("image").path("external").path("url").asText(""));
                yield "<img src=\"" + url + "\"/>\n";
            }
            default -> "<!-- unsupported block: " + type + " -->\n";
        };
    }

    private String richTextToHtml(JsonNode richTextArray) {
        if (richTextArray == null || !richTextArray.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode rt : richTextArray) {
            String text = rt.path("plain_text").asText("");
            JsonNode annotations = rt.get("annotations");
            if (annotations != null) {
                if (annotations.path("bold").asBoolean()) text = "<strong>" + text + "</strong>";
                if (annotations.path("italic").asBoolean()) text = "<em>" + text + "</em>";
                if (annotations.path("code").asBoolean()) text = "<code>" + text + "</code>";
                if (annotations.path("strikethrough").asBoolean()) text = "<del>" + text + "</del>";
            }
            String href = rt.path("href").asText(null);
            if (href != null) text = "<a href=\"" + href + "\">" + text + "</a>";
            sb.append(text);
        }
        return sb.toString();
    }
}
