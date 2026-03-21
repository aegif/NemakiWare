package jp.aegif.nemaki.rest.purview.publish;

import jp.aegif.nemaki.rest.purview.lineage.PurviewArchiveLineageService;
import jp.aegif.nemaki.rest.purview.sync.PurviewArchiveSyncResult;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterState;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewDocumentArchiveRelationshipService;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewArchivePublishServiceImpl implements PurviewArchivePublishService {

    private static final Logger logger = LoggerFactory.getLogger(PurviewArchivePublishServiceImpl.class);

    private static final int ARCHIVE_FETCH_PAGE_SIZE = 100;
    private static final int ENTITY_BATCH_SIZE = 100;
    private static final String ARCHIVE_TYPE_NAME = "nemaki_archive";
    private static final String DOCUMENT_TYPE_NAME = "nemaki_document";
    private static final String ARCHIVE_LINEAGE_STREAM_KIND = "archive-lineage";
    private static final String ARCHIVE_ENTITY_STREAM_KIND = "archive-entity";
    private static final String ARCHIVE_LINEAGE_TYPE_NAME = "nemaki_archive_process";
    private static final String UNIQUE_ATTRIBUTE_NAME = "qualifiedName";

    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final ContentDaoService contentDaoService;
    private final PurviewDocumentArchiveRelationshipService documentArchiveRelationshipService;
    private final PurviewDocumentPublishService documentPublishService;
    private final PurviewArchiveLineageService archiveLineageService;
    private final PurviewDeadLetterStateService deadLetterStateService;

    public PurviewArchivePublishServiceImpl(
            PurviewConfig purviewConfig,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewDocumentArchiveRelationshipService documentArchiveRelationshipService,
            PurviewDocumentPublishService documentPublishService,
            PurviewArchiveLineageService archiveLineageService,
            PurviewDeadLetterStateService deadLetterStateService) {
        this.purviewConfig = purviewConfig;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.contentDaoService = contentDaoService;
        this.documentArchiveRelationshipService = documentArchiveRelationshipService;
        this.documentPublishService = documentPublishService;
        this.archiveLineageService = archiveLineageService;
        this.deadLetterStateService = deadLetterStateService;
    }

    @Override
    public int publishRepositoryArchives(String repositoryId) {
        return publishArchives(repositoryId, loadValidArchives(repositoryId), "");
    }

    @Override
    public int retryRepositoryArchiveLineage(String repositoryId, String previousSnapshot) {
        return publishArchiveLineage(repositoryId, loadValidArchives(repositoryId));
    }

    @Override
    public String buildRepositoryArchiveSnapshot(String repositoryId) {
        return buildSnapshot(loadValidArchives(repositoryId));
    }

    @Override
    public PurviewArchiveSyncResult syncRepositoryArchivesIfChanged(String repositoryId, String previousSnapshot) {
        List<Archive> archives = loadValidArchives(repositoryId);
        String currentSnapshot = buildSnapshot(archives);
        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);
        if (Objects.equals(currentSnapshot, normalizedPreviousSnapshot)) {
            return new PurviewArchiveSyncResult(currentSnapshot, false, 0, 0);
        }

        int publishedCount = publishArchives(repositoryId, archives, normalizedPreviousSnapshot);
        int reconciledCount = reconcileMissingArchives(repositoryId, normalizedPreviousSnapshot, archives);
        return new PurviewArchiveSyncResult(currentSnapshot, true, publishedCount, reconciledCount);
    }

    private List<Archive> loadValidArchives(String repositoryId) {
        List<Archive> archives = new ArrayList<>();
        for (int skip = 0; ; skip += ARCHIVE_FETCH_PAGE_SIZE) {
            List<Archive> page = contentDaoService.getArchives(repositoryId, skip, ARCHIVE_FETCH_PAGE_SIZE, Boolean.FALSE);
            if (page == null || page.isEmpty()) {
                break;
            }

            page.stream()
                    .filter(Objects::nonNull)
                    .filter(archive -> archive.getId() != null && !archive.getId().isBlank())
                    .filter(archive -> archive.getOriginalId() != null && !archive.getOriginalId().isBlank())
                    .forEach(archives::add);

            if (page.size() < ARCHIVE_FETCH_PAGE_SIZE) {
                break;
            }
        }

        return archives.stream()
                .sorted(Comparator.comparing(Archive::getId))
                .toList();
    }

    private int publishArchives(String repositoryId, List<Archive> archives, String lineageCheckpoint) {
        List<Map<String, Object>> entityBatch = new ArrayList<>();
        List<Archive> relationshipCandidates = new ArrayList<>();
        int processedCount = 0;

        for (Archive archive : archives) {
            entityBatch.add(entityPayloadFactory.buildArchivedDocumentEntity(repositoryId, archive));
            processedCount += flushIfNeeded(repositoryId, entityBatch);
            entityBatch.add(entityPayloadFactory.buildArchiveEntity(repositoryId, archive));
            relationshipCandidates.add(archive);
            processedCount += flushIfNeeded(repositoryId, entityBatch);
        }

        int archiveEntityCount = processedCount
                + flushEntities(repositoryId, entityBatch)
                + documentArchiveRelationshipService.upsertDocumentArchiveRelationships(repositoryId, relationshipCandidates);
        publishArchiveLineageBestEffort(repositoryId, archives, lineageCheckpoint);
        return archiveEntityCount;
    }

    private int publishArchiveLineage(String repositoryId, List<Archive> archives) {
        if (archives == null || archives.isEmpty()) {
            return 0;
        }
        return archiveLineageService.upsertArchiveLineage(repositoryId, archives);
    }

    private void publishArchiveLineageBestEffort(String repositoryId, List<Archive> archives, String checkpoint) {
        try {
            publishArchiveLineage(repositoryId, archives);
            deadLetterStateService.deleteDeadLetterState(repositoryId, ARCHIVE_LINEAGE_STREAM_KIND, repositoryId);
        } catch (RuntimeException e) {
            deadLetterStateService.saveDeadLetterState(buildRepositoryDeadLetterState(
                    repositoryId,
                    ARCHIVE_LINEAGE_STREAM_KIND,
                    ARCHIVE_LINEAGE_TYPE_NAME,
                    checkpoint,
                    e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private int flushIfNeeded(List<Map<String, Object>> entities) {
        return flushIfNeeded(null, entities);
    }

    private int flushIfNeeded(String repositoryId, List<Map<String, Object>> entities) {
        if (entities.size() < ENTITY_BATCH_SIZE) {
            return 0;
        }
        return flushEntities(repositoryId, entities);
    }

    private int flushEntities(List<Map<String, Object>> entities) {
        return flushEntities(null, entities);
    }

    private int flushEntities(String repositoryId, List<Map<String, Object>> entities) {
        if (entities.isEmpty()) {
            return 0;
        }

        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    buildConnectionRequest(),
                    entityPayloadFactory.buildBulkPayload(entities));
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            if (result.hasFailures() && repositoryId != null) {
                for (PurviewEntityPublishResult.FailedItem failedItem : result.getFailedItems()) {
                    logger.warn("Purview bulk partial failure: qualifiedName={}, type={}, error={}",
                            failedItem.getQualifiedName(), failedItem.getTypeName(), failedItem.getErrorMessage());
                    deadLetterStateService.saveDeadLetterState(buildEntityDeadLetterState(
                            repositoryId,
                            failedItem.getQualifiedName(),
                            failedItem.getTypeName(),
                            failedItem.getErrorMessage()));
                }
            }
            int publishedCount = result.getPublishedCount();
            entities.clear();
            return publishedCount;
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private int reconcileMissingArchives(String repositoryId, String previousSnapshot, List<Archive> currentArchives) {
        Map<String, String> previousArchiveMap = parseSnapshot(previousSnapshot);
        Set<String> currentArchiveIds = currentArchives.stream()
                .map(Archive::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int reconciledCount = 0;
        for (Map.Entry<String, String> entry : previousArchiveMap.entrySet()) {
            String archiveId = entry.getKey();
            String originalId = entry.getValue();
            if (currentArchiveIds.contains(archiveId)) {
                continue;
            }

            reconciledCount += deleteEntity(ARCHIVE_TYPE_NAME, buildArchiveQualifiedName(repositoryId, archiveId));

            Content liveContent = contentDaoService.getContentFresh(repositoryId, originalId);
            if (liveContent != null && liveContent.isDocument()) {
                reconciledCount += documentPublishService.upsertContents(repositoryId, List.of(liveContent));
            } else {
                reconciledCount += deleteEntity(DOCUMENT_TYPE_NAME, buildObjectQualifiedName(repositoryId, originalId));
            }
        }
        return reconciledCount;
    }

    private int deleteEntity(String typeName, String qualifiedName) {
        try {
            PurviewEntityPublishResult result = entityRegistryClient.deleteByUniqueAttribute(
                    buildConnectionRequest(),
                    typeName,
                    UNIQUE_ATTRIBUTE_NAME,
                    qualifiedName);
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            return result.getPublishedCount();
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private Map<String, String> parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return Map.of();
        }

        LinkedHashMap<String, String> archives = new LinkedHashMap<>();
        for (String line : snapshot.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                archives.put(parts[0], parts[1]);
            }
        }
        return archives;
    }

    private String buildSnapshot(List<Archive> archives) {
        return archives.stream()
                .map(archive -> String.join("|",
                        archive.getId(),
                        archive.getOriginalId(),
                        nullToEmpty(archive.getEffectiveArchiveState()),
                        String.valueOf(resolveModifiedMillis(archive)),
                        String.valueOf(toEpochMillis(archive.getColdArchivedAt()))))
                .collect(Collectors.joining("\n"));
    }

    private long resolveModifiedMillis(Archive archive) {
        if (archive.getColdArchivedAt() != null) {
            return archive.getColdArchivedAt().getTimeInMillis();
        }
        if (archive.getArchivedAt() != null) {
            return archive.getArchivedAt().getTimeInMillis();
        }
        if (archive.getModified() != null) {
            return archive.getModified().getTimeInMillis();
        }
        if (archive.getCreated() != null) {
            return archive.getCreated().getTimeInMillis();
        }
        return 0L;
    }

    private long toEpochMillis(java.util.GregorianCalendar value) {
        return value == null ? 0L : value.getTimeInMillis();
    }

    private String buildArchiveQualifiedName(String repositoryId, String archiveId) {
        return "nemaki://" + repositoryId + "/archives/" + archiveId;
    }

    private String buildObjectQualifiedName(String repositoryId, String objectId) {
        return "nemaki://" + repositoryId + "/objects/" + objectId;
    }

    private String normalizeSnapshot(String snapshot) {
        return snapshot == null ? "" : snapshot;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private PurviewDeadLetterState buildEntityDeadLetterState(
            String repositoryId,
            String qualifiedName,
            String typeName,
            String errorSummary) {
        String entryKey = qualifiedName != null ? qualifiedName : "unknown";
        String now = java.time.Instant.now().toString();
        PurviewDeadLetterState existingState = deadLetterStateService.getDeadLetterState(
                repositoryId, ARCHIVE_ENTITY_STREAM_KIND, entryKey);
        String firstFailedAt = existingState == null || existingState.getFirstFailedAt() == null
                || existingState.getFirstFailedAt().isBlank()
                        ? now
                        : existingState.getFirstFailedAt();
        int failureCount = existingState == null ? 1 : Math.max(0, existingState.getFailureCount()) + 1;
        return new PurviewDeadLetterState(
                repositoryId,
                ARCHIVE_ENTITY_STREAM_KIND,
                entryKey,
                typeName != null ? typeName : "",
                qualifiedName != null ? qualifiedName : "",
                firstFailedAt,
                now,
                failureCount,
                "",
                errorSummary != null ? errorSummary : "");
    }

    private PurviewDeadLetterState buildRepositoryDeadLetterState(
            String repositoryId,
            String streamKind,
            String typeName,
            String checkpoint,
            String errorSummary) {
        String now = java.time.Instant.now().toString();
        PurviewDeadLetterState existingState = deadLetterStateService.getDeadLetterState(repositoryId, streamKind, repositoryId);
        String firstFailedAt = existingState == null || existingState.getFirstFailedAt() == null
                || existingState.getFirstFailedAt().isBlank()
                        ? now
                        : existingState.getFirstFailedAt();
        int failureCount = existingState == null ? 1 : Math.max(0, existingState.getFailureCount()) + 1;
        return new PurviewDeadLetterState(
                repositoryId,
                streamKind,
                repositoryId,
                typeName,
                "nemaki://" + repositoryId + "/purview/" + streamKind,
                firstFailedAt,
                now,
                failureCount,
                checkpoint == null ? "" : checkpoint,
                errorSummary);
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
}
