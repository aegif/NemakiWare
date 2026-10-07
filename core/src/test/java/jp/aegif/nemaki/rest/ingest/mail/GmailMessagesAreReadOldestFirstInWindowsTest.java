package jp.aegif.nemaki.rest.ingest.mail;

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
import jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Gmail: the messages a query matches are read from the checkpoint in time windows, oldest first
 * (R107).
 *
 * <p>The previous orchestrator listed the newest {@code limit} messages after a DAY and raised the
 * day to the newest one it had seen. What is measured here is the REAL adapter pointed at a local
 * stub of the Gmail API — whose search reads {@code after:} and {@code before:} as exclusive (or,
 * where a lock needs it, inclusive: Gmail documents neither), and which lists newest first or out of
 * order — and
 * the orchestrator's windows, order, budget, dead-letter answers and the checkpoint it saves.
 */
class GmailMessagesAreReadOldestFirstInWindowsTest {

    private static HttpServer server;
    private static String base;

    /** A message in the stub mailbox. */
    record StubMessage(String id, long internalDate) {}

    private static volatile List<StubMessage> mailbox = List.of();
    /** Listed out of date order (rotated), rather than newest first. */
    private static volatile boolean scrambled;
    private static volatile Set<String> failingSummaries = Set.of();
    private static volatile Set<String> undatedSummaries = Set.of();
    private static volatile Set<String> failingRaws = Set.of();
    /** The listing carries a message without an id. */
    private static volatile boolean idlessListing;
    /** Every page answers the same next-page token: a listing that does not advance. */
    private static volatile boolean stuckToken;
    /** The search reads both bounds as inclusive — the other reading Gmail leaves open. */
    private static volatile boolean inclusiveBounds;
    /** Ids whose first summary read is answered 429 (rate limited); the ones after are answered. */
    private static volatile Set<String> rateLimitedOnce = Set.of();
    private static final Set<String> RATE_LIMITED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Ids whose first summary read is answered 503 (unavailable); the ones after are answered. */
    private static volatile Set<String> unavailableOnce = Set.of();
    private static final Set<String> UNAVAILABLE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** The search strings asked for, in order. */
    private static final List<String> LIST_QUERIES = new CopyOnWriteArrayList<>();
    /** The page tokens asked for, in order. */
    private static final List<String> PAGE_TOKENS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger SUMMARY_CALLS = new AtomicInteger();

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Pattern AFTER = Pattern.compile("after:(\\d+)");
    private static final Pattern BEFORE = Pattern.compile("before:(\\d+)");

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gmail/v1/users/me/messages", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Map<String, List<String>> params = params(exchange.getRequestURI().getRawQuery());
            if (path.equals("/gmail/v1/users/me/messages")) {
                list(exchange, params);
            } else {
                get(exchange, path.substring(path.lastIndexOf('/') + 1), params);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void reset() {
        mailbox = List.of();
        scrambled = false;
        failingSummaries = Set.of();
        undatedSummaries = Set.of();
        failingRaws = Set.of();
        idlessListing = false;
        stuckToken = false;
        inclusiveBounds = false;
        rateLimitedOnce = Set.of();
        RATE_LIMITED.clear();
        unavailableOnce = Set.of();
        UNAVAILABLE.clear();
        warnedImports = Map.of();
        LIST_QUERIES.clear();
        PAGE_TOKENS.clear();
        SUMMARY_CALLS.set(0);
        dlqWritable = true;
        failingImports = List.of();
        throwingImports = List.of();
        skippingImports = List.of();
        attachmentWarningImports = List.of();
        skippedWithMissingAttachments = List.of();
    }

    private static Map<String, List<String>> params(String rawQuery) {
        Map<String, List<String>> out = new HashMap<>();
        if (rawQuery == null) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    private static String first(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static Long bound(String q, Pattern pattern) {
        Matcher m = pattern.matcher(q == null ? "" : q);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    private static void list(HttpExchange exchange, Map<String, List<String>> params) throws IOException {
        String q = first(params, "q");
        LIST_QUERIES.add(q);
        String pageToken = first(params, "pageToken");
        if (pageToken != null) PAGE_TOKENS.add(pageToken);
        if (idlessListing) {
            json(exchange, 200, "{\"messages\":[{\"threadId\":\"t\"}],\"resultSizeEstimate\":1}");
            return;
        }
        Long after = bound(q, AFTER);
        Long before = bound(q, BEFORE);
        List<StubMessage> matching = new ArrayList<>();
        for (StubMessage m : mailbox) {
            long second = Math.floorDiv(m.internalDate(), 1000);
            boolean afterHolds = after == null || (inclusiveBounds ? second >= after : second > after);
            boolean beforeHolds = before == null || (inclusiveBounds ? second <= before : second < before);
            if (afterHolds && beforeHolds) matching.add(m);
        }
        matching.sort(Comparator.comparingLong(StubMessage::internalDate).reversed());
        if (scrambled) {
            matching.sort(Comparator.comparingLong(StubMessage::internalDate));
            Collections.rotate(matching, matching.size() / 2);
        }
        int size = Integer.parseInt(first(params, "maxResults"));
        int offset = pageToken == null ? 0 : Integer.parseInt(pageToken.substring(1));
        List<StubMessage> page = matching.subList(Math.min(offset, matching.size()), Math.min(offset + size, matching.size()));
        StringBuilder body = new StringBuilder("{");
        if (!page.isEmpty()) {
            List<String> refs = new ArrayList<>();
            for (StubMessage m : page) refs.add("{\"id\":\"" + m.id() + "\",\"threadId\":\"t-" + m.id() + "\"}");
            body.append("\"messages\":[").append(String.join(",", refs)).append("],");
        }
        if (stuckToken && matching.size() > size) {
            body.append("\"nextPageToken\":\"p").append(size).append("\",");
        } else if (offset + size < matching.size()) {
            body.append("\"nextPageToken\":\"p").append(offset + size).append("\",");
        }
        body.append("\"resultSizeEstimate\":").append(matching.size()).append('}');
        json(exchange, 200, body.toString());
    }

    private static void get(HttpExchange exchange, String id, Map<String, List<String>> params) throws IOException {
        StubMessage message = mailbox.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
        if ("raw".equals(first(params, "format"))) {
            if (message == null || failingRaws.contains(id)) {
                json(exchange, 500, "{\"error\":{\"code\":500,\"message\":\"boom\"}}");
                return;
            }
            String eml = "From: a@x.com\r\nSubject: s " + id + "\r\n\r\nbody of " + id;
            json(exchange, 200, "{\"id\":\"" + id + "\",\"raw\":\""
                    + Base64.getUrlEncoder().encodeToString(eml.getBytes(StandardCharsets.UTF_8)) + "\"}");
            return;
        }
        SUMMARY_CALLS.incrementAndGet();
        if (unavailableOnce.contains(id) && UNAVAILABLE.add(id)) {
            json(exchange, 503, "{\"error\":{\"code\":503,\"message\":\"backend unavailable\"}}");
            return;
        }
        if (rateLimitedOnce.contains(id) && RATE_LIMITED.add(id)) {
            exchange.getResponseHeaders().add("Retry-After", "0");
            json(exchange, 429, "{\"error\":{\"code\":429,\"message\":\"rate limit exceeded\"}}");
            return;
        }
        if (message == null || failingSummaries.contains(id)) {
            json(exchange, 500, "{\"error\":{\"code\":500,\"message\":\"boom\"}}");
            return;
        }
        String date = undatedSummaries.contains(id) ? "" : ",\"internalDate\":\"" + message.internalDate() + "\"";
        json(exchange, 200, "{\"id\":\"" + id + "\",\"threadId\":\"t-" + id + "\"" + date
                + ",\"payload\":{\"headers\":[{\"name\":\"Subject\",\"value\":\"s " + id + "\"},"
                + "{\"name\":\"From\",\"value\":\"a@x.com\"}]}}");
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static StubMessage msg(String id, String at) {
        return new StubMessage(id, Instant.parse(at).toEpochMilli());
    }

    private static String canonical(String at) {
        return WatermarkCheckpoint.canonical(Instant.parse(at));
    }

    private static long second(String at) {
        return Instant.parse(at).getEpochSecond();
    }

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService importService;
    private final List<String> importedIds = new ArrayList<>();
    private final List<String> dlqReasons = new ArrayList<>();
    private final List<String> dlqReadReasons = new ArrayList<>();
    private final List<String> dlqNeverReadReasons = new ArrayList<>();
    private boolean dlqWritable = true;
    private List<String> failingImports = List.of();
    private List<String> throwingImports = List.of();
    private List<String> skippingImports = List.of();
    private List<String> attachmentWarningImports = List.of();
    /** Ids the import answers "already imported" for, with an attachment it tried again and could not import. */
    private List<String> skippedWithMissingAttachments = List.of();
    /** Ids the import answers "imported" for, with the given warnings — the import's own wording. */
    private Map<String, List<String>> warnedImports = Map.of();

    private GmailFetchOrchestrator gmail() {
        GmailFetchOrchestrator orchestrator = new GmailFetchOrchestrator();
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        importedIds.clear();
        dlqReasons.clear();
        dlqReadReasons.clear();
        dlqNeverReadReasons.clear();
        lenient().when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("gmail-token");
        lenient().doNothing().when(fetchSupport).throttle(anyLong());
        doCallRealMethod().when(fetchSupport).buildMailRequest(any(), any(), any(), any(), any(), any());
        lenient().doAnswer(call -> {
            dlqReasons.add(call.getArgument(1));
            dlqNeverReadReasons.add(call.getArgument(1));
            return dlqWritable;
        }).when(fetchSupport).saveSourceNeverReadToDlq(any(), anyString());
        lenient().doAnswer(call -> {
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
        orchestrator.adapterFactory = token -> new GmailConnectorAdapter(token, base);
        orchestrator.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return orchestrator;
    }

    private void checkpointIs(String stored) {
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(stored);
    }

    private String savedCheckpoint() {
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(checkpointManager).saveSimpleCheckpoint(eq("p-gmail"), eq(GmailFetchOrchestrator.KEY), saved.capture());
        return saved.getValue();
    }

    private static ImportProfileDefinition profile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-gmail");
        profile.setRepositoryId("bedroom");
        return profile;
    }

    private static ConnectorDefinition connector() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-gmail");
        connector.setSourceSystem("gmail_mail");
        return connector;
    }

    private static final Map<String, String> INBOX = Map.of("query", "in:inbox");

    // ── order and budget ──────────────────────────────────────────

    /** A burst larger than the limit: the OLDEST are taken, and the checkpoint names the last one settled. */
    @Test
    @DisplayName("Gmail: a burst larger than the limit is taken oldest first, and the checkpoint names the last one settled")
    void gmailABurstIsTakenOldestFirst() {
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"), msg("m3", "2026-03-01T10:00:03Z"),
                msg("m4", "2026-03-01T10:00:04Z"), msg("m5", "2026-03-01T10:00:05Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 2);

        assertEquals(List.of("m1", "m2"), importedIds, "the newest were taken, not the oldest: " + importedIds);
        assertEquals(canonical("2026-03-01T10:00:02Z") + "|m2", savedCheckpoint());
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("limit of 2")), result.incompleteReads().toString());
    }

    /** The next poll takes the rest from the checkpoint the last one saved, and not what it covers. */
    @Test
    @DisplayName("Gmail: the next poll takes the rest from the checkpoint, not what it covers")
    void gmailTheNextPollTakesTheRestFromTheCheckpoint() {
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"), msg("m3", "2026-03-01T10:00:03Z"),
                msg("m4", "2026-03-01T10:00:04Z"), msg("m5", "2026-03-01T10:00:05Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T10:00:02Z") + "|m2");

        orchestrator.execute(null, profile(), connector(), INBOX, 2);

        assertEquals(List.of("m3", "m4"), importedIds);
        assertEquals(canonical("2026-03-01T10:00:04Z") + "|m4", savedCheckpoint());
    }

    /** Gmail's listing is in no documented order: the oldest is found by the dates read, not by its place. */
    @Test
    @DisplayName("Gmail: the listing's order is not trusted — the oldest is found by its date")
    void gmailTheListingOrderIsNotTrusted() {
        scrambled = true;
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"), msg("m3", "2026-03-01T10:00:03Z"),
                msg("m4", "2026-03-01T10:00:04Z"), msg("m5", "2026-03-01T10:00:05Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 2);

        assertEquals(List.of("m1", "m2"), importedIds, "the listing's order was taken: " + importedIds);
    }

    /** A message at the checkpoint's own time that the checkpoint does not name is taken; the named one is not. */
    @Test
    @DisplayName("Gmail: a message sharing the checkpoint's time that it does not name is taken, and the named one is not")
    void gmailASiblingAtTheCheckpointsTimeIsNotLost() {
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01.500Z"), msg("m2", "2026-03-01T10:00:01.500Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T10:00:01.500Z") + "|m1");

        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(List.of("m2"), importedIds, "the message the checkpoint does not name was lost, or the named one taken again: " + importedIds);
        assertEquals(1, result.skipped(), result.toString());
    }

    @Test
    @DisplayName("Gmail: a fresh profile reads from the beginning, oldest first")
    void gmailAFreshProfileReadsFromTheBeginning() {
        mailbox = List.of(msg("m2", "2026-02-28T10:00:00Z"), msg("m1", "2020-01-01T00:00:00Z"));
        GmailFetchOrchestrator orchestrator = gmail();

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(List.of("m1", "m2"), importedIds);
        assertFalse(LIST_QUERIES.isEmpty(), "nothing was listed");
        assertFalse(LIST_QUERIES.get(0).contains("after:"), LIST_QUERIES.toString());
    }

    // ── the search ────────────────────────────────────────────────

    /** Epoch seconds, the lower bound one second before the window, the profile's query in parentheses. */
    @Test
    @DisplayName("Gmail: the search is in epoch seconds, from one second before the window, around the profile's query")
    void gmailTheSearchIsInEpochSecondsAroundTheQuery() {
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), Map.of("query", "from:a OR from:b"), 10);

        assertFalse(LIST_QUERIES.isEmpty(), "nothing was listed");
        assertEquals("(from:a OR from:b) after:" + (second("2026-03-01T09:00:00Z") - 1) + " before:" + (NOW.getEpochSecond() + 1),
                LIST_QUERIES.get(0));
    }

    @Test
    @DisplayName("Gmail: the search string — no clause for a blank query, no lower bound for none")
    void gmailTheSearchStringOfTheAdapter() {
        assertEquals("(in:inbox) after:100 before:200", GmailConnectorAdapter.search("in:inbox", 100L, 200));
        assertEquals("before:5", GmailConnectorAdapter.search("  ", null, 5));
    }

    // ── windows ───────────────────────────────────────────────────

    /** More than a page from the checkpoint: the window is halved until it fits, and only its messages' dates are read. */
    @Test
    @DisplayName("Gmail: a window that does not fit one page is narrowed, and only its messages' dates are read")
    void gmailAWindowThatDoesNotFitOnePageIsNarrowed() {
        List<StubMessage> hourly = new ArrayList<>();
        for (int h = 3; h <= 10; h++) hourly.add(msg("m" + (h - 2), String.format("2026-03-01T%02d:00:00Z", h)));
        mailbox = hourly;
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T02:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 1);

        assertEquals(List.of("m1"), importedIds, "the first page was taken for the window: " + importedIds);
        assertTrue(SUMMARY_CALLS.get() <= 4, "the dates of more than one page were read: " + SUMMARY_CALLS.get());
        assertEquals(canonical("2026-03-01T03:00:00Z") + "|m1", savedCheckpoint());
    }

    /**
     * One second that holds more than a page cannot be narrowed: it is read whole, and its oldest
     * taken — an hour behind the checkpoint, within the default request cap: the search for each
     * window starts from the bound the last one did not fit under (from now each time, reaching it
     * took about a hundred requests).
     */
    @Test
    @DisplayName("Gmail: one second that holds more than a page is read whole, and reached within the default cap")
    void gmailASecondThatHoldsMoreThanAPageIsReadWhole() {
        List<StubMessage> oneSecond = new ArrayList<>();
        for (int i = 1; i <= 6; i++) oneSecond.add(msg("m" + i, "2026-03-01T10:00:00." + i + "00Z"));
        mailbox = oneSecond;
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 1);

        assertEquals(List.of("m1"), importedIds, "the second was not read whole, or not reached: " + importedIds
                + " " + result.incompleteReads());
        assertFalse(PAGE_TOKENS.isEmpty(), "no second page was asked for");
    }

    /** A window that was finished moves the checkpoint to its end even when it held nothing. */
    @Test
    @DisplayName("Gmail: a finished window moves the checkpoint even when it held nothing")
    void gmailAFinishedWindowMovesTheCheckpointEvenWhenEmpty() {
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T06:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(canonical("2026-03-01T11:55:00Z"), savedCheckpoint(), "the empty window did not move the checkpoint");
    }

    /**
     * A run cut at the request cap keeps the windows it finished: the requests spent finding them
     * are not spent again, or a backlog wider than the cap would be looked for from the same place
     * on every poll.
     */
    @Test
    @DisplayName("Gmail: a run cut at the request cap keeps the windows it finished")
    void gmailARunCutAtTheCapKeepsTheWindowsItFinished() {
        List<StubMessage> dense = new ArrayList<>();
        for (int i = 1; i <= 5; i++) dense.add(msg("m" + i, "2026-03-01T11:00:0" + i + "Z"));
        mailbox = dense;
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-02-19T12:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(),
                Map.of("query", "in:inbox", GmailFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "3"), 1);

        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("cap of 3")), result.incompleteReads().toString());
        assertEquals(canonical("2026-02-24T12:00:00Z"), savedCheckpoint(), "the finished window was not kept");
    }

    /** Read as inclusive, both neighbouring windows list the second between them: a message there is tried once. */
    @Test
    @DisplayName("Gmail: a message two windows both list is tried once in a run")
    void gmailAMessageTwoWindowsListIsTriedOnce() {
        inclusiveBounds = true;
        failingImports = List.of("m1", "m2", "m3", "m4");
        List<StubMessage> two = new ArrayList<>();
        for (int i = 1; i <= 4; i++) two.add(msg("m" + i, "2026-03-01T10:00:0" + i + "Z"));
        for (int i = 5; i <= 9; i++) two.add(msg("m" + i, "2026-03-01T10:30:0" + (i - 5) + "Z"));
        mailbox = two;
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 2);

        assertEquals(List.of("m5", "m6"), importedIds, "a message was tried twice in one run: " + importedIds);
    }

    /** A next-page token answered twice for one second's messages: the listing does not advance, and that is a cut. */
    @Test
    @DisplayName("Gmail: a page token answered twice is a cut, not a loop")
    void gmailAPageTokenAnsweredTwiceIsACut() {
        stuckToken = true;
        List<StubMessage> oneSecond = new ArrayList<>();
        for (int i = 1; i <= 6; i++) oneSecond.add(msg("m" + i, "2026-03-01T10:00:00." + i + "00Z"));
        mailbox = oneSecond;
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(),
                Map.of("query", "in:inbox", GmailFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "200"), 1);

        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("twice")), result.incompleteReads().toString());
    }

    // ── the cap ───────────────────────────────────────────────────

    /** Nothing within the lag of the listing's start is named: the index may not have it yet. */
    @Test
    @DisplayName("Gmail: the checkpoint stops at the listing's start minus the lag")
    void gmailTheCheckpointStopsAtTheLag() {
        mailbox = List.of(msg("m1", "2026-03-01T11:30:00Z"), msg("m2", "2026-03-01T11:58:00Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T11:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(List.of("m1", "m2"), importedIds);
        assertEquals(canonical("2026-03-01T11:55:00Z"), savedCheckpoint(), "the checkpoint passed the lag");
    }

    @Test
    @DisplayName("Gmail: the lag is the profile's parameter")
    void gmailTheLagIsTheProfilesParameter() {
        mailbox = List.of(msg("m1", "2026-03-01T11:40:00Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T11:00:00Z"));

        orchestrator.execute(null, profile(), connector(),
                Map.of("query", "in:inbox", GmailFetchOrchestrator.PARAM_CHECKPOINT_LAG_MINUTES, "30"), 10);

        assertEquals(List.of("m1"), importedIds);
        assertEquals(canonical("2026-03-01T11:30:00Z"), savedCheckpoint());
    }

    /** A checkpoint above this run's cap covers nothing above the cap, and is not lowered. */
    @Test
    @DisplayName("Gmail: a checkpoint above the cap covers nothing above it, and is not lowered")
    void gmailACheckpointAboveTheCapIsNotTrustedAboveIt() {
        mailbox = List.of(msg("m1", "2026-03-01T11:57:00Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T11:59:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(List.of("m1"), importedIds, "a message above the cap was taken as covered");
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    // ── the stored checkpoint ─────────────────────────────────────

    /** The day an earlier version wrote is read from the start of that UTC day, not Pacific midnight. */
    @Test
    @DisplayName("Gmail: a day an earlier version wrote is read from the start of that UTC day")
    void gmailALegacyDayIsReadFromTheStartOfThatUtcDay() {
        mailbox = List.of(msg("m0", "2026-02-28T23:00:00Z"), msg("m1", "2026-03-01T02:00:00Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs("2026/03/01");

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(List.of("m1"), importedIds, "the day was not read from its UTC start, or not read at all: " + importedIds);
        assertFalse(LIST_QUERIES.isEmpty(), "nothing was listed");
        assertTrue(LIST_QUERIES.get(0).contains("after:" + (second("2026-03-01T00:00:00Z") - 1)), LIST_QUERIES.toString());
    }

    @Test
    @DisplayName("Gmail: a checkpoint that cannot be read is an error, and nothing is read")
    void gmailACheckpointThatCannotBeReadIsAnError() {
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs("yesterday");
        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 10);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("not a position this connector can read")), result.errors().toString());

        checkpointIs("2026/02/30");
        FetchResult impossibleDay = orchestrator.execute(null, profile(), connector(), INBOX, 10);
        assertTrue(impossibleDay.errors().stream().anyMatch(e -> e.contains("not a position this connector can read")),
                impossibleDay.errors().toString());
        assertTrue(LIST_QUERIES.isEmpty(), LIST_QUERIES.toString());
    }

    // ── one message ───────────────────────────────────────────────

    /** A message whose summary cannot be read is tried by its id — first — not dropped. */
    @Test
    @DisplayName("Gmail: a message whose date cannot be read is tried first, not dropped")
    void gmailAMessageWhoseDateCannotBeReadIsStillTried() {
        failingSummaries = Set.of("m2");
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"), msg("m3", "2026-03-01T10:00:03Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(List.of("m2", "m1", "m3"), importedIds, "the message without a readable summary was dropped or not tried first: " + importedIds);
    }

    /** A summary read Gmail answers 429 is asked again: the message is placed by its date, not tried first as unreadable. */
    @Test
    @DisplayName("Gmail: a rate-limited summary read is asked again, not taken as unreadable")
    void gmailARateLimitedSummaryIsAskedAgain() {
        rateLimitedOnce = Set.of("m2");
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"), msg("m3", "2026-03-01T10:00:03Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(List.of("m1", "m2", "m3"), importedIds, "the rate-limited read was not asked again: " + importedIds);
        assertEquals(Set.of("m2"), RATE_LIMITED, "the stub did not answer 429");
    }

    /** A summary read Gmail answers 503 is asked again too: the message is placed by its date. */
    @Test
    @DisplayName("Gmail: an unavailable (503) summary read is asked again, not taken as unreadable")
    void gmailAnUnavailableSummaryIsAskedAgain() {
        unavailableOnce = Set.of("m2");
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"), msg("m3", "2026-03-01T10:00:03Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(List.of("m1", "m2", "m3"), importedIds, "the unavailable read was not asked again: " + importedIds);
        assertEquals(Set.of("m2"), UNAVAILABLE, "the stub did not answer 503");
    }

    /** An attachment imported and not linked is a part missing: the import says "Relationship failed" (review, P1). */
    @Test
    @DisplayName("Gmail: a message whose attachment could not be linked is recorded")
    void gmailAMessageWhoseAttachmentLinkFailedIsRecorded() {
        warnedImports = Map.of("m1", List.of("Relationship failed: boom"));
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(1, dlqReadReasons.size(), "the failed link was not recorded: " + dlqReasons);
        assertTrue(dlqReadReasons.get(0).contains("Relationship failed"), dlqReadReasons.toString());
    }

    /** The import's warnings about evidence do not mean a part is missing (review, P1). */
    @Test
    @DisplayName("Gmail: a warning about evidence is not a missing part")
    void gmailAnEvidenceWarningIsNotAMissingPart() {
        warnedImports = Map.of("m1", List.of("attachment 'a.pdf': Provenance was NOT recorded for this document (x)",
                "Provenance was NOT recorded for this document (y)"));
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertTrue(dlqReasons.isEmpty(), "a complete mail was dead-lettered for a warning about evidence: " + dlqReasons);
        assertEquals(1, result.imported(), result.toString());
    }

    /** The connector's rate limit paces every summary read as well as every import. */
    @Test
    @DisplayName("Gmail: the connector's rate limit paces the summary reads too")
    void gmailSummaryReadsArePacedByTheRateLimit() {
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"), msg("m3", "2026-03-01T10:00:03Z"),
                msg("m4", "2026-03-01T10:00:04Z"), msg("m5", "2026-03-01T10:00:05Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));
        ConnectorDefinition paced = connector();
        paced.setRateLimitRpm(60);

        orchestrator.execute(null, profile(), paced, INBOX, 2);

        assertEquals(5, SUMMARY_CALLS.get());
        // five summary reads and two imports, each after a 1 s pause
        verify(fetchSupport, org.mockito.Mockito.times(7)).throttle(1000L);
    }

    /** A message without a date is tried first and never named: the budget stops, the checkpoint holds. */
    @Test
    @DisplayName("Gmail: a message without a date is tried first and never named")
    void gmailAMessageWithoutADateIsTriedFirstAndNeverNamed() {
        undatedSummaries = Set.of("m1");
        mailbox = List.of(msg("m1", "2026-03-01T10:00:05Z"), msg("m2", "2026-03-01T10:00:02Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 1);

        assertEquals(List.of("m1"), importedIds);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Gmail: a raw form that cannot be fetched is dead-lettered as never read")
    void gmailARawThatCannotBeFetchedIsDeadLetteredAsNeverRead() {
        failingRaws = Set.of("m1");
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(1, dlqNeverReadReasons.size(), dlqReasons.toString());
        assertTrue(dlqNeverReadReasons.get(0).contains("m1"), dlqNeverReadReasons.toString());
        assertEquals(List.of("m2"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
    }

    @Test
    @DisplayName("Gmail: an import that answers an error is dead-lettered as read")
    void gmailARefusedImportIsDeadLetteredAsRead() {
        failingImports = List.of("m1");
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(1, dlqReadReasons.size(), "the refused import was not recorded: " + dlqReasons);
        assertTrue(dlqReadReasons.get(0).contains("m1"), dlqReadReasons.toString());
        assertEquals(List.of("m2"), importedIds);
    }

    @Test
    @DisplayName("Gmail: an import that throws is dead-lettered as read")
    void gmailAnImportThatThrowsIsDeadLetteredAsRead() {
        throwingImports = List.of("m1");
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(1, dlqReadReasons.size(), dlqReasons.toString());
        assertTrue(dlqNeverReadReasons.isEmpty(), dlqReasons.toString());
        assertEquals(List.of("m2"), importedIds);
    }

    @Test
    @DisplayName("Gmail: a message imported without some of its attachments is counted and recorded")
    void gmailAMessageImportedWithoutAnAttachmentIsCountedAndRecorded() {
        attachmentWarningImports = List.of("m1");
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(1, result.imported(), result.toString());
        assertEquals(1, dlqReadReasons.size(), dlqReasons.toString());
        assertTrue(dlqReadReasons.get(0).contains("imported without some of its parts"), dlqReadReasons.toString());
    }

    /** A failure nothing records stops the run, and the message settled before it does not move the checkpoint past it. */
    /** Imported before; the import service tried its missing attachments again and one still failed: recorded, not passed as a skip. */
    @Test
    @DisplayName("Gmail: an already imported message whose attachments are still missing is recorded")
    void gmailAnAlreadyImportedMessageWithAttachmentsStillMissingIsRecorded() {
        skippedWithMissingAttachments = List.of("m1");
        mailbox = List.of(msg("m1", "2026-03-01T10:00:01Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(1, dlqReadReasons.size(), "the missing attachment was not recorded: " + dlqReasons);
        assertTrue(dlqReadReasons.get(0).contains("still missing"), dlqReadReasons.toString());
        assertEquals(1, result.skipped(), result.toString());
    }

    @Test
    @DisplayName("Gmail: a failure that cannot be dead-lettered stops the run and holds the checkpoint")
    void gmailAFailureThatCannotBeRecordedHoldsTheCheckpoint() {
        dlqWritable = false;
        failingRaws = Set.of("m1");
        mailbox = List.of(msg("m0", "2026-03-01T10:00:00Z"), msg("m1", "2026-03-01T10:00:01Z"), msg("m2", "2026-03-01T10:00:02Z"));
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertEquals(List.of("m0"), importedIds, "a message past the unrecorded failure was imported: " + importedIds);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** At most limit × 4 messages are tried in a run; the next is not tried and not passed. */
    @Test
    @DisplayName("Gmail: the attempts of one run are bounded by four times the limit")
    void gmailTheAttemptsOfOneRunAreBounded() {
        failingImports = List.of("m1", "m2", "m3", "m4", "m5");
        List<StubMessage> failing = new ArrayList<>();
        for (int i = 1; i <= 5; i++) failing.add(msg("m" + i, "2026-03-01T10:00:0" + i + "Z"));
        mailbox = failing;
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(),
                Map.of("query", "in:inbox", GmailFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "200"), 1);

        assertEquals(4, dlqReadReasons.size(), "the attempts were not bounded at 4 × the limit: " + dlqReasons);
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("4 × the limit of 1")), result.incompleteReads().toString());
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(checkpointManager, org.mockito.Mockito.atMost(1)).saveSimpleCheckpoint(anyString(), anyString(), saved.capture());
        for (String value : saved.getAllValues()) {
            assertFalse(WatermarkCheckpoint.parse(value).covers(canonical("2026-03-01T10:00:05Z"), "m5"),
                    "the checkpoint passed the message that was never tried: " + value);
        }
    }

    // ── refusals ──────────────────────────────────────────────────

    @Test
    @DisplayName("Gmail: a parameter that is not a number in range is reported, and nothing is read")
    void gmailABadParameterIsReported() {
        FetchResult cap = gmail().execute(null, profile(), connector(),
                Map.of(GmailFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "fifty"), 10);
        assertTrue(cap.errors().stream().anyMatch(e -> e.contains("gmailListMaxRequests")), cap.errors().toString());
        FetchResult lag = gmail().execute(null, profile(), connector(),
                Map.of(GmailFetchOrchestrator.PARAM_CHECKPOINT_LAG_MINUTES, "-1"), 10);
        assertTrue(lag.errors().stream().anyMatch(e -> e.contains("gmailCheckpointLagMinutes")), lag.errors().toString());
        assertTrue(LIST_QUERIES.isEmpty(), LIST_QUERIES.toString());
    }

    @Test
    @DisplayName("Gmail: a listed message without an id is not read around")
    void gmailAListedMessageWithoutAnIdIsNotReadAround() {
        idlessListing = true;
        GmailFetchOrchestrator orchestrator = gmail();
        checkpointIs(canonical("2026-03-01T09:00:00Z"));

        FetchResult result = orchestrator.execute(null, profile(), connector(), INBOX, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without an id")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }
}
