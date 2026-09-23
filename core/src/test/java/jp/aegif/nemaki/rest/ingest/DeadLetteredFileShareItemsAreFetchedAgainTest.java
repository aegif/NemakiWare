package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.ingest.fileshare.BoxConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.fileshare.DropboxConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.fileshare.FileShareRefetch;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A dead-lettered FILE_SHARE item whose bytes were never stored is replayed with its bytes
 * fetched again from the source — or not replayed at all.
 *
 * <p>Replayed through the plain import, such a row created a content-less document, reported
 * success, and the row — the only record of the item — was deleted (review, P1). Box and
 * Dropbox rows are fetched again by the row's own identifiers; the systems this cannot fetch
 * again are refused with the row kept (R111).
 */
class DeadLetteredFileShareItemsAreFetchedAgainTest {

    private final List<ExternalIngestRequest> executed = new ArrayList<>();
    private final List<String> downloaded = new ArrayList<>();
    private final AtomicInteger adaptersBuilt = new AtomicInteger();

    private ResponseEntity<?> retry(String system, boolean rowHasContent, String requestJson,
            java.util.function.Function<String, InputStream> download) throws Exception {
        return retry(system, rowHasContent, rowHasContent ? "stored bytes" : null, requestJson, download, row -> { });
    }

    /**
     * @param storedBytes what the store answers for the row's attachment (null: none)
     * @param shapeRow the row's recorded state — payload dropped, write unfinished, presence assumed
     */
    private ResponseEntity<?> retry(String system, boolean rowHasContent, String storedBytes, String requestJson,
            java.util.function.Function<String, InputStream> download,
            java.util.function.Consumer<IngestDeadLetterRecord> shapeRow) throws Exception {
        IngestDlqController controller = new IngestDlqController();

        IngestJobService jobService = mock(IngestJobService.class);
        IngestDeadLetterRecord row = new IngestDeadLetterRecord();
        row.setDlqId("dlq-1");
        row.setHasContent(rowHasContent);
        row.setSourceNeverRead(!rowHasContent);
        row.setRetryCount(0);
        row.setOriginalRequestJson(requestJson);
        when(jobService.getDlqEntry("dlq-1")).thenReturn(row);
        when(jobService.reserveDlqRetry(any())).thenAnswer(inv -> {
            IngestDeadLetterRecord r = inv.getArgument(0);
            r.setRetryCount(r.getRetryCount() + 1);
            return true;
        });
        shapeRow.accept(row);
        when(jobService.loadDlqContent("dlq-1")).thenReturn(storedBytes == null ? null : storedBytes.getBytes(StandardCharsets.UTF_8));
        when(jobService.deleteDlqEntry(any())).thenReturn(new IngestJobService.DlqDeletion(1, 0));

        CanonicalImportService importService = mock(CanonicalImportService.class);
        when(importService.execute(any(), any())).thenAnswer(inv -> {
            ExternalIngestRequest req = inv.getArgument(1);
            executed.add(req);
            return ExternalIngestResult.success("req-1", "obj-1", "1.0", false, null);
        });

        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem(system);
        connector.setCredentialRef("key");
        when(connectorService.get("c1")).thenReturn(connector);
        when(connectorService.countIndexFree("c1")).thenReturn(1);

        FetchSupport fetchSupport = mock(FetchSupport.class);
        when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("tok");

        FileShareRefetch refetch = new FileShareRefetch();
        Field boxFactory = FileShareRefetch.class.getDeclaredField("boxFactory");
        boxFactory.setAccessible(true);
        boxFactory.set(refetch, (java.util.function.Function<String, BoxConnectorAdapter>) token -> {
            adaptersBuilt.incrementAndGet();
            return new BoxConnectorAdapter(token) {
                @Override public InputStream downloadFile(String fileId) {
                    downloaded.add("box:" + fileId);
                    return download.apply(fileId);
                }
            };
        });
        Field dropboxFactory = FileShareRefetch.class.getDeclaredField("dropboxFactory");
        dropboxFactory.setAccessible(true);
        dropboxFactory.set(refetch, (java.util.function.Function<String, DropboxConnectorAdapter>) token -> {
            adaptersBuilt.incrementAndGet();
            return new DropboxConnectorAdapter(token) {
                @Override public InputStream downloadFile(String path) {
                    downloaded.add("dropbox:" + path);
                    return download.apply(path);
                }
            };
        });

        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(ctx.getUsername()).thenReturn("admin");
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getAttribute("CallContext")).thenReturn(ctx);

        wire(controller, "ingestJobService", jobService);
        wire(controller, "canonicalImportService", importService);
        wire(controller, "connectorDefinitionService", connectorService);
        wire(controller, "fetchSupport", fetchSupport);
        wire(controller, "refetch", refetch);
        wire(controller, "httpRequest", http);
        this.jobService = jobService;
        return controller.retryDlqEntry("dlq-1");
    }

    private IngestJobService jobService;

    private static void wire(IngestDlqController controller, String field, Object value) throws Exception {
        Field f = IngestDlqController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private static final String BOX_ROW = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"f-1\"}";
    private static final String DROPBOX_ROW = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"id:1\","
            + "\"metadata\":{\"dropboxPath\":\"/x.txt\"}}";

    private static InputStream requireBytes(ExternalIngestRequest req) {
        assertNotNull(req.getContentStream(), "the row was replayed without its bytes — an empty document");
        return req.getContentStream();
    }

    private static String read(InputStream in) throws Exception {
        try (in) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test
    @DisplayName("a Box row without bytes is fetched again by its file id before it is replayed")
    void aBoxRowWithoutBytesIsFetchedAgainBeforeReplay() throws Exception {
        ResponseEntity<?> res = retry("box", false, BOX_ROW, id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, executed.size(), "the replay did not reach the import: " + res.getBody());
        assertNotNull(executed.get(0).getContentStream(), "the row was replayed without its bytes — an empty document");
        assertEquals("fresh bytes", read(executed.get(0).getContentStream()));
        assertEquals(List.of("box:f-1"), downloaded);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("box", ((Map<?, ?>) res.getBody()).get("refetchedFromSource"));
    }

    @Test
    @DisplayName("a Dropbox row without bytes is fetched again by its file id — not by the path, which names whatever sits there now")
    void aDropboxRowIsFetchedAgainByItsFileIdNotItsPath() throws Exception {
        retry("dropbox", false, DROPBOX_ROW, id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)));

        assertEquals(List.of("dropbox:id:1"), downloaded, "a path was used: another file may sit at it by now");
        assertEquals(1, executed.size());
        assertNotNull(executed.get(0).getContentStream());
        assertEquals("fresh bytes", read(executed.get(0).getContentStream()));
    }

    @Test
    @DisplayName("a Dropbox row that names no 'id:' file id is refused, not fetched by a path")
    void aDropboxRowWithoutAFileIdIsRefused() throws Exception {
        String pathOnly = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"/x.txt\","
                + "\"metadata\":{\"dropboxPath\":\"/x.txt\"}}";
        ResponseEntity<?> res = retry("dropbox", false, pathOnly, id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)));

        assertTrue(downloaded.isEmpty(), "fetched by something other than the file id: " + downloaded);
        assertTrue(executed.isEmpty());
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("file id"), String.valueOf(res.getBody()));
    }

    /** A row whose payload was dropped (encryption refused the bytes) is not the empty-document case for a re-fetchable item: the bytes come from the source. */
    @Test
    @DisplayName("a row whose stored payload was dropped is fetched again, not refused")
    void aRowWhosePayloadWasDroppedIsFetchedAgain() throws Exception {
        ResponseEntity<?> res = retry("box", false, null, BOX_ROW, id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)),
                row -> row.setPayloadDropReason("encryption refused the bytes"));

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals(1, executed.size());
        assertEquals("fresh bytes", read(requireBytes(executed.get(0))));
    }

    /** …but only for a re-fetchable item: a Google Drive row with a dropped payload is still refused. */
    @Test
    @DisplayName("a row whose payload was dropped and whose system is not fetched again is still refused — the control")
    void aDroppedPayloadOfASystemNotFetchedAgainIsStillRefused() throws Exception {
        ResponseEntity<?> res = retry("google_drive", false, null, BOX_ROW, id -> new ByteArrayInputStream(new byte[0]),
                row -> row.setPayloadDropReason("encryption refused the bytes"));

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(executed.isEmpty());
    }

    /** A payload write that was never confirmed may have left an EARLIER attempt's bytes on the row; a re-fetchable item takes the source's, never those. */
    @Test
    @DisplayName("a row whose payload write was never confirmed is fetched again — the unattributable stored bytes are not replayed")
    void anUnfinishedPayloadWriteIsFetchedAgainNotReplayedFromTheOldAttempt() throws Exception {
        ResponseEntity<?> res = retry("box", true, "an earlier attempt's bytes", BOX_ROW,
                id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)),
                row -> row.setPayloadWriteToken("write-7"));

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals(1, executed.size());
        assertEquals("fresh bytes", read(requireBytes(executed.get(0))), "the earlier attempt's bytes were replayed under this attempt's metadata");
    }

    /** Recorded as carrying bytes, and the store has none: for a re-fetchable item that is not a refusal either. */
    @Test
    @DisplayName("a row recorded as carrying bytes that the store no longer has is fetched again")
    void aRowRecordedAsCarryingBytesThatAreGoneIsFetchedAgain() throws Exception {
        ResponseEntity<?> res = retry("box", true, null, BOX_ROW,
                id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), row -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals(1, executed.size());
        assertEquals("fresh bytes", read(requireBytes(executed.get(0))));
    }

    /** The note written when an assumed payload turns out absent must not say "replayed without one" when the bytes then came from the source. */
    @Test
    @DisplayName("an assumed payload disproved by the store says the bytes were fetched again, not that the row was replayed without any")
    void anAssumedPayloadDisprovedSaysTheBytesWereFetchedAgain() throws Exception {
        ResponseEntity<?> res = retry("box", true, null, BOX_ROW,
                id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)),
                row -> row.setPayloadPresenceAssumed(true));

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals(Boolean.TRUE, body.get("payloadPresenceAssumptionCleared"));
        String note = String.valueOf(body.get("payloadPresenceNote"));
        assertTrue(note.contains("fetched again"), note);
        assertFalse(note.contains("without one"), "the note contradicts the replay: " + note);
        assertEquals("fresh bytes", read(requireBytes(executed.get(0))));
    }

    @Test
    @DisplayName("a row whose bytes cannot be fetched again is not replayed and is kept")
    void aRowWhoseBytesCannotBeFetchedAgainIsNotReplayed() throws Exception {
        ResponseEntity<?> res = retry("box", false, BOX_ROW, id -> { throw new RuntimeException("Box HTTP 500"); });

        assertTrue(executed.isEmpty(), "replayed without bytes: an empty document would have been imported");
        assertEquals(HttpStatus.BAD_GATEWAY, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("Box HTTP 500"), String.valueOf(res.getBody()));
        verify(jobService, never()).deleteDlqEntry(any());
    }

    @Test
    @DisplayName("a row of a system that is not fetched again here is refused, not replayed as an empty document")
    void aRowOfASystemNotFetchedAgainHereIsRefused() throws Exception {
        ResponseEntity<?> res = retry("google_drive", false, BOX_ROW, id -> new ByteArrayInputStream(new byte[0]));

        assertTrue(executed.isEmpty(), "replayed without bytes: an empty document would have been imported");
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("google_drive"), String.valueOf(res.getBody()));
        verify(jobService, never()).deleteDlqEntry(any());
    }

    @Test
    @DisplayName("a row without a credential to fetch with is refused")
    void aRowWithoutACredentialIsRefused() throws Exception {
        ResponseEntity<?> res = retryWithoutCredential();

        assertTrue(executed.isEmpty(), "replayed without bytes and without a credential: an empty document");
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(downloaded.isEmpty(), downloaded.toString());
    }

    private ResponseEntity<?> retryWithoutCredential() throws Exception {
        IngestDlqController controller = new IngestDlqController();
        IngestJobService jobService = mock(IngestJobService.class);
        IngestDeadLetterRecord row = new IngestDeadLetterRecord();
        row.setDlqId("dlq-1");
        row.setHasContent(false);
        row.setSourceNeverRead(true);
        row.setOriginalRequestJson(BOX_ROW);
        when(jobService.getDlqEntry("dlq-1")).thenReturn(row);
        when(jobService.reserveDlqRetry(any())).thenReturn(true);
        CanonicalImportService importService = mock(CanonicalImportService.class);
        when(importService.execute(any(), any())).thenAnswer(inv -> {
            executed.add(inv.getArgument(1));
            return ExternalIngestResult.success("req-1", "obj-1", "1.0", false, null);
        });
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("box");
        when(connectorService.get("c1")).thenReturn(connector);
        when(connectorService.countIndexFree("c1")).thenReturn(1);
        FetchSupport none = mock(FetchSupport.class);
        when(none.resolvePasswordOrRefuse(any())).thenReturn(null);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(ctx.getUsername()).thenReturn("admin");
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getAttribute("CallContext")).thenReturn(ctx);
        wire(controller, "ingestJobService", jobService);
        wire(controller, "canonicalImportService", importService);
        wire(controller, "connectorDefinitionService", connectorService);
        wire(controller, "fetchSupport", none);
        wire(controller, "httpRequest", http);
        return controller.retryDlqEntry("dlq-1");
    }

    @Test
    @DisplayName("a row WITH stored bytes is replayed with them, and nothing is fetched again — the control")
    void aRowWithStoredBytesIsReplayedAsBefore() throws Exception {
        ResponseEntity<?> res = retry("box", true, BOX_ROW, id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, executed.size(), String.valueOf(res.getBody()));
        assertEquals("stored bytes", read(executed.get(0).getContentStream()));
        assertTrue(downloaded.isEmpty(), "the stored bytes were there; the source was asked anyway: " + downloaded);
        assertEquals(0, adaptersBuilt.get());
        assertEquals(HttpStatus.OK, res.getStatusCode());
    }
}
