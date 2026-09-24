package jp.aegif.nemaki.rest.ingest.chat;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A Mattermost channel is read down to the checkpoint and the OLDEST new posts are taken first (R107).
 *
 * <p>Mattermost lists newest first and offers no filter on the creation time. The previous
 * orchestrator asked for the first {@code limit} posts — the {@code limit} NEWEST — and raised the
 * checkpoint to the newest it had seen, so in a burst every older new post fell below the
 * checkpoint for ever. What is measured here is the REAL adapter pointed at a local stub of the
 * Mattermost API — its paging by {@code before} cursors, its stop at the checkpoint and its refusals
 * — and the orchestrator's budget, dead-letter answers and checkpoint. Pages are 200 posts, the
 * size the real adapter asks for and the server clamps to, so a "full page" here is a full page.
 */
class MattermostChannelsAreReadToTheCheckpointTest {

    private static HttpServer server;
    private static String base;
    private static String previousAllowLocalhost;

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, int callNumber) throws IOException;
    }

    /** What the channel posts endpoint answers; set per test. */
    private static volatile Responder posts;
    /** File ids whose download answers 500. */
    private static volatile List<String> failingDownloads = List.of();
    /** File ids whose info call answers 500. */
    private static volatile List<String> failingInfos = List.of();
    private static final AtomicInteger POST_CALLS = new AtomicInteger();
    private static final AtomicInteger DOWNLOAD_CALLS = new AtomicInteger();
    private static final List<String> POST_QUERIES = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 2023-11-14T22:13:20Z; post k is created k seconds after it. */
    static final long BASE = 1_700_000_000_000L;

    @BeforeAll
    static void startStub() throws Exception {
        previousAllowLocalhost = System.getProperty("nemaki.ingest.allowLocalhost");
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/channels/C1/posts", exchange -> {
            POST_QUERIES.add(String.valueOf(exchange.getRequestURI().getQuery()));
            posts.respond(exchange, POST_CALLS.incrementAndGet());
        });
        server.createContext("/api/v4/files/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (path.endsWith("/info")) {
                String id = parts[parts.length - 2];
                if (failingInfos.contains(id)) {
                    json(exchange, 500, "{\"message\":\"info boom\"}");
                } else {
                    json(exchange, 200, "{\"id\":\"" + id + "\",\"name\":\"" + id + ".txt\",\"mime_type\":\"text/plain\",\"size\":3}");
                }
                return;
            }
            String id = parts[parts.length - 1];
            DOWNLOAD_CALLS.incrementAndGet();
            if (failingDownloads.contains(id)) {
                json(exchange, 500, "{\"message\":\"boom\"}");
            } else {
                byte[] out = ("content of " + id).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
        if (previousAllowLocalhost == null) {
            System.clearProperty("nemaki.ingest.allowLocalhost");
        } else {
            System.setProperty("nemaki.ingest.allowLocalhost", previousAllowLocalhost);
        }
    }

    @BeforeEach
    void reset() {
        POST_CALLS.set(0);
        DOWNLOAD_CALLS.set(0);
        POST_QUERIES.clear();
        failingDownloads = List.of();
        failingInfos = List.of();
        dlqWritable = true;
        failingImports = List.of();
        throwingImports = List.of();
        posts = (exchange, n) -> json(exchange, 200, page(post("p1", 1)));
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Retry-After", "0");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    /** One post of a page: its id, its creation time and its file ids. */
    record P(String id, long createAt, List<String> files) {}

    /** Post {@code id} created {@code k} seconds after {@link #BASE}. */
    private static P post(String id, long k) {
        return new P(id, BASE + k * 1000, List.of());
    }

    private static P postWithFile(String id, long k, String fileId) {
        return new P(id, BASE + k * 1000, List.of(fileId));
    }

    /** {@code count} posts p{firstK}, p{firstK-1}, … newest first — one per second. */
    private static List<P> descending(int firstK, int count) {
        List<P> out = new ArrayList<>();
        for (int k = firstK; k > firstK - count; k--) out.add(post("p" + k, k));
        return out;
    }

    private static String postJson(P p) {
        StringBuilder files = new StringBuilder();
        for (String f : p.files()) {
            if (files.length() > 0) files.append(',');
            files.append('"').append(f).append('"');
        }
        return "\"" + p.id() + "\":{\"id\":\"" + p.id() + "\",\"message\":\"hello " + p.id() + "\",\"user_id\":\"U1\",\"create_at\":"
                + p.createAt() + ",\"root_id\":\"\",\"file_ids\":[" + files + "]}";
    }

    /** A page as Mattermost writes it: {@code order} (newest first) and the {@code posts} map. */
    private static String page(List<P> list) {
        StringBuilder order = new StringBuilder();
        StringBuilder map = new StringBuilder();
        for (P p : list) {
            if (order.length() > 0) { order.append(','); map.append(','); }
            order.append('"').append(p.id()).append('"');
            map.append(postJson(p));
        }
        return "{\"order\":[" + order + "],\"posts\":{" + map + "},\"next_post_id\":\"\",\"prev_post_id\":\"\"}";
    }

    private static String page(P... list) {
        return page(List.of(list));
    }

    private static List<P> concat(List<P> a, List<P> b) {
        List<P> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String queryValue(String query, String name) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        return null;
    }

    /** The canonical form of the creation time of post k: what the checkpoint stores. */
    private static String at(long k) {
        return jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.canonical(java.time.Instant.ofEpochMilli(BASE + k * 1000));
    }

    // ── the orchestrator under test ────────────────────────────────

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService importService;
    private final List<String> importedIds = new ArrayList<>();
    private final List<String> dlqReasons = new ArrayList<>();
    private final List<String> dlqReadReasons = new ArrayList<>();
    private final List<ExternalIngestRequest> dlqRequests = new ArrayList<>();
    private List<String> throwingImports = List.of();
    private boolean dlqWritable = true;
    private List<String> failingImports = List.of();

    private MattermostFetchOrchestrator mattermost() {
        MattermostFetchOrchestrator orchestrator = new MattermostFetchOrchestrator();
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        importedIds.clear();
        dlqReasons.clear();
        dlqReadReasons.clear();
        dlqRequests.clear();
        lenient().when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("mm-token");
        lenient().doNothing().when(fetchSupport).throttle(anyLong());
        lenient().doAnswer(call -> {
            dlqRequests.add(call.getArgument(0));
            dlqReasons.add(call.getArgument(1));
            return dlqWritable;
        }).when(fetchSupport).saveSourceNeverReadToDlq(any(), anyString());
        lenient().doAnswer(call -> {
            dlqRequests.add(call.getArgument(0));
            dlqReasons.add(call.getArgument(1));
            dlqReadReasons.add(call.getArgument(1));
            return dlqWritable;
        }).when(fetchSupport).saveSourceReadToDlq(any(), anyString());
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(null);
        lenient().when(importService.executeChatContextImport(any(), any())).thenAnswer(call -> {
            ExternalIngestRequest req = call.getArgument(1);
            if (failingImports.contains(req.getSourceObjectId())) {
                return ExternalIngestResult.error("r", "refused by the import service");
            }
            if (throwingImports.contains(req.getSourceObjectId())) {
                throw new RuntimeException("the import service threw after reading the content");
            }
            importedIds.add(req.getSourceObjectId());
            return new ExternalIngestResult("r", "obj-" + req.getSourceObjectId(), "1.0", false, false, false, null, null,
                    List.of(), List.of());
        });
        orchestrator.setFetchSupport(fetchSupport);
        orchestrator.setCheckpointManager(checkpointManager);
        orchestrator.setCanonicalImportService(importService);
        // the REAL adapter, at the connector's endpoint — the stub
        orchestrator.adapterFactory = (endpoint, token) -> new MattermostConnectorAdapter(endpoint, token);
        return orchestrator;
    }

    private void checkpointIs(String stored) {
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(stored);
    }

    private static ImportProfileDefinition profile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-mm");
        profile.setRepositoryId("bedroom");
        return profile;
    }

    private static ConnectorDefinition connector() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-mm");
        connector.setSourceSystem("mattermost");
        connector.setEndpoint(base);
        connector.setTenantId("T1");
        return connector;
    }

    private static final Map<String, String> CHANNEL = Map.of("channelId", "C1");
    private static final String KEY = "mattermost.C1";

    // ── the listing ────────────────────────────────────────────────

    /**
     * 205 new posts over two pages, newest first as Mattermost lists them; a budget of 2 takes the
     * two OLDEST and leaves the newer 203 for the next poll. The old shape took the newest two and
     * passed the other 203 for ever.
     */
    @Test
    @DisplayName("Mattermost: the channel is read across pages and the oldest new posts are taken first")
    void mattermostListsTheChannelAndTakesTheOldestFirst() {
        posts = (exchange, n) -> {
            if (n == 1) json(exchange, 200, page(descending(300, 200)));
            else json(exchange, 200, page(descending(100, 5)));
        };
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 2);

        assertEquals(2, POST_CALLS.get(), "the second page was not asked for");
        assertEquals(205, result.fetched(), result.toString());
        assertEquals(List.of("p96", "p97"), importedIds, "the budget did not take the oldest posts");
        assertFalse(result.sawEverything(), "203 posts were left for the next poll: " + result);
        assertTrue(result.incompleteReads().get(0).contains("left for the next poll"), result.incompleteReads().get(0));
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-mm", KEY, at(97) + "|p97");
    }

    /**
     * {@code before} is strict, so the next page is asked before the last post created strictly
     * after the page's oldest creation time — here p104, above three posts created at one time —
     * and a fourth post at that time, which the page boundary cut off, is listed by the next page
     * and imported. Asked before the page's last post (t3), that fourth post would be lost.
     */
    @Test
    @DisplayName("Mattermost: the next page is asked before the post above the page's oldest creation time, so a same-time post cut by the page boundary is not lost")
    void mattermostTheNextPageIsAskedBeforeThePostAboveTheOldestTime() {
        List<P> ties = List.of(new P("t1", BASE + 100_000, List.of()), new P("t2", BASE + 100_000, List.of()), new P("t3", BASE + 100_000, List.of()));
        List<P> firstPage = concat(descending(300, 197), ties); // 197 + 3 = 200: full
        posts = (exchange, n) -> {
            if (n == 1) {
                json(exchange, 200, page(firstPage));
            } else if ("p104".equals(queryValue(exchange.getRequestURI().getQuery(), "before"))) {
                json(exchange, 200, page(concat(concat(ties, List.of(new P("t4", BASE + 100_000, List.of()))), descending(99, 10))));
            } else {
                json(exchange, 200, page(descending(99, 10)));
            }
        };
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 300);

        assertEquals(2, POST_CALLS.get(), POST_QUERIES.toString());
        assertEquals("p104", queryValue(POST_QUERIES.get(1), "before"), "the next page was not asked before the post above the tie: " + POST_QUERIES.get(1));
        assertTrue(importedIds.contains("t4"), "the same-time post on the next page was lost: " + importedIds);
        assertTrue(importedIds.indexOf("p90") < importedIds.indexOf("t4") && importedIds.indexOf("t4") < importedIds.indexOf("p104"), importedIds.toString());
        assertEquals(211, importedIds.size(), importedIds.toString());
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertFalse(result.hasErrors(), result.errors().toString());
    }

    /** The listing STOPS at the page holding a post created before the checkpoint — the older pages are never asked for. */
    @Test
    @DisplayName("Mattermost: the listing stops at the checkpoint's page and does not ask for the older ones")
    void mattermostTheListingStopsAtTheCheckpointPage() {
        posts = (exchange, n) -> {
            if (n == 1) json(exchange, 200, page(descending(300, 200)));
            else if (n == 2) json(exchange, 200, page(descending(100, 200)));
            else json(exchange, 200, page(descending(-100, 200)));
        };
        MattermostFetchOrchestrator orchestrator = mattermost();
        // the checkpoint is p50, in the middle of page 2: p49 (older) stops the listing there
        checkpointIs(at(50) + "|p50");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 300);

        assertEquals(2, POST_CALLS.get(), "the page below the checkpoint was asked for");
        assertEquals(250, importedIds.size(), importedIds.toString());
        assertEquals("p51", importedIds.get(0), importedIds.toString());
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-mm", KEY, at(300) + "|p300");
    }

    /**
     * The checkpoint's own post at the END of a page: the next page is read too — a post at the
     * checkpoint's own time can continue on it (p101b), and only a strictly older post (p100)
     * says the span is done.
     */
    @Test
    @DisplayName("Mattermost: a checkpoint at the end of a page reads the next page — a same-time post on it is not lost")
    void mattermostACheckpointAtThePageEndReadsTheNextPage() {
        posts = (exchange, n) -> {
            if (n == 1) json(exchange, 200, page(descending(300, 200)));
            else json(exchange, 200, page(post("p101", 101), post("p101b", 101), post("p100", 100)));
        };
        MattermostFetchOrchestrator orchestrator = mattermost();
        checkpointIs(at(101) + "|p101");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 300);

        assertEquals(2, POST_CALLS.get(), "the page after the checkpoint's is read once, and no further");
        assertEquals("p101b", importedIds.get(0), "the same-time post on the next page was lost: " + importedIds);
        assertEquals(200, importedIds.size(), importedIds.toString());
        assertFalse(importedIds.contains("p101") || importedIds.contains("p100"), importedIds.toString());
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-mm", KEY, at(300) + "|p300");
    }

    /** A post AT the checkpoint's time that the checkpoint names is skipped; one it does not name is taken. */
    @Test
    @DisplayName("Mattermost: at the checkpoint's creation time only the ids it names are skipped")
    void mattermostAtTheCheckpointTimestampOnlyTheNamedIdsAreSkipped() {
        posts = (exchange, n) -> json(exchange, 200, page(post("p3", 3), post("p2b", 2), post("p2", 2)));
        MattermostFetchOrchestrator orchestrator = mattermost();
        checkpointIs(at(2) + "|p2");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(List.of("p2b", "p3"), importedIds, "a post at the checkpoint time that it does not name was skipped: " + importedIds);
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-mm", KEY, at(3) + "|p3");
    }

    @Test
    @DisplayName("Mattermost: a listing cut at the request cap imports nothing, holds the checkpoint and names the cap")
    void mattermostAListingCutAtTheCapImportsNothing() {
        posts = (exchange, n) -> json(exchange, 200, page(descending(1000 - (n - 1) * 200, 200)));
        FetchResult result = mattermost().execute(null, profile(), connector(),
                Map.of("channelId", "C1", MattermostFetchOrchestrator.PARAM_MAX_POST_REQUESTS, "2"), 10);

        assertEquals(2, POST_CALLS.get());
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("mattermostPostMaxRequests"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A server that answers the same full page again for {@code before} is out of order — the
     * page's first post is newer than the last post read — and the order check refuses it on the
     * request that repeated it; the listing is not walked to the cap.
     */
    @Test
    @DisplayName("Mattermost: a repeated page is refused as out of order on the request that repeated it — nothing imported, checkpoint held")
    void mattermostARepeatedPageIsRefusedNotWalkedToTheCap() {
        posts = (exchange, n) -> json(exchange, 200, page(descending(300, 200)));
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(2, POST_CALLS.get(), "the repeated page was not recognised on the request that repeated it: " + POST_QUERIES);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("not listed newest first")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** A full page of posts all created at one time cannot be walked past by a cursor on the creation time: a cut, said so. */
    @Test
    @DisplayName("Mattermost: a full page of posts all created at one time is a cut, not read as the end")
    void mattermostAFullPageOfOneCreationTimeIsACut() {
        List<P> ties = new ArrayList<>();
        for (int i = 1; i <= 200; i++) ties.add(new P("q" + i, BASE + 5000, List.of()));
        posts = (exchange, n) -> json(exchange, 200, page(ties));
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, POST_CALLS.get(), POST_QUERIES.toString());
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("all created at"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Mattermost: a page without an order array is refused, not read as an empty channel")
    void mattermostAPageWithoutOrderIsRefused() {
        posts = (exchange, n) -> json(exchange, 200, "{\"posts\":{}}");
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.hasErrors(), "a malformed page was read as a channel: " + result);
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Mattermost: an order naming a post the page does not carry is refused, not read around")
    void mattermostAnOrderNamingAPostThePageDoesNotCarryIsRefused() {
        posts = (exchange, n) -> json(exchange, 200, "{\"order\":[\"p9\",\"p1\"],\"posts\":{" + postJson(post("p1", 1)) + "}}");
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("p9")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Mattermost: a post without a creation time refuses the listing")
    void mattermostAPostWithoutACreationTimeRefusesTheListing() {
        posts = (exchange, n) -> json(exchange, 200, "{\"order\":[\"p-x\",\"p1\"],\"posts\":{\"p-x\":{\"id\":\"p-x\",\"message\":\"no time\",\"user_id\":\"U1\"},"
                + postJson(post("p1", 1)) + "}}");
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("p-x") && e.contains("creation time")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Mattermost: a post without an id refuses the listing — it can neither be skipped nor named")
    void mattermostAPostWithoutAnIdRefusesTheListing() {
        posts = (exchange, n) -> json(exchange, 200, "{\"order\":[\"p1\"],\"posts\":{\"p1\":{\"message\":\"no id\",\"user_id\":\"U1\",\"create_at\":" + (BASE + 1000) + "}}}");
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without an id")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Mattermost: a listing that is not newest first is refused — the stop at the checkpoint rests on the order")
    void mattermostAListingNotNewestFirstIsRefused() {
        // p5 comes AFTER p3 — newer than the one before it — while the listing is still above the checkpoint
        posts = (exchange, n) -> json(exchange, 200, page(post("p3", 3), post("p5", 5), post("p4", 4)));
        MattermostFetchOrchestrator orchestrator = mattermost();
        checkpointIs(at(2) + "|p2");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("not listed newest first")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), "a listing that is not newest first was trusted: " + importedIds);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    // ── failures and the checkpoint ────────────────────────────────

    /**
     * An attachment whose download fails is dead-lettered as never read, naming the file id the
     * DLQ controller fetches it again by; the post is not named, and a newer post that settles
     * moves the checkpoint past it — the row being the record.
     */
    @Test
    @DisplayName("Mattermost: a failed download is dead-lettered as never read with its file id; the post is not named and the newer one moves the checkpoint")
    void mattermostAFailedDownloadIsDeadLetteredNeverReadWithItsFileId() {
        failingDownloads = List.of("F-bad");
        posts = (exchange, n) -> json(exchange, 200, page(post("p2", 2), postWithFile("p1", 1, "F-bad")));
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertTrue(dlqReasons.get(0).contains("F-bad") && dlqReadReasons.isEmpty(), "expected a never-read row: " + dlqReasons);
        assertEquals("F-bad", dlqRequests.get(0).getSourceObjectId(), "the row must name the file id it is fetched again by");
        assertEquals("attachment", dlqRequests.get(0).getSourceObjectType());
        assertEquals("F-bad.txt", dlqRequests.get(0).getFileName(), "the name the info call answered");
        assertTrue(importedIds.contains("p2") && importedIds.contains("p1"),
                "the body of the failed post and the newer post should still be imported: " + importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-mm", KEY, at(2) + "|p2");
    }

    /** The info call is the first read of the file; when it fails the item was never read — a never-read row, and no download is tried. */
    @Test
    @DisplayName("Mattermost: a failed file-info call is dead-lettered as never read, named by the file id")
    void mattermostAFailedFileInfoIsDeadLetteredNeverRead() {
        failingInfos = List.of("F-noinfo");
        posts = (exchange, n) -> json(exchange, 200, page(post("p2", 2), postWithFile("p1", 1, "F-noinfo")));
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertTrue(dlqReasons.get(0).contains("F-noinfo") && dlqReadReasons.isEmpty(), "expected a never-read row: " + dlqReasons);
        assertEquals("F-noinfo", dlqRequests.get(0).getSourceObjectId());
        assertEquals("F-noinfo", dlqRequests.get(0).getFileName(), "without the info the row is named by the id");
        assertEquals(0, DOWNLOAD_CALLS.get(), "the bytes were asked for although the info call failed");
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-mm", KEY, at(2) + "|p2");
    }

    @Test
    @DisplayName("Mattermost: an import that answers an error is dead-lettered as read and the post is not named")
    void mattermostAnImportThatAnswersAnErrorIsDeadLetteredAsRead() {
        failingImports = List.of("p2");
        posts = (exchange, n) -> json(exchange, 200, page(post("p2", 2), post("p1", 1)));
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReadReasons.size(), "the refused import was not dead-lettered as read: " + dlqReasons);
        assertEquals(List.of("p1"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-mm", KEY, at(1) + "|p1");
    }

    @Test
    @DisplayName("Mattermost: an import that throws after the read is dead-lettered as read, not never-read")
    void mattermostAnImportThatThrowsIsDeadLetteredAsRead() {
        throwingImports = List.of("p1");
        posts = (exchange, n) -> json(exchange, 200, page(post("p2", 2), post("p1", 1)));
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReadReasons.size(), "the thrown import was not recorded as a READ item: " + dlqReasons);
        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertEquals(List.of("p2"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-mm", KEY, at(2) + "|p2");
    }

    @Test
    @DisplayName("Mattermost: a failure that could not be dead-lettered holds the checkpoint and is reported")
    void mattermostAnUnrecordedFailureHoldsTheCheckpoint() {
        dlqWritable = false;
        failingDownloads = List.of("F-bad");
        posts = (exchange, n) -> json(exchange, 200, page(post("p2", 2), postWithFile("p1", 1, "F-bad")));
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** With exactly limit × 4 failures ahead, the good post behind them is left untried this run — said so, checkpoint held. */
    @Test
    @DisplayName("Mattermost: the attempts of one run are bounded by four times the limit")
    void mattermostTheAttemptsOfOneRunAreBoundedByFourTimesTheLimit() {
        failingImports = List.of("p1", "p2", "p3", "p4");
        posts = (exchange, n) -> json(exchange, 200, page(post("p5", 5), post("p4", 4), post("p3", 3), post("p2", 2), post("p1", 1)));
        FetchResult result = mattermost().execute(null, profile(), connector(), CHANNEL, 1);

        assertEquals(4, dlqReadReasons.size(), dlqReasons.toString());
        assertTrue(importedIds.isEmpty(), "the post behind four failures was tried beyond the bound: " + importedIds);
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("4 × the limit of 1")), result.incompleteReads().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Mattermost: a request-cap parameter that is not a number is reported and nothing is read")
    void mattermostABadCapParameterIsReported() {
        FetchResult result = mattermost().execute(null, profile(), connector(),
                Map.of("channelId", "C1", MattermostFetchOrchestrator.PARAM_MAX_POST_REQUESTS, "fifty"), 10);

        assertTrue(result.hasErrors(), result.toString());
        assertTrue(result.errors().get(0).contains("mattermostPostMaxRequests"), result.errors().get(0));
        assertEquals(0, POST_CALLS.get());
    }

    @Test
    @DisplayName("Mattermost: a stored checkpoint that cannot be read is an error, not 'no checkpoint'")
    void mattermostAStoredCheckpointThatCannotBeReadIsAnError() {
        MattermostFetchOrchestrator orchestrator = mattermost();
        checkpointIs("last tuesday");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("last tuesday") && e.contains("not a creation time")), result.errors().toString());
        assertEquals(0, POST_CALLS.get(), "the channel was listed although the checkpoint could not be read");
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A legacy checkpoint (the newest create_at seen, Unix milliseconds, no ids) names nothing at
     * its time, so a post AT that time is offered once more — the import service's dedupe answers
     * for it — and is then named in the canonical form; a post older than it is not offered.
     */
    @Test
    @DisplayName("Mattermost: a legacy checkpoint (Unix milliseconds) offers the posts at its own time once, then names them")
    void mattermostALegacyCheckpointOffersItsOwnTimeOnceThenNamesIt() {
        posts = (exchange, n) -> json(exchange, 200, page(post("p1", 1), post("p0", 0)));
        MattermostFetchOrchestrator orchestrator = mattermost();
        checkpointIs("1700000001000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(List.of("p1"), importedIds, "the post at the legacy checkpoint's time is offered once; older ones are not: " + importedIds);
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-mm", KEY, "2023-11-14T22:13:21.000000000Z|p1");
    }
}
