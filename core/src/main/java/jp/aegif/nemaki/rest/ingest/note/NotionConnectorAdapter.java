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
     * caller as the same value. The block listing used to answer a 429 and a 500 with the blocks
     * collected so far — which the note importer states as "this page has no attachments",
     * imports the page without them, and moves the checkpoint past it. (NOT a timed-out page:
     * a request timeout has always come out as an {@code IOException} and has always
     * propagated. An earlier draft of this sentence said otherwise and the correction was added
     * elsewhere in the file while this copy kept the retracted claim.)
     *
     * <p><b>It is not the only way a read fails.</b> A timeout, a dropped connection and a
     * malformed body arrive as {@code IOException} / parse failures and propagate as
     * themselves. Anything that treats this type as "the complete set of incomplete reads"
     * would let those through.
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
     * @param pages what was read, in the order Notion returned it — newest first when
     *     {@code ordered}
     * @param complete true when Notion answered {@code has_more: false} without reporting the
     *     result set incomplete, or when the caller's checkpoint was reached. A response that
     *     omits {@code has_more}, or carries it as anything but a boolean, is refused rather
     *     than read as an end (R61)
     * @param truncatedBecause why it stopped early; null when {@code complete}
     * @param ordered true when the rows came back DESCENDING by {@code last_edited_time} — the
     *     order this adapter asks for, and the one that lets it stop reading at the caller's
     *     checkpoint. False when Notion rejected the sort and the rows were read in whatever
     *     order Notion chose: such a listing is whole only if Notion ended it, and a cut-short
     *     one says nothing about which pages were left out (R59)
     */
    public record PageListing(List<NotionPageSummary> pages, boolean complete,
            String truncatedBecause, boolean ordered) {

        static PageListing whole(List<NotionPageSummary> pages, boolean ordered) {
            return new PageListing(pages, true, null, ordered);
        }

        static PageListing cutShort(List<NotionPageSummary> pages, boolean ordered,
                String because) {
            return new PageListing(pages, false, because, ordered);
        }
    }

    /** How many {@code /search} requests one listing may make unless the caller says otherwise. */
    public static final int DEFAULT_MAX_SEARCH_REQUESTS = 50;

    /**
     * The most results Notion returns for one search query (documented; {@code has_more} turns
     * false there and {@code request_status.type} is {@code incomplete}).
     */
    public static final int NOTION_QUERY_RESULT_LIMIT = 10_000;

    /**
     * List the pages edited at or after {@code since}, newest first.
     *
     * <p>The search is asked for rows DESCENDING by {@code last_edited_time} — the one timestamp
     * sort Notion's {@code /search} documents — and read until the first row edited BEFORE
     * {@code since}. Every row after that one is older still, so the listing is whole without
     * reading them. Rows edited AT {@code since} are kept: Notion rounds an edit time DOWN to the
     * minute, so the minute a checkpoint names is a group of pages of which the caller may have
     * imported only some, and it tells them apart by id.
     *
     * <p>This replaced a listing read in Notion's unspecified order and stopped at the caller's
     * limit (R59). Cut short, that listing was an arbitrary sample, and the caller raised its
     * last-edited checkpoint to the newest row it had seen — every page it had NOT been shown
     * with an older edit time was then filtered out by every later poll, permanently. Asking for
     * ASCENDING order and a limit was tried first and was worse: the limit then returned the
     * oldest pages of the whole workspace on every poll, so nothing past the first batch was
     * ever imported. The listing therefore takes no limit at all; the caller's per-run budget
     * is the caller's, applied to what this method returns.
     *
     * <p>If Notion answers 400 to the sort — the parameter is documented, but this was not
     * verified against a live workspace before it shipped (R106) — the page is asked for again
     * WITHOUT it and the listing is marked unordered. It is then read to Notion's end or to the
     * request cap, because without an order there is no row at which "the rest is older" can
     * be said. The connector does not stop; a cut-short unordered listing is what holds the
     * caller's checkpoint.
     *
     * <p>{@code complete} is true where nothing this method saw says there is more. Notion's own
     * {@code request_status} is read as well as {@code has_more}: a result set Notion reports as
     * {@code incomplete} ({@code query_result_limit_reached}) is a cut whatever {@code has_more}
     * says, unless the checkpoint was reached inside it — Notion cuts the TAIL of the ordered
     * set, and the tail is older than the checkpoint.
     *
     * <p>A response that omits {@code has_more}, or carries it as anything but a boolean, is
     * refused like one that omits {@code results} (R61). Read as {@code false} it called the
     * listing whole, and the caller moved its checkpoint over every page the answer had not
     * shown. It is refused only where the answer depends on it: a page on which the checkpoint
     * was reached, or that Notion reports {@code incomplete}, is decided without it.
     *
     * @param since a {@code last_edited_time} as Notion writes it (the caller's checkpoint
     *     minute), or null to read every page
     * @param maxRequests how many {@code /search} requests to make before giving up and
     *     reporting the listing cut short
     */
    public PageListing searchPages(String query, String since, int maxRequests) throws Exception {
        List<NotionPageSummary> allPages = new ArrayList<>();
        String cursor = null;
        boolean sorted = true;
        // Every /search call counts towards the cap — the retry without the sort included. A
        // cap of N that allowed N+1 calls was not the "number of requests" the parameter
        // documents (review, P3).
        int requests = 0;

        while (requests < maxRequests) {
            HttpResponse<String> response = search(query, cursor, sorted);
            requests++;
            if (response.statusCode() == 400 && sorted) {
                logger.warn("Notion search refused the last_edited_time sort (400); reading the "
                        + "listing unordered — it is whole only if Notion ends it, and cut short "
                        + "it holds the checkpoint: {}",
                        jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
                sorted = false;
                if (requests >= maxRequests) {
                    break;
                }
                response = search(query, cursor, sorted);
                requests++;
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("Notion API error " + response.statusCode() + ": " + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.get("results");
            // A missing or non-array `results` is a malformed answer, not an empty workspace.
            // It used to end the loop the same way a genuine last page does.
            if (results == null || !results.isArray()) {
                throw new NotionReadIncompleteException("Notion search answered without a results "
                        + "array on request " + requests + ", so how many pages exist is unknown");
            }
            // Notion pagination: has_more + next_cursor. Read BEFORE the empty-page arm, because
            // an empty page that says there is more is not the end of anything — treating it as
            // one reported the rest of the workspace as nothing (both reviews, P1). Decided
            // below, after the rows: whether it may be missing depends on what they showed.
            JsonNode hasMore = root.get("has_more");
            String nextCursor = root.path("next_cursor").asText(null);
            // Notion's OWN statement that the result set was cut. Documented beside has_more as
            // {"type": "complete" | "incomplete", "incomplete_reason": "query_result_limit_reached"}
            // — an earlier draft read fields named status / reason, which Notion never writes,
            // so it could never have fired.
            JsonNode requestStatus = root.path("request_status");
            boolean notionSaysIncomplete = "incomplete".equalsIgnoreCase(
                    requestStatus.path("type").asText(""));

            for (JsonNode pageNode : results) {
                // A page object always carries last_edited_time; one without it is a malformed
                // answer. Tolerated as null, such a row could never be placed against the
                // checkpoint — and in an ordered listing it could sit BELOW the row this loop
                // stops at, never to be read (review, P1). Refused, like a missing results array.
                if (!pageNode.hasNonNull("last_edited_time")) {
                    throw new NotionReadIncompleteException("Notion search answered a page without "
                            + "last_edited_time (" + pageNode.path("id").asText("no id") + ") on request "
                            + requests + ", so it cannot be placed against the checkpoint");
                }
                String lastEdited = pageNode.get("last_edited_time").asText();
                if (sorted && since != null && lastEdited.compareTo(since) < 0) {
                    // The checkpoint: this row and every row after it were edited before it.
                    return logged(query, since, PageListing.whole(allPages, true));
                }
                String id = pageNode.path("id").asText();
                String url = pageNode.path("url").asText();
                String title = extractTitle(pageNode);
                String parentId = extractParentId(pageNode);
                allPages.add(new NotionPageSummary(id, title, url, parentId, lastEdited));
            }

            if (notionSaysIncomplete) {
                return logged(query, since, PageListing.cutShort(allPages, sorted,
                        "Notion reported the search result set as incomplete ("
                                + requestStatus.path("incomplete_reason").asText("no reason given")
                                + ")"));
            }
            // A missing or non-boolean has_more is a malformed answer, like a missing results
            // array — not "no more". Read as false it called the listing whole and the caller
            // moved its checkpoint over every page this answer did not show (R61). Refused HERE
            // and not beside results: a page on which the checkpoint was reached, or that Notion
            // reports incomplete, has already been answered without it, and refusing those would
            // be refusing a whole answer.
            if (hasMore == null || !hasMore.isBoolean()) {
                throw new NotionReadIncompleteException("Notion search answered "
                        + (hasMore == null ? "without has_more" : "has_more as " + hasMore.getNodeType())
                        + " on request " + requests + " after " + allPages.size() + " page(s), so "
                        + "whether more pages exist is unknown");
            }
            if (!hasMore.booleanValue()) {
                // Notion caps one query at 10,000 results: pagination then stops with
                // has_more=false and a request_status saying so. If that field is ABSENT at
                // exactly the cap — an API version that does not write it, or a shape this
                // reader does not recognise — has_more alone would call the listing whole and
                // the checkpoint would move over the tail Notion cut. The documented number is
                // read as the cut instead (review, P1 on R106).
                if (requestStatus.isMissingNode() && allPages.size() >= NOTION_QUERY_RESULT_LIMIT) {
                    return logged(query, since, PageListing.cutShort(allPages, sorted,
                            "Notion's documented limit of " + NOTION_QUERY_RESULT_LIMIT
                                    + " results per query was reached and the response carried no "
                                    + "request_status, so whether the result set was cut is unknown"));
                }
                // Notion said there is no more.
                return logged(query, since, PageListing.whole(allPages, sorted));
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
        // The request cap: Notion offered another cursor and this method stopped asking. Counting
        // that as a whole listing is what let the caller raise its checkpoint over pages it had
        // never been shown.
        return logged(query, since, PageListing.cutShort(allPages, sorted,
                "the cap of " + maxRequests + " search request(s) was reached with " + allPages.size()
                        + " page(s) read and more still offered (raise the profile's "
                        + "notionSearchMaxRequests parameter, or narrow the query)"));
    }

    private HttpResponse<String> search(String query, String cursor, boolean sorted)
            throws Exception {
        var bodyNode = MAPPER.createObjectNode();
        if (query != null && !query.isBlank()) {
            bodyNode.put("query", query);
        }
        bodyNode.putObject("filter").put("value", "page").put("property", "object");
        if (sorted) {
            bodyNode.putObject("sort").put("direction", "descending")
                    .put("timestamp", "last_edited_time");
        }
        bodyNode.put("page_size", 100); // Notion max page_size: 100
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
        return jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(httpClient, request,
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * One logging point for every way {@link #searchPages} RETURNS. The refusals throw and are
     * recorded by the caller as errors; "every way it ends" covered those too, which it does not
     * (subagent review, P3).
     */
    private PageListing logged(String query, String since, PageListing listing) {
        logger.info("Notion searchPages: query='{}', since={}, fetched={}, complete={}, ordered={}{}",
                query, since, listing.pages().size(), listing.complete(), listing.ordered(),
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
     * @throws NotionReadIncompleteException when the STORE's answer stopped the listing for any
     *     reason other than saying there is nothing more. A timeout, a dropped connection or an
     *     unparseable body do not arrive as this type — they propagate as themselves, and a
     *     caller that treats this type as the whole of "did not finish" will miss them
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
            // As in the search: a missing or non-boolean has_more is a malformed answer, not the
            // end of the page. Read as false it was "that is the whole page" — for extractFiles,
            // "this page has no attachments" — without Notion having said so (R61).
            JsonNode hasMore = root.get("has_more");
            if (hasMore == null || !hasMore.isBoolean()) {
                throw new NotionReadIncompleteException("Notion answered block page " + (page + 1)
                        + " of " + pageId
                        + (hasMore == null ? " without has_more" : " with has_more as " + hasMore.getNodeType())
                        + " after " + allBlocks.size() + " block(s), so whether the page has more "
                        + "blocks is unknown");
            }
            if (!hasMore.booleanValue()) {
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
