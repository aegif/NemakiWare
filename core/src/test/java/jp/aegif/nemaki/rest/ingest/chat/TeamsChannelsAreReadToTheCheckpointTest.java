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
 * A Teams channel is read down to the checkpoint and the OLDEST new messages are taken first (R107).
 *
 * <p>Graph lists channel messages newest first and offers no filter on the creation time. The
 * previous orchestrator asked for the first {@code limit} messages — the {@code limit} NEWEST —
 * and raised the checkpoint to the newest it had seen, so in a burst every older new message fell
 * below the checkpoint for ever. What is measured here is the REAL adapter pointed at a local stub
 * of Graph — its paging, its stop at the checkpoint and its refusals — and the orchestrator's
 * budget, dead-letter answers and checkpoint.
 */
class TeamsChannelsAreReadToTheCheckpointTest {

    private static HttpServer server;
    private static String base;
    private static String previousAllowLocalhost;

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, int callNumber) throws IOException;
    }

    private static volatile Responder messages;
    private static volatile List<String> failingDownloads = List.of();
    private static final AtomicInteger MESSAGE_CALLS = new AtomicInteger();

    @BeforeAll
    static void startStub() throws Exception {
        previousAllowLocalhost = System.getProperty("nemaki.ingest.allowLocalhost");
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1.0/teams/T1/channels/C1/messages", exchange -> messages.respond(exchange, MESSAGE_CALLS.incrementAndGet()));
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
        failingDownloads = List.of();
        dlqWritable = true;
        failingImports = List.of();
        messages = (exchange, n) -> json(exchange, 200, page(null, msg("m1", "2026-01-01T00:00:01Z")));
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

    /** A page; {@code nextPage} is the number of the page the nextLink points at (null: last page). */
    private static String page(Integer nextPage, String... msgs) {
        return "{\"value\":[" + String.join(",", msgs) + "]"
                + (nextPage == null ? "" : ",\"@odata.nextLink\":\"" + base + "/v1.0/teams/T1/channels/C1/messages?$skiptoken=p" + nextPage + "\"") + "}";
    }

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService importService;
    private final List<String> importedIds = new ArrayList<>();
    private final List<String> dlqReasons = new ArrayList<>();
    private final List<String> dlqReadReasons = new ArrayList<>();
    private final List<ExternalIngestRequest> dlqRequests = new ArrayList<>();
    private boolean dlqWritable = true;
    private List<String> failingImports = List.of();

    private TeamsFetchOrchestrator teams() {
        TeamsFetchOrchestrator orchestrator = new TeamsFetchOrchestrator();
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        importedIds.clear();
        dlqReasons.clear();
        dlqReadReasons.clear();
        dlqRequests.clear();
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
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(null);
        lenient().when(importService.executeChatContextImport(any(), any())).thenAnswer(call -> {
            ExternalIngestRequest req = call.getArgument(1);
            if (failingImports.contains(req.getSourceObjectId())) {
                return ExternalIngestResult.error("r", "refused by the import service");
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

    @Test
    @DisplayName("Teams: the channel is read across pages and the oldest new messages are taken first")
    void teamsListsTheChannelAndTakesTheOldestFirst() {
        messages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, page(2, msg("m5", "2026-01-01T00:00:05Z"), msg("m4", "2026-01-01T00:00:04Z"), msg("m3", "2026-01-01T00:00:03Z")));
            else json(exchange, 200, page(null, msg("m2", "2026-01-01T00:00:02Z"), msg("m1", "2026-01-01T00:00:01Z")));
        };
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 2);

        assertEquals(2, MESSAGE_CALLS.get(), "the nextLink was not followed");
        assertEquals(5, result.fetched(), result.toString());
        assertEquals(List.of("m1", "m2"), importedIds, "the budget did not take the oldest messages");
        assertFalse(result.sawEverything(), "3 messages were left for the next poll: " + result);
        assertTrue(result.incompleteReads().get(0).contains("left for the next poll"), result.incompleteReads().get(0));
        verify(checkpointManager).saveSimpleCheckpoint("p-teams", KEY, "2026-01-01T00:00:02.000000000Z|m2");
    }

    /**
     * Graph has no filter on the creation time, so the listing STOPS at the first page holding a
     * message at or below the checkpoint — the older pages are never asked for.
     */
    @Test
    @DisplayName("Teams: the listing stops at the checkpoint's page and does not ask for the older ones")
    void teamsTheListingStopsAtTheCheckpointPage() {
        messages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, page(2, msg("m5", "2026-01-01T00:00:05Z"), msg("m4", "2026-01-01T00:00:04Z")));
            else if (n == 2) json(exchange, 200, page(3, msg("m3", "2026-01-01T00:00:03Z"), msg("m2", "2026-01-01T00:00:02Z")));
            else json(exchange, 200, page(null, msg("m1", "2026-01-01T00:00:01Z")));
        };
        TeamsFetchOrchestrator orchestrator = teams();
        // the checkpoint is m3, in the middle of page 2: m2 (older) stops the listing there
        checkpointIs("2026-01-01T00:00:03Z|m3");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(2, MESSAGE_CALLS.get(), "the page below the checkpoint was asked for");
        assertEquals(List.of("m4", "m5"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-teams", KEY, "2026-01-01T00:00:05.000000000Z|m5");
    }

    /** A message AT the checkpoint's timestamp that the checkpoint names is skipped; one it does not name is taken. */
    @Test
    @DisplayName("Teams: at the checkpoint's timestamp only the ids it names are skipped")
    void teamsAtTheCheckpointTimestampOnlyTheNamedIdsAreSkipped() {
        messages = (exchange, n) -> json(exchange, 200, page(null, msg("m3", "2026-01-01T00:00:03Z"), msg("m2b", "2026-01-01T00:00:02Z"), msg("m2", "2026-01-01T00:00:02Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs("2026-01-01T00:00:02Z|m2");

        // the adapter stops at m2b? No: m2b is AT the checkpoint's time and not named, so it is newer for the checkpoint's purposes —
        // the adapter must not stop on it. It stops on m2 (named).
        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(importedIds.contains("m2b") && importedIds.contains("m3"), "a message at the checkpoint time that it does not name was skipped: " + importedIds);
        assertFalse(importedIds.contains("m2"), importedIds.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-teams", KEY, "2026-01-01T00:00:03.000000000Z|m3");
    }

    @Test
    @DisplayName("Teams: a listing cut at the request cap imports nothing, holds the checkpoint and names the cap")
    void teamsAListingCutAtTheCapImportsNothing() {
        messages = (exchange, n) -> json(exchange, 200, page(n + 1, msg("m" + (9 - n), "2026-01-01T00:00:0" + (9 - n) + "Z")));
        FetchResult result = teams().execute(null, profile(), connector(),
                Map.of("teamId", "T1", "channelId", "C1", TeamsFetchOrchestrator.PARAM_MAX_MESSAGE_REQUESTS, "2"), 10);

        assertEquals(2, MESSAGE_CALLS.get());
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("teamsMessageMaxRequests"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: a page without a value array is refused, not read as an empty channel")
    void teamsAPageWithoutValueIsRefused() {
        messages = (exchange, n) -> json(exchange, 200, "{\"@odata.context\":\"x\"}");
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.hasErrors(), "a malformed page was read as a channel: " + result);
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: a message without a readable creation time refuses the listing")
    void teamsAMessageWithoutACreationTimeRefusesTheListing() {
        messages = (exchange, n) -> json(exchange, 200, "{\"value\":[{\"id\":\"m-x\",\"body\":{\"content\":\"no time\"}}," + msg("m1", "2026-01-01T00:00:01Z") + "]}");
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("m-x") && e.contains("creation time")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: a message without an id refuses the listing — it can neither be skipped nor named")
    void teamsAMessageWithoutAnIdRefusesTheListing() {
        messages = (exchange, n) -> json(exchange, 200, "{\"value\":[{\"createdDateTime\":\"2026-01-01T00:00:02Z\",\"body\":{\"content\":\"no id\"}}," + msg("m1", "2026-01-01T00:00:01Z") + "]}");
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without an id")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: the same nextLink twice is a cut, not progress")
    void teamsTheSameNextLinkTwiceIsACut() {
        messages = (exchange, n) -> json(exchange, 200, page(1, msg("m" + (9 - n), "2026-01-01T00:00:0" + (9 - n) + "Z")));
        FetchResult result = teams().execute(null, profile(), connector(),
                Map.of("teamId", "T1", "channelId", "C1", TeamsFetchOrchestrator.PARAM_MAX_MESSAGE_REQUESTS, "6"), 10);

        assertEquals(2, MESSAGE_CALLS.get(), "the repeated nextLink was not recognised on the request that repeated it");
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("same @odata.nextLink twice"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: a failed download is dead-lettered as never read with its URL; the message is not named and the newer one moves the checkpoint")
    void teamsAFailedDownloadIsDeadLetteredNeverReadWithItsUrl() {
        failingDownloads = List.of("F-bad");
        messages = (exchange, n) -> json(exchange, 200, page(null, msg("m2", "2026-01-01T00:00:02Z"), msgWithFile("m1", "2026-01-01T00:00:01Z", "F-bad")));
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertTrue(dlqReasons.get(0).contains("F-bad") && dlqReadReasons.isEmpty(), "expected a never-read row: " + dlqReasons);
        assertEquals(base + "/files/F-bad", dlqRequests.get(0).getMetadata().get("teamsFileUrl"), "the row must carry the URL it is fetched again by");
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-teams", KEY, "2026-01-01T00:00:02.000000000Z|m2");
    }

    @Test
    @DisplayName("Teams: an import that answers an error is dead-lettered as read and the message is not named")
    void teamsAnImportThatAnswersAnErrorIsDeadLetteredAsRead() {
        failingImports = List.of("m2");
        messages = (exchange, n) -> json(exchange, 200, page(null, msg("m2", "2026-01-01T00:00:02Z"), msg("m1", "2026-01-01T00:00:01Z")));
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReadReasons.size(), "the refused import was not dead-lettered as read: " + dlqReasons);
        assertEquals(List.of("m1"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-teams", KEY, "2026-01-01T00:00:01.000000000Z|m1");
    }

    @Test
    @DisplayName("Teams: a failure that could not be dead-lettered holds the checkpoint and is reported")
    void teamsAnUnrecordedFailureHoldsTheCheckpoint() {
        dlqWritable = false;
        failingDownloads = List.of("F-bad");
        messages = (exchange, n) -> json(exchange, 200, page(null, msg("m2", "2026-01-01T00:00:02Z"), msgWithFile("m1", "2026-01-01T00:00:01Z", "F-bad")));
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: the attempts of one run are bounded by four times the limit")
    void teamsTheAttemptsOfOneRunAreBoundedByFourTimesTheLimit() {
        failingImports = List.of("m1", "m2", "m3", "m4");
        messages = (exchange, n) -> json(exchange, 200, page(null, msg("m5", "2026-01-01T00:00:05Z"), msg("m4", "2026-01-01T00:00:04Z"),
                msg("m3", "2026-01-01T00:00:03Z"), msg("m2", "2026-01-01T00:00:02Z"), msg("m1", "2026-01-01T00:00:01Z")));
        FetchResult result = teams().execute(null, profile(), connector(), CHANNEL, 1);

        assertEquals(4, dlqReadReasons.size(), dlqReasons.toString());
        assertTrue(importedIds.isEmpty(), "the message behind four failures was tried beyond the bound: " + importedIds);
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("4 × the limit of 1")), result.incompleteReads().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Teams: a request-cap parameter that is not a number is reported and nothing is read")
    void teamsABadCapParameterIsReported() {
        FetchResult result = teams().execute(null, profile(), connector(),
                Map.of("teamId", "T1", "channelId", "C1", TeamsFetchOrchestrator.PARAM_MAX_MESSAGE_REQUESTS, "fifty"), 10);

        assertTrue(result.hasErrors(), result.toString());
        assertTrue(result.errors().get(0).contains("teamsMessageMaxRequests"), result.errors().get(0));
        assertEquals(0, MESSAGE_CALLS.get());
    }

    @Test
    @DisplayName("Teams: a stored checkpoint that cannot be read is an error, not 'no checkpoint'")
    void teamsAStoredCheckpointThatCannotBeReadIsAnError() {
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs("last tuesday|m1");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("last tuesday") && e.contains("not a timestamp this connector can read")), result.errors().toString());
        assertEquals(0, MESSAGE_CALLS.get(), "the channel was listed although the checkpoint could not be read");
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A legacy checkpoint (Graph's own form, no ids) names nothing at its timestamp, so a message
     * AT that time is offered once more — the import service's dedupe answers for it — and is then
     * named; a message older than it is not offered.
     */
    @Test
    @DisplayName("Teams: a legacy checkpoint offers the messages at its own time once, then names them")
    void teamsALegacyCheckpointOffersItsOwnTimeOnceThenNamesIt() {
        messages = (exchange, n) -> json(exchange, 200, page(null, msg("m1", "2026-01-01T00:00:01Z"), msg("m0", "2026-01-01T00:00:00Z")));
        TeamsFetchOrchestrator orchestrator = teams();
        checkpointIs("2026-01-01T00:00:01Z");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(List.of("m1"), importedIds, "the message at the legacy checkpoint's time is offered once; older ones are not: " + importedIds);
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-teams", KEY, "2026-01-01T00:00:01.000000000Z|m1");
    }
}
