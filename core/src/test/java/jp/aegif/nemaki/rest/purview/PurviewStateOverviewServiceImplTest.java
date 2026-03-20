package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PurviewStateOverviewServiceImplTest {

    private PurviewStateStore stateStore;
    private PurviewStateOverviewServiceImpl service;

    @BeforeEach
    public void setUp() {
        stateStore = mock(PurviewStateStore.class);
        service = new PurviewStateOverviewServiceImpl(stateStore);
    }

    @Test
    public void testGetStateOverviewAggregatesSchemaJobsCursorsLocksAndTombstones() {
        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("purview.schema.state.NemakiWare.schemaVersion", "1");
        persisted.put("purview.schema.state.NemakiWare.schemaHash", "schema-hash");
        persisted.put("purview.schema.state.NemakiWare.lastAppliedAt", "2026-03-20T01:00:00Z");
        persisted.put("purview.schema.state.NemakiWare.lastAppliedBy", "admin");
        persisted.put("purview.schema.state.NemakiWare.lastDiffSummary", "applied");
        persisted.put("purview.job.job-001.jobKind", "FULL_SYNC");
        persisted.put("purview.job.job-001.repositoryId", "bedroom");
        persisted.put("purview.job.job-001.status", "RUNNING");
        persisted.put("purview.job.job-001.startedAt", "2026-03-20T02:00:00Z");
        persisted.put("purview.job.job-001.endedAt", "");
        persisted.put("purview.job.job-001.processedCount", 3);
        persisted.put("purview.job.job-001.failedCount", 1);
        persisted.put("purview.job.job-001.checkpoint", "token-3");
        persisted.put("purview.job.job-001.errorSummary", "one failed");
        persisted.put("purview.job.job-002.jobKind", "TYPE_BOOTSTRAP");
        persisted.put("purview.job.job-002.repositoryId", "collection:NemakiWare");
        persisted.put("purview.job.job-002.status", "COMPLETED");
        persisted.put("purview.job.job-002.startedAt", "2026-03-20T03:00:00Z");
        persisted.put("purview.job.job-002.endedAt", "2026-03-20T03:00:05Z");
        persisted.put("purview.job.job-002.processedCount", 5);
        persisted.put("purview.job.job-002.failedCount", 0);
        persisted.put("purview.job.job-002.checkpoint", "schema-hash");
        persisted.put("purview.job.job-002.errorSummary", "");
        persisted.put("purview.cursor.state.bedroom.content-change-log.cursor", "token-100");
        persisted.put("purview.cursor.state.bedroom.content-change-log.cursorKind", "changeToken");
        persisted.put("purview.cursor.state.bedroom.content-change-log.lastRunAt", "2026-03-20T04:00:00Z");
        persisted.put("purview.cursor.state.bedroom.content-change-log.lastSuccessAt", "2026-03-20T04:00:01Z");
        persisted.put("purview.cursor.state.bedroom.content-change-log.lastErrorAt", "");
        persisted.put("purview.cursor.state.bedroom.content-change-log.lastErrorMessage", "");
        persisted.put("purview.cursor.state.bedroom.content-change-log.consecutiveFailureCount", 0);
        persisted.put("purview.cursor.state.bedroom.content-change-log.deadLetterCount", 0);
        persisted.put("purview.lock.repository.bedroom.FULL_SYNC.ownerJobId", "job-001");
        persisted.put("purview.lock.repository.bedroom.FULL_SYNC.lockedAt", "2026-03-20T02:00:00Z");
        persisted.put("purview.tombstone.state.bedroom.doc-001.typeName", "nemaki_document");
        persisted.put("purview.tombstone.state.bedroom.doc-001.qualifiedName", "nemaki://bedroom/objects/doc-001");
        persisted.put("purview.tombstone.state.bedroom.doc-001.changeToken", "token-101");
        persisted.put("purview.tombstone.state.bedroom.doc-001.firstSeenAt", "2026-03-20T05:00:00Z");
        persisted.put("purview.tombstone.state.bedroom.doc-001.dueAt", "2026-03-20T05:00:05Z");
        persisted.put("purview.tombstone.state.bedroom.doc-001.status", "PENDING");

        when(stateStore.getAll()).thenReturn(persisted);

        PurviewStateOverview overview = service.getStateOverview("NemakiWare");

        assertEquals("NemakiWare", overview.getCollection());
        assertEquals("schema-hash", overview.getSchemaState().getSchemaHash());
        assertEquals(List.of("job-002", "job-001"),
                overview.getJobs().stream().map(PurviewJobState::getJobId).toList());
        assertEquals("token-100", overview.getCursors().get(0).getCursor());
        assertEquals("bedroom", overview.getLocks().get(0).getRepositoryId());
        assertTrue(overview.getLocks().get(0).isLocked());
        assertEquals("doc-001", overview.getTombstones().get(0).getObjectId());
    }

    @Test
    public void testGetStateOverviewReturnsEmptyCollectionsWhenNothingIsPersisted() {
        when(stateStore.getAll()).thenReturn(Map.of());

        PurviewStateOverview overview = service.getStateOverview("NemakiWare");

        assertEquals("NemakiWare", overview.getCollection());
        assertEquals("", overview.getSchemaState().getSchemaHash());
        assertTrue(overview.getJobs().isEmpty());
        assertTrue(overview.getCursors().isEmpty());
        assertTrue(overview.getLocks().isEmpty());
        assertTrue(overview.getTombstones().isEmpty());
    }
}
