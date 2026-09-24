package jp.aegif.nemaki.rest.ingest.mail;

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
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * An M365 mail folder is followed through Graph's mail delta feed, page by page (R107).
 *
 * <p>The previous orchestrator listed the folder newest first, cut it at the run's limit and
 * raised a received-time checkpoint over the rest; a received-time watermark also never sees a
 * message MOVED into the folder after the checkpoint passed its received time. What is measured
 * here is the REAL adapter pointed at a local stub of Graph — the delta feed and its links — and
 * the orchestrator's start, mailbox binding, page-by-page progress, budget, dead-letter answers
 * and the checkpoint it saves. The folder listing is never read.
 */
class M365MailFolderIsFollowedThroughTheDeltaFeedTest {

    private static HttpServer server;
    private static String base;
    private static String previousAllowLocalhost;

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, int callNumber) throws IOException;
    }

    private static volatile Responder deltaPages;
    private static volatile List<String> failingFetches = List.of();
    /** Calls to the folder LISTING — the orchestrator must not use it. */
    private static final AtomicInteger LISTING_CALLS = new AtomicInteger();
    private static final AtomicInteger DELTA_CALLS = new AtomicInteger();
    /** The delta links asked for, as full URLs as sent, in order. */
    private static final List<String> DELTA_LINKS = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The Prefer header of the last delta request. */
    private static volatile String lastPrefer;

    private static final String USER = "u@x.com";
    /** The same mailbox named by its object id. */
    private static final String OBJECT_ID = "0f0e0d0c-0000-4000-8000-000000000001";
    /** The id Graph answers for the inbox — what a checkpoint is bound to. */
    private static final String FOLDER_ID = "AAMkInbox";
    /** What the folder read answers: an id, or (null) a failure. */
    private static volatile String folderAnswer = FOLDER_ID;
    private static final AtomicInteger FOLDER_CALLS = new AtomicInteger();
    /** The inbox in the spelling Graph's own links may use: the key syntax, the folder by id. */
    private static final String GRAPH_SPELLED_FEED = "/v1.0/users('u@x.com')/mailfolders('AAMkInbox')/messages/delta()";

    @BeforeAll
    static void startStub() throws Exception {
        previousAllowLocalhost = System.getProperty("nemaki.ingest.allowLocalhost");
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String feed : List.of("/v1.0/users/" + USER + "/mailFolders/inbox/messages/delta", GRAPH_SPELLED_FEED)) {
            server.createContext(feed, exchange -> {
                DELTA_LINKS.add(base + exchange.getRequestURI().toString());
                lastPrefer = exchange.getRequestHeaders().getFirst("Prefer");
                deltaPages.respond(exchange, DELTA_CALLS.incrementAndGet());
            });
        }
        // The inbox by its id, and another folder: what a link in another spelling, or into
        // another folder, is resolved to.
        server.createContext("/v1.0/users/" + USER + "/mailFolders/AAMkInbox", exchange -> json(exchange, 200, "{\"id\":\"" + FOLDER_ID + "\"}"));
        server.createContext("/v1.0/users/other@x.com/mailFolders/inbox", exchange -> json(exchange, 200, "{\"id\":\"AAMkOthersInbox\"}"));
        server.createContext("/v1.0/users/" + USER + "/mailFolders/Other", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/mailFolders/Other")) json(exchange, 200, "{\"id\":\"AAMkOther\"}");
            else deltaPages.respond(exchange, DELTA_CALLS.incrementAndGet());
        });
        for (String mailbox : List.of(USER, OBJECT_ID)) {
            server.createContext("/v1.0/users/" + mailbox + "/mailFolders/inbox", exchange -> {
                if (!exchange.getRequestURI().getPath().endsWith("/mailFolders/inbox")) {
                    json(exchange, 404, "{\"error\":\"not here\"}");
                    return;
                }
                FOLDER_CALLS.incrementAndGet();
                if (folderAnswer == null) json(exchange, 500, "{\"error\":\"boom\"}");
                else json(exchange, 200, "{\"id\":\"" + folderAnswer + "\"}");
            });
        }
        server.createContext("/v1.0/users/" + USER + "/mailFolders/inbox/messages", exchange -> {
            LISTING_CALLS.incrementAndGet();
            json(exchange, 200, "{\"value\":[]}");
        });
        for (String mailbox : List.of(USER, OBJECT_ID)) {
            server.createContext("/v1.0/users/" + mailbox + "/messages/", exchange -> {
                String[] parts = exchange.getRequestURI().getPath().split("/");
                String id = parts[parts.length - 2];
                if (failingFetches.contains(id)) {
                    json(exchange, 500, "{\"error\":\"boom\"}");
                } else {
                    byte[] out = ("From: a@x.com\r\nSubject: " + id + "\r\n\r\nbody of " + id).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "message/rfc822");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                    exchange.close();
                }
            });
        }
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
        LISTING_CALLS.set(0);
        DELTA_CALLS.set(0);
        FOLDER_CALLS.set(0);
        folderAnswer = FOLDER_ID;
        skippedWithMissingAttachments = List.of();
        warnedImports = Map.of();
        DELTA_LINKS.clear();
        lastPrefer = null;
        failingFetches = List.of();
        dlqWritable = true;
        failingImports = List.of();
        throwingImports = List.of();
        skippingImports = List.of();
        attachmentWarningImports = List.of();
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

    private static String msg(String id) {
        return "{\"id\":\"" + id + "\",\"internetMessageId\":\"<" + id + "@x>\",\"subject\":\"s " + id + "\","
                + "\"from\":{\"emailAddress\":{\"address\":\"a@x.com\"}},\"receivedDateTime\":\"2026-01-01T00:00:01Z\"}";
    }

    /** A delta link on the stub; {@code kind} is "skip" (a nextLink) or "delta" (a deltaLink). */
    private static String link(String kind, String token) {
        return base + "/v1.0/users/" + USER + "/mailFolders/inbox/messages/delta?$" + kind + "token=" + token;
    }

    private static String deltaPage(String kind, String token, String... msgs) {
        return pageLinking(link(kind, token), "skip".equals(kind), msgs);
    }

    private static String pageLinking(String link, boolean next, String... msgs) {
        return "{\"value\":[" + String.join(",", msgs) + "],\"@odata." + (next ? "nextLink" : "deltaLink") + "\":\"" + link + "\"}";
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
    private List<String> throwingImports = List.of();
    private List<String> skippingImports = List.of();
    /** Ids the import answers "imported, but an attachment failed" for. */
    private List<String> attachmentWarningImports = List.of();
    /** Ids the import answers "already imported" for, with an attachment it tried again and could not import. */
    private List<String> skippedWithMissingAttachments = List.of();
    /** Ids the import answers "imported" for, with the given warnings — the import's own wording. */
    private Map<String, List<String>> warnedImports = Map.of();

    private M365MailFetchOrchestrator m365() {
        M365MailFetchOrchestrator orchestrator = new M365MailFetchOrchestrator();
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        importedIds.clear();
        dlqReasons.clear();
        dlqReadReasons.clear();
        dlqRequests.clear();
        lenient().when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("graph-token");
        lenient().doNothing().when(fetchSupport).throttle(anyLong());
        doCallRealMethod().when(fetchSupport).buildMailRequest(any(), any(), any(), any(), any(), any());
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
        lenient().when(importService.executeMailImport(any(), any())).thenAnswer(call -> {
            ExternalIngestRequest req = call.getArgument(1);
            String id = req.getSourceObjectId();
            if (failingImports.contains(id)) return ExternalIngestResult.error("r", "refused by the import service");
            if (throwingImports.contains(id)) throw new RuntimeException("the import service threw after reading the content");
            if (skippingImports.contains(id)) return ExternalIngestResult.skipped("r", "obj-" + id, "already imported");
            if (warnedImports.containsKey(id)) {
                importedIds.add(id);
                return new ExternalIngestResult("r", "obj-" + id, "1.0", false, false, false, null, null, List.of(), warnedImports.get(id));
            }
            if (skippedWithMissingAttachments.contains(id)) {
                return new ExternalIngestResult("r", "obj-" + id, "1.0", false, false, true, "already imported", null, List.of(),
                        List.of("Attachment 'a.pdf' import failed: boom"));
            }
            importedIds.add(id);
            List<String> warnings = attachmentWarningImports.contains(id)
                    ? List.of("Attachment 'a.pdf' import failed: boom") : List.of();
            return new ExternalIngestResult("r", "obj-" + id, "1.0", false, false, false, null, null, List.of(), warnings);
        });
        orchestrator.setFetchSupport(fetchSupport);
        orchestrator.setCheckpointManager(checkpointManager);
        orchestrator.setCanonicalImportService(importService);
        orchestrator.adapterFactory = (token, userId) -> new M365MailConnectorAdapter(token, userId,
                jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared(), base + "/v1.0");
        return orchestrator;
    }

    private void checkpointIs(String stored) {
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(stored);
    }

    private String savedCheckpoint() {
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(checkpointManager).saveSimpleCheckpoint(eq("p-m365"), eq(KEY), saved.capture());
        return saved.getValue();
    }

    private static ImportProfileDefinition profile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-m365");
        profile.setRepositoryId("bedroom");
        profile.setSchedulerParams(Map.of("userId", USER, "folderId", "inbox"));
        return profile;
    }

    private static ConnectorDefinition connector() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-m365");
        connector.setSourceSystem("m365_mail");
        return connector;
    }

    private static final Map<String, String> FOLDER = Map.of("folderId", "inbox");
    private static final String KEY = "m365mail.inbox";

    private String stored(String kind, String token) {
        return "delta:" + FOLDER_ID + "|" + link(kind, token);
    }

    private static ImportProfileDefinition profileReading(String userId) {
        ImportProfileDefinition profile = profile();
        profile.setSchedulerParams(Map.of("userId", userId, "folderId", "inbox"));
        return profile;
    }

    // ── where the feed starts ──────────────────────────────────────

    @Test
    @DisplayName("M365: a fresh profile reads the folder's whole delta feed, not the listing, and saves the link with its folder")
    void m365AFreshProfileReadsTheWholeFeedAndSavesTheLinkWithItsFolder() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1"), msg("m2")));
        FetchResult result = m365().execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(0, LISTING_CALLS.get(), "the folder listing was read");
        assertEquals(List.of(base + "/v1.0/users/u%40x.com/mailFolders/inbox/messages/delta?$select=id,internetMessageId,subject,from,receivedDateTime"),
                DELTA_LINKS, "the feed was not read from its start");
        assertEquals("odata.maxpagesize=50", lastPrefer);
        assertEquals(List.of("m1", "m2"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    /**
     * The received-time checkpoint an earlier version wrote: the WHOLE folder is read again, with no
     * {@code $filter} — Graph caps a filtered delta query at 5,000 messages and ends the cut round
     * like a complete one (review, P1).
     */
    @Test
    @DisplayName("M365: a legacy received-time checkpoint reads the whole folder again, with no filter")
    void m365ALegacyCheckpointReadsTheWholeFolderAgain() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs("2026-01-01T00:00:01Z");

        orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(List.of(base + "/v1.0/users/u%40x.com/mailFolders/inbox/messages/delta?$select=id,internetMessageId,subject,from,receivedDateTime"),
                DELTA_LINKS, "the legacy time was sent as a filter, which Graph caps at 5,000 messages");
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("M365: a stored checkpoint that is neither a delta link nor a timestamp is an error, not 'no checkpoint'")
    void m365AStoredCheckpointThatCannotBeReadIsAnError() {
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs("last tuesday");

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("last tuesday") && e.contains("neither a delta link nor a timestamp")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get() + LISTING_CALLS.get());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * The folder now answers another id — the token belongs to someone else (both are "me"), or the
     * userId names another mailbox: the checkpoint's link reads the folder it was written for, so it
     * is an error, not a place to continue from (review, P2).
     */
    @Test
    @DisplayName("M365: a checkpoint written for another folder is not followed")
    void m365ACheckpointWrittenForAnotherFolderIsNotFollowed() {
        folderAnswer = "AAMkBobsInbox";
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("written for the folder '" + FOLDER_ID + "'")
                && e.contains("'AAMkBobsInbox'")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get(), "another folder's feed was read: " + DELTA_LINKS);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** Graph's ids are case-sensitive: an id in another case is another folder. */
    @Test
    @DisplayName("M365: the checkpoint's folder is compared exactly")
    void m365TheCheckpointFolderIsComparedExactly() {
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs("delta:aamkinbox|" + link("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("written for the folder 'aamkinbox'")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get());
    }

    /** The same mailbox named by its object id instead of its UPN answers the same folder: the feed goes on (review, P2). */
    @Test
    @DisplayName("M365: the same mailbox named by its object id continues the feed")
    void m365TheSameMailboxByItsObjectIdContinuesTheFeed() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m7")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profileReading(OBJECT_ID), connector(), FOLDER, 10);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(List.of("m7"), importedIds);
        // Its own folder, then the stored link's and the answered link's: the links name the
        // mailbox by its UPN, so each is asked for the id of the folder it reads.
        assertEquals(3, FOLDER_CALLS.get());
    }

    /** The folder cannot be read: the stored link cannot be checked against it, so nothing is read. */
    @Test
    @DisplayName("M365: a folder whose id cannot be read is an error, and nothing is read")
    void m365AFolderWhoseIdCannotBeReadIsAnError() {
        folderAnswer = null;
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("reading the mail folder")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("M365: a stored link on another host is not followed")
    void m365AStoredLinkOnAnotherHostIsNotFollowed() {
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs("delta:" + USER + "|http://graph.example.invalid:" + server.getAddress().getPort()
                + "/v1.0/users/" + USER + "/mailFolders/inbox/messages/delta?$deltatoken=t1");

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("not a delta link this connector wrote")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("M365: a stored delta value that names no folder is not followed")
    void m365AStoredValueThatNamesNoFolderIsNotFollowed() {
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs("delta:" + link("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("not a delta link this connector wrote")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get());
    }

    /** Graph may spell the feed its own way — the key syntax, the folder by id: the same feed, followed and saved. */
    @Test
    @DisplayName("M365: a nextLink in Graph's own spelling is followed and saved")
    void m365AGraphLinkInItsOwnSpellingIsFollowed() {
        String graphLink = base + GRAPH_SPELLED_FEED + "?$skiptoken=k1";
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, pageLinking(graphLink, true, msg("m7")));
            else json(exchange, 200, deltaPage("delta", "t2", msg("m8")));
        };
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertFalse(result.hasErrors(), "Graph's own link was refused: " + result.errors());
        assertEquals(graphLink, DELTA_LINKS.get(1));
        assertEquals(List.of("m7", "m8"), importedIds);
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    // ── following the feed ─────────────────────────────────────────

    @Test
    @DisplayName("M365: a round follows nextLinks and saves the deltaLink that ends it")
    void m365ARoundFollowsNextLinksThenSavesTheDeltaLink() {
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("m7")));
            else json(exchange, 200, deltaPage("delta", "t2", msg("m8")));
        };
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(link("skip", "s1"), DELTA_LINKS.get(1), "the nextLink was not followed");
        assertEquals(List.of("m7", "m8"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("M365: a run cut at the request cap saves the link it reached")
    void m365ARunCutAtTheCapSavesTheLinkItReached() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("skip", "s" + n, msg("m" + n)));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(),
                Map.of("folderId", "inbox", M365MailFetchOrchestrator.PARAM_MAX_MESSAGE_REQUESTS, "1"), 10);

        assertEquals(1, DELTA_CALLS.get());
        assertEquals(List.of("m1"), importedIds);
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("m365MessageMaxRequests")), result.incompleteReads().toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("skip", "s1"), savedCheckpoint());
    }

    @Test
    @DisplayName("M365: the budget stops inside a page without passing it")
    void m365TheBudgetStopsInsideAPageWithoutPassingIt() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m7"), msg("m8")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 1);

        assertEquals(List.of("m7"), importedIds);
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("read again next poll")), result.incompleteReads().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("M365: the budget reached at a page boundary passes the page and asks for no further one")
    void m365TheBudgetReachedAtAPageBoundaryReadsNoFurther() {
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("m7")));
            else json(exchange, 200, deltaPage("delta", "t2", msg("m8")));
        };
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 1);

        assertEquals(1, DELTA_CALLS.get(), DELTA_LINKS.toString());
        assertEquals(List.of("m7"), importedIds);
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("continues from the saved link")), result.incompleteReads().toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("skip", "s1"), savedCheckpoint());
    }

    @Test
    @DisplayName("M365: a page read again spends no budget on the messages it already imported")
    void m365APageReadAgainSpendsNoBudgetOnWhatItSkips() {
        skippingImports = List.of("m7");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m7"), msg("m8")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 1);

        assertEquals(List.of("m8"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("M365: a failure that could not be dead-lettered holds its page; the pages before it stay passed")
    void m365AnUnrecordedFailureHoldsThePage() {
        dlqWritable = false;
        failingFetches = List.of("bad");
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("m6")));
            else json(exchange, 200, deltaPage("delta", "t2", msg("bad")));
        };
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("skip", "s1"), savedCheckpoint());
    }

    /** A MIME that could not be fetched: a never-read row naming the message and its mailbox; the page passes. */
    @Test
    @DisplayName("M365: a failed fetch is dead-lettered as never read and the page is passed")
    void m365AFailedFetchIsDeadLetteredNeverReadAndThePagePasses() {
        failingFetches = List.of("bad");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("bad"), msg("m8")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertTrue(dlqReasons.get(0).contains("bad") && dlqReadReasons.isEmpty(), "expected a never-read row: " + dlqReasons);
        assertEquals("bad", dlqRequests.get(0).getSourceObjectId());
        assertEquals(USER, dlqRequests.get(0).getMetadata().get("m365Mailbox"));
        assertEquals(List.of("m8"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("M365: a message removed from the folder (@removed) is skipped, not imported")
    void m365ARemovedMessageIsSkipped() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", "{\"id\":\"gone\",\"@removed\":{\"reason\":\"deleted\"}}", msg("m7")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(List.of("m7"), importedIds);
        assertEquals(1, result.skipped(), result.toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    /** An attachment imported and not linked to its message is a part missing too: the import says "Relationship failed" (review, P1). */
    @Test
    @DisplayName("M365: a message whose attachment could not be linked is recorded")
    void m365AMessageWhoseAttachmentLinkFailedIsRecorded() {
        warnedImports = Map.of("m1", List.of("Relationship failed: boom"));
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(1, dlqReadReasons.size(), "the failed link was not recorded: " + dlqReasons);
        assertTrue(dlqReadReasons.get(0).contains("Relationship failed"), dlqReadReasons.toString());
    }

    /**
     * The import's warnings about evidence — an attachment's provenance, say — do not mean a part
     * is missing: recording them dead-lettered complete mail that could not be replayed (review, P1).
     */
    @Test
    @DisplayName("M365: a warning about evidence is not a missing part")
    void m365AnEvidenceWarningIsNotAMissingPart() {
        warnedImports = Map.of("m1", List.of("attachment 'a.pdf': Provenance was NOT recorded for this document (x)",
                "Provenance was NOT recorded for this document (y)"));
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(dlqReasons.isEmpty(), "a complete mail was dead-lettered for a warning about evidence: " + dlqReasons);
        assertEquals(1, result.imported(), result.toString());
    }

    /** A stored link of the right shape into another folder: the prefix names this folder, the link reads another (review, P1). */
    @Test
    @DisplayName("M365: a stored link that reads another folder is not followed")
    void m365AStoredLinkThatReadsAnotherFolderIsNotFollowed() {
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs("delta:" + FOLDER_ID + "|" + base + "/v1.0/users/" + USER + "/mailFolders/Other/messages/delta?$deltatoken=t1");

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("reads another folder")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get(), "another folder's feed was read: " + DELTA_LINKS);
    }

    /** Another mailbox's inbox: the folder segment is the same name, the mailbox is not — resolved, and refused. */
    @Test
    @DisplayName("M365: a stored link into another mailbox's folder of the same name is not followed")
    void m365AStoredLinkIntoAnotherMailboxsFolderIsNotFollowed() {
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs("delta:" + FOLDER_ID + "|" + base + "/v1.0/users/other@x.com/mailFolders/inbox/messages/delta?$deltatoken=t1");

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("reads another folder")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get(), "another mailbox's feed was read: " + DELTA_LINKS);
    }

    /** The form an earlier build of this version wrote names the mailbox: refused, and said to be that (review, P2). */
    @Test
    @DisplayName("M365: a checkpoint an earlier build wrote with the mailbox is refused as such")
    void m365ACheckpointFromAnEarlierBuildIsRefusedAsSuch() {
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs("delta:" + USER + "|" + link("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("earlier build")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get());
    }

    /** Graph answers a link into another folder: the page is not passed on it. */
    @Test
    @DisplayName("M365: a page whose link reads another folder is refused, nothing on it imported")
    void m365APageWhoseLinkReadsAnotherFolderIsRefused() {
        deltaPages = (exchange, n) -> json(exchange, 200, pageLinking(base + "/v1.0/users/" + USER + "/mailFolders/Other/messages/delta?$skiptoken=s1",
                true, msg("m1")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("reads another folder")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** A removal that names nothing is not one this connector can account for: the page is not passed on it (review, P3). */
    @Test
    @DisplayName("M365: a removed entry without an id holds its page, nothing on it imported")
    void m365ARemovedEntryWithoutAnIdHoldsThePage() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", "{\"@removed\":{\"reason\":\"deleted\"}}", msg("m1")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("removed entry without an id")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A message imported before comes again; the import service skips it and tries its missing
     * attachments again, and one still fails. Passed as a skip, nothing recorded it (review, P1):
     * a read row, and the page passes on the record.
     */
    @Test
    @DisplayName("M365: an already imported message whose attachments are still missing is recorded")
    void m365AnAlreadyImportedMessageWithAttachmentsStillMissingIsRecorded() {
        skippedWithMissingAttachments = List.of("m1");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(1, dlqReadReasons.size(), "the missing attachment was not recorded: " + dlqReasons);
        assertTrue(dlqReadReasons.get(0).contains("still missing"), dlqReadReasons.toString());
        assertEquals(1, result.skipped(), result.toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("M365: a page without a nextLink or deltaLink is refused")
    void m365APageWithoutALinkIsRefused() {
        deltaPages = (exchange, n) -> json(exchange, 200, "{\"value\":[" + msg("m7") + "]}");
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("@odata.nextLink or @odata.deltaLink")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("M365: a page whose nextLink is its own link is refused")
    void m365APageLinkingToItselfIsRefused() {
        deltaPages = (exchange, n) -> json(exchange, 200, "{\"value\":[],\"@odata.nextLink\":\"" + link("delta", "t1") + "\"}");
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(1, DELTA_CALLS.get(), DELTA_LINKS.toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("own link")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("M365: a page without a value array is refused")
    void m365APageWithoutValueIsRefused() {
        deltaPages = (exchange, n) -> json(exchange, 200, "{\"@odata.deltaLink\":\"" + link("delta", "t2") + "\"}");
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without a value array")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("M365: a page linking out of the mail delta feed is refused, nothing on it imported")
    void m365APageLinkingOutOfTheMailFeedIsRefused() {
        // the folder's CHILD-FOLDERS delta: a real Graph feed, of the same length, and not this one
        deltaPages = (exchange, n) -> json(exchange, 200, pageLinking(base + "/v1.0/users/" + USER + "/mailFolders/inbox/childFolders/delta?$skiptoken=x", true, msg("m7")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        // The feed's own words, not the folder lookup's: that lookup also refuses a link out of
        // the feed, as a "connection failed" — which tells the operator the wrong thing.
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("answered a link that is not a mail delta link")),
                result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("M365: a message without an id holds its page, nothing on it imported")
    void m365AMessageWithoutAnIdHoldsThePage() {
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m7"), "{\"subject\":\"no id\"}"));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without an id")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("M365: the attempts of one run are bounded by four times the limit, counted between pages")
    void m365TheAttemptsOfOneRunAreBoundedBetweenPages() {
        failingImports = List.of("f1", "f2", "f3", "f4", "f5");
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("f1"), msg("f2"), msg("f3")));
            else if (n == 2) json(exchange, 200, deltaPage("skip", "s2", msg("f4"), msg("f5")));
            else json(exchange, 200, deltaPage("delta", "t3", msg("m9")));
        };
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 1);

        assertEquals(2, DELTA_CALLS.get(), DELTA_LINKS.toString());
        assertEquals(5, dlqReadReasons.size(), dlqReasons.toString());
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("4 × the limit of 1")), result.incompleteReads().toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("skip", "s2"), savedCheckpoint());
    }

    @Test
    @DisplayName("M365: a request-cap parameter that is not a number is reported and nothing is read")
    void m365ABadCapParameterIsReported() {
        FetchResult result = m365().execute(null, profile(), connector(),
                Map.of("folderId", "inbox", M365MailFetchOrchestrator.PARAM_MAX_MESSAGE_REQUESTS, "fifty"), 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("m365MessageMaxRequests")), result.errors().toString());
        assertEquals(0, DELTA_CALLS.get());
    }

    @Test
    @DisplayName("M365: a failed request keeps the pages already passed")
    void m365AFailedRequestKeepsThePagesAlreadyPassed() {
        deltaPages = (exchange, n) -> {
            if (n == 1) json(exchange, 200, deltaPage("skip", "s1", msg("m7")));
            else json(exchange, 400, "{\"error\":{\"code\":\"BadRequest\"}}");
        };
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("connection failed")), result.errors().toString());
        assertEquals(List.of("m7"), importedIds);
        assertEquals("delta:" + FOLDER_ID + "|" + link("skip", "s1"), savedCheckpoint());
    }

    // ── one message ────────────────────────────────────────────────

    @Test
    @DisplayName("M365: an import that answers an error is dead-lettered as read")
    void m365AnImportThatAnswersAnErrorIsDeadLetteredAsRead() {
        failingImports = List.of("m1");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1"), msg("m2")));
        FetchResult result = m365().execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(1, dlqReadReasons.size(), dlqReasons.toString());
        assertEquals(List.of("m2"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    @Test
    @DisplayName("M365: an import that throws after the read is dead-lettered as read, not never-read")
    void m365AnImportThatThrowsIsDeadLetteredAsRead() {
        throwingImports = List.of("m1");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1"), msg("m2")));
        FetchResult result = m365().execute(null, profile(), connector(), FOLDER, 10);

        assertEquals(1, dlqReadReasons.size(), "the thrown import was not recorded as a READ item: " + dlqReasons);
        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertEquals(List.of("m2"), importedIds);
        assertEquals("delta:" + FOLDER_ID + "|" + link("delta", "t2"), savedCheckpoint());
    }

    /**
     * A message imported without some of its attachments was passed silently before — the
     * attachment lost once a newer message moved the checkpoint. It is recorded now as read, and
     * it spends the budget: the document is there.
     */
    @Test
    @DisplayName("M365: a message imported without an attachment is recorded, and spends the budget")
    void m365AMessageImportedWithoutAnAttachmentIsRecordedAndSpendsTheBudget() {
        attachmentWarningImports = List.of("m1", "m2");
        deltaPages = (exchange, n) -> json(exchange, 200, deltaPage("delta", "t2", msg("m1"), msg("m2")));
        M365MailFetchOrchestrator orchestrator = m365();
        checkpointIs(stored("delta", "t1"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), FOLDER, 1);

        assertEquals(List.of("m1"), importedIds, "a message past the limit was imported: " + importedIds);
        assertEquals(1, dlqReadReasons.size(), dlqReasons.toString());
        assertTrue(dlqReadReasons.get(0).contains("without some of its parts"), dlqReadReasons.toString());
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("read again next poll")), result.incompleteReads().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }
}
