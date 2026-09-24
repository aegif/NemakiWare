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
 * A Slack channel is read to the checkpoint and the OLDEST new messages are taken first (R107).
 *
 * <p>Slack lists newest first. The previous orchestrator asked for the first {@code limit}
 * messages since the checkpoint — the {@code limit} NEWEST — and raised the checkpoint to the
 * newest it had seen, so in a burst every older new message fell below the checkpoint for ever.
 * What is measured here is the REAL adapter pointed at a local stub of the Slack API — its
 * paging and refusals — and the orchestrator's budget, dead-letter answers and checkpoint.
 */
class SlackChannelsAreReadToTheCheckpointTest {

    private static HttpServer server;
    private static String base;
    private static String previousAllowLocalhost;

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, int callNumber) throws IOException;
    }

    /** What {@code conversations.history} answers; set per test. */
    private static volatile Responder history;
    /** File ids whose download answers 500. */
    private static volatile List<String> failingDownloads = List.of();
    private static final AtomicInteger HISTORY_CALLS = new AtomicInteger();
    private static final List<String> HISTORY_QUERIES = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStub() throws Exception {
        previousAllowLocalhost = System.getProperty("nemaki.ingest.allowLocalhost");
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/conversations.history", exchange -> {
            HISTORY_QUERIES.add(String.valueOf(exchange.getRequestURI().getQuery()));
            history.respond(exchange, HISTORY_CALLS.incrementAndGet());
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
        HISTORY_CALLS.set(0);
        HISTORY_QUERIES.clear();
        failingDownloads = List.of();
        dlqWritable = true;
        failingImports = List.of();
        throwingImports = List.of();
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000001.000000")));
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Retry-After", "0");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    /** A message with no files; {@code ts} is its id. */
    private static String msg(String ts) {
        return "{\"ts\":\"" + ts + "\",\"user\":\"U1\",\"text\":\"hello " + ts + "\"}";
    }

    /** A message with one file, downloadable from the stub. */
    private static String msgWithFile(String ts, String fileId) {
        return "{\"ts\":\"" + ts + "\",\"user\":\"U1\",\"text\":\"see file\",\"files\":[{\"id\":\"" + fileId
                + "\",\"name\":\"" + fileId + ".txt\",\"mimetype\":\"text/plain\",\"size\":3,\"url_private_download\":\""
                + base + "/files/" + fileId + "\"}]}";
    }

    private static String page(boolean hasMore, String cursor, String... messages) {
        return "{\"ok\":true,\"messages\":[" + String.join(",", messages) + "],\"has_more\":" + hasMore
                + (cursor == null ? "" : ",\"response_metadata\":{\"next_cursor\":\"" + cursor + "\"}") + "}";
    }

    private static String queryValue(String query, String name) {
        for (String part : query.split("&")) {
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        return null;
    }

    // ── the orchestrator under test ────────────────────────────────

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService importService;
    private final List<String> importedIds = new ArrayList<>();
    private final List<String> dlqReasons = new ArrayList<>();
    private final List<String> dlqReadReasons = new ArrayList<>();
    private final List<ExternalIngestRequest> dlqRequests = new ArrayList<>();
    /** The bytes a read row was written with, by source object id (null: metadata-only). */
    private final Map<String, byte[]> dlqBodies = new java.util.HashMap<>();
    private List<String> throwingImports = List.of();
    private boolean dlqWritable = true;
    private List<String> failingImports = List.of();

    private SlackFetchOrchestrator slack() {
        SlackFetchOrchestrator orchestrator = new SlackFetchOrchestrator();
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        importedIds.clear();
        dlqReasons.clear();
        dlqReadReasons.clear();
        dlqRequests.clear();
        dlqBodies.clear();
        lenient().when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("xoxb-secret");
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
            importedIds.add(req.getSourceObjectId());
            return new ExternalIngestResult("r", "obj-" + req.getSourceObjectId(), "1.0", false, false, false, null, null,
                    List.of(), List.of());
        });
        orchestrator.setFetchSupport(fetchSupport);
        orchestrator.setCheckpointManager(checkpointManager);
        orchestrator.setCanonicalImportService(importService);
        orchestrator.adapterFactory = token -> new SlackConnectorAdapter(token, base + "/api");
        return orchestrator;
    }

    private void checkpointIs(String stored) {
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(stored);
    }

    /** files_and_body, so that message bodies are imported and their order can be seen. */
    private static ImportProfileDefinition profile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-slack");
        profile.setRepositoryId("bedroom");
        profile.setImportPolicy("files_and_body");
        return profile;
    }

    private static ConnectorDefinition connector() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-slack");
        connector.setSourceSystem("slack");
        connector.setTenantId("T1");
        return connector;
    }

    private static final Map<String, String> CHANNEL = Map.of("channelId", "C1");

    // ── the listing ────────────────────────────────────────────────

    /**
     * Five new messages over two pages, newest first as Slack lists them; a budget of 2 takes
     * the two OLDEST and leaves the newer three for the next poll. The old shape took the
     * newest two and passed the other three for ever.
     */
    @Test
    @DisplayName("Slack: the channel is read to the end across cursors and the oldest new messages are taken first")
    void slackListsTheChannelToTheEndAndTakesTheOldestFirst() {
        history = (exchange, n) -> {
            if (n == 1) json(exchange, 200, page(true, "c-2", msg("1700000005.000000"), msg("1700000004.000000"), msg("1700000003.000000")));
            else json(exchange, 200, page(false, null, msg("1700000002.000000"), msg("1700000001.000000")));
        };
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 2);

        assertEquals(2, HISTORY_CALLS.get(), "the cursor was not followed");
        assertEquals(5, result.fetched(), result.toString());
        assertEquals(List.of("1700000001.000000", "1700000002.000000"), importedIds, "the budget did not take the oldest messages");
        assertFalse(result.sawEverything(), "3 messages were left for the next poll: " + result);
        assertTrue(result.incompleteReads().get(0).contains("left for the next poll"), result.incompleteReads().get(0));
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-slack", "slack.C1", "1700000002.000000");
    }

    /** The next poll asks Slack for what is newer than the checkpoint (oldest is exclusive) and moves on. */
    @Test
    @DisplayName("Slack: the next poll asks from the checkpoint and takes the rest")
    void slackTheNextPollAsksFromTheCheckpoint() {
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000005.000000"), msg("1700000004.000000"), msg("1700000003.000000")));
        SlackFetchOrchestrator orchestrator = slack();
        checkpointIs("1700000002.000000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals("1700000002.000000", queryValue(HISTORY_QUERIES.get(0), "oldest"), HISTORY_QUERIES.get(0));
        assertEquals(List.of("1700000003.000000", "1700000004.000000", "1700000005.000000"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-slack", "slack.C1", "1700000005.000000");
    }

    /** An API that ignored `oldest` would offer the whole channel again; the checkpoint is still applied and never moves backwards. */
    @Test
    @DisplayName("Slack: messages at or below the checkpoint are skipped even when Slack lists them")
    void slackMessagesBelowTheCheckpointAreSkippedAndTheCheckpointNeverMovesBack() {
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000002.000000"), msg("1700000001.000000")));
        SlackFetchOrchestrator orchestrator = slack();
        checkpointIs("1700000002.000000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(importedIds.isEmpty(), "a message at or below the checkpoint was imported again: " + importedIds);
        assertEquals(2, result.skipped(), result.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A listing cut at the request cap imports nothing and holds the checkpoint: the messages
     * it did not reach are the OLDER ones, and a checkpoint raised over the ones it reached
     * would exclude them from every later poll. The reason names the parameter to raise.
     */
    @Test
    @DisplayName("Slack: a listing cut at the request cap imports nothing, holds the checkpoint and names the cap")
    void slackAListingCutAtTheCapImportsNothing() {
        history = (exchange, n) -> json(exchange, 200, page(true, "c-" + (n + 1), msg("170000000" + (9 - n) + ".000000")));
        FetchResult result = slack().execute(null, profile(), connector(),
                Map.of("channelId", "C1", SlackFetchOrchestrator.PARAM_MAX_HISTORY_REQUESTS, "2"), 10);

        assertEquals(2, HISTORY_CALLS.get());
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("slackHistoryMaxRequests"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Slack: a page without a messages array is refused, not read as an empty channel")
    void slackAPageWithoutMessagesIsRefused() {
        history = (exchange, n) -> json(exchange, 200, "{\"ok\":true,\"has_more\":false}");
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.hasErrors(), "a malformed page was read as a channel: " + result);
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Slack: a page without has_more is refused — read as false it was the end of the channel")
    void slackAPageWithoutHasMoreIsRefused() {
        history = (exchange, n) -> json(exchange, 200, "{\"ok\":true,\"messages\":[" + msg("1700000001.000000") + "]}");
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.hasErrors(), "a page without has_more was read as the end: " + result);
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Slack: has_more without a cursor is a cut — nothing imported, checkpoint held")
    void slackHasMoreWithoutACursorIsACut() {
        history = (exchange, n) -> json(exchange, 200, page(true, null, msg("1700000001.000000")));
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("no cursor"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Slack: the same cursor twice is a cut, not progress")
    void slackTheSameCursorTwiceIsACut() {
        history = (exchange, n) -> json(exchange, 200, page(true, "c-1", msg("170000000" + (9 - n) + ".000000")));
        FetchResult result = slack().execute(null, profile(), connector(),
                Map.of("channelId", "C1", SlackFetchOrchestrator.PARAM_MAX_HISTORY_REQUESTS, "6"), 10);

        assertEquals(2, HISTORY_CALLS.get(), "the repeated cursor was not recognised on the request that repeated it");
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("same cursor twice"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    // ── failures and the checkpoint ────────────────────────────────

    /**
     * An attachment whose download fails is dead-lettered as never read, with the URL the DLQ
     * controller fetches it again by; the message is not named, and a newer message that
     * settles moves the checkpoint past it — the row being the record.
     */
    @Test
    @DisplayName("Slack: a failed download is dead-lettered as never read with its URL; the message is not named and the newer one moves the checkpoint")
    void slackAFailedDownloadIsDeadLetteredNeverReadWithItsUrl() {
        failingDownloads = List.of("F-bad");
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000002.000000"), msgWithFile("1700000001.000000", "F-bad")));
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertTrue(dlqReasons.get(0).contains("F-bad") && dlqReadReasons.isEmpty(), "expected a never-read row: " + dlqReasons);
        assertEquals(base + "/files/F-bad", dlqRequests.get(0).getMetadata().get("slackFileUrl"), "the row must carry the URL it is fetched again by");
        assertEquals("attachment", dlqRequests.get(0).getSourceObjectType());
        assertTrue(importedIds.contains("1700000002.000000") && importedIds.contains("1700000001.000000"),
                "the body of the failed message and the newer message should still be imported: " + importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-slack", "slack.C1", "1700000002.000000");
    }

    /** A file without a download URL cannot be read; skipped, the message would settle and the checkpoint pass it (Codex P1). */
    @Test
    @DisplayName("Slack: a file without a download URL is dead-lettered as never read, not skipped — the message is not named")
    void slackAFileWithoutADownloadUrlIsDeadLetteredNotSkipped() {
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000002.000000"),
                "{\"ts\":\"1700000001.000000\",\"user\":\"U1\",\"text\":\"see file\",\"files\":[{\"id\":\"F-nourl\",\"name\":\"a.pdf\",\"mimetype\":\"application/pdf\",\"size\":3}]}"));
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReasons.size(), "the file without a URL was skipped, not recorded: " + dlqReasons);
        assertTrue(dlqReasons.get(0).contains("F-nourl") && dlqReadReasons.isEmpty(), dlqReasons.toString());
        assertTrue(result.hasErrors(), result.toString());
        // the newer message settles and passes the failed one — the row is the record
        verify(checkpointManager).saveSimpleCheckpoint("p-slack", "slack.C1", "1700000002.000000");
    }

    /** A file Slack only links to (mode external) has no URL by design: not an attachment, not a failure. */
    @Test
    @DisplayName("Slack: an external (linked) file is not an attachment — the message settles without a dead-letter row")
    void slackAnExternalFileIsNotAnAttachment() {
        history = (exchange, n) -> json(exchange, 200, page(false, null,
                "{\"ts\":\"1700000001.000000\",\"user\":\"U1\",\"text\":\"see link\",\"files\":[{\"id\":\"F-ext\",\"name\":\"doc\",\"mode\":\"external\",\"is_external\":true,\"size\":0}]}"));
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(dlqReasons.isEmpty(), dlqReasons.toString());
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-slack", "slack.C1", "1700000001.000000");
    }

    @Test
    @DisplayName("Slack: an import that throws after the read is dead-lettered as read, not never-read")
    void slackAnImportThatThrowsIsDeadLetteredAsRead() {
        throwingImports = List.of("1700000001.000000");
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000002.000000"), msg("1700000001.000000")));
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReadReasons.size(), "the thrown import was not recorded as a READ item: " + dlqReasons);
        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertEquals(List.of("1700000002.000000"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-slack", "slack.C1", "1700000002.000000");
    }

    @Test
    @DisplayName("Slack: an import that answers an error is dead-lettered as read and the message is not named")
    void slackAnImportThatAnswersAnErrorIsDeadLetteredAsRead() {
        failingImports = List.of("1700000002.000000");
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000002.000000"), msg("1700000001.000000")));
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqReadReasons.size(), "the refused import was not dead-lettered as read: " + dlqReasons);
        assertEquals(List.of("1700000001.000000"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-slack", "slack.C1", "1700000001.000000");
    }

    /** A message whose body import failed is dead-lettered WITH the body: replayable, not only a record of the miss (Codex P1 on Mattermost). */
    @Test
    @DisplayName("Slack: a failed body import is dead-lettered with the body bytes, so the replay imports the text")
    void slackAFailedBodyImportRowCarriesTheBodyBytes() {
        failingImports = List.of("1700000002.000000");
        throwingImports = List.of("1700000001.000000");
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000002.000000"), msg("1700000001.000000")));
        slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(2, dlqReadReasons.size(), dlqReasons.toString());
        assertEquals("hello 1700000002.000000", new String(dlqBodies.getOrDefault("1700000002.000000", new byte[0]), StandardCharsets.UTF_8), "the row for the refused body carries no bytes");
        assertEquals("hello 1700000001.000000", new String(dlqBodies.getOrDefault("1700000001.000000", new byte[0]), StandardCharsets.UTF_8), "the row for the thrown body carries no bytes");
    }

    /** The attachment row names the message document, so a replay links the attachment to it again (Codex P2 on Mattermost). */
    @Test
    @DisplayName("Slack: a failed attachment row names its message document for the replay to link to")
    void slackAFailedAttachmentRowNamesItsMessageDocument() {
        failingDownloads = List.of("F-bad");
        history = (exchange, n) -> json(exchange, 200, page(false, null, msgWithFile("1700000001.000000", "F-bad")));
        slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertEquals(1, dlqRequests.size(), dlqReasons.toString());
        assertEquals("obj-1700000001.000000", dlqRequests.get(0).getMetadata().get("parentObjectId"), "the row does not name the message document: " + dlqRequests.get(0).getMetadata());
    }

    @Test
    @DisplayName("Slack: a failure that could not be dead-lettered holds the checkpoint and is reported")
    void slackAnUnrecordedFailureHoldsTheCheckpoint() {
        dlqWritable = false;
        failingDownloads = List.of("F-bad");
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000002.000000"), msgWithFile("1700000001.000000", "F-bad")));
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** With exactly limit × 4 failures ahead, the good message behind them is left untried this run — said so, checkpoint held. */
    @Test
    @DisplayName("Slack: the attempts of one run are bounded by four times the limit")
    void slackTheAttemptsOfOneRunAreBoundedByFourTimesTheLimit() {
        failingImports = List.of("1700000001.000000", "1700000002.000000", "1700000003.000000", "1700000004.000000");
        history = (exchange, n) -> json(exchange, 200, page(false, null, msg("1700000005.000000"), msg("1700000004.000000"),
                msg("1700000003.000000"), msg("1700000002.000000"), msg("1700000001.000000")));
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 1);

        assertEquals(4, dlqReadReasons.size(), dlqReasons.toString());
        assertTrue(importedIds.isEmpty(), "the message behind four failures was tried beyond the bound: " + importedIds);
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("4 × the limit of 1")), result.incompleteReads().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Slack: a request-cap parameter that is not a number is reported and nothing is read")
    void slackABadCapParameterIsReported() {
        FetchResult result = slack().execute(null, profile(), connector(),
                Map.of("channelId", "C1", SlackFetchOrchestrator.PARAM_MAX_HISTORY_REQUESTS, "fifty"), 10);

        assertTrue(result.hasErrors(), result.toString());
        assertTrue(result.errors().get(0).contains("slackHistoryMaxRequests"), result.errors().get(0));
        assertEquals(0, HISTORY_CALLS.get());
    }

    @Test
    @DisplayName("Slack: a stored checkpoint that is not a message timestamp is an error, not 'no checkpoint'")
    void slackAStoredCheckpointThatIsNotATimestampIsAnError() {
        SlackFetchOrchestrator orchestrator = slack();
        checkpointIs("last tuesday");

        FetchResult result = orchestrator.execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("last tuesday") && e.contains("not a message timestamp")), result.errors().toString());
        assertEquals(0, HISTORY_CALLS.get(), "the channel was listed although the checkpoint could not be read");
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Slack: a message without a timestamp refuses the listing — it can neither be skipped nor named")
    void slackAMessageWithoutATimestampRefusesTheListing() {
        history = (exchange, n) -> json(exchange, 200, "{\"ok\":true,\"messages\":[{\"user\":\"U1\",\"text\":\"no ts\"}," + msg("1700000001.000000") + "],\"has_more\":false}");
        FetchResult result = slack().execute(null, profile(), connector(), CHANNEL, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without a timestamp")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }
}
