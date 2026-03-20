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
}
