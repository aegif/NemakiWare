package jp.aegif.nemaki.rest.purview.sync;

import jp.aegif.nemaki.rest.purview.publish.PurviewArchivePublishService;
import jp.aegif.nemaki.rest.purview.publish.PurviewCloudMetadataPublishService;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorState;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterState;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import jp.aegif.nemaki.rest.purview.publish.PurviewDocumentPublishService;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockStateService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerService;
import jp.aegif.nemaki.rest.purview.state.PurviewTombstoneStateService;
import jp.aegif.nemaki.rest.purview.publish.PurviewTypeDefinitionPublishService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
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
    private PurviewDeadLetterStateService deadLetterStateService;
    private PurviewDocumentPublishService documentPublishService;
    private PurviewArchivePublishService archivePublishService;
    private PurviewCloudMetadataPublishService cloudMetadataPublishService;
    private PurviewTypeDefinitionPublishService typeDefinitionPublishService;
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
        deadLetterStateService = mock(PurviewDeadLetterStateService.class);
        documentPublishService = mock(PurviewDocumentPublishService.class);
        archivePublishService = mock(PurviewArchivePublishService.class);
        cloudMetadataPublishService = mock(PurviewCloudMetadataPublishService.class);
        typeDefinitionPublishService = mock(PurviewTypeDefinitionPublishService.class);
        purviewConfig = mock(PurviewConfig.class);
        contentDaoService = mock(ContentDaoService.class);

        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorStateService.saveCursorState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tombstoneStateService.saveTombstoneState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deadLetterStateService.saveDeadLetterState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deadLetterStateService.countDeadLetterStates("bedroom", "content-change-log")).thenReturn(0);
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);
        when(documentPublishService.upsertContents(any(), anyList())).thenAnswer(invocation -> invocation.getArgument(1, List.class).size());
        when(archivePublishService.syncRepositoryArchivesIfChanged(any(), any()))
                .thenReturn(new PurviewArchiveSyncResult("", false, 0, 0));
        when(cloudMetadataPublishService.syncRepositoryCloudMetadataIfChanged(any(), any()))
                .thenReturn(new PurviewCloudMetadataSyncResult("", false, 0, 0));
        when(typeDefinitionPublishService.syncRepositoryTypeDefinitionsIfChanged(any(), any()))
                .thenReturn(new PurviewTypeDefinitionSyncResult("", false, 0, 0));
        when(purviewConfig.getDeleteResolutionDelayMs()).thenReturn(5000L);
        when(cursorStateService.getCursorState("bedroom", "content-change-log")).thenReturn(new PurviewCursorState(
                "bedroom", "content-change-log", "", "changeToken", "", "", "", "", 0, 0));
        when(cursorStateService.getCursorState("bedroom", "archive-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "archive-snapshot", "", "snapshot", "", "", "", "", 0, 0));
        when(cursorStateService.getCursorState("bedroom", "cloud-metadata-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "cloud-metadata-snapshot", "", "snapshot", "", "", "", "", 0, 0));
        when(cursorStateService.getCursorState("bedroom", "type-definition-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "type-definition-snapshot", "", "snapshot", "", "", "", "", 0, 0));

        service = new PurviewIncrementalSyncServiceImpl(
                purviewConfig,
                schemaPlannerService,
                lockStateService,
                jobStateService,
                cursorStateService,
                tombstoneStateService,
                deadLetterStateService,
                documentPublishService,
                archivePublishService,
                cloudMetadataPublishService,
                typeDefinitionPublishService,
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
        verify(cursorStateService, times(4)).saveCursorState(cursorCaptor.capture());
        PurviewCursorState contentCursorState = cursorCaptor.getAllValues().stream()
                .filter(state -> "content-change-log".equals(state.getStreamKind()))
                .findFirst()
                .orElseThrow();
        assertEquals("103", contentCursorState.getCursor());
        assertEquals("", contentCursorState.getLastErrorAt());
        assertEquals("", contentCursorState.getLastErrorMessage());
        assertEquals(0, contentCursorState.getConsecutiveFailureCount());
        verify(documentPublishService, times(3)).upsertContents(eq("bedroom"), argThat(contents -> contents.size() == 1));
        verify(lockStateService).releaseRepositoryLock("bedroom", "INCREMENTAL_SYNC", result.getJobId());
    }

    @Test
    public void testStartIncrementalSyncSyncsTypeDefinitionsWhenSnapshotChanges() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(typeDefinitionPublishService.syncRepositoryTypeDefinitionsIfChanged("bedroom", "old-snapshot"))
                .thenReturn(new PurviewTypeDefinitionSyncResult("new-snapshot", true, 2, 1));
        when(cursorStateService.getCursorState("bedroom", "type-definition-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "type-definition-snapshot", "old-snapshot", "snapshot",
                "2026-03-20T01:00:00Z", "2026-03-20T01:00:00Z", "", "", 0, 0));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(3, result.getProcessedCount());
        verify(typeDefinitionPublishService).syncRepositoryTypeDefinitionsIfChanged("bedroom", "old-snapshot");
        verify(cursorStateService).saveCursorState(argThat(state ->
                "type-definition-snapshot".equals(state.getStreamKind())
                        && "new-snapshot".equals(state.getCursor())
                        && "snapshot".equals(state.getCursorKind())));
    }

    @Test
    public void testStartIncrementalSyncSyncsArchivesWhenSnapshotChanges() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(archivePublishService.syncRepositoryArchivesIfChanged("bedroom", "archive-old"))
                .thenReturn(new PurviewArchiveSyncResult("archive-new", true, 2, 1));
        when(cursorStateService.getCursorState("bedroom", "archive-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "archive-snapshot", "archive-old", "snapshot",
                "2026-03-20T01:00:00Z", "2026-03-20T01:00:00Z", "", "", 0, 0));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(3, result.getProcessedCount());
        verify(archivePublishService).syncRepositoryArchivesIfChanged("bedroom", "archive-old");
        verify(cursorStateService).saveCursorState(argThat(state ->
                "archive-snapshot".equals(state.getStreamKind())
                        && "archive-new".equals(state.getCursor())
                        && "snapshot".equals(state.getCursorKind())));
    }

    @Test
    public void testStartIncrementalSyncSyncsCloudMetadataWhenSnapshotChanges() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cloudMetadataPublishService.syncRepositoryCloudMetadataIfChanged("bedroom", "cloud-old"))
                .thenReturn(new PurviewCloudMetadataSyncResult("cloud-new", true, 2, 1));
        when(cursorStateService.getCursorState("bedroom", "cloud-metadata-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "cloud-metadata-snapshot", "cloud-old", "snapshot",
                "2026-03-20T01:00:00Z", "2026-03-20T01:00:00Z", "", "", 0, 0));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(3, result.getProcessedCount());
        verify(cloudMetadataPublishService).syncRepositoryCloudMetadataIfChanged("bedroom", "cloud-old");
        verify(cursorStateService).saveCursorState(argThat(state ->
                "cloud-metadata-snapshot".equals(state.getStreamKind())
                        && "cloud-new".equals(state.getCursor())
                        && "snapshot".equals(state.getCursorKind())));
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
        verify(cursorStateService, times(4)).saveCursorState(cursorCaptor.capture());
        PurviewCursorState contentCursorState = cursorCaptor.getAllValues().stream()
                .filter(state -> "content-change-log".equals(state.getStreamKind()))
                .findFirst()
                .orElseThrow();
        assertEquals("100", contentCursorState.getCursor());
        assertEquals("2026-03-20T00:55:00Z", contentCursorState.getLastSuccessAt());
        assertTrue(contentCursorState.getLastErrorMessage().contains("change log unavailable"));
        assertEquals(1, contentCursorState.getConsecutiveFailureCount());
        assertEquals(1, contentCursorState.getDeadLetterCount());
        verify(lockStateService).releaseRepositoryLock("bedroom", "INCREMENTAL_SYNC", result.getJobId());
    }

    @Test
    public void testStartIncrementalSyncStoresTypeCursorFailureStateWhenTypeSyncFails() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cursorStateService.getCursorState("bedroom", "type-definition-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "type-definition-snapshot", "snapshot-1", "snapshot",
                "2026-03-20T01:00:00Z", "2026-03-20T00:55:00Z", "", "", 0, 0));
        when(typeDefinitionPublishService.syncRepositoryTypeDefinitionsIfChanged("bedroom", "snapshot-1"))
                .thenThrow(new IllegalStateException("type sync unavailable"));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        verify(cursorStateService).saveCursorState(argThat(state ->
                "type-definition-snapshot".equals(state.getStreamKind())
                        && "snapshot-1".equals(state.getCursor())
                        && state.getLastErrorMessage().contains("type sync unavailable")
                        && state.getConsecutiveFailureCount() == 1));
    }

    @Test
    public void testStartIncrementalSyncMovesArchiveFailureToDeadLetterAndContinues() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cursorStateService.getCursorState("bedroom", "archive-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "archive-snapshot", "archive-1", "snapshot",
                "2026-03-20T01:00:00Z", "2026-03-20T00:55:00Z", "", "", 0, 0));
        when(deadLetterStateService.getDeadLetterState("bedroom", "archive-snapshot", "bedroom"))
                .thenReturn(new PurviewDeadLetterState("bedroom", "archive-snapshot", "bedroom",
                        "", "", "", "", 0, "", ""));
        when(deadLetterStateService.countDeadLetterStates("bedroom", "archive-snapshot")).thenReturn(1);
        when(archivePublishService.syncRepositoryArchivesIfChanged("bedroom", "archive-1"))
                .thenThrow(new IllegalStateException("archive sync unavailable"));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("COMPLETED_WITH_ERRORS", result.getStatus());
        assertEquals(1, result.getFailedCount());
        verify(cursorStateService).saveCursorState(argThat(state ->
                "archive-snapshot".equals(state.getStreamKind())
                        && "archive-1".equals(state.getCursor())
                        && state.getLastErrorMessage().contains("archive sync unavailable")
                        && state.getConsecutiveFailureCount() == 1
                        && state.getDeadLetterCount() == 1));
        verify(deadLetterStateService).saveDeadLetterState(argThat(state ->
                "bedroom".equals(state.getRepositoryId())
                        && "archive-snapshot".equals(state.getStreamKind())
                        && "bedroom".equals(state.getEntryKey())
                        && "archive-1".equals(state.getCheckpoint())
                        && state.getFailureCount() == 1
                        && state.getErrorSummary().contains("archive sync unavailable")));
    }

    @Test
    public void testStartIncrementalSyncMovesCloudMetadataFailureToDeadLetterAndContinues() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cursorStateService.getCursorState("bedroom", "cloud-metadata-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "cloud-metadata-snapshot", "cloud-1", "snapshot",
                "2026-03-20T01:00:00Z", "2026-03-20T00:55:00Z", "", "", 0, 0));
        when(deadLetterStateService.getDeadLetterState("bedroom", "cloud-metadata-snapshot", "bedroom"))
                .thenReturn(new PurviewDeadLetterState("bedroom", "cloud-metadata-snapshot", "bedroom",
                        "", "", "", "", 0, "", ""));
        when(deadLetterStateService.countDeadLetterStates("bedroom", "cloud-metadata-snapshot")).thenReturn(1);
        when(cloudMetadataPublishService.syncRepositoryCloudMetadataIfChanged("bedroom", "cloud-1"))
                .thenThrow(new IllegalStateException("cloud sync unavailable"));

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("COMPLETED_WITH_ERRORS", result.getStatus());
        assertEquals(1, result.getFailedCount());
        verify(cursorStateService).saveCursorState(argThat(state ->
                "cloud-metadata-snapshot".equals(state.getStreamKind())
                        && "cloud-1".equals(state.getCursor())
                        && state.getLastErrorMessage().contains("cloud sync unavailable")
                        && state.getConsecutiveFailureCount() == 1
                        && state.getDeadLetterCount() == 1));
        verify(deadLetterStateService).saveDeadLetterState(argThat(state ->
                "bedroom".equals(state.getRepositoryId())
                        && "cloud-metadata-snapshot".equals(state.getStreamKind())
                        && "bedroom".equals(state.getEntryKey())
                        && "cloud-1".equals(state.getCheckpoint())
                        && state.getFailureCount() == 1
                        && state.getErrorSummary().contains("cloud sync unavailable")));
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

    @Test
    public void testStartIncrementalSyncMovesFailedObjectToDeadLetterAndContinues() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        Change change1 = createChange("101");
        Change change2 = createChange("102");
        when(contentDaoService.getLatestChanges("bedroom", null, 100)).thenReturn(List.of(change1, change2));
        when(contentDaoService.getContentsByIds(eq("bedroom"), anyList()))
                .thenReturn(Map.of(
                        "object-101", document("object-101"),
                        "object-102", document("object-102")));
        when(deadLetterStateService.getDeadLetterState("bedroom", "content-change-log", "object-101"))
                .thenReturn(new PurviewDeadLetterState("bedroom", "content-change-log", "object-101",
                        "", "", "", "", 0, "", ""));
        when(deadLetterStateService.getDeadLetterState("bedroom", "content-change-log", "object-102"))
                .thenReturn(new PurviewDeadLetterState("bedroom", "content-change-log", "object-102",
                        "", "", "", "", 0, "", ""));
        when(deadLetterStateService.countDeadLetterStates("bedroom", "content-change-log")).thenReturn(1);
        when(documentPublishService.upsertContents(eq("bedroom"), argThat(contents ->
                contents.size() == 1 && "object-101".equals(contents.get(0).getId()))))
                        .thenThrow(new IllegalStateException("publish failed"));
        when(documentPublishService.upsertContents(eq("bedroom"), argThat(contents ->
                contents.size() == 1 && "object-102".equals(contents.get(0).getId()))))
                        .thenReturn(1);

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("COMPLETED_WITH_ERRORS", result.getStatus());
        assertEquals(2, result.getProcessedCount());
        assertEquals(1, result.getFailedCount());
        verify(deadLetterStateService).saveDeadLetterState(argThat(state ->
                "bedroom".equals(state.getRepositoryId())
                        && "content-change-log".equals(state.getStreamKind())
                        && "object-101".equals(state.getEntryKey())
                        && "nemaki_document".equals(state.getTypeName())
                        && "101".equals(state.getCheckpoint())
                        && state.getFailureCount() == 1
                        && state.getErrorSummary().contains("publish failed")));
        verify(deadLetterStateService).deleteDeadLetterState("bedroom", "content-change-log", "object-102");
        verify(cursorStateService).saveCursorState(argThat(state ->
                "content-change-log".equals(state.getStreamKind())
                        && "102".equals(state.getCursor())
                        && state.getDeadLetterCount() == 1));
    }

    @Test
    public void testGetContentsByIdsIsBatchedWhenObjectCountExceedsBatchSize() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        // Create 75 changes to verify batching at CONTENT_BATCH_SIZE=50
        List<Change> changes = new ArrayList<>();
        java.util.LinkedHashMap<String, Content> allContents = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 75; i++) {
            String token = String.valueOf(i);
            changes.add(createChange(token));
            allContents.put("object-" + token, document("object-" + token));
        }
        when(contentDaoService.getLatestChanges("bedroom", null, 100)).thenReturn(changes);

        // Mock: return matching contents for ANY batch of objectIds
        when(contentDaoService.getContentsByIds(eq("bedroom"), anyList()))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(1);
                    java.util.LinkedHashMap<String, Content> batch = new java.util.LinkedHashMap<>();
                    for (String id : ids) {
                        Content c = allContents.get(id);
                        if (c != null) batch.put(id, c);
                    }
                    return batch;
                });

        PurviewJobState result = service.startIncrementalSync("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(75, result.getProcessedCount());

        // Verify getContentsByIds was called multiple times (batched)
        ArgumentCaptor<List<String>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(contentDaoService, times(2)).getContentsByIds(eq("bedroom"), batchCaptor.capture());

        List<List<String>> batches = batchCaptor.getAllValues();
        assertEquals(50, batches.get(0).size());
        assertEquals(25, batches.get(1).size());
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
