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
package jp.aegif.nemaki.rest.ingest.fileshare;

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
 * The Box and Dropbox connectors read the WHOLE folder before deciding what to import (R107).
 *
 * <p>They used to ask the API for the first {@code limit} items. Neither API lists a folder by
 * modification time, so those were the first {@code limit} names: every file after them was
 * never listed on any poll, and the checkpoint the poll raised from the files it did see
 * excluded any of them modified earlier for ever. What is measured here is the REAL adapter
 * pointed at a local stub of each API — the adapter's own paging, its refusals and the
 * orchestrator's budget and checkpoint — not a mock's idea of them (the shape
 * {@code NotionPartialReadsAreNotCompleteTest} established).
 */
class FileShareFoldersAreReadWholeTest {

    private static HttpServer server;
    private static String base;
    private static String previousAllowLocalhost;

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, int callNumber) throws IOException;
    }

    /** What Box's {@code /folders/{id}/items} answers; set per test. */
    private static volatile Responder boxItems;
    /** What Dropbox's {@code /files/list_folder} and {@code /continue} answer; set per test. */
    private static volatile Responder dropboxList;
    /** Ids whose download answers 500. */
    private static volatile List<String> failingDownloads = List.of();

    private static final AtomicInteger BOX_LIST_CALLS = new AtomicInteger();
    private static volatile String lastBoxListQuery = "";
    private static volatile String lastDropboxDownloadArg = "";
    private static final AtomicInteger DROPBOX_LIST_CALLS = new AtomicInteger();

    @BeforeAll
    static void startStub() throws Exception {
        previousAllowLocalhost = System.getProperty("nemaki.ingest.allowLocalhost");
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2.0/folders", exchange -> {
            lastBoxListQuery = String.valueOf(exchange.getRequestURI().getQuery());
            boxItems.respond(exchange, BOX_LIST_CALLS.incrementAndGet());
        });
        server.createContext("/2.0/files", exchange -> {
            // /2.0/files/{id}/content
            String[] parts = exchange.getRequestURI().getPath().split("/");
            String id = parts[parts.length - 2];
            if (failingDownloads.contains(id)) {
                json(exchange, 500, "{\"message\":\"boom\"}");
            } else {
                bytes(exchange, ("content of " + id).getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/2/files/list_folder", exchange -> dropboxList.respond(exchange, DROPBOX_LIST_CALLS.incrementAndGet()));
        server.createContext("/2/files/download", exchange -> {
            String arg = exchange.getRequestHeaders().getFirst("Dropbox-API-Arg");
            lastDropboxDownloadArg = String.valueOf(arg);
            boolean failing = false;
            for (String id : failingDownloads) {
                if (arg != null && arg.contains(id)) failing = true;
            }
            if (failing) {
                json(exchange, 500, "{\"error_summary\":\"boom\"}");
            } else {
                bytes(exchange, "dropbox content".getBytes(StandardCharsets.UTF_8));
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
        BOX_LIST_CALLS.set(0);
        DROPBOX_LIST_CALLS.set(0);
        failingDownloads = List.of();
        dlqWritable = true;
        failingImports = List.of();
        throwingImports = List.of();
        skippingImports = List.of();
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-1@2026-01-01T00:00:00-00:00"));
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-1@2026-01-01T00:00:00Z"));
    }

    // ── the stubs ─────────────────────────────────────────────────

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Retry-After", "0");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static void bytes(HttpExchange exchange, byte[] out) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    /** Box entries as {@code id@modified_at}; a {@code nextMarker} of null ends the folder. */
    private static String boxPage(String nextMarker, String... idAtTime) {
        StringBuilder entries = new StringBuilder();
        for (String spec : idAtTime) {
            String id = spec.substring(0, spec.indexOf('@'));
            String at = spec.substring(spec.indexOf('@') + 1);
            if (entries.length() > 0) entries.append(',');
            entries.append("{\"type\":\"file\",\"id\":\"").append(id).append("\",\"name\":\"").append(id)
                    .append(".txt\",\"size\":3,\"modified_at\":\"").append(at)
                    .append("\",\"parent\":{\"id\":\"0\"}}");
        }
        return "{\"entries\":[" + entries + "],\"limit\":1000"
                + (nextMarker == null ? "" : ",\"next_marker\":\"" + nextMarker + "\"") + "}";
    }

    /** Dropbox entries as {@code id@server_modified}. */
    private static String dropboxPage(boolean hasMore, String cursor, String... idAtTime) {
        StringBuilder entries = new StringBuilder();
        for (String spec : idAtTime) {
            String id = spec.substring(0, spec.indexOf('@'));
            String at = spec.substring(spec.indexOf('@') + 1);
            if (entries.length() > 0) entries.append(',');
            entries.append("{\".tag\":\"file\",\"id\":\"").append(id).append("\",\"name\":\"").append(id)
                    .append(".txt\",\"path_display\":\"/").append(id).append(".txt\",\"size\":3,\"server_modified\":\"")
                    .append(at).append("\"}");
        }
        return "{\"entries\":[" + entries + "],\"has_more\":" + hasMore
                + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
    }

    private static String markerOf(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        for (String part : query.split("&")) {
            if (part.startsWith("marker=")) return part.substring("marker=".length());
        }
        return null;
    }

    // ── the orchestrators under test ──────────────────────────────

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService importService;
    private final List<String> importedIds = new ArrayList<>();
    private final List<String> dlqReasons = new ArrayList<>();
    /** The reasons handed to saveSourceReadToDlq — the item WAS read — as opposed to never-read. */
    private final List<String> dlqReadReasons = new ArrayList<>();
    /** Ids whose import THROWS (as opposed to answering an error). */
    private List<String> throwingImports = List.of();
    /** Ids the import service answers "skipped" for — already imported, by its dedupe. */
    private List<String> skippingImports = List.of();
    /** What the dead-letter store answers when asked to record a failure. */
    private boolean dlqWritable = true;
    /** Ids whose import answers an error result (not an exception). */
    private List<String> failingImports = List.of();

    private void wire(Object orchestrator) {
        fetchSupport = mock(FetchSupport.class);
        checkpointManager = mock(CheckpointManager.class);
        importService = mock(CanonicalImportService.class);
        importedIds.clear();
        dlqReasons.clear();
        dlqReadReasons.clear();
        lenient().when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("secret-token");
        lenient().doNothing().when(fetchSupport).throttle(anyLong());
        lenient().doAnswer(call -> {
            dlqReasons.add(call.getArgument(1));
            return dlqWritable;
        }).when(fetchSupport).saveSourceNeverReadToDlq(any(), anyString());
        lenient().doAnswer(call -> {
            dlqReasons.add(call.getArgument(1));
            dlqReadReasons.add(call.getArgument(1));
            return dlqWritable;
        }).when(fetchSupport).saveSourceReadToDlq(any(), anyString());
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(null);
        lenient().when(importService.execute(any(), any())).thenAnswer(call -> {
            ExternalIngestRequest req = call.getArgument(1);
            if (failingImports.contains(req.getSourceObjectId())) {
                return ExternalIngestResult.error("r", "refused by the import service");
            }
            if (throwingImports.contains(req.getSourceObjectId())) {
                throw new RuntimeException("the import service threw after reading the content");
            }
            if (skippingImports.contains(req.getSourceObjectId())) {
                return ExternalIngestResult.skipped("r", "already imported");
            }
            importedIds.add(req.getSourceObjectId());
            return new ExternalIngestResult("r", "obj-1", "1.0", false, false, false, null, null,
                    List.of(), List.of());
        });
        if (orchestrator instanceof BoxFetchOrchestrator box) {
            box.setFetchSupport(fetchSupport);
            box.setCheckpointManager(checkpointManager);
            box.setCanonicalImportService(importService);
            box.adapterFactory = token -> new BoxConnectorAdapter(token, base + "/2.0",
                    jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
        } else {
            DropboxFetchOrchestrator dropbox = (DropboxFetchOrchestrator) orchestrator;
            dropbox.setFetchSupport(fetchSupport);
            dropbox.setCheckpointManager(checkpointManager);
            dropbox.setCanonicalImportService(importService);
            dropbox.adapterFactory = token -> new DropboxConnectorAdapter(token, base + "/2", base + "/2",
                    jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
        }
    }

    private BoxFetchOrchestrator box() {
        BoxFetchOrchestrator orchestrator = new BoxFetchOrchestrator();
        wire(orchestrator);
        return orchestrator;
    }

    private DropboxFetchOrchestrator dropbox() {
        DropboxFetchOrchestrator orchestrator = new DropboxFetchOrchestrator();
        wire(orchestrator);
        return orchestrator;
    }

    private void checkpointIs(String stored) {
        lenient().when(checkpointManager.loadSimpleCheckpoint(anyString(), anyString())).thenReturn(stored);
    }

    private static ImportProfileDefinition profile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-share");
        profile.setRepositoryId("bedroom");
        return profile;
    }

    private static ConnectorDefinition connector(String system) {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-" + system);
        connector.setSourceSystem(system);
        return connector;
    }

    // ── Box ───────────────────────────────────────────────────────

    /**
     * A folder larger than one Box page (1,000 items) AND than the run's budget is listed
     * WHOLE, and the budget takes the oldest.
     *
     * <p>1,001 files across two pages, in name order (which is not time order), budget two: all
     * 1,001 are listed, the two oldest — the last of page one and the only file of page two —
     * are imported, the rest are reported as left for the next poll, and the checkpoint names
     * what was taken. The old code listed the first two names and would never have listed the
     * other 999.
     */
    @Test
    @DisplayName("Box: a folder larger than a page and than the budget is listed whole and the oldest files are taken first")
    void boxListsTheWholeFolderAndTakesTheOldestFirst() {
        boxItems = (exchange, n) -> {
            String marker = markerOf(exchange);
            StringBuilder page = new StringBuilder("{\"entries\":[");
            if (marker == null) {
                for (int i = 0; i < 1000; i++) {
                    if (i > 0) page.append(',');
                    // f-0999 is the oldest file in the folder; everything else on this page is newer
                    String at = i == 999 ? "2026-01-01T00:00:00-00:00" : "2026-02-01T00:00:00-00:00";
                    page.append("{\"type\":\"file\",\"id\":\"f-").append(String.format("%04d", i))
                            .append("\",\"name\":\"f.txt\",\"size\":3,\"modified_at\":\"").append(at)
                            .append("\",\"parent\":{\"id\":\"0\"}}");
                }
            } else {
                page.append("{\"type\":\"file\",\"id\":\"f-1000\",\"name\":\"f.txt\",\"size\":3,"
                        + "\"modified_at\":\"2026-01-02T00:00:00-00:00\",\"parent\":{\"id\":\"0\"}}");
            }
            json(exchange, 200, page.append("],\"limit\":1000").append(marker == null ? ",\"next_marker\":\"m-2\"" : "").append("}").toString());
        };
        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 2);

        assertEquals(2, BOX_LIST_CALLS.get(), "the folder was not read past its first page");
        assertTrue(lastBoxListQuery.contains("usemarker=true"), "Box requires usemarker=true for marker paging: " + lastBoxListQuery);
        assertTrue(lastBoxListQuery.contains("marker=m-2"), "the second request did not carry the marker: " + lastBoxListQuery);
        assertEquals(1001, result.fetched(), result.toString());
        assertEquals(List.of("f-0999", "f-1000"), importedIds, "the budget did not take the oldest files");
        assertFalse(result.sawEverything(), "999 files were left for the next poll: " + result);
        assertTrue(result.incompleteReads().get(0).contains("left for the next poll"), result.incompleteReads().get(0));
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-01-02T00:00:00.000000000Z|f-1000");
    }

    /** The next poll, with that checkpoint, takes the rest and moves on. */
    @Test
    @DisplayName("Box: the next poll skips what the checkpoint names and takes the rest")
    void boxTheNextPollTakesTheRest() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-a@2026-01-05T00:00:00-00:00",
                "f-b@2026-01-02T00:00:00-00:00", "f-c@2026-01-04T00:00:00-00:00",
                "f-d@2026-01-01T00:00:00-00:00", "f-e@2026-01-03T00:00:00-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        checkpointIs("2026-01-02T00:00:00-00:00|f-b"); // the source's own form, normalised on read

        FetchResult result = orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        assertEquals(List.of("f-e", "f-c", "f-a"), importedIds);
        assertEquals(2, result.skipped(), "f-d (older) and f-b (named) should be skipped: " + result);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-01-05T00:00:00.000000000Z|f-a");
    }

    /**
     * A listing cut at the request cap imports nothing and holds the checkpoint.
     *
     * <p>The files a cut listing did not reach are not any particular subset of the folder, so
     * a checkpoint raised over the ones it did reach would exclude the unseen ones from every
     * later poll. The reason names the parameter to raise.
     */
    @Test
    @DisplayName("Box: a listing cut at the request cap imports nothing, holds the checkpoint and names the cap")
    void boxAListingCutAtTheCapImportsNothing() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage("m-2",
                "f-a@2026-01-05T00:00:00-00:00", "f-b@2026-01-02T00:00:00-00:00"));
        FetchResult result = box().execute(null, profile(), connector("box"),
                Map.of(BoxFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "1"), 10);

        assertEquals(1, BOX_LIST_CALLS.get());
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("boxListMaxRequests"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertFalse(result.hasErrors(), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** A Box answer without an {@code entries} array is refused, not read as an empty folder. */
    @Test
    @DisplayName("Box: a listing without an entries array is refused, not read as an empty folder")
    void boxAListingWithoutEntriesIsRefused() {
        boxItems = (exchange, n) -> json(exchange, 200, "{\"limit\":1000}");
        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 10);

        assertTrue(result.hasErrors(), "a malformed listing was read as a folder: " + result);
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** A file that fails is dead-lettered, not named by the checkpoint, and does not block the ones behind it. */
    @Test
    @DisplayName("Box: a file that fails is dead-lettered, not named, and the files behind it are still taken")
    void boxAFailingFileDoesNotBlockTheRest() {
        failingDownloads = List.of("f-bad");
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-bad@2026-01-01T00:00:00-00:00",
                "f-ok@2026-01-01T00:00:00-00:00", "f-new@2026-01-02T00:00:00-00:00"));
        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 2);

        assertEquals(1, dlqReasons.size(), dlqReasons.toString());
        assertTrue(dlqReasons.get(0).contains("f-bad"), dlqReasons.get(0));
        assertEquals(List.of("f-ok", "f-new"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-01-02T00:00:00.000000000Z|f-new");
    }

    /** A cap parameter that is not a number is reported, not replaced by the default. */
    @Test
    @DisplayName("Box: a request-cap parameter that is not a number is reported and nothing is read")
    void boxABadCapParameterIsReported() {
        FetchResult result = box().execute(null, profile(), connector("box"),
                Map.of(BoxFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "fifty"), 10);

        assertTrue(result.hasErrors(), result.toString());
        assertTrue(result.errors().get(0).contains("boxListMaxRequests"), result.errors().get(0));
        assertEquals(0, BOX_LIST_CALLS.get());
    }

    /**
     * An import that ANSWERS an error (no exception) is dead-lettered too, and not settled.
     *
     * <p>Adding the message to {@code errors} alone let a newer file that settles move the
     * checkpoint past it with nothing recording it (review, P1). The row is the metadata-only
     * record of the miss ({@code saveSourceReadToDlq}).
     */
    @Test
    @DisplayName("Box: an import that answers an error is dead-lettered and not named by the checkpoint")
    void boxAnImportThatAnswersAnErrorIsDeadLettered() {
        failingImports = List.of("f-bad");
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-bad@2026-01-01T00:00:00-00:00",
                "f-good@2026-01-02T00:00:00-00:00"));
        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 10);

        assertEquals(1, dlqReasons.size(), "the refused import was not dead-lettered: " + dlqReasons);
        assertTrue(dlqReasons.get(0).contains("f-bad"), dlqReasons.get(0));
        assertEquals(List.of("f-good"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-01-02T00:00:00.000000000Z|f-good");
    }

    /**
     * A failure whose dead-letter row could NOT be written holds the checkpoint.
     *
     * <p>Otherwise a newer file that settles would move the checkpoint past a file nothing
     * records — the same silent loss the row exists to prevent, and the usual cause (the
     * configuration store being unreachable) is the same store the checkpoint goes into.
     */
    @Test
    @DisplayName("Box: a failure that could not be dead-lettered holds the checkpoint and is reported")
    void boxAnUnrecordedFailureHoldsTheCheckpoint() {
        dlqWritable = false;
        failingDownloads = List.of("f-bad");
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-bad@2026-01-01T00:00:00-00:00",
                "f-good@2026-01-02T00:00:00-00:00"));
        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 10);

        assertEquals(List.of("f-good"), importedIds, "the good file should still be taken");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A file whose modification time is missing or cannot be read cannot be placed against the
     * checkpoint. It is imported on every poll (the import service's dedupe answers after the
     * first), never named, and the run says so — PARTIAL, not failed. Not a refusal of the
     * listing (Box's schema does not make modified_at required — review, P2); not a dead-letter
     * row (a never-read row replayed through the plain import made an empty document and
     * deleted the row — review, P1); not "the oldest" (that would skip it without a word).
     */
    @Test
    @DisplayName("Box: a file without a modified_at is imported on every poll, never named, and reported — not refused, not dead-lettered")
    void boxAFileWithoutAReadableTimestampIsImportedEveryPollAndNeverNamed() {
        boxItems = (exchange, n) -> json(exchange, 200, "{\"entries\":[{\"type\":\"file\",\"id\":\"f-x\",\"name\":\"x.txt\",\"size\":1,\"parent\":{\"id\":\"0\"}},{\"type\":\"file\",\"id\":\"f-ok\",\"name\":\"f-ok.txt\",\"size\":1,\"modified_at\":\"2026-01-03T00:00:00-00:00\",\"parent\":{\"id\":\"0\"}}],\"limit\":1000}");
        BoxFetchOrchestrator orchestrator = box();
        checkpointIs("2026-01-02T00:00:00-00:00|f-good");

        FetchResult result = orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        assertEquals(List.of("f-ok", "f-x"), importedIds, "a file that cannot be placed must be imported after the placeable ones: " + result);
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-01-03T00:00:00.000000000Z|f-ok");
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("f-x") && n.contains("never named")), result.incompleteReads().toString());
        assertFalse(result.hasErrors(), "an error on every poll would open the connector's breaker for every profile: " + result.errors());
        assertTrue(dlqReasons.isEmpty(), "a dead-letter row for a file that was imported: " + dlqReasons);
    }

    /** The files that cannot be placed never take the budget of the ones that can: they come after, with their own bound. */
    @Test
    @DisplayName("Box: a file that cannot be placed does not take the budget of the files that can")
    void boxAnUnplaceableFileDoesNotTakeTheBudgetOfPlaceableOnes() {
        boxItems = (exchange, n) -> json(exchange, 200, "{\"entries\":[{\"type\":\"file\",\"id\":\"f-x\",\"name\":\"x.txt\",\"size\":1,\"parent\":{\"id\":\"0\"}},{\"type\":\"file\",\"id\":\"f-old\",\"name\":\"f-old.txt\",\"size\":1,\"modified_at\":\"2026-01-01T00:00:00-00:00\",\"parent\":{\"id\":\"0\"}},{\"type\":\"file\",\"id\":\"f-new\",\"name\":\"f-new.txt\",\"size\":1,\"modified_at\":\"2026-01-02T00:00:00-00:00\",\"parent\":{\"id\":\"0\"}}],\"limit\":1000}");
        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 1);

        assertEquals(List.of("f-old", "f-x"), importedIds, "with a budget of 1: the oldest placeable file, then the unplaceable one: " + result);
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("left for the next poll")), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-01-01T00:00:00.000000000Z|f-old");
    }

    /**
     * A checkpoint above this run's cap — written before this batch, or before the allowance
     * was raised — covers nothing above the cap. A file modified within the allowance of an
     * EARLIER listing may still have been missing from it (moved in behind the marker), and
     * the position that listing wrote must not pass it (review, P1). The position is not
     * lowered: it is not trusted above the cap until the cap passes it.
     */
    @Test
    @DisplayName("Box: a checkpoint above the cap covers nothing above the cap, and is not lowered")
    void boxACheckpointAboveTheCapCoversNothingAboveIt() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null,
                "f-x@2026-09-24T09:58:00-00:00", "f-old@2026-09-24T09:00:00-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-24T10:00:00Z"), java.time.ZoneOffset.UTC);
        checkpointIs("2026-09-24T09:59:00Z|f-z");

        FetchResult result = orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        // cap = 09:55: f-x (09:58) is above it and must be offered although the checkpoint (09:59)
        // is past it; f-old (09:00) is below the cap and stays covered.
        assertEquals(List.of("f-x"), importedIds, "a file within the allowance was passed by a checkpoint above the cap: " + result);
        assertEquals(1, result.skipped(), result.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * Beyond four digits the year is not fixed width ("+10000" sorts before "9999"), so such a
     * timestamp is unreadable rather than mis-ordered (review, P1): the file cannot be placed —
     * imported on every poll and never named, never skipped as "older".
     */
    @Test
    @DisplayName("Box: a five-digit year is not ordered by string — the file is imported, never named, never skipped as older")
    void boxAYearBeyondFourDigitsIsNotPlaced() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-y@+10000-01-01T00:00:00-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        checkpointIs("2026-01-02T00:00:00Z|f-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        assertEquals(List.of("f-y"), importedIds, "a five-digit year was ordered as older and skipped without a word: " + result);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("f-y")), result.incompleteReads().toString());
    }

    /** The cap is taken from the clock; a clock outside the four-digit years cannot make a fixed-width cap (review, P2). */
    @Test
    @DisplayName("Box: a clock outside the four-digit years is named as the clock, not as the connector failing")
    void boxAClockOutsideFourDigitYearsIsAnError() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-a@2026-01-05T00:00:00-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("+10000-06-01T00:00:00Z"), java.time.ZoneOffset.UTC); // minus the allowance still beyond 9999
        checkpointIs("2026-01-02T00:00:00Z|f-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("clock") && e.contains("four-digit")), result.errors().toString());
        assertFalse(result.errors().stream().anyMatch(e -> e.contains("connection failed")), "the host's clock is not the connector failing: " + result.errors());
    }

    @Test
    @DisplayName("Box: a clock outside the four-digit years imports nothing — no cap that does not sort")
    void boxAClockOutsideFourDigitYearsImportsNothing() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-a@2026-01-05T00:00:00-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("+10000-06-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        checkpointIs("2026-01-02T00:00:00Z|f-b");

        orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        assertTrue(importedIds.isEmpty(), "with a cap that does not sort, every file became a candidate: " + importedIds);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * An item without an id cannot be told from any other: two of them would collapse into
     * one and the second be dropped without a word (review, P1). The page is refused — for
     * EVERY item type, folders included: the progress set counts them (Dropbox checks files
     * only; its folders are not listed and its deleted entries carry no id).
     */
    @Test
    @DisplayName("Box: an item without an id — a folder too — refuses the listing")
    void boxAnEntryWithoutAnIdIsRefused() {
        boxItems = (exchange, n) -> json(exchange, 200, "{\"entries\":[{\"type\":\"folder\",\"name\":\"sub\","
                + "\"modified_at\":\"2026-01-01T00:00:00-00:00\",\"parent\":{\"id\":\"0\"}},"
                + "{\"type\":\"file\",\"id\":\"f-c\",\"name\":\"c.txt\",\"size\":1,"
                + "\"modified_at\":\"2026-01-02T00:00:00-00:00\",\"parent\":{\"id\":\"0\"}}],\"limit\":1000}");
        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("no id")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), "an item without an id was imported, or its page was used: " + importedIds);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A stored checkpoint that cannot be read is NOT "no checkpoint": read as none it would
     * silently re-offer the whole folder, and nobody would learn that the value is broken
     * (review, P2).
     */
    @Test
    @DisplayName("Box: a stored checkpoint that cannot be read is an error, not 'no checkpoint'")
    void boxAStoredCheckpointThatCannotBeReadIsAnError() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-a@2026-01-05T00:00:00-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        checkpointIs("last tuesday|f-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("last tuesday") && e.contains("not a timestamp this connector can read")), result.errors().toString());
        assertEquals(0, BOX_LIST_CALLS.get(), "the folder was listed although the checkpoint could not be read");
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A marker that moves but returns a page this listing has already seen is not progress.
     *
     * <p>Read as progress, an API that repeats a page (or cycles A→B→A→B, which "the same
     * marker twice" does not catch) would spend the cap on repeats and be reported cut — or
     * run out of cap on the last repeat and be reported whole with files never reached.
     * Refused as a cut, nothing is imported and the checkpoint holds.
     */
    @Test
    @DisplayName("Box: a marker that moves but returns nothing new is a cut, not progress")
    void boxAMarkerThatMovesButAddsNothingIsACut() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage("m-" + (n + 1),
                "f-a@2026-01-05T00:00:00-00:00", "f-b@2026-01-02T00:00:00-00:00"));
        FetchResult result = box().execute(null, profile(), connector("box"),
                Map.of(BoxFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "10"), 10);

        assertEquals(2, BOX_LIST_CALLS.get(), "the repeated page was not recognised on the second request");
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("nothing this listing had not seen"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /**
     * A file modified within the lag allowance of the listing's start is imported but not
     * named by the checkpoint.
     *
     * <p>A folder listing is not a snapshot: a file added or moved in while the pages are
     * being read, at a position the marker has passed, is not in this listing. Keeping every
     * file modified around the listing above the checkpoint means the next listing offers it.
     */
    @Test
    @DisplayName("Box: a file modified within the lag allowance of the listing start is imported but not named")
    void boxAFileModifiedAroundTheListingIsNotNamed() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null,
                "f-old@2026-09-24T09:00:00-00:00", "f-fresh@2026-09-24T09:58:00-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-24T10:00:00Z"), java.time.ZoneOffset.UTC);

        FetchResult result = orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        assertEquals(List.of("f-old", "f-fresh"), importedIds, "both files should be imported");
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        // 09:58 is inside the default 5-minute allowance before 10:00; 09:00 is not.
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-09-24T09:00:00.000000000Z|f-old");
    }

    /**
     * An import that THROWS after the content was read is recorded as a read item, not a
     * never-read one — a never-read row replayed as "nothing to import" would be taken for a
     * resolution.
     */
    @Test
    @DisplayName("Box: an import that throws after reading the content is recorded as read, not never-read")
    void boxAnImportThatThrowsIsRecordedAsRead() {
        throwingImports = List.of("f-bad");
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-bad@2026-01-01T00:00:00-00:00",
                "f-good@2026-01-02T00:00:00-00:00"));
        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 10);

        assertEquals(1, dlqReadReasons.size(), "the thrown import was not recorded as a READ item: " + dlqReasons);
        assertEquals(1, dlqReasons.size(), "the thrown import was recorded twice (read AND never-read): " + dlqReasons);
        assertTrue(dlqReadReasons.get(0).contains("f-bad"), dlqReadReasons.get(0));
        assertEquals(List.of("f-good"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-01-02T00:00:00.000000000Z|f-good");
    }

    /** A page that repeats part of the previous one lists each item once and still counts as progress. */
    @Test
    @DisplayName("Box: an item repeated on a later page is listed once, and the page still counts as progress")
    void boxARepeatedItemIsListedOnce() {
        boxItems = (exchange, n) -> json(exchange, 200, n == 1
                ? boxPage("m-2", "f-a@2026-01-03T00:00:00-00:00", "f-b@2026-01-01T00:00:00-00:00")
                : boxPage(null, "f-b@2026-01-01T00:00:00-00:00", "f-c@2026-01-02T00:00:00-00:00"));
        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 10);

        assertEquals(2, BOX_LIST_CALLS.get());
        assertEquals(3, result.fetched(), "the repeated item was listed twice: " + result);
        assertEquals(List.of("f-b", "f-c", "f-a"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-01-03T00:00:00.000000000Z|f-a");
    }

    /**
     * A fraction of a second orders by time, not by string: {@code Instant.toString()} drops a
     * zero fraction, and "…:00Z" sorts AFTER "…:00.5Z" ('Z' > '.'), so a file half a second
     * newer than the checkpoint was read as older and skipped (fail-open).
     */
    @Test
    @DisplayName("Box: a file half a second newer than the checkpoint is newer, whatever the strings look like")
    void boxAFractionOfASecondOrdersByTimeNotByString() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-x@2026-01-02T00:00:00.500-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        checkpointIs("2026-01-02T00:00:00Z|f-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        assertEquals(List.of("f-x"), importedIds, "a file newer by half a second was read as older: " + result);
        assertEquals(0, result.skipped(), result.toString());
    }

    /**
     * The canonical form carries nine digits of fraction, so a file 100 ns older than the
     * checkpoint is covered — with three digits the two would tie and the file be offered
     * although it is older (the width the documentation claims, measured).
     */
    @Test
    @DisplayName("Box: a file 100 nanoseconds older than the checkpoint is covered — the form keeps nine digits")
    void boxANanosecondOlderFileIsCovered() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null, "f-x@2026-01-02T00:00:00.000000300-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        checkpointIs("2026-01-02T00:00:00.000000400Z|f-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("box"), Map.of(), 10);

        assertTrue(importedIds.isEmpty(), "a file older by 100 ns was read as not older: " + importedIds);
        assertEquals(1, result.skipped(), result.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** The lag parameter is validated like the request cap: reported, not replaced by the default. */
    @Test
    @DisplayName("Box: a lag parameter that is not in range is reported and nothing is read")
    void boxABadLagParameterIsReported() {
        FetchResult result = box().execute(null, profile(), connector("box"),
                Map.of(BoxFetchOrchestrator.PARAM_CHECKPOINT_LAG_MINUTES, "-1"), 10);

        assertTrue(result.hasErrors(), result.toString());
        assertTrue(result.errors().get(0).contains("boxCheckpointLagMinutes"), result.errors().get(0));
        assertEquals(0, BOX_LIST_CALLS.get());
    }

    /** The lag parameter is what the cap is taken from: with 0, a file modified just before the listing is named. */
    @Test
    @DisplayName("Box: the lag parameter moves the cap — with 0 the freshest file is named")
    void boxTheLagParameterIsHonoured() {
        boxItems = (exchange, n) -> json(exchange, 200, boxPage(null,
                "f-old@2026-09-24T09:00:00-00:00", "f-fresh@2026-09-24T09:58:00-00:00"));
        BoxFetchOrchestrator orchestrator = box();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-24T10:00:00Z"), java.time.ZoneOffset.UTC);

        orchestrator.execute(null, profile(), connector("box"),
                Map.of(BoxFetchOrchestrator.PARAM_CHECKPOINT_LAG_MINUTES, "0"), 10);

        verify(checkpointManager).saveSimpleCheckpoint("p-share", "box.0", "2026-09-24T09:58:00.000000000Z|f-fresh");
    }

    /**
     * The bound on the files that cannot be placed counts imports and failures, not the import
     * service's skips: with a bound of {@code limit × 4} attempts, the first four already-imported
     * files would use it up on every poll and the fifth would never be reached (review, P1).
     */
    @Test
    @DisplayName("Box: unplaceable files beyond the try bound are still reached — a dedupe skip is not a try")
    void boxUnplaceableFilesBeyondTheTryBoundAreStillReached() {
        skippingImports = List.of("f-1", "f-2", "f-3", "f-4");
        StringBuilder entries = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            if (i > 1) entries.append(',');
            entries.append("{\"type\":\"file\",\"id\":\"f-").append(i).append("\",\"name\":\"f.txt\",\"size\":1,\"parent\":{\"id\":\"0\"}}");
        }
        boxItems = (exchange, n) -> json(exchange, 200, "{\"entries\":[" + entries + "],\"limit\":1000}");

        FetchResult result = box().execute(null, profile(), connector("box"), Map.of(), 1);

        assertEquals(List.of("f-5"), importedIds, "the file behind four already-imported ones was never reached: " + result);
        assertEquals(4, result.skipped(), result.toString());
    }

    /** On a cut listing nothing is offered — the note about unplaceable files must not claim otherwise (review, P3). */
    @Test
    @DisplayName("Box: a cut listing does not claim the unplaceable files were imported")
    void boxACutListingDoesNotClaimUnplaceableFilesWereImported() {
        boxItems = (exchange, n) -> json(exchange, 200, "{\"entries\":[{\"type\":\"file\",\"id\":\"f-x\",\"name\":\"x.txt\","
                + "\"size\":1,\"parent\":{\"id\":\"0\"}}],\"limit\":1000,\"next_marker\":\"m-2\"}");
        FetchResult result = box().execute(null, profile(), connector("box"),
                Map.of(BoxFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "1"), 10);

        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertFalse(result.incompleteReads().stream().anyMatch(n -> n.contains("never named")),
                "the note claims an import that did not happen: " + result.incompleteReads());
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("boxListMaxRequests")), result.incompleteReads().toString());
    }

    // ── Dropbox ───────────────────────────────────────────────────

    @Test
    @DisplayName("Dropbox: a folder larger than the budget is listed whole across cursors and the oldest files are taken first")
    void dropboxListsTheWholeFolderAndTakesTheOldestFirst() {
        dropboxList = (exchange, n) -> {
            if (exchange.getRequestURI().getPath().endsWith("/continue")) {
                json(exchange, 200, dropboxPage(false, null, "d-d@2026-01-01T00:00:00Z", "d-e@2026-01-03T00:00:00Z"));
            } else {
                json(exchange, 200, dropboxPage(true, "c-2", "d-a@2026-01-05T00:00:00Z",
                        "d-b@2026-01-02T00:00:00Z", "d-c@2026-01-04T00:00:00Z"));
            }
        };
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 2);

        assertEquals(2, DROPBOX_LIST_CALLS.get(), "the cursor was not followed");
        assertEquals(5, result.fetched(), result.toString());
        assertEquals(List.of("d-d", "d-b"), importedIds, "the budget did not take the oldest files");
        assertFalse(result.sawEverything(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "dropbox", "2026-01-02T00:00:00.000000000Z|d-b");
    }

    @Test
    @DisplayName("Dropbox: the next poll skips what the checkpoint names and takes the rest")
    void dropboxTheNextPollTakesTheRest() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-a@2026-01-05T00:00:00Z",
                "d-b@2026-01-02T00:00:00Z", "d-c@2026-01-04T00:00:00Z", "d-d@2026-01-01T00:00:00Z",
                "d-e@2026-01-03T00:00:00Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        checkpointIs("2026-01-02T00:00:00Z|d-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(List.of("d-e", "d-c", "d-a"), importedIds);
        assertEquals(2, result.skipped(), result.toString());
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "dropbox", "2026-01-05T00:00:00.000000000Z|d-a");
    }

    @Test
    @DisplayName("Dropbox: a listing cut at the request cap imports nothing, holds the checkpoint and names the cap")
    void dropboxAListingCutAtTheCapImportsNothing() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(true, "c-" + n,
                "d-" + n + "@2026-01-0" + n + "T00:00:00Z"));
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"),
                Map.of(DropboxFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "2"), 10);

        assertEquals(2, DROPBOX_LIST_CALLS.get(), "the cap did not bound the listing");
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("dropboxListMaxRequests"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Dropbox: an import that answers an error is dead-lettered and not named by the checkpoint")
    void dropboxAnImportThatAnswersAnErrorIsDeadLettered() {
        failingImports = List.of("d-bad");
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-bad@2026-01-01T00:00:00Z",
                "d-good@2026-01-02T00:00:00Z"));
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(1, dlqReasons.size(), "the refused import was not dead-lettered: " + dlqReasons);
        assertTrue(dlqReasons.get(0).contains("d-bad"), dlqReasons.get(0));
        assertEquals(List.of("d-good"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "dropbox", "2026-01-02T00:00:00.000000000Z|d-good");
    }

    @Test
    @DisplayName("Dropbox: a failure that could not be dead-lettered holds the checkpoint and is reported")
    void dropboxAnUnrecordedFailureHoldsTheCheckpoint() {
        dlqWritable = false;
        failingDownloads = List.of("d-bad");
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-bad@2026-01-01T00:00:00Z",
                "d-good@2026-01-02T00:00:00Z"));
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(List.of("d-good"), importedIds);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("could not be dead-lettered")), result.errors().toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Dropbox: a file modified within the lag allowance of the listing start is imported but not named")
    void dropboxAFileModifiedAroundTheListingIsNotNamed() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null,
                "d-old@2026-09-24T09:00:00Z", "d-fresh@2026-09-24T09:58:00Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-24T10:00:00Z"), java.time.ZoneOffset.UTC);

        FetchResult result = orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(List.of("d-old", "d-fresh"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "dropbox", "2026-09-24T09:00:00.000000000Z|d-old");
    }

    @Test
    @DisplayName("Dropbox: an import that throws after reading the content is recorded as read, not never-read")
    void dropboxAnImportThatThrowsIsRecordedAsRead() {
        throwingImports = List.of("d-bad");
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-bad@2026-01-01T00:00:00Z",
                "d-good@2026-01-02T00:00:00Z"));
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(1, dlqReadReasons.size(), "the thrown import was not recorded as a READ item: " + dlqReasons);
        assertEquals(1, dlqReasons.size(), "the thrown import was recorded twice (read AND never-read): " + dlqReasons);
        assertTrue(dlqReadReasons.get(0).contains("d-bad"), dlqReadReasons.get(0));
        assertEquals(List.of("d-good"), importedIds);
        assertTrue(result.hasErrors(), result.toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "dropbox", "2026-01-02T00:00:00.000000000Z|d-good");
    }

    @Test
    @DisplayName("Dropbox: an item repeated on a later page is listed once")
    void dropboxARepeatedItemIsListedOnce() {
        dropboxList = (exchange, n) -> json(exchange, 200, n == 1
                ? dropboxPage(true, "c-2", "d-a@2026-01-03T00:00:00Z", "d-b@2026-01-01T00:00:00Z")
                : dropboxPage(false, null, "d-b@2026-01-01T00:00:00Z", "d-c@2026-01-02T00:00:00Z"));
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(2, DROPBOX_LIST_CALLS.get());
        assertEquals(3, result.fetched(), "the repeated item was listed twice: " + result);
        assertEquals(List.of("d-b", "d-c", "d-a"), importedIds);
        assertTrue(result.sawEverything(), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "dropbox", "2026-01-03T00:00:00.000000000Z|d-a");
    }

    @Test
    @DisplayName("Dropbox: a file half a second newer than the checkpoint is newer, whatever the strings look like")
    void dropboxAFractionOfASecondOrdersByTimeNotByString() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-x@2026-01-02T00:00:00.500Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        checkpointIs("2026-01-02T00:00:00Z|d-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(List.of("d-x"), importedIds, "a file newer by half a second was read as older: " + result);
        assertEquals(0, result.skipped(), result.toString());
    }

    @Test
    @DisplayName("Dropbox: a file whose server_modified cannot be read is imported on every poll, never named, and reported")
    void dropboxAFileWithoutAReadableTimestampIsImportedEveryPollAndNeverNamed() {
        dropboxList = (exchange, n) -> json(exchange, 200, "{\"entries\":[{\".tag\":\"file\",\"id\":\"d-x\",\"name\":\"x.txt\",\"path_display\":\"/x.txt\",\"size\":1,\"server_modified\":\"yesterday\"},{\".tag\":\"file\",\"id\":\"d-ok\",\"name\":\"d-ok.txt\",\"path_display\":\"/d-ok.txt\",\"size\":1,\"server_modified\":\"2026-01-03T00:00:00Z\"}],\"has_more\":false}");
        DropboxFetchOrchestrator orchestrator = dropbox();
        checkpointIs("2026-01-02T00:00:00Z|d-good");

        FetchResult result = orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(List.of("d-ok", "d-x"), importedIds, "a file that cannot be placed must be imported after the placeable ones: " + result);
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "dropbox", "2026-01-03T00:00:00.000000000Z|d-ok");
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("d-x") && n.contains("never named")), result.incompleteReads().toString());
        assertFalse(result.hasErrors(), result.errors().toString());
        assertTrue(dlqReasons.isEmpty(), dlqReasons.toString());
    }

    @Test
    @DisplayName("Dropbox: a file that cannot be placed does not take the budget of the files that can")
    void dropboxAnUnplaceableFileDoesNotTakeTheBudgetOfPlaceableOnes() {
        dropboxList = (exchange, n) -> json(exchange, 200, "{\"entries\":[{\".tag\":\"file\",\"id\":\"d-x\",\"name\":\"x.txt\",\"path_display\":\"/x.txt\",\"size\":1,\"server_modified\":\"yesterday\"},{\".tag\":\"file\",\"id\":\"d-old\",\"name\":\"d-old.txt\",\"path_display\":\"/d-old.txt\",\"size\":1,\"server_modified\":\"2026-01-01T00:00:00Z\"},{\".tag\":\"file\",\"id\":\"d-new\",\"name\":\"d-new.txt\",\"path_display\":\"/d-new.txt\",\"size\":1,\"server_modified\":\"2026-01-02T00:00:00Z\"}],\"has_more\":false}");
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 1);

        assertEquals(List.of("d-old", "d-x"), importedIds, result.toString());
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("left for the next poll")), result.incompleteReads().toString());
        verify(checkpointManager).saveSimpleCheckpoint("p-share", "dropbox", "2026-01-01T00:00:00.000000000Z|d-old");
    }

    @Test
    @DisplayName("Dropbox: a checkpoint above the cap covers nothing above the cap, and is not lowered")
    void dropboxACheckpointAboveTheCapCoversNothingAboveIt() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null,
                "d-x@2026-09-24T09:58:00Z", "d-old@2026-09-24T09:00:00Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-24T10:00:00Z"), java.time.ZoneOffset.UTC);
        checkpointIs("2026-09-24T09:59:00Z|d-z");

        FetchResult result = orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(List.of("d-x"), importedIds, "a file within the allowance was passed by a checkpoint above the cap: " + result);
        assertEquals(1, result.skipped(), result.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Dropbox: a five-digit year is not ordered by string — the file is imported, never named, never skipped as older")
    void dropboxAYearBeyondFourDigitsIsNotPlaced() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-y@+10000-01-01T00:00:00Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        checkpointIs("2026-01-02T00:00:00Z|d-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(List.of("d-y"), importedIds, "a five-digit year was ordered as older and skipped without a word: " + result);
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("d-y")), result.incompleteReads().toString());
    }

    @Test
    @DisplayName("Dropbox: a clock outside the four-digit years is named as the clock, not as the connector failing")
    void dropboxAClockOutsideFourDigitYearsIsAnError() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-a@2026-01-05T00:00:00Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("+10000-06-01T00:00:00Z"), java.time.ZoneOffset.UTC); // minus the allowance still beyond 9999
        checkpointIs("2026-01-02T00:00:00Z|d-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("clock") && e.contains("four-digit")), result.errors().toString());
        assertFalse(result.errors().stream().anyMatch(e -> e.contains("connection failed")), result.errors().toString());
    }

    @Test
    @DisplayName("Dropbox: a clock outside the four-digit years imports nothing")
    void dropboxAClockOutsideFourDigitYearsImportsNothing() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-a@2026-01-05T00:00:00Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("+10000-06-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        checkpointIs("2026-01-02T00:00:00Z|d-b");

        orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Dropbox: a file without an id refuses the listing")
    void dropboxAnEntryWithoutAnIdIsRefused() {
        dropboxList = (exchange, n) -> json(exchange, 200, "{\"entries\":[{\".tag\":\"file\",\"name\":\"a.txt\","
                + "\"path_display\":\"/a.txt\",\"size\":1,\"server_modified\":\"2026-01-01T00:00:00Z\"},"
                + "{\".tag\":\"file\",\"id\":\"d-c\",\"name\":\"c.txt\",\"path_display\":\"/c.txt\",\"size\":1,"
                + "\"server_modified\":\"2026-01-02T00:00:00Z\"}],\"has_more\":false}");
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("no id")), result.errors().toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Dropbox: a stored checkpoint that cannot be read is an error, not 'no checkpoint'")
    void dropboxAStoredCheckpointThatCannotBeReadIsAnError() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-a@2026-01-05T00:00:00Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        checkpointIs("last tuesday|d-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("last tuesday") && e.contains("not a timestamp this connector can read")), result.errors().toString());
        assertEquals(0, DROPBOX_LIST_CALLS.get(), "the folder was listed although the checkpoint could not be read");
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Dropbox: a file 100 nanoseconds older than the checkpoint is covered — the form keeps nine digits")
    void dropboxANanosecondOlderFileIsCovered() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-x@2026-01-02T00:00:00.000000300Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        checkpointIs("2026-01-02T00:00:00.000000400Z|d-b");

        FetchResult result = orchestrator.execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertTrue(importedIds.isEmpty(), "a file older by 100 ns was read as not older: " + importedIds);
        assertEquals(1, result.skipped(), result.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Dropbox: a lag parameter that is not in range is reported and nothing is read")
    void dropboxABadLagParameterIsReported() {
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"),
                Map.of(DropboxFetchOrchestrator.PARAM_CHECKPOINT_LAG_MINUTES, "-1"), 10);

        assertTrue(result.hasErrors(), result.toString());
        assertTrue(result.errors().get(0).contains("dropboxCheckpointLagMinutes"), result.errors().get(0));
        assertEquals(0, DROPBOX_LIST_CALLS.get());
    }

    @Test
    @DisplayName("Dropbox: the lag parameter moves the cap — with 0 the freshest file is named")
    void dropboxTheLagParameterIsHonoured() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null,
                "d-old@2026-09-24T09:00:00Z", "d-fresh@2026-09-24T09:58:00Z"));
        DropboxFetchOrchestrator orchestrator = dropbox();
        orchestrator.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-24T10:00:00Z"), java.time.ZoneOffset.UTC);

        orchestrator.execute(null, profile(), connector("dropbox"),
                Map.of(DropboxFetchOrchestrator.PARAM_CHECKPOINT_LAG_MINUTES, "0"), 10);

        verify(checkpointManager).saveSimpleCheckpoint("p-share", "dropbox", "2026-09-24T09:58:00.000000000Z|d-fresh");
    }

    /**
     * The same cursor twice cannot move forward and is a cut. Only that: Dropbox may answer an
     * empty page with a NEW cursor and has_more true, so "a page that adds nothing" is not a cut
     * here (it would refuse real folders) — those pages are bounded by the request cap.
     */
    @Test
    @DisplayName("Dropbox: the same cursor twice is a cut; an empty page with a new cursor is not")
    void dropboxTheSameCursorTwiceIsACut() {
        dropboxList = (exchange, n) -> json(exchange, 200, n == 1
                ? dropboxPage(true, "c-1", "d-a@2026-01-01T00:00:00Z")
                : n == 2 ? dropboxPage(true, "c-2")           // empty page, NEW cursor: not a cut
                : dropboxPage(true, "c-2", "d-b@2026-01-02T00:00:00Z"));  // the same cursor again: a cut
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"),
                Map.of(DropboxFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "6"), 10);

        assertEquals(3, DROPBOX_LIST_CALLS.get(), "the repeated cursor was not recognised on the request that repeated it");
        assertFalse(result.sawEverything(), result.toString());
        assertTrue(result.incompleteReads().get(0).contains("same cursor twice"), result.incompleteReads().get(0));
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Dropbox: unplaceable files beyond the try bound are still reached — a dedupe skip is not a try")
    void dropboxUnplaceableFilesBeyondTheTryBoundAreStillReached() {
        skippingImports = List.of("d-1", "d-2", "d-3", "d-4");
        StringBuilder entries = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            if (i > 1) entries.append(',');
            entries.append("{\".tag\":\"file\",\"id\":\"d-").append(i).append("\",\"name\":\"f.txt\",\"path_display\":\"/f")
                    .append(i).append(".txt\",\"size\":1,\"server_modified\":\"yesterday\"}");
        }
        dropboxList = (exchange, n) -> json(exchange, 200, "{\"entries\":[" + entries + "],\"has_more\":false}");

        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 1);

        assertEquals(List.of("d-5"), importedIds, "the file behind four already-imported ones was never reached: " + result);
        assertEquals(4, result.skipped(), result.toString());
    }

    /**
     * The poll downloads by the file's id, not its path: a path names whatever sits at it NOW,
     * and a file moved away and replaced between the listing and the download would come back
     * as the other file's bytes under this id (review, P1). Dropbox's download accepts "id:…".
     */
    @Test
    @DisplayName("Dropbox: the download names the file by its id, not by its path")
    void dropboxTheDownloadIsByFileIdNotPath() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(false, null, "d-a@2026-01-05T00:00:00Z"));
        dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertEquals(List.of("d-a"), importedIds);
        assertTrue(lastDropboxDownloadArg.contains("\"path\":\"d-a\""), "the download named the path, not the id: " + lastDropboxDownloadArg);
    }

    @Test
    @DisplayName("Dropbox: a cut listing does not claim the unplaceable files were imported")
    void dropboxACutListingDoesNotClaimUnplaceableFilesWereImported() {
        dropboxList = (exchange, n) -> json(exchange, 200, "{\"entries\":[{\".tag\":\"file\",\"id\":\"d-x\",\"name\":\"x.txt\","
                + "\"path_display\":\"/x.txt\",\"size\":1,\"server_modified\":\"yesterday\"}],\"has_more\":true,\"cursor\":\"c-2\"}");
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"),
                Map.of(DropboxFetchOrchestrator.PARAM_MAX_LIST_REQUESTS, "1"), 10);

        assertTrue(importedIds.isEmpty(), importedIds.toString());
        assertFalse(result.incompleteReads().stream().anyMatch(n -> n.contains("never named")),
                "the note claims an import that did not happen: " + result.incompleteReads());
        assertTrue(result.incompleteReads().stream().anyMatch(n -> n.contains("dropboxListMaxRequests")), result.incompleteReads().toString());
    }

    /** A page without {@code has_more} is refused — read as false it was the end of the folder. */
    @Test
    @DisplayName("Dropbox: a page without has_more is refused, not read as the end of the folder")
    void dropboxAPageWithoutHasMoreIsRefused() {
        dropboxList = (exchange, n) -> json(exchange, 200, "{\"entries\":[{\".tag\":\"file\",\"id\":\"d-a\","
                + "\"name\":\"a.txt\",\"path_display\":\"/a.txt\",\"size\":3,\"server_modified\":\"2026-01-05T00:00:00Z\"}],"
                + "\"cursor\":\"c-2\"}");
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertTrue(result.hasErrors(), "a page without has_more was read as the end: " + result);
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }

    /** {@code has_more} with no cursor is a cut, not the end of the folder. */
    @Test
    @DisplayName("Dropbox: has_more without a cursor is a cut, not the end of the folder")
    void dropboxHasMoreWithoutACursorIsACut() {
        dropboxList = (exchange, n) -> json(exchange, 200, dropboxPage(true, null, "d-a@2026-01-05T00:00:00Z"));
        FetchResult result = dropbox().execute(null, profile(), connector("dropbox"), Map.of(), 10);

        assertFalse(result.sawEverything(), result.toString());
        assertTrue(importedIds.isEmpty(), importedIds.toString());
        verify(checkpointManager, never()).saveSimpleCheckpoint(anyString(), anyString(), anyString());
    }
}
