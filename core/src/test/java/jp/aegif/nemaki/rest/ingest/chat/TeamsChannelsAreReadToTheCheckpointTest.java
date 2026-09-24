package jp.aegif.nemaki.rest.ingest.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A Teams channel is followed through Graph's delta feed, page by page (R107).
 *
 * <p>Graph sorts the channel listing by the last modified time of the whole reply chain, not by
 * the creation time, and has no filter on it — so no creation-time checkpoint over that listing
 * holds: the shapes this orchestrator had took the newest {@code limit} and passed the rest, then
 * refused every channel with a live thread, then could lose a message whose thread rose above the
 * page cursor during a listing. What is measured here is the REAL adapter pointed at a local stub
 * of Graph — the delta feed and its links — and the orchestrator's start, page-by-page progress,
 * budget, dead-letter answers and the checkpoint it saves. The channel listing is never read.
 */
class TeamsChannelsAreReadToTheCheckpointTest {

    private static HttpServer server;
    private static String base;
    private static String previousAllowLocalhost;

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, int callNumber) throws IOException;
    }

    /** What the delta feed answers; set per test. */
    private static volatile Responder deltaPages;
    private static volatile List<String> failingDownloads = List.of();
    /** Calls to the channel LISTING — the orchestrator must not use it. */
    private static final AtomicInteger MESSAGE_CALLS = new AtomicInteger();
    private static final AtomicInteger DELTA_CALLS = new AtomicInteger();
    /** The delta links asked for, as full URLs, in order. */
    private static final List<String> DELTA_LINKS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The C1 feed in the OData key syntax, as Graph may write it in the links it returns (its mail delta links do). */
    private static final String KEY_SYNTAX_FEED = "/v1.0/teams('T1')/channels('C1')/messages/delta()";

    /** A channel id as Graph writes it: a colon and an at sign, which Graph leaves raw in the links it returns. */
    private static final String RAW_CHANNEL = "19:abc@thread.tacv2";

    @BeforeAll
    static void startStub() throws Exception {
        previousAllowLocalhost = System.getProperty("nemaki.ingest.allowLocalhost");
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String feed : List.of("/v1.0/teams/T1/channels/C1/messages/delta", "/v1.0/teams/T1/channels/" + RAW_CHANNEL + "/messages/delta",
                KEY_SYNTAX_FEED)) {
            server.createContext(feed, exchange -> {
                DELTA_LINKS.add(base + exchange.getRequestURI().toString());
                deltaPages.respond(exchange, DELTA_CALLS.incrementAndGet());
            });
        }
        server.createContext("/v1.0/teams/T1/channels/C1/messages", exchange -> {
            MESSAGE_CALLS.incrementAndGet();
            json(exchange, 200, "{\"value\":[]}");
        });
        server.createContext("/files/", exchange -> {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            String id = parts[parts.length - 1];
            if (failingDownloads.contains(id)) {
                json(exchange, 500, "{\"error\":\"boom\"}");
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
        MESSAGE_CALLS.set(0);
        DELTA_CALLS.set(0);
        DELTA_LINKS.clear();
        failingDownloads = List.of();
        dlqWritable = true;
        failingImports = List.of();
        throwingImports = List.of();
        skippingImports = List.of();
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t-end"));
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Retry-After", "0");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static String msg(String id, String created) {
        return "{\"id\":\"" + id + "\",\"createdDateTime\":\"" + created + "\",\"body\":{\"content\":\"hello " + id + "\"},"
                + "\"from\":{\"user\":{\"displayName\":\"u\"}}}";
    }

    private static String msgWithFile(String id, String created, String fileId) {
        return "{\"id\":\"" + id + "\",\"createdDateTime\":\"" + created + "\",\"body\":{\"content\":\"see file\"},"
                + "\"from\":{\"user\":{\"displayName\":\"u\"}},\"attachments\":[{\"id\":\"" + fileId + "\",\"name\":\"" + fileId
                + ".txt\",\"contentType\":\"file\",\"contentUrl\":\"" + base + "/files/" + fileId + "\"}]}";
    }

    /** A delta link on the stub; {@code kind} is "skip" (a nextLink) or "delta" (a deltaLink). */
    private static String deltaLink(String kind, String token) {
        return deltaLink("C1", kind, token);
    }

    private static String deltaLink(String channel, String kind, String token) {
        return base + "/v1.0/teams/T1/channels/" + channel + "/messages/delta?$" + kind + "token=" + token;
    }

    /** A page of the feed; {@code kind} "skip" carries a nextLink, "delta" a deltaLink (the round is complete). */
    private static String deltaPage(String kind, String token, String... msgs) {
        return deltaPageLinking(deltaLink(kind, token), "skip".equals(kind), msgs);
    }

    private static String deltaPageLinking(String link, boolean next, String... msgs) {
        return "{\"value\":[" + String.join(",", msgs) + "],\"@odata." + (next ? "nextLink" : "deltaLink") + "\":\"" + link + "\"}";
    }

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService importService;
    private final List<String> importedIds = new ArrayList<>();
    private final List<String> dlqReasons = new ArrayList<>();
    private final List<String> dlqReadReasons = new ArrayList<>();
    private final List<ExternalIngestRequest> dlqRequests = new ArrayList<>();
    /** The bytes a read row was written with, by source object id (null: metadata-only). */
    private final Map<String, byte[]> dlqBodies = new java.util.HashMap<>();
    private boolean dlqWritable = true;
    private List<String> failingImports = List.of();
    private List<String> throwingImports = List.of();
    /** Ids the import service answers "skipped" for — already imported. */
    private List<String> skippingImports = List.of();

    private TeamsFetchOrchestrator teams() {
        TeamsFetchOrchestrator orchestrator = new TeamsFetchOrchestrator();
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        importedIds.clear();
        dlqReasons.clear();
        dlqReadReasons.clear();
        dlqRequests.clear();
        dlqBodies.clear();
        lenient().when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("graph-token");
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
        lenient().doAnswer(call -> {
            ExternalIngestRequest r = call.getArgument(0);
            dlqRequests.add(r);
            dlqReasons.add(call.getArgument(1));
            dlqReadReasons.add(call.getArgument(1));
            dlqBodies.put(r.getSourceObjectId(), call.getArgument(2));
            return dlqWritable;
        }).when(fetchSupport).saveSourceReadToDlq(any(), anyString(), any());
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(null);
        lenient().when(importService.executeChatContextImport(any(), any())).thenAnswer(call -> {
            ExternalIngestRequest req = call.getArgument(1);
            if (failingImports.contains(req.getSourceObjectId())) {
                return ExternalIngestResult.error("r", "refused by the import service");
            }
            if (throwingImports.contains(req.getSourceObjectId())) {
                throw new RuntimeException("the import service threw after reading the content");
            }
            if (skippingImports.contains(req.getSourceObjectId())) {
                return ExternalIngestResult.skipped("r", "obj-" + req.getSourceObjectId(), "already imported");
            }
            importedIds.add(req.getSourceObjectId());
            return new ExternalIngestResult("r", "obj-" + req.getSourceObjectId(), "1.0", false, false, false, null, null,
                    List.of(), List.of());
        });
        orchestrator.setFetchSupport(fetchSupport);
        orchestrator.setCheckpointManager(checkpointManager);
        orchestrator.setCanonicalImportService(importService);
        orchestrator.adapterFactory = token -> new TeamsConnectorAdapter(token, base + "/v1.0");
        return orchestrator;
    }

    private void checkpointIs(String stored) {
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(stored);
    }

    /** What was saved as the checkpoint, exactly once. */
    private String savedCheckpoint() {
        return savedCheckpoint(KEY);
    }

    private String savedCheckpoint(String key) {
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(checkpointManager).saveSimpleCheckpoint(eq("p-teams"), eq(key), saved.capture());
        return saved.getValue();
    }

    private static ImportProfileDefinition profile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-teams");
        profile.setRepositoryId("bedroom");
        return profile;
    }

    private static ConnectorDefinition connector() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-teams");
        connector.setSourceSystem("teams");
        return connector;
    }

    private static final Map<String, String> CHANNEL = Map.of("teamId", "T1", "channelId", "C1");
    private static final String KEY = "teams.T1.C1";
    private static final String STORED = "delta:%s/v1.0/teams/T1/channels/C1/messages/delta?$deltatoken=t1";

    private String storedLink() {
        return base + "/v1.0/teams/T1/channels/C1/messages/delta?$deltatoken=t1";
    }

    // ── where the feed starts ──────────────────────────────────────

    /** No checkpoint: the whole feed, unfiltered — and the channel listing is never read. */
    @Test
    @DisplayName("Teams: a fresh profile reads the whole delta feed, not the channel listing, and saves the delta link")
    void teamsAFreshProfileReadsTheWholeFeedNotTheListing() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1", "2026-01-01T00:00:01Z"), msg("m2", "2026-01-01T00:00:02Z")));
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(0, MESSAGE_CALLS.get(), "the channel listing was read");
        assertEquals(List.of(base + "/v1.0/teams/T1/channels/C1/messages/delta"), DELTA_LINKS, "the feed was not read from its start, unfiltered");
        assertEquals(List.of("m1", "m2"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    /**
     * A creation-time checkpoint an earlier version wrote: the feed starts a millisecond before
     * it (lastModifiedDateTime gt), so a message at that very time comes again — the import
     * service's dedupe answers for it — and none created after it is left out.
     */
    @Test
    @DisplayName("Teams: a legacy checkpoint starts the feed a millisecond before its time")
    void teamsALegacyCheckpointStartsTheFeedAMillisecondBeforeIt() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1", "2026-01-01T00:00:01Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs("2026-01-01T00:00:01Z");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(List.of(base + "/v1.0/teams/T1/channels/C1/messages/delta?$filter=lastModifiedDateTime%20gt%202026-01-01T00%3A00%3A00.999Z"),
                DELTA_LINKS, "the feed did not start a millisecond before the legacy checkpoint");
        assertEquals(List.of("m1"), importedIds);
        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    /** The shape an intermediate build wrote — canonical time and the ids at it — starts the same way, from its time truncated to the millisecond. */
    @Test
    @DisplayName("Teams: a <time>|<ids> checkpoint starts the feed a millisecond before its time")
    void teamsATimeAndIdsCheckpointStartsTheFeedAMillisecondBeforeIt() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2"));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs("2026-01-01T00:00:02.000500000Z|m2");

        orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(List.of(base + "/v1.0/teams/T1/channels/C1/messages/delta?$filter=lastModifiedDateTime%20gt%202026-01-01T00%3A00%3A01.999Z"), DELTA_LINKS);
    }

    @Test
    @DisplayName("Teams: a stored checkpoint that is neither a delta link nor a timestamp is an error, not 'no checkpoint'")
    void teamsAStoredCheckpointThatCannotBeReadIsAnError() {
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs("last tuesday|m1");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("last tuesday") && e.contains("neither a delta link nor a timestamp")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get() + MESSAGE_CALLS.get(), "the channel was read although the checkpoint could not be");
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** A checkpoint is not a place to put a URL: a stored link into another channel's feed is not followed. */
    @Test
    @DisplayName("Teams: a stored delta link into another channel's feed is not followed")
    void teamsAStoredDeltaLinkOfAnotherChannelIsNotFollowed() {
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs("delta:" + deltaLink("OTHER", "delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("did not write")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get() + MESSAGE_CALLS.get(), "a link this connector did not write was followed: " + DELTA_LINKS);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: a stored delta link on another host is not followed")
    void teamsAStoredDeltaLinkOfAnotherHostIsNotFollowed() {
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs("delta:http://graph.example.invalid:" + server.getAddress().getPort() + "/v1.0/teams/T1/channels/C1/messages/delta?$deltatoken=t1");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("did not write")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get() + MESSAGE_CALLS.get());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * Graph writes a channel id raw in the links it hands back ({@code 19:…@thread.tacv2}); this
     * connector writes it encoded. The same place either way — a comparison of the raw strings
     * would refuse every link Graph returned and stop the channel for good.
     */
    @Test
    @DisplayName("Teams: a delta link with the channel id written raw, as Graph writes it, is followed and saved")
    void teamsAGraphLinkWithTheChannelIdRawIsFollowed() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPageLinking(deltaLink(RAW_CHANNEL, "delta", "t2"), false, msg("m7", "2026-01-01T00:00:07Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs("delta:" + deltaLink(RAW_CHANNEL, "delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), Map.of("teamId", "T1", "channelId", RAW_CHANNEL), 10);

        assertFalse(result.hasErrors(), "Graph's own link was refused: " + result.errors());
        assertEquals(List.of("m7"), importedIds);
        assertEquals("delta:" + deltaLink(RAW_CHANNEL, "delta", "t2"), savedCheckpoint("teams.T1." + RAW_CHANNEL));
    }

    /** Graph may hand a link back in the OData key syntax: the same feed, followed and saved. */
    @Test
    @DisplayName("Teams: a nextLink in Graph's key syntax is this channel's feed — followed and saved")
    void teamsAGraphLinkInTheKeySyntaxIsFollowed() {
        String keyLink = base + KEY_SYNTAX_FEED + "?$skiptoken=k1";
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPageLinking(keyLink, true, msg("m7", "2026-01-01T00:00:07Z")));
            else json(exchange, 200, deltaPage("delta", "t2", msg("m8", "2026-01-01T00:00:08Z")));
        };
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertFalse(result.hasErrors(), "Graph's key-syntax link was refused: " + result.errors());
        assertEquals(keyLink, DELTA_LINKS.get(1), DELTA_LINKS.toString());
        assertEquals(List.of("m7", "m8"), importedIds);
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    // ── following the feed ─────────────────────────────────────────

    @Test
    @DisplayName("Teams: a delta checkpoint reads the feed from the stored link and saves the new delta link")
    void teamsADeltaCheckpointReadsTheFeedFromTheStoredLink() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m7", "2026-01-01T00:00:07Z"), msg("m8", "2026-01-01T00:00:08Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(0, MESSAGE_CALLS.get(), "the channel listing was read");
        assertEquals(List.of(storedLink()), DELTA_LINKS, "the feed was not read from the stored link");
        assertEquals(List.of("m7", "m8"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("Teams: a round follows nextLinks and saves the deltaLink that ends it")
    void teamsADeltaRoundFollowsNextLinksThenSavesTheDeltaLink() {
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("m7", "2026-01-01T00:00:07Z")));
            else json(exchange, 200, deltaPage("delta", "t2", msg("m8", "2026-01-01T00:00:08Z")));
        };
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(2, DELTA_CALLS.get(), DELTA_LINKS.toString());
        assertEquals(deltaLink("skip", "s1"), DELTA_LINKS.get(1), "the nextLink was not followed");
        assertEquals(List.of("m7", "m8"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    /** A run cut at the request cap loses nothing: what was read is imported and the link it reached is saved. */
    @Test
    @DisplayName("Teams: a run cut at the request cap saves the link it reached — the next poll continues, nothing is lost")
    void teamsADeltaRoundCutAtTheCapSavesTheLinkItReached() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("skip", "s" + n, msg("m" + (6 + n), "2026-01-01T00:00:0" + (6 + n) + "Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(),
                Map.of("teamId", "T1", "channelId", "C1", TeamsFetchOrchestrator.PARAM_MAX_MESSAGE_REQUESTS, "1"), 10);

        assertEquals(1, DELTA_CALLS.get());
        assertEquals(List.of("m7"), importedIds, "the page read before the cap should be imported: " + importedIds);
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("teamsMessageMaxRequests"), result.incompleteReads().get(0));
        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals("delta:" + deltaLink("skip", "s1"), savedCheckpoint());
    }

    /** The budget stops INSIDE a page: the page is not passed, so the next poll reads it again and takes the rest. */
    @Test
    @DisplayName("Teams: the budget stops inside a page without passing it")
    void teamsTheBudgetStopsInsideADeltaPageWithoutPassingIt() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m7", "2026-01-01T00:00:07Z"), msg("m8", "2026-01-01T00:00:08Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 1);

        assertEquals(List.of("m7"), importedIds, "the budget did not stop: " + importedIds);
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("read again next poll"), result.incompleteReads().get(0));
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A message whose body was imported before its attachment failed (and was recorded) has spent
     * the budget: with a limit of 1 the second such message is not attempted, and the page is read
     * again next poll — not a page of fifty partial imports past the limit (Codex P2).
     */
    @Test
    @DisplayName("Teams: a message that imported part of itself before a part failed spends the budget")
    void teamsAMessageThatImportedPartOfItselfSpendsTheBudget() {
        failingDownloads = List.of("F-bad1", "F-bad2");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2",
                msgWithFile("m1", "2026-01-01T00:00:01Z", "F-bad1"), msgWithFile("m2", "2026-01-01T00:00:02Z", "F-bad2")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 1);

        assertEquals(List.of("m1"), importedIds, "a message past the limit was imported: " + importedIds);
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("read again next poll")), result.incompleteReads().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** The budget reached at a page boundary: the page is passed and the next one is not asked for. */
    @Test
    @DisplayName("Teams: the budget reached at a page boundary passes the page and asks for no further one")
    void teamsTheBudgetReachedAtAPageBoundaryReadsNoFurther() {
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("m7", "2026-01-01T00:00:07Z")));
            else json(exchange, 200, deltaPage("delta", "t2", msg("m8", "2026-01-01T00:00:08Z")));
        };
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 1);

        assertEquals(1, DELTA_CALLS.get(), "a page was asked for after the budget was spent: " + DELTA_LINKS);
        assertEquals(List.of("m7"), importedIds);
        assertTrue(result.incompleteReads().get(0).contains("continues from the saved link"), result.incompleteReads().get(0));
        assertEquals("delta:" + deltaLink("skip", "s1"), savedCheckpoint());
    }

    /** A page read again is mostly messages already imported: their skips spend no budget, so the page is passed. */
    @Test
    @DisplayName("Teams: a page read again spends no budget on the messages it already imported")
    void teamsADeltaPageReadAgainSpendsNoBudgetOnWhatItSkips() {
        skippingImports = List.of("m7");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m7", "2026-01-01T00:00:07Z"), msg("m8", "2026-01-01T00:00:08Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 1);

        assertEquals(List.of("m8"), importedIds, "the skipped message spent the budget and the page can never be passed: " + importedIds);
        assertEquals(1, result.skipped(), result.toString());
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    /** A failure nothing records holds its page — and the pages passed before it stay passed. */
    @Test
    @DisplayName("Teams: a failure that could not be dead-lettered holds its page; the pages before it stay passed")
    void teamsAnUnrecordedFailureInADeltaPageHoldsTheLink() {
        dlqWritable = false;
        failingDownloads = List.of("F-bad");
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("m6", "2026-01-01T00:00:06Z")));
            else json(exchange, 200, deltaPage("delta", "t2", msgWithFile("m7", "2026-01-01T00:00:07Z", "F-bad")));
        };
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        assertEquals("delta:" + deltaLink("skip", "s1"), savedCheckpoint(), "the held page was passed, or the passed one was not saved");
    }

    /** A recorded failure does not hold the page: the row is the record, and the feed moves on. */
    @Test
    @DisplayName("Teams: a failed download is dead-lettered as never read with its URL and the page is passed")
    void teamsAFailedDownloadIsDeadLetteredNeverReadAndThePagePasses() {
        failingDownloads = List.of("F-bad");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msgWithFile("m7", "2026-01-01T00:00:07Z", "F-bad")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertTrue(dlqReasons.get(0).contains("F-bad") && dlqReadReasons.isEmpty(), "expected a never-read row: " + dlqReasons);
        assertEquals(base + "/files/F-bad", dlqRequests.get(0).getMetadata().get("teamsFileUrl"), "the row must carry the URL it is fetched again by");
        assertTrue(result.hasErrors(), result.toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("Teams: a deleted message in the feed — deletedDateTime or @removed — is skipped, not imported")
    void teamsADeletedMessageInTheFeedIsSkippedNotImported() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2",
                "{\"id\":\"m5\",\"createdDateTime\":\"2026-01-01T00:00:05Z\",\"deletedDateTime\":\"2026-01-02T00:00:00Z\",\"body\":{\"content\":\"\"}}",
                "{\"id\":\"m6\",\"@removed\":{\"reason\":\"deleted\"}}",
                msg("m7", "2026-01-01T00:00:07Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(List.of("m7"), importedIds, "a deleted message was imported: " + importedIds);
        assertEquals(2, result.skipped(), result.toString());
        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("Teams: a page without a nextLink or deltaLink is refused — the feed could not be continued from it")
    void teamsADeltaPageWithoutALinkIsRefused() {
        deltaPages = (exchange, n) -> json(exchange, 200, "{\"value\":[" + msg("m7", "2026-01-01T00:00:07Z") + "]}");
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("@odata.nextLink or @odata.deltaLink")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), "a page the feed cannot continue from was imported: " + importedIds);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: a page whose nextLink is its own link is refused, not followed to the cap")
    void teamsADeltaPageThatLinksToItselfIsRefused() {
        deltaPages = (exchange, n) -> json(exchange, 200, "{\"value\":[],\"@odata.nextLink\":\"" + storedLink() + "\"}");
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, DELTA_CALLS.get(), DELTA_LINKS.toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("own link")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: a page without a value array is refused, not passed as empty")
    void teamsADeltaPageWithoutValueIsRefused() {
        deltaPages = (exchange, n) -> json(exchange, 200, "{\"@odata.deltaLink\":\"" + deltaLink("delta", "t2") + "\"}");
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.hasErrors(), "a malformed page was read as empty: " + result);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** A link Graph hands back is saved only if it would be followed: one out of this channel's feed holds the page. */
    @Test
    @DisplayName("Teams: a page linking out of this channel's feed is refused, nothing on it imported")
    void teamsADeltaPageLinkingOutOfTheChannelFeedIsRefused() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPageLinking(deltaLink("OTHER", "skip", "x"), true, msg("m7", "2026-01-01T00:00:07Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("outside this channel's feed")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** The page is checked whole first: a message without an id holds the page before anything on it is imported. */
    @Test
    @DisplayName("Teams: a message without an id holds its page, and nothing on the page is imported")
    void teamsAMessageWithoutAnIdHoldsThePageAndNothingOnItIsImported() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m7", "2026-01-01T00:00:07Z"),
                "{\"createdDateTime\":\"2026-01-01T00:00:08Z\",\"body\":{\"content\":\"no id\"}}"));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without an id")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), "part of a page that cannot be passed was imported: " + importedIds);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * At most limit × 4 messages are tried in a run, counted between pages — a page is finished
     * once begun, so a page of failures cannot hold the feed for ever. Three failures on page 1
     * (under the bound), two on page 2 (over it), and page 3 is not asked for.
     */
    @Test
    @DisplayName("Teams: the attempts of one run are bounded by four times the limit, counted between pages")
    void teamsTheAttemptsOfOneRunAreBoundedBetweenPages() {
        failingImports = List.of("f1", "f2", "f3", "f4", "f5");
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("f1", "2026-01-01T00:00:01Z"), msg("f2", "2026-01-01T00:00:02Z"), msg("f3", "2026-01-01T00:00:03Z")));
            else if (n == 2) json(exchange, 200, deltaPage("skip", "s2", msg("f4", "2026-01-01T00:00:04Z"), msg("f5", "2026-01-01T00:00:05Z")));
            else json(exchange, 200, deltaPage("delta", "t3", msg("m9", "2026-01-01T00:00:09Z")));
        };
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 1);

        assertEquals(2, DELTA_CALLS.get(), "a page was asked for past the bound: " + DELTA_LINKS);
        assertEquals(5, dlqReadReasons.size(), dlqReasons.toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("4 × the limit of 1")), result.incompleteReads().toString());
        assertEquals("delta:" + deltaLink("skip", "s2"), savedCheckpoint());
    }

    @Test
    @DisplayName("Teams: a request-cap parameter that is not a number is reported and nothing is read")
    void teamsABadCapParameterIsReported() {
        FetchResult result = teams().execute(null, profile(), connector(),
                Map.of("teamId", "T1", "channelId", "C1", TeamsFetchOrchestrator.PARAM_MAX_MESSAGE_REQUESTS, "fifty"), 10);

        assertTrue(result.hasErrors(), result.toString());
        assertTrue(result.errors().get(0).contains("teamsMessageMaxRequests"), result.errors().get(0));
        assertEquals(0, DELTA_CALLS.get());
    }

    /** A request that fails midway: the pages passed before it stay passed. */
    @Test
    @DisplayName("Teams: a failed request keeps the pages already passed")
    void teamsAFailedRequestKeepsThePagesAlreadyPassed() {
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("m7", "2026-01-01T00:00:07Z")));
            else json(exchange, 400, "{\"error\":{\"code\":\"BadRequest\"}}");
        };
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs(String.format(STORED, base));

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("connection failed")), result.errors().toString());
        assertEquals(List.of("m7"), importedIds);
        assertEquals("delta:" + deltaLink("skip", "s1"), savedCheckpoint(), "the page passed before the failure was not kept");
    }

    // ── one message: body, attachments, dead letters ──────────────

    /** A file attachment without a content URL cannot be read; skipped, the message would settle and the page pass it. */
    @Test
    @DisplayName("Teams: a file attachment without a content URL is dead-lettered as never read, not skipped")
    void teamsAFileWithoutAContentUrlIsDeadLetteredNotSkipped() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2",
                "{\"id\":\"m1\",\"createdDateTime\":\"2026-01-01T00:00:01Z\",\"body\":{\"content\":\"see file\"},\"from\":{\"user\":{\"displayName\":\"u\"}},"
                + "\"attachments\":[{\"id\":\"att-nourl\",\"name\":\"a.pdf\",\"contentType\":\"file\",\"contentUrl\":null}]}"));
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReasons.size(), "the attachment without a URL was skipped, not recorded: " + dlqReasons);
        assertTrue(dlqReasons.get(0).contains("att-nourl") && dlqReadReasons.isEmpty(), dlqReasons.toString());
        assertTrue(result.hasErrors(), result.toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("Teams: an import that throws after the read is dead-lettered as read, not never-read")
    void teamsAnImportThatThrowsIsDeadLetteredAsRead() {
        throwingImports = List.of("m1");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1", "2026-01-01T00:00:01Z"), msg("m2", "2026-01-01T00:00:02Z")));
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReadReasons.size(), "the thrown import was not recorded as a READ item: " + dlqReasons);
        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertEquals(List.of("m2"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("Teams: an import that answers an error is dead-lettered as read")
    void teamsAnImportThatAnswersAnErrorIsDeadLetteredAsRead() {
        failingImports = List.of("m2");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1", "2026-01-01T00:00:01Z"), msg("m2", "2026-01-01T00:00:02Z")));
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReadReasons.size(), "the refused import was not dead-lettered as read: " + dlqReasons);
        assertEquals(List.of("m1"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        assertEquals("delta:" + deltaLink("delta", "t2"), savedCheckpoint());
    }

    /** A message whose body import failed is dead-lettered WITH the body: replayable, not only a record of the miss (Codex P1 on Mattermost). */
    @Test
    @DisplayName("Teams: a failed body import is dead-lettered with the body bytes, so the replay imports the text")
    void teamsAFailedBodyImportRowCarriesTheBodyBytes() {
        failingImports = List.of("m2");
        throwingImports = List.of("m1");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1", "2026-01-01T00:00:01Z"), msg("m2", "2026-01-01T00:00:02Z")));
        teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(2, dlqReadReasons.size(), dlqReasons.toString());
        assertEquals("hello m2", new String(dlqBodies.getOrDefault("m2", new byte[0]), StandardCharsets.UTF_8), "the row for the refused body carries no bytes");
        assertEquals("hello m1", new String(dlqBodies.getOrDefault("m1", new byte[0]), StandardCharsets.UTF_8), "the row for the thrown body carries no bytes");
    }

    /** The attachment row names the message document, so a replay links the attachment to it again (Codex P2 on Mattermost). */
    @Test
    @DisplayName("Teams: a failed attachment row names its message document for the replay to link to")
    void teamsAFailedAttachmentRowNamesItsMessageDocument() {
        failingDownloads = List.of("F-bad");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msgWithFile("m1", "2026-01-01T00:00:01Z", "F-bad")));
        teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqRequests.size(), dlqReasons.toString());
        assertEquals("obj-m1", dlqRequests.get(0).getMetadata().get("parentObjectId"), "the row does not name the message document: " + dlqRequests.get(0).getMetadata());
    }
}
