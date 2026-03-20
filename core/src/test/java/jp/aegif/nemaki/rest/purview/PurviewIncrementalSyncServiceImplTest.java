package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

public class PurviewIncrementalSyncServiceImplTest {

    private PurviewSchemaPlannerService schemaPlannerService;
    private PurviewLockStateService lockStateService;
    private PurviewJobStateService jobStateService;
    private PurviewCursorStateService cursorStateService;
    private PurviewTombstoneStateService tombstoneStateService;
    private PurviewDocumentPublishService documentPublishService;
    private PurviewConfig purviewConfig;
    private ContentDaoService contentDaoService;
    private PurviewIncrementalSyncServiceImpl service;

    @BeforeEach
    public void setUp() {
        schemaPlannerService = mock(PurviewSchemaPlannerService.class);
        lockStateService = mock(PurviewLockStateService.class);
        jobStateService = mock(PurviewJobStateService.class);
        cursorStateService = mock(PurviewCursorStateService.class);
        tombstoneStateService = mock(PurviewTombstoneStateService.class);
        documentPublishService = mock(PurviewDocumentPublishService.class);
        purviewConfig = mock(PurviewConfig.class);
        contentDaoService = mock(ContentDaoService.class);

        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorStateService.saveCursorState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tombstoneStateService.saveTombstoneState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);
        when(documentPublishService.upsertContents(any(), anyList())).thenAnswer(invocation -> invocation.getArgument(1, List.class).size());
        when(purviewConfig.getDeleteResolutionDelayMs()).thenReturn(5000L);
        when(cursorStateService.getCursorState("bedroom", "content-change-log")).thenReturn(new PurviewCursorState(
                "bedroom", "content-change-log", "", "changeToken", "", "", "", "", 0, 0));

        service = new PurviewIncrementalSyncServiceImpl(
                purviewConfig,
                schemaPlannerService,
                lockStateService,
                jobStateService,
                cursorStateService,
                tombstoneStateService,
                documentPublishService,
                contentDaoService);
    }

    @Test
    public void testStartIncrementalSyncRejectsWhenRepositoryLockIsAlreadyHeld() {
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(false);

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("already running"));
        verify(schemaPlannerService, never()).getSchemaDiff();
    }

    @Test
    public void testStartIncrementalSyncFailsFastWhenSchemaBootstrapIsRequired() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "", "", "1", "desired-hash", true,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("schema bootstrap"));
        verify(lockStateService).releaseRepositoryLock("bedroom", "INCREMENTAL_SYNC", result.getJobId());
    }

    @Test
    public void testStartIncrementalSyncAdvancesCursorFromLatestChangeToken() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cursorStateService.getCursorState("bedroom", "content-change-log")).thenReturn(new PurviewCursorState(
                "bedroom", "content-change-log", "100", "changeToken",
                "2026-03-20T01:00:00Z", "2026-03-20T01:00:00Z", "2026-03-20T00:50:00Z", "previous error", 2, 0));

        Change change1 = createChange("100");
        Change change2 = createFolderChange("101");
        Change change3 = createChange("102");
        Change change4 = createChange("103");
        when(contentDaoService.getLatestChanges("bedroom", "100", 101)).thenReturn(List.of(change1, change2, change3, change4));
        when(contentDaoService.getContentsByIds(eq("bedroom"), anyList()))
                .thenReturn(Map.of(
                        "object-101-folder", folder("object-101-folder"),
                        "object-102", document("object-102"),
                        "object-103", document("object-103")));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");
        ArgumentCaptor<PurviewCursorState> cursorCaptor = ArgumentCaptor.forClass(PurviewCursorState.class);

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(3, result.getProcessedCount());
        assertEquals("103", result.getCheckpoint());
        verify(contentDaoService).getLatestChanges("bedroom", "100", 101);
        verify(cursorStateService).saveCursorState(cursorCaptor.capture());
        assertEquals("103", cursorCaptor.getValue().getCursor());
        assertEquals("", cursorCaptor.getValue().getLastErrorAt());
        assertEquals("", cursorCaptor.getValue().getLastErrorMessage());
        assertEquals(0, cursorCaptor.getValue().getConsecutiveFailureCount());
        verify(documentPublishService).upsertContents(eq("bedroom"), argThat(contents ->
                contents.size() == 3
                        && "object-101-folder".equals(contents.get(0).getId())
                        && "object-102".equals(contents.get(1).getId())
                        && "object-103".equals(contents.get(2).getId())));
        verify(lockStateService).releaseRepositoryLock("bedroom", "INCREMENTAL_SYNC", result.getJobId());
    }

    @Test
    public void testStartIncrementalSyncStoresCursorFailureStateWhenChangeLogFetchFails() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cursorStateService.getCursorState("bedroom", "content-change-log")).thenReturn(new PurviewCursorState(
                "bedroom", "content-change-log", "100", "changeToken",
                "2026-03-20T01:00:00Z", "2026-03-20T00:55:00Z", "", "", 0, 1));
        when(contentDaoService.getLatestChanges(eq("bedroom"), eq("100"), eq(101)))
                .thenThrow(new RuntimeException("change log unavailable"));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");
        ArgumentCaptor<PurviewCursorState> cursorCaptor = ArgumentCaptor.forClass(PurviewCursorState.class);

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("change log unavailable"));
        assertEquals("100", result.getCheckpoint());
        assertEquals(1, result.getFailedCount());
        verify(cursorStateService).saveCursorState(cursorCaptor.capture());
        assertEquals("100", cursorCaptor.getValue().getCursor());
        assertEquals("2026-03-20T00:55:00Z", cursorCaptor.getValue().getLastSuccessAt());
        assertTrue(cursorCaptor.getValue().getLastErrorMessage().contains("change log unavailable"));
        assertEquals(1, cursorCaptor.getValue().getConsecutiveFailureCount());
        assertEquals(1, cursorCaptor.getValue().getDeadLetterCount());
        verify(lockStateService).releaseRepositoryLock("bedroom", "INCREMENTAL_SYNC", result.getJobId());
    }

    @Test
    public void testStartIncrementalSyncStagesTombstoneForDeletedChange() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "2", "current-hash", "2", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        Change deletedChange = createDeletedChange("101");
        when(contentDaoService.getLatestChanges("bedroom", null, 100)).thenReturn(List.of(deletedChange));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1, result.getProcessedCount());
        verify(tombstoneStateService).saveTombstoneState(argThat(state ->
                "bedroom".equals(state.getRepositoryId())
                        && "object-101".equals(state.getObjectId())
                        && "nemaki_document".equals(state.getTypeName())
                        && "101".equals(state.getChangeToken())
                        && "PENDING".equals(state.getStatus())
                        && Instant.parse(state.getDueAt()).equals(Instant.parse(state.getFirstSeenAt()).plusMillis(5000L))));
        verify(documentPublishService, never()).upsertContents(eq("bedroom"), anyList());
        verify(contentDaoService, never()).getContentsByIds(eq("bedroom"), anyList());
    }

    @Test
    public void testStartIncrementalSyncStagesFolderTombstoneUsingFolderTypeName() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "2", "current-hash", "2", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        Change deletedChange = createDeletedFolderChange("101");
        when(contentDaoService.getLatestChanges("bedroom", null, 100)).thenReturn(List.of(deletedChange));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        verify(tombstoneStateService).saveTombstoneState(argThat(state ->
                "nemaki_folder".equals(state.getTypeName())
                        && "nemaki://bedroom/objects/object-101".equals(state.getQualifiedName())));
    }

    private Change createChange(String token) {
        Change change = new Change();
        change.setToken(token);
        change.setObjectId("object-" + token);
        change.setChangeType(ChangeType.UPDATED);
        change.setBaseType("cmis:document");
        return change;
    }

    private Change createDeletedChange(String token) {
        Change change = new Change();
        change.setToken(token);
        change.setObjectId("object-" + token);
        change.setChangeType(ChangeType.DELETED);
        change.setBaseType("cmis:document");
        return change;
    }

    private Change createFolderChange(String token) {
        Change change = new Change();
        change.setToken(token);
        change.setObjectId("object-" + token + "-folder");
        change.setChangeType(ChangeType.UPDATED);
        change.setBaseType("cmis:folder");
        return change;
    }

    private Change createDeletedFolderChange(String token) {
        Change change = new Change();
        change.setToken(token);
        change.setObjectId("object-" + token);
        change.setChangeType(ChangeType.DELETED);
        change.setBaseType("cmis:folder");
        return change;
    }

    private Content document(String objectId) {
        Document document = new Document();
        document.setId(objectId);
        document.setName(objectId);
        return document;
    }

    private Content folder(String objectId) {
        Folder folder = new Folder();
        folder.setId(objectId);
        folder.setName(objectId);
        return folder;
    }
}
