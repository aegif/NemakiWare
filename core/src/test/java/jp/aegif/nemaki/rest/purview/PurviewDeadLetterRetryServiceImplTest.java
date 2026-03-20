package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Document;

public class PurviewDeadLetterRetryServiceImplTest {

    private PurviewSchemaPlannerService schemaPlannerService;
    private PurviewLockStateService lockStateService;
    private PurviewJobStateService jobStateService;
    private PurviewCursorStateService cursorStateService;
    private PurviewDeadLetterStateService deadLetterStateService;
    private PurviewDocumentPublishService documentPublishService;
    private PurviewArchivePublishService archivePublishService;
    private PurviewCloudMetadataPublishService cloudMetadataPublishService;
    private ContentDaoService contentDaoService;
    private Document document;
    private PurviewDeadLetterRetryServiceImpl service;

    @BeforeEach
    public void setUp() {
        schemaPlannerService = mock(PurviewSchemaPlannerService.class);
        lockStateService = mock(PurviewLockStateService.class);
        jobStateService = mock(PurviewJobStateService.class);
        cursorStateService = mock(PurviewCursorStateService.class);
        deadLetterStateService = mock(PurviewDeadLetterStateService.class);
        documentPublishService = mock(PurviewDocumentPublishService.class);
        archivePublishService = mock(PurviewArchivePublishService.class);
        cloudMetadataPublishService = mock(PurviewCloudMetadataPublishService.class);
        contentDaoService = mock(ContentDaoService.class);

        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorStateService.saveCursorState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "schema-hash", "1", "schema-hash", false,
                List.of(), List.of(), List.of()));
        when(cursorStateService.getCursorState("bedroom", "content-change-log")).thenReturn(new PurviewCursorState(
                "bedroom", "content-change-log", "token-101", "changeToken",
                "2026-03-20T10:00:00Z", "2026-03-20T09:59:00Z", "", "", 0, 1));
        when(deadLetterStateService.listDeadLetterStates("bedroom")).thenReturn(List.of(new PurviewDeadLetterState(
                "bedroom",
                "content-change-log",
                "object-101",
                "nemaki_document",
                "nemaki://bedroom/objects/object-101",
                "2026-03-20T10:00:00Z",
                "2026-03-20T10:01:00Z",
                1,
                "101",
                "publish failed")));
        when(deadLetterStateService.countDeadLetterStates("bedroom", "content-change-log")).thenReturn(0);

        document = new Document();
        document.setId("object-101");
        document.setName("object-101");
        when(contentDaoService.getContent("bedroom", "object-101")).thenReturn(document);
        when(documentPublishService.upsertContents("bedroom", List.of(document))).thenReturn(1);

        service = new PurviewDeadLetterRetryServiceImpl(
                schemaPlannerService,
                lockStateService,
                jobStateService,
                cursorStateService,
                deadLetterStateService,
                documentPublishService,
                archivePublishService,
                cloudMetadataPublishService,
                contentDaoService);
    }

    @Test
    public void testStartRetryFailedRepublishesDeadLetterAndClearsCursorCount() {
        PurviewJobState result = service.startRetryFailed("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1, result.getProcessedCount());
        assertEquals(0, result.getFailedCount());
        verify(documentPublishService).upsertContents("bedroom", List.of(document));
        verify(deadLetterStateService).deleteDeadLetterState("bedroom", "content-change-log", "object-101");
        verify(cursorStateService).saveCursorState(argThat(state ->
                "content-change-log".equals(state.getStreamKind())
                        && state.getDeadLetterCount() == 0
                        && "token-101".equals(state.getCursor())));
    }

    @Test
    public void testStartRetryFailedRetriesCloudMetadataDeadLetterAndUpdatesSnapshotCursor() {
        when(deadLetterStateService.listDeadLetterStates("bedroom")).thenReturn(List.of(new PurviewDeadLetterState(
                "bedroom",
                "cloud-metadata-snapshot",
                "bedroom",
                "cloud-metadata-snapshot",
                "nemaki://bedroom/purview/cloud-metadata-snapshot",
                "2026-03-20T10:00:00Z",
                "2026-03-20T10:01:00Z",
                1,
                "cloud-old",
                "publish failed")));
        when(cursorStateService.getCursorState("bedroom", "cloud-metadata-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "cloud-metadata-snapshot", "cloud-old", "snapshot",
                "2026-03-20T10:00:00Z", "2026-03-20T09:59:00Z", "", "", 0, 1));
        when(deadLetterStateService.countDeadLetterStates("bedroom", "cloud-metadata-snapshot")).thenReturn(0);
        when(cloudMetadataPublishService.syncRepositoryCloudMetadataIfChanged("bedroom", "cloud-old"))
                .thenReturn(new PurviewCloudMetadataSyncResult("cloud-new", true, 2, 1));

        PurviewJobState result = service.startRetryFailed("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1, result.getProcessedCount());
        verify(cloudMetadataPublishService).syncRepositoryCloudMetadataIfChanged("bedroom", "cloud-old");
        verify(deadLetterStateService).deleteDeadLetterState("bedroom", "cloud-metadata-snapshot", "bedroom");
        verify(cursorStateService).saveCursorState(argThat(state ->
                "cloud-metadata-snapshot".equals(state.getStreamKind())
                        && "cloud-new".equals(state.getCursor())
                        && state.getDeadLetterCount() == 0));
    }

    @Test
    public void testStartRetryFailedRetriesArchiveDeadLetterAndUpdatesSnapshotCursor() {
        when(deadLetterStateService.listDeadLetterStates("bedroom")).thenReturn(List.of(new PurviewDeadLetterState(
                "bedroom",
                "archive-snapshot",
                "bedroom",
                "archive-snapshot",
                "nemaki://bedroom/purview/archive-snapshot",
                "2026-03-20T10:00:00Z",
                "2026-03-20T10:01:00Z",
                1,
                "archive-old",
                "publish failed")));
        when(cursorStateService.getCursorState("bedroom", "archive-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "archive-snapshot", "archive-old", "snapshot",
                "2026-03-20T10:00:00Z", "2026-03-20T09:59:00Z", "", "", 0, 1));
        when(deadLetterStateService.countDeadLetterStates("bedroom", "archive-snapshot")).thenReturn(0);
        when(archivePublishService.syncRepositoryArchivesIfChanged("bedroom", "archive-old"))
                .thenReturn(new PurviewArchiveSyncResult("archive-new", true, 2, 1));

        PurviewJobState result = service.startRetryFailed("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1, result.getProcessedCount());
        verify(archivePublishService).syncRepositoryArchivesIfChanged("bedroom", "archive-old");
        verify(deadLetterStateService).deleteDeadLetterState("bedroom", "archive-snapshot", "bedroom");
        verify(cursorStateService).saveCursorState(argThat(state ->
                "archive-snapshot".equals(state.getStreamKind())
                        && "archive-new".equals(state.getCursor())
                        && state.getDeadLetterCount() == 0));
    }
}
