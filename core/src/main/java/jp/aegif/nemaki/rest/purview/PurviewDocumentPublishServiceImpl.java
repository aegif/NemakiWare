package jp.aegif.nemaki.rest.purview;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewDocumentPublishServiceImpl implements PurviewDocumentPublishService {

    private static final int CHILD_FETCH_PAGE_SIZE = 100;
    private static final int ENTITY_BATCH_SIZE = 100;

    private final PurviewConfig purviewConfig;
    private final RepositoryInfoMap repositoryInfoMap;
    private final ContentDaoService contentDaoService;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final PurviewContainmentRelationshipService containmentRelationshipService;
    private final PurviewDocumentTypeRelationshipService documentTypeRelationshipService;

    public PurviewDocumentPublishServiceImpl(
            PurviewConfig purviewConfig,
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            PurviewContainmentRelationshipService containmentRelationshipService,
            PurviewDocumentTypeRelationshipService documentTypeRelationshipService) {
        this.purviewConfig = purviewConfig;
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.containmentRelationshipService = containmentRelationshipService;
        this.documentTypeRelationshipService = documentTypeRelationshipService;
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
        processedCount += flushIfNeeded(entityBatch);

        Content rootFolder = contentDaoService.getContent(repositoryId, rootFolderId);
        if (rootFolder == null || !rootFolder.isFolder()) {
            throw new IllegalStateException("Root folder content is not available for repository " + repositoryId);
        }
        entityBatch.add(entityPayloadFactory.buildFolderEntity(repositoryId, rootFolder));
        containmentCandidates.add(rootFolder);
        processedCount += flushIfNeeded(entityBatch);

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
                        processedCount += flushIfNeeded(entityBatch);
                        folderQueue.addLast(child.getId());
                        continue;
                    }
                    if (!child.isDocument()) {
                        continue;
                    }

                    entityBatch.add(entityPayloadFactory.buildDocumentEntity(repositoryId, child));
                    containmentCandidates.add(child);
                    relationshipCandidates.add(child);
                    processedCount += flushIfNeeded(entityBatch);
                }
            }
        }

        return processedCount
                + flushEntities(entityBatch)
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
            processedCount += flushIfNeeded(pending);
        }
        return processedCount
                + flushEntities(pending)
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

    private int flushIfNeeded(List<Map<String, Object>> entities) {
        if (entities.size() < ENTITY_BATCH_SIZE) {
            return 0;
        }
        return flushEntities(entities);
    }

    private int flushEntities(List<Map<String, Object>> entities) {
        if (entities.isEmpty()) {
            return 0;
        }

        PurviewConnectionRequest request = buildConnectionRequest();
        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    request,
                    entityPayloadFactory.buildBulkPayload(entities));
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            int publishedCount = entities.size();
            entities.clear();
            return publishedCount;
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
