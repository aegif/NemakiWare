package jp.aegif.nemaki.rest.ingest.record;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Salesforce: the records a SOQL query selects are read in key order — SystemModstamp, then Id —
 * from the checkpoint on (R107).
 *
 * <p>The previous orchestrator ran the profile's SOQL as written (a LIMIT and no ORDER BY), added the
 * checkpoint's filter only when the SOQL had no WHERE, read one batch, and wrote a checkpoint it
 * never read back. What is measured here is the REAL adapter pointed at a local stub of the REST
 * query API — which evaluates the key condition, the order and the LIMIT the connector sends, and
 * answers in batches — and the orchestrator's query, order, budget, dead-letter answers and the
 * checkpoint it saves.
 */
class SalesforceRecordsAreReadInKeyOrderTest {

    private static HttpServer server;
    private static String base;
    private static String previousAllowLocalhost;

    /** A record in the stub org. */
    record StubRecord(String id, String systemModstamp) {}

    private static volatile List<StubRecord> org = List.of();
    /** Records per batch of an answer. */
    private static volatile int batchSize = 2000;
    /** The next batch's path is answered as a URL on another host. */
    private static volatile boolean foreignNextUrl;
    /** An answer says done=false and gives no nextRecordsUrl. */
    private static volatile boolean doneWithoutNext;
    /** An answer carries no records array. */
    private static volatile boolean noRecordsArray;
    /** Answer the records against the requested order: the second of each pair swapped. */
    private static volatile boolean outOfOrder;
    /** Ids answered without their SystemModstamp. */
    private static volatile java.util.Set<String> unstamped = java.util.Set.of();
    /** The SOQL strings asked for, in order. */
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static final List<String> NEXT_PATHS = new CopyOnWriteArrayList<>();
    /** The answer the stub last computed, by its locator. */
    private static final Map<String, List<StubRecord>> ANSWERS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Pattern KEY = Pattern.compile("\\(SystemModstamp > (\\S+) OR \\(SystemModstamp = (\\S+) AND Id > '([A-Za-z0-9]+)'\\)\\)");
    private static final Pattern LIMIT = Pattern.compile("LIMIT (\\d+)$");

    @BeforeAll
    static void startStub() throws Exception {
        previousAllowLocalhost = System.getProperty("nemaki.ingest.allowLocalhost");
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/data/v59.0/query", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/services/data/v59.0/query")) {
                String q = params(exchange.getRequestURI().getRawQuery()).get("q");
                QUERIES.add(q);
                List<StubRecord> answer = answer(q);
                String locator = "01g" + QUERIES.size();
                ANSWERS.put(locator, answer);
                batch(exchange, locator, answer, 0);
            } else {
                NEXT_PATHS.add(path);
                String[] parts = path.substring(path.lastIndexOf('/') + 1).split("-");
                batch(exchange, parts[0], ANSWERS.get(parts[0]), Integer.parseInt(parts[1]));
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
        if (previousAllowLocalhost == null) System.clearProperty("nemaki.ingest.allowLocalhost");
        else System.setProperty("nemaki.ingest.allowLocalhost", previousAllowLocalhost);
    }

    @BeforeEach
    void reset() {
        org = List.of();
        batchSize = 2000;
        foreignNextUrl = false;
        doneWithoutNext = false;
        noRecordsArray = false;
        outOfOrder = false;
        unstamped = java.util.Set.of();
        QUERIES.clear();
        NEXT_PATHS.clear();
        ANSWERS.clear();
        dlqWritable = true;
        failingImports = List.of();
        throwingImports = List.of();
        skippingImports = List.of();
    }

    private static Map<String, String> params(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** The records a keyed query selects: after the key, in key order, at most its LIMIT. */
    private static List<StubRecord> answer(String q) {
        Matcher key = KEY.matcher(q);
        Instant keyAt = null;
        String keyId = null;
        if (key.find()) {
            keyAt = Instant.parse(key.group(1));
            keyId = key.group(3);
        }
        Matcher limit = LIMIT.matcher(q);
        int n = limit.find() ? Integer.parseInt(limit.group(1)) : Integer.MAX_VALUE;
        List<StubRecord> selected = new ArrayList<>();
        for (StubRecord r : org) {
            Instant at = Instant.parse(r.systemModstamp());
            if (keyAt == null || at.isAfter(keyAt) || (at.equals(keyAt) && r.id().compareTo(keyId) > 0)) selected.add(r);
        }
        selected.sort(Comparator.comparing((StubRecord r) -> Instant.parse(r.systemModstamp())).thenComparing(StubRecord::id));
        List<StubRecord> answer = new ArrayList<>(selected.subList(0, Math.min(n, selected.size())));
        if (outOfOrder && answer.size() >= 2) java.util.Collections.swap(answer, 0, 1);
        return answer;
    }

    private static void batch(HttpExchange exchange, String locator, List<StubRecord> answer, int offset) throws IOException {
        if (noRecordsArray) {
            json(exchange, 200, "{\"totalSize\":0,\"done\":true}");
            return;
        }
        List<StubRecord> page = answer.subList(Math.min(offset, answer.size()), Math.min(offset + batchSize, answer.size()));
        List<String> recs = new ArrayList<>();
        for (StubRecord r : page) {
            String stamp = r.systemModstamp().replace("Z", ".000+0000");
            recs.add("{\"attributes\":{\"type\":\"Account\"},\"Id\":\"" + r.id() + "\",\"Name\":\"n " + r.id() + "\""
                    + (unstamped.contains(r.id()) ? "" : ",\"SystemModstamp\":\"" + stamp + "\"") + "}");
        }
        boolean done = offset + batchSize >= answer.size();
        StringBuilder body = new StringBuilder("{\"totalSize\":" + answer.size() + ",\"done\":" + done);
        if (!done && !doneWithoutNext) {
            String next = "/services/data/v59.0/query/" + locator + "-" + (offset + batchSize);
            body.append(",\"nextRecordsUrl\":\"").append(foreignNextUrl ? "http://evil.example" + next : next).append('"');
        }
        body.append(",\"records\":[").append(String.join(",", recs)).append("]}");
        json(exchange, 200, body.toString());
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    /** A Salesforce-shaped Id: 18 characters, the given suffix at its end. */
    private static String id(String suffix) {
        return ("001000000000000000" + suffix).substring(suffix.length());
    }

    private static StubRecord rec(String suffix, String at) {
        return new StubRecord(id(suffix), at);
    }

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService importService;
    private final List<String> importedIds = new ArrayList<>();
    private final List<String> dlqReadReasons = new ArrayList<>();
    private final Map<String, byte[]> dlqBytes = new HashMap<>();
    private boolean dlqWritable = true;
    private List<String> failingImports = List.of();
    private List<String> throwingImports = List.of();
    private List<String> skippingImports = List.of();

    private SalesforceFetchOrchestrator salesforce() {
        SalesforceFetchOrchestrator orchestrator = new SalesforceFetchOrchestrator();
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        importedIds.clear();
        dlqReadReasons.clear();
        dlqBytes.clear();
        lenient().when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("sf-token");
        lenient().doNothing().when(fetchSupport).throttle(anyLong());
        lenient().doAnswer(call -> {
            dlqReadReasons.add(call.getArgument(1));
            return dlqWritable;
        }).when(fetchSupport).saveSourceReadToDlq(any(), anyString());
        lenient().doAnswer(call -> {
            ExternalIngestRequest r = call.getArgument(0);
            dlqReadReasons.add(call.getArgument(1));
            dlqBytes.put(r.getSourceObjectId(), call.getArgument(2));
            return dlqWritable;
        }).when(fetchSupport).saveSourceReadToDlq(any(), anyString(), any());
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(null);
        lenient().when(importService.executeBusinessRecordImport(any(), any())).thenAnswer(call -> {
            ExternalIngestRequest req = call.getArgument(1);
            String rid = req.getSourceObjectId();
            if (failingImports.contains(rid)) return ExternalIngestResult.error("r", "refused by the import service");
            if (throwingImports.contains(rid)) throw new RuntimeException("the import service threw after reading the content");
            if (skippingImports.contains(rid)) return ExternalIngestResult.skipped("r", "obj-" + rid, "already imported");
            importedIds.add(rid);
            return ExternalIngestResult.success("r", "obj-" + rid, "1.0", false, null);
        });
        orchestrator.setFetchSupport(fetchSupport);
        orchestrator.setCheckpointManager(checkpointManager);
        orchestrator.setCanonicalImportService(importService);
        orchestrator.adapterFactory = (endpoint, token) -> new SalesforceConnectorAdapter(endpoint, token);
        orchestrator.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return orchestrator;
    }

    private void checkpointIs(String stored) {
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(stored);
    }

    private String savedCheckpoint() {
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(checkpointManager).saveSimpleCheckpoint(eq("p-sf"), eq(SalesforceFetchOrchestrator.KEY), saved.capture());
        return saved.getValue();
    }

    private static ImportProfileDefinition profile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-sf");
        profile.setRepositoryId("bedroom");
        return profile;
    }

    private static ConnectorDefinition connector() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-sf");
        connector.setSourceSystem("salesforce");
        connector.setEndpoint(base);
        return connector;
    }

    private static final Map<String, String> ACCOUNTS = Map.of("soql", "SELECT Id, Name FROM Account");

    private static List<StubRecord> five() {
        return List.of(rec("E5", "2026-03-01T10:00:05Z"), rec("A1", "2026-03-01T10:00:01Z"), rec("C3", "2026-03-01T10:00:03Z"),
                rec("B2", "2026-03-01T10:00:02Z"), rec("D4", "2026-03-01T10:00:04Z"));
    }

    // ── order, budget, the key ────────────────────────────────────

    @Test
    @DisplayName("Salesforce: a burst larger than the limit is taken in key order, and the checkpoint names the last one passed")
    void salesforceABurstIsTakenInKeyOrder() {
        org = five();
        SalesforceFetchOrchestrator orchestrator = salesforce();

        FetchResult result = orchestrator.execute(null, profile(), connector(), ACCOUNTS, 2);

        assertEquals(List.of(id("A1"), id("B2")), importedIds);
        assertEquals("key:2026-03-01T10:00:02Z|" + id("B2"), savedCheckpoint());
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("limit of 2")), result.incompleteReads().toString());
    }

    @Test
    @DisplayName("Salesforce: the next poll continues from the key")
    void salesforceTheNextPollContinuesFromTheKey() {
        org = five();
        SalesforceFetchOrchestrator orchestrator = salesforce();
        checkpointIs("key:2026-03-01T10:00:02Z|" + id("B2"));

        orchestrator.execute(null, profile(), connector(), ACCOUNTS, 2);

        assertEquals(List.of(id("C3"), id("D4")), importedIds);
        assertEquals("key:2026-03-01T10:00:04Z|" + id("D4"), savedCheckpoint());
    }

    /** One second holding more records than a run takes: walked through by Id, poll after poll. */
    @Test
    @DisplayName("Salesforce: a second holding more records than a run takes is walked through by Id")
    void salesforceASecondHoldingManyRecordsIsWalkedThroughById() {
        org = List.of(rec("A1", "2026-03-01T10:00:00Z"), rec("A2", "2026-03-01T10:00:00Z"), rec("A3", "2026-03-01T10:00:00Z"));
        SalesforceFetchOrchestrator orchestrator = salesforce();
        checkpointIs("key:2026-03-01T10:00:00Z|" + id("A1"));

        orchestrator.execute(null, profile(), connector(), ACCOUNTS, 1);

        assertEquals(List.of(id("A2")), importedIds, "the records sharing the key's second were not walked by Id: " + importedIds);
        assertEquals("key:2026-03-01T10:00:00Z|" + id("A2"), savedCheckpoint());
    }

    /** The profile's condition in parentheses, the key fields added, the key condition, the order and the limit — the connector's. */
    @Test
    @DisplayName("Salesforce: the SOQL sent is the profile's, keyed, ordered and limited by the connector")
    void salesforceTheSoqlSentIsKeyedOrderedAndLimited() {
        SalesforceFetchOrchestrator orchestrator = salesforce();
        checkpointIs("key:2026-03-01T10:00:02Z|" + id("B2"));

        orchestrator.execute(null, profile(), connector(), Map.of("soql", "SELECT Name FROM Account WHERE Type = 'A' OR Type = 'B'"), 10);

        assertFalse(QUERIES.isEmpty(), "nothing was asked");
        assertEquals("SELECT Name, Id, SystemModstamp FROM Account WHERE (Type = 'A' OR Type = 'B') AND "
                + "(SystemModstamp > 2026-03-01T10:00:02Z OR (SystemModstamp = 2026-03-01T10:00:02Z AND Id > '" + id("B2") + "')) "
                + "ORDER BY SystemModstamp ASC, Id ASC LIMIT 41", QUERIES.get(0));
    }

    @Test
    @DisplayName("Salesforce: a SOQL with its own order, limit or grouping is refused, and nothing is read")
    void salesforceASoqlWithItsOwnOrderOrLimitIsRefused() {
        // After a condition, where nothing else would refuse them: straight after the object, the
        // object's name check refuses them too, with other words.
        for (String soql : List.of("SELECT Id FROM Account WHERE Type = 'A' ORDER BY Name",
                "SELECT Id FROM Account WHERE Type = 'A' LIMIT 10", "SELECT Id FROM Account ORDER BY Name",
                "SELECT Id FROM Account LIMIT 10")) {
            String word = soql.contains("ORDER") ? "ORDER" : "LIMIT";
            FetchResult result = salesforce().execute(null, profile(), connector(), Map.of("soql", soql), 10);
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("carries " + word + " at the top level")),
                    soql + ": " + result.errors());
        }
        for (String soql : List.of("SELECT Id FROM Account WHERE Type = 'A' GROUP BY Id", "DELETE FROM Account")) {
            FetchResult result = salesforce().execute(null, profile(), connector(), Map.of("soql", soql), 10);
            assertTrue(result.hasErrors(), soql + " was not refused");
        }
        assertTrue(QUERIES.isEmpty(), QUERIES.toString());
    }

    /** A clause word inside a sub-query or a literal is not a clause of the query. */
    @Test
    @DisplayName("Salesforce: a word inside a sub-query or a literal is not taken for a clause")
    void salesforceAWordInsideASubQueryOrALiteralIsNotAClause() {
        org = five();
        FetchResult result = salesforce().execute(null, profile(), connector(),
                Map.of("soql", "SELECT Id, (SELECT Id FROM Contacts ORDER BY Name) FROM Account WHERE Name != 'Limit order'"), 10);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(5, importedIds.size(), importedIds.toString());
    }

    // ── every batch ───────────────────────────────────────────────

    @Test
    @DisplayName("Salesforce: every batch of the answer is read")
    void salesforceEveryBatchOfTheAnswerIsRead() {
        batchSize = 2;
        org = five();
        SalesforceFetchOrchestrator orchestrator = salesforce();

        orchestrator.execute(null, profile(), connector(), ACCOUNTS, 10);

        assertEquals(5, importedIds.size(), "a batch after the first was not read: " + importedIds);
        assertEquals(2, NEXT_PATHS.size(), NEXT_PATHS.toString());
    }

    /** The request cap cuts the answer: the batches read are a prefix in key order, taken; the checkpoint moves over them. */
    @Test
    @DisplayName("Salesforce: an answer cut at the request cap takes the prefix it read")
    void salesforceAnAnswerCutAtTheCapTakesThePrefix() {
        batchSize = 2;
        org = five();
        SalesforceFetchOrchestrator orchestrator = salesforce();

        FetchResult result = orchestrator.execute(null, profile(), connector(),
                Map.of("soql", "SELECT Id FROM Account", SalesforceFetchOrchestrator.PARAM_MAX_QUERY_REQUESTS, "1"), 10);

        assertEquals(List.of(id("A1"), id("B2")), importedIds);
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("cap of 1")), result.incompleteReads().toString());
        assertEquals("key:2026-03-01T10:00:02Z|" + id("B2"), savedCheckpoint());
    }

    @Test
    @DisplayName("Salesforce: a next batch answered on another host is not followed")
    void salesforceANextBatchOnAnotherHostIsNotFollowed() {
        batchSize = 2;
        foreignNextUrl = true;
        org = five();
        FetchResult result = salesforce().execute(null, profile(), connector(), ACCOUNTS, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("not a query path on this instance")), result.errors().toString());
        assertTrue(NEXT_PATHS.isEmpty(), NEXT_PATHS.toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
    }

    @Test
    @DisplayName("Salesforce: an answer that says it is not done and names no next batch is refused")
    void salesforceAnAnswerNotDoneWithoutANextBatchIsRefused() {
        batchSize = 2;
        doneWithoutNext = true;
        org = five();
        FetchResult result = salesforce().execute(null, profile(), connector(), ACCOUNTS, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("done=false without a nextRecordsUrl")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Salesforce: an answer without a records array is refused, not read as no records")
    void salesforceAnAnswerWithoutRecordsIsRefused() {
        noRecordsArray = true;
        org = five();
        FetchResult result = salesforce().execute(null, profile(), connector(), ACCOUNTS, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without a records array")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** An answer out of key order is refused whole: taking its head would pass a record not yet taken. */
    @Test
    @DisplayName("Salesforce: an answer out of key order is refused whole, and the checkpoint does not move")
    void salesforceAnAnswerOutOfOrderIsRefusedWhole() {
        outOfOrder = true;
        org = five();
        SalesforceFetchOrchestrator orchestrator = salesforce();
        FetchResult result = orchestrator.execute(null, profile(), connector(), ACCOUNTS, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("out of SystemModstamp order")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), "part of an answer out of order was taken: " + importedIds);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** A record the key cannot place is refused with its answer: taking the others would pass it. */
    @Test
    @DisplayName("Salesforce: an answer carrying a record without a SystemModstamp is refused whole")
    void salesforceARecordWithoutASystemModstampIsRefusedWhole() {
        org = five();
        unstamped = java.util.Set.of(id("C3"));
        SalesforceFetchOrchestrator orchestrator = salesforce();
        FetchResult result = orchestrator.execute(null, profile(), connector(), ACCOUNTS, 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("without an Id or a SystemModstamp")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    // ── the cap ───────────────────────────────────────────────────

    /** A record newer than the listing's start minus the lag is imported, and the checkpoint does not pass it. */
    @Test
    @DisplayName("Salesforce: the checkpoint stops at the listing's start minus the lag")
    void salesforceTheCheckpointStopsAtTheLag() {
        org = List.of(rec("A1", "2026-03-01T11:30:00Z"), rec("B2", "2026-03-01T11:58:00Z"));
        SalesforceFetchOrchestrator orchestrator = salesforce();

        orchestrator.execute(null, profile(), connector(), ACCOUNTS, 10);

        assertEquals(List.of(id("A1"), id("B2")), importedIds);
        assertEquals("key:2026-03-01T11:30:00Z|" + id("A1"), savedCheckpoint(), "the checkpoint passed the lag");
    }

    // ── the stored checkpoint ─────────────────────────────────────

    /** The LastModifiedDate an earlier version wrote was never honoured: the first record is where this version starts. */
    @Test
    @DisplayName("Salesforce: the LastModifiedDate an earlier version wrote reads from the first record")
    void salesforceALegacyCheckpointReadsFromTheFirstRecord() {
        org = five();
        SalesforceFetchOrchestrator orchestrator = salesforce();
        checkpointIs("2026-03-01T10:00:04.000+0000");

        orchestrator.execute(null, profile(), connector(), ACCOUNTS, 10);

        assertEquals(5, importedIds.size(), importedIds.toString());
        assertFalse(QUERIES.get(0).contains("SystemModstamp >"), QUERIES.toString());
    }

    @Test
    @DisplayName("Salesforce: a checkpoint that cannot be read, or names no Salesforce Id, is an error")
    void salesforceACheckpointThatCannotBeReadIsAnError() {
        for (String stored : List.of("yesterday", "key:2026-03-01T10:00:02Z|x' OR Id != '", "key:not-a-time|" + id("B2"))) {
            SalesforceFetchOrchestrator orchestrator = salesforce();
            checkpointIs(stored);
            FetchResult result = orchestrator.execute(null, profile(), connector(), ACCOUNTS, 10);
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("nothing was read")), stored + ": " + result.errors());
        }
        assertTrue(QUERIES.isEmpty(), QUERIES.toString());
    }

    // ── one record ────────────────────────────────────────────────

    /** A refused import is dead-lettered as read WITH its JSON — the replay imports it — and the checkpoint passes it. */
    @Test
    @DisplayName("Salesforce: a refused import is dead-lettered as read with its JSON, and passed")
    void salesforceARefusedImportIsDeadLetteredWithItsJson() {
        failingImports = List.of(id("A1"));
        org = List.of(rec("A1", "2026-03-01T10:00:01Z"), rec("B2", "2026-03-01T10:00:02Z"));
        SalesforceFetchOrchestrator orchestrator = salesforce();

        orchestrator.execute(null, profile(), connector(), ACCOUNTS, 10);

        assertEquals(1, dlqReadReasons.size(), dlqReadReasons.toString());
        assertTrue(new String(dlqBytes.getOrDefault(id("A1"), new byte[0]), StandardCharsets.UTF_8).contains("\"Id\""),
                "the row does not carry the record's JSON");
        assertEquals(List.of(id("B2")), importedIds);
        assertEquals("key:2026-03-01T10:00:02Z|" + id("B2"), savedCheckpoint());
    }

    @Test
    @DisplayName("Salesforce: an import that throws is dead-lettered as read with its JSON")
    void salesforceAnImportThatThrowsIsDeadLetteredWithItsJson() {
        throwingImports = List.of(id("A1"));
        org = List.of(rec("A1", "2026-03-01T10:00:01Z"));
        salesforce().execute(null, profile(), connector(), ACCOUNTS, 10);

        assertEquals(1, dlqReadReasons.size(), dlqReadReasons.toString());
        assertTrue(dlqBytes.containsKey(id("A1")) && dlqBytes.get(id("A1")) != null, "the row does not carry the record's JSON");
    }

    @Test
    @DisplayName("Salesforce: a failure that cannot be dead-lettered stops the run with the checkpoint before it")
    void salesforceAFailureThatCannotBeRecordedHoldsTheCheckpoint() {
        dlqWritable = false;
        failingImports = List.of(id("B2"));
        org = List.of(rec("A1", "2026-03-01T10:00:01Z"), rec("B2", "2026-03-01T10:00:02Z"), rec("C3", "2026-03-01T10:00:03Z"));
        SalesforceFetchOrchestrator orchestrator = salesforce();

        FetchResult result = orchestrator.execute(null, profile(), connector(), ACCOUNTS, 10);

        assertEquals(List.of(id("A1")), importedIds, "a record past the unrecorded failure was imported: " + importedIds);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        assertEquals("key:2026-03-01T10:00:01Z|" + id("A1"), savedCheckpoint(), "the checkpoint passed the unrecorded failure");
    }

    @Test
    @DisplayName("Salesforce: the attempts of one run are bounded by four times the limit")
    void salesforceTheAttemptsOfOneRunAreBounded() {
        List<StubRecord> failing = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            failing.add(rec("F" + i, "2026-03-01T10:00:0" + i + "Z"));
            ids.add(id("F" + i));
        }
        failingImports = ids;
        org = failing;
        FetchResult result = salesforce().execute(null, profile(), connector(), ACCOUNTS, 1);

        assertEquals(4, dlqReadReasons.size(), "the attempts were not bounded at 4 × the limit: " + dlqReadReasons);
        assertTrue(result.incompleteReads().stream().anyMatch(r -> r.contains("4 × the limit of 1")), result.incompleteReads().toString());
        assertEquals("key:2026-03-01T10:00:04Z|" + id("F4"), savedCheckpoint());
    }

    @Test
    @DisplayName("Salesforce: a parameter that is not a number in range is reported, and nothing is read")
    void salesforceABadParameterIsReported() {
        FetchResult cap = salesforce().execute(null, profile(), connector(),
                Map.of(SalesforceFetchOrchestrator.PARAM_MAX_QUERY_REQUESTS, "fifty"), 10);
        assertTrue(cap.errors().stream().anyMatch(e -> e.contains("salesforceQueryMaxRequests")), cap.errors().toString());
        FetchResult lag = salesforce().execute(null, profile(), connector(),
                Map.of(SalesforceFetchOrchestrator.PARAM_CHECKPOINT_LAG_MINUTES, "-1"), 10);
        assertTrue(lag.errors().stream().anyMatch(e -> e.contains("salesforceCheckpointLagMinutes")), lag.errors().toString());
        assertTrue(QUERIES.isEmpty(), QUERIES.toString());
    }
}
