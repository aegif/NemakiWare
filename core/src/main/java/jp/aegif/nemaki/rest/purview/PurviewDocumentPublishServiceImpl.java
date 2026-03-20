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

    public PurviewDocumentPublishServiceImpl(
            PurviewConfig purviewConfig,
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.purviewConfig = purviewConfig;
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public int publishRepositoryDocuments(String repositoryId) {
        String rootFolderId = resolveRootFolderId(repositoryId);
        Deque<String> folderQueue = new ArrayDeque<>();
        folderQueue.add(rootFolderId);
        List<Content> documentBatch = new ArrayList<>();
        int processedCount = 0;

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
                        folderQueue.addLast(child.getId());
                        continue;
                    }
                    if (!child.isDocument()) {
                        continue;
                    }

                    documentBatch.add(child);
                    processedCount += flushIfNeeded(repositoryId, documentBatch);
                }
            }
        }

        return processedCount + flushDocuments(repositoryId, documentBatch);
    }

    @Override
    public int upsertDocuments(String repositoryId, List<Content> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }

        List<Content> pending = new ArrayList<>();
        int processedCount = 0;
        for (Content document : documents) {
            if (document == null || !document.isDocument()) {
                continue;
            }
            pending.add(document);
            processedCount += flushIfNeeded(repositoryId, pending);
        }
        return processedCount + flushDocuments(repositoryId, pending);
    }

    private int flushIfNeeded(String repositoryId, List<Content> documents) {
        if (documents.size() < ENTITY_BATCH_SIZE) {
            return 0;
        }
        return flushDocuments(repositoryId, documents);
    }

    private int flushDocuments(String repositoryId, List<Content> documents) {
        if (documents.isEmpty()) {
            return 0;
        }

        PurviewConnectionRequest request = buildConnectionRequest();
        List<Map<String, Object>> entities = documents.stream()
                .map(document -> entityPayloadFactory.buildDocumentEntity(repositoryId, document))
                .toList();
        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    request,
                    entityPayloadFactory.buildBulkPayload(entities));
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            int publishedCount = entities.size();
            documents.clear();
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
        RepositoryInfo repositoryInfo = repositoryInfoMap.get(repositoryId);
        if (repositoryInfo == null || repositoryInfo.getRootFolderId() == null || repositoryInfo.getRootFolderId().isBlank()) {
            throw new IllegalStateException("Root folder ID is not configured for repository " + repositoryId);
        }
        return repositoryInfo.getRootFolderId();
    }
}
