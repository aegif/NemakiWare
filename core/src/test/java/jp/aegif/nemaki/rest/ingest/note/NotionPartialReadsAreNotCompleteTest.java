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
        lenient().when(importService.executeNoteImport(any(), any())).thenReturn(
                new ExternalIngestResult("r", "obj-1", "1.0", false, false, false, null, null,
                        List.of(), List.of()));

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
    @DisplayName("a page listing cut short at the limit is not a complete run")
    void aTruncatedListingIsNotComplete() {
        // Notion has more pages and this poll stopped at the caller's limit. Nothing failed —
        // and that is exactly why it used to be indistinguishable from having seen everything.
        search = (exchange, n) -> json(exchange, 200,
                onePage("page-1", "2026-01-01T00:00:00.000Z", true, "cursor-2"));

        FetchResult result = orchestrator().execute(null, profile(), connector(), Map.of(), 1);

        assertFalse(result.sawEverything(),
                "the run says it saw the whole workspace, and Notion said there was more: "
                        + result);
        assertEquals(1, result.incompleteReads().size(), result.incompleteReads().toString());
        assertTrue(result.incompleteReads().get(0).contains("limit"),
                result.incompleteReads().get(0));
        assertFalse(result.hasErrors(),
                "a listing that stopped at the caller's limit is not a FAILURE — reporting it as "
                        + "one makes the scheduler count a healthy workspace towards the "
                        + "connector's circuit breaker: " + result.errors());
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
                "2026-01-01T00:00:00.000Z");
    }
}
