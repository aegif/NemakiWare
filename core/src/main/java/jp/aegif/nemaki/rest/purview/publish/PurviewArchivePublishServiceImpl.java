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
import java.util.HashMap;
import java.util.HashSet;
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
        Map<String, String> guidAccumulator = new HashMap<>();
        Set<String> failedQualifiedNames = new HashSet<>();
        int processedCount = 0;

        for (Archive archive : archives) {
            entityBatch.add(entityPayloadFactory.buildArchivedDocumentEntity(repositoryId, archive));
            processedCount += flushIfNeeded(repositoryId, entityBatch, guidAccumulator, failedQualifiedNames);
            entityBatch.add(entityPayloadFactory.buildArchiveEntity(repositoryId, archive));
            relationshipCandidates.add(archive);
            processedCount += flushIfNeeded(repositoryId, entityBatch, guidAccumulator, failedQualifiedNames);
        }

        processedCount += flushEntities(repositoryId, entityBatch, guidAccumulator, failedQualifiedNames);
        pruneFailedArchiveCandidates(repositoryId, relationshipCandidates, failedQualifiedNames);
        int archiveEntityCount = processedCount
                + documentArchiveRelationshipService.upsertDocumentArchiveRelationships(repositoryId, relationshipCandidates, guidAccumulator);
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
        return flushIfNeeded(null, entities, null, null);
    }

    private int flushIfNeeded(String repositoryId, List<Map<String, Object>> entities,
            Map<String, String> guidAccumulator, Set<String> failedQualifiedNames) {
        if (entities.size() < ENTITY_BATCH_SIZE) {
            return 0;
        }
        return flushEntities(repositoryId, entities, guidAccumulator, failedQualifiedNames);
    }

    private int flushEntities(List<Map<String, Object>> entities) {
        return flushEntities(null, entities, null, null);
    }

    private int flushEntities(String repositoryId, List<Map<String, Object>> entities,
            Map<String, String> guidAccumulator, Set<String> failedQualifiedNames) {
        if (entities.isEmpty()) {
            return 0;
        }

        List<SentEntityRef> sentRefs = extractSentEntityRefs(entities);

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
                    if (failedQualifiedNames != null && failedItem.getQualifiedName() != null) {
                        failedQualifiedNames.add(failedItem.getQualifiedName());
                    }
                }
            }
            if (guidAccumulator != null && result.getEntityGuids() != null) {
                guidAccumulator.putAll(result.getEntityGuids());
            }
            if (guidAccumulator != null && purviewConfig.isAtlasOnPrem()) {
                resolveUnmappedGuids(sentRefs, guidAccumulator, result.getEntityGuids());
            }
            int publishedCount = result.getPublishedCount();
            entities.clear();
            return publishedCount;
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void resolveUnmappedGuids(
            List<SentEntityRef> sentRefs,
            Map<String, String> guidAccumulator,
            Map<String, String> responseGuids) {
        for (SentEntityRef ref : sentRefs) {
            if (ref.qualifiedName == null || ref.typeName == null) {
                continue;
            }
            if (responseGuids != null && responseGuids.containsKey(ref.qualifiedName)) {
                continue;
            }
            if (guidAccumulator.containsKey(ref.qualifiedName)) {
                continue;
            }
            try {
                Map<String, Object> entity = entityRegistryClient.getEntityByUniqueAttribute(
                        buildConnectionRequest(),
                        ref.typeName,
                        "qualifiedName",
                        ref.qualifiedName);
                if (entity != null) {
                    Object entityObj = entity.get("entity");
                    String guid = null;
                    if (entityObj instanceof Map<?, ?> entityMap) {
                        Object g = entityMap.get("guid");
                        if (g != null) guid = String.valueOf(g);
                    }
                    if (guid == null) {
                        Object g = entity.get("guid");
                        if (g != null) guid = String.valueOf(g);
                    }
                    if (guid != null) {
                        guidAccumulator.put(ref.qualifiedName, guid);
                        logger.debug("Atlas GUID follow-up resolved: {} → {}", ref.qualifiedName, guid);
                    }
                }
            } catch (PurviewClientException e) {
                logger.debug("Atlas GUID follow-up lookup failed for {}: {}", ref.qualifiedName, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<SentEntityRef> extractSentEntityRefs(List<Map<String, Object>> entities) {
        List<SentEntityRef> refs = new ArrayList<>();
        for (Map<String, Object> entity : entities) {
            String typeName = entity.get("typeName") instanceof String t ? t : null;
            String qualifiedName = null;
            Object attrs = entity.get("attributes");
            if (attrs instanceof Map<?, ?> attrMap) {
                Object qn = attrMap.get("qualifiedName");
                if (qn instanceof String s) qualifiedName = s;
            }
            refs.add(new SentEntityRef(typeName, qualifiedName));
        }
        return refs;
    }

    private record SentEntityRef(String typeName, String qualifiedName) {
    }

    /**
     * Removes Archive items from the candidate list whose entity upsert failed.
     * An archive produces two entities (archived document + archive record);
     * if either failed, the archive is removed from relationship candidates.
     */
    private void pruneFailedArchiveCandidates(String repositoryId, List<Archive> candidates, Set<String> failedQualifiedNames) {
        if (failedQualifiedNames == null || failedQualifiedNames.isEmpty()) {
            return;
        }
        candidates.removeIf(archive -> {
            if (archive == null) {
                return false;
            }
            String docQN = entityPayloadFactory.buildObjectQualifiedName(repositoryId, archive.getOriginalId());
            String archiveQN = buildArchiveQualifiedName(repositoryId, archive.getId());
            return failedQualifiedNames.contains(docQN) || failedQualifiedNames.contains(archiveQN);
        });
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
                purviewConfig.getAuthType(),
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.getBasicUsername(),
                purviewConfig.getBasicPassword(),
                purviewConfig.getConnectTimeoutMs(),
                purviewConfig.getReadTimeoutMs());
    }
}
