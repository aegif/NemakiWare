package jp.aegif.nemaki.rest.purview;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewArchiveReconciliationServiceImpl implements PurviewArchiveReconciliationService {

    private static final String JOB_KIND = "ARCHIVE_RECONCILIATION";
    private static final String TOMBSTONE_STATUS_ARCHIVED = "ARCHIVED";
    private static final String TOMBSTONE_STATUS_ERROR = "ERROR";

    private final PurviewConfig purviewConfig;
    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewLockStateService lockStateService;
    private final PurviewJobStateService jobStateService;
    private final PurviewTombstoneStateService tombstoneStateService;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final ContentDaoService contentDaoService;

    public PurviewArchiveReconciliationServiceImpl(
            PurviewConfig purviewConfig,
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewLockStateService lockStateService,
            PurviewJobStateService jobStateService,
            PurviewTombstoneStateService tombstoneStateService,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService) {
        this.purviewConfig = purviewConfig;
        this.schemaPlannerService = schemaPlannerService;
        this.lockStateService = lockStateService;
        this.jobStateService = jobStateService;
        this.tombstoneStateService = tombstoneStateService;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.contentDaoService = contentDaoService;
    }

    @Override
    public PurviewJobState startArchiveReconciliation(String repositoryId, String requestedBy) {
        String now = Instant.now().toString();
        String jobId = UUID.randomUUID().toString();
        if (!lockStateService.tryAcquireRepositoryLock(repositoryId, JOB_KIND, jobId, now)) {
            return jobStateService.saveJobState(new PurviewJobState(
                    jobId,
                    JOB_KIND,
                    repositoryId,
                    "REJECTED",
                    now,
                    now,
                    0,
                    0,
                    "",
                    "Purview archive reconciliation is already running for repository " + repositoryId));
        }

        try {
            PurviewSchemaDiff diff = schemaPlannerService.getSchemaDiff();
            if (diff.isApplyRequired()) {
                return jobStateService.saveJobState(new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "FAILED",
                        now,
                        now,
                        0,
                        0,
                        "",
                        "Purview schema bootstrap is required before archive reconciliation"));
            }

            List<PurviewTombstoneState> archivedTombstones = listArchivedTombstones(repositoryId);
            int failedCount = 0;
            List<String> failures = new ArrayList<>();

            for (PurviewTombstoneState tombstoneState : archivedTombstones) {
                try {
                    reconcileArchivedTombstone(repositoryId, tombstoneState);
                } catch (RuntimeException e) {
                    failedCount++;
                    tombstoneStateService.saveTombstoneState(copyWithStatus(tombstoneState, TOMBSTONE_STATUS_ERROR));
                    failures.add(tombstoneState.getObjectId() + ": " + buildErrorSummary(e));
                }
            }

            return jobStateService.saveJobState(new PurviewJobState(
                    jobId,
                    JOB_KIND,
                    repositoryId,
                    failedCount == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS",
                    now,
                    Instant.now().toString(),
                    archivedTombstones.size(),
                    failedCount,
                    archivedTombstones.isEmpty() ? "" : archivedTombstones.get(archivedTombstones.size() - 1).getChangeToken(),
                    summarizeFailures(failures)));
        } finally {
            lockStateService.releaseRepositoryLock(repositoryId, JOB_KIND, jobId);
        }
    }

    private List<PurviewTombstoneState> listArchivedTombstones(String repositoryId) {
        return tombstoneStateService.listTombstoneStates(repositoryId).stream()
                .filter(state -> TOMBSTONE_STATUS_ARCHIVED.equals(state.getStatus()))
                .toList();
    }

    private void reconcileArchivedTombstone(String repositoryId, PurviewTombstoneState tombstoneState) {
        Content liveContent = contentDaoService.getContentFresh(repositoryId, tombstoneState.getObjectId());
        if (liveContent != null) {
            tombstoneStateService.deleteTombstoneState(repositoryId, tombstoneState.getObjectId());
            return;
        }

        Archive archive = contentDaoService.getArchiveByOriginalId(repositoryId, tombstoneState.getObjectId());
        if (archive == null) {
            throw new IllegalStateException("Archive not found for original object " + tombstoneState.getObjectId());
        }

        List<java.util.Map<String, Object>> entities = List.of(
                entityPayloadFactory.buildArchivedDocumentEntity(repositoryId, archive),
                entityPayloadFactory.buildArchiveEntity(repositoryId, archive));
        PurviewEntityPublishResult result = publishEntities(entities);
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getMessage());
        }

        tombstoneStateService.deleteTombstoneState(repositoryId, tombstoneState.getObjectId());
    }

    private PurviewEntityPublishResult publishEntities(List<java.util.Map<String, Object>> entities) {
        try {
            return entityRegistryClient.bulkCreateOrUpdateEntities(
                    buildConnectionRequest(),
                    entityPayloadFactory.buildBulkPayload(entities));
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private PurviewConnectionRequest buildConnectionRequest() {
        return new PurviewConnectionRequest(
                purviewConfig.getEndpoint(),
                purviewConfig.getAtlasBasePath(),
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.getConnectTimeoutMs(),
                purviewConfig.getReadTimeoutMs());
    }

    private PurviewTombstoneState copyWithStatus(PurviewTombstoneState tombstoneState, String status) {
        return new PurviewTombstoneState(
                tombstoneState.getRepositoryId(),
                tombstoneState.getObjectId(),
                tombstoneState.getTypeName(),
                tombstoneState.getQualifiedName(),
                tombstoneState.getChangeToken(),
                tombstoneState.getFirstSeenAt(),
                tombstoneState.getDueAt(),
                status);
    }

    private String buildErrorSummary(RuntimeException e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getMessage();
    }

    private String summarizeFailures(List<String> failures) {
        if (failures.isEmpty()) {
            return "";
        }
        if (failures.size() == 1) {
            return failures.get(0);
        }
        return failures.size() + " archive reconciliations failed: " + failures.get(0);
    }
}
