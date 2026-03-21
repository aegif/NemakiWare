package jp.aegif.nemaki.rest.purview.sync;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockStateService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerService;
import jp.aegif.nemaki.rest.purview.state.PurviewTombstoneState;
import jp.aegif.nemaki.rest.purview.state.PurviewTombstoneStateService;
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
public class PurviewDeleteResolutionServiceImpl implements PurviewDeleteResolutionService {

    private static final String JOB_KIND = "DELETE_RESOLUTION";
    private static final String TOMBSTONE_STATUS_ARCHIVED = "ARCHIVED";
    private static final String TOMBSTONE_STATUS_ERROR = "ERROR";
    private static final String UNIQUE_ATTRIBUTE_NAME = "qualifiedName";

    private final PurviewConfig purviewConfig;
    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewLockStateService lockStateService;
    private final PurviewJobStateService jobStateService;
    private final PurviewTombstoneStateService tombstoneStateService;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final ContentDaoService contentDaoService;

    public PurviewDeleteResolutionServiceImpl(
            PurviewConfig purviewConfig,
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewLockStateService lockStateService,
            PurviewJobStateService jobStateService,
            PurviewTombstoneStateService tombstoneStateService,
            PurviewEntityRegistryClient entityRegistryClient,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService) {
        this.purviewConfig = purviewConfig;
        this.schemaPlannerService = schemaPlannerService;
        this.lockStateService = lockStateService;
        this.jobStateService = jobStateService;
        this.tombstoneStateService = tombstoneStateService;
        this.entityRegistryClient = entityRegistryClient;
        this.contentDaoService = contentDaoService;
    }

    @Override
    public PurviewJobState startDeleteResolution(String repositoryId, String requestedBy) {
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
                    "Purview delete resolution is already running for repository " + repositoryId));
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
                        "Purview schema bootstrap is required before delete resolution"));
            }

            List<PurviewTombstoneState> dueTombstones = tombstoneStateService.listDueTombstoneStates(
                    repositoryId,
                    Instant.parse(now));
            int failedCount = 0;
            List<String> failures = new ArrayList<>();

            for (PurviewTombstoneState tombstoneState : dueTombstones) {
                try {
                    resolveTombstone(tombstoneState);
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
                    dueTombstones.size(),
                    failedCount,
                    dueTombstones.isEmpty() ? "" : dueTombstones.get(dueTombstones.size() - 1).getChangeToken(),
                    summarizeFailures(failures)));
        } finally {
            lockStateService.releaseRepositoryLock(repositoryId, JOB_KIND, jobId);
        }
    }

    private void resolveTombstone(PurviewTombstoneState tombstoneState) {
        String repositoryId = tombstoneState.getRepositoryId();
        String objectId = tombstoneState.getObjectId();

        Content liveContent = contentDaoService.getContentFresh(repositoryId, objectId);
        if (liveContent != null) {
            tombstoneStateService.deleteTombstoneState(repositoryId, objectId);
            return;
        }

        if (isDocumentTombstone(tombstoneState)) {
            Archive archive = contentDaoService.getArchiveByOriginalId(repositoryId, objectId);
            if (archive != null) {
                tombstoneStateService.saveTombstoneState(copyWithStatus(tombstoneState, TOMBSTONE_STATUS_ARCHIVED));
                return;
            }
        }

        PurviewEntityPublishResult result = deletePurviewEntity(tombstoneState);
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getMessage());
        }
        tombstoneStateService.deleteTombstoneState(repositoryId, objectId);
    }

    private PurviewEntityPublishResult deletePurviewEntity(PurviewTombstoneState tombstoneState) {
        if (tombstoneState.getTypeName().isBlank() || tombstoneState.getQualifiedName().isBlank()) {
            throw new IllegalStateException("Purview tombstone is missing typeName or qualifiedName");
        }
        try {
            return entityRegistryClient.deleteByUniqueAttribute(
                    buildConnectionRequest(),
                    tombstoneState.getTypeName(),
                    UNIQUE_ATTRIBUTE_NAME,
                    tombstoneState.getQualifiedName());
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
        return failures.size() + " tombstones failed: " + failures.get(0);
    }

    private boolean isDocumentTombstone(PurviewTombstoneState tombstoneState) {
        return "nemaki_document".equals(tombstoneState.getTypeName());
    }
}
