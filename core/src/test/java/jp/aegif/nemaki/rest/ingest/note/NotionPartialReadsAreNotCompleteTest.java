/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.ingest.note;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jp.aegif.nemaki.rest.ingest.CanonicalImportService;
import jp.aegif.nemaki.rest.ingest.CheckpointManager;
import jp.aegif.nemaki.rest.ingest.ConnectorDefinition;
import jp.aegif.nemaki.rest.ingest.ExternalIngestRequest;
import jp.aegif.nemaki.rest.ingest.ExternalIngestResult;
import jp.aegif.nemaki.rest.ingest.FetchResult;
import jp.aegif.nemaki.rest.ingest.FetchSupport;
import jp.aegif.nemaki.rest.ingest.ImportProfileDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the Notion connector does when a read does not finish (R14, plan A-8).
 *
 * <h2>Why none of this could be measured before</h2>
 *
 * <p>{@code NotionFetchOrchestrator} built its adapter with {@code new
 * NotionConnectorAdapter(token)}, so every arm below was reachable only by talking to Notion.
 * They were written from reasoning, reviewed from reasoning, and never once run. The orchestrator
 * now takes an {@code adapterFactory} — the same shape {@code ImapIdleMonitor} already used — and
 * these tests point the REAL adapter at a local stub of the Notion API. The adapter's own status
 * handling, retry and pagination are therefore what is measured, not a mock's idea of them.
 *
 * <h2>The defect this found</h2>
 *
 * <p>The block listing answered a 429, a 500 and a broken connection with the blocks it had
 * collected so far. {@code extractFiles} then reported an empty file list, which the orchestrator
 * states as a fact: the note is imported with no attachments, no dead letter is written, and the
 * last-edited checkpoint moves past the page — after which no poll will ever look at it again.
 *
 * <h2>About the timeout</h2>
 *
 * <p>The adapter's requests carry a 30-second timeout. Waiting that out would make this class
 * take half a minute per case, so what is exercised here is the BROKEN CONNECTION: it arrives at
 * the same place, as an {@code IOException} out of {@code sendWithRetry}. A socket timeout is not
 * itself measured here and this class does not claim it is.
 */
class NotionPartialReadsAreNotCompleteTest {

    private static HttpServer server;
    private static String apiBase;
    private static String previousAllowLocalhost;

    /** What /v1/search answers. Set per test. */
    private static volatile Responder search;
    /** What /v1/blocks/{id}/children answers. Set per test. */
    private static volatile Responder blocks;

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, int callNumber) throws IOException;
    }

    private static final AtomicInteger SEARCH_CALLS = new AtomicInteger();
    private static final AtomicInteger BLOCK_CALLS = new AtomicInteger();

    @BeforeAll
    static void startStub() throws Exception {
        // Documented test-only escape: AdapterHttpClient refuses loopback targets otherwise, and
        // it reads the property per call, so setting it here is enough.
        previousAllowLocalhost = System.getProperty("nemaki.ingest.allowLocalhost");
        System.setProperty("nemaki.ingest.allowLocalhost", "true");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/search", exchange ->
                search.respond(exchange, SEARCH_CALLS.incrementAndGet()));
        server.createContext("/v1/blocks", exchange ->
                blocks.respond(exchange, BLOCK_CALLS.incrementAndGet()));
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
        if (previousAllowLocalhost == null) {
            System.clearProperty("nemaki.ingest.allowLocalhost");
        } else {
            System.setProperty("nemaki.ingest.allowLocalhost", previousAllowLocalhost);
        }
    }

    @BeforeEach
    void resetCounters() {
        SEARCH_CALLS.set(0);
        BLOCK_CALLS.set(0);
        search = (exchange, n) -> json(exchange, 200, onePage("page-1", "2026-01-01T00:00:00.000Z", false, null));
        blocks = (exchange, n) -> json(exchange, 200, "{\"results\":[],\"has_more\":false}");
    }

    // ── the stub ───────────────────────────────────────────────────

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // Retry-After: 0 so sendWithRetry's backoff does not turn a 429 case into a 14-second
        // test. The retries still happen — BLOCK_CALLS counts them.
        exchange.getResponseHeaders().add("Retry-After", "0");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Headers, then the connection dies. What a dropped or timed-out read looks like. */
    private static void cutTheConnection(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 4096); // promises 4096 bytes
        exchange.getResponseBody().write("{\"res".getBytes(StandardCharsets.UTF_8));
        exchange.close(); // and delivers five
    }

    private static String onePage(String id, String lastEdited, boolean hasMore, String cursor) {
        return "{\"results\":[{\"id\":\"" + id + "\",\"url\":\"https://notion.so/" + id + "\","
                + "\"last_edited_time\":\"" + lastEdited + "\","
                + "\"properties\":{\"title\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"T\"}]}},"
                + "\"parent\":{\"workspace\":true}}],"
                + "\"has_more\":" + hasMore
                + (cursor == null ? "" : ",\"next_cursor\":\"" + cursor + "\"") + "}";
    }

    /** Three rows on one page, so a limit can be made to fall inside it. */
    private static String threePages(boolean hasMore) {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            if (i > 1) {
                rows.append(',');
            }
            rows.append("{\"id\":\"p-").append(i).append("\",\"url\":\"https://notion.so/p")
                    .append(i).append("\",\"last_edited_time\":\"2026-01-0").append(i)
                    .append("T00:00:00.000Z\",")
                    .append("\"properties\":{\"title\":{\"type\":\"title\","
                            + "\"title\":[{\"plain_text\":\"T\"}]}},")
                    .append("\"parent\":{\"workspace\":true}}");
        }
        return "{\"results\":[" + rows + "],\"has_more\":" + hasMore + "}";
    }

    private static String blockPage(String blockId, boolean hasMore, String cursor) {
        return "{\"results\":[{\"id\":\"" + blockId + "\",\"type\":\"paragraph\","
                + "\"paragraph\":{\"rich_text\":[{\"plain_text\":\"hello\"}]}}],"
                + "\"has_more\":" + hasMore
                + (cursor == null ? "" : ",\"next_cursor\":\"" + cursor + "\"") + "}";
    }

    // ── the orchestrator under test ────────────────────────────────

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService importService;
    private final List<String> dlqReasons = new ArrayList<>();
    /** Source object ids handed to the import service, in the order they were handed over. */
    private final List<String> importedIds = new ArrayList<>();

    private NotionFetchOrchestrator orchestrator() {
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        dlqReasons.clear();

        lenient().when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("secret-token");
        lenient().doNothing().when(fetchSupport).throttle(anyLong());
        lenient().doAnswer(call -> {
            dlqReasons.add(call.getArgument(1));
            return null;
        }).when(fetchSupport).saveSourceNeverReadToDlq(any(), anyString());
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString()))
                .thenReturn(null);
        importedIds.clear();
        lenient().when(importService.executeNoteImport(any(), any())).thenAnswer(call -> {
            ExternalIngestRequest req = call.getArgument(1);
            importedIds.add(req.getSourceObjectId());
            return new ExternalIngestResult("r", "obj-1", "1.0", false, false, false, null, null,
                    List.of(), List.of());
        });

        NotionFetchOrchestrator orchestrator = new NotionFetchOrchestrator();
        orchestrator.setFetchSupport(fetchSupport);
        orchestrator.setCheckpointManager(checkpointManager);
        orchestrator.setCanonicalImportService(importService);
        // The injection this whole class exists because of: the real adapter, pointed at the stub.
        orchestrator.adapterFactory = token -> new NotionConnectorAdapter(token, apiBase);
        return orchestrator;
    }

    private static ImportProfileDefinition profile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-notion");
        profile.setRepositoryId("bedroom");
        profile.setImportPolicy("files_only");
        return profile;
    }

    private static ConnectorDefinition connector() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-notion");
        connector.setSourceSystem("notion");
        return connector;
    }

    private FetchResult run() {
        return orchestrator().execute(null, profile(), connector(), Map.of(), 10);
    }

    // ── 429 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a rate-limited block listing is not the finding that the page has no attachments")
    void aRateLimitedBlockListingIsNotAnEmptyPage() {
        blocks = (exchange, n) -> json(exchange, 429, "{\"message\":\"rate_limited\"}");

        FetchResult result = run();

        assertTrue(BLOCK_CALLS.get() > 1,
                "sendWithRetry did not retry the 429 at all — this case is not what it says it is");
        verify(importService, never()).executeNoteImport(any(), any());
        assertEquals(1, dlqReasons.size(),
                "the page was not dead-lettered, so nothing records that its attachments were "
                        + "never read: " + dlqReasons);
        assertTrue(dlqReasons.get(0).contains("429"), dlqReasons.get(0));
        assertTrue(result.hasErrors(), "the run reported no error for a page it could not read");
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    // ── a broken read (the timeout's arrival point) ────────────────

    @Test
    @DisplayName("a block listing whose connection dies is not an empty page either")
    void aBrokenBlockListingIsNotAnEmptyPage() {
        blocks = (exchange, n) -> cutTheConnection(exchange);

        FetchResult result = run();

        verify(importService, never()).executeNoteImport(any(), any());
        assertEquals(1, dlqReasons.size(), "the page was not dead-lettered: " + dlqReasons);
        assertTrue(result.hasErrors(), "the run reported no error for a page it could not read");
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    // ── page N+1 ───────────────────────────────────────────────────

    @Test
    @DisplayName("a failure on the SECOND block page does not pass the first page off as the page")
    void aFailureAfterTheFirstBlockPageIsNotAPartialPage() {
        // The arm that is easiest to get wrong: page one arrives, so there IS something to
        // return, and returning it looks like success. The page's attachments may all be on
        // page two.
        blocks = (exchange, n) -> {
            if (n == 1) {
                json(exchange, 200, blockPage("b-1", true, "cursor-2"));
            } else {
                json(exchange, 500, "{\"message\":\"boom\"}");
            }
        };

        FetchResult result = run();

        assertTrue(BLOCK_CALLS.get() >= 2, "the second block page was never requested");
        verify(importService, never()).executeNoteImport(any(), any());
        assertEquals(1, dlqReasons.size(), "the page was not dead-lettered: " + dlqReasons);
        assertTrue(dlqReasons.get(0).contains("500"), dlqReasons.get(0));
        assertTrue(result.hasErrors(), "the run reported no error");
    }

    @Test
    @DisplayName("has_more with no cursor is not the end of the page")
    void hasMoreWithoutACursorIsRefused() {
        blocks = (exchange, n) -> json(exchange, 200,
                "{\"results\":[{\"id\":\"b-1\",\"type\":\"paragraph\"}],\"has_more\":true}");

        run();

        verify(importService, never()).executeNoteImport(any(), any());
        assertEquals(1, dlqReasons.size(), "the page was not dead-lettered: " + dlqReasons);
    }

    // ── the partial listing ────────────────────────────────────────

    @Test
    @DisplayName("a run whose budget leaves pages for the next poll is not a complete run")
    void aTruncatedListingIsNotComplete() {
        // Two pages newer than the checkpoint and a budget of one. Nothing failed — and that
        // is exactly why it used to be indistinguishable from having seen everything.
        search = (exchange, n) -> json(exchange, 200, pageOf(false,
                "p-2@2026-01-02T00:00:00.000Z", "p-1@2026-01-01T00:00:00.000Z"));

        FetchResult result = orchestrator().execute(null, profile(), connector(), Map.of(), 1);

        assertFalse(result.sawEverything(),
                "the run says it saw the whole workspace, and a page was left for the next poll: "
                        + result);
        assertEquals(1, result.incompleteReads().size(), result.incompleteReads().toString());
        assertTrue(result.incompleteReads().get(0).contains("limit"),
                result.incompleteReads().get(0));
        assertFalse(result.hasErrors(),
                "a run that stopped at its budget is not a FAILURE — reporting it as one makes "
                        + "the scheduler count a healthy workspace towards the connector's "
                        + "circuit breaker: " + result.errors());
        assertEquals(List.of("p-1"), importedIds, "the budget did not take the OLDEST page first");
    }

    @Test
    @DisplayName("a listing Notion said was whole is not reported as cut short")
    void aWholeListingIsNotReportedAsTruncated() {
        // The over-refusal side. A connector that called every run partial would satisfy the
        // test above and tell an operator nothing.
        search = (exchange, n) -> json(exchange, 200,
                onePage("page-1", "2026-01-01T00:00:00.000Z", false, null));

        FetchResult result = run();

        assertTrue(result.sawEverything(),
                "a complete listing was reported as cut short: " + result.incompleteReads());
        assertEquals(1, result.fetched(), result.toString());
    }

    @Test
    @DisplayName("an EMPTY search page that says there is more is not the end of the workspace")
    void anEmptyPageThatSaysThereIsMoreIsFollowed() {
        // Both reviews, P1. The empty-results arm answered "there are no more pages" without
        // ever looking at has_more, so a workspace whose first page came back empty — a filter,
        // a permission, a shard that answered late — was reported as fully read, and the
        // checkpoint moved on.
        search = (exchange, n) -> {
            if (n == 1) {
                json(exchange, 200, "{\"results\":[],\"has_more\":true,\"next_cursor\":\"c2\"}");
            } else {
                json(exchange, 200, onePage("page-2", "2026-02-02T00:00:00.000Z", false, null));
            }
        };

        FetchResult result = run();

        assertTrue(SEARCH_CALLS.get() >= 2, "the cursor after the empty page was never followed");
        assertEquals(1, result.fetched(), "the page behind the empty one was never seen: " + result);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
    }

    @Test
    @DisplayName("an empty search page that says there is more, with no cursor, is refused")
    void anEmptyPageWithMoreAndNoCursorIsRefused() {
        search = (exchange, n) -> json(exchange, 200, "{\"results\":[],\"has_more\":true}");

        FetchResult result = run();

        assertTrue(result.hasErrors(),
                "Notion said there are more pages and gave nowhere to look; the run reported no "
                        + "problem: " + result);
        assertEquals(0, result.fetched(), result.toString());
    }

    @Test
    @DisplayName("a budget that stops inside a page leaves the rest for the next poll, and the checkpoint names only what was taken")
    void aLimitInsideAPageIsStillTruncation() {
        // Once the subagent's P1 about a limit landing mid-page: rows the adapter had READ were
        // dropped while Notion's has_more was false, and the listing called itself whole. The
        // adapter no longer takes a limit (R59); the run's budget is applied to the whole
        // listing, oldest first, and what it leaves is reported.
        search = (exchange, n) -> json(exchange, 200, threePages(false));

        FetchResult result = orchestrator().execute(null, profile(), connector(), Map.of(), 2);

        assertFalse(result.sawEverything(),
                "a row this run listed and did not import was reported as nothing left: " + result);
        assertTrue(result.incompleteReads().get(0).contains("left for the next poll"),
                result.incompleteReads().get(0));
        assertEquals(List.of("p-1", "p-2"), importedIds);
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-02T00:00:00.000Z|p-2");
    }

    @Test
    @DisplayName("a limit that falls exactly on a page boundary is not truncation")
    void aLimitOnThePageBoundaryIsNotTruncation() {
        // The over-refusal side of the one above. Taking every row of a page that says there is
        // nothing after it is a WHOLE listing, and calling it partial would make the flag mean
        // nothing for every workspace smaller than one poll.
        search = (exchange, n) -> json(exchange, 200, threePages(false));

        FetchResult result = orchestrator().execute(null, profile(), connector(), Map.of(), 3);

        assertTrue(result.sawEverything(),
                "a listing that ended exactly at the limit, with Notion saying there is no more, "
                        + "was reported as cut short: " + result.incompleteReads());
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-03T00:00:00.000Z|p-3");
    }

    @Test
    @DisplayName("a search page without a results array is not an empty workspace")
    void aSearchPageWithoutResultsIsRefused() {
        search = (exchange, n) -> json(exchange, 200, "{\"has_more\":false}");

        FetchResult result = run();

        assertTrue(result.hasErrors(), "a malformed search answer was read as an empty workspace: "
                + result);
        assertEquals(0, result.fetched(), result.toString());
    }

    @Test
    @DisplayName("a block page without a results array is not a page with no blocks")
    void aBlockPageWithoutResultsIsRefused() {
        blocks = (exchange, n) -> json(exchange, 200, "{\"has_more\":false}");

        run();

        verify(importService, never()).executeNoteImport(any(), any());
        assertEquals(1, dlqReasons.size(), "the page was not dead-lettered: " + dlqReasons);
    }

    @Test
    @DisplayName("a block cursor that does not move is not the end of the page")
    void aRepeatedBlockCursorIsRefused() {
        // Notion failing to paginate. Stopping quietly reported the rest of the page as absent,
        // which for extractFiles means "no attachments".
        blocks = (exchange, n) -> json(exchange, 200, blockPage("b-" + n, true, "same-cursor"));

        run();

        verify(importService, never()).executeNoteImport(any(), any());
        assertEquals(1, dlqReasons.size(), "the page was not dead-lettered: " + dlqReasons);
        assertTrue(dlqReasons.get(0).contains("repeated"), dlqReasons.get(0));
    }

    // ── the answer that must stay an answer ────────────────────────

    @Test
    @DisplayName("a page that really has no blocks still imports, with no dead letter")
    void anEmptyPageIsStillAnAnswer() {
        // The whole point of the refusals above is that they are NOT this. If "no attachments"
        // had become a refusal, every ordinary note in the workspace would dead-letter.
        blocks = (exchange, n) -> json(exchange, 200, "{\"results\":[],\"has_more\":false}");

        FetchResult result = run();

        verify(importService).executeNoteImport(any(), any(ExternalIngestRequest.class));
        assertTrue(dlqReasons.isEmpty(), "an ordinary empty page was dead-lettered: " + dlqReasons);
        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(1, result.imported(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-01T00:00:00.000Z|page-1");
    }

    // ── R59: the listing is read newest first down to the checkpoint minute; the budget takes
    //    the oldest first; the checkpoint names a CLOSED minute and the pages done at it ─────

    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_SEARCH_BODY =
            new java.util.concurrent.atomic.AtomicReference<>("");

    private static String bodyOf(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Rows as {@code id@last_edited_time}, in the order given. */
    private static String rowsJson(String... idAtTime) {
        StringBuilder rows = new StringBuilder();
        for (String spec : idAtTime) {
            String id = spec.substring(0, spec.indexOf('@'));
            String at = spec.substring(spec.indexOf('@') + 1);
            if (rows.length() > 0) {
                rows.append(',');
            }
            rows.append("{\"id\":\"").append(id).append("\",\"url\":\"https://notion.so/")
                    .append(id).append("\",\"last_edited_time\":\"").append(at).append("\",")
                    .append("\"properties\":{\"title\":{\"type\":\"title\","
                            + "\"title\":[{\"plain_text\":\"T\"}]}},")
                    .append("\"parent\":{\"workspace\":true}}");
        }
        return rows.toString();
    }

    private static String pageOf(boolean hasMore, String... idAtTime) {
        return "{\"results\":[" + rowsJson(idAtTime) + "],\"has_more\":" + hasMore
                + (hasMore ? ",\"next_cursor\":\"next\"" : "") + "}";
    }

    /** A page that says there is more, with the cursor to read it. */
    private static String pageWithCursor(String cursor, String... idAtTime) {
        return "{\"results\":[" + rowsJson(idAtTime) + "],\"has_more\":true,"
                + "\"next_cursor\":\"" + cursor + "\"}";
    }

    private FetchResult runWithLimit(int limit) {
        return orchestrator().execute(null, profile(), connector(), Map.of(), limit);
    }

    private void checkpointIs(String stored) {
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString()))
                .thenReturn(stored);
    }

    /**
     * The search asks Notion for rows DESCENDING by {@code last_edited_time}.
     *
     * <p>Without an order, a listing cut short was an arbitrary sample and the checkpoint
     * raised from it silently excluded every page not shown whose edit time was older (R59).
     * Newest first is what lets the adapter stop at the checkpoint instead of taking a limit;
     * ascending with a limit returned the OLDEST pages of the workspace on every poll.
     */
    @Test
    @DisplayName("the search asks for rows newest first")
    void theSearchAsksForLastEditedOrder() {
        search = (exchange, n) -> {
            LAST_SEARCH_BODY.set(bodyOf(exchange));
            json(exchange, 200, onePage("page-1", "2026-01-01T00:00:00.000Z", false, null));
        };
        run();
        String body = LAST_SEARCH_BODY.get();
        assertTrue(body.contains("\"sort\""), "the search carries no sort: " + body);
        assertTrue(body.contains("\"timestamp\":\"last_edited_time\""), body);
        assertTrue(body.contains("\"direction\":\"descending\""), body);
    }

    /**
     * The listing stops at the first row edited before the checkpoint minute, and is whole.
     *
     * <p>Notion offers a cursor after the page; it is not followed, because every row after
     * the one at {@code 01-01} is older still. The row AT the checkpoint minute that the
     * checkpoint names is skipped; the one it does not name is imported.
     */
    @Test
    @DisplayName("the listing stops at the checkpoint minute and is whole without reading further")
    void theListingStopsAtTheCheckpointMinute() {
        search = (exchange, n) -> json(exchange, 200, pageWithCursor("c2",
                "p-4@2026-01-03T00:00:00.000Z", "p-3@2026-01-02T00:00:00.000Z",
                "p-2@2026-01-02T00:00:00.000Z", "p-1@2026-01-01T00:00:00.000Z"));
        NotionFetchOrchestrator orchestrator = orchestrator();
        checkpointIs("2026-01-02T00:00:00.000Z|p-2");

        FetchResult result = orchestrator.execute(null, profile(), connector(), Map.of(), 10);

        assertEquals(1, SEARCH_CALLS.get(), "the cursor past the checkpoint was followed");
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertEquals(List.of("p-3", "p-4"), importedIds);
        assertEquals(1, result.skipped(), "the page the checkpoint names was not skipped: " + result);
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-03T00:00:00.000Z|p-4");
    }

    /**
     * A budget that stops inside a minute's group names the pages it took, and the next poll
     * takes the rest and ADDS them to the same minute.
     */
    @Test
    @DisplayName("a budget that stops inside a minute names what it took; the next poll adds the rest")
    void aBudgetInsideAMinuteGroupIsResumedByTheNextPoll() {
        search = (exchange, n) -> json(exchange, 200, pageOf(false,
                "p-4@2026-01-02T00:00:00.000Z", "p-3@2026-01-02T00:00:00.000Z",
                "p-2@2026-01-02T00:00:00.000Z", "p-1@2026-01-01T00:00:00.000Z"));
        FetchResult first = runWithLimit(2);

        assertEquals(List.of("p-1", "p-2"), importedIds);
        assertEquals(1, first.incompleteReads().size(), first.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-02T00:00:00.000Z|p-2");

        NotionFetchOrchestrator second = orchestrator();
        checkpointIs("2026-01-02T00:00:00.000Z|p-2");
        search = (exchange, n) -> json(exchange, 200, pageOf(false,
                "p-4@2026-01-02T00:00:00.000Z", "p-3@2026-01-02T00:00:00.000Z",
                "p-2@2026-01-02T00:00:00.000Z", "p-1@2026-01-01T00:00:00.000Z"));
        FetchResult result = second.execute(null, profile(), connector(), Map.of(), 10);

        assertEquals(List.of("p-3", "p-4"), importedIds, "the rest of the minute was not taken");
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-02T00:00:00.000Z|p-2,p-3,p-4");
    }

    /**
     * A minute that has not closed is not named by the checkpoint.
     *
     * <p>Notion rounds an edit time DOWN to the minute and its search index is not immediate.
     * A page edited later in the same minute keeps the same {@code last_edited_time}; if the
     * checkpoint named that minute and the page, the later edit would never be seen. So the
     * page is imported, and the checkpoint waits for the minute to close.
     */
    @Test
    @DisplayName("a minute that has not closed is imported but not named by the checkpoint")
    void anOpenMinuteIsNotNamedByTheCheckpoint() {
        search = (exchange, n) -> json(exchange, 200, pageOf(false,
                "p-1@2026-09-23T12:00:00.000Z"));
        NotionFetchOrchestrator orchestrator = orchestrator();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-23T12:05:00Z"),
                java.time.ZoneOffset.UTC);

        FetchResult result = orchestrator.execute(null, profile(), connector(), Map.of(), 10);

        assertEquals(List.of("p-1"), importedIds, "the page in the open minute was not imported");
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** The other direction: once the minute and the index-lag allowance have passed, it is named. */
    @Test
    @DisplayName("a minute that closed within the lag allowance is named by the checkpoint")
    void aClosedMinuteIsNamedByTheCheckpoint() {
        search = (exchange, n) -> json(exchange, 200, pageOf(false,
                "p-1@2026-09-23T12:00:00.000Z"));
        NotionFetchOrchestrator orchestrator = orchestrator();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-23T12:11:00Z"),
                java.time.ZoneOffset.UTC);

        orchestrator.execute(null, profile(), connector(), Map.of(), 10);

        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-09-23T12:00:00.000Z|p-1");
    }

    /**
     * When Notion refuses the sort, the listing is read unordered; whole, it is imported in
     * edit order all the same — the connector does not stop.
     *
     * <p>This is the answer to why R59 sat open: an API parameter that could not be verified
     * against a live workspace might stop the connector if refused. Refused, it is retried
     * without, and a listing Notion ENDED is whole whatever order it came in.
     */
    @Test
    @DisplayName("a refused sort is retried without it, and a whole unordered listing is imported oldest first")
    void aRefusedSortIsRetriedWithoutIt() {
        search = (exchange, n) -> {
            String body = bodyOf(exchange);
            if (body.contains("\"sort\"")) {
                json(exchange, 400, "{\"object\":\"error\",\"status\":400,"
                        + "\"code\":\"validation_error\",\"message\":\"sort is not valid\"}");
                return;
            }
            json(exchange, 200, pageOf(false,
                    "p-2@2026-01-02T00:00:00.000Z", "p-3@2026-01-03T00:00:00.000Z",
                    "p-1@2026-01-01T00:00:00.000Z"));
        };
        FetchResult result = runWithLimit(10);

        assertEquals(2, SEARCH_CALLS.get(), "the refused sort was not retried without it");
        assertTrue(result.errors().isEmpty(), "the refused sort stopped the connector: "
                + result.errors());
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertEquals(List.of("p-1", "p-2", "p-3"), importedIds, "not imported oldest first");
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-03T00:00:00.000Z|p-3");
    }

    /**
     * An UNORDERED listing is read to Notion's end, not to the checkpoint: without an order, a
     * row older than the checkpoint says nothing about the rows after it.
     */
    @Test
    @DisplayName("an unordered listing is read to its end, not to the checkpoint")
    void anUnorderedListingIsReadToItsEndNotToTheCheckpoint() {
        search = (exchange, n) -> {
            if (bodyOf(exchange).contains("\"sort\"")) {
                json(exchange, 400, "{\"object\":\"error\",\"status\":400,"
                        + "\"code\":\"validation_error\",\"message\":\"sort is not valid\"}");
                return;
            }
            json(exchange, 200, pageOf(false,
                    "p-3@2026-01-03T00:00:00.000Z", "p-1@2026-01-01T00:00:00.000Z",
                    "p-4@2026-01-04T00:00:00.000Z"));
        };
        NotionFetchOrchestrator orchestrator = orchestrator();
        checkpointIs("2026-01-02T00:00:00.000Z");

        FetchResult result = orchestrator.execute(null, profile(), connector(), Map.of(), 10);

        assertEquals(List.of("p-3", "p-4"), importedIds,
                "the row after the one older than the checkpoint was not read");
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-04T00:00:00.000Z|p-4");
    }

    /**
     * An unordered listing that was cut short shows nothing about which pages were left out,
     * so nothing is imported and the checkpoint holds.
     */
    @Test
    @DisplayName("an unordered listing cut short imports nothing and holds the checkpoint")
    void anUnorderedListingCutShortHoldsTheCheckpoint() {
        search = (exchange, n) -> {
            if (bodyOf(exchange).contains("\"sort\"")) {
                json(exchange, 400, "{\"object\":\"error\",\"status\":400,"
                        + "\"code\":\"validation_error\",\"message\":\"sort is not valid\"}");
                return;
            }
            json(exchange, 200, pageWithCursor("c" + n, "p-" + n + "@2026-01-0" + n + "T00:00:00.000Z"));
        };
        FetchResult result = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_MAX_SEARCH_REQUESTS, "3"), 10);

        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("cap of 3"),
                result.incompleteReads().get(0));
        // The reason must not claim what an unordered listing cannot show.
        assertTrue(result.incompleteReads().get(0).contains("unordered"),
                result.incompleteReads().get(0));
        assertFalse(result.incompleteReads().get(0).contains("older than"),
                "an unordered listing claimed its unseen rows are older: " + result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), "pages were imported from a listing whose gaps are unknown: "
                + importedIds);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * An ORDERED listing cut short at the request cap imports nothing and holds too.
     *
     * <p>The rows it was not shown are older than the ones it was. A checkpoint raised over
     * them would exclude them from every later poll — R59 itself. The reason names the
     * parameter to raise.
     */
    @Test
    @DisplayName("a listing cut short at the request cap imports nothing, holds the checkpoint and names the cap")
    void aListingCutShortAtTheCapHoldsTheCheckpoint() {
        search = (exchange, n) -> json(exchange, 200,
                pageWithCursor("c" + n, "p-" + n + "@2026-01-0" + (9 - n) + "T00:00:00.000Z"));
        FetchResult result = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_MAX_SEARCH_REQUESTS, "2"), 10);

        assertEquals(2, SEARCH_CALLS.get(), "the cap parameter was not what bounded the listing");
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("notionSearchMaxRequests"),
                result.incompleteReads().get(0));
        assertTrue(result.incompleteReads().get(0).contains("older than"),
                result.incompleteReads().get(0));
        assertFalse(result.hasErrors(), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** A cap parameter that is not a number is reported, not replaced by the default. */
    @Test
    @DisplayName("a request-cap parameter that is not a number is reported and nothing is read")
    void aBadCapParameterIsReportedNotGuessed() {
        FetchResult result = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_MAX_SEARCH_REQUESTS, "fifty"), 10);

        assertTrue(result.hasErrors(), result.toString());
        assertTrue(result.errors().get(0).contains("notionSearchMaxRequests"), result.errors().get(0));
        assertEquals(0, SEARCH_CALLS.get(), "Notion was asked despite the unreadable parameter");
    }

    /**
     * Notion's own {@code request_status} — documented as {@code type: incomplete} with
     * {@code incomplete_reason} — is a cut, whatever {@code has_more} says. (An earlier lock
     * used field names Notion never writes, and the reader agreed with it.)
     */
    @Test
    @DisplayName("a result set Notion itself reports as incomplete is not a whole listing")
    void aSearchNotionReportsIncompleteIsCutShort() {
        search = (exchange, n) -> json(exchange, 200, "{\"results\":["
                + rowsJson("p-1@2026-01-01T00:00:00.000Z") + "],\"has_more\":false,"
                + "\"request_status\":{\"type\":\"incomplete\","
                + "\"incomplete_reason\":\"query_result_limit_reached\"}}");
        FetchResult result = runWithLimit(10);

        assertEquals(1, result.incompleteReads().size(), result.incompleteReads().toString());
        assertTrue(result.incompleteReads().get(0).contains("query_result_limit_reached"),
                result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** The over-refusal side: {@code type: complete} is not a cut. */
    @Test
    @DisplayName("a request_status of complete is not a cut")
    void aRequestStatusOfCompleteIsNotACut() {
        search = (exchange, n) -> json(exchange, 200, "{\"results\":["
                + rowsJson("p-1@2026-01-01T00:00:00.000Z") + "],\"has_more\":false,"
                + "\"request_status\":{\"type\":\"complete\"}}");
        FetchResult result = runWithLimit(10);

        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertEquals(List.of("p-1"), importedIds);
    }

    /**
     * A checkpoint reached INSIDE a result set Notion calls incomplete still makes the listing
     * whole: Notion cuts the tail of the ordered set, and the tail is older than the checkpoint.
     */
    @Test
    @DisplayName("the checkpoint reached inside an incomplete result set still makes the listing whole")
    void theCheckpointInsideAnIncompleteResultSetIsStillWhole() {
        search = (exchange, n) -> json(exchange, 200, "{\"results\":["
                + rowsJson("p-3@2026-01-03T00:00:00.000Z", "p-1@2026-01-01T00:00:00.000Z")
                + "],\"has_more\":false,"
                + "\"request_status\":{\"type\":\"incomplete\","
                + "\"incomplete_reason\":\"query_result_limit_reached\"}}");
        NotionFetchOrchestrator orchestrator = orchestrator();
        checkpointIs("2026-01-02T00:00:00.000Z");

        FetchResult result = orchestrator.execute(null, profile(), connector(), Map.of(), 10);

        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertEquals(List.of("p-3"), importedIds);
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-03T00:00:00.000Z|p-3");
    }

    /** A parameter past its bound is reported, not clamped and not used (an int overflow lived here). */
    @Test
    @DisplayName("an index-lag parameter past its bound is reported and nothing is read")
    void aLagParameterBeyondTheBoundIsReportedNotUsed() {
        FetchResult result = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_INDEX_LAG_MINUTES, "2147483647"), 10);

        assertTrue(result.hasErrors(), result.toString());
        assertTrue(result.errors().get(0).contains("notionIndexLagMinutes"), result.errors().get(0));
        assertEquals(0, SEARCH_CALLS.get(), "Notion was asked despite the out-of-range parameter");
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A row without {@code last_edited_time} is a malformed answer, not a page with no edit
     * time. Tolerated, it could sit below the row the ordered listing stops at and never be
     * read; and it could never be placed against the checkpoint.
     */
    @Test
    @DisplayName("a search row without last_edited_time is refused, not skipped")
    void aRowWithoutAnEditTimeIsRefused() {
        search = (exchange, n) -> json(exchange, 200, "{\"results\":["
                + rowsJson("p-3@2026-01-03T00:00:00.000Z") + ","
                + "{\"id\":\"p-x\",\"url\":\"https://notion.so/p-x\","
                + "\"properties\":{\"title\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"T\"}]}},"
                + "\"parent\":{\"workspace\":true}},"
                + rowsJson("p-1@2026-01-01T00:00:00.000Z") + "],\"has_more\":false}");
        NotionFetchOrchestrator orchestrator = orchestrator();
        checkpointIs("2026-01-02T00:00:00.000Z");

        FetchResult result = orchestrator.execute(null, profile(), connector(), Map.of(), 10);

        assertTrue(result.hasErrors(), "a page without an edit time was tolerated: " + result);
        assertTrue(result.errors().get(0).contains("last_edited_time"), result.errors().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** One hundred pages of one hundred rows: Notion's documented per-query limit, exactly. */
    private static void serveTenThousandRows(String lastPageExtra) {
        search = (exchange, n) -> {
            StringBuilder rows = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                if (i > 0) rows.append(',');
                rows.append(rowsJson("p-" + n + "-" + i + "@2026-01-01T00:00:00.000Z"));
            }
            boolean last = n >= 100;
            json(exchange, 200, "{\"results\":[" + rows + "],\"has_more\":" + !last
                    + (last ? lastPageExtra : ",\"next_cursor\":\"c" + (n + 1) + "\"") + "}");
        };
    }

    /**
     * Notion's documented limit of 10,000 results per query, reached with no
     * {@code request_status} in the response, is read as a cut — not as the whole listing.
     *
     * <p>This is the fail-closed answer to the part of R106 that could otherwise fail open:
     * if the real response does not carry the field this reader knows, {@code has_more=false}
     * at the cap would call the listing whole and the checkpoint would move over the tail.
     */
    @Test
    @DisplayName("Notion's documented result limit reached without a request_status is a cut, not a whole listing")
    void theDocumentedResultLimitWithoutARequestStatusIsACut() {
        serveTenThousandRows("");
        FetchResult result = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_MAX_SEARCH_REQUESTS, "200"), 10);

        assertEquals(100, SEARCH_CALLS.get(), "the stub did not serve the whole result set");
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("10000"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** The over-refusal side: the same 10,000 rows that Notion itself calls complete are whole. */
    @Test
    @DisplayName("ten thousand rows Notion calls complete are a whole listing")
    void theDocumentedResultLimitThatNotionCallsCompleteIsWhole() {
        serveTenThousandRows(",\"request_status\":{\"type\":\"complete\"}");
        FetchResult result = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_MAX_SEARCH_REQUESTS, "200"), 10);

        assertEquals(1, result.incompleteReads().size(), result.incompleteReads().toString());
        assertTrue(result.incompleteReads().get(0).contains("limit of 10"),
                "cut for a reason other than the run's budget: " + result.incompleteReads().get(0));
        assertEquals(10, importedIds.size(), importedIds.toString());
    }

    /** The retry without the sort is a request too; a cap of one allows one. */
    @Test
    @DisplayName("the retry without the sort counts towards the request cap")
    void theSortFallbackCountsTowardTheRequestCap() {
        search = (exchange, n) -> {
            if (bodyOf(exchange).contains("\"sort\"")) {
                json(exchange, 400, "{\"object\":\"error\",\"status\":400,"
                        + "\"code\":\"validation_error\",\"message\":\"sort is not valid\"}");
                return;
            }
            json(exchange, 200, pageOf(false, "p-1@2026-01-01T00:00:00.000Z"));
        };
        FetchResult result = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_MAX_SEARCH_REQUESTS, "1"), 10);

        assertEquals(1, SEARCH_CALLS.get(), "a cap of one request allowed a second");
        assertFalse(result.hasErrors(), result.errors().toString());
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("cap of 1"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
    }

    /**
     * A page that FAILED at the checkpoint minute is not named by the checkpoint.
     *
     * <p>Two pages in one minute; one's block listing answers 500 and is dead-lettered, the
     * other settles. The minute is named, with the settled page only — the failed one is
     * offered to the next poll again. Naming it would have called it done (review, P1).
     */
    @Test
    @DisplayName("a page that failed at the checkpoint minute is dead-lettered and not named by the checkpoint")
    void aPageThatFailedAtTheCheckpointMinuteIsNotNamedByIt() {
        search = (exchange, n) -> json(exchange, 200, pageOf(false,
                "p-2@2026-01-02T00:00:00.000Z", "p-1@2026-01-02T00:00:00.000Z"));
        blocks = (exchange, n) -> {
            if (exchange.getRequestURI().getPath().contains("/p-1/")) {
                json(exchange, 500, "{\"message\":\"boom\"}");
            } else {
                json(exchange, 200, "{\"results\":[],\"has_more\":false}");
            }
        };

        FetchResult result = runWithLimit(10);

        assertEquals(1, dlqReasons.size(), "the failed page was not dead-lettered: " + dlqReasons);
        assertTrue(dlqReasons.get(0).contains("p-1"), dlqReasons.get(0));
        assertEquals(List.of("p-2"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-02T00:00:00.000Z|p-2");
    }

    /**
     * Pages that fail on every poll do not starve the pages behind them.
     *
     * <p>Two failing pages at the head of the list and a budget of two: counting ATTEMPTS
     * would spend the whole budget on them, settle nothing, move no checkpoint, and never try
     * the good page behind them — on this poll or any later one (review, P1). The budget
     * counts settled pages, so the good page is reached, settles, and the checkpoint passes
     * the failing ones.
     */
    @Test
    @DisplayName("pages that fail on every poll do not starve the pages behind them")
    void aPageThatFailsOnEveryPollDoesNotStarveThePagesBehindIt() {
        search = (exchange, n) -> json(exchange, 200, pageOf(false,
                "p-good@2026-01-03T00:00:00.000Z", "p-bad2@2026-01-02T00:00:00.000Z",
                "p-bad1@2026-01-02T00:00:00.000Z"));
        blocks = (exchange, n) -> {
            if (exchange.getRequestURI().getPath().contains("/p-bad")) {
                json(exchange, 500, "{\"message\":\"boom\"}");
            } else {
                json(exchange, 200, "{\"results\":[],\"has_more\":false}");
            }
        };

        FetchResult result = runWithLimit(2);

        assertEquals(2, dlqReasons.size(), dlqReasons.toString());
        assertEquals(List.of("p-good"), importedIds, "the page behind the failing ones was never tried");
        assertTrue(result.sawEverything(), "nothing was left for the next poll: " + result.incompleteReads());
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-03T00:00:00.000Z|p-good");
    }

    /**
     * The other side of the failed-page contract: a page that failed at an OLDER minute than
     * the one a settled page moved the checkpoint to is passed — its dead-letter row is the
     * record — and the next poll does not offer it again. (The same-minute side is the lock
     * above; a review noted the contract had only one side measured.)
     */
    @Test
    @DisplayName("a page that failed at an older minute is passed by the checkpoint and not offered again")
    void aPageThatFailedAtAnOlderMinuteIsPassedByTheCheckpoint() {
        search = (exchange, n) -> json(exchange, 200, pageOf(false,
                "p-good@2026-01-02T00:00:00.000Z", "p-bad@2026-01-01T00:00:00.000Z"));
        blocks = (exchange, n) -> {
            if (exchange.getRequestURI().getPath().contains("/p-bad/")) {
                json(exchange, 500, "{\"message\":\"boom\"}");
            } else {
                json(exchange, 200, "{\"results\":[],\"has_more\":false}");
            }
        };
        runWithLimit(10);

        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertEquals(List.of("p-good"), importedIds);
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-02T00:00:00.000Z|p-good");

        // The next poll, with the sort REFUSED so the listing is read to its end and the failed
        // page reaches the orchestrator: the checkpoint must cover it. (Ordered, the adapter
        // stops before it, and this lock would be satisfied by that stop instead of by
        // `covers` — a control that removed the `covers` arm did not fire it.)
        search = (exchange, n) -> {
            if (bodyOf(exchange).contains("\"sort\"")) {
                json(exchange, 400, "{\"object\":\"error\",\"status\":400,"
                        + "\"code\":\"validation_error\",\"message\":\"sort is not valid\"}");
                return;
            }
            json(exchange, 200, pageOf(false,
                    "p-bad@2026-01-01T00:00:00.000Z", "p-good@2026-01-02T00:00:00.000Z"));
        };
        NotionFetchOrchestrator next = orchestrator();
        checkpointIs("2026-01-02T00:00:00.000Z|p-good");
        FetchResult result = next.execute(null, profile(), connector(), Map.of(), 10);

        assertTrue(dlqReasons.isEmpty(), "the passed page was attempted again: " + dlqReasons);
        assertTrue(importedIds.isEmpty(), "a page the checkpoint passed was offered again: " + importedIds);
        assertEquals(2, result.skipped(), "both rows should be covered by the checkpoint: " + result);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * The two parameter bounds are not each other's: 43,201 is past the lag bound and inside
     * the request bound. A single out-of-both-bounds value could not tell the constants apart
     * at the call site (review, P2).
     */
    @Test
    @DisplayName("the request-cap bound and the index-lag bound are not confused with each other")
    void theTwoParameterBoundsAreNotConfused() {
        FetchResult requests = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_MAX_SEARCH_REQUESTS, "43201"), 10);
        assertFalse(requests.hasErrors(), "43,201 requests is inside its bound: " + requests.errors());
        assertEquals(1, SEARCH_CALLS.get(), "Notion was not asked under an in-range request cap");

        FetchResult lag = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_INDEX_LAG_MINUTES, "43201"), 10);
        assertTrue(lag.hasErrors(), "43,201 minutes is past the lag bound: " + lag);
        assertTrue(lag.errors().get(0).contains("notionIndexLagMinutes"), lag.errors().get(0));

        FetchResult tooMany = orchestrator().execute(null, profile(), connector(),
                Map.of(NotionFetchOrchestrator.PARAM_MAX_SEARCH_REQUESTS, "1000001"), 10);
        assertTrue(tooMany.hasErrors(), "1,000,001 requests is past its bound: " + tooMany);
    }

    /**
     * A checkpoint written before R59 names a minute and no ids. Nothing at that minute is
     * known to be done, so the minute is imported once more, and the ids are recorded.
     */
    @Test
    @DisplayName("a checkpoint written before R59 imports its minute once more and then names the ids")
    void aLegacyCheckpointImportsItsMinuteOnceMore() {
        search = (exchange, n) -> json(exchange, 200, pageOf(false,
                "p-3@2026-01-02T00:00:00.000Z", "p-2@2026-01-02T00:00:00.000Z",
                "p-1@2026-01-01T00:00:00.000Z"));
        NotionFetchOrchestrator orchestrator = orchestrator();
        checkpointIs("2026-01-02T00:00:00.000Z");

        FetchResult result = orchestrator.execute(null, profile(), connector(), Map.of(), 10);

        assertEquals(List.of("p-2", "p-3"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-notion", "notion",
                "2026-01-02T00:00:00.000Z|p-2,p-3");
    }

}
