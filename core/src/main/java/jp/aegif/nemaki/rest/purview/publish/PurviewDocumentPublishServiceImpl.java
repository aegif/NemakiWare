package jp.aegif.nemaki.rest.purview.publish;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.relationship.PurviewContainmentRelationshipService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewDocumentTypeRelationshipService;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterState;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewDocumentPublishServiceImpl implements PurviewDocumentPublishService {

    private static final Logger logger = LoggerFactory.getLogger(PurviewDocumentPublishServiceImpl.class);

    private static final int CHILD_FETCH_PAGE_SIZE = 100;
    private static final int ENTITY_BATCH_SIZE = 100;
    private static final String DOCUMENT_ENTITY_STREAM_KIND = "document-entity";

    private final PurviewConfig purviewConfig;
    private final RepositoryInfoMap repositoryInfoMap;
    private final ContentDaoService contentDaoService;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final PurviewContainmentRelationshipService containmentRelationshipService;
    private final PurviewDocumentTypeRelationshipService documentTypeRelationshipService;
    private final PurviewDeadLetterStateService deadLetterStateService;

    public PurviewDocumentPublishServiceImpl(
            PurviewConfig purviewConfig,
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            PurviewContainmentRelationshipService containmentRelationshipService,
            PurviewDocumentTypeRelationshipService documentTypeRelationshipService,
            PurviewDeadLetterStateService deadLetterStateService) {
        this.purviewConfig = purviewConfig;
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.containmentRelationshipService = containmentRelationshipService;
        this.documentTypeRelationshipService = documentTypeRelationshipService;
        this.deadLetterStateService = deadLetterStateService;
    }

    @Override
    public int publishRepositoryHierarchy(String repositoryId) {
        RepositoryInfo repositoryInfo = resolveRepositoryInfo(repositoryId);
        String rootFolderId = resolveRootFolderId(repositoryId);
        Deque<String> folderQueue = new ArrayDeque<>();
        folderQueue.add(rootFolderId);
        List<Map<String, Object>> entityBatch = new ArrayList<>();
        List<Content> containmentCandidates = new ArrayList<>();
        List<Content> relationshipCandidates = new ArrayList<>();
        int processedCount = 0;

        entityBatch.add(entityPayloadFactory.buildRepositoryEntity(repositoryInfo));
        processedCount += flushIfNeeded(repositoryId, entityBatch);

        Content rootFolder = contentDaoService.getContent(repositoryId, rootFolderId);
        if (rootFolder == null || !rootFolder.isFolder()) {
            throw new IllegalStateException("Root folder content is not available for repository " + repositoryId);
        }
        entityBatch.add(entityPayloadFactory.buildFolderEntity(repositoryId, rootFolder));
        containmentCandidates.add(rootFolder);
        processedCount += flushIfNeeded(repositoryId, entityBatch);

        while (!folderQueue.isEmpty()) {
            String folderId = folderQueue.removeFirst();
            long totalChildren = Math.max(0L, contentDaoService.getChildrenCount(repositoryId, folderId));
            for (int skip = 0; skip < totalChildren; skip += CHILD_FETCH_PAGE_SIZE) {
                List<Content> children = contentDaoService.getChildrenPaged(
                        repositoryId,
                        folderId,
                        skip,
                        CHILD_FETCH_PAGE_SIZE);
                if (children == null || children.isEmpty()) {
                    break;
                }

                for (Content child : children) {
                    if (child == null || child.getId() == null || child.getId().isBlank()) {
                        continue;
                    }

                    if (child.isFolder()) {
                        entityBatch.add(entityPayloadFactory.buildFolderEntity(repositoryId, child));
                        containmentCandidates.add(child);
                        processedCount += flushIfNeeded(repositoryId, entityBatch);
                        folderQueue.addLast(child.getId());
                        continue;
                    }
                    if (!child.isDocument()) {
                        continue;
                    }

                    entityBatch.add(entityPayloadFactory.buildDocumentEntity(repositoryId, child));
                    containmentCandidates.add(child);
                    relationshipCandidates.add(child);
                    processedCount += flushIfNeeded(repositoryId, entityBatch);
                }
            }
        }

        return processedCount
                + flushEntities(repositoryId, entityBatch)
                + containmentRelationshipService.upsertContainmentRelationships(repositoryId, containmentCandidates)
                + documentTypeRelationshipService.upsertDocumentTypeRelationships(repositoryId, relationshipCandidates);
    }

    @Override
    public int publishRepositoryDocuments(String repositoryId) {
        return publishRepositoryHierarchy(repositoryId);
    }

    @Override
    public int upsertContents(String repositoryId, List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        List<Map<String, Object>> pending = new ArrayList<>();
        List<Content> containmentCandidates = new ArrayList<>();
        List<Content> relationshipCandidates = new ArrayList<>();
        int processedCount = 0;
        for (Content content : contents) {
            Map<String, Object> entity = buildContentEntity(repositoryId, content);
            if (entity == null) {
                continue;
            }
            pending.add(entity);
            containmentCandidates.add(content);
            if (content != null && content.isDocument()) {
                relationshipCandidates.add(content);
            }
            processedCount += flushIfNeeded(repositoryId, pending);
        }
        return processedCount
                + flushEntities(repositoryId, pending)
                + containmentRelationshipService.upsertContainmentRelationships(repositoryId, containmentCandidates)
                + documentTypeRelationshipService.upsertDocumentTypeRelationships(repositoryId, relationshipCandidates);
    }

    @Override
    public int upsertDocuments(String repositoryId, List<Content> documents) {
        return upsertContents(repositoryId, documents);
    }

    private Map<String, Object> buildContentEntity(String repositoryId, Content content) {
        if (content == null) {
            return null;
        }
        if (content.isFolder()) {
            return entityPayloadFactory.buildFolderEntity(repositoryId, content);
        }
        if (content.isDocument()) {
            return entityPayloadFactory.buildDocumentEntity(repositoryId, content);
        }
        return null;
    }

    private int flushIfNeeded(String repositoryId, List<Map<String, Object>> entities) {
        if (entities.size() < ENTITY_BATCH_SIZE) {
            return 0;
        }
        return flushEntities(repositoryId, entities);
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

    private PurviewDeadLetterState buildEntityDeadLetterState(
            String repositoryId,
            String qualifiedName,
            String typeName,
            String errorSummary) {
        String entryKey = qualifiedName != null ? qualifiedName : "unknown";
        String now = Instant.now().toString();
        PurviewDeadLetterState existingState = deadLetterStateService.getDeadLetterState(
                repositoryId, DOCUMENT_ENTITY_STREAM_KIND, entryKey);
        String firstFailedAt = existingState == null || existingState.getFirstFailedAt() == null
                || existingState.getFirstFailedAt().isBlank()
                        ? now
                        : existingState.getFirstFailedAt();
        int failureCount = existingState == null ? 1 : Math.max(0, existingState.getFailureCount()) + 1;
        return new PurviewDeadLetterState(
                repositoryId,
                DOCUMENT_ENTITY_STREAM_KIND,
                entryKey,
                typeName != null ? typeName : "",
                qualifiedName != null ? qualifiedName : "",
                firstFailedAt,
                now,
                failureCount,
                "",
                errorSummary != null ? errorSummary : "");
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

    private String resolveRootFolderId(String repositoryId) {
        RepositoryInfo repositoryInfo = resolveRepositoryInfo(repositoryId);
        if (repositoryInfo == null || repositoryInfo.getRootFolderId() == null || repositoryInfo.getRootFolderId().isBlank()) {
            throw new IllegalStateException("Root folder ID is not configured for repository " + repositoryId);
        }
        return repositoryInfo.getRootFolderId();
    }

    private RepositoryInfo resolveRepositoryInfo(String repositoryId) {
        return repositoryInfoMap.get(repositoryId);
    }
}
