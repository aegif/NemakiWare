package jp.aegif.nemaki.rest.ingest.note;

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
 * Notion API connector adapter — fetches pages and blocks.
 *
 * <p>Uses Notion API v2022-06-28. Authentication via integration token
 * (passed as Bearer token).
 */
public class NotionConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(NotionConnectorAdapter.class);
    private static final String DEFAULT_API = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28"; // Stable version; upgrade to newer when needed
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private final String token;
    private final String apiBase;
    private final HttpClient httpClient;

    public NotionConnectorAdapter(String token) {
        this(token, DEFAULT_API);
    }

    public NotionConnectorAdapter(String token, String apiBase) {
        this(token, apiBase, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
    }

    public NotionConnectorAdapter(String token, String apiBase, HttpClient httpClient) {
        this.token = token;
        this.apiBase = apiBase;
        this.httpClient = httpClient;
    }

    public record NotionPageSummary(String id, String title, String url, String parentId, String lastEditedTime) {}

    /**
     * A listing this adapter could not finish reading.
     *
     * <p>Distinct from every "there is nothing more" so that the two can never be handed to a
     * caller as the same value. The block listing used to answer a 429, a 500 and a timed-out
     * page with the blocks collected so far — which the note importer states as "this page has
     * no attachments", imports the page without them, and moves the checkpoint past it.
     */
    public static class NotionReadIncompleteException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotionReadIncompleteException(String message) {
            super(message);
        }
    }

    /**
     * A page listing, and whether it is the WHOLE listing.
     *
     * @param pages what was read
     * @param complete true when nothing this method saw says there is more. NOT the same as
     *     "Notion answered that there is nothing after these": a response that OMITS
     *     {@code has_more} is read as false, i.e. as an end (R61 — recorded, not fixed,
     *     because the plan stops this area after its second P1)
     * @param truncatedBecause why it stopped early; null when {@code complete}
     */
    public record PageListing(List<NotionPageSummary> pages, boolean complete,
            String truncatedBecause) {

        static PageListing whole(List<NotionPageSummary> pages) {
            return new PageListing(pages, true, null);
        }

        static PageListing cutShort(List<NotionPageSummary> pages, String because) {
            return new PageListing(pages, false, because);
        }
    }

    /**
     * Search for pages in the workspace with {@code start_cursor} pagination.
     *
     * <p>Notion API supports max {@code page_size} of 100.  This method
     * follows {@code next_cursor} across pages until {@code limit} results
     * are collected or no more pages remain.
     *
     * <p>It returns whether the listing is whole. Stopping at the caller's {@code limit} or at
     * the page cap is legitimate and common — what is not legitimate is handing back the same
     * shape for "these are all the pages" and "these are the first N of more", because the
     * caller advances a last-edited-time checkpoint from what it was given.
     *
     * <p>{@code complete} is true where nothing this method saw says there is more. Two ways of
     * getting that wrong were fixed: an empty {@code results} page may still carry
     * {@code has_more}, and a {@code limit} above 100 lands in the middle of a page, so rows
     * this method has already read can be dropped while Notion's own {@code has_more} for that
     * page is false.
     *
     * <p><b>One is left open and recorded (R61).</b> A response that omits {@code has_more}
     * altogether is read as {@code false} — as an end — while the line above it refuses a
     * response that omits {@code results}. The two are equally broken answers and only one is
     * refused. This javadoc used to say {@code complete} means "Notion has ANSWERED that there
     * is nothing after what came back", which that asymmetry makes false; the sentence is
     * corrected here rather than the code, because the plan stops this area after its second
     * P1 and the fix belongs with whoever opens it.
     */
    public PageListing searchPages(String query, int limit) throws Exception {
        int pageSize = Math.min(limit, 100); // Notion max page_size: 100
        List<NotionPageSummary> allPages = new ArrayList<>();
        String cursor = null;
        // Set only where Notion has ANSWERED that there is nothing more. Every other way out of
        // this loop is a return or a throw, so the flag is what tells the cap apart from an end.
        boolean nothingMore = false;

        for (int page = 0; page < 50; page++) { // Hard cap on pages
            var bodyNode = MAPPER.createObjectNode();
            if (query != null && !query.isBlank()) {
                bodyNode.put("query", query);
            }
            bodyNode.putObject("filter").put("value", "page").put("property", "object");
            bodyNode.put("page_size", pageSize);
            if (cursor != null) bodyNode.put("start_cursor", cursor);
            String body = MAPPER.writeValueAsString(bodyNode);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/search"))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", NOTION_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(httpClient, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Notion API error " + response.statusCode() + ": " + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.get("results");
            // A missing or non-array `results` is a malformed answer, not an empty workspace.
            // It used to end the loop the same way a genuine last page does.
            if (results == null || !results.isArray()) {
                throw new NotionReadIncompleteException("Notion search answered without a results "
                        + "array on page " + (page + 1) + ", so how many pages exist is unknown");
            }
            // Notion pagination: has_more + next_cursor. Read BEFORE the empty-page arm, because
            // an empty page that says there is more is not the end of anything — treating it as
            // one reported the rest of the workspace as nothing (both reviews, P1).
            boolean hasMore = root.path("has_more").asBoolean(false);
            String nextCursor = root.path("next_cursor").asText(null);

            if (results.isEmpty()) {
                if (!hasMore) {
                    // Notion said there is no more — or omitted has_more, which is read
                    // the same way and is not the same thing (R61).
                    nothingMore = true;
                    break;
                }
                if (nextCursor == null || nextCursor.isEmpty()) {
                    throw new NotionReadIncompleteException("Notion search answered an empty page "
                            + "that says there are more, and gave no cursor to read them with, "
                            + "after " + allPages.size() + " page(s)");
                }
                cursor = nextCursor;
                continue;
            }

            // The limit is checked BEFORE adding, so `read` counts what was kept and the rest of
            // this page is known to have been left behind.
            int read = 0;
            for (JsonNode pageNode : results) {
                if (allPages.size() >= limit) {
                    break;
                }
                String id = pageNode.path("id").asText();
                String url = pageNode.path("url").asText();
                String lastEdited = pageNode.path("last_edited_time").asText();
                String title = extractTitle(pageNode);
                String parentId = extractParentId(pageNode);
                allPages.add(new NotionPageSummary(id, title, url, parentId, lastEdited));
                read++;
            }
            int leftOnThePage = results.size() - read;

            if (allPages.size() >= limit) {
                // `hasMore` alone is not enough. With limit > 100 the page size is 100, so the
                // limit falls in the MIDDLE of a page: Notion can answer has_more=false for a
                // page whose last rows this method just dropped. Reporting that as the whole
                // listing raised the caller's checkpoint over rows it had read and thrown away
                // (subagent review, P1).
                if (hasMore || leftOnThePage > 0) {
                    return logged(query, limit, PageListing.cutShort(allPages, "the caller's limit of " + limit
                            + " was reached" + (leftOnThePage > 0
                                    ? " part-way through a page, leaving " + leftOnThePage
                                            + " already-read page(s) out"
                                    : " and Notion says there are more pages")));
                }
                return logged(query, limit, PageListing.whole(allPages));
            }
            if (!hasMore) {
                return logged(query, limit, PageListing.whole(allPages));
            }
            if (nextCursor == null || nextCursor.isEmpty()) {
                // has_more with nowhere to go. Ending here quietly reported the rest of the
                // workspace as "nothing more".
                throw new NotionReadIncompleteException("Notion search said there are more pages "
                        + "and gave no cursor to read them with, after " + allPages.size()
                        + " page(s)");
            }
            cursor = nextCursor;
        }

        if (nothingMore) {
            return logged(query, limit, PageListing.whole(allPages));
        }
        // The 50-page cap: Notion offered another cursor and this method stopped asking. Counting
        // that as a whole listing is what let the caller raise its checkpoint over pages it had
        // never been shown.
        return logged(query, limit, PageListing.cutShort(allPages,
                "the 50-page pagination cap was reached with " + allPages.size()
                        + " page(s) read and more still offered"));
    }

    /**
     * One logging point for every way {@link #searchPages} ends.
     *
     * <p>The line used to run on every call. Restructuring the exits left it reachable from two
     * of six, so the ordinary case stopped logging and the truncation cases — the ones worth
     * investigating — logged nothing either (subagent review, P3).
     */
    private PageListing logged(String query, int limit, PageListing listing) {
        logger.info("Notion searchPages: query='{}', fetched={}, limit={}, complete={}{}",
                query, listing.pages().size(), limit, listing.complete(),
                listing.complete() ? "" : " (" + listing.truncatedBecause() + ")");
        return listing;
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
                // Skip OS/desktop pseudo files (e.g. a macOS .textClipping) here
                // so we don't even spend a download on a value-less blob. The
                // canonical import path has the same guard as an all-connector
                // backstop.
                if (jp.aegif.nemaki.rest.ingest.FetchSupport.isPseudoSystemFile(name)) {
                    logger.info("Notion: skipping OS pseudo file attachment '{}'", name);
                    continue;
                }
                if (fileUrl != null && !fileUrl.isBlank()) {
                    files.add(new NotionFile(block.path("id").asText(), name, fileUrl, type));
                }
            }
        }
        return files;
    }

    /**
     * Fetch all child blocks with pagination (follows has_more/next_cursor).
     *
     * <p><b>This never returns a partial listing.</b> It used to: any non-200 — a 429 that
     * outlived {@code sendWithRetry}'s backoff, a 500, a gateway's own error page — ended the
     * loop and returned the blocks read so far. (NOT this method's 30-second request timeout:
     * that has always come out of {@code sendWithRetry} as an {@code IOException} and has always
     * propagated. An earlier draft of this sentence said otherwise, which overstated what the
     * old {@code break} did.) Both callers state that result as a fact about
     * the page: {@link #extractFiles} as "this page has no attachments" (so the note is imported
     * without them, no dead letter is written, and the poller's last-edited checkpoint moves past
     * the page, which is permanent) and {@link #fetchPageAsHtml} as the page's body. A read that
     * failed and a page that is empty are now different outcomes, which is the whole point.
     *
     * @throws NotionReadIncompleteException when the listing stopped for any reason other than
     *     Notion answering that there is nothing more
     */
    private List<JsonNode> fetchAllBlocks(String pageId) throws Exception {
        List<JsonNode> allBlocks = new ArrayList<>();
        String cursor = null;
        String prevCursor = null;
        for (int page = 0; page < 100; page++) { // Hard cap to prevent infinite loops
            String url = apiBase + "/blocks/" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(pageId) + "/children?page_size=100";
            if (cursor != null) url += "&start_cursor=" + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.encodePathSegment(cursor);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", NOTION_VERSION)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(httpClient, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new NotionReadIncompleteException("Notion answered " + response.statusCode()
                        + " for block page " + (page + 1) + " of " + pageId + " after "
                        + allBlocks.size() + " block(s). What the page contains is unknown — this "
                        + "is not a finding that it has no attachments: "
                        + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                throw new NotionReadIncompleteException("Notion answered block page " + (page + 1)
                        + " of " + pageId + " without a results array, so what the page contains "
                        + "is unknown");
            }
            for (JsonNode block : results) {
                allBlocks.add(block);
            }
            boolean hasMore = root.path("has_more").asBoolean(false);
            if (!hasMore) {
                // As above: an omitted has_more reaches here as "that is the whole
                // page" without Notion having said so (R61).
                return allBlocks;
            }
            cursor = root.path("next_cursor").asText(null);
            if (cursor == null || cursor.isEmpty()) {
                throw new NotionReadIncompleteException("Notion said page " + pageId + " has more "
                        + "blocks and gave no cursor to read them with, after " + allBlocks.size()
                        + " block(s)");
            }
            // A cursor that does not move is the API failing to paginate, not the end of the
            // page. Stopping here reported the rest of the page as absent.
            if (cursor.equals(prevCursor)) {
                throw new NotionReadIncompleteException("Notion repeated the same block cursor for "
                        + pageId + " after " + allBlocks.size() + " block(s), so the rest of the "
                        + "page cannot be read");
            }
            prevCursor = cursor;
        }
        throw new NotionReadIncompleteException("the 100-page block cap was reached for " + pageId
                + " with " + allBlocks.size() + " block(s) read and more still offered");
    }

    /**
     * Download a file from a URL.
     */
    public InputStream downloadFile(String url) throws Exception {
        jp.aegif.nemaki.rest.ingest.AdapterHttpClient.validateExternalUrl(url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRedirectValidation(request, HttpResponse.BodyHandlers.ofInputStream(), 5);
        return jp.aegif.nemaki.rest.ingest.AdapterHttpClient.requireOkOrClose(response, "Notion file download");
    }

    public record NotionFile(String blockId, String name, String url, String type) {}

    // ── Internal helpers ──────────────────────────────────────────

    private String extractTitle(JsonNode page) {
        JsonNode props = page.path("properties");
        for (var field : props.properties()) {
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
                yield "<img src=\"" + escapeHtmlAttr(url) + "\"/>\n";
            }
            default -> "<!-- unsupported block: " + type + " -->\n";
        };
    }

    private String richTextToHtml(JsonNode richTextArray) {
        if (richTextArray == null || !richTextArray.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode rt : richTextArray) {
            String text = escapeHtml(rt.path("plain_text").asText(""));
            JsonNode annotations = rt.get("annotations");
            if (annotations != null) {
                if (annotations.path("bold").asBoolean()) text = "<strong>" + text + "</strong>";
                if (annotations.path("italic").asBoolean()) text = "<em>" + text + "</em>";
                if (annotations.path("code").asBoolean()) text = "<code>" + text + "</code>";
                if (annotations.path("strikethrough").asBoolean()) text = "<del>" + text + "</del>";
            }
            String href = rt.path("href").asText(null);
            if (href != null) text = "<a href=\"" + escapeHtmlAttr(href) + "\">" + text + "</a>";
            sb.append(text);
        }
        return sb.toString();
    }

    /** Escape HTML text content. */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Escape HTML attribute values (quotes + standard entities). */
    private static String escapeHtmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
