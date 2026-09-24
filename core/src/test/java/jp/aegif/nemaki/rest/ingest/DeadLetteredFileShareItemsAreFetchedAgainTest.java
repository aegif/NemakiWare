package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.ingest.fileshare.BoxConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.fileshare.DropboxConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.fileshare.FileShareRefetch;
import jp.aegif.nemaki.rest.ingest.chat.MattermostConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.chat.SlackConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.chat.TeamsConnectorAdapter;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        return retry(system, SourceArchetype.FILE_SHARE, rowHasContent, storedBytes, requestJson, download, shapeRow);
    }

    private ResponseEntity<?> retry(String system, SourceArchetype archetype, boolean rowHasContent, String storedBytes,
            String requestJson, java.util.function.Function<String, InputStream> download,
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
        // a CHAT_CONTEXT row dispatches to the chat import; the same record
        when(importService.executeChatContextImport(any(), any())).thenAnswer(inv -> {
            ExternalIngestRequest req = inv.getArgument(1);
            executed.add(req);
            return replaySkips ? ExternalIngestResult.skipped("req-1", "obj-1", "already imported")
                    : ExternalIngestResult.success("req-1", "obj-1", "1.0", false, null);
        });

        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceArchetype(archetype);
        connector.setSourceSystem(system);
        connector.setCredentialRef("key");
        connector.setEndpoint("https://mm.example.com");
        when(connectorService.get("c1")).thenReturn(connector);
        when(connectorService.countIndexFree("c1")).thenReturn(1);

        FetchSupport fetchSupport = mock(FetchSupport.class);
        when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("tok");
        if (relinkFails) {
            org.mockito.Mockito.doAnswer(inv -> {
                java.util.List<String> warnings = inv.getArgument(6);
                warnings.add("the link could not be made");
                return null;
            }).when(fetchSupport).createRelationshipSafe(any(), any(), any(), any(), any(), any(), any());
        }

        FileShareRefetch refetch = new FileShareRefetch();
        Field mattermostFactory = FileShareRefetch.class.getDeclaredField("mattermostFactory");
        mattermostFactory.setAccessible(true);
        mattermostFactory.set(refetch, (java.util.function.BiFunction<String, String, MattermostConnectorAdapter>) (endpoint, token) -> {
            adaptersBuilt.incrementAndGet();
            return new MattermostConnectorAdapter(endpoint, token) {
                @Override public MattermostFile getFileInfo(String fileId) {
                    return new MattermostFile(fileId, "report.pdf", "application/pdf", 3);
                }
                @Override public InputStream downloadFile(String fileId) {
                    downloaded.add("mattermost:" + endpoint + "#" + fileId);
                    return download.apply(fileId);
                }
            };
        });
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
        Field slackFactory = FileShareRefetch.class.getDeclaredField("slackFactory");
        slackFactory.setAccessible(true);
        slackFactory.set(refetch, (java.util.function.Function<String, SlackConnectorAdapter>) token -> {
            adaptersBuilt.incrementAndGet();
            return new SlackConnectorAdapter(token) {
                @Override public InputStream downloadFile(String url) {
                    downloaded.add("slack:" + url);
                    return download.apply(url);
                }
            };
        });
        Field teamsFactory = FileShareRefetch.class.getDeclaredField("teamsFactory");
        teamsFactory.setAccessible(true);
        teamsFactory.set(refetch, (java.util.function.Function<String, TeamsConnectorAdapter>) token -> {
            adaptersBuilt.incrementAndGet();
            return new TeamsConnectorAdapter(token) {
                @Override public InputStream downloadFile(String url) {
                    downloaded.add("teams:" + url);
                    return download.apply(url);
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
        this.lastFetchSupport = fetchSupport;
        return controller.retryDlqEntry("dlq-1");
    }

    private IngestJobService jobService;
    /** The FetchSupport the last replay was given — the link to the parent is asked of it. */
    private FetchSupport lastFetchSupport;
    /** Whether the link to the parent fails on the next replay. */
    private boolean relinkFails;
    /** Whether the next chat replay answers "skipped" (the item is already in the repository). */
    private boolean replaySkips;

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

    private static final String SLACK_ATTACHMENT_ROW = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"F-1\","
            + "\"sourceObjectType\":\"attachment\",\"metadata\":{\"slackFileUrl\":\"https://files.slack.com/files-pri/T1-F-1/download/a.pdf\"}}";

    /** A Slack attachment row is bytes or nothing too: the chat import would create a content-less document from it. */
    @Test
    @DisplayName("a Slack attachment row without bytes is fetched again by the URL the orchestrator recorded")
    void aSlackAttachmentRowIsFetchedAgainByItsUrl() throws Exception {
        ResponseEntity<?> res = retry("slack", SourceArchetype.CHAT_CONTEXT, false, null, SLACK_ATTACHMENT_ROW,
                url -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), row -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals(List.of("slack:https://files.slack.com/files-pri/T1-F-1/download/a.pdf"), downloaded);
        assertEquals(1, executed.size());
        assertEquals("fresh bytes", read(requireBytes(executed.get(0))));
    }

    private static final String TEAMS_ATTACHMENT_ROW = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"att-1\","
            + "\"sourceObjectType\":\"attachment\",\"metadata\":{\"teamsFileUrl\":\"https://contoso.sharepoint.com/sites/x/a.pdf\"}}";

    @Test
    @DisplayName("a Teams attachment row without bytes is fetched again by the content URL the orchestrator recorded")
    void aTeamsAttachmentRowIsFetchedAgainByItsUrl() throws Exception {
        ResponseEntity<?> res = retry("teams", SourceArchetype.CHAT_CONTEXT, false, null, TEAMS_ATTACHMENT_ROW,
                url -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), row -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals(List.of("teams:https://contoso.sharepoint.com/sites/x/a.pdf"), downloaded);
        assertEquals("fresh bytes", read(requireBytes(executed.get(0))));
    }

    /** A row written before the orchestrator recorded the URL — every Slack attachment row from before this batch — cannot be fetched again: refused, kept. */
    @Test
    @DisplayName("a Slack attachment row without a recorded download URL is refused, not fetched by anything else")
    void aSlackAttachmentRowWithoutAUrlIsRefused() throws Exception {
        String noUrl = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"F-1\",\"sourceObjectType\":\"attachment\","
                + "\"metadata\":{\"channelId\":\"C1\"}}";
        ResponseEntity<?> res = retry("slack", SourceArchetype.CHAT_CONTEXT, false, null, noUrl,
                url -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), row -> { });

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("download URL"), String.valueOf(res.getBody()));
        assertTrue(executed.isEmpty() && downloaded.isEmpty(), "replayed or fetched without a URL: " + executed.size() + " / " + downloaded);
    }

    @Test
    @DisplayName("a Teams attachment row without a recorded content URL is refused, not fetched by anything else")
    void aTeamsAttachmentRowWithoutAUrlIsRefused() throws Exception {
        String noUrl = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"att-1\",\"sourceObjectType\":\"attachment\","
                + "\"metadata\":{\"channelId\":\"C1\"}}";
        ResponseEntity<?> res = retry("teams", SourceArchetype.CHAT_CONTEXT, false, null, noUrl,
                url -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), row -> { });

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("content URL"), String.valueOf(res.getBody()));
        assertTrue(executed.isEmpty() && downloaded.isEmpty(), "replayed or fetched without a URL: " + executed.size() + " / " + downloaded);
    }

    private static final String MATTERMOST_ATTACHMENT_ROW = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"f-1\","
            + "\"sourceObjectType\":\"attachment\",\"fileName\":\"a.pdf\",\"metadata\":{\"channelId\":\"C1\"}}";

    /** A Mattermost attachment row names the file id; the bytes come back from the connector's own endpoint. */
    @Test
    @DisplayName("a Mattermost attachment row without bytes is fetched again by its file id from the connector's endpoint")
    void aMattermostAttachmentRowIsFetchedAgainByItsFileId() throws Exception {
        ResponseEntity<?> res = retry("mattermost", SourceArchetype.CHAT_CONTEXT, false, null, MATTERMOST_ATTACHMENT_ROW,
                id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), row -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals(List.of("mattermost:https://mm.example.com#f-1"), downloaded);
        assertEquals(1, executed.size());
        assertEquals("fresh bytes", read(requireBytes(executed.get(0))));
    }

    /** A row written when the info call failed is named by the id alone; the replay asks the info again (Codex P2). */
    @Test
    @DisplayName("a Mattermost attachment row named by its id alone gets its name and type from the file info again")
    void aMattermostAttachmentRowNamedByItsIdGetsItsNameFromTheFileInfo() throws Exception {
        String namedById = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"f-1\",\"sourceObjectType\":\"attachment\","
                + "\"fileName\":\"f-1\",\"metadata\":{\"channelId\":\"C1\"}}";
        ResponseEntity<?> res = retry("mattermost", SourceArchetype.CHAT_CONTEXT, false, null, namedById,
                id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), row -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals("report.pdf", executed.get(0).getFileName(), "the replay kept the id as the name");
        assertEquals("application/pdf", executed.get(0).getMimeType(), "the replay has no type for the bytes");
        assertEquals("fresh bytes", read(requireBytes(executed.get(0))));
    }

    /**
     * An attachment row names the message document it belongs to; the replay links the two the
     * way the orchestrator does on the normal path (when the link cannot be made the row stays —
     * the locks below) — whatever the profile's relationship policy,
     * which the chat import's own linking obeys (Codex P2 on Mattermost: a replayed attachment
     * stood alone for ever).
     */
    @Test
    @DisplayName("a replayed attachment that names its message document is linked to it again")
    void aReplayedAttachmentIsLinkedToTheMessageItBelongsTo() throws Exception {
        String row = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"F-1\",\"sourceObjectType\":\"attachment\","
                + "\"metadata\":{\"slackFileUrl\":\"https://files.slack.com/files-pri/T1-F-1/download/a.pdf\",\"parentObjectId\":\"obj-msg\"}}";
        ResponseEntity<?> res = retry("slack", SourceArchetype.CHAT_CONTEXT, false, null, row,
                url -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), r -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        verify(lastFetchSupport).createRelationshipSafe(any(), eq("bedroom"), eq("obj-msg"), eq("obj-1"), isNull(), any(), any());
    }

    /** A replay whose link to the parent could not be made keeps its row: deleted, the attachment would stand alone with nothing to retry from (Codex P2). */
    @Test
    @DisplayName("a replay whose link to the parent fails keeps the entry for a retry")
    void aReplayWhoseParentLinkFailsKeepsTheEntry() throws Exception {
        relinkFails = true;
        String row = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"F-1\",\"sourceObjectType\":\"attachment\","
                + "\"metadata\":{\"slackFileUrl\":\"https://files.slack.com/files-pri/T1-F-1/download/a.pdf\",\"parentObjectId\":\"obj-msg\"}}";
        ResponseEntity<?> res = retry("slack", SourceArchetype.CHAT_CONTEXT, false, null, row,
                url -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), r -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("entryKept=true"), String.valueOf(res.getBody()));
        verify(jobService, never()).deleteDlqEntry(any());
    }

    /**
     * The same on the SKIP path: the attachment is already in the repository (a replay of a row
     * whose bytes were kept), and its link to the parent fails — the row stays. Only the success
     * path was measured, and a protection that dropped the skip half stayed green (Codex P2).
     */
    @Test
    @DisplayName("a replay that finds the item already there, and fails to link it to its parent, keeps the entry")
    void aSkippedReplayWhoseParentLinkFailsKeepsTheEntry() throws Exception {
        relinkFails = true;
        replaySkips = true;
        String row = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"F-1\",\"sourceObjectType\":\"attachment\","
                + "\"metadata\":{\"slackFileUrl\":\"https://files.slack.com/files-pri/T1-F-1/download/a.pdf\",\"parentObjectId\":\"obj-msg\"}}";
        ResponseEntity<?> res = retry("slack", SourceArchetype.CHAT_CONTEXT, true, "kept bytes", row,
                url -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), r -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("resolved-entry-kept"), String.valueOf(res.getBody()));
        verify(jobService, never()).deleteDlqEntry(any());
    }

    @Test
    @DisplayName("a replayed row that names no parent is linked to nothing")
    void aReplayedRowThatNamesNoParentIsLinkedToNothing() throws Exception {
        ResponseEntity<?> res = retry("slack", SourceArchetype.CHAT_CONTEXT, false, null, SLACK_ATTACHMENT_ROW,
                url -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), r -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        verify(lastFetchSupport, never()).createRelationshipSafe(any(), any(), any(), any(), any(), any(), any());
    }

    private static String chatMessageRow(String metadataJson) {
        return "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"1700000001.000000\","
                + "\"sourceObjectType\":\"chat_message\",\"fileName\":\"slack-1.txt\",\"metadata\":" + metadataJson + "}";
    }

    /**
     * A chat message row without its body — every body row written before the body was kept with
     * it — is refused, row kept: the chat import made an empty document from it, reported success
     * and deleted the row, the only record of the message (Codex P1 on Mattermost).
     */
    @Test
    @DisplayName("a chat message row without its body is refused, not replayed as an empty document")
    void aChatMessageRowWithoutItsBodyIsRefused() throws Exception {
        ResponseEntity<?> res = retry("slack", SourceArchetype.CHAT_CONTEXT, false, null, chatMessageRow("{\"messageText\":\"hello\"}"),
                url -> new ByteArrayInputStream(new byte[0]), r -> { });

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("without its body"), String.valueOf(res.getBody()));
        assertTrue(executed.isEmpty(), "replayed without its body: an empty document would have been imported");
    }

    /**
     * A gap row records messages the chat API no longer answers (Chatwork gives a room's latest 100):
     * there is no item behind it. Replayed, the chat import made an empty document and the only
     * record of the loss went with the row — refused, row kept.
     */
    @Test
    @DisplayName("a chat gap row is not replayed")
    void aChatGapRowIsNotReplayed() throws Exception {
        String row = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"gap:R1:1000-2001\","
                + "\"sourceObjectType\":\"chat_gap\",\"metadata\":{\"channelId\":\"R1\",\"gapAfterMessageId\":\"1000\","
                + "\"gapBeforeMessageId\":\"2001\"}}";
        // Marked, and not in the shape an unmarked gap row is refused by (never read): the mark alone refuses it.
        ResponseEntity<?> res = retry("chatwork", SourceArchetype.CHAT_CONTEXT, false, null, row,
                url -> new ByteArrayInputStream(new byte[0]), r -> { r.setGapRecord(true); r.setSourceNeverRead(false); });

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("records a possible gap"), String.valueOf(res.getBody()));
        assertTrue(executed.isEmpty(), "a gap row was replayed: " + executed);
        verify(jobService, never()).deleteDlqEntry(any());
    }

    /**
     * A gap row the build before the mark wrote: the gap's type, never read, nothing held, no mark.
     * Replayed, it was an empty document and the record went with the row (review, P2).
     */
    @Test
    @DisplayName("a gap row written before gap rows were marked is not replayed either")
    void aGapRowFromBeforeTheMarkIsNotReplayed() throws Exception {
        String row = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"gap:R1:1000-2001\","
                + "\"sourceObjectType\":\"chat_gap\",\"metadata\":{\"channelId\":\"R1\",\"gapAfterMessageId\":\"1000\","
                + "\"gapBeforeMessageId\":\"2001\"}}";
        ResponseEntity<?> res = retry("chatwork", SourceArchetype.CHAT_CONTEXT, false, null, row,
                url -> new ByteArrayInputStream(new byte[0]), r -> { });

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("records a possible gap"), String.valueOf(res.getBody()));
        assertTrue(executed.isEmpty(), "an unmarked gap row was replayed: " + executed);
        verify(jobService, never()).deleteDlqEntry(any());
    }

    /**
     * A row is a gap record by the mark the service writes, not by its type: sourceObjectType is the
     * caller's string, and keyed on it a genuine item that carried "chat_gap" was refused for ever
     * (review, P2).
     */
    @Test
    @DisplayName("a row that only names the gap type is replayed like any other")
    void aRowThatOnlyNamesTheGapTypeIsReplayed() throws Exception {
        String row = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"m-1\","
                + "\"sourceObjectType\":\"chat_gap\",\"fileName\":\"m-1.txt\",\"metadata\":{\"channelId\":\"R1\"}}";
        ResponseEntity<?> res = retry("chatwork", SourceArchetype.CHAT_CONTEXT, true, "the message text", row,
                url -> new ByteArrayInputStream(new byte[0]), r -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals(1, executed.size(), "a genuine item was refused as a gap: " + res.getBody());
    }

    /** A row that does not say what the text was is not read as an empty message. */
    @Test
    @DisplayName("a chat message row that does not record its text is refused too")
    void aChatMessageRowThatDoesNotRecordItsTextIsRefused() throws Exception {
        ResponseEntity<?> res = retry("slack", SourceArchetype.CHAT_CONTEXT, false, null, chatMessageRow("{\"channelId\":\"C1\"}"),
                url -> new ByteArrayInputStream(new byte[0]), r -> { });

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(executed.isEmpty(), "replayed without its body");
    }

    @Test
    @DisplayName("a chat message row that carries its body is replayed with it")
    void aChatMessageRowWithItsBodyIsReplayedWithIt() throws Exception {
        ResponseEntity<?> res = retry("slack", SourceArchetype.CHAT_CONTEXT, true, "hello", chatMessageRow("{\"messageText\":\"hello\"}"),
                url -> new ByteArrayInputStream(new byte[0]), r -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals("hello", read(requireBytes(executed.get(0))));
    }

    /**
     * A message that had no text: an empty document is the faithful replay, not a refusal. Nor is
     * a message row an attachment — nothing is fetched again for it. (This replaces a lock that
     * said every message row without bytes still replays: that was the empty-document defect.)
     */
    @Test
    @DisplayName("a chat message row recording an empty message is replayed as one, and nothing is fetched for it")
    void aChatMessageRowRecordingAnEmptyMessageIsReplayed() throws Exception {
        ResponseEntity<?> res = retry("teams", SourceArchetype.CHAT_CONTEXT, false, null, chatMessageRow("{\"messageText\":\"\"}"),
                url -> new ByteArrayInputStream(new byte[0]), r -> { });

        assertEquals(HttpStatus.OK, res.getStatusCode(), String.valueOf(res.getBody()));
        assertEquals(1, executed.size());
        assertTrue(downloaded.isEmpty(), "a message row was fetched as if it were an attachment: " + downloaded);
    }

    @Test
    @DisplayName("a Mattermost attachment row that names no file id is refused, not fetched by anything else")
    void aMattermostAttachmentRowWithoutAFileIdIsRefused() throws Exception {
        String noId = "{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\",\"sourceObjectId\":\"\",\"sourceObjectType\":\"attachment\","
                + "\"metadata\":{\"channelId\":\"C1\"}}";
        ResponseEntity<?> res = retry("mattermost", SourceArchetype.CHAT_CONTEXT, false, null, noId,
                id -> new ByteArrayInputStream("fresh bytes".getBytes(StandardCharsets.UTF_8)), row -> { });

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), String.valueOf(res.getBody()));
        assertTrue(String.valueOf(res.getBody()).contains("file id"), String.valueOf(res.getBody()));
        assertTrue(executed.isEmpty() && downloaded.isEmpty(), "replayed or fetched without a file id: " + executed.size() + " / " + downloaded);
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
