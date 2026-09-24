package jp.aegif.nemaki.rest.ingest.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jp.aegif.nemaki.rest.ingest.CanonicalImportService;
import jp.aegif.nemaki.rest.ingest.CheckpointManager;
import jp.aegif.nemaki.rest.ingest.ConnectorDefinition;
import jp.aegif.nemaki.rest.ingest.ExternalIngestRequest;
import jp.aegif.nemaki.rest.ingest.ExternalIngestResult;
import jp.aegif.nemaki.rest.ingest.FetchResult;
import jp.aegif.nemaki.rest.ingest.FetchSupport;
import jp.aegif.nemaki.rest.ingest.ImportProfileDefinition;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Chatwork: a room's latest messages are read, the ones newer than the checkpoint taken oldest
 * first, and a gap — messages the API no longer answers — recorded instead of stopping every poll
 * (R107).
 *
 * <p>What is measured here is the REAL adapter pointed at a local stub of the Chatwork API, which
 * answers a room's messages as a list in whatever order the fixture gives, and the orchestrator's
 * order, gap rule, budget, dead-letter answers and the checkpoint it saves.
 */
class ChatworkRoomsAreReadWithTheirGapsRecordedTest {

    private static HttpServer server;
    private static String base;
    private static String previousAllowLocalhost;

    /** The messages answered, as JSON, in the order answered; null answers 204. */
    private static volatile String messagesAnswer = "[]";

    @BeforeAll
    static void startStub() throws Exception {
        previousAllowLocalhost = System.getProperty("nemaki.ingest.allowLocalhost");
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/rooms/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/messages")) {
                if (messagesAnswer == null) {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                } else {
                    json(exchange, messagesAnswer);
                }
            } else if (path.endsWith("/files")) {
                json(exchange, "[]");
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v2";
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
        if (previousAllowLocalhost == null) System.clearProperty("nemaki.ingest.allowLocalhost");
        else System.setProperty("nemaki.ingest.allowLocalhost", previousAllowLocalhost);
    }

    @BeforeEach
    void reset() {
        messagesAnswer = "[]";
        dlqWritable = true;
        failingImports = List.of();
        throwingImports = List.of();
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static String msg(String id) {
        return "{\"message_id\":\"" + id + "\",\"account\":{\"account_id\":1,\"name\":\"u\"},\"body\":\"text of " + id + "\","
                + "\"send_time\":1,\"update_time\":0}";
    }

    private static String answer(String... ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) out.add(msg(id));
        return "[" + String.join(",", out) + "]";
    }

    /** A full window: 100 messages, ids from..from+99. */
    private static String window(long from) {
        List<String> out = new ArrayList<>();
        for (long id = from; id < from + ChatworkFetchOrchestrator.WINDOW; id++) out.add(msg(String.valueOf(id)));
        return "[" + String.join(",", out) + "]";
    }

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService importService;
    private final List<String> importedIds = new ArrayList<>();
    private final List<String> dlqNeverRead = new ArrayList<>();
    private final List<ExternalIngestRequest> dlqNeverReadRequests = new ArrayList<>();
    private final List<String> dlqRead = new ArrayList<>();
    private final Map<String, byte[]> dlqBytes = new HashMap<>();
    private boolean dlqWritable = true;
    private List<String> failingImports = List.of();
    private List<String> throwingImports = List.of();

    private ChatworkFetchOrchestrator chatwork() {
        ChatworkFetchOrchestrator orchestrator = new ChatworkFetchOrchestrator();
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        importedIds.clear();
        dlqNeverRead.clear();
        dlqNeverReadRequests.clear();
        dlqRead.clear();
        dlqBytes.clear();
        lenient().when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("cw-token");
        lenient().doNothing().when(fetchSupport).throttle(anyLong());
        lenient().doAnswer(call -> {
            dlqNeverReadRequests.add(call.getArgument(0));
            dlqNeverRead.add(call.getArgument(1));
            return dlqWritable;
        }).when(fetchSupport).saveSourceNeverReadToDlq(any(), anyString());
        lenient().doAnswer(call -> {
            ExternalIngestRequest r = call.getArgument(0);
            dlqRead.add(call.getArgument(1));
            dlqBytes.put(r.getSourceObjectId(), call.getArgument(2));
            return dlqWritable;
        }).when(fetchSupport).saveSourceReadToDlq(any(), anyString(), any());
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(null);
        lenient().when(importService.executeChatContextImport(any(), any())).thenAnswer(call -> {
            ExternalIngestRequest req = call.getArgument(1);
            String id = req.getSourceObjectId();
            if (failingImports.contains(id)) return ExternalIngestResult.error("r", "refused by the import service");
            if (throwingImports.contains(id)) throw new RuntimeException("the import service threw after reading the content");
            importedIds.add(id);
            return ExternalIngestResult.success("r", "obj-" + id, "1.0", false, null);
        });
        orchestrator.setFetchSupport(fetchSupport);
        orchestrator.setCheckpointManager(checkpointManager);
        orchestrator.setCanonicalImportService(importService);
        orchestrator.adapterFactory = token -> new ChatworkConnectorAdapter(token, base);
        return orchestrator;
    }

    private void checkpointIs(String stored) {
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(stored);
    }

    private String savedCheckpoint() {
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(checkpointManager).saveSimpleCheckpoint(eq("p-cw"), eq("chatwork.R1"), saved.capture());
        return saved.getValue();
    }

    private static ImportProfileDefinition profile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-cw");
        profile.setRepositoryId("bedroom");
        return profile;
    }

    private static ConnectorDefinition connector() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-cw");
        connector.setSourceSystem("chatwork");
        return connector;
    }

    private static final Map<String, String> ROOM = Map.of("roomId", "R1");

    // ── order and budget ──────────────────────────────────────────

    /** Whatever order the room's messages come in, the ones newer than the checkpoint are taken oldest first. */
    @Test
    @DisplayName("Chatwork: the messages newer than the checkpoint are taken oldest first, within the limit")
    void chatworkNewMessagesAreTakenOldestFirst() {
        messagesAnswer = answer("1003", "1001", "1002", "999", "1000");
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("1000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), ROOM, 2);

        assertEquals(List.of("1001", "1002"), importedIds);
        assertEquals("1002", savedCheckpoint());
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("limit of 2")), result.incompleteReads().toString());
    }

    // ── the gap ───────────────────────────────────────────────────

    /**
     * A full window whose oldest message is newer than the checkpoint: the messages between cannot
     * be fetched. The gap is recorded, and the run goes on — it used to stop, and every poll after it.
     */
    @Test
    @DisplayName("Chatwork: a full window past the checkpoint records the gap and goes on")
    void chatworkAFullWindowPastTheCheckpointRecordsTheGapAndGoesOn() {
        messagesAnswer = window(2001);
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("1000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), ROOM, 200);

        assertEquals(1, dlqNeverReadRequests.size(), "the gap was not recorded: " + dlqNeverRead);
        ExternalIngestRequest gap = dlqNeverReadRequests.get(0);
        assertEquals(ChatworkFetchOrchestrator.GAP_TYPE, gap.getSourceObjectType());
        assertEquals("1000", gap.getMetadata().get("gapAfterMessageId"));
        assertEquals("2001", gap.getMetadata().get("gapBeforeMessageId"));
        assertEquals(ChatworkFetchOrchestrator.WINDOW, importedIds.size(), "the run stopped at the gap: " + importedIds);
        assertEquals("2100", savedCheckpoint());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("cannot be fetched")), result.errors().toString());
    }

    /** Fewer messages than the window: the room's whole history was answered, so a missing checkpoint message is no gap. */
    @Test
    @DisplayName("Chatwork: an answer shorter than the window is no gap, even when the checkpoint's message is gone")
    void chatworkAnAnswerShorterThanTheWindowIsNoGap() {
        messagesAnswer = answer("1005", "1006");
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("1000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), ROOM, 10);

        assertTrue(dlqNeverRead.isEmpty(), "a gap was recorded for a short answer: " + dlqNeverRead);
        assertEquals(List.of("1005", "1006"), importedIds);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
    }

    @Test
    @DisplayName("Chatwork: a gap that cannot be dead-lettered holds the checkpoint, and nothing is taken")
    void chatworkAGapThatCannotBeRecordedHoldsTheCheckpoint() {
        dlqWritable = false;
        messagesAnswer = window(2001);
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("1000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), ROOM, 200);

        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    // ── the answer ────────────────────────────────────────────────

    /** A message whose id is not a number cannot be placed: the answer is refused, not read around. */
    @Test
    @DisplayName("Chatwork: a message without a numeric id refuses the answer")
    void chatworkAMessageWithoutANumericIdRefusesTheAnswer() {
        messagesAnswer = answer("1001", "abc", "1002");
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("1000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), ROOM, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without a numeric id")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Chatwork: an answer that is not a list is an error, not an empty room")
    void chatworkAnAnswerThatIsNotAListIsAnError() {
        messagesAnswer = "{\"errors\":[\"x\"]}";
        FetchResult result = chatwork().execute(null, profile(), connector(), ROOM, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("something other than a list")), result.errors().toString());
    }

    @Test
    @DisplayName("Chatwork: a stored checkpoint that is not a message id is an error")
    void chatworkACorruptedCheckpointIsAnError() {
        messagesAnswer = answer("1001");
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("yesterday");

        FetchResult result = orchestrator.execute(null, profile(), connector(), ROOM, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("corrupted")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
    }

    // ── one message ───────────────────────────────────────────────

    /** A refused import is dead-lettered with the message's text — replayable — and passed. */
    @Test
    @DisplayName("Chatwork: a refused import is dead-lettered with the message's text, and passed")
    void chatworkARefusedImportIsDeadLetteredWithItsText() {
        failingImports = List.of("1001");
        messagesAnswer = answer("1001", "1002");
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("1000");

        orchestrator.execute(null, profile(), connector(), ROOM, 10);

        assertEquals(1, dlqRead.size(), "the refused import was not recorded: " + dlqRead);
        assertEquals("text of 1001", new String(dlqBytes.getOrDefault("1001", new byte[0]), StandardCharsets.UTF_8));
        assertEquals(List.of("1002"), importedIds);
        assertEquals("1002", savedCheckpoint());
    }

    @Test
    @DisplayName("Chatwork: an import that throws is dead-lettered with the message's text")
    void chatworkAnImportThatThrowsIsDeadLetteredWithItsText() {
        throwingImports = List.of("1001");
        messagesAnswer = answer("1001");
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("1000");

        orchestrator.execute(null, profile(), connector(), ROOM, 10);

        assertEquals("text of 1001", new String(dlqBytes.getOrDefault("1001", new byte[0]), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Chatwork: a failure that cannot be dead-lettered stops the run with the checkpoint before it")
    void chatworkAFailureThatCannotBeRecordedHoldsTheCheckpoint() {
        dlqWritable = false;
        failingImports = List.of("1002");
        messagesAnswer = answer("1001", "1002", "1003");
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("1000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), ROOM, 10);

        assertEquals(List.of("1001"), importedIds, "a message past the unrecorded failure was imported: " + importedIds);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        assertEquals("1001", savedCheckpoint());
    }

    @Test
    @DisplayName("Chatwork: the attempts of one run are bounded by four times the limit")
    void chatworkTheAttemptsOfOneRunAreBounded() {
        failingImports = List.of("1001", "1002", "1003", "1004", "1005");
        messagesAnswer = answer("1001", "1002", "1003", "1004", "1005");
        ChatworkFetchOrchestrator orchestrator = chatwork();
        checkpointIs("1000");

        FetchResult result = orchestrator.execute(null, profile(), connector(), ROOM, 1);

        assertEquals(4, dlqRead.size(), "the attempts were not bounded at 4 × the limit: " + dlqRead);
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("4 × the limit of 1")), result.incompleteReads().toString());
        assertEquals("1004", savedCheckpoint());
    }
}
